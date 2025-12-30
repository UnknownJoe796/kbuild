package com.ivieleague.kbuild.browser

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJsCompile
import com.ivieleague.kbuild.kotlin.JsModuleKind
import com.ivieleague.kbuild.kotlin.JsOutputMode
import com.ivieleague.kbuild.maven.KlibDependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserTestRunnerTest {

    @Test
    fun `SimpleHttpServer serves files correctly`() {
        val root = File("build/run/SimpleHttpServerTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create test files
        root.resolve("index.html").writeText("<html><body>Hello</body></html>")
        root.resolve("test.js").writeText("console.log('test');")
        root.resolve("style.css").writeText("body { color: red; }")

        val server = SimpleHttpServer(root, port = 0).start()

        try {
            val url = server.url
            println("Server started at: $url")

            // Test that server is running
            assertTrue(server.isRunning)

            // Test content type detection
            val htmlContent = java.net.URL("$url/index.html").openConnection().apply {
                connect()
            }.getHeaderField("Content-Type")
            assertTrue(htmlContent?.contains("text/html") == true, "Should serve HTML with correct content type")

            val jsContent = java.net.URL("$url/test.js").openConnection().apply {
                connect()
            }.getHeaderField("Content-Type")
            assertTrue(jsContent?.contains("javascript") == true, "Should serve JS with correct content type")

            val cssContent = java.net.URL("$url/style.css").openConnection().apply {
                connect()
            }.getHeaderField("Content-Type")
            assertTrue(cssContent?.contains("text/css") == true, "Should serve CSS with correct content type")

        } finally {
            server.stop()
        }

        assertTrue(!server.isRunning, "Server should be stopped")
    }

    @Test
    fun `BrowserTestHarness generates valid HTML`() {
        val html = BrowserTestHarness.generateTestHtml("test.mjs", "Test Title")

        assertTrue(html.contains("<!DOCTYPE html>"), "Should be valid HTML")
        assertTrue(html.contains("test.mjs"), "Should reference test module")
        assertTrue(html.contains("Test Title"), "Should have title")
        assertTrue(html.contains("__kbuildTestReporter"), "Should have test reporter")
        assertTrue(html.contains(BrowserTestHarness.Markers.TEST_PASS), "Should have pass marker")
        assertTrue(html.contains(BrowserTestHarness.Markers.RUN_COMPLETE), "Should have completion marker")
    }

    @Test
    fun `BrowserTestHarness writes harness files`() {
        val root = File("build/run/BrowserTestHarnessTest")
        root.deleteRecursively()
        root.mkdirs()

        val testHtml = BrowserTestHarness.writeTestHarness(
            outputDir = root,
            testModulePath = "my-tests.mjs",
            title = "My Tests"
        )

        assertTrue(testHtml.exists(), "test.html should be created")
        assertTrue(root.resolve("test-wrapper.js").exists(), "test-wrapper.js should be created")

        val htmlContent = testHtml.readText()
        assertTrue(htmlContent.contains("my-tests.mjs"), "Should reference correct test module")
    }

    @Test
    fun `Chrome detection works`() {
        val chrome = BrowserTestRunner.findChrome()
        if (chrome != null) {
            println("Found Chrome at: $chrome")
            val version = BrowserTestRunner.getChromeVersion()
            println("Chrome version: $version")
            assertTrue(chrome.exists())
            assertTrue(chrome.canExecute())
        } else {
            println("Chrome not found on this system (test skipped)")
        }
    }

    @Test
    fun `runs simple JS test in browser`() {
        // Skip if Chrome is not available
        assumeTrue(BrowserTestRunner.isChromeAvailable(), "Chrome not available")

        val root = File("build/run/BrowserTestRunnerSimpleTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create a simple JavaScript test file
        // This doesn't use kotlin.test, just demonstrates the harness works
        val testJs = root.resolve("simple-test.mjs")
        testJs.writeText("""
            // Simple test that uses our reporter
            const reporter = window.__kbuildTestReporter;

            if (reporter) {
                reporter.suiteStarted('SimpleTests');

                reporter.testStarted('testAddition');
                const result = 2 + 2;
                if (result === 4) {
                    reporter.testPassed('testAddition', 1);
                } else {
                    reporter.testFailed('testAddition', new Error('Expected 4, got ' + result), 1);
                }

                reporter.testStarted('testString');
                if ('hello'.length === 5) {
                    reporter.testPassed('testString', 2);
                } else {
                    reporter.testFailed('testString', new Error('Expected 5'), 2);
                }

                reporter.suiteFinished('SimpleTests');
                reporter.runComplete();
            } else {
                console.log('No reporter found');
            }

            export function main() {}
        """.trimIndent())

        val runner = BrowserTestRunner(
            testJsFile = testJs,
            projectDir = root,
            timeoutSeconds = 30
        )

        var startCalled = false
        var completeCalled = false
        val testResults = mutableListOf<String>()

        runner.onTestRunStart = { startCalled = true }
        runner.onTestComplete = { result ->
            println("Test: ${result.identifier} - ${if (result.passed) "PASSED" else "FAILED"}")
            testResults.add(result.identifier)
        }
        runner.onTestRunComplete = { completeCalled = true }

        val results = runner.run()

        println("\nTest Results:")
        results.forEach { result ->
            println("  ${result.identifier}: ${if (result.passed) "PASSED" else "FAILED"}")
        }

        assertTrue(startCalled, "onTestRunStart should be called")
        assertTrue(completeCalled, "onTestRunComplete should be called")
        assertTrue(results.isNotEmpty(), "Should have test results")
    }

    @Test
    fun `compiles and runs Kotlin JS tests in browser`() {
        // Skip if Chrome is not available
        assumeTrue(BrowserTestRunner.isChromeAvailable(), "Chrome not available")

        val root = File("build/run/BrowserTestRunnerKotlinTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("output")

        // Create simple Kotlin test source
        srcDir.resolve("SimpleTest.kt").writeText("""
            fun main() {
                // Simple test - just print results
                println("Running browser tests...")

                val tests = listOf(
                    "testAddition" to { 2 + 2 == 4 },
                    "testString" to { "hello".length == 5 },
                    "testList" to { listOf(1, 2, 3).size == 3 }
                )

                var passed = 0
                var failed = 0

                tests.forEach { (name, test) ->
                    try {
                        if (test()) {
                            println("PASSED: ${'$'}name")
                            passed++
                        } else {
                            println("FAILED: ${'$'}name")
                            failed++
                        }
                    } catch (e: Exception) {
                        println("FAILED: ${'$'}name - ${'$'}{e.message}")
                        failed++
                    }
                }

                println("Tests complete: ${'$'}passed passed, ${'$'}failed failed")
            }
        """.trimIndent())

        // Get Kotlin/JS stdlib
        val jsStdlib = MavenAether.libraries(
            listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
        ).map { it.default }.toSet()

        // Compile to JS
        @Suppress("DEPRECATION")
        val compile = KotlinJsCompile(
            name = "browser-test",
            sourceRoots = { setOf(srcDir) },
            libraries = { jsStdlib },
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            outputDir = outputDir
        )

        println("Compiling Kotlin to JS...")
        val jsOutput = compile()
        assertTrue(jsOutput.exists(), "JS output should exist: $jsOutput")

        println("Running tests in browser...")
        val runner = BrowserTestRunner(
            testJsFile = jsOutput,
            projectDir = root,
            timeoutSeconds = 60
        )

        val results = runner.run()

        println("\nBrowser Test Results:")
        results.forEach { result ->
            println("  ${result.identifier}: ${if (result.passed) "PASSED" else "FAILED"}")
            result.error?.let { println("    Error: $it") }
        }

        assertTrue(results.isNotEmpty(), "Should have test results")
    }
}
