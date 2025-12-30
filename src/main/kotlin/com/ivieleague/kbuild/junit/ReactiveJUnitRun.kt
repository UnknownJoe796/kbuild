package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.jvm.JVM
import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestExecutionResult.Status
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.File
import java.util.*
import kotlin.jvm.optionals.getOrNull

/**
 * A reactive JUnit test runner that automatically reruns tests when dependencies change.
 *
 * This class monitors:
 * - Test module compilation (required)
 * - Main module compilation (optional, if tests depend on main sources)
 * - Classpath changes
 *
 * When any dependency changes, tests are automatically rerun in the background.
 *
 * State:
 * - notReady: waiting for compilation or tests running
 * - Success: tests completed (may include failures)
 * - exception: test execution failed catastrophically
 *
 * @param testModule Reactive compilation of test sources
 * @param mainModule Optional reactive compilation of main sources (if tests depend on them)
 * @param classpath Reactive classpath for test execution
 * @param filter Optional filter to run specific test classes/methods
 */
class ReactiveJUnitRun(
    val testModule: Reactive<File>,
    val mainModule: Reactive<File>? = null,
    val classpath: Reactive<Set<File>>,
    val filter: ((String) -> Boolean)? = null
) : BaseReactive<Set<TestResult>>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeTestModuleListener: (() -> Unit)? = null
    private var removeMainModuleListener: (() -> Unit)? = null
    private var removeClasspathListener: (() -> Unit)? = null

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

    override fun activate() {
        removeTestModuleListener = testModule.addListener { triggerTestRun() }
        mainModule?.let { removeMainModuleListener = it.addListener { triggerTestRun() } }
        removeClasspathListener = classpath.addListener { triggerTestRun() }

        // Initial test run
        triggerTestRun()
    }

    override fun deactivate() {
        removeTestModuleListener?.invoke()
        removeMainModuleListener?.invoke()
        removeClasspathListener?.invoke()
        removeTestModuleListener = null
        removeMainModuleListener = null
        removeClasspathListener = null
    }

    private fun triggerTestRun() {
        val testModuleState = testModule.state
        val mainModuleState = mainModule?.state
        val classpathState = classpath.state

        // Wait for all dependencies to be ready
        if (!testModuleState.ready || !classpathState.ready) {
            state = ReactiveState.notReady
            return
        }
        if (mainModule != null && mainModuleState?.ready != true) {
            state = ReactiveState.notReady
            return
        }

        // Check for compilation errors
        testModuleState.exception?.let {
            state = ReactiveState.exception(it)
            return
        }
        mainModuleState?.exception?.let {
            state = ReactiveState.exception(it)
            return
        }

        val testModuleDir = testModuleState.getOrNull() ?: return
        val mainModuleDir = mainModuleState?.getOrNull()
        val classpathFiles = classpathState.getOrNull() ?: return

        // Mark as running
        state = ReactiveState.notReady
        onTestRunStart?.invoke()

        scope.launch {
            try {
                val results = runTests(testModuleDir, mainModuleDir, classpathFiles)
                state = ReactiveState(results)
                onTestRunComplete?.invoke(results)
            } catch (e: Exception) {
                state = ReactiveState.exception(e)
            }
        }
    }

    private fun runTests(
        testModuleDir: File,
        mainModuleDir: File?,
        classpathFiles: Set<File>
    ): Set<TestResult> {
        val allClasspath = buildList {
            addAll(classpathFiles)
            mainModuleDir?.let { add(it) }
            add(testModuleDir)
        }

        val loaded = JVM.load(allClasspath)
        val testClasses = findTestClasses(testModuleDir, loaded)

        // Apply filter if provided
        val filteredClasses = if (filter != null) {
            testClasses.filter { filter.invoke(it) }
        } else {
            testClasses
        }

        if (filteredClasses.isEmpty()) {
            return emptySet()
        }

        val results = mutableSetOf<TestResult>()
        val startTime = System.currentTimeMillis()

        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(filteredClasses.map { DiscoverySelectors.selectClass(loaded.loadClass(it)) })
                .build(),
            object : TestExecutionListener {
                override fun executionFinished(
                    testIdentifier: TestIdentifier,
                    testExecutionResult: TestExecutionResult
                ) {
                    // Only report actual test methods, not containers
                    if (!testIdentifier.isTest) return

                    val result = TestResult(
                        identifier = testIdentifier.uniqueId,
                        passed = testExecutionResult.status == Status.SUCCESSFUL,
                        standardOutput = "",
                        standardError = "",
                        error = testExecutionResult.throwable.getOrNull()?.let {
                            "${it::class.simpleName}: ${it.message}"
                        },
                        durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0,
                        runAt = Date(),
                        runOn = "JUnit 5"
                    )
                    results.add(result)
                    onTestComplete?.invoke(result)
                }
            }
        )

        return results
    }

    private fun findTestClasses(testModuleDir: File, loader: JVM.JarFileLoader): Set<String> {
        val annotationClass = try {
            loader.loadClass("org.junit.jupiter.api.Test")
        } catch (e: ClassNotFoundException) {
            return emptySet()
        }

        return JVM.listJavaClasses(testModuleDir)
            .asSequence()
            .mapNotNull { className ->
                try {
                    loader.loadClass(className)
                } catch (e: Exception) {
                    null
                }
            }
            .filter { clazz ->
                clazz.methods.any { method ->
                    method.annotations.any { annotationClass.isInstance(it) }
                }
            }
            .map { it.name }
            .toSet()
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
}

/**
 * Summary of a test run.
 */
data class TestRunSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val results: Set<TestResult>
) {
    val allPassed: Boolean get() = failed == 0
    override fun toString(): String = "$passed/$total passed" + if (failed > 0) " ($failed failed)" else ""
}

/**
 * Create a reactive test runner for a test module.
 */
fun reactiveJUnitRun(
    testModule: Reactive<File>,
    mainModule: Reactive<File>? = null,
    classpath: Reactive<Set<File>>,
    filter: ((String) -> Boolean)? = null
): ReactiveJUnitRun = ReactiveJUnitRun(
    testModule = testModule,
    mainModule = mainModule,
    classpath = classpath,
    filter = filter
)
