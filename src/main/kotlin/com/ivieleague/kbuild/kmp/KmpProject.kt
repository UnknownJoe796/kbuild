package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.native.*
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Reactive
import org.apache.maven.model.Dependency
import java.io.File

/**
 * Configuration for a Kotlin Multiplatform project.
 *
 * This data class holds all the configuration needed to build a KMP project.
 * Use the reactive functions below to actually compile targets.
 *
 * Example usage:
 * ```
 * val config = KmpProjectConfig(
 *     name = "my-library",
 *     projectRoot = File("."),
 *     targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host())
 * )
 *
 * // Build reactively
 * with(ReactiveScope.Standard) {
 *     val jvmClasses = config.compileJvm(ctx)
 *     val jsOutput = config.compileJs(ctx)
 * }
 * ```
 */
data class KmpProjectConfig(
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

    /**
     * Get test source directories for a target.
     */
    fun getTestSourcesForTarget(target: KmpTarget): Set<File> {
        val sourceSet = sourceSets.getTestSourceSetForTarget(target) ?: return emptySet()
        return sourceSet.allSourceDirectories.filter { it.exists() }.toSet()
    }

    /**
     * Create reactive file watches for all source directories of a target.
     * Returns a reactive that combines watches from all source directories.
     */
    fun watchSourcesForTarget(target: KmpTarget): Reactive<Set<File>> {
        val sources = getSourcesForTarget(target)
        if (sources.isEmpty()) return Constant(emptySet())

        // For simplicity, watch only the first source directory
        // In a real implementation, you might want to combine multiple watches
        val firstDir = sources.first()
        return DirectoryWatch(
            root = firstDir,
            globPattern = "**/*.kt"
        )
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

// ============== JVM Compilation ==============

/**
 * Compile JVM target reactively.
 *
 * Watches source files and recompiles when they change.
 *
 * @param config KMP project configuration
 * @param sourceRoots Reactive source directories (defaults to file watching)
 * @return Output directory with compiled classes
 */
suspend fun kmpCompileJvm(
    config: KmpProjectConfig,
    sourceRoots: Reactive<Set<File>> = config.watchSourcesForTarget(KmpTarget.Jvm)
): File {
    require(KmpTarget.Jvm in config.targets) { "JVM target not enabled for this project" }

    return kotlinJvmCompile(
        name = config.name,
        sourceRoots = sourceRoots,
        classpathJars = Constant(config.dependencies.resolveJvmClasspath()),
        cache = config.buildDir.resolve("kotlin/jvm/cache"),
        outputFolder = config.buildDir.resolve("classes/kotlin/jvm/main")
    )
}

/**
 * Compile JVM target (blocking, non-reactive).
 */
fun kmpCompileJvmBlocking(config: KmpProjectConfig): File {
    require(KmpTarget.Jvm in config.targets) { "JVM target not enabled for this project" }

    return kotlinJvmCompileBlocking(
        name = config.name,
        sourceRoots = config.getSourcesForTarget(KmpTarget.Jvm),
        classpathJars = config.dependencies.resolveJvmClasspath(),
        cache = config.buildDir.resolve("kotlin/jvm/cache"),
        outputFolder = config.buildDir.resolve("classes/kotlin/jvm/main")
    )
}

// ============== JS Compilation ==============

/**
 * Compile JS target reactively to KLIB (for library distribution).
 *
 * @param config KMP project configuration
 * @param sourceRoots Reactive source directories (defaults to file watching)
 * @return Output KLIB file
 */
suspend fun kmpCompileJsKlib(
    config: KmpProjectConfig,
    sourceRoots: Reactive<Set<File>> = config.watchSourcesForTarget(KmpTarget.Js)
): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    return kotlinJsCompile(
        name = config.name,
        sourceRoots = sourceRoots,
        libraries = Constant(config.dependencies.resolveJsLibraries()),
        outputMode = JsOutputMode.KLIB,
        outputDir = config.buildDir.resolve("libs/js")
    )
}

/**
 * Compile JS target reactively to executable JS (for browser/node execution).
 *
 * @param config KMP project configuration
 * @param sourceRoots Reactive source directories (defaults to file watching)
 * @param moduleKind JavaScript module format
 * @return Output directory containing JS files
 */
suspend fun kmpCompileJs(
    config: KmpProjectConfig,
    sourceRoots: Reactive<Set<File>> = config.watchSourcesForTarget(KmpTarget.Js),
    moduleKind: JsModuleKind = JsModuleKind.ES
): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    return kotlinJsCompile(
        name = config.name,
        sourceRoots = sourceRoots,
        libraries = Constant(config.dependencies.resolveJsLibraries()),
        outputMode = JsOutputMode.JS,
        moduleKind = moduleKind,
        sourceMap = true,
        outputDir = config.buildDir.resolve("js")
    )
}

/**
 * Compile JS target (blocking, non-reactive) to KLIB.
 */
fun kmpCompileJsKlibBlocking(config: KmpProjectConfig): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    return kotlinJsCompileBlocking(
        name = config.name,
        sourceRoots = config.getSourcesForTarget(KmpTarget.Js),
        libraries = config.dependencies.resolveJsLibraries(),
        outputMode = JsOutputMode.KLIB,
        outputDir = config.buildDir.resolve("libs/js")
    )
}

/**
 * Compile JS target (blocking, non-reactive) to executable JS.
 */
fun kmpCompileJsBlocking(
    config: KmpProjectConfig,
    moduleKind: JsModuleKind = JsModuleKind.ES
): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    return kotlinJsCompileBlocking(
        name = config.name,
        sourceRoots = config.getSourcesForTarget(KmpTarget.Js),
        libraries = config.dependencies.resolveJsLibraries(),
        outputMode = JsOutputMode.JS,
        moduleKind = moduleKind,
        sourceMap = true,
        outputDir = config.buildDir.resolve("js")
    )
}

// ============== Native Compilation ==============

/**
 * Compile a native target to KLIB (blocking).
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @return Output KLIB file
 */
fun kmpCompileNativeKlibBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native,
    additionalArgs: List<String> = emptyList()
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    // Get common sources for @OptionalExpectation support
    val commonSourceFiles = config.sourceSets.commonMain.allSourceDirectories
        .filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" } }
        .map { it.absolutePath }

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { config.dependencies.resolveNativeLibraries(target) },
        target = target.konanTarget,
        outputKind = NativeOutputKind.LIBRARY,
        outputDir = config.buildDir.resolve("libs/${target.name}"),
        additionalArgs = listOf(
            "-Xcontext-parameters",
            "-Xmulti-platform"
        ) + commonSourceFiles.flatMap { listOf("-Xcommon-sources=$it") } + additionalArgs
    )

    return compiler.invoke()
}

/**
 * Compile a native target to executable (blocking).
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @param entryPoint Entry point function (default: main)
 * @return Output executable file
 */
fun kmpCompileNativeExecutableBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native = KmpTarget.Native.host(),
    entryPoint: String? = null
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    val additionalArgs = if (entryPoint != null) listOf("-entry", entryPoint) else emptyList()

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { config.dependencies.resolveNativeLibraries(target) },
        target = target.konanTarget,
        outputKind = NativeOutputKind.EXECUTABLE,
        outputDir = config.buildDir.resolve("bin/${target.name}"),
        additionalArgs = additionalArgs
    )

    return compiler.invoke()
}

/**
 * Build an Apple framework (blocking).
 *
 * @param config KMP project configuration
 * @param target Apple native target
 * @param static Whether to build a static framework
 * @return Output framework directory
 */
fun kmpBuildFrameworkBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native,
    static: Boolean = false
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }
    require(target.isAppleTarget()) { "Frameworks are only supported on Apple platforms, got: $target" }

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { config.dependencies.resolveNativeLibraries(target) },
        target = target.konanTarget,
        outputKind = if (static) NativeOutputKind.STATIC_FRAMEWORK else NativeOutputKind.FRAMEWORK,
        outputDir = config.buildDir.resolve("frameworks/${target.name}")
    )

    return compiler.invoke()
}

/**
 * Build all enabled native targets (blocking).
 *
 * @param config KMP project configuration
 * @return Map of target to output file
 */
fun kmpBuildAllNativeBlocking(config: KmpProjectConfig): Map<KmpTarget.Native, File> {
    val results = mutableMapOf<KmpTarget.Native, File>()
    for (target in config.targets.filterIsInstance<KmpTarget.Native>()) {
        results[target] = kmpCompileNativeKlibBlocking(config, target)
    }
    return results
}

// ============== Build All ==============

/**
 * Build all enabled targets (blocking).
 *
 * @param config KMP project configuration
 * @return Map of target to output file
 */
fun kmpBuildAllBlocking(config: KmpProjectConfig): Map<KmpTarget, File> {
    val results = mutableMapOf<KmpTarget, File>()

    // Build JVM
    if (KmpTarget.Jvm in config.targets) {
        results[KmpTarget.Jvm] = kmpCompileJvmBlocking(config)
    }

    // Build JS
    if (config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        results[KmpTarget.Js] = kmpCompileJsKlibBlocking(config)
    }

    // Build all native targets
    for (target in config.targets.filterIsInstance<KmpTarget.Native>()) {
        results[target] = kmpCompileNativeKlibBlocking(config, target)
    }

    return results
}

// ============== Testing ==============

/**
 * Run native tests for the specified target (blocking).
 *
 * @param config KMP project configuration
 * @param target Native target to run tests on (defaults to host)
 * @return Set of test results
 */
fun kmpRunNativeTestsBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native = KmpTarget.Native.host()
): Set<TestResult> {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    val testSources = config.getTestSourcesForTarget(target)
    val mainSources = config.getSourcesForTarget(target)

    val runner = KotlinNativeTestRunner(
        name = "${config.name}-test",
        testSourceRoots = { testSources },
        mainSourceRoots = { mainSources },
        libraries = { config.dependencies.resolveNativeLibraries(target) },
        target = target.konanTarget,
        buildDir = config.buildDir
    )

    return runner.run()
}

// ============== DSL Builder ==============

/**
 * Builder for KmpProjectConfig.
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

    fun build(): KmpProjectConfig = KmpProjectConfig(
        name = name,
        projectRoot = projectRoot,
        targets = targets,
        commonDependencies = commonDependencies,
        targetDependencies = targetDependencies.mapValues { it.value.toSet() }
    )
}

/**
 * DSL for creating a KMP project configuration.
 */
fun kmpProject(
    name: String,
    projectRoot: File,
    block: KmpProjectBuilder.() -> Unit
): KmpProjectConfig = KmpProjectBuilder(name, projectRoot).apply(block).build()
