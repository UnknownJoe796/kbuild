package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ReactiveKotlinCompileTest {

    @Test
    fun `compiles kotlin source files`() {
        val tempDir = createTempDir("reactive-compile-test")
        val srcDir = tempDir.resolve("src").also { it.mkdirs() }
        val cacheDir = tempDir.resolve("cache").also { it.mkdirs() }
        val outputDir = tempDir.resolve("output").also { it.mkdirs() }

        try {
            // Create source file
            srcDir.resolve("Hello.kt").writeText("""
                package test
                fun hello() = "Hello, World!"
            """.trimIndent())

            // Get Kotlin stdlib from Maven
            val stdlib = MavenAether.libraries(Kotlin.standardLibraryJvmId)
                .map { it.default }
                .toSet()

            // Create reactive source watcher
            val sources = DirectoryWatch(srcDir, "**/*.kt")

            // Create reactive compilation
            val compile = ReactiveKotlinCompile(
                name = "test-module",
                sources = sources,
                classpath = stdlib.asReactive(),
                cache = cacheDir,
                outputFolder = outputDir
            )

            val compilationLatch = CountDownLatch(1)
            var compiledSuccessfully = false

            // Listen for compilation results
            val removeListener = compile.addListener {
                if (compile.state.ready && compile.state.success) {
                    compiledSuccessfully = true
                    compilationLatch.countDown()
                } else if (compile.state.exception != null) {
                    println("Compilation error: ${compile.state.exception}")
                    compilationLatch.countDown()
                }
            }

            try {
                // Wait for compilation
                val completed = compilationLatch.await(60, TimeUnit.SECONDS)

                assertTrue(completed, "Compilation should complete within timeout")
                assertTrue(compiledSuccessfully, "Compilation should succeed")

                // Verify output exists
                val classFiles = outputDir.walkTopDown().filter { it.extension == "class" }.toList()
                assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")
                println("Compiled ${classFiles.size} class files: ${classFiles.map { it.name }}")
            } finally {
                removeListener()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `handles compilation errors gracefully`() {
        val tempDir = createTempDir("reactive-compile-error-test")
        val srcDir = tempDir.resolve("src").also { it.mkdirs() }
        val cacheDir = tempDir.resolve("cache").also { it.mkdirs() }
        val outputDir = tempDir.resolve("output").also { it.mkdirs() }

        try {
            // Create source file with syntax error
            srcDir.resolve("Broken.kt").writeText("""
                package test
                fun broken( = "Missing parameter"
            """.trimIndent())

            val stdlib = MavenAether.libraries(Kotlin.standardLibraryJvmId)
                .map { it.default }
                .toSet()

            val sources = DirectoryWatch(srcDir, "**/*.kt")

            val compile = ReactiveKotlinCompile(
                name = "test-module",
                sources = sources,
                classpath = stdlib.asReactive(),
                cache = cacheDir,
                outputFolder = outputDir
            )

            val errorLatch = CountDownLatch(1)
            var gotError = false

            val removeListener = compile.addListener {
                val exception = compile.state.exception
                if (exception != null) {
                    println("Got expected error: ${exception.message}")
                    gotError = true
                    errorLatch.countDown()
                }
            }

            try {
                val completed = errorLatch.await(60, TimeUnit.SECONDS)
                assertTrue(completed, "Should complete within timeout")
                assertTrue(gotError, "Should report compilation error")
            } finally {
                removeListener()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}
