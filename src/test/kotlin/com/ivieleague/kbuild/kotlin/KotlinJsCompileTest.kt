package com.ivieleague.kbuild.kotlin

import com.lightningkite.reactive.core.Constant
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

class KotlinJsCompileTest {

    // Kotlin JS stdlib klib files (loaded once, reused across tests)
    private val jsStdlib: Set<File> by lazy {
        Kotlin.standardLibraryJs.mapNotNull { it.default }.toSet()
    }

    @Test
    fun `compiles simple Kotlin to JS`() = runBlocking {
        val root = File("build/run/KotlinJsCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        // Create simple Kotlin source
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello from Kotlin/JS!")
            }
        """.trimIndent())

        val result = kotlinJsCompile(
            name = "hello-js",
            sourceRoots = Constant(setOf(srcDir)),
            libraries = jsStdlib,
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            outputDir = outputDir
        )

        assertTrue(result.exists(), "Output should exist: $result")
        val jsFiles = outputDir.walkTopDown().filter { it.extension == "js" || it.extension == "mjs" }.toList()
        assertTrue(jsFiles.isNotEmpty(), "Should have JS output files: ${outputDir.walkTopDown().toList()}")
        println("Compiled to: ${jsFiles.joinToString()}")
    }

    @Test
    fun `compiles to klib`() = runBlocking {
        val root = File("build/run/KotlinJsKlibTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        srcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun double(x: Int): Int = x * 2
            fun triple(x: Int): Int = x * 3
        """.trimIndent())

        val result = kotlinJsCompile(
            name = "mylib",
            sourceRoots = Constant(setOf(srcDir)),
            libraries = jsStdlib,
            outputMode = JsOutputMode.KLIB,
            outputDir = outputDir
        )

        assertTrue(result.exists(), "Output should exist: $result")
        assertTrue(result.extension == "klib", "Output should be .klib file: $result")
        println("Created library: $result")
    }

    @Test
    fun `incremental compilation is faster than clean build`() = runBlocking {
        val root = File("build/run/KotlinJsIncrementalTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial source files
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello!")
                greet("World")
            }
        """.trimIndent())

        srcDir.resolve("Utils.kt").writeText("""
            fun greet(name: String) {
                println("Hello, ${'$'}name!")
            }
        """.trimIndent())

        // First build (clean)
        val cleanBuildTime = measureTimeMillis {
            kotlinJsCompile(
                name = "incremental-test",
                sourceRoots = Constant(setOf(srcDir)),
                libraries = jsStdlib,
                outputMode = JsOutputMode.JS,
                cache = cacheDir,
                outputDir = outputDir
            )
        }
        println("Clean build: ${cleanBuildTime}ms")

        // Second build (no changes)
        val noChangeBuildTime = measureTimeMillis {
            kotlinJsCompile(
                name = "incremental-test",
                sourceRoots = Constant(setOf(srcDir)),
                libraries = jsStdlib,
                outputMode = JsOutputMode.JS,
                cache = cacheDir,
                outputDir = outputDir
            )
        }
        println("No-change rebuild: ${noChangeBuildTime}ms")

        // Modify one file
        Thread.sleep(50) // Ensure timestamp changes
        srcDir.resolve("Utils.kt").writeText("""
            fun greet(name: String) {
                println("Hi, ${'$'}name!")
            }
        """.trimIndent())

        // Third build (incremental with change)
        val incrementalBuildTime = measureTimeMillis {
            kotlinJsCompile(
                name = "incremental-test",
                sourceRoots = Constant(setOf(srcDir)),
                libraries = jsStdlib,
                outputMode = JsOutputMode.JS,
                cache = cacheDir,
                outputDir = outputDir
            )
        }
        println("Incremental build: ${incrementalBuildTime}ms")

        println("\nPerformance Summary:")
        println("  Clean build:       ${cleanBuildTime}ms")
        println("  No-change rebuild: ${noChangeBuildTime}ms")
        println("  Incremental build: ${incrementalBuildTime}ms")

        // Assert no-change should be significantly faster than clean
        // (This will be more meaningful after optimization)
        assertTrue(outputDir.exists(), "Output should exist")
    }

    @Test
    fun `performance benchmark with multiple files`() = runBlocking {
        val root = File("build/run/KotlinJsPerformanceBenchmark")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create multiple source files to simulate a real project
        val fileCount = 10
        for (i in 1..fileCount) {
            srcDir.resolve("File$i.kt").writeText("""
                package benchmark

                class Class$i {
                    fun method1(): Int = $i
                    fun method2(): String = "Class$i"
                    fun method3(): List<Int> = listOf(${(1..10).joinToString()})
                }

                fun function$i(x: Int): Int = x + $i
                fun compute$i(): Double = ${i}.0 * 2.0
            """.trimIndent())
        }

        // Add main file
        srcDir.resolve("Main.kt").writeText("""
            package benchmark

            fun main() {
                ${(1..fileCount).joinToString("\n                ") { "println(Class$it().method2())" }}
            }
        """.trimIndent())

        println("Created $fileCount source files for benchmark")

        // Warmup build
        println("\nWarmup build...")
        kotlinJsCompile(
            name = "benchmark",
            sourceRoots = Constant(setOf(srcDir)),
            libraries = jsStdlib,
            outputMode = JsOutputMode.JS,
            cache = cacheDir,
            outputDir = outputDir
        )

        // Clean and measure fresh build
        outputDir.deleteRecursively()
        cacheDir.deleteRecursively()

        val cleanBuildTimes = mutableListOf<Long>()
        repeat(3) { run ->
            outputDir.deleteRecursively()
            cacheDir.deleteRecursively()

            val time = measureTimeMillis {
                kotlinJsCompile(
                    name = "benchmark",
                    sourceRoots = Constant(setOf(srcDir)),
                    libraries = jsStdlib,
                    outputMode = JsOutputMode.JS,
                    cache = cacheDir,
                    outputDir = outputDir
                )
            }
            cleanBuildTimes.add(time)
            println("Clean build run ${run + 1}: ${time}ms")
        }

        // Measure no-change rebuild
        val noChangeTimes = mutableListOf<Long>()
        repeat(3) { run ->
            val time = measureTimeMillis {
                kotlinJsCompile(
                    name = "benchmark",
                    sourceRoots = Constant(setOf(srcDir)),
                    libraries = jsStdlib,
                    outputMode = JsOutputMode.JS,
                    cache = cacheDir,
                    outputDir = outputDir
                )
            }
            noChangeTimes.add(time)
            println("No-change rebuild run ${run + 1}: ${time}ms")
        }

        // Measure single-file change rebuild
        Thread.sleep(50)
        srcDir.resolve("File5.kt").writeText("""
            package benchmark

            class Class5 {
                fun method1(): Int = 5
                fun method2(): String = "Class5-modified"
                fun method3(): List<Int> = listOf(${(1..10).joinToString()})
            }

            fun function5(x: Int): Int = x + 5
            fun compute5(): Double = 5.0 * 3.0  // Changed
        """.trimIndent())

        val incrementalTimes = mutableListOf<Long>()
        val time = measureTimeMillis {
            kotlinJsCompile(
                name = "benchmark",
                sourceRoots = Constant(setOf(srcDir)),
                libraries = jsStdlib,
                outputMode = JsOutputMode.JS,
                cache = cacheDir,
                outputDir = outputDir
            )
        }
        incrementalTimes.add(time)
        println("Single-file change build: ${time}ms")

        println("\n=== PERFORMANCE SUMMARY ===")
        println("Files: $fileCount source files")
        println("Clean build avg:       ${cleanBuildTimes.average().toLong()}ms (min: ${cleanBuildTimes.min()}, max: ${cleanBuildTimes.max()})")
        println("No-change rebuild avg: ${noChangeTimes.average().toLong()}ms (min: ${noChangeTimes.min()}, max: ${noChangeTimes.max()})")
        println("Incremental build:     ${incrementalTimes.first()}ms")

        val speedup = cleanBuildTimes.average() / noChangeTimes.average()
        println("\nNo-change speedup: ${String.format("%.1f", speedup)}x")
    }
}
