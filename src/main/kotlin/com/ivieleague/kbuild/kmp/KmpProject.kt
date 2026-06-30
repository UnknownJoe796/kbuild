package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.native.*
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.ivieleague.kbuild.common.Dependency
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
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
    val commonDependencies: Set<Dependency> = emptySet(),
    val targetDependencies: Map<KmpTarget, Set<Dependency>> = emptyMap(),
    /** Compiler arguments applied to JVM compilation. multiPlatform is set automatically. */
    val jvmCompilerArguments: Configurer<K2JVMCompilerArguments> = {},
    /** Compiler arguments applied to JS compilation. multiPlatform is set automatically. */
    val jsCompilerArguments: Configurer<K2JSCompilerArguments> = {},
    /** Additional compiler arguments for native targets (e.g., "-Xcontext-parameters"). */
    val nativeCompilerArguments: List<String> = emptyList()
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
     * Common main source files as absolute paths (for -Xcommon-sources compiler argument).
     */
    val commonSourceFiles: Array<String>
        get() = sourceSets.commonMain.allSourceDirectories
            .filter { it.exists() }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
            .map { it.absolutePath }
            .toTypedArray()

    /**
     * Common test source files as absolute paths (for -Xcommon-sources compiler argument).
     */
    val commonTestSourceFiles: Array<String>
        get() = sourceSets.commonTest.allSourceDirectories
            .filter { it.exists() }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
            .map { it.absolutePath }
            .toTypedArray()

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
        arguments = {
            multiPlatform = true
            expectActualClasses = true
            commonSources = config.commonSourceFiles
            config.jvmCompilerArguments(this)
        },
        cache = config.buildDir.resolve("kotlin/jvm/cache"),
        outputFolder = config.buildDir.resolve("classes/kotlin/jvm/main")
    )
}

/**
 * Compile JVM target (suspend, resolves dependencies then compiles).
 */
suspend fun kmpCompileJvmBlocking(config: KmpProjectConfig): File =
    kmpCompileJvmBlocking(config, config.dependencies.resolveJvmClasspath())

/**
 * Compile JVM target with a pre-resolved [classpathJars]. Callers that fan out several target
 * compiles concurrently resolve up front and use this overload, because MavenAether's Aether
 * session is not safe for concurrent use.
 */
suspend fun kmpCompileJvmBlocking(config: KmpProjectConfig, classpathJars: Set<File>): File {
    require(KmpTarget.Jvm in config.targets) { "JVM target not enabled for this project" }

    return kotlinJvmCompileBlocking(
        name = config.name,
        sourceRoots = config.getSourcesForTarget(KmpTarget.Jvm),
        classpathJars = classpathJars,
        arguments = {
            multiPlatform = true
            expectActualClasses = true
            commonSources = config.commonSourceFiles
            config.jvmCompilerArguments(this)
        },
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

    val libraries = config.dependencies.resolveJsLibraries()

    return kotlinJsCompile(
        name = config.name,
        sourceRoots = sourceRoots,
        libraries = Constant(libraries),
        arguments = {
            multiPlatform = true
            commonSources = config.commonSourceFiles
            config.jsCompilerArguments(this)
        },
        outputMode = JsOutputMode.KLIB,
        cache = config.buildDir.resolve("kotlin/js/cache"),
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

    val libraries = config.dependencies.resolveJsLibraries()

    return kotlinJsCompile(
        name = config.name,
        sourceRoots = sourceRoots,
        libraries = Constant(libraries),
        arguments = {
            multiPlatform = true
            commonSources = config.commonSourceFiles
            config.jsCompilerArguments(this)
        },
        outputMode = JsOutputMode.JS,
        moduleKind = moduleKind,
        sourceMap = true,
        cache = config.buildDir.resolve("kotlin/js/cache"),
        outputDir = config.buildDir.resolve("js")
    )
}

/**
 * Compile JS target (suspend, resolves dependencies then compiles) to KLIB.
 */
suspend fun kmpCompileJsKlibBlocking(config: KmpProjectConfig): File =
    kmpCompileJsKlibBlocking(config, config.dependencies.resolveJsLibraries())

/**
 * Compile JS target to KLIB with pre-resolved [libraries]. See [kmpCompileJvmBlocking] for why
 * concurrent callers resolve up front and use this overload.
 */
suspend fun kmpCompileJsKlibBlocking(config: KmpProjectConfig, libraries: Set<File>): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    val sourceRoots = config.getSourcesForTarget(KmpTarget.Js)
    val cache = config.buildDir.resolve("kotlin/js/cache")
    val outputDir = config.buildDir.resolve("libs/js")
    val argConfigurer: Configurer<K2JSCompilerArguments> = {
        multiPlatform = true
        commonSources = config.commonSourceFiles
        config.jsCompilerArguments(this)
    }

    // JS uses the in-process compiler: take the in-process permit if free, otherwise compile in a
    // forked kbuild JVM so it overlaps a concurrent in-process compile (the metadata compile during
    // publishAll). The fork gets the argument configurer rendered to strings, since a lambda can't
    // cross the process boundary.
    return InProcessCompileLock.runInProcessOrFork(
        fork = {
            val argStrings = ArgumentUtils.convertArgumentsToStringListNoDefaults(
                K2JSCompilerArguments().apply(argConfigurer)
            )
            CompileFork.jsKlib(config.name, sourceRoots, libraries, argStrings, cache, outputDir)
        },
        inProcess = {
            kotlinJsCompileBlocking(
                name = config.name,
                sourceRoots = sourceRoots,
                libraries = libraries,
                arguments = argConfigurer,
                outputMode = JsOutputMode.KLIB,
                cache = cache,
                outputDir = outputDir
            )
        }
    )
}

/**
 * Compile JS target (suspend, resolves dependencies then compiles) to executable JS.
 */
suspend fun kmpCompileJsBlocking(
    config: KmpProjectConfig,
    moduleKind: JsModuleKind = JsModuleKind.ES
): File {
    require(config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
        "JS target not enabled for this project"
    }

    val libraries = config.dependencies.resolveJsLibraries()

    return kotlinJsCompileBlocking(
        name = config.name,
        sourceRoots = config.getSourcesForTarget(KmpTarget.Js),
        libraries = libraries,
        arguments = {
            multiPlatform = true
            commonSources = config.commonSourceFiles
            config.jsCompilerArguments(this)
        },
        outputMode = JsOutputMode.JS,
        moduleKind = moduleKind,
        sourceMap = true,
        cache = config.buildDir.resolve("kotlin/js/cache"),
        outputDir = config.buildDir.resolve("js")
    )
}

// ============== Native Compilation ==============

/**
 * Compile a native target to KLIB (suspend).
 *
 * Note: Currently wraps the blocking version since KotlinNativeCompile
 * doesn't yet support Reactive inputs. Future versions will be fully reactive.
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @param additionalArgs Additional compiler arguments
 * @return Output KLIB file
 */
suspend fun kmpCompileNativeKlib(
    config: KmpProjectConfig,
    target: KmpTarget.Native,
    additionalArgs: List<String> = emptyList()
): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    kmpCompileNativeKlibBlocking(config, target, additionalArgs)
}

/**
 * Compile a native target to executable (suspend).
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @param entryPoint Entry point function (default: main)
 * @return Output executable file
 */
suspend fun kmpCompileNativeExecutable(
    config: KmpProjectConfig,
    target: KmpTarget.Native = KmpTarget.Native.host(),
    entryPoint: String? = null
): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    kmpCompileNativeExecutableBlocking(config, target, entryPoint)
}

/**
 * Compile a native target to KLIB (suspend, resolves dependencies then compiles).
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @return Output KLIB file
 */
suspend fun kmpCompileNativeKlibBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native,
    additionalArgs: List<String> = emptyList()
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    val libraries = config.dependencies.resolveNativeLibraries(target)

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { libraries },
        target = target.konanTarget,
        outputKind = NativeOutputKind.LIBRARY,
        outputDir = config.buildDir.resolve("libs/${target.name}"),
        additionalArgs = listOf("-Xmulti-platform") +
            config.commonSourceFiles.map { "-Xcommon-sources=$it" } +
            config.nativeCompilerArguments +
            additionalArgs
    )

    return compiler.invoke()
}

/**
 * Compile a native target to executable (suspend, resolves dependencies then compiles).
 *
 * @param config KMP project configuration
 * @param target Native target to compile for
 * @param entryPoint Entry point function (default: main)
 * @return Output executable file
 */
suspend fun kmpCompileNativeExecutableBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native = KmpTarget.Native.host(),
    entryPoint: String? = null
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    val additionalArgs = if (entryPoint != null) listOf("-entry", entryPoint) else emptyList()
    val libraries = config.dependencies.resolveNativeLibraries(target)

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { libraries },
        target = target.konanTarget,
        outputKind = NativeOutputKind.EXECUTABLE,
        outputDir = config.buildDir.resolve("bin/${target.name}"),
        additionalArgs = additionalArgs
    )

    return compiler.invoke()
}

/**
 * Build an Apple framework (suspend, resolves dependencies then compiles).
 *
 * @param config KMP project configuration
 * @param target Apple native target
 * @param static Whether to build a static framework
 * @return Output framework directory
 */
suspend fun kmpBuildFrameworkBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native,
    static: Boolean = false
): File {
    require(target in config.targets) { "Target $target is not enabled for this project" }
    require(target.isAppleTarget()) { "Frameworks are only supported on Apple platforms, got: $target" }

    val libraries = config.dependencies.resolveNativeLibraries(target)

    val compiler = KotlinNativeCompile(
        name = config.name,
        sourceRoots = { config.getSourcesForTarget(target) },
        libraries = { libraries },
        target = target.konanTarget,
        outputKind = if (static) NativeOutputKind.STATIC_FRAMEWORK else NativeOutputKind.FRAMEWORK,
        outputDir = config.buildDir.resolve("frameworks/${target.name}")
    )

    return compiler.invoke()
}

/**
 * Build all enabled native targets (suspend).
 *
 * @param config KMP project configuration
 * @return Map of target to output file
 */
suspend fun kmpBuildAllNativeBlocking(config: KmpProjectConfig): Map<KmpTarget.Native, File> {
    val compilers = kmpNativeLibraryCompilers(config)
    if (compilers.isEmpty()) return emptyMap()

    // Compile targets in parallel: each konanc invocation is its own subprocess writing to a
    // per-target output directory, so they are fully independent. This is the dominant cost of
    // a multi-target build, so fanning it out is the biggest single speedup.
    return coroutineScope {
        compilers.map { (target, compiler) ->
            async(Dispatchers.IO) { target to compiler.invoke() }
        }.awaitAll().toMap()
    }
}

/**
 * Build one LIBRARY-output native compiler per enabled native target, ready to invoke concurrently.
 *
 * Resolves each target's libraries sequentially (MavenAether's Aether session is not concurrency-
 * safe; resolution is cache-fast) and installs the shared Kotlin/Native distribution once before
 * returning, so the callers can launch every konanc subprocess in parallel without racing on the
 * download/extract. Returns an empty map when no native targets are enabled.
 */
internal suspend fun kmpNativeLibraryCompilers(config: KmpProjectConfig): Map<KmpTarget.Native, KotlinNativeCompile> {
    val targets = config.targets.filterIsInstance<KmpTarget.Native>()
    if (targets.isEmpty()) return emptyMap()

    val librariesByTarget = targets.associateWith { config.dependencies.resolveNativeLibraries(it) }
    val compilers = targets.associateWith { target ->
        KotlinNativeCompile(
            name = config.name,
            sourceRoots = { config.getSourcesForTarget(target) },
            libraries = { librariesByTarget.getValue(target) },
            target = target.konanTarget,
            outputKind = NativeOutputKind.LIBRARY,
            outputDir = config.buildDir.resolve("libs/${target.name}"),
            additionalArgs = listOf("-Xmulti-platform") +
                config.commonSourceFiles.map { "-Xcommon-sources=$it" } +
                config.nativeCompilerArguments
        )
    }
    compilers.values.first().ensureCompilerInstalled()
    return compilers
}

// ============== Build All ==============

/**
 * Build all enabled targets (suspend), compiling every target concurrently.
 *
 * The targets use different, non-conflicting compile mechanisms so they overlap freely: JVM goes to
 * the out-of-process Kotlin daemon, JS uses the in-process compiler (serialized against other
 * in-process work by [com.ivieleague.kbuild.kotlin.InProcessCompileLock]), and natives are separate
 * konanc subprocesses. Dependency resolution (MavenAether's Aether session is not safe for
 * concurrent use) and the one-time Kotlin/Native install are done sequentially up front, before the
 * compile fan-out — only the compiles, which touch no shared resolver state, run in parallel.
 *
 * @param config KMP project configuration
 * @return Map of target to output file
 */
suspend fun kmpBuildAllBlocking(config: KmpProjectConfig): Map<KmpTarget, File> = coroutineScope {
    val hasJvm = KmpTarget.Jvm in config.targets
    val hasJs = config.targets.any { it is KmpTarget.Js }

    // Resolve every classpath sequentially first (Aether session is not concurrency-safe; cached
    // resolution is fast) and install the native distribution once. The compiles below never touch
    // the resolver, so they overlap freely.
    val jvmClasspath = if (hasJvm) config.dependencies.resolveJvmClasspath() else null
    val jsLibraries = if (hasJs) config.dependencies.resolveJsLibraries() else null
    val nativeCompilers = kmpNativeLibraryCompilers(config)

    val deferred = buildList {
        if (jvmClasspath != null) add(async(Dispatchers.IO) {
            KmpTarget.Jvm to kmpCompileJvmBlocking(config, jvmClasspath)
        })
        if (jsLibraries != null) add(async(Dispatchers.IO) {
            (KmpTarget.Js as KmpTarget) to kmpCompileJsKlibBlocking(config, jsLibraries)
        })
        nativeCompilers.forEach { (target, compiler) ->
            add(async(Dispatchers.IO) { (target as KmpTarget) to compiler.invoke() })
        }
    }

    deferred.awaitAll().toMap()
}

// ============== Testing ==============

/**
 * Run native tests for the specified target (suspend, resolves dependencies then runs).
 *
 * @param config KMP project configuration
 * @param target Native target to run tests on (defaults to host)
 * @return Set of test results
 */
suspend fun kmpRunNativeTestsBlocking(
    config: KmpProjectConfig,
    target: KmpTarget.Native = KmpTarget.Native.host()
): Set<TestResult> {
    require(target in config.targets) { "Target $target is not enabled for this project" }

    val testSources = config.getTestSourcesForTarget(target)
    val mainSources = config.getSourcesForTarget(target)
    val libraries = config.dependencies.resolveNativeLibraries(target)

    val runner = KotlinNativeTestRunner(
        name = "${config.name}-test",
        testSourceRoots = { testSources },
        mainSourceRoots = { mainSources },
        libraries = { libraries },
        target = target.konanTarget,
        buildDir = config.buildDir
    )

    return runner.run()
}
