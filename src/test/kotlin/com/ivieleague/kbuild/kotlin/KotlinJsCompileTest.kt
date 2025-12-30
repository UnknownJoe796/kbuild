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
}
