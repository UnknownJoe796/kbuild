package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import java.io.File

/**
 * A reactive Kotlin/JS compiler that automatically recompiles when sources or dependencies change.
 *
 * This integrates with the reactive build system:
 * - Monitors source files for changes
 * - Monitors klib dependencies for changes
 * - Recompiles automatically when changes detected
 *
 * State:
 * - notReady: compilation in progress
 * - Success(File): compilation succeeded (output directory or klib file)
 * - exception: compilation failed
 *
 * @param name Module name
 * @param sources Reactive source of source files
 * @param libraries Reactive source of klib library files
 * @param arguments Additional compiler arguments
 * @param outputMode Whether to output JS or KLIB
 * @param moduleKind Module format for JS output
 * @param sourceMap Whether to generate source maps
 * @param outputDir Output directory
 */
class ReactiveKotlinJsCompile(
    val name: String,
    val sources: Reactive<Set<File>>,
    val libraries: Reactive<Set<File>> = emptySet<File>().asReactive(),
    val arguments: Configurer<K2JSCompilerArguments> = {},
    val outputMode: JsOutputMode = JsOutputMode.JS,
    val moduleKind: JsModuleKind = JsModuleKind.ES,
    val sourceMap: Boolean = true,
    val outputDir: File
) : BaseReactive<File>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeSourcesListener: (() -> Unit)? = null
    private var removeLibrariesListener: (() -> Unit)? = null

    /**
     * The underlying compiler instance.
     */
    val compiler: KotlinJsCompile by lazy {
        KotlinJsCompile(
            name = name,
            sourceRoots = { sources.state.getOrNull()?.filter { it.isDirectory }?.toSet() ?: emptySet() },
            libraries = { libraries.state.getOrNull() ?: emptySet() },
            arguments = arguments,
            outputMode = outputMode,
            moduleKind = moduleKind,
            sourceMap = sourceMap,
            outputDir = outputDir
        )
    }

    /**
     * Callback when compilation starts.
     */
    var onCompileStart: (() -> Unit)? = null

    /**
     * Callback when compilation completes successfully.
     */
    var onCompileSuccess: ((File) -> Unit)? = null

    /**
     * Callback when compilation fails.
     */
    var onCompileError: ((Throwable) -> Unit)? = null

    override fun activate() {
        removeSourcesListener = sources.addListener { triggerCompile() }
        removeLibrariesListener = libraries.addListener { triggerCompile() }

        // Initial compile
        triggerCompile()
    }

    override fun deactivate() {
        removeSourcesListener?.invoke()
        removeLibrariesListener?.invoke()
        removeSourcesListener = null
        removeLibrariesListener = null
    }

    private fun triggerCompile() {
        val sourcesState = sources.state
        val librariesState = libraries.state

        // Wait for sources to be ready
        if (!sourcesState.ready) {
            state = ReactiveState.notReady
            return
        }

        // Check for source errors
        sourcesState.exception?.let {
            state = ReactiveState.exception(it)
            return
        }

        // Wait for libraries to be ready
        if (!librariesState.ready) {
            state = ReactiveState.notReady
            return
        }

        // Check for library errors
        librariesState.exception?.let {
            state = ReactiveState.exception(it)
            return
        }

        val sourceFiles = sourcesState.getOrNull()
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            // No source files - return empty output
            outputDir.mkdirs()
            state = ReactiveState(outputDir)
            return
        }

        // Mark as compiling
        state = ReactiveState.notReady
        onCompileStart?.invoke()

        // Compile in background
        scope.launch {
            try {
                val result = compiler()
                state = ReactiveState(result)
                onCompileSuccess?.invoke(result)
            } catch (e: Exception) {
                state = ReactiveState.exception(e)
                onCompileError?.invoke(e)
            }
        }
    }
}

/**
 * Create a reactive Kotlin/JS compiler for producing JavaScript output.
 */
fun reactiveKotlinJsCompile(
    name: String,
    sources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>> = emptySet<File>().asReactive(),
    outputDir: File,
    moduleKind: JsModuleKind = JsModuleKind.ES,
    sourceMap: Boolean = true,
    arguments: Configurer<K2JSCompilerArguments> = {}
): ReactiveKotlinJsCompile = ReactiveKotlinJsCompile(
    name = name,
    sources = sources,
    libraries = libraries,
    outputMode = JsOutputMode.JS,
    moduleKind = moduleKind,
    sourceMap = sourceMap,
    outputDir = outputDir,
    arguments = arguments
)

/**
 * Create a reactive Kotlin/JS compiler for producing a .klib library.
 */
fun reactiveKotlinJsKlib(
    name: String,
    sources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>> = emptySet<File>().asReactive(),
    outputDir: File,
    arguments: Configurer<K2JSCompilerArguments> = {}
): ReactiveKotlinJsCompile = ReactiveKotlinJsCompile(
    name = name,
    sources = sources,
    libraries = libraries,
    outputMode = JsOutputMode.KLIB,
    outputDir = outputDir,
    arguments = arguments
)
