package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
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
 * Compiles Kotlin/JS sources reactively.
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
 * @param outputDir Output directory for compiled files
 * @return The output directory or file
 */
context(ReactiveContext)
fun kotlinJsCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
    arguments: Configurer<K2JSCompilerArguments> = {},
    outputMode: JsOutputMode = JsOutputMode.JS,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
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
            outputDir = outputDir
        )
    }
}

/**
 * Convenience function for compiling Kotlin/JS to JavaScript.
 */
context(ReactiveContext)
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
context(ReactiveContext)
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
 * Blocking Kotlin/JS compilation.
 * Use [kotlinJsCompile] for reactive usage.
 *
 * For JS output mode, K2 requires a two-phase compilation:
 * 1. Sources → KLIB (intermediate)
 * 2. KLIB → JS (linking)
 */
fun kotlinJsCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    libraries: Set<File> = emptySet(),
    arguments: Configurer<K2JSCompilerArguments> = {},
    outputMode: JsOutputMode = JsOutputMode.JS,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
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
            // Single phase: sources → klib
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
            // Phase 1: sources → intermediate klib (in separate directory to avoid conflicts)
            val tempKlibDir = outputDir.parentFile.resolve("${outputDir.name}-klib-temp")
            tempKlibDir.deleteRecursively()
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
                // Clean up intermediate klib after successful linking
                tempKlibDir.deleteRecursively()
            }
        }
    }
}

/**
 * Phase 1: Compile sources to KLIB
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

            noStdlib = true
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

// Legacy class-based API for backwards compatibility
@Deprecated("Use kotlinJsCompile function with ReactiveContext instead")
class KotlinJsCompile(
    val name: String,
    val sourceRoots: () -> Set<File>,
    val libraries: () -> Set<File> = { emptySet() },
    val arguments: Configurer<K2JSCompilerArguments> = {},
    val outputMode: JsOutputMode = JsOutputMode.JS,
    val moduleKind: JsModuleKind = JsModuleKind.ES,
    val sourceMap: Boolean = true,
    val outputDir: File
) : () -> File {

    val outputFile: File
        get() = when (outputMode) {
            JsOutputMode.JS -> outputDir.resolve("$name.js")
            JsOutputMode.KLIB -> outputDir.resolve("$name.klib")
        }

    override fun invoke(): File = kotlinJsCompileBlocking(
        name = name,
        sourceRoots = sourceRoots(),
        libraries = libraries(),
        arguments = arguments,
        outputMode = outputMode,
        moduleKind = moduleKind,
        sourceMap = sourceMap,
        outputDir = outputDir
    )
}
