package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.compareVersions
import com.ivieleague.kbuild.jvm.JVM
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

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
suspend fun junitRun(
    testModule: Reactive<File>,
    classpath: Reactive<Set<File>>
): Set<TestResult> {
    val module = testModule()
    val cp = classpath()

    return withContext(Dispatchers.IO) {
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
suspend fun junitRunTests(
    testModule: Reactive<File>,
    classpath: Reactive<Set<File>>,
    tests: Set<String>
): Set<TestResult> {
    val module = testModule()
    val cp = classpath()

    return withContext(Dispatchers.IO) {
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
 * Blocking JUnit test execution. Use [junitRun] for reactive usage.
 *
 * Tests run in a **forked JVM** ([JUnitForkRunner]) whose classpath is exactly the project's
 * test classpath, so test code is fully isolated from kbuild's own runtime — the same model
 * Gradle uses. This avoids the version-skew/`LinkageError` problems of running another
 * project's tests inside kbuild's own classloader.
 */
fun junitRunBlocking(
    testModule: File,
    classpath: Set<File>
): Set<TestResult> = forkAndRun(testModule, classpath, emptyList())

/**
 * Blocking JUnit test execution for specific tests. Use [junitRunTests] for reactive usage.
 *
 * @param tests Fully-qualified `com.example.TestClass.testMethod` identifiers.
 */
fun junitRunTestsBlocking(
    testModule: File,
    classpath: Set<File>,
    tests: Set<String>
): Set<TestResult> = forkAndRun(testModule, classpath, tests.toList())

/**
 * Launch [JUnitForkRunner] in a separate JVM and parse the results it writes.
 *
 * Classpath ordering puts the project's own test module and dependencies first, then the
 * JUnit platform infrastructure, then kbuild's jar last (only to supply the runner class) —
 * so the project's versions always win within the fork's flat classpath.
 */
private fun forkAndRun(
    testModule: File,
    classpath: Set<File>,
    filters: List<String>
): Set<TestResult> {
    val resultsFile = File.createTempFile("kbuild-junit", ".tsv").apply { deleteOnExit() }
    try {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val forkClasspath = buildForkClasspath(testModule, classpath).joinToString(File.pathSeparator) { it.absolutePath }

        val command = listOf(
            javaBin, "-cp", forkClasspath,
            "com.ivieleague.kbuild.junit.JUnitForkRunner",
            resultsFile.absolutePath, testModule.absolutePath
        ) + filters

        val exit = ProcessBuilder(command).inheritIO().start().waitFor()

        val results = parseResults(resultsFile)
        if (results.isEmpty() && exit != 0) {
            throw RuntimeException("JUnit fork failed (exit $exit) and produced no results")
        }
        return results
    } finally {
        resultsFile.delete()
    }
}

private fun buildForkClasspath(testModule: File, classpath: Set<File>): List<File> {
    val entries = LinkedHashSet<File>()
    entries.add(testModule)
    entries.addAll(classpath)
    // JUnit platform infrastructure: the project's test deps may carry the engine/api, but not
    // necessarily the launcher. Pull whatever kbuild has so the fork can always run.
    entries.addAll(junitInfrastructureJars())
    // kbuild's own jar last, only to provide JUnitForkRunner.
    runnerLocation()?.let { entries.add(it) }
    // This classpath is merged from independently-resolved dependency sets (project deps,
    // test deps, kbuild's JUnit infrastructure), so the same artifact can appear at multiple
    // versions — fatal for JUnit (e.g. a newer launcher against an older platform-engine).
    // Keep only the highest version of each artifact, as a resolver would.
    return dedupeByArtifact(entries.toList())
}

/** Filename split into (artifact, version) for jars named `artifact-1.2.3.jar`; null version otherwise. */
private val jarVersionRegex = Regex("""^(.*?)-(\d[\w.]*(?:-[\w.]+)*)\.jar$""")

/** Keep only the highest-versioned jar per artifact; non-versioned entries and directories are kept as-is. */
private fun dedupeByArtifact(files: List<File>): List<File> {
    val best = LinkedHashMap<String, File>()  // artifact -> chosen file
    val passthrough = ArrayList<File>()
    for (file in files) {
        val match = jarVersionRegex.find(file.name)
        if (match == null) {
            passthrough.add(file)
            continue
        }
        val artifact = match.groupValues[1]
        val version = match.groupValues[2]
        val existing = best[artifact]
        if (existing == null) {
            best[artifact] = file
        } else {
            val existingVersion = jarVersionRegex.find(existing.name)!!.groupValues[2]
            if (compareVersions(version, existingVersion) > 0) best[artifact] = file
        }
    }
    return passthrough + best.values
}

/** JUnit platform/jupiter jars from kbuild's own runtime classpath. */
private fun junitInfrastructureJars(): List<File> {
    val markers = listOf(
        "junit-platform-launcher", "junit-platform-engine", "junit-platform-commons",
        "junit-jupiter-engine", "junit-jupiter-api", "opentest4j", "apiguardian"
    )
    val classpath = (System.getProperty("kbuild.classpath") ?: System.getProperty("java.class.path") ?: "")
    return classpath.split(File.pathSeparator)
        .filter { entry -> markers.any { entry.substringAfterLast(File.separatorChar).contains(it) } }
        .map { File(it) }
        .filter { it.exists() }
}

/** Location of kbuild's own classes (jar or classes dir) so the fork can load [JUnitForkRunner]. */
private fun runnerLocation(): File? = try {
    File(JUnitForkRunner::class.java.protectionDomain.codeSource.location.toURI())
} catch (e: Exception) {
    null
}

private fun parseResults(resultsFile: File): Set<TestResult> {
    if (!resultsFile.exists()) return emptySet()
    val decoder = Base64.getDecoder()
    fun decode(s: String) = String(decoder.decode(s), Charsets.UTF_8)

    return resultsFile.readLines()
        .filter { it.isNotBlank() }
        .map { line ->
            val parts = line.split('\t')
            val status = parts[0]
            val durationMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val identifier = parts.getOrNull(2)?.let { decode(it) } ?: ""
            val error = parts.getOrNull(3)?.let { decode(it) }?.takeIf { it.isNotEmpty() }
            TestResult(
                identifier = identifier,
                passed = status == "PASS",
                standardOutput = "",
                standardError = "",
                error = error,
                durationSeconds = durationMs / 1000.0,
                runAt = Date(),
                runOn = "JUnit 5 (forked)"
            )
        }
        .toSet()
}
