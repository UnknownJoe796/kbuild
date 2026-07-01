package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import java.io.File

/**
 * Output mode for Kotlin/JS compilation.
 */
enum class JsOutputMode {
    /** Output a single .js file (for executables/applications) */
    JS,
    /** Output a .klib file (for libraries) */
    KLIB
}

/**
 * Module kind for JS output.
 */
enum class JsModuleKind(val value: String) {
    PLAIN("plain"),
    AMD("amd"),
    COMMONJS("commonjs"),
    UMD("umd"),
    ES("es")
}

/**
 * Compiles Kotlin/JS sources.
 *
 * Reads [sourceRoots] via `invoke()` so that, inside a reactive scope, the source watch is registered
 * as a dependency (re-running the compile on change); without a scope it reads the current value and
 * compiles once. [libraries] is a resolved, static input used directly.
 *
 * Compilation runs out-of-process in the Kotlin daemon (see [DaemonJsCompile]) through the Build
 * Tools API, so it overlaps the other daemon compiles (JVM, metadata) and the native konanc
 * subprocesses. For JS output the K2 two-phase compile (sources → KLIB → JS) is driven by the daemon.
 * When [cache] is set, a [SourceFileTracker] gives a cheap no-change skip (avoiding the daemon round-
 * trip entirely), and the KLIB phase uses BTA history-based incremental compilation for changed builds.
 *
 * @param name Module name
 * @param sourceRoots Reactive set of source root directories
 * @param libraries Resolved set of library files (.klib or .jar with JS metadata)
 * @param arguments Additional compiler arguments (applied to the KLIB phase)
 * @param outputMode Whether to output JS or KLIB
 * @param moduleKind Module format for JS output
 * @param sourceMap Whether to generate source maps
 * @param cache Directory for the no-change skip state and the intermediate KLIB (null disables the skip)
 * @param outputDir Output directory for compiled files
 * @return The output KLIB file (KLIB mode) or the output directory (JS mode)
 */
suspend fun kotlinJsCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Set<File> = emptySet(),
    arguments: Configurer<K2JSCompilerArguments> = {},
    outputMode: JsOutputMode = JsOutputMode.JS,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
    cache: File? = null,
    outputDir: File
): File {
    val sources = sourceRoots()

    return withContext(Dispatchers.IO) {
        outputDir.mkdirs()

        val sourceFiles = sources.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.extension == "kt" }
            .toList()

        if (sourceFiles.isEmpty()) return@withContext outputDir  // Nothing to compile.

        // Cheap no-change skip: if nothing changed since last build and the expected output exists,
        // skip the daemon round-trip entirely.
        val expectedKlib = outputDir.resolve("$name.klib")
        if (cache != null) {
            cache.mkdirs()
            val changes = SourceFileTracker.forCache(cache).computeChanges(sourceFiles)
            val hasOutput = when (outputMode) {
                JsOutputMode.KLIB -> expectedKlib.exists()
                JsOutputMode.JS -> outputDir.walkTopDown().any { it.extension == "js" || it.extension == "mjs" }
            }
            if (!changes.isFirstBuild && changes.isEmpty && hasOutput) {
                if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
                    println("No source changes detected, skipping JS compilation")
                }
                return@withContext if (outputMode == JsOutputMode.KLIB) expectedKlib else outputDir
            }
        }

        // BTA's KLIB operation treats its destination as a *directory* and writes `<dir>/<name>.klib`
        // inside it. In KLIB mode that directory is the caller's outputDir (so the klib lands at
        // expectedKlib); in JS mode it is an intermediate temp dir feeding the linking phase.
        val klibDir = when (outputMode) {
            JsOutputMode.KLIB -> outputDir
            JsOutputMode.JS -> (cache ?: outputDir.parentFile).resolve("${outputDir.name}-klib-temp")
        }
        klibDir.mkdirs()
        val klibFile = klibDir.resolve("$name.klib")

        val request = HashMap<String, Any?>()
        request["sources"] = sourceFiles.map { it.absolutePath }
        request["klibDir"] = klibDir.absolutePath
        request["klibFile"] = klibFile.absolutePath
        request["klibArgs"] = klibArgStrings(name, libraries, arguments)
        request["outputMode"] = outputMode.name
        request["daemonRunDir"] = DaemonJsCompile.daemonRunDir.absolutePath
        request["debug"] = Settings.outputLevel <= Settings.OutputLevel.Debug
        if (cache != null) {
            // Enable history-based incremental compilation for the KLIB phase. These dirs live under
            // the cache so the compiler's IC state survives between builds.
            request["icRootProjectDir"] = klibDir.absolutePath
            request["icWorkingDir"] = cache.resolve("js-ic-caches").absolutePath
            request["icModuleName"] = name
            request["icModuleBuildDir"] = cache.resolve("js-ic-build").absolutePath
        }
        if (outputMode == JsOutputMode.JS) {
            request["output"] = outputDir.absolutePath
            request["linkArgs"] = linkArgStrings(name, libraries, moduleKind, sourceMap)
        }

        failIfErrors(DaemonJsCompile.compile(request))

        if (outputMode == JsOutputMode.KLIB) klibFile else outputDir
    }
}

/**
 * Convenience function for compiling Kotlin/JS to JavaScript.
 */
suspend fun kotlinJsToJs(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Set<File> = emptySet(),
    outputDir: File,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
    arguments: Configurer<K2JSCompilerArguments> = {}
): File = kotlinJsCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    outputMode = JsOutputMode.JS,
    moduleKind = moduleKind,
    sourceMap = sourceMap,
    outputDir = outputDir,
    arguments = arguments
)

/**
 * Convenience function for compiling Kotlin/JS to a .klib library.
 */
suspend fun kotlinJsToKlib(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Set<File> = emptySet(),
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments> = {}
): File = kotlinJsCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    outputMode = JsOutputMode.KLIB,
    outputDir = outputDir,
    arguments = arguments
)

/**
 * Renders the KLIB-phase arguments. The module's own [libraries] (dependency KLIBs) go on the
 * classpath; the caller's [arguments] configurer (e.g. `multiPlatform`, `commonSources`) is applied
 * here since it drives the frontend. BTA's KLIB operation controls the production mode and output
 * path, so those are not set as arguments.
 */
private fun klibArgStrings(
    name: String,
    libraries: Set<File>,
    arguments: Configurer<K2JSCompilerArguments>
): List<String> {
    val args = K2JSCompilerArguments().apply {
        moduleName = name
        if (libraries.isNotEmpty()) {
            this.libraries = libraries.joinToString(File.pathSeparator) { it.absolutePath }
        }
        arguments(this)
    }
    return ArgumentUtils.convertArgumentsToStringListNoDefaults(args)
}

/**
 * Renders the linking-phase arguments. The KLIB being linked is supplied to BTA's linking operation
 * directly (so it is not repeated here); [libraries] are the dependency KLIBs. Only linking-relevant
 * flags (module kind, source maps) are set.
 */
private fun linkArgStrings(
    name: String,
    libraries: Set<File>,
    moduleKind: JsModuleKind,
    sourceMap: Boolean
): List<String> {
    val args = K2JSCompilerArguments().apply {
        moduleName = name
        if (libraries.isNotEmpty()) {
            this.libraries = libraries.joinToString(File.pathSeparator) { it.absolutePath }
        }
        this.moduleKind = moduleKind.value
        this.sourceMap = sourceMap
        if (sourceMap) sourceMapEmbedSources = "always"
    }
    return ArgumentUtils.convertArgumentsToStringListNoDefaults(args)
}

/** Maps daemon error messages to a [Kotlin.CompilationException]. */
private fun failIfErrors(errors: List<String>) {
    if (errors.isNotEmpty()) {
        throw Kotlin.CompilationException(errors.map { Kotlin.CompilationMessage(CompilerMessageSeverity.ERROR, it) })
    }
}
