package com.ivieleague.kbuild.browser

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.junit.TestRunSummary
import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * A reactive browser test runner that automatically reruns tests when the compiled JS changes.
 *
 * This class monitors:
 * - Compiled test JS module (required)
 * - Optional main module (if tests depend on main code)
 *
 * When the JS module changes, tests are automatically rerun in headless Chrome.
 *
 * State:
 * - notReady: waiting for compilation or tests running
 * - Success: tests completed (may include failures)
 * - exception: test execution failed catastrophically
 *
 * @param testModule Reactive file pointing to the compiled test JS
 * @param mainModule Optional reactive file for main module (not typically needed)
 * @param projectDir Directory for the test harness
 * @param filter Optional filter to run specific tests
 * @param chromePath Path to Chrome executable (null for auto-detect)
 */
class ReactiveBrowserTestRunner(
    val testModule: Reactive<File>,
    val mainModule: Reactive<File>? = null,
    val projectDir: File,
    val filter: ((String) -> Boolean)? = null,
    val chromePath: String? = null
) : BaseReactive<Set<TestResult>>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeTestModuleListener: (() -> Unit)? = null
    private var removeMainModuleListener: (() -> Unit)? = null

    /**
     * Callback invoked when a single test completes.
     * Useful for streaming results to UI.
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
     * Callback invoked on error.
     */
    var onError: ((Throwable) -> Unit)? = null

    override fun activate() {
        removeTestModuleListener = testModule.addListener { triggerTestRun() }
        mainModule?.let { removeMainModuleListener = it.addListener { triggerTestRun() } }

        // Initial test run
        triggerTestRun()
    }

    override fun deactivate() {
        removeTestModuleListener?.invoke()
        removeMainModuleListener?.invoke()
        removeTestModuleListener = null
        removeMainModuleListener = null
    }

    private fun triggerTestRun() {
        val testModuleState = testModule.state
        val mainModuleState = mainModule?.state

        // Wait for test module to be ready
        if (!testModuleState.ready) {
            state = ReactiveState.notReady
            return
        }
        if (mainModule != null && mainModuleState?.ready != true) {
            state = ReactiveState.notReady
            return
        }

        // Check for errors
        testModuleState.exception?.let {
            state = ReactiveState.exception(it)
            onError?.invoke(it)
            return
        }
        mainModuleState?.exception?.let {
            state = ReactiveState.exception(it)
            onError?.invoke(it)
            return
        }

        val testJsFile = testModuleState.getOrNull() ?: return

        // Validate file exists
        if (!testJsFile.exists()) {
            val error = IllegalStateException("Test JS file not found: $testJsFile")
            state = ReactiveState.exception(error)
            onError?.invoke(error)
            return
        }

        // Check for Chrome
        if (!BrowserTestRunner.isChromeAvailable()) {
            val error = IllegalStateException("Chrome/Chromium not found. Please install Chrome.")
            state = ReactiveState.exception(error)
            onError?.invoke(error)
            return
        }

        // Mark as running
        state = ReactiveState.notReady
        onTestRunStart?.invoke()

        scope.launch {
            try {
                val results = runTests(testJsFile)
                state = ReactiveState(results)
                onTestRunComplete?.invoke(results)
            } catch (e: Exception) {
                state = ReactiveState.exception(e)
                onError?.invoke(e)
            }
        }
    }

    private fun runTests(testJsFile: File): Set<TestResult> {
        val runner = BrowserTestRunner(
            testJsFile = testJsFile,
            projectDir = projectDir,
            chromePath = chromePath
        )

        // Wire up callbacks
        runner.onTestComplete = { result ->
            onTestComplete?.invoke(result)
        }

        return runner.run(filter)
    }

    /**
     * Summary of the last test run.
     */
    val summary: TestRunSummary?
        get() {
            val results = state.getOrNull() ?: return null
            return TestRunSummary(
                total = results.size,
                passed = results.count { it.passed },
                failed = results.count { !it.passed },
                results = results
            )
        }

    /**
     * Clean up temporary files.
     */
    fun clean() {
        projectDir.resolve(".kbuild-test-harness").deleteRecursively()
    }
}

/**
 * Create a reactive browser test runner.
 */
fun reactiveBrowserTestRunner(
    testModule: Reactive<File>,
    mainModule: Reactive<File>? = null,
    projectDir: File,
    filter: ((String) -> Boolean)? = null,
    chromePath: String? = null
): ReactiveBrowserTestRunner = ReactiveBrowserTestRunner(
    testModule = testModule,
    mainModule = mainModule,
    projectDir = projectDir,
    filter = filter,
    chromePath = chromePath
)
