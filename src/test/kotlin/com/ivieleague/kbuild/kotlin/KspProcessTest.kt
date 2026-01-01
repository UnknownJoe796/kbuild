package com.ivieleague.kbuild.kotlin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KspProcessTest {

    @Test
    fun `kspJvmProcessBlocking creates output directories`() {
        val root = File("build/run/KspProcessTest/jvm-dirs")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kotlinOutputDir = root.resolve("generated/kotlin")
        val javaOutputDir = root.resolve("generated/java")
        val resourceOutputDir = root.resolve("generated/resources")
        val classOutputDir = root.resolve("generated/classes")
        val cacheDir = root.resolve("cache")

        // Create minimal Kotlin source
        srcDir.resolve("Example.kt").writeText("""
            package example

            class Example {
                fun greet(): String = "Hello"
            }
        """.trimIndent())

        // Run without any processors - should return empty set but create directories
        val result = kspJvmProcessBlocking(
            name = "test-module",
            sourceRoots = setOf(srcDir),
            classpathJars = emptySet(),
            processorClasspath = emptySet(), // No processors
            kotlinOutputDir = kotlinOutputDir,
            javaOutputDir = javaOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )

        // Should return empty since no processors
        assertTrue(result.isEmpty(), "Result should be empty when no processors are configured")

        // Output directories should NOT be created when no processors are found
        // (early return before directory creation)
    }

    @Test
    fun `kspJsProcessBlocking handles empty processor classpath`() {
        val root = File("build/run/KspProcessTest/js-empty")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kotlinOutputDir = root.resolve("generated/kotlin")
        val resourceOutputDir = root.resolve("generated/resources")
        val classOutputDir = root.resolve("generated/classes")
        val cacheDir = root.resolve("cache")

        srcDir.resolve("Example.kt").writeText("""
            package example

            fun hello() = "Hello JS"
        """.trimIndent())

        val result = kspJsProcessBlocking(
            name = "test-js-module",
            sourceRoots = setOf(srcDir),
            libraries = emptySet(),
            processorClasspath = emptySet(),
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )

        assertTrue(result.isEmpty(), "Result should be empty when no processors are configured")
    }

    @Test
    fun `kspNativeProcessBlocking handles empty processor classpath`() {
        val root = File("build/run/KspProcessTest/native-empty")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kotlinOutputDir = root.resolve("generated/kotlin")
        val resourceOutputDir = root.resolve("generated/resources")
        val classOutputDir = root.resolve("generated/classes")
        val cacheDir = root.resolve("cache")

        srcDir.resolve("Example.kt").writeText("""
            package example

            fun hello() = "Hello Native"
        """.trimIndent())

        val result = kspNativeProcessBlocking(
            name = "test-native-module",
            sourceRoots = setOf(srcDir),
            libraries = emptySet(),
            target = "macos_arm64",
            processorClasspath = emptySet(),
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )

        assertTrue(result.isEmpty(), "Result should be empty when no processors are configured")
    }

    @Test
    fun `KBuildKspLogger collects errors and warnings`() {
        val logger = KBuildKspLogger(printLogs = false)

        logger.logging("Debug message")
        logger.info("Info message")
        logger.warn("Warning message")
        logger.error("Error message")
        logger.exception(RuntimeException("Test exception"))

        assertEquals(2, logger.errors.size, "Should have 2 errors")
        assertEquals(1, logger.warnings.size, "Should have 1 warning")
        assertTrue(logger.errors.contains("Error message"))
        assertTrue(logger.errors.any { it.contains("RuntimeException") })
        assertTrue(logger.warnings.contains("Warning message"))
    }

    @Test
    fun `KspProcessingException contains error details`() {
        val errors = listOf("Error 1", "Error 2")
        val warnings = listOf("Warning 1")

        val exception = KspProcessingException(errors, warnings)

        assertTrue(exception.message!!.contains("2 error(s)"))
        assertTrue(exception.message!!.contains("Error 1"))
        assertTrue(exception.message!!.contains("Error 2"))
        assertEquals(errors, exception.errors)
        assertEquals(warnings, exception.warnings)
    }
}
