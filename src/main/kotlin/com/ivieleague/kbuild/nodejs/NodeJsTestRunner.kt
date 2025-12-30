package com.ivieleague.kbuild.nodejs

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.npm.NpmProject
import java.io.File
import java.util.*

/**
 * Runs Kotlin/JS tests in Node.js.
 *
 * This runner executes compiled Kotlin/JS test files in Node.js and parses the results.
 *
 * @param projectDir The project directory containing node_modules
 * @param testFile The compiled test JS/MJS file to run
 */
class NodeJsTestRunner(
    val projectDir: File,
    val testFile: File
) {
    private val npmProject = NpmProject(projectDir)

    /**
     * Callback for individual test results.
     */
    var onTestComplete: ((TestResult) -> Unit)? = null

    /**
     * Run the tests.
     *
     * @return Set of test results
     */
    fun run(): Set<TestResult> {
        if (!NpmProject.isNodeAvailable()) {
            throw IllegalStateException("Node.js is not available. Please install Node.js to run JS tests.")
        }

        if (!testFile.exists()) {
            throw IllegalStateException("Test file not found: $testFile")
        }

        // Run the test file with Node.js
        val results = mutableSetOf<TestResult>()
        val startTime = System.currentTimeMillis()

        val process = ProcessBuilder("node", testFile.absolutePath)
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val errorOutput = StringBuilder()

        // Capture output
        process.inputStream.bufferedReader().forEachLine { line ->
            output.appendLine(line)
            parseTestLine(line)?.let { result ->
                results.add(result)
                onTestComplete?.invoke(result)
            }
        }

        val exitCode = process.waitFor()
        val duration = (System.currentTimeMillis() - startTime) / 1000.0

        // If no structured test results were parsed, create a summary result
        if (results.isEmpty()) {
            val passed = exitCode == 0
            results.add(
                TestResult(
                    identifier = testFile.nameWithoutExtension,
                    passed = passed,
                    standardOutput = output.toString(),
                    standardError = errorOutput.toString(),
                    error = if (!passed) "Process exited with code $exitCode" else null,
                    durationSeconds = duration,
                    runAt = Date(),
                    runOn = "Node.js"
                )
            )
        }

        return results
    }

    /**
     * Parse a line of output for test results.
     *
     * Supports common test output formats:
     * - "✓ testName" or "PASS: testName" for passing tests
     * - "✗ testName" or "FAIL: testName" for failing tests
     */
    private fun parseTestLine(line: String): TestResult? {
        val trimmed = line.trim()

        // Check for pass/fail markers
        val passed = when {
            trimmed.startsWith("✓") || trimmed.startsWith("√") -> true
            trimmed.startsWith("PASS:") || trimmed.startsWith("PASSED:") -> true
            trimmed.startsWith("✗") || trimmed.startsWith("×") -> false
            trimmed.startsWith("FAIL:") || trimmed.startsWith("FAILED:") -> false
            else -> return null
        }

        // Extract test name
        val testName = trimmed
            .removePrefix("✓").removePrefix("√")
            .removePrefix("✗").removePrefix("×")
            .removePrefix("PASS:").removePrefix("PASSED:")
            .removePrefix("FAIL:").removePrefix("FAILED:")
            .trim()

        if (testName.isEmpty()) return null

        return TestResult(
            identifier = testName,
            passed = passed,
            standardOutput = "",
            standardError = "",
            error = if (!passed) "Test failed" else null,
            durationSeconds = 0.0,
            runAt = Date(),
            runOn = "Node.js"
        )
    }
}

/**
 * Configuration for Node.js test execution.
 */
data class NodeJsTestConfig(
    val projectDir: File,
    val testFiles: List<File>,
    val timeout: Long = 60_000
)

/**
 * Run Kotlin/JS tests with Node.js.
 *
 * @param testFile The compiled test file (.js or .mjs)
 * @param projectDir The project directory
 * @return Set of test results
 */
fun runNodeJsTests(
    testFile: File,
    projectDir: File = testFile.parentFile
): Set<TestResult> {
    return NodeJsTestRunner(projectDir, testFile).run()
}

/**
 * Check if Node.js is available for running tests.
 */
fun isNodeJsAvailable(): Boolean = NpmProject.isNodeAvailable()

/**
 * Get the Node.js version.
 */
fun getNodeJsVersion(): String? = NpmProject.getNodeVersion()
