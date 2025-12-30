package com.example.build

import com.ivieleague.kbuild.native.*
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Sample reactive build configuration for a Kotlin/Native project.
 *
 * This demonstrates:
 * - Compiling Kotlin to native executable
 * - Reactive file watching with automatic recompilation
 * - Running the compiled executable
 */
object HelloNativeBuild {
    val projectRoot = File("samples/hello-native")
    val srcDir = projectRoot.resolve("src/main/kotlin")
    val buildDir = projectRoot.resolve("build")
    val outputDir = buildDir.resolve("bin")

    // Target platform (defaults to current host)
    val target = KonanTarget.host()

    // Reactive source watcher - fires when .kt files change
    val sources = DirectoryWatch(srcDir, "**/*.kt")

    // Reactive compilation - recompiles when sources change
    val compile = ReactiveKotlinNativeCompile(
        name = "hello-native",
        sources = sources,
        target = target,
        outputKind = NativeOutputKind.EXECUTABLE,
        outputDir = outputDir,
        debug = true
    )

    fun build() {
        println("Building Kotlin/Native executable...")
        println("Target: ${target.targetName}")
        println("Source: ${srcDir.absolutePath}")
        println("Output: ${outputDir.absolutePath}")
        println()

        val latch = CountDownLatch(1)

        val removeListener = compile.addListener {
            val state = compile.state
            when {
                state.success -> {
                    val output = state.getOrNull()
                    println("✓ Build successful: $output")
                    latch.countDown()
                }
                state.exception != null -> {
                    println("✗ Build failed: ${state.exception?.message}")
                    state.exception?.printStackTrace()
                    latch.countDown()
                }
                else -> {
                    println("⟳ Compiling...")
                }
            }
        }

        // Wait for build
        latch.await()
        removeListener()
    }

    fun run() {
        build()

        val executable = compile.compiler.outputFile
        if (!executable.exists()) {
            println("Executable not found: $executable")
            return
        }

        println()
        println("Running $executable...")
        println("---")

        val process = ProcessBuilder(executable.absolutePath)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        println("---")
        println("Exit code: $exitCode")
    }

    fun watch() {
        println("Starting continuous build...")
        println("Target: ${target.targetName}")
        println("Watching: ${srcDir.absolutePath}")
        println("Output: ${outputDir.absolutePath}")
        println("Press Ctrl+C to stop")
        println()

        compile.onCompileStart = {
            println("[${java.time.LocalTime.now()}] ⟳ Compiling...")
        }

        compile.onCompileSuccess = { output ->
            println("[${java.time.LocalTime.now()}] ✓ Build successful: ${output.name}")
        }

        compile.onCompileError = { error ->
            println("[${java.time.LocalTime.now()}] ✗ Build failed: ${error.message}")
        }

        compile.onCompilerDownloading = {
            println("[${java.time.LocalTime.now()}] ⟳ Downloading Kotlin/Native compiler...")
        }

        // Trigger initial build
        compile.addListener {}

        // Keep running until interrupted
        Thread.currentThread().join()
    }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "watch" -> HelloNativeBuild.watch()
        "run" -> HelloNativeBuild.run()
        else -> HelloNativeBuild.build()
    }
}
