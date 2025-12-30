package com.ivieleague.kbuild.native

import com.ivieleague.kbuild.kotlin.Kotlin
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Kotlin/Native target platforms.
 */
enum class KonanTarget(
    val targetName: String,
    val family: TargetFamily
) {
    // macOS
    MACOS_X64("macos_x64", TargetFamily.OSX),
    MACOS_ARM64("macos_arm64", TargetFamily.OSX),

    // iOS
    IOS_ARM64("ios_arm64", TargetFamily.IOS),
    IOS_SIMULATOR_ARM64("ios_simulator_arm64", TargetFamily.IOS),
    IOS_X64("ios_x64", TargetFamily.IOS),

    // watchOS
    WATCHOS_ARM64("watchos_arm64", TargetFamily.WATCHOS),
    WATCHOS_SIMULATOR_ARM64("watchos_simulator_arm64", TargetFamily.WATCHOS),

    // tvOS
    TVOS_ARM64("tvos_arm64", TargetFamily.TVOS),
    TVOS_SIMULATOR_ARM64("tvos_simulator_arm64", TargetFamily.TVOS),

    // Linux
    LINUX_X64("linux_x64", TargetFamily.LINUX),
    LINUX_ARM64("linux_arm64", TargetFamily.LINUX),

    // Windows
    MINGW_X64("mingw_x64", TargetFamily.MINGW),

    // Android Native
    ANDROID_ARM64("android_arm64", TargetFamily.ANDROID),
    ANDROID_ARM32("android_arm32", TargetFamily.ANDROID),
    ANDROID_X64("android_x64", TargetFamily.ANDROID),
    ANDROID_X86("android_x86", TargetFamily.ANDROID);

    companion object {
        /**
         * Get the target for the current host platform.
         */
        fun host(): KonanTarget {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()

            return when {
                os.contains("mac") && arch.contains("aarch64") -> MACOS_ARM64
                os.contains("mac") -> MACOS_X64
                os.contains("linux") && arch.contains("aarch64") -> LINUX_ARM64
                os.contains("linux") -> LINUX_X64
                os.contains("windows") -> MINGW_X64
                else -> throw IllegalStateException("Unsupported host platform: $os $arch")
            }
        }

        fun fromString(name: String): KonanTarget? {
            return entries.find { it.targetName == name || it.name.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * Target family for grouping related targets.
 */
enum class TargetFamily {
    OSX, IOS, WATCHOS, TVOS, LINUX, MINGW, ANDROID
}

/**
 * Output kind for Kotlin/Native compilation.
 */
enum class NativeOutputKind(val value: String) {
    /** Executable binary */
    EXECUTABLE("program"),
    /** Static library (.a) */
    STATIC("static"),
    /** Dynamic/shared library (.dylib, .so, .dll) */
    DYNAMIC("dynamic"),
    /** Kotlin/Native library (.klib) */
    LIBRARY("library"),
    /** Framework for Apple platforms */
    FRAMEWORK("framework"),
    /** Static framework for Apple platforms */
    STATIC_FRAMEWORK("static_framework")
}

/**
 * Manages the Kotlin/Native compiler distribution.
 *
 * Downloads and caches the compiler in ~/.konan (same location as Gradle)
 * to avoid duplicate downloads.
 *
 * @param version Kotlin version to use
 * @param konanHome Directory to store Kotlin/Native distributions (defaults to ~/.konan)
 */
class KonanCompiler(
    val version: String = Kotlin.version.toString(),
    val konanHome: File = File(System.getProperty("user.home"), ".konan")
) {
    /**
     * The host platform for this compiler.
     */
    val hostTarget: KonanTarget = KonanTarget.host()

    /**
     * The platform-specific distribution name.
     */
    private val platformSuffix: String = when {
        hostTarget == KonanTarget.MACOS_ARM64 -> "macos-aarch64"
        hostTarget == KonanTarget.MACOS_X64 -> "macos-x86_64"
        hostTarget == KonanTarget.LINUX_X64 -> "linux-x86_64"
        hostTarget == KonanTarget.LINUX_ARM64 -> "linux-aarch64"
        hostTarget == KonanTarget.MINGW_X64 -> "windows-x86_64"
        else -> throw IllegalStateException("Unsupported host: $hostTarget")
    }

    /**
     * The distribution directory for this version.
     */
    val distributionDir: File = konanHome.resolve("kotlin-native-prebuilt-$platformSuffix-$version")

    /**
     * The konanc compiler executable.
     */
    val konanc: File = distributionDir.resolve("bin/konanc")

    /**
     * The cinterop tool executable.
     */
    val cinterop: File = distributionDir.resolve("bin/cinterop")

    /**
     * The klib tool executable.
     */
    val klib: File = distributionDir.resolve("bin/klib")

    /**
     * Check if the compiler is already installed.
     */
    fun isInstalled(): Boolean {
        return konanc.exists() && konanc.canExecute()
    }

    /**
     * Ensure the compiler is installed, downloading if necessary.
     *
     * @param onProgress Optional callback for download progress
     * @return The compiler distribution directory
     */
    fun ensureInstalled(onProgress: ((Long, Long) -> Unit)? = null): File {
        if (isInstalled()) {
            return distributionDir
        }

        download(onProgress)
        return distributionDir
    }

    /**
     * Download the Kotlin/Native distribution.
     */
    fun download(onProgress: ((Long, Long) -> Unit)? = null) {
        konanHome.mkdirs()

        val extension = if (hostTarget == KonanTarget.MINGW_X64) "zip" else "tar.gz"
        val archiveName = "kotlin-native-prebuilt-$platformSuffix-$version.$extension"
        val downloadUrl = "https://github.com/JetBrains/kotlin/releases/download/v$version/$archiveName"

        println("Downloading Kotlin/Native $version for $platformSuffix...")
        println("URL: $downloadUrl")

        val archiveFile = konanHome.resolve(archiveName)

        // Download the archive
        URL(downloadUrl).openStream().use { input ->
            Files.copy(input, archiveFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        println("Extracting...")

        // Extract the archive
        if (extension == "zip") {
            extractZip(archiveFile, konanHome)
        } else {
            extractTarGz(archiveFile, konanHome)
        }

        // Clean up archive
        archiveFile.delete()

        // Make executables executable on Unix
        if (hostTarget != KonanTarget.MINGW_X64) {
            distributionDir.resolve("bin").listFiles()?.forEach { file ->
                file.setExecutable(true)
            }
        }

        println("Kotlin/Native $version installed to $distributionDir")
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destFile = destDir.resolve(entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        // Use system tar command for simplicity
        val process = ProcessBuilder("tar", "-xzf", tarGzFile.absolutePath)
            .directory(destDir)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Failed to extract tar.gz archive, exit code: $exitCode")
        }
    }

    /**
     * Compile Kotlin source files to native.
     *
     * @param sources List of source files or directories
     * @param output Output file path
     * @param target Target platform
     * @param outputKind Type of output to produce
     * @param libraries List of .klib libraries to link
     * @param optimizations Enable optimizations
     * @param debug Include debug information
     * @param additionalArgs Additional compiler arguments
     * @return The output file
     */
    fun compile(
        sources: List<File>,
        output: File,
        target: KonanTarget = hostTarget,
        outputKind: NativeOutputKind = NativeOutputKind.EXECUTABLE,
        libraries: List<File> = emptyList(),
        optimizations: Boolean = false,
        debug: Boolean = true,
        additionalArgs: List<String> = emptyList()
    ): File {
        ensureInstalled()

        val args = mutableListOf<String>()

        // Add source files
        sources.forEach { source ->
            if (source.isDirectory) {
                source.walkTopDown()
                    .filter { it.extension == "kt" }
                    .forEach { args.add(it.absolutePath) }
            } else {
                args.add(source.absolutePath)
            }
        }

        // Target
        args.add("-target")
        args.add(target.targetName)

        // Output
        args.add("-o")
        args.add(output.absolutePath)

        // Output kind
        args.add("-produce")
        args.add(outputKind.value)

        // Libraries
        libraries.forEach { lib ->
            args.add("-l")
            args.add(lib.absolutePath)
        }

        // Optimizations
        if (optimizations) {
            args.add("-opt")
        }

        // Debug info
        if (debug) {
            args.add("-g")
        }

        // Additional arguments
        args.addAll(additionalArgs)

        val process = ProcessBuilder(konanc.absolutePath, *args.toTypedArray())
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Kotlin/Native compilation failed with exit code: $exitCode")
        }

        return output
    }

    /**
     * Run cinterop to generate Kotlin bindings from C headers.
     *
     * @param defFile The .def file describing the library
     * @param output Output .klib file
     * @param target Target platform
     * @param additionalArgs Additional cinterop arguments
     * @return The output .klib file
     */
    fun cinterop(
        defFile: File,
        output: File,
        target: KonanTarget = hostTarget,
        additionalArgs: List<String> = emptyList()
    ): File {
        ensureInstalled()

        val args = mutableListOf(
            "-def", defFile.absolutePath,
            "-o", output.absolutePath,
            "-target", target.targetName
        )
        args.addAll(additionalArgs)

        val process = ProcessBuilder(cinterop.absolutePath, *args.toTypedArray())
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("cinterop failed with exit code: $exitCode")
        }

        return output
    }

    companion object {
        /**
         * Get a compiler for the current Kotlin version.
         */
        fun default(): KonanCompiler = KonanCompiler()

        /**
         * Get a compiler for a specific version.
         */
        fun forVersion(version: String): KonanCompiler = KonanCompiler(version)
    }
}
