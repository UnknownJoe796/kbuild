package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.ReactiveKotlinCompile
import com.ivieleague.kbuild.kotlin.asReactive
import com.ivieleague.kbuild.maven.Dependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ReactiveJUnitRunTest {

    @Test
    fun `runs tests reactively when compilation completes`() {
        val root = File("build/run/ReactiveJUnitRunTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val buildDir = root.resolve("build")
        val cacheDir = buildDir.resolve("cache")
        val outputDir = buildDir.resolve("classes")

        // Create test source
        srcDir.resolve("TestClass.kt").writeText("""
            package test
            import kotlin.test.Test
            import kotlin.test.assertTrue

            class SampleTest {
                @Test
                fun passingTest() {
                    assertTrue(1 + 1 == 2)
                }

                @Test
                fun anotherPassingTest() {
                    assertTrue("hello".length == 5)
                }

                @Test
                fun failingTest() {
                    throw RuntimeException("Expected failure")
                }
            }
        """.trimIndent())

        // Get test dependencies
        val dependencies = MavenAether.libraries(
            listOf(
                Dependency(Kotlin.standardLibraryJvmId).aether(),
                Dependency(Kotlin.standardLibraryTestJunit5Id).aether()
            )
        ).map { it.default }.toSet()

        // Create reactive compilation
        val sources = DirectoryWatch(srcDir, "**/*.kt")
        val compile = ReactiveKotlinCompile(
            name = "reactive-junit-test",
            sources = sources,
            classpath = dependencies.asReactive(),
            cache = cacheDir,
            outputFolder = outputDir
        )

        // Create reactive test runner
        val testRun = ReactiveJUnitRun(
            testModule = compile,
            classpath = dependencies.asReactive()
        )

        val latch = CountDownLatch(1)
        var testResults: Set<TestResult>? = null
        val individualResults = mutableListOf<TestResult>()

        testRun.onTestComplete = { result ->
            println("Test completed: ${result.identifier} - ${if (result.passed) "PASSED" else "FAILED"}")
            individualResults.add(result)
        }

        testRun.onTestRunComplete = { results ->
            println("Test run complete: ${results.size} tests")
            testResults = results
            latch.countDown()
        }

        val removeListener = testRun.addListener {
            val state = testRun.state
            println("State changed: ready=${state.ready}, success=${state.success}, exception=${state.exception}")
        }

        try {
            val completed = latch.await(120, TimeUnit.SECONDS)
            assertTrue(completed, "Tests should complete within timeout")

            val results = testResults
            assertTrue(results != null, "Should have test results")
            assertTrue(results.isNotEmpty(), "Should have at least one test result")

            val summary = testRun.summary
            println("Summary: $summary")
            assertTrue(summary != null, "Should have summary")
            assertTrue(summary.passed >= 2, "Should have at least 2 passing tests")
            assertTrue(summary.failed >= 1, "Should have at least 1 failing test")

            // Verify individual callback was invoked
            assertTrue(individualResults.isNotEmpty(), "Individual results should be captured")
        } finally {
            removeListener()
        }
    }

    @Test
    fun `supports test filtering`() {
        val root = File("build/run/ReactiveJUnitRunFilterTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val buildDir = root.resolve("build")

        srcDir.resolve("FilterTest.kt").writeText("""
            package test
            import kotlin.test.Test

            class IncludedTest {
                @Test fun test1() {}
            }

            class ExcludedTest {
                @Test fun test2() {}
            }
        """.trimIndent())

        val dependencies = MavenAether.libraries(
            listOf(
                Dependency(Kotlin.standardLibraryJvmId).aether(),
                Dependency(Kotlin.standardLibraryTestJunit5Id).aether()
            )
        ).map { it.default }.toSet()

        val sources = DirectoryWatch(srcDir, "**/*.kt")
        val compile = ReactiveKotlinCompile(
            name = "filter-test",
            sources = sources,
            classpath = dependencies.asReactive(),
            cache = buildDir.resolve("cache"),
            outputFolder = buildDir.resolve("classes")
        )

        // Filter to only run IncludedTest
        val testRun = ReactiveJUnitRun(
            testModule = compile,
            classpath = dependencies.asReactive(),
            filter = { it.contains("Included") }
        )

        val latch = CountDownLatch(1)
        var testResults: Set<TestResult>? = null

        testRun.onTestRunComplete = { results ->
            testResults = results
            latch.countDown()
        }

        val removeListener = testRun.addListener { }

        try {
            val completed = latch.await(120, TimeUnit.SECONDS)
            assertTrue(completed, "Tests should complete")

            val results = testResults
            assertTrue(results != null && results.size == 1, "Should have exactly 1 test result (filtered)")
            assertTrue(results.first().identifier.contains("Included"), "Should only run IncludedTest")
        } finally {
            removeListener()
        }
    }
}
