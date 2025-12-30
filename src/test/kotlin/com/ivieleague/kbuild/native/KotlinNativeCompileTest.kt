package com.ivieleague.kbuild.native

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinNativeCompileTest {

    @Test
    fun `compiles simple Kotlin to native executable`() {
        val root = File("build/run/KotlinNativeCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        // Create simple Kotlin source
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello from Kotlin/Native!")
            }
        """.trimIndent())

        val compile = KotlinNativeCompile(
            name = "hello-native",
            sourceRoots = { setOf(srcDir) },
            target = KonanTarget.host(),
            outputKind = NativeOutputKind.EXECUTABLE,
            outputDir = outputDir
        )

        println("Ensuring Kotlin/Native compiler is installed...")
        compile.ensureCompilerInstalled()

        println("Compiling to ${KonanTarget.host()}...")
        val result = compile()

        assertTrue(result.exists(), "Output should exist: $result")
        println("Compiled to: $result")

        // On Unix systems, try to run the executable
        if (KonanTarget.host().family != TargetFamily.MINGW) {
            assertTrue(result.canExecute(), "Output should be executable")

            val process = ProcessBuilder(result.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            println("Output: $output")
            assertTrue(exitCode == 0, "Executable should run successfully, got exit code: $exitCode")
            assertTrue(output.contains("Hello from Kotlin/Native!"), "Should print expected message")
        }
    }

    @Test
    fun `compiles to klib library`() {
        val root = File("build/run/KotlinNativeKlibTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        srcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun double(x: Int): Int = x * 2
            fun triple(x: Int): Int = x * 3
        """.trimIndent())

        val compile = kotlinNativeLibrary(
            name = "mylib",
            sourceRoots = { setOf(srcDir) },
            target = KonanTarget.host(),
            outputDir = outputDir
        )

        val result = compile()

        assertTrue(result.exists(), "Output should exist: $result")
        assertTrue(result.extension == "klib", "Output should be .klib file: $result")
        println("Created library: $result")
    }

    @Test
    fun `compiles with library dependency`() {
        val root = File("build/run/KotlinNativeWithLibTest")
        root.deleteRecursively()
        root.mkdirs()

        val libSrcDir = root.resolve("lib-src").also { it.mkdirs() }
        val appSrcDir = root.resolve("app-src").also { it.mkdirs() }
        val libOutputDir = root.resolve("lib-output")
        val appOutputDir = root.resolve("app-output")

        // Create library
        libSrcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun greet(name: String): String = "Hello, ${'$'}name!"
        """.trimIndent())

        val libCompile = kotlinNativeLibrary(
            name = "mylib",
            sourceRoots = { setOf(libSrcDir) },
            target = KonanTarget.host(),
            outputDir = libOutputDir
        )

        val libOutput = libCompile()
        assertTrue(libOutput.exists(), "Library should be created")

        // Create app using library
        appSrcDir.resolve("Main.kt").writeText("""
            import mylib.greet

            fun main() {
                println(greet("Kotlin/Native"))
            }
        """.trimIndent())

        val appCompile = kotlinNativeExecutable(
            name = "myapp",
            sourceRoots = { setOf(appSrcDir) },
            libraries = { setOf(libOutput) },
            target = KonanTarget.host(),
            outputDir = appOutputDir
        )

        val appOutput = appCompile()
        assertTrue(appOutput.exists(), "App should be created: $appOutput")

        // Run the app
        if (KonanTarget.host().family != TargetFamily.MINGW) {
            val process = ProcessBuilder(appOutput.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            println("Output: $output")
            assertTrue(exitCode == 0, "App should run successfully")
            assertTrue(output.contains("Hello, Kotlin/Native!"), "Should print expected message")
        }
    }

    @Test
    fun `DetFileBuilder creates valid def file`() {
        val root = File("build/run/DefFileBuilderTest")
        root.deleteRecursively()
        root.mkdirs()

        val defFile = DefFileBuilder("posix")
            .headers("stdio.h", "stdlib.h")
            .headerFilter("stdio.h", "stdlib.h")
            .compilerOpts("-DSOME_FLAG")
            .linkerOpts("-lm")
            .packageName("platform.posix")
            .build(root)

        assertTrue(defFile.exists(), "Def file should exist")

        val content = defFile.readText()
        println("Def file content:\n$content")

        assertTrue(content.contains("headers = stdio.h stdlib.h"), "Should have headers")
        assertTrue(content.contains("headerFilter = stdio.h stdlib.h"), "Should have headerFilter")
        assertTrue(content.contains("compilerOpts = -DSOME_FLAG"), "Should have compilerOpts")
        assertTrue(content.contains("linkerOpts = -lm"), "Should have linkerOpts")
        assertTrue(content.contains("package = platform.posix"), "Should have package")
    }
}
