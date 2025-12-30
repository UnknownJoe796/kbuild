package com.ivieleague.kbuild.native

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.junit.TestRunSummary
import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

/**
 * A reactive Kotlin/Native test runner that automatically reruns tests when dependencies change.
 *
 * This class monitors:
 * - Test sources (required)
 * - Main sources (optional, if tests depend on main code)
 * - Libraries (klib dependencies)
 *
 * When any dependency changes, tests are automatically recompiled and rerun in the background.
 *
 * State:
 * - notReady: waiting for compilation or tests running
 * - Success: tests completed (may include failures)
 * - exception: test execution failed catastrophically
 *
 * @param name Module name for the test executable
 * @param testSources Reactive set of test source directories
 * @param libraries Reactive set of library dependencies (.klib files)
 * @param mainSources Optional reactive set of main source directories
 * @param target Target platform (defaults to host)
 * @param buildDir Build directory for compiled output
 * @param filter Optional filter to run specific tests
 */
class ReactiveKotlinNativeTestRunner(
    val name: String,
    val testSources: Reactive<Set<File>>,
    val libraries: Reactive<Set<File>>,
    val mainSources: Reactive<Set<File>>? = null,
    val target: KonanTarget = KonanTarget.host(),
    val buildDir: File,
    val filter: ((String) -> Boolean)? = null
) : BaseReactive<Set<TestResult>>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeTestSourcesListener: (() -> Unit)? = null
    private var removeMainSourcesListener: (() -> Unit)? = null
    private var removeLibrariesListener: (() -> Unit)? = null

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
     * Callback invoked when compilation starts.
     */
    var onCompileStart: (() -> Unit)? = null

    /**
     * Callback invoked when compilation succeeds.
     */
    var onCompileComplete: ((File) -> Unit)? = null

    /**
     * Callback invoked on compilation or test error.
     */
    var onError: ((Throwable) -> Unit)? = null

    override fun activate() {
        removeTestSourcesListener = testSources.addListener { triggerTestRun() }
        mainSources?.let { removeMainSourcesListener = it.addListener { triggerTestRun() } }
        removeLibrariesListener = libraries.addListener { triggerTestRun() }

        // Initial test run
        triggerTestRun()
    }

    override fun deactivate() {
        removeTestSourcesListener?.invoke()
        removeMainSourcesListener?.invoke()
        removeLibrariesListener?.invoke()
        removeTestSourcesListener = null
        removeMainSourcesListener = null
        removeLibrariesListener = null
    }

    private fun triggerTestRun() {
        val testSourcesState = testSources.state
        val mainSourcesState = mainSources?.state
        val librariesState = libraries.state

        // Wait for all dependencies to be ready
        if (!testSourcesState.ready || !librariesState.ready) {
            state = ReactiveState.notReady
            return
        }
        if (mainSources != null && mainSourcesState?.ready != true) {
            state = ReactiveState.notReady
            return
        }

        // Check for errors in dependencies
        testSourcesState.exception?.let {
            state = ReactiveState.exception(it)
            onError?.invoke(it)
            return
        }
        mainSourcesState?.exception?.let {
            state = ReactiveState.exception(it)
            onError?.invoke(it)
            return
        }
        librariesState.exception?.let {
            state = ReactiveState.exception(it)
            onError?.invoke(it)
            return
        }

        val testSourceDirs = testSourcesState.getOrNull() ?: return
        val mainSourceDirs = mainSourcesState?.getOrNull()
        val libraryFiles = librariesState.getOrNull() ?: return

        // Validate we have sources
        if (testSourceDirs.isEmpty()) {
            state = ReactiveState.exception(IllegalStateException("No test source directories provided"))
            return
        }

        // Mark as running
        state = ReactiveState.notReady
        onCompileStart?.invoke()

        scope.launch {
            try {
                val results = compileAndRunTests(testSourceDirs, mainSourceDirs, libraryFiles)
                state = ReactiveState(results)
                onTestRunComplete?.invoke(results)
            } catch (e: Exception) {
                state = ReactiveState.exception(e)
                onError?.invoke(e)
            }
        }
    }

    private fun compileAndRunTests(
        testSourceDirs: Set<File>,
        mainSourceDirs: Set<File>?,
        libraryFiles: Set<File>
    ): Set<TestResult> {
        val runner = KotlinNativeTestRunner(
            name = name,
            testSourceRoots = { testSourceDirs },
            libraries = { libraryFiles },
            mainSourceRoots = mainSourceDirs?.let { { it } },
            target = target,
            buildDir = buildDir
        )

        // Wire up callbacks
        runner.onTestComplete = { result ->
            onTestComplete?.invoke(result)
        }
        runner.onTestRunStart = {
            onTestRunStart?.invoke()
        }

        // Compile
        val executable = runner.compile()
        onCompileComplete?.invoke(executable)

        // Run tests
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
     * Clean build artifacts.
     */
    fun clean() {
        buildDir.resolve("native-test/$name").deleteRecursively()
        buildDir.resolve("native-test-cache/$name").deleteRecursively()
    }
}

/**
 * Create a reactive Kotlin/Native test runner.
 */
fun reactiveKotlinNativeTestRunner(
    name: String,
    testSources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
    mainSources: Reactive<Set<File>>? = null,
    target: KonanTarget = KonanTarget.host(),
    buildDir: File,
    filter: ((String) -> Boolean)? = null
): ReactiveKotlinNativeTestRunner = ReactiveKotlinNativeTestRunner(
    name = name,
    testSources = testSources,
    libraries = libraries,
    mainSources = mainSources,
    target = target,
    buildDir = buildDir,
    filter = filter
)
