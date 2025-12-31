package com.ivieleague.kbuild.native

import com.ivieleague.kbuild.common.TestResult
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Runs Kotlin/Native tests using the kotlin.test framework.
 *
 * This runner:
 * 1. Compiles test sources (along with optional main sources) to a native executable
 * 2. Executes the compiled test binary
 * 3. Parses the output to extract test results
 *
 * The kotlin.test framework on Native produces output in the format:
 * ```
 * [==========] Running X tests from Y test cases.
 * [----------] Global test environment set-up.
 * [----------] X tests from TestClass
 * [ RUN      ] TestClass.testName
 * [       OK ] TestClass.testName (X ms)
 * [ RUN      ] TestClass.anotherTest
 * [  FAILED  ] TestClass.anotherTest (X ms)
 * [----------] X tests from TestClass (X ms total)
 * [==========] X tests from Y test cases ran. (X ms total)
 * [  PASSED  ] X tests.
 * [  FAILED  ] X tests.
 * ```
 *
 * @param name Module name for the test executable
 * @param testSourceRoots Producer of test source directories
 * @param libraries Producer of library dependencies (.klib files)
 * @param mainSourceRoots Optional producer of main source directories (if tests depend on main code)
 * @param target Target platform (defaults to host)
 * @param buildDir Build directory for compiled output
 * @param timeoutSeconds Maximum time to wait for tests to complete
 */
class KotlinNativeTestRunner(
    val name: String,
    val testSourceRoots: () -> Set<File>,
    val libraries: () -> Set<File> = { emptySet() },
    val mainSourceRoots: (() -> Set<File>)? = null,
    val target: KonanTarget = KonanTarget.host(),
    val buildDir: File,
    val timeoutSeconds: Long = 300
) {
    private val compiler = KonanCompiler.default()

    private val outputDir: File = buildDir.resolve("native-test/$name")
    private val cacheDir: File = buildDir.resolve("native-test-cache/$name")

    /**
     * The compiled test executable.
     */
    val testExecutable: File
        get() {
            val extension = when (target.family) {
                TargetFamily.MINGW -> ".exe"
                else -> ".kexe"
            }
            return outputDir.resolve("$name-test$extension")
        }

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
     * Compile the test sources to a native executable.
     *
     * @return The compiled test executable
     */
    fun compile(): File {
        outputDir.mkdirs()
        cacheDir.mkdirs()

        // Collect all source roots
        val allSources = mutableSetOf<File>()
        allSources.addAll(testSourceRoots())
        mainSourceRoots?.let { allSources.addAll(it()) }

        if (allSources.isEmpty()) {
            throw IllegalStateException("No source directories provided for test compilation")
        }

        // Collect libraries
        val libs = libraries().toList()

        println("Compiling native tests: $name")
        println("  Sources: ${allSources.joinToString()}")
        println("  Libraries: ${libs.size} klibs")

        return compiler.compile(
            sources = allSources.toList(),
            output = testExecutable,
            target = target,
            outputKind = NativeOutputKind.EXECUTABLE,
            libraries = libs,
            optimizations = false,
            debug = true,
            additionalArgs = listOf("-tr") // Enable test runner mode
        )
    }

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
        // Compile if needed
        if (!testExecutable.exists()) {
            compile()
        }

        if (!testExecutable.exists()) {
            throw IllegalStateException("Test executable not found: $testExecutable")
        }

        onTestRunStart?.invoke()

        val startTime = System.currentTimeMillis()
        val results = mutableSetOf<TestResult>()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        // Build command with optional filter
        val command = mutableListOf(testExecutable.absolutePath)
        if (filter != null) {
            // For kotlin.test native, use --gtest_filter for filtering
            // Pattern: TestClass.testMethod or TestClass.* for all in class
            command.add("--ktest_filter=*")
        }

        val process = ProcessBuilder(command)
            .directory(buildDir)
            .start()

        // Read stdout in a separate thread
        val stdoutThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                stdout.appendLine(line)
                parseTestLine(line, startTime)?.let { result ->
                    // Apply filter if present
                    if (filter == null || filter(result.identifier)) {
                        results.add(result)
                        onTestComplete?.invoke(result)
                    }
                }
            }
        }

        // Read stderr in a separate thread
        val stderrThread = Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                stderr.appendLine(line)
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Test execution timed out after $timeoutSeconds seconds")
        }

        stdoutThread.join()
        stderrThread.join()

        val exitCode = process.exitValue()
        val duration = (System.currentTimeMillis() - startTime) / 1000.0

        // If no structured results were parsed, create a summary result
        if (results.isEmpty()) {
            val passed = exitCode == 0
            results.add(
                TestResult(
                    identifier = "$name (native tests)",
                    passed = passed,
                    standardOutput = stdout.toString(),
                    standardError = stderr.toString(),
                    error = if (!passed) "Test process exited with code $exitCode" else null,
                    durationSeconds = duration,
                    runAt = Date(),
                    runOn = "Kotlin/Native ${target.targetName}"
                )
            )
        }

        onTestRunComplete?.invoke(results)
        return results
    }

    /**
     * Parse a line of test output and extract a test result if applicable.
     */
    private fun parseTestLine(line: String, runStartTime: Long): TestResult? {
        val trimmed = line.trim()

        // Parse gtest-style output from kotlin.test native
        // [       OK ] TestClass.testName (X ms)
        // [  FAILED  ] TestClass.testName (X ms)
        val okMatch = OK_PATTERN.matchEntire(trimmed)
        if (okMatch != null) {
            val testName = okMatch.groupValues[1]
            val durationMs = okMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            return TestResult(
                identifier = testName,
                passed = true,
                standardOutput = "",
                standardError = "",
                error = null,
                durationSeconds = durationMs / 1000.0,
                runAt = Date(),
                runOn = "Kotlin/Native ${target.targetName}"
            )
        }

        val failedMatch = FAILED_PATTERN.matchEntire(trimmed)
        if (failedMatch != null) {
            val testName = failedMatch.groupValues[1]
            val durationMs = failedMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            return TestResult(
                identifier = testName,
                passed = false,
                standardOutput = "",
                standardError = "",
                error = "Test failed",
                durationSeconds = durationMs / 1000.0,
                runAt = Date(),
                runOn = "Kotlin/Native ${target.targetName}"
            )
        }

        // Also parse simpler formats that kotlin.test might use
        // PASS: testName
        // FAIL: testName
        if (trimmed.startsWith("PASS:") || trimmed.startsWith("PASSED:")) {
            val testName = trimmed.substringAfter(":").trim()
            return TestResult(
                identifier = testName,
                passed = true,
                standardOutput = "",
                standardError = "",
                error = null,
                durationSeconds = 0.0,
                runAt = Date(),
                runOn = "Kotlin/Native ${target.targetName}"
            )
        }

        if (trimmed.startsWith("FAIL:") || trimmed.startsWith("FAILED:")) {
            val testName = trimmed.substringAfter(":").trim()
            return TestResult(
                identifier = testName,
                passed = false,
                standardOutput = "",
                standardError = "",
                error = "Test failed",
                durationSeconds = 0.0,
                runAt = Date(),
                runOn = "Kotlin/Native ${target.targetName}"
            )
        }

        return null
    }

    /**
     * Ensure the Kotlin/Native compiler is installed.
     */
    fun ensureCompilerInstalled(): File {
        return compiler.ensureInstalled()
    }

    /**
     * Clean build artifacts.
     */
    fun clean() {
        outputDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    companion object {
        // Patterns for parsing gtest-style output
        private val OK_PATTERN = Regex("""\[\s*OK\s*]\s+(\S+)\s+\((\d+)\s*ms\)""")
        private val FAILED_PATTERN = Regex("""\[\s*FAILED\s*]\s+(\S+)\s+\((\d+)\s*ms\)""")
    }
}

/**
 * Create a Kotlin/Native test runner.
 */
fun kotlinNativeTestRunner(
    name: String,
    testSourceRoots: () -> Set<File>,
    libraries: () -> Set<File> = { emptySet() },
    mainSourceRoots: (() -> Set<File>)? = null,
    target: KonanTarget = KonanTarget.host(),
    buildDir: File
): KotlinNativeTestRunner = KotlinNativeTestRunner(
    name = name,
    testSourceRoots = testSourceRoots,
    libraries = libraries,
    mainSourceRoots = mainSourceRoots,
    target = target,
    buildDir = buildDir
)
