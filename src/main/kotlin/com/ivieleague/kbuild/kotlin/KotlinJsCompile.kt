package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import org.jetbrains.kotlin.build.report.DoNothingICReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.makeJsIncrementally
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
context(ctx: ReactiveContext)
fun kotlinJsCompile(
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

    return async(name, sources, libs, outputDir, outputMode, moduleKind) {
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
context(ctx: ReactiveContext)
fun kotlinJsToJs(
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
context(ctx: ReactiveContext)
fun kotlinJsToKlib(
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

    return when (outputMode) {
        JsOutputMode.KLIB -> {
            if (cache != null) {
                // Incremental compilation to klib
                compileToKlibIncremental(
                    name = name,
                    sourceRoots = sourceRoots,
                    libraries = libraryFiles,
                    cache = cache,
                    outputDir = outputDir,
                    arguments = arguments
                )
            } else {
                // Non-incremental: sources → klib
                compileToKlib(
                    name = name,
                    sourceFiles = allSourceFiles,
                    libraries = libraryFiles,
                    outputDir = outputDir,
                    arguments = arguments
                )
            }
        }
        JsOutputMode.JS -> {
            // Two-phase compilation for K2:
            // Phase 1: sources → intermediate klib (in separate directory to avoid conflicts)
            val tempKlibDir = outputDir.parentFile.resolve("${outputDir.name}-klib-temp")
            tempKlibDir.mkdirs()

            val intermediateKlib = if (cache != null) {
                // Incremental compilation for the klib phase
                compileToKlibIncremental(
                    name = name,
                    sourceRoots = sourceRoots,
                    libraries = libraryFiles,
                    cache = cache,
                    outputDir = tempKlibDir,
                    arguments = arguments
                )
            } else {
                tempKlibDir.deleteRecursively()
                tempKlibDir.mkdirs()
                compileToKlib(
                    name = name,
                    sourceFiles = allSourceFiles,
                    libraries = libraryFiles,
                    outputDir = tempKlibDir,
                    arguments = arguments
                )
            }

            // Phase 2: link klib → JS (linking is always non-incremental)
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
                // Clean up intermediate klib after successful linking (only if non-incremental)
                if (cache == null) {
                    tempKlibDir.deleteRecursively()
                }
            }
        }
    }
}

/**
 * Incremental compilation to KLIB using makeJsIncrementally.
 * This tracks file changes and only recompiles what's necessary.
 *
 * Note: K2 JS incremental compiler has bugs in cache management. We catch
 * these errors and verify the output was created successfully.
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
    val expectedOutput = outputDir.resolve("$name.klib")

    val args = K2JSCompilerArguments().apply {
        moduleName = name

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

    val reporter = object : ICReporter {
        override fun report(message: () -> String, severity: ICReporter.ReportSeverity) {
            if (severity == ICReporter.ReportSeverity.WARNING || severity == ICReporter.ReportSeverity.INFO) {
                println("[IC] ${severity}: ${message()}")
            }
        }

        override fun reportCompileIteration(
            incremental: Boolean,
            sourceFiles: Collection<File>,
            exitCode: ExitCode
        ) {
            println("[IC] Compile iteration: incremental=$incremental, files=${sourceFiles.size}, exit=$exitCode")
        }

        override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {
            println("[IC] Mark dirty: ${affectedFiles.count()} files, reason: $reason")
        }

        override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {
            // Verbose, skip
        }

        override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {
            // Verbose, skip
        }
    }

    // K2 JS incremental compiler has bugs in cache management (NPE in clearCacheForRemovedClasses)
    // We catch these and verify the output was created
    try {
        makeJsIncrementally(
            cachesDir = cache,
            sourceRoots = sourceRoots,
            args = args,
            buildHistoryFile = buildHistoryFile,
            messageCollector = collector,
            reporter = reporter
        )
    } catch (e: NullPointerException) {
        // Known K2 bug in IncrementalJsCache.clearCacheForRemovedClasses
        // Check if compilation actually succeeded
        if (expectedOutput.exists()) {
            println("[IC] Warning: Cache update failed but output was created: ${e.message}")
        } else {
            throw e
        }
    }

    // Check for compilation errors
    val errors = collector.messages.filter { it.severity == CompilerMessageSeverity.ERROR }
    if (errors.isNotEmpty()) {
        throw Kotlin.CompilationException(collector.messages)
    }

    if (!expectedOutput.exists()) {
        throw IllegalStateException("Incremental compilation did not produce expected output: $expectedOutput")
    }

    return expectedOutput
}

/**
 * Phase 1: Compile sources to KLIB (non-incremental)
 */
private fun compileToKlib(
    name: String,
    sourceFiles: List<File>,
    libraries: List<File>,
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments>
): File {
    val collector = Kotlin.CompilationMessageCollector()
    val code = K2JSCompiler().exec(
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
    val code = try {
        K2JSCompiler().exec(
            messageCollector = collector,
            services = Services.EMPTY,
            arguments = args
        )
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
