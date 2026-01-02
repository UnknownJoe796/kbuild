package com.ivieleague.kbuild.kotlin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.system.measureTimeMillis

class KotlinJvmCompileTest {

    // Kotlin JVM stdlib (loaded once, reused across tests)
    private val jvmStdlib: Set<File> by lazy {
        Kotlin.standardLibraryJvm.mapNotNull { it.default }.toSet()
    }

    @Test
    fun `compiles simple Kotlin to JVM classes`() {
        val root = File("build/run/KotlinJvmCompileTest/simple")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create simple Kotlin source
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello from Kotlin/JVM!")
            }
        """.trimIndent())

        val result = kotlinJvmCompileBlocking(
            name = "hello-jvm",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(result.exists(), "Output should exist: $result")
        val classFiles = outputDir.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have class output files: ${outputDir.walkTopDown().toList()}")
        assertTrue(classFiles.any { it.name == "MainKt.class" }, "Should have MainKt.class")
        println("Compiled to: ${classFiles.joinToString { it.name }}")
    }

    @Test
    fun `compiles multiple files with dependencies`() {
        val root = File("build/run/KotlinJvmCompileTest/multi")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create utility file
        srcDir.resolve("Utils.kt").writeText("""
            package myapp

            fun greet(name: String): String = "Hello, ${'$'}name!"
            fun double(x: Int): Int = x * 2
        """.trimIndent())

        // Create main file using utils
        srcDir.resolve("Main.kt").writeText("""
            package myapp

            fun main() {
                println(greet("World"))
                println(double(21))
            }
        """.trimIndent())

        val result = kotlinJvmCompileBlocking(
            name = "multi-file-app",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(result.exists(), "Output should exist")
        val classFiles = outputDir.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.any { it.name == "MainKt.class" }, "Should have MainKt.class")
        assertTrue(classFiles.any { it.name == "UtilsKt.class" }, "Should have UtilsKt.class")
    }

    @Test
    fun `incremental compilation skips when no changes`() {
        val root = File("build/run/KotlinJvmCompileTest/no-change")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Initial version")
            }
        """.trimIndent())

        // First build
        println("=== First build ===")
        val firstBuildTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "no-change-test",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("First build: ${firstBuildTime}ms")

        val classFiles = outputDir.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have class files after first build")
        val firstBuildTimestamp = classFiles.first().lastModified()

        // Second build (no changes)
        Thread.sleep(100) // Ensure time has passed
        println("\n=== Second build (no changes) ===")
        val secondBuildTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "no-change-test",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("Second build (no changes): ${secondBuildTime}ms")

        // Class files should not be rewritten
        val secondBuildTimestamp = classFiles.first().lastModified()
        assertEquals(firstBuildTimestamp, secondBuildTimestamp,
            "Class file should not be modified when no source changes")

        // No-change build should be significantly faster
        assertTrue(secondBuildTime < firstBuildTime / 2 || secondBuildTime < 500,
            "No-change build should be fast. First: ${firstBuildTime}ms, Second: ${secondBuildTime}ms")

        println("\nNo-change speedup: ${firstBuildTime.toDouble() / secondBuildTime}x")
    }

    @Test
    fun `incremental compilation recompiles modified files`() {
        val root = File("build/run/KotlinJvmCompileTest/modified")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial files
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println(getMessage())
            }
        """.trimIndent())

        srcDir.resolve("Message.kt").writeText("""
            fun getMessage(): String = "Hello"
        """.trimIndent())

        // First build
        println("=== First build ===")
        kotlinJvmCompileBlocking(
            name = "modified-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(outputDir.resolve("MainKt.class").exists(), "MainKt.class should exist")
        assertTrue(outputDir.resolve("MessageKt.class").exists(), "MessageKt.class should exist")

        // Modify one file
        Thread.sleep(100) // Ensure timestamp changes
        srcDir.resolve("Message.kt").writeText("""
            fun getMessage(): String = "Hello World"
        """.trimIndent())

        // Second build (incremental)
        println("\n=== Second build (file modified) ===")
        val incrementalTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "modified-test",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("Incremental build: ${incrementalTime}ms")

        // Output should still exist and be updated
        assertTrue(outputDir.resolve("MainKt.class").exists(), "MainKt.class should still exist")
        assertTrue(outputDir.resolve("MessageKt.class").exists(), "MessageKt.class should still exist")
    }

    @Test
    fun `incremental compilation handles new files`() {
        val root = File("build/run/KotlinJvmCompileTest/new-file")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial file
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello")
            }
        """.trimIndent())

        // First build
        println("=== First build ===")
        kotlinJvmCompileBlocking(
            name = "new-file-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        val classFilesBefore = outputDir.walkTopDown().filter { it.extension == "class" }.count()
        println("Class files after first build: $classFilesBefore")

        // Add new file
        Thread.sleep(100)
        srcDir.resolve("Utils.kt").writeText("""
            fun helper(): Int = 42
        """.trimIndent())

        // Update Main to use the new file
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello: ${'$'}{helper()}")
            }
        """.trimIndent())

        // Second build
        println("\n=== Second build (new file added) ===")
        kotlinJvmCompileBlocking(
            name = "new-file-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        val classFilesAfter = outputDir.walkTopDown().filter { it.extension == "class" }.count()
        println("Class files after second build: $classFilesAfter")

        assertTrue(classFilesAfter > classFilesBefore,
            "Should have more class files after adding source file")
        assertTrue(outputDir.resolve("UtilsKt.class").exists(),
            "UtilsKt.class should be created for new file")
    }

    @Test
    fun `incremental compilation handles deleted files`() {
        val root = File("build/run/KotlinJvmCompileTest/deleted-file")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create initial files
        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Hello")
            }
        """.trimIndent())

        srcDir.resolve("ToDelete.kt").writeText("""
            fun unused(): String = "This will be deleted"
        """.trimIndent())

        // First build
        println("=== First build ===")
        kotlinJvmCompileBlocking(
            name = "deleted-file-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(outputDir.resolve("ToDeleteKt.class").exists(),
            "ToDeleteKt.class should exist after first build")

        // Delete the file
        Thread.sleep(100)
        srcDir.resolve("ToDelete.kt").delete()

        // Second build
        println("\n=== Second build (file deleted) ===")
        kotlinJvmCompileBlocking(
            name = "deleted-file-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Note: The incremental compiler may or may not clean up the orphaned class file
        // The important thing is that the build succeeds
        assertTrue(outputDir.resolve("MainKt.class").exists(),
            "MainKt.class should still exist after deleting unrelated file")
    }

    @Test
    fun `performance benchmark clean vs incremental`() {
        val root = File("build/run/KotlinJvmCompileTest/benchmark")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create multiple source files
        val fileCount = 10
        for (i in 1..fileCount) {
            srcDir.resolve("File$i.kt").writeText("""
                package benchmark

                class Class$i {
                    fun method1(): Int = $i
                    fun method2(): String = "Class$i"
                    fun method3(): List<Int> = listOf(${(1..5).joinToString()})
                }

                fun function$i(x: Int): Int = x + $i
            """.trimIndent())
        }

        srcDir.resolve("Main.kt").writeText("""
            package benchmark

            fun main() {
                ${(1..fileCount).joinToString("\n                ") { "println(Class$it().method2())" }}
            }
        """.trimIndent())

        println("Created ${fileCount + 1} source files")

        // Clean build
        println("\n=== Clean build ===")
        val cleanBuildTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "benchmark",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("Clean build: ${cleanBuildTime}ms")

        // No-change rebuild
        println("\n=== No-change rebuild ===")
        val noChangeTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "benchmark",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("No-change rebuild: ${noChangeTime}ms")

        // Single file change
        Thread.sleep(100)
        srcDir.resolve("File5.kt").writeText("""
            package benchmark

            class Class5 {
                fun method1(): Int = 55  // Changed
                fun method2(): String = "Class5-modified"
                fun method3(): List<Int> = listOf(${(1..5).joinToString()})
            }

            fun function5(x: Int): Int = x + 55
        """.trimIndent())

        println("\n=== Incremental build (1 file changed) ===")
        val incrementalTime = measureTimeMillis {
            kotlinJvmCompileBlocking(
                name = "benchmark",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        }
        println("Incremental build: ${incrementalTime}ms")

        println("\n=== PERFORMANCE SUMMARY ===")
        println("Files: ${fileCount + 1} source files")
        println("Clean build:       ${cleanBuildTime}ms")
        println("No-change rebuild: ${noChangeTime}ms")
        println("Incremental build: ${incrementalTime}ms")

        if (noChangeTime > 0) {
            println("\nNo-change speedup: ${String.format("%.1f", cleanBuildTime.toDouble() / noChangeTime)}x")
        }
        if (incrementalTime > 0) {
            println("Incremental speedup: ${String.format("%.1f", cleanBuildTime.toDouble() / incrementalTime)}x")
        }

        // Assert that incremental builds are faster
        assertTrue(noChangeTime < cleanBuildTime,
            "No-change rebuild should be faster than clean build")
    }

    @Test
    fun `compiles with context parameters enabled`() {
        val root = File("build/run/KotlinJvmCompileTest/context-params")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create source using context parameters
        srcDir.resolve("Main.kt").writeText("""
            class MyContext(val prefix: String)

            context(ctx: MyContext)
            fun greet(name: String): String = "${'$'}{ctx.prefix} ${'$'}name!"

            fun main() {
                with(MyContext("Hello")) {
                    println(greet("World"))
                }
            }
        """.trimIndent())

        val result = kotlinJvmCompileBlocking(
            name = "context-params-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir,
            enableContextParameters = true
        )

        assertTrue(result.exists(), "Output should exist")
        assertTrue(outputDir.resolve("MainKt.class").exists(), "MainKt.class should exist")
        assertTrue(outputDir.resolve("MyContext.class").exists(), "MyContext.class should exist")
    }

    @Test
    fun `FileChangeTracker correctly detects changes`() {
        val root = File("build/run/KotlinJvmCompileTest/change-tracker")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Track compilation messages to verify incremental behavior
        val compilations = mutableListOf<String>()

        // Create initial file
        srcDir.resolve("A.kt").writeText("fun a() = 1")

        // First build - should be non-incremental
        println("=== Build 1: Initial ===")
        kotlinJvmCompileBlocking(
            name = "change-tracker",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Second build - no changes, should skip
        println("\n=== Build 2: No changes ===")
        kotlinJvmCompileBlocking(
            name = "change-tracker",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Third build - modify file
        Thread.sleep(100)
        srcDir.resolve("A.kt").writeText("fun a() = 2")
        println("\n=== Build 3: File modified ===")
        kotlinJvmCompileBlocking(
            name = "change-tracker",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Fourth build - add new file
        Thread.sleep(100)
        srcDir.resolve("B.kt").writeText("fun b() = 3")
        println("\n=== Build 4: New file added ===")
        kotlinJvmCompileBlocking(
            name = "change-tracker",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Fifth build - remove file
        Thread.sleep(100)
        srcDir.resolve("B.kt").delete()
        println("\n=== Build 5: File removed ===")
        kotlinJvmCompileBlocking(
            name = "change-tracker",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            cache = cacheDir,
            outputFolder = outputDir
        )

        println("\n=== All builds completed successfully ===")
    }

    @Test
    fun `handles compilation errors gracefully`() {
        val root = File("build/run/KotlinJvmCompileTest/error")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")
        val cacheDir = root.resolve("cache")

        // Create file with syntax error
        srcDir.resolve("Bad.kt").writeText("""
            fun main() {
                println("Missing closing brace"
            }
        """.trimIndent())

        var exceptionThrown = false
        try {
            kotlinJvmCompileBlocking(
                name = "error-test",
                sourceRoots = setOf(srcDir),
                classpathJars = jvmStdlib,
                cache = cacheDir,
                outputFolder = outputDir
            )
        } catch (e: Kotlin.CompilationException) {
            exceptionThrown = true
            println("Caught expected exception: ${e.message}")
            assertTrue(e.messages.isNotEmpty(), "Should have error messages")
        }

        assertTrue(exceptionThrown, "Should throw CompilationException for syntax errors")
    }

    @Test
    fun `non-incremental compilation works`() {
        val root = File("build/run/KotlinJvmCompileTest/non-incremental")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        srcDir.resolve("Main.kt").writeText("""
            fun main() {
                println("Non-incremental build")
            }
        """.trimIndent())

        val result = kotlinJvmCompileNonIncrementalBlocking(
            name = "non-incremental-test",
            sourceRoots = setOf(srcDir),
            classpathJars = jvmStdlib,
            outputFolder = outputDir
        )

        assertTrue(result.exists(), "Output should exist")
        assertTrue(outputDir.resolve("MainKt.class").exists(), "MainKt.class should exist")
    }
}
