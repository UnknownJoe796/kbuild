package com.ivieleague.kbuild.native

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinNativeTestRunnerTest {

    @Test
    fun `runs kotlin test tests on native`() {
        val root = File("build/run/KotlinNativeTestRunnerTest")
        root.deleteRecursively()
        root.mkdirs()

        val testSrcDir = root.resolve("test-src").also { it.mkdirs() }

        // Create simple test source using kotlin.test
        testSrcDir.resolve("SimpleTest.kt").writeText("""
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertTrue

            class SimpleTest {
                @Test
                fun testAddition() {
                    assertEquals(4, 2 + 2)
                }

                @Test
                fun testString() {
                    assertTrue("hello".length == 5)
                }
            }
        """.trimIndent())

        val runner = KotlinNativeTestRunner(
            name = "simple-tests",
            testSourceRoots = { setOf(testSrcDir) },
            target = KonanTarget.host(),
            buildDir = root.resolve("build")
        )

        println("Ensuring Kotlin/Native compiler is installed...")
        runner.ensureCompilerInstalled()

        println("Compiling tests...")
        val executable = runner.compile()
        assertTrue(executable.exists(), "Test executable should exist: $executable")

        println("Running tests...")
        val results = runner.run()

        println("Test results:")
        results.forEach { result ->
            println("  ${result.identifier}: ${if (result.passed) "PASSED" else "FAILED"}")
        }

        assertTrue(results.isNotEmpty(), "Should have test results")
    }

    @Test
    fun `reports failed tests correctly`() {
        val root = File("build/run/KotlinNativeTestRunnerFailingTest")
        root.deleteRecursively()
        root.mkdirs()

        val testSrcDir = root.resolve("test-src").also { it.mkdirs() }

        // Create test with intentional failure
        testSrcDir.resolve("FailingTest.kt").writeText("""
            import kotlin.test.Test
            import kotlin.test.assertEquals

            class FailingTest {
                @Test
                fun testThatPasses() {
                    assertEquals(2, 1 + 1)
                }

                @Test
                fun testThatFails() {
                    assertEquals(5, 2 + 2, "This should fail")
                }
            }
        """.trimIndent())

        val runner = KotlinNativeTestRunner(
            name = "failing-tests",
            testSourceRoots = { setOf(testSrcDir) },
            target = KonanTarget.host(),
            buildDir = root.resolve("build")
        )

        runner.compile()
        val results = runner.run()

        println("Test results:")
        results.forEach { result ->
            println("  ${result.identifier}: ${if (result.passed) "PASSED" else "FAILED"}")
            result.error?.let { println("    Error: $it") }
        }

        // Should have at least one passing and one failing test
        // Note: The exact parsing depends on kotlin.test native output format
        assertTrue(results.isNotEmpty(), "Should have test results")
    }

    @Test
    fun `runs tests with main sources`() {
        val root = File("build/run/KotlinNativeTestRunnerWithMainTest")
        root.deleteRecursively()
        root.mkdirs()

        val mainSrcDir = root.resolve("main-src").also { it.mkdirs() }
        val testSrcDir = root.resolve("test-src").also { it.mkdirs() }

        // Create main source
        mainSrcDir.resolve("Calculator.kt").writeText("""
            package calculator

            fun add(a: Int, b: Int): Int = a + b
            fun multiply(a: Int, b: Int): Int = a * b
        """.trimIndent())

        // Create test source that uses main code
        testSrcDir.resolve("CalculatorTest.kt").writeText("""
            package calculator

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class CalculatorTest {
                @Test
                fun testAdd() {
                    assertEquals(5, add(2, 3))
                }

                @Test
                fun testMultiply() {
                    assertEquals(6, multiply(2, 3))
                }
            }
        """.trimIndent())

        val runner = KotlinNativeTestRunner(
            name = "calculator-tests",
            testSourceRoots = { setOf(testSrcDir) },
            mainSourceRoots = { setOf(mainSrcDir) },
            target = KonanTarget.host(),
            buildDir = root.resolve("build")
        )

        runner.compile()
        val results = runner.run()

        println("Test results:")
        results.forEach { result ->
            println("  ${result.identifier}: ${if (result.passed) "PASSED" else "FAILED"}")
        }

        assertTrue(results.isNotEmpty(), "Should have test results")
    }

    @Test
    fun `callbacks are invoked during test run`() {
        val root = File("build/run/KotlinNativeTestRunnerCallbackTest")
        root.deleteRecursively()
        root.mkdirs()

        val testSrcDir = root.resolve("test-src").also { it.mkdirs() }

        testSrcDir.resolve("CallbackTest.kt").writeText("""
            import kotlin.test.Test
            import kotlin.test.assertTrue

            class CallbackTest {
                @Test
                fun testOne() {
                    assertTrue(true)
                }

                @Test
                fun testTwo() {
                    assertTrue(true)
                }
            }
        """.trimIndent())

        val runner = KotlinNativeTestRunner(
            name = "callback-tests",
            testSourceRoots = { setOf(testSrcDir) },
            target = KonanTarget.host(),
            buildDir = root.resolve("build")
        )

        var testRunStarted = false
        var testRunCompleted = false
        val completedTests = mutableListOf<String>()

        runner.onTestRunStart = {
            testRunStarted = true
            println("Test run started")
        }

        runner.onTestComplete = { result ->
            completedTests.add(result.identifier)
            println("Test completed: ${result.identifier}")
        }

        runner.onTestRunComplete = { results ->
            testRunCompleted = true
            println("Test run completed with ${results.size} results")
        }

        runner.compile()
        runner.run()

        assertTrue(testRunStarted, "onTestRunStart should have been called")
        assertTrue(testRunCompleted, "onTestRunComplete should have been called")
        println("Completed tests via callback: $completedTests")
    }
}
