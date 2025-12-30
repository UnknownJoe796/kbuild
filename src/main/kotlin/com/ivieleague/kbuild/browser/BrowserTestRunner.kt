package com.ivieleague.kbuild.browser

import com.ivieleague.kbuild.common.TestResult
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Runs Kotlin/JS tests in a headless Chrome browser.
 *
 * This runner:
 * 1. Generates a test HTML harness that loads the compiled test JS
 * 2. Starts a simple HTTP server to serve the files
 * 3. Launches headless Chrome to run the tests
 * 4. Captures console output and parses test results
 *
 * @param testJsFile The compiled test JavaScript file (.js or .mjs)
 * @param projectDir Directory containing the test files (for serving)
 * @param port Port for the HTTP server (0 for auto-select)
 * @param chromePath Path to Chrome executable (null for auto-detect)
 * @param timeoutSeconds Maximum time to wait for tests to complete
 */
class BrowserTestRunner(
    val testJsFile: File,
    val projectDir: File = testJsFile.parentFile,
    val port: Int = 0,
    val chromePath: String? = null,
    val timeoutSeconds: Long = 120
) {
    private var server: SimpleHttpServer? = null

    /**
     * Callback invoked when a single test completes.
     */
    var onTestComplete: ((TestResult) -> Unit)? = null

    /**
     * Callback invoked when test run starts.
     */
    var onTestRunStart: (() -> Unit)? = null

    /**
     * Callback invoked when test run finishes.
     */
    var onTestRunComplete: ((Set<TestResult>) -> Unit)? = null

    /**
     * Run all tests.
     *
     * @return Set of test results
     */
    fun run(): Set<TestResult> {
        return run(filter = null)
    }

    /**
     * Run tests with an optional filter.
     *
     * @param filter Optional predicate to filter test names
     * @return Set of test results
     */
    fun run(filter: ((String) -> Boolean)?): Set<TestResult> {
        if (!testJsFile.exists()) {
            throw IllegalStateException("Test JS file not found: $testJsFile")
        }

        val chrome = findChrome()
            ?: throw IllegalStateException("Chrome/Chromium not found. Please install Chrome or set chromePath.")

        // Setup test harness
        val harnessDir = projectDir.resolve(".kbuild-test-harness")
        harnessDir.mkdirs()

        // Copy test JS to harness directory if it's not already there
        val testJsName = testJsFile.name
        val testJsInHarness = harnessDir.resolve(testJsName)
        if (testJsFile.canonicalPath != testJsInHarness.canonicalPath) {
            testJsFile.copyTo(testJsInHarness, overwrite = true)
            // Also copy source map if it exists
            val sourceMap = File(testJsFile.absolutePath + ".map")
            if (sourceMap.exists()) {
                sourceMap.copyTo(harnessDir.resolve("$testJsName.map"), overwrite = true)
            }
        }

        // Generate test harness HTML
        val testHtmlFile = BrowserTestHarness.writeTestHarness(
            outputDir = harnessDir,
            testModulePath = testJsName,
            title = "Kotlin/JS Browser Tests - ${testJsFile.nameWithoutExtension}"
        )

        // Start HTTP server
        server = SimpleHttpServer(harnessDir, port).start()
        val serverUrl = server!!.url

        try {
            onTestRunStart?.invoke()

            val results = runInChrome(chrome, "$serverUrl/test.html", filter)

            onTestRunComplete?.invoke(results)
            return results
        } finally {
            server?.stop()
            server = null
            // Clean up harness directory
            harnessDir.deleteRecursively()
        }
    }

    private fun runInChrome(
        chromePath: File,
        testUrl: String,
        filter: ((String) -> Boolean)?
    ): Set<TestResult> {
        val results = mutableSetOf<TestResult>()
        val startTime = System.currentTimeMillis()
        var runComplete = false

        // Build Chrome command for headless mode
        val command = mutableListOf(
            chromePath.absolutePath,
            "--headless=new",
            "--disable-gpu",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-extensions",
            "--remote-debugging-pipe",
            "--enable-logging=stderr",
            "--v=0",
            testUrl
        )

        println("Starting headless Chrome: ${command.joinToString(" ")}")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // Read output and parse test results
        val outputThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                parseConsoleLine(line, startTime)?.let { result ->
                    // Apply filter if present
                    if (filter == null || filter(result.identifier)) {
                        results.add(result)
                        onTestComplete?.invoke(result)
                    }
                }

                // Check for completion
                if (line.contains(BrowserTestHarness.Markers.RUN_COMPLETE)) {
                    runComplete = true
                }
            }
        }
        outputThread.start()

        // Wait for tests to complete or timeout
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (!runComplete && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            if (!process.isAlive) {
                break
            }
        }

        // Terminate Chrome
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }

        outputThread.join(5000)

        if (!runComplete) {
            println("Warning: Test run did not complete normally (timeout or process died)")
        }

        // If no results were captured, create a summary result
        if (results.isEmpty()) {
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            results.add(
                TestResult(
                    identifier = testJsFile.nameWithoutExtension,
                    passed = runComplete,
                    standardOutput = "",
                    standardError = "",
                    error = if (!runComplete) "Test run did not complete" else null,
                    durationSeconds = duration,
                    runAt = Date(),
                    runOn = "Chrome (headless)"
                )
            )
        }

        return results
    }

    private fun parseConsoleLine(line: String, runStartTime: Long): TestResult? {
        val markers = BrowserTestHarness.Markers

        return when {
            line.contains(markers.TEST_PASS) -> {
                val content = line.substringAfter(markers.TEST_PASS).trim()
                val (name, durationMs) = parseTestResult(content)
                TestResult(
                    identifier = name,
                    passed = true,
                    standardOutput = "",
                    standardError = "",
                    error = null,
                    durationSeconds = durationMs / 1000.0,
                    runAt = Date(),
                    runOn = "Chrome (headless)"
                )
            }

            line.contains(markers.TEST_FAIL) -> {
                val content = line.substringAfter(markers.TEST_FAIL).trim()
                val (name, durationMs, error) = parseTestResultWithError(content)
                TestResult(
                    identifier = name,
                    passed = false,
                    standardOutput = "",
                    standardError = "",
                    error = error ?: "Test failed",
                    durationSeconds = durationMs / 1000.0,
                    runAt = Date(),
                    runOn = "Chrome (headless)"
                )
            }

            line.contains(markers.TEST_SKIP) -> {
                val name = line.substringAfter(markers.TEST_SKIP).trim()
                TestResult(
                    identifier = name,
                    passed = true, // Skipped tests are considered "passing"
                    standardOutput = "",
                    standardError = "",
                    error = "Skipped",
                    durationSeconds = 0.0,
                    runAt = Date(),
                    runOn = "Chrome (headless)"
                )
            }

            line.contains(markers.ERROR) -> {
                val error = line.substringAfter(markers.ERROR).trim()
                TestResult(
                    identifier = "Error",
                    passed = false,
                    standardOutput = "",
                    standardError = "",
                    error = error,
                    durationSeconds = 0.0,
                    runAt = Date(),
                    runOn = "Chrome (headless)"
                )
            }

            else -> null
        }
    }

    private fun parseTestResult(content: String): Pair<String, Double> {
        // Format: "testName (Xms)"
        val match = Regex("""(.+?)\s*\((\d+)ms\)""").find(content)
        return if (match != null) {
            Pair(match.groupValues[1].trim(), match.groupValues[2].toDouble())
        } else {
            Pair(content, 0.0)
        }
    }

    private fun parseTestResultWithError(content: String): Triple<String, Double, String?> {
        // Format: "testName (Xms): error message"
        val match = Regex("""(.+?)\s*\((\d+)ms\)(?::\s*(.+))?""").find(content)
        return if (match != null) {
            Triple(
                match.groupValues[1].trim(),
                match.groupValues[2].toDouble(),
                match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
            )
        } else {
            Triple(content, 0.0, null)
        }
    }

    /**
     * Clean up any temporary files.
     */
    fun clean() {
        projectDir.resolve(".kbuild-test-harness").deleteRecursively()
    }

    companion object {
        /**
         * Find Chrome/Chromium executable on the system.
         *
         * @return Path to Chrome executable, or null if not found
         */
        fun findChrome(): File? {
            // Check common Chrome locations
            val candidates = listOf(
                // macOS
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",

                // Linux
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/snap/bin/chromium",

                // Windows
                System.getenv("LOCALAPPDATA")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                System.getenv("PROGRAMFILES")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" }
            )

            for (candidate in candidates.filterNotNull()) {
                val file = File(candidate)
                if (file.exists() && file.canExecute()) {
                    return file
                }
            }

            // Try to find via PATH
            val chromeName = if (System.getProperty("os.name").lowercase().contains("windows")) {
                "chrome.exe"
            } else {
                "google-chrome"
            }

            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
            for (dir in pathDirs) {
                val file = File(dir, chromeName)
                if (file.exists() && file.canExecute()) {
                    return file
                }
            }

            return null
        }

        /**
         * Check if Chrome is available on the system.
         */
        fun isChromeAvailable(): Boolean = findChrome() != null

        /**
         * Get Chrome version if available.
         */
        fun getChromeVersion(): String? {
            val chrome = findChrome() ?: return null
            return try {
                val process = ProcessBuilder(chrome.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Create a browser test runner.
 */
fun browserTestRunner(
    testJsFile: File,
    projectDir: File = testJsFile.parentFile,
    port: Int = 0,
    chromePath: String? = null
): BrowserTestRunner = BrowserTestRunner(
    testJsFile = testJsFile,
    projectDir = projectDir,
    port = port,
    chromePath = chromePath
)
