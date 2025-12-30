package com.ivieleague.kbuild.browser

import java.io.File

/**
 * Generates HTML and JavaScript harness files for running Kotlin/JS tests in a browser.
 *
 * The harness:
 * 1. Loads the compiled test module
 * 2. Hooks into kotlin.test framework output
 * 3. Outputs test results in a parseable format to console
 * 4. Signals completion for headless browser to detect
 */
object BrowserTestHarness {

    /**
     * Markers for parsing test output from console.
     */
    object Markers {
        const val TEST_START = "[KBUILD:TEST:START]"
        const val TEST_PASS = "[KBUILD:TEST:PASS]"
        const val TEST_FAIL = "[KBUILD:TEST:FAIL]"
        const val TEST_SKIP = "[KBUILD:TEST:SKIP]"
        const val SUITE_START = "[KBUILD:SUITE:START]"
        const val SUITE_END = "[KBUILD:SUITE:END]"
        const val RUN_COMPLETE = "[KBUILD:RUN:COMPLETE]"
        const val ERROR = "[KBUILD:ERROR]"
    }

    /**
     * Generate the test HTML page.
     *
     * @param testModulePath Relative path to the test JS module (e.g., "test.mjs")
     * @param title Page title
     * @return HTML content
     */
    fun generateTestHtml(
        testModulePath: String,
        title: String = "Kotlin/JS Browser Tests"
    ): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$title</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            margin: 20px;
            background: #1a1a2e;
            color: #eee;
        }
        #status {
            padding: 10px;
            margin-bottom: 20px;
            border-radius: 4px;
            background: #16213e;
        }
        #results {
            font-family: monospace;
            white-space: pre-wrap;
            background: #0f0f23;
            padding: 15px;
            border-radius: 4px;
            max-height: 80vh;
            overflow-y: auto;
        }
        .pass { color: #4ade80; }
        .fail { color: #f87171; }
        .skip { color: #fbbf24; }
        .info { color: #60a5fa; }
    </style>
</head>
<body>
    <h1>$title</h1>
    <div id="status">Loading tests...</div>
    <div id="results"></div>

    <script type="module">
        // Test result reporter
        const results = [];
        let testsStarted = 0;
        let testsPassed = 0;
        let testsFailed = 0;
        let testsSkipped = 0;

        const statusEl = document.getElementById('status');
        const resultsEl = document.getElementById('results');

        function log(message, className = '') {
            const line = document.createElement('div');
            line.textContent = message;
            if (className) line.className = className;
            resultsEl.appendChild(line);
            resultsEl.scrollTop = resultsEl.scrollHeight;
        }

        function updateStatus() {
            const total = testsPassed + testsFailed + testsSkipped;
            statusEl.innerHTML = 'Tests: <span class="pass">' + testsPassed + ' passed</span>, ' +
                '<span class="fail">' + testsFailed + ' failed</span>, ' +
                '<span class="skip">' + testsSkipped + ' skipped</span> / ' + testsStarted + ' total';
        }

        // Hook console.log to detect test framework output
        const originalLog = console.log;
        const originalError = console.error;
        const originalWarn = console.warn;

        // Export reporter for kotlin.test
        window.__kbuildTestReporter = {
            testStarted: function(name) {
                testsStarted++;
                console.log('${Markers.TEST_START} ' + name);
                log('▶ ' + name, 'info');
                updateStatus();
            },
            testPassed: function(name, durationMs) {
                testsPassed++;
                console.log('${Markers.TEST_PASS} ' + name + ' (' + durationMs + 'ms)');
                log('✓ ' + name + ' (' + durationMs + 'ms)', 'pass');
                updateStatus();
            },
            testFailed: function(name, error, durationMs) {
                testsFailed++;
                const errorMsg = error ? (error.message || String(error)) : 'Unknown error';
                console.log('${Markers.TEST_FAIL} ' + name + ' (' + durationMs + 'ms): ' + errorMsg);
                log('✗ ' + name + ' (' + durationMs + 'ms)', 'fail');
                log('  Error: ' + errorMsg, 'fail');
                if (error && error.stack) {
                    log('  ' + error.stack.split('\\n').slice(1).join('\\n  '), 'fail');
                }
                updateStatus();
            },
            testSkipped: function(name) {
                testsSkipped++;
                console.log('${Markers.TEST_SKIP} ' + name);
                log('○ ' + name + ' (skipped)', 'skip');
                updateStatus();
            },
            suiteStarted: function(name) {
                console.log('${Markers.SUITE_START} ' + name);
                log('\\n━━━ ' + name + ' ━━━', 'info');
            },
            suiteFinished: function(name) {
                console.log('${Markers.SUITE_END} ' + name);
            },
            runComplete: function() {
                console.log('${Markers.RUN_COMPLETE} passed=' + testsPassed + ' failed=' + testsFailed + ' skipped=' + testsSkipped);
                log('\\n════════════════════════════════════════', 'info');
                log('Test run complete: ' + testsPassed + ' passed, ' + testsFailed + ' failed, ' + testsSkipped + ' skipped', testsFailed > 0 ? 'fail' : 'pass');
                statusEl.innerHTML = '✓ Complete: <span class="pass">' + testsPassed + ' passed</span>, ' +
                    '<span class="fail">' + testsFailed + ' failed</span>, ' +
                    '<span class="skip">' + testsSkipped + ' skipped</span>';
            }
        };

        // Handle uncaught errors
        window.onerror = function(message, source, line, col, error) {
            console.log('${Markers.ERROR} ' + message);
            log('ERROR: ' + message, 'fail');
            return false;
        };

        window.onunhandledrejection = function(event) {
            console.log('${Markers.ERROR} Unhandled rejection: ' + event.reason);
            log('ERROR: Unhandled rejection: ' + event.reason, 'fail');
        };

        // Load and run tests
        try {
            log('Loading test module: $testModulePath', 'info');
            const testModule = await import('./$testModulePath');

            // Try to find and invoke the test entry point
            // kotlin.test generates a main function that runs all tests
            if (typeof testModule.main === 'function') {
                log('Running tests via main()...', 'info');
                await testModule.main();
            } else if (typeof testModule.default === 'function') {
                log('Running tests via default export...', 'info');
                await testModule.default();
            } else {
                // List available exports for debugging
                const exports = Object.keys(testModule);
                log('Available exports: ' + exports.join(', '), 'info');

                // If there's a runTests function, try that
                if (typeof testModule.runTests === 'function') {
                    log('Running tests via runTests()...', 'info');
                    await testModule.runTests();
                } else {
                    log('Warning: No test entry point found. Module loaded but tests may not run automatically.', 'skip');
                }
            }

            // Signal completion if reporter hasn't already
            setTimeout(() => {
                if (!window.__kbuildTestsCompleted) {
                    window.__kbuildTestReporter.runComplete();
                    window.__kbuildTestsCompleted = true;
                }
            }, 1000);

        } catch (error) {
            console.log('${Markers.ERROR} ' + error.message);
            log('Fatal error loading tests: ' + error.message, 'fail');
            if (error.stack) {
                log(error.stack, 'fail');
            }
            console.log('${Markers.RUN_COMPLETE} passed=0 failed=1 skipped=0');
        }
    </script>
</body>
</html>
    """.trimIndent()

    /**
     * Generate a test wrapper that can be used to configure kotlin.test
     * to use our reporter.
     *
     * This should be loaded before the main test module.
     */
    fun generateTestWrapper(): String = """
// KBuild test wrapper - configures kotlin.test to use our reporter
(function() {
    'use strict';

    const reporter = window.__kbuildTestReporter;
    if (!reporter) {
        console.error('${Markers.ERROR} Test reporter not found. Is test.html loaded correctly?');
        return;
    }

    // Try to hook into kotlin.test framework
    // The exact mechanism depends on the Kotlin version and test framework configuration

    // For kotlin.test with JS IR backend, tests are typically discovered and run automatically
    // We hook into console output to detect test results

    const originalLog = console.log;
    console.log = function(...args) {
        const message = args.join(' ');

        // Parse kotlin.test output patterns
        // These patterns match the standard kotlin.test output
        if (message.includes('PASSED:')) {
            const match = message.match(/PASSED:\s*(.+)/);
            if (match) reporter.testPassed(match[1], 0);
        } else if (message.includes('FAILED:')) {
            const match = message.match(/FAILED:\s*(.+)/);
            if (match) reporter.testFailed(match[1], new Error('Test failed'), 0);
        } else if (message.includes('SKIPPED:')) {
            const match = message.match(/SKIPPED:\s*(.+)/);
            if (match) reporter.testSkipped(match[1]);
        }

        originalLog.apply(console, args);
    };
})();
    """.trimIndent()

    /**
     * Write the test harness files to a directory.
     *
     * @param outputDir Directory to write files to
     * @param testModulePath Relative path to the test module
     * @param title Page title
     * @return The path to the generated test.html file
     */
    fun writeTestHarness(
        outputDir: File,
        testModulePath: String,
        title: String = "Kotlin/JS Browser Tests"
    ): File {
        outputDir.mkdirs()

        val testHtmlFile = outputDir.resolve("test.html")
        testHtmlFile.writeText(generateTestHtml(testModulePath, title))

        val testWrapperFile = outputDir.resolve("test-wrapper.js")
        testWrapperFile.writeText(generateTestWrapper())

        return testHtmlFile
    }
}
