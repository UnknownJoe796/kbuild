package com.example.build

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.ReactiveKotlinCompile
import com.ivieleague.kbuild.kotlin.asReactive
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Sample reactive build configuration for a Kotlin JVM project.
 *
 * This demonstrates:
 * - Reactive file watching with DirectoryWatch
 * - Automatic recompilation when sources change
 * - Integration with Maven dependencies
 */
object HelloReactiveBuild {
    val projectRoot = File("samples/hello-reactive")
    val srcDir = projectRoot.resolve("src/main/kotlin")
    val buildDir = projectRoot.resolve("build")
    val cacheDir = buildDir.resolve("cache")
    val outputDir = buildDir.resolve("classes")

    // Reactive source watcher - fires when .kt files change
    val sources = DirectoryWatch(srcDir, "**/*.kt")

    // Dependencies from Maven
    val dependencies = MavenAether.libraries(Kotlin.standardLibraryJvmId)
        .map { it.default }
        .toSet()

    // Reactive compilation - recompiles when sources change
    val compile = ReactiveKotlinCompile(
        name = "hello-reactive",
        sources = sources,
        classpath = dependencies.asReactive(),
        cache = cacheDir,
        outputFolder = outputDir
    )

    fun build() {
        println("Starting reactive build...")
        println("Watching: ${srcDir.absolutePath}")
        println("Output: ${outputDir.absolutePath}")
        println()

        val latch = CountDownLatch(1)

        val removeListener = compile.addListener {
            val state = compile.state
            when {
                state.success -> {
                    val output = state.getOrNull()
                    println("✓ Build successful: $output")
                    val classes = output?.walkTopDown()?.filter { it.extension == "class" }?.toList() ?: emptyList()
                    println("  Compiled ${classes.size} class files")
                    latch.countDown()
                }
                state.exception != null -> {
                    println("✗ Build failed: ${state.exception?.message}")
                    latch.countDown()
                }
                else -> {
                    println("⟳ Building...")
                }
            }
        }

        // Wait for initial build
        latch.await()
        removeListener()
    }

    fun watch() {
        println("Starting continuous build...")
        println("Watching: ${srcDir.absolutePath}")
        println("Output: ${outputDir.absolutePath}")
        println("Press Ctrl+C to stop")
        println()

        compile.addListener {
            val state = compile.state
            when {
                state.success -> {
                    val output = state.getOrNull()
                    println("[${java.time.LocalTime.now()}] ✓ Build successful")
                    val classes = output?.walkTopDown()?.filter { it.extension == "class" }?.toList() ?: emptyList()
                    println("  Compiled ${classes.size} class files")
                }
                state.exception != null -> {
                    println("[${java.time.LocalTime.now()}] ✗ Build failed: ${state.exception?.message}")
                }
                else -> {
                    println("[${java.time.LocalTime.now()}] ⟳ Building...")
                }
            }
        }

        // Keep running until interrupted
        Thread.currentThread().join()
    }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "watch" -> HelloReactiveBuild.watch()
        else -> HelloReactiveBuild.build()
    }
}
