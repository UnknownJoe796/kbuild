package com.ivieleague.kbuild.junit

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone JUnit 5 runner executed in a **forked JVM** by [junitRunBlocking].
 *
 * The fork is launched with the project's test classpath and nothing else, so test code sees
 * exactly the project's own dependencies — no interference from kbuild's runtime (kbuild even
 * bundles its own `reactive`, `kotlinx-coroutines`, etc.). This is the same isolation model
 * Gradle uses, and it sidesteps every in-process classloader-delegation conflict.
 *
 * To run on an arbitrary project's classpath this class depends only on the JDK and the JUnit
 * platform. Results are written to a file, one finished node per line:
 *
 *     status \t durationMillis \t base64(identifier) \t base64(errorMessage)
 *
 * where status is `PASS` or `FAIL`. Container nodes are recorded too (matching kbuild's
 * historical count), with their display name as the identifier.
 *
 * Arguments: `<resultsFile> <testModuleDir> [testId ...]`
 * When no test ids are given, every test discovered under the module directory is run.
 */
object JUnitForkRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val resultsFile = File(args[0])
        val testModule = File(args[1])
        val filters = args.drop(2)

        val selectors = if (filters.isEmpty()) {
            DiscoverySelectors.selectClasspathRoots(setOf(testModule.toPath()))
        } else {
            filters.map { id ->
                DiscoverySelectors.selectMethod("${id.substringBeforeLast('.')}#${id.substringAfterLast('.')}")
            }
        }

        val starts = ConcurrentHashMap<String, Long>()
        val lines = mutableListOf<String>()

        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request().selectors(selectors).build(),
            object : TestExecutionListener {
                override fun executionStarted(testIdentifier: TestIdentifier) {
                    starts[testIdentifier.uniqueId] = System.nanoTime()
                }

                override fun executionFinished(
                    testIdentifier: TestIdentifier,
                    testExecutionResult: TestExecutionResult
                ) {
                    val durationMs = starts[testIdentifier.uniqueId]
                        ?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0L
                    val identifier = (testIdentifier.source.orElse(null) as? MethodSource)
                        ?.let { "${it.className}.${it.methodName}" }
                        ?: testIdentifier.displayName
                    val status = if (testExecutionResult.status == TestExecutionResult.Status.SUCCESSFUL) "PASS" else "FAIL"
                    val error = testExecutionResult.throwable.map { it.message ?: it.toString() }.orElse("")
                    lines.add("$status\t$durationMs\t${b64(identifier)}\t${b64(error)}")
                }
            }
        )

        resultsFile.writeText(lines.joinToString("\n"))

        // Tests (via the Kotlin compiler / KSP analysis API) can leave non-daemon IntelliJ
        // platform threads running, which would keep this forked JVM alive forever. Results
        // are already persisted and test outcomes travel via the file (not the exit code), so
        // force a clean exit. Flush first so inherited stdout/stderr isn't truncated.
        System.out.flush()
        System.err.flush()
        kotlin.system.exitProcess(0)
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
