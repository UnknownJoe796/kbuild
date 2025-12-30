package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.browser.BrowserTestRunner
import com.ivieleague.kbuild.common.Producer
import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.native.*
import org.apache.maven.model.Dependency
import java.io.File

/**
 * A Kotlin Multiplatform project that coordinates compilation across all targets.
 *
 * This class manages:
 * - Source set hierarchy (commonMain, jvmMain, jsMain, nativeMain, etc.)
 * - Dependency resolution for each target
 * - Compilation for each enabled target
 * - Output artifacts (JARs, KLIBs, executables, frameworks)
 *
 * Example usage:
 * ```
 * val project = KmpProject(
 *     name = "my-library",
 *     projectRoot = File("."),
 *     targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host())
 * )
 *
 * // Build all targets
 * project.buildAll()
 *
 * // Build specific target
 * project.buildJvm()
 * project.buildJs()
 * project.buildNative(KmpTarget.Native.MacosArm64)
 * ```
 */
class KmpProject(
    val name: String,
    val projectRoot: File,
    val targets: Set<KmpTarget>,
    val commonDependencies: Set<KmpDependency> = emptySet(),
    val targetDependencies: Map<KmpTarget, Set<Dependency>> = emptyMap()
) {
    val buildDir: File = projectRoot.resolve("build")
    val outputDir: File = buildDir.resolve("libs")

    /**
     * Source set hierarchy for this project.
     */
    val sourceSets = SourceSetHierarchy(projectRoot, targets)

    /**
     * Dependency resolver.
     */
    val dependencies = KmpDependencyResolver(targets, commonDependencies, targetDependencies)

    /**
     * Get source directories for a target (includes all inherited sources).
     */
    fun getSourcesForTarget(target: KmpTarget): Set<File> {
        val sourceSet = sourceSets.getSourceSetForTarget(target) ?: return emptySet()
        return sourceSet.allSourceDirectories.filter { it.exists() }.toSet()
    }

    // ============== JVM Compilation ==============

    val jvmCompile: KotlinJvmCompile? by lazy {
        if (KmpTarget.Jvm !in targets) null
        else KotlinJvmCompile(
            name = name,
            sourceRoots = { getSourcesForTarget(KmpTarget.Jvm) },
            classpathJars = { dependencies.resolveJvmClasspath() },
            cache = buildDir.resolve("kotlin/jvm/cache"),
            outputFolder = buildDir.resolve("classes/kotlin/jvm/main")
        )
    }

    fun buildJvm(): File? {
        return jvmCompile?.invoke()
    }

    // ============== JS Compilation ==============

    val jsCompile: KotlinJsCompile? by lazy {
        if (KmpTarget.Js !in targets && KmpTarget.Js.Browser !in targets && KmpTarget.Js.Node !in targets) null
        else KotlinJsCompile(
            name = name,
            sourceRoots = { getSourcesForTarget(KmpTarget.Js) },
            libraries = { dependencies.resolveJsLibraries() },
            outputMode = JsOutputMode.KLIB, // For libraries, produce klib
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            outputDir = buildDir.resolve("libs/js")
        )
    }

    fun buildJs(): File? {
        return jsCompile?.invoke()
    }

    /**
     * Compile JS for web browser consumption (ES modules).
     *
     * Unlike buildJs() which produces a KLIB for library distribution,
     * this produces executable JavaScript that can be loaded by a browser.
     *
     * @param outputDir Where to put the output JS file
     * @param moduleKind Module format (default: ES for Vite compatibility)
     * @return The output JS file
     */
    fun compileJsForBrowser(
        outputDir: File = buildDir.resolve("js"),
        moduleKind: String = "es"
    ): File {
        require(KmpTarget.Js in targets || KmpTarget.Js.Browser in targets) {
            "Project must have a JS target"
        }

        val jsCompile = KotlinJsCompile(
            name = name,
            sourceRoots = { getSourcesForTarget(KmpTarget.Js) },
            libraries = { dependencies.resolveJsLibraries() },
            outputMode = JsOutputMode.JS,
            moduleKind = when (moduleKind.lowercase()) {
                "es", "esm" -> JsModuleKind.ES
                "commonjs", "cjs" -> JsModuleKind.COMMONJS
                "umd" -> JsModuleKind.UMD
                "amd" -> JsModuleKind.AMD
                else -> JsModuleKind.ES
            },
            sourceMap = true,
            outputDir = outputDir
        )

        return jsCompile.invoke()
    }

    // ============== Native Compilation ==============

    private val nativeCompilers = mutableMapOf<KmpTarget.Native, KotlinNativeCompile>()

    fun getNativeCompile(target: KmpTarget.Native): KotlinNativeCompile {
        return nativeCompilers.getOrPut(target) {
            KotlinNativeCompile(
                name = name,
                sourceRoots = { getSourcesForTarget(target) },
                libraries = { dependencies.resolveNativeLibraries(target) },
                target = target.konanTarget,
                outputKind = NativeOutputKind.LIBRARY,
                outputDir = buildDir.resolve("libs/${target.name}")
            )
        }
    }

    fun buildNative(target: KmpTarget.Native): File {
        require(target in targets) { "Target $target is not enabled for this project" }
        return getNativeCompile(target).invoke()
    }

    /**
     * Build all enabled native targets.
     */
    fun buildAllNative(): Map<KmpTarget.Native, File> {
        val results = mutableMapOf<KmpTarget.Native, File>()
        for (target in targets.filterIsInstance<KmpTarget.Native>()) {
            results[target] = buildNative(target)
        }
        return results
    }

    // ============== Apple Framework ==============

    private val frameworkCompilers = mutableMapOf<KmpTarget.Native, KotlinNativeCompile>()

    fun getFrameworkCompile(target: KmpTarget.Native, static: Boolean = false): KotlinNativeCompile {
        require(target.konanTarget.family in listOf(
            TargetFamily.OSX, TargetFamily.IOS, TargetFamily.WATCHOS, TargetFamily.TVOS
        )) { "Frameworks are only supported on Apple platforms, got: $target" }

        return frameworkCompilers.getOrPut(target) {
            KotlinNativeCompile(
                name = name,
                sourceRoots = { getSourcesForTarget(target) },
                libraries = { dependencies.resolveNativeLibraries(target) },
                target = target.konanTarget,
                outputKind = if (static) NativeOutputKind.STATIC_FRAMEWORK else NativeOutputKind.FRAMEWORK,
                outputDir = buildDir.resolve("frameworks/${target.name}")
            )
        }
    }

    fun buildFramework(target: KmpTarget.Native, static: Boolean = false): File {
        require(target in targets) { "Target $target is not enabled for this project" }
        return getFrameworkCompile(target, static).invoke()
    }

    // ============== Build All ==============

    /**
     * Build all enabled targets.
     *
     * @return Map of target to output file
     */
    fun buildAll(): Map<KmpTarget, File> {
        val results = mutableMapOf<KmpTarget, File>()

        // Build JVM
        if (KmpTarget.Jvm in targets) {
            buildJvm()?.let { results[KmpTarget.Jvm] = it }
        }

        // Build JS
        if (targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            buildJs()?.let { results[KmpTarget.Js] = it }
        }

        // Build all native targets
        for (target in targets.filterIsInstance<KmpTarget.Native>()) {
            results[target] = buildNative(target)
        }

        return results
    }

    // ============== Testing ==============

    /**
     * Create a native test runner for the specified target.
     *
     * The test runner will compile test sources from commonTest and the
     * target-specific test source set, then execute them.
     *
     * @param target Native target to run tests on (defaults to host)
     * @return A configured KotlinNativeTestRunner
     */
    fun nativeTestRunner(target: KmpTarget.Native = KmpTarget.Native.host()): KotlinNativeTestRunner {
        require(target in targets) { "Target $target is not enabled for this project" }

        val testSourceSet = sourceSets.getTestSourceSetForTarget(target)
        val mainSourceSet = sourceSets.getSourceSetForTarget(target)

        return KotlinNativeTestRunner(
            name = "$name-test",
            testSourceRoots = {
                testSourceSet?.allSourceDirectories?.filter { it.exists() }?.toSet() ?: emptySet()
            },
            mainSourceRoots = {
                mainSourceSet?.allSourceDirectories?.filter { it.exists() }?.toSet() ?: emptySet()
            },
            libraries = { dependencies.resolveNativeLibraries(target) },
            target = target.konanTarget,
            buildDir = buildDir
        )
    }

    /**
     * Run native tests for the specified target.
     *
     * @param target Native target to run tests on (defaults to host)
     * @return Set of test results
     */
    fun runNativeTests(target: KmpTarget.Native = KmpTarget.Native.host()): Set<TestResult> {
        return nativeTestRunner(target).run()
    }

    /**
     * Create a browser test runner for Kotlin/JS tests.
     *
     * This compiles test sources and runs them in headless Chrome.
     *
     * @return A configured BrowserTestRunner
     */
    fun browserTestRunner(): BrowserTestRunner {
        require(targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            "Project must have a JS target for browser testing"
        }

        // Compile test JS
        val testSourceSet = sourceSets.getTestSourceSetForTarget(KmpTarget.Js)
        val mainSourceSet = sourceSets.getSourceSetForTarget(KmpTarget.Js)

        val allSources = mutableSetOf<File>()
        testSourceSet?.allSourceDirectories?.filter { it.exists() }?.let { allSources.addAll(it) }
        mainSourceSet?.allSourceDirectories?.filter { it.exists() }?.let { allSources.addAll(it) }

        val testCompile = KotlinJsCompile(
            name = "$name-test",
            sourceRoots = { allSources },
            libraries = { dependencies.resolveJsLibraries() },
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            outputDir = buildDir.resolve("js-test")
        )

        val testJsFile = testCompile.invoke()

        return BrowserTestRunner(
            testJsFile = testJsFile,
            projectDir = buildDir
        )
    }

    /**
     * Run browser tests for Kotlin/JS.
     *
     * @return Set of test results
     */
    fun runBrowserTests(): Set<TestResult> {
        return browserTestRunner().run()
    }

    /**
     * Run tests for the specified target.
     *
     * @param target Target to run tests for
     * @return Set of test results
     */
    fun runTests(target: KmpTarget): Set<TestResult> {
        return when (target) {
            is KmpTarget.Native -> runNativeTests(target)
            is KmpTarget.Js, KmpTarget.Js.Browser -> runBrowserTests()
            KmpTarget.Jvm -> {
                // JVM tests use JUnitRun, which is already implemented
                throw UnsupportedOperationException(
                    "Use JUnitRun for JVM tests. KmpProject.runTests() supports Native and JS targets."
                )
            }
            else -> throw UnsupportedOperationException("Testing not supported for target: $target")
        }
    }

    /**
     * Print build summary.
     */
    fun printSummary() {
        println("KMP Project: $name")
        println("Root: $projectRoot")
        println("Targets: ${targets.joinToString { it.name }}")
        println()
        println("Source Sets:")
        for (sourceSet in sourceSets.getMainSourceSets()) {
            val existingSources = sourceSet.allSourceDirectories.filter { it.exists() }
            if (existingSources.isNotEmpty()) {
                println("  ${sourceSet.name}:")
                existingSources.forEach { println("    - $it") }
            }
        }
    }
}

/**
 * Builder for KmpProject.
 */
class KmpProjectBuilder(
    val name: String,
    val projectRoot: File
) {
    private val targets = mutableSetOf<KmpTarget>()
    private val commonDependencies = mutableSetOf<KmpDependency>()
    private val targetDependencies = mutableMapOf<KmpTarget, MutableSet<Dependency>>()

    fun jvm() = apply { targets.add(KmpTarget.Jvm) }
    fun js() = apply { targets.add(KmpTarget.Js) }
    fun wasmJs() = apply { targets.add(KmpTarget.Wasm.Js) }

    fun native(target: KmpTarget.Native) = apply { targets.add(target) }
    fun nativeHost() = apply { targets.add(KmpTarget.Native.host()) }

    fun macosX64() = apply { targets.add(KmpTarget.Native.MacosX64) }
    fun macosArm64() = apply { targets.add(KmpTarget.Native.MacosArm64) }
    fun macos() = apply { macosX64(); macosArm64() }

    fun iosArm64() = apply { targets.add(KmpTarget.Native.IosArm64) }
    fun iosSimulatorArm64() = apply { targets.add(KmpTarget.Native.IosSimulatorArm64) }
    fun iosX64() = apply { targets.add(KmpTarget.Native.IosX64) }
    fun ios() = apply { iosArm64(); iosSimulatorArm64() }

    fun linuxX64() = apply { targets.add(KmpTarget.Native.LinuxX64) }
    fun linuxArm64() = apply { targets.add(KmpTarget.Native.LinuxArm64) }
    fun linux() = apply { linuxX64(); linuxArm64() }

    fun mingwX64() = apply { targets.add(KmpTarget.Native.MingwX64) }

    /**
     * Add a common dependency that applies to all targets.
     */
    fun commonDependency(path: String) = apply {
        commonDependencies.add(KmpDependency.parse(path))
    }

    /**
     * Add a common dependency that applies to all targets.
     */
    fun commonDependency(dep: KmpDependency) = apply {
        commonDependencies.add(dep)
    }

    /**
     * Add a target-specific dependency.
     */
    fun dependency(target: KmpTarget, dep: Dependency) = apply {
        targetDependencies.getOrPut(target) { mutableSetOf() }.add(dep)
    }

    fun build(): KmpProject = KmpProject(
        name = name,
        projectRoot = projectRoot,
        targets = targets,
        commonDependencies = commonDependencies,
        targetDependencies = targetDependencies.mapValues { it.value.toSet() }
    )
}

/**
 * DSL for creating a KMP project.
 */
fun kmpProject(
    name: String,
    projectRoot: File,
    block: KmpProjectBuilder.() -> Unit
): KmpProject = KmpProjectBuilder(name, projectRoot).apply(block).build()
