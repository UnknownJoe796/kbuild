package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.jvm.JVM
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import org.junit.platform.engine.DiscoverySelector
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
 * Runs JUnit 5 tests reactively.
 *
 * The test execution is cached based on input values.
 * When any input reactive changes, tests will re-run.
 *
 * @param testModule Reactive file pointing to the compiled test classes
 * @param classpath Reactive set of classpath files
 * @return Set of test results
 */
context(ctx: ReactiveContext)
fun junitRun(
    testModule: Reactive<File>,
    classpath: Reactive<Set<File>>
): Set<TestResult> {
    val module = testModule()
    val cp = classpath()

    return async(module, cp) {
        junitRunBlocking(
            testModule = module,
            classpath = cp
        )
    }
}

/**
 * Runs specific JUnit 5 tests reactively.
 *
 * @param testModule Reactive file pointing to the compiled test classes
 * @param classpath Reactive set of classpath files
 * @param tests Set of test method names to run (e.g., "com.example.TestClass.testMethod")
 * @return Set of test results
 */
context(ctx: ReactiveContext)
fun junitRunTests(
    testModule: Reactive<File>,
    classpath: Reactive<Set<File>>,
    tests: Set<String>
): Set<TestResult> {
    val module = testModule()
    val cp = classpath()

    return async(module, cp, tests) {
        junitRunTestsBlocking(
            testModule = module,
            classpath = cp,
            tests = tests
        )
    }
}

/**
 * Gets the list of test class names from a test module.
 */
fun getTestClassNames(
    testModule: File,
    classpath: Set<File>
): Set<String> {
    val loaded = JVM.load(classpath.toList() + testModule)
    val annotationClass = loaded.loadClass("org.junit.jupiter.api.Test")
    val classes = JVM.listJavaClasses(testModule)
    return classes
        .asSequence()
        .map { loaded.loadClass(it) }
        .filter { c ->
            c.methods
                .any { it.annotations.any { annotationClass.isInstance(it) } }
        }
        .map { it.name }
        .toSet()
}

/**
 * Blocking JUnit test execution.
 * Use [junitRun] for reactive usage.
 */
fun junitRunBlocking(
    testModule: File,
    classpath: Set<File>
): Set<TestResult> = junitRunWithSelectorsBlocking(testModule, classpath) { loader ->
    getTestClassNames(testModule, classpath).map { n ->
        DiscoverySelectors.selectClass(loader.loadClass(n))
    }
}

/**
 * Blocking JUnit test execution for specific tests.
 * Use [junitRunTests] for reactive usage.
 */
fun junitRunTestsBlocking(
    testModule: File,
    classpath: Set<File>,
    tests: Set<String>
): Set<TestResult> = junitRunWithSelectorsBlocking(testModule, classpath) { loaded ->
    tests.map { test ->
        val className = test.substringBeforeLast('.')
        val methodName = test.substringAfterLast('.')
        DiscoverySelectors.selectMethod(loaded.loadClass(className), methodName)
    }
}

/**
 * Blocking JUnit test execution with custom selectors.
 */
fun junitRunWithSelectorsBlocking(
    testModule: File,
    classpath: Set<File>,
    selectors: (JVM.JarFileLoader) -> List<DiscoverySelector>
): Set<TestResult> = buildSet {
    val loaded = JVM.load(classpath.toList() + testModule)
    LauncherFactory.create().execute(
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors(loaded))
            .build(),
        object : TestExecutionListener {
            override fun executionFinished(
                testIdentifier: TestIdentifier,
                testExecutionResult: TestExecutionResult
            ) {
                add(
                    TestResult(
                        testIdentifier.displayName,
                        testExecutionResult.status == Status.SUCCESSFUL,
                        standardOutput = "",
                        standardError = "",
                        error = testExecutionResult.throwable.getOrNull()?.message,
                        durationSeconds = 1.0,
                        runAt = Date(),
                        runOn = "JUnit 5"
                    )
                )
            }
        }
    )
}

// Legacy class-based API for backwards compatibility
@Deprecated("Use junitRun function with ReactiveContext instead")
class JUnitRun(
    val testModule: () -> File,
    val classpath: () -> Set<File>
) : () -> Set<TestResult>, (Set<String>) -> Set<TestResult> {
    val testClassNames: Set<String>
        get() = getTestClassNames(testModule(), classpath())

    override fun invoke(): Set<TestResult> = junitRunBlocking(
        testModule = testModule(),
        classpath = classpath()
    )

    override operator fun invoke(tests: Set<String>): Set<TestResult> = junitRunTestsBlocking(
        testModule = testModule(),
        classpath = classpath(),
        tests = tests
    )
}
