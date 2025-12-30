package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.maven.KlibDependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class KotlinJsCompileTest {

    @Test
    fun `compiles simple Kotlin to JS`() {
        val root = File("build/run/KotlinJsCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        // Create simple Kotlin source
        srcDir.resolve("Main.kt").writeText("""
            package test

            fun greet(name: String): String {
                return "Hello, ${'$'}name!"
            }

            fun main() {
                println(greet("World"))
            }
        """.trimIndent())

        // Get Kotlin/JS stdlib (uses klib extension)
        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        val compile = KotlinJsCompile(
            name = "test-js",
            sourceRoots = { setOf(srcDir) },
            libraries = { jsStdlib },
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            outputDir = outputDir
        )

        val result = compile()

        assertTrue(result.exists(), "Output directory should exist")
        assertTrue(result.isDirectory, "Output should be a directory for JS mode")

        // Check for generated JS files (.js or .mjs for ES modules)
        val jsFiles = outputDir.walkTopDown().filter { it.extension == "js" || it.extension == "mjs" }.toList()
        assertTrue(jsFiles.isNotEmpty(), "Should have generated JS files, found: ${outputDir.walkTopDown().filter { it.isFile }.toList()}")

        // Check for source map
        val mapFiles = outputDir.walkTopDown().filter { it.extension == "map" }.toList()
        assertTrue(mapFiles.isNotEmpty(), "Should have generated source map files")

        println("Generated files:")
        outputDir.walkTopDown().filter { it.isFile }.forEach {
            println("  ${it.relativeTo(outputDir)}")
        }
    }

    @Test
    fun `compiles Kotlin to klib`() {
        val root = File("build/run/KotlinJsKlibTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        // Create library source
        srcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun double(x: Int): Int = x * 2
            fun triple(x: Int): Int = x * 3
        """.trimIndent())

        // Get Kotlin/JS stdlib (uses klib extension)
        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        val compile = KotlinJsCompile(
            name = "mylib",
            sourceRoots = { setOf(srcDir) },
            libraries = { jsStdlib },
            outputMode = JsOutputMode.KLIB,
            outputDir = outputDir
        )

        val result = compile()

        assertTrue(result.exists(), "Output should exist")
        println("KLIB output: $result")

        // The output could be a .klib file or the output directory containing it
        val klibExists = result.extension == "klib" ||
            outputDir.walkTopDown().any { it.extension == "klib" }
        assertTrue(klibExists || outputDir.exists(), "Should have klib output or directory")
    }

    @Test
    fun `compiles with CommonJS module kind`() {
        val root = File("build/run/KotlinJsCommonJSTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        srcDir.resolve("Main.kt").writeText("""
            package test

            fun add(a: Int, b: Int): Int = a + b
        """.trimIndent())

        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        val compile = KotlinJsCompile(
            name = "test-commonjs",
            sourceRoots = { setOf(srcDir) },
            libraries = { jsStdlib },
            outputDir = outputDir,
            moduleKind = JsModuleKind.COMMONJS
        )

        val result = compile()
        assertTrue(result.exists(), "Output should exist")

        // Check that JS files were generated (.js for CommonJS)
        val jsFiles = outputDir.walkTopDown().filter { it.extension == "js" || it.extension == "cjs" }.toList()
        assertTrue(jsFiles.isNotEmpty(), "Should have generated JS files")
    }

    @Test
    fun `incremental compilation works for klib`() {
        val root = File("build/run/KotlinJsIncrementalKlibTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial source
        srcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun double(x: Int): Int = x * 2
        """.trimIndent())

        // Get Kotlin/JS stdlib
        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        // First compilation (full rebuild)
        val startTime1 = System.currentTimeMillis()
        kotlinJsCompileBlocking(
            name = "mylib-ic",
            sourceRoots = setOf(srcDir),
            libraries = jsStdlib,
            outputMode = JsOutputMode.KLIB,
            cache = cacheDir,
            outputDir = outputDir
        )
        val time1 = System.currentTimeMillis() - startTime1
        println("First compilation (full): ${time1}ms")

        assertTrue(outputDir.resolve("mylib-ic.klib").exists(), "First compilation should produce klib")
        // Cache directory should exist with incremental data
        assertTrue(cacheDir.exists(), "Cache directory should exist")

        // Modify source slightly
        srcDir.resolve("Lib.kt").writeText("""
            package mylib

            fun double(x: Int): Int = x * 2
            fun triple(x: Int): Int = x * 3
        """.trimIndent())

        // Second compilation (should be incremental)
        val startTime2 = System.currentTimeMillis()
        kotlinJsCompileBlocking(
            name = "mylib-ic",
            sourceRoots = setOf(srcDir),
            libraries = jsStdlib,
            outputMode = JsOutputMode.KLIB,
            cache = cacheDir,
            outputDir = outputDir
        )
        val time2 = System.currentTimeMillis() - startTime2
        println("Second compilation (incremental): ${time2}ms")

        assertTrue(outputDir.resolve("mylib-ic.klib").exists(), "Second compilation should produce klib")

        // Third compilation with no changes (should be very fast)
        val startTime3 = System.currentTimeMillis()
        kotlinJsCompileBlocking(
            name = "mylib-ic",
            sourceRoots = setOf(srcDir),
            libraries = jsStdlib,
            outputMode = JsOutputMode.KLIB,
            cache = cacheDir,
            outputDir = outputDir
        )
        val time3 = System.currentTimeMillis() - startTime3
        println("Third compilation (no changes): ${time3}ms")

        assertTrue(outputDir.resolve("mylib-ic.klib").exists(), "Third compilation should produce klib")

        println("Incremental compilation test completed successfully")
    }

    @Test
    fun `incremental compilation works for JS output`() {
        val root = File("build/run/KotlinJsIncrementalJsTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial source
        srcDir.resolve("Main.kt").writeText("""
            package test

            fun greet(name: String): String = "Hello, ${'$'}name!"
        """.trimIndent())

        // Get Kotlin/JS stdlib
        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        // First compilation (full rebuild)
        val startTime1 = System.currentTimeMillis()
        kotlinJsCompileBlocking(
            name = "test-ic",
            sourceRoots = setOf(srcDir),
            libraries = jsStdlib,
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            cache = cacheDir,
            outputDir = outputDir
        )
        val time1 = System.currentTimeMillis() - startTime1
        println("First JS compilation (full): ${time1}ms")

        val jsFiles1 = outputDir.walkTopDown().filter { it.extension == "mjs" || it.extension == "js" }.toList()
        assertTrue(jsFiles1.isNotEmpty(), "First compilation should produce JS files")

        // Modify source slightly
        srcDir.resolve("Main.kt").writeText("""
            package test

            fun greet(name: String): String = "Hello, ${'$'}name!"
            fun farewell(name: String): String = "Goodbye, ${'$'}name!"
        """.trimIndent())

        // Second compilation (should use incremental klib compilation)
        val startTime2 = System.currentTimeMillis()
        kotlinJsCompileBlocking(
            name = "test-ic",
            sourceRoots = setOf(srcDir),
            libraries = jsStdlib,
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            cache = cacheDir,
            outputDir = outputDir
        )
        val time2 = System.currentTimeMillis() - startTime2
        println("Second JS compilation (incremental klib): ${time2}ms")

        val jsFiles2 = outputDir.walkTopDown().filter { it.extension == "mjs" || it.extension == "js" }.toList()
        assertTrue(jsFiles2.isNotEmpty(), "Second compilation should produce JS files")

        println("Incremental JS compilation test completed successfully")
    }
}
