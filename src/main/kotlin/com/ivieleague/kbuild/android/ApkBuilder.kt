package com.ivieleague.kbuild.android

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Low-level APK building using Android SDK tools.
 */
class ApkBuilder(
    val sdk: AndroidSdk,
    val buildToolsVersion: String = sdk.latestBuildToolsVersion ?: error("No build-tools installed"),
    val targetSdk: Int = sdk.latestPlatformVersion ?: error("No platforms installed"),
    val verbose: Boolean = false
) {
    private val aapt2 = sdk.aapt2(buildToolsVersion)
    private val d8 = sdk.d8(buildToolsVersion)
    private val zipalign = sdk.zipalign(buildToolsVersion)
    private val apksigner = sdk.apksigner(buildToolsVersion)
    private val androidJar = sdk.androidJar(targetSdk)

    private data class CommandResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int
    )

    private fun runCommand(vararg command: String, timeoutSeconds: Long = 300): CommandResult {
        if (verbose) {
            println("$ ${command.joinToString(" ")}")
        }

        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CommandResult(false, "Command timed out after $timeoutSeconds seconds", -1)
        }

        val exitCode = process.exitValue()

        if (verbose && output.isNotEmpty()) {
            println(output)
        }

        return CommandResult(
            success = exitCode == 0,
            output = output,
            exitCode = exitCode
        )
    }

    fun compileResources(resourcesDir: File, outputDir: File): List<File> {
        if (!resourcesDir.exists()) {
            return emptyList()
        }

        outputDir.mkdirs()

        val flatFiles = mutableListOf<File>()
        val resourceTypes = resourcesDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        for (typeDir in resourceTypes) {
            val files = typeDir.listFiles { f -> f.isFile && !f.name.startsWith(".") } ?: continue

            for (file in files) {
                val result = runCommand(
                    aapt2.absolutePath,
                    "compile",
                    "-o", outputDir.absolutePath,
                    file.absolutePath
                )

                if (!result.success) {
                    throw RuntimeException("aapt2 compile failed for $file: ${result.output}")
                }

                val actualFlat = outputDir.listFiles { f ->
                    f.name.endsWith(".flat") && f.name.contains(file.nameWithoutExtension)
                }?.firstOrNull()

                if (actualFlat != null) {
                    flatFiles.add(actualFlat)
                }
            }
        }

        if (verbose) {
            println("Compiled ${flatFiles.size} resource files")
        }

        return flatFiles
    }

    fun linkResources(
        flatFiles: List<File>,
        manifest: File,
        outputApk: File,
        minSdk: Int = 21,
        generateR: Boolean = false,
        rJavaOutputDir: File? = null
    ): File {
        require(manifest.exists()) { "Manifest not found: $manifest" }

        outputApk.parentFile?.mkdirs()

        val args = mutableListOf(
            aapt2.absolutePath,
            "link",
            "-o", outputApk.absolutePath,
            "-I", androidJar.absolutePath,
            "--manifest", manifest.absolutePath,
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--auto-add-overlay"
        )

        if (generateR && rJavaOutputDir != null) {
            rJavaOutputDir.mkdirs()
            args.add("--java")
            args.add(rJavaOutputDir.absolutePath)
        }

        flatFiles.forEach { args.add(it.absolutePath) }

        val result = runCommand(*args.toTypedArray())

        if (!result.success) {
            throw RuntimeException("aapt2 link failed: ${result.output}")
        }

        if (verbose) {
            println("Linked resources to $outputApk")
        }

        return outputApk
    }

    fun compileDex(
        classesDir: File,
        outputDir: File,
        libraries: List<File> = emptyList(),
        minSdk: Int = 21
    ): File {
        require(classesDir.exists()) { "Classes directory not found: $classesDir" }

        outputDir.mkdirs()

        val classFiles = classesDir.walkTopDown()
            .filter { it.extension == "class" }
            .toList()

        if (classFiles.isEmpty()) {
            throw IllegalArgumentException("No .class files found in $classesDir")
        }

        val args = mutableListOf(
            d8.absolutePath,
            "--output", outputDir.absolutePath,
            "--min-api", minSdk.toString(),
            "--lib", androidJar.absolutePath
        )

        libraries.forEach { lib ->
            if (lib.exists()) {
                args.add("--classpath")
                args.add(lib.absolutePath)
            }
        }

        classFiles.forEach { args.add(it.absolutePath) }

        val result = runCommand(*args.toTypedArray())

        if (!result.success) {
            throw RuntimeException("d8 compilation failed: ${result.output}")
        }

        val dexFile = outputDir.resolve("classes.dex")
        if (!dexFile.exists()) {
            throw RuntimeException("d8 did not produce classes.dex")
        }

        if (verbose) {
            println("Compiled ${classFiles.size} class files to $dexFile")
        }

        return dexFile
    }

    fun dexJars(
        jarFiles: List<File>,
        outputDir: File,
        minSdk: Int = 21
    ): File {
        require(jarFiles.isNotEmpty()) { "No JAR files provided" }
        jarFiles.forEach { require(it.exists()) { "JAR not found: $it" } }

        outputDir.mkdirs()

        val args = mutableListOf(
            d8.absolutePath,
            "--output", outputDir.absolutePath,
            "--min-api", minSdk.toString(),
            "--lib", androidJar.absolutePath
        )

        jarFiles.forEach { args.add(it.absolutePath) }

        val result = runCommand(*args.toTypedArray())

        if (!result.success) {
            throw RuntimeException("d8 failed: ${result.output}")
        }

        val dexFile = outputDir.resolve("classes.dex")
        if (!dexFile.exists()) {
            throw RuntimeException("d8 did not produce classes.dex")
        }

        return dexFile
    }

    fun addDexToApk(apk: File, vararg dexFiles: File) {
        require(apk.exists()) { "APK not found: $apk" }
        dexFiles.forEach { require(it.exists()) { "DEX not found: $it" } }

        val tempApk = apk.parentFile.resolve("${apk.nameWithoutExtension}_temp.apk")

        ZipFile(apk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(tempApk)).use { zipOut ->
                sourceZip.entries().asSequence().forEach { entry ->
                    if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) {
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        sourceZip.getInputStream(entry).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }

                dexFiles.forEach { dexFile ->
                    zipOut.putNextEntry(ZipEntry(dexFile.name))
                    FileInputStream(dexFile).use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }

        apk.delete()
        tempApk.renameTo(apk)

        if (verbose) {
            println("Added ${dexFiles.size} DEX file(s) to $apk")
        }
    }

    fun addNativeLibs(apk: File, nativeLibsDir: File) {
        if (!nativeLibsDir.exists()) return

        val tempApk = apk.parentFile.resolve("${apk.nameWithoutExtension}_temp.apk")

        ZipFile(apk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(tempApk)).use { zipOut ->
                sourceZip.entries().asSequence().forEach { entry ->
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    sourceZip.getInputStream(entry).use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }

                nativeLibsDir.walkTopDown()
                    .filter { it.isFile && it.extension == "so" }
                    .forEach { soFile ->
                        val relativePath = soFile.relativeTo(nativeLibsDir).path
                        val entryName = "lib/$relativePath"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(soFile).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
            }
        }

        apk.delete()
        tempApk.renameTo(apk)

        if (verbose) {
            println("Added native libraries to $apk")
        }
    }

    fun addAssets(apk: File, assetsDir: File) {
        if (!assetsDir.exists()) return

        val tempApk = apk.parentFile.resolve("${apk.nameWithoutExtension}_temp.apk")

        ZipFile(apk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(tempApk)).use { zipOut ->
                sourceZip.entries().asSequence().forEach { entry ->
                    if (!entry.name.startsWith("assets/")) {
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        sourceZip.getInputStream(entry).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }

                assetsDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val entryName = "assets/${file.relativeTo(assetsDir).path}"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
            }
        }

        apk.delete()
        tempApk.renameTo(apk)

        if (verbose) {
            println("Added assets to $apk")
        }
    }

    fun align(inputApk: File, outputApk: File): File {
        require(inputApk.exists()) { "Input APK not found: $inputApk" }

        outputApk.parentFile?.mkdirs()

        if (outputApk.exists()) {
            outputApk.delete()
        }

        val result = runCommand(
            zipalign.absolutePath,
            "-v",
            "-p",
            "4",
            inputApk.absolutePath,
            outputApk.absolutePath
        )

        if (!result.success) {
            throw RuntimeException("zipalign failed: ${result.output}")
        }

        if (verbose) {
            println("Aligned APK: $outputApk")
        }

        return outputApk
    }

    fun verifyAlignment(apk: File): Boolean {
        val result = runCommand(
            zipalign.absolutePath,
            "-c",
            "-v",
            "4",
            apk.absolutePath
        )
        return result.success
    }

    fun sign(inputApk: File, keystore: KeystoreConfig, outputApk: File): File {
        require(inputApk.exists()) { "Input APK not found: $inputApk" }
        require(keystore.file.exists()) { "Keystore not found: ${keystore.file}" }

        outputApk.parentFile?.mkdirs()

        val args = mutableListOf(
            apksigner.absolutePath,
            "sign",
            "--ks", keystore.file.absolutePath,
            "--ks-pass", "pass:${keystore.storePassword}",
            "--ks-key-alias", keystore.alias,
            "--key-pass", "pass:${keystore.keyPassword}",
            "--out", outputApk.absolutePath,
            inputApk.absolutePath
        )

        val result = runCommand(*args.toTypedArray())

        if (!result.success) {
            throw RuntimeException("apksigner failed: ${result.output}")
        }

        if (verbose) {
            println("Signed APK: $outputApk")
        }

        return outputApk
    }

    fun verifySignature(apk: File): SignatureVerification {
        val result = runCommand(
            apksigner.absolutePath,
            "verify",
            "-v",
            "--print-certs",
            apk.absolutePath
        )

        return SignatureVerification(
            valid = result.success,
            output = result.output
        )
    }

    data class SignatureVerification(
        val valid: Boolean,
        val output: String
    )

    data class KeystoreConfig(
        val file: File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String = storePassword
    )

    companion object {
        fun debugKeystore(projectDir: File): KeystoreConfig {
            val keystoreDir = projectDir.resolve("keystore")
            keystoreDir.mkdirs()

            val keystoreFile = keystoreDir.resolve("debug.keystore")

            if (!keystoreFile.exists()) {
                createDebugKeystore(keystoreFile)
            }

            return KeystoreConfig(
                file = keystoreFile,
                storePassword = "android",
                alias = "androiddebugkey",
                keyPassword = "android"
            )
        }

        private fun createDebugKeystore(keystoreFile: File) {
            keystoreFile.parentFile?.mkdirs()

            // Use keytool to create the debug keystore
            val keytool = findKeytool()

            val process = ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias", "androiddebugkey",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-keystore", keystoreFile.absolutePath,
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Android Debug,O=Android,C=US"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Failed to create debug keystore: $output")
            }
        }

        private fun findKeytool(): String {
            // Try JAVA_HOME first
            val javaHome = System.getenv("JAVA_HOME")
            if (javaHome != null) {
                val keytool = File(javaHome, "bin/keytool")
                if (keytool.exists()) return keytool.absolutePath

                val keytoolExe = File(javaHome, "bin/keytool.exe")
                if (keytoolExe.exists()) return keytoolExe.absolutePath
            }

            // Try system PATH
            return "keytool"
        }
    }
}
