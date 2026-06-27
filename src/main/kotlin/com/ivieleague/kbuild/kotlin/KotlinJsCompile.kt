package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.build.report.DoNothingICReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.io.PrintStream

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
 * Compiles Kotlin/JS sources reactively with incremental compilation support.
 *
 * The compilation is cached based on input values.
 * When any input reactive changes, the compilation will re-run.
 *
 * @param name Module name
 * @param sourceRoots Reactive set of source root directories
 * @param libraries Reactive set of library files (.klib or .jar with JS metadata)
 * @param arguments Additional compiler arguments
 * @param outputMode Whether to output JS or KLIB
 * @param moduleKind Module format for JS output
 * @param sourceMap Whether to generate source maps
 * @param cache Directory for incremental compilation cache (null for non-incremental)
 * @param outputDir Output directory for compiled files
 * @return The output directory or file
 */
suspend fun kotlinJsCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
    arguments: Configurer<K2JSCompilerArguments> = {},
    outputMode: JsOutputMode = JsOutputMode.JS,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
    cache: File? = null,
    outputDir: File
): File {
    val sources = sourceRoots()
    val libs = libraries()

    return withContext(Dispatchers.IO) {
        kotlinJsCompileBlocking(
            name = name,
            sourceRoots = sources,
            libraries = libs,
            arguments = arguments,
            outputMode = outputMode,
            moduleKind = moduleKind,
            sourceMap = sourceMap,
            cache = cache,
            outputDir = outputDir
        )
    }
}

/**
 * Convenience function for compiling Kotlin/JS to JavaScript.
 */
suspend fun kotlinJsToJs(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
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
    libraries: Reactive<Set<File>>,
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
 * Blocking Kotlin/JS compilation with optional incremental support.
 * Use [kotlinJsCompile] for reactive usage.
 *
 * For JS output mode, K2 requires a two-phase compilation:
 * 1. Sources → KLIB (intermediate)
 * 2. KLIB → JS (linking)
 *
 * @param name Module name
 * @param sourceRoots Source root directories
 * @param libraries Library files (.klib)
 * @param arguments Additional compiler arguments
 * @param outputMode Whether to output JS or KLIB
 * @param moduleKind Module format for JS output
 * @param sourceMap Whether to generate source maps
 * @param cache Directory for incremental compilation cache (null for non-incremental)
 * @param outputDir Output directory for compiled files
 */
fun kotlinJsCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    libraries: Set<File> = emptySet(),
    arguments: Configurer<K2JSCompilerArguments> = {},
    outputMode: JsOutputMode = JsOutputMode.JS,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
    cache: File? = null,
    outputDir: File
): File {
    outputDir.mkdirs()

    val allSourceFiles = sourceRoots.asSequence()
        .flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" }
        .toList()

    if (allSourceFiles.isEmpty()) {
        // No source files - create empty output directory
        return outputDir
    }

    val libraryFiles = libraries.toList()

    // Incremental compilation with patched IC when cache is enabled
    if (cache != null) {
        cache.mkdirs()
        val tracker = SourceFileTracker.forCache(cache)
        val changes = tracker.computeChanges(allSourceFiles)

        // Skip compilation if no changes and output exists
        if (!changes.isFirstBuild && changes.isEmpty) {
            val hasOutput = when (outputMode) {
                JsOutputMode.KLIB -> outputDir.resolve("$name.klib").exists()
                JsOutputMode.JS -> outputDir.walkTopDown().any { it.extension == "js" || it.extension == "mjs" }
            }
            if (hasOutput) {
                println("No source changes detected, skipping JS compilation")
                return when (outputMode) {
                    JsOutputMode.KLIB -> outputDir.resolve("$name.klib")
                    JsOutputMode.JS -> outputDir
                }
            }
        }

        // Check for new files BEFORE any compilation
        // A "new file" is one that has never been compiled (no entry in cache)
        val expectedKlib = outputDir.resolve("$name.klib")
        val newFiles = if (!changes.isFirstBuild) {
            changes.modified.filter { f ->
                // Check if this file has been compiled before by looking at cache state
                // For simplicity, check if the klib exists - if not, all files are "new"
                !expectedKlib.exists()
            }
        } else emptyList()

        val hasNewFiles = newFiles.isNotEmpty() || changes.isFirstBuild

        if (!changes.isFirstBuild && !hasNewFiles) {
            println("JS incremental: ${changes.modified.size} modified, ${changes.removed.size} removed")
        } else if (!changes.isFirstBuild) {
            // New files require clearing cache to avoid IC state mismatch
            println("JS: New files detected, clearing cache for full rebuild")
            cache.deleteRecursively()
            cache.mkdirs()
            outputDir.deleteRecursively()
            outputDir.mkdirs()
        } else {
            println("JS: First build - full compilation")
        }

        return when (outputMode) {
            JsOutputMode.KLIB -> {
                compileToKlibIncremental(
                    name = name,
                    sourceRoots = sourceRoots,
                    libraries = libraryFiles,
                    cache = cache,
                    outputDir = outputDir,
                    arguments = arguments
                )
            }
            JsOutputMode.JS -> {
                // Two-phase compilation for K2:
                // Phase 1: sources → intermediate klib
                val klibCacheDir = cache.resolve("klib-cache")
                val tempKlibDir = outputDir.parentFile.resolve("${outputDir.name}-klib-temp")
                tempKlibDir.mkdirs()

                val expectedKlibFile = tempKlibDir.resolve("$name.klib")
                val klibModTimeBefore = if (expectedKlibFile.exists()) expectedKlibFile.lastModified() else -1L

                val intermediateKlib = compileToKlibIncremental(
                    name = name,
                    sourceRoots = sourceRoots,
                    libraries = libraryFiles,
                    cache = klibCacheDir,
                    outputDir = tempKlibDir,
                    arguments = arguments
                )

                // Phase 2: link klib → JS (skip if KLIB unchanged and output exists)
                val klibModTimeAfter = intermediateKlib.lastModified()
                val klibUnchanged = klibModTimeBefore != -1L && klibModTimeBefore == klibModTimeAfter
                val hasJsOutput = outputDir.walkTopDown().any { it.extension == "js" || it.extension == "mjs" }

                if (klibUnchanged && hasJsOutput) {
                    println("KLIB unchanged, skipping JS linking")
                    return outputDir
                }

                linkToJs(
                    name = name,
                    klib = intermediateKlib,
                    libraries = libraryFiles,
                    outputDir = outputDir,
                    moduleKind = moduleKind,
                    sourceMap = sourceMap,
                    arguments = arguments
                )
            }
        }
    }

    // Non-incremental compilation (no cache)
    return when (outputMode) {
        JsOutputMode.KLIB -> {
            compileToKlib(
                name = name,
                sourceFiles = allSourceFiles,
                libraries = libraryFiles,
                outputDir = outputDir,
                arguments = arguments
            )
        }
        JsOutputMode.JS -> {
            // Two-phase compilation for K2:
            // Phase 1: sources → intermediate klib
            val tempKlibDir = outputDir.parentFile.resolve("${outputDir.name}-klib-temp")
            tempKlibDir.mkdirs()

            val intermediateKlib = compileToKlib(
                name = name,
                sourceFiles = allSourceFiles,
                libraries = libraryFiles,
                outputDir = tempKlibDir,
                arguments = arguments
            )

            // Phase 2: link klib → JS
            try {
                linkToJs(
                    name = name,
                    klib = intermediateKlib,
                    libraries = libraryFiles,
                    outputDir = outputDir,
                    moduleKind = moduleKind,
                    sourceMap = sourceMap,
                    arguments = arguments
                )
            } finally {
                tempKlibDir.deleteRecursively()
            }
        }
    }
}

/**
 * Incremental compilation to KLIB using patched IncrementalJsCompilerRunner.
 *
 * Uses ByteBuddy patches to fix K2 JS IC bugs:
 * 1. NPE in TranslationResultMap.remove()
 * 2. Relative/absolute path mismatch in dirty source filtering
 */
private fun compileToKlibIncremental(
    name: String,
    sourceRoots: Set<File>,
    libraries: List<File>,
    cache: File,
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments>
): File {
    cache.mkdirs()
    outputDir.mkdirs()

    val collector = Kotlin.CompilationMessageCollector()
    val buildHistoryFile = cache.resolve("build-history.bin")

    val args = K2JSCompilerArguments().apply {
        moduleName = name

        if (libraries.isNotEmpty()) {
            this.libraries = libraries.joinToString(File.pathSeparator) { it.absolutePath }
        }

        irProduceKlibDir = false
        irProduceKlibFile = true
        irProduceJs = false
        this.outputDir = outputDir.absolutePath

        arguments()
    }

    val reporter = object : org.jetbrains.kotlin.build.report.ICReporter {
        override fun report(message: () -> String, severity: org.jetbrains.kotlin.build.report.ICReporter.ReportSeverity) {
            if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                println("[JS-IC] ${severity}: ${message()}")
            }
        }
        override fun reportCompileIteration(incremental: Boolean, sourceFiles: Collection<File>, exitCode: org.jetbrains.kotlin.cli.common.ExitCode) {
            if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
                println("JS compile iteration: incremental=$incremental, files=${sourceFiles.size}, exit=$exitCode")
            }
        }
        override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {
            if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                println("[JS-IC] markDirty: $affectedFiles; $reason")
            }
        }
        override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {
            if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                println("[JS-IC] markDirtyClass: $affectedFiles; $classFqName")
            }
        }
        override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {
            if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                println("[JS-IC] markDirtyMember: $affectedFiles; $scope; $name")
            }
        }
    }

    // Use the patched incremental compilation. JS runs in-process; the lock keeps it from
    // overlapping any other in-process compilation (the metadata compile or JVM snapshotting).
    InProcessCompileLock.guard {
        makeJsIncrementallyEnhanced(
            cachesDir = cache,
            sourceRoots = sourceRoots,
            args = args,
            buildHistoryFile = buildHistoryFile,
            messageCollector = collector,
            reporter = reporter
        )
    }

    for (message in collector.messages) {
        if (message.severity <= CompilerMessageSeverity.WARNING) {
            println("${message.message} at ${message.location}")
        }
    }

    val errors = collector.messages.filter { it.severity == CompilerMessageSeverity.ERROR }
    if (errors.isNotEmpty()) {
        throw Kotlin.CompilationException(collector.messages)
    }

    val expectedOutput = outputDir.resolve("$name.klib")
    if (!expectedOutput.exists()) {
        throw IllegalStateException("JS incremental compilation succeeded but output not found: $expectedOutput")
    }

    return expectedOutput
}

/**
 * Phase 1: Compile sources to KLIB (non-incremental)
 *
 * Note: K2 JS incremental compilation (makeJsIncrementally) has a known bug where
 * clearCacheForRemovedClasses throws NPE when translationResults.remove is called
 * on a file not in the cache. This bug is triggered on every first build.
 * Until this is fixed in the Kotlin compiler, we use non-incremental compilation
 * and rely on our file change tracking for no-change skip optimization.
 */
private fun compileToKlib(
    name: String,
    sourceFiles: List<File>,
    libraries: List<File>,
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments>
): File {
    val collector = Kotlin.CompilationMessageCollector()
    // Suppress stdout as K2 JS compiler prints verbose phase names. The in-process lock keeps this
    // from overlapping any other in-process compilation.
    val code = InProcessCompileLock.guard { suppressStdout {
        K2JSCompiler().exec(
            messageCollector = collector,
            services = Services.EMPTY,
            arguments = K2JSCompilerArguments().apply {
                moduleName = name
                freeArgs = sourceFiles.map { it.absolutePath }

                if (libraries.isNotEmpty()) {
                    this.libraries = libraries.joinToString(File.pathSeparator) { it.absolutePath }
                }

                // Produce klib only
                irProduceKlibDir = false
                irProduceKlibFile = true
                irProduceJs = false
                this.outputDir = outputDir.absolutePath

                arguments()
            }
        )
    } }

    for (message in collector.messages) {
        if (message.severity <= CompilerMessageSeverity.WARNING) {
            println("${message.message} at ${message.location}")
        }
    }

    if (code != ExitCode.OK) {
        throw Kotlin.CompilationException(collector.messages)
    }

    return outputDir.resolve("$name.klib")
}

/**
 * EXPERIMENTAL: Incremental compilation to KLIB using makeJsIncrementally.
 *
 * This attempts to work around the K2 JS incremental compiler bug (NPE in
 * TranslationResultMap.remove) by providing explicit ChangedFiles.Known instead
 * of letting the compiler compute changes.
 *
 * The theory: The NPE happens when files remain in dirtySources that were never
 * in translationResults. By providing ChangedFiles.Known with no "removed" files,
 * we avoid the clearCacheForRemovedClasses code path that triggers the bug.
 *
 * @return The output klib file, or null if incremental compilation failed
 */
internal fun compileToKlibIncrementalExperimental(
    name: String,
    sourceRoots: Set<File>,
    libraries: List<File>,
    cache: File,
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments>
): File? {
    cache.mkdirs()
    outputDir.mkdirs()

    val allSourceFiles = sourceRoots.asSequence()
        .flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" }
        .toList()

    val collector = Kotlin.CompilationMessageCollector()
    val buildHistoryFile = cache.resolve("build-history.bin")
    val expectedOutput = outputDir.resolve("$name.klib")

    val args = K2JSCompilerArguments().apply {
        moduleName = name

        if (libraries.isNotEmpty()) {
            this.libraries = libraries.joinToString(File.pathSeparator) { it.absolutePath }
        }

        irProduceKlibDir = false
        irProduceKlibFile = true
        irProduceJs = false
        this.outputDir = outputDir.absolutePath

        arguments()
    }

    val reporter = object : ICReporter {
        override fun report(message: () -> String, severity: ICReporter.ReportSeverity) {
            if (severity == ICReporter.ReportSeverity.WARNING || severity == ICReporter.ReportSeverity.INFO) {
                println("[IC] ${severity}: ${message()}")
            }
        }
        override fun reportCompileIteration(incremental: Boolean, sourceFiles: Collection<File>, exitCode: ExitCode) {
            println("[IC] Compile: incremental=$incremental, files=${sourceFiles.size}, exit=$exitCode")
        }
        override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {}
        override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {}
        override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {}
    }

    // Use our enhanced version with comprehensive debugging. In-process: guard against overlap.
    InProcessCompileLock.guard {
        makeJsIncrementallyEnhanced(
            cachesDir = cache,
            sourceRoots = sourceRoots,
            args = args,
            buildHistoryFile = buildHistoryFile,
            messageCollector = collector,
            reporter = reporter
        )
    }

    // WORKAROUND: If we get "Conflicting overloads" errors, it's likely cache corruption
    // Clear cache and output, then retry with a full rebuild
    val hasConflictingOverloads = collector.messages.any {
        it.severity == CompilerMessageSeverity.ERROR &&
        it.message.contains("Conflicting overloads")
    }
    if (hasConflictingOverloads) {
        println("[IC] Detected 'Conflicting overloads' error - cache corruption detected")
        println("[IC] Clearing cache and output, retrying with full rebuild...")

        // Clear everything
        cache.deleteRecursively()
        cache.mkdirs()
        expectedOutput.deleteRecursively()

        // Retry with fresh cache
        val retryCollector = Kotlin.CompilationMessageCollector()
        InProcessCompileLock.guard {
            makeJsIncrementallyFixed(
                cachesDir = cache,
                sourceRoots = sourceRoots,
                args = args,
                buildHistoryFile = buildHistoryFile,
                messageCollector = retryCollector,
                reporter = reporter
            )
        }

        // Use retry results
        collector.messages.clear()
        collector.messages.addAll(retryCollector.messages)

        println("[IC] Retry complete")
    }

    val errors = collector.messages.filter { it.severity == CompilerMessageSeverity.ERROR }
    if (errors.isNotEmpty()) {
        throw Kotlin.CompilationException(collector.messages)
    }

    return if (expectedOutput.exists()) expectedOutput else null
}

/**
 * Phase 2: Link KLIB to JS
 *
 * Note: K2 compiler has a bug where it throws an error during cleanup when
 * it can't find the klib file (which it deleted internally). We catch this
 * and check if the output was actually created.
 */
private fun linkToJs(
    name: String,
    klib: File,
    libraries: List<File>,
    outputDir: File,
    moduleKind: JsModuleKind,
    sourceMap: Boolean,
    arguments: Configurer<K2JSCompilerArguments>
): File {
    val collector = Kotlin.CompilationMessageCollector()

    // All klibs to include: our compiled klib + dependencies
    val allKlibs = listOf(klib) + libraries

    val args = K2JSCompilerArguments().apply {
        moduleName = name

        // No source files in linking phase
        freeArgs = emptyList()

        // All klibs as libraries
        this.libraries = allKlibs.joinToString(File.pathSeparator) { it.absolutePath }

        // Must specify klibs to include in output
        includes = klib.absolutePath

        // Produce JS only
        irProduceKlibDir = false
        irProduceKlibFile = false
        irProduceJs = true
        this.outputDir = outputDir.absolutePath
        this.moduleKind = moduleKind.value

        this.sourceMap = sourceMap
        if (sourceMap) {
            sourceMapEmbedSources = "always"
        }

        arguments()
    }

    // K2 compiler may throw AssertionError during cleanup even after successful compilation
    // We catch this and verify the output was created
    // Suppress stdout as K2 JS compiler prints verbose phase names
    val code = try {
        InProcessCompileLock.guard { suppressStdout {
            K2JSCompiler().exec(
                messageCollector = collector,
                services = Services.EMPTY,
                arguments = args
            )
        } }
    } catch (e: AssertionError) {
        // Check if this is the known cleanup bug (NoSuchFileException for klib)
        if (e.cause is java.nio.file.NoSuchFileException) {
            // Verify the output was actually created despite the cleanup error
            val expectedOutput = outputDir.resolve("$name.mjs")
            val alternateOutput = outputDir.resolve("$name.js")
            if (expectedOutput.exists() || alternateOutput.exists() ||
                outputDir.listFiles()?.any { it.extension == "mjs" || it.extension == "js" } == true) {
                // Compilation succeeded, ignore cleanup error
                ExitCode.OK
            } else {
                throw e
            }
        } else {
            throw e
        }
    }

    for (message in collector.messages) {
        if (message.severity <= CompilerMessageSeverity.WARNING) {
            println("${message.message} at ${message.location}")
        }
    }

    if (code != ExitCode.OK) {
        throw Kotlin.CompilationException(collector.messages)
    }

    return outputDir
}

/**
 * Suppresses stdout during block execution unless in Debug mode.
 * The K2 JS compiler prints phase names to stdout which clutters output.
 */
private inline fun <T> suppressStdout(block: () -> T): T {
    if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
        return block()
    }
    val originalOut = System.out
    try {
        System.setOut(PrintStream(object : java.io.OutputStream() {
            override fun write(b: Int) {}
        }))
        return block()
    } finally {
        System.setOut(originalOut)
    }
}
