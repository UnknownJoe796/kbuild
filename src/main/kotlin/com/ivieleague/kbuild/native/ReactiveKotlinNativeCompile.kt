package com.ivieleague.kbuild.native

import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Extension function to convert a Set to a Reactive.
 */
fun <T> Set<T>.asNativeReactive(): Reactive<Set<T>> = object : Reactive<Set<T>> {
    override val state: ReactiveState<Set<T>> = ReactiveState(this@asNativeReactive)
    override fun addListener(listener: () -> Unit): () -> Unit = {}
}

/**
 * A reactive Kotlin/Native compiler that automatically recompiles when sources or dependencies change.
 *
 * This integrates with the reactive build system:
 * - Monitors source files for changes
 * - Monitors library dependencies for changes
 * - Recompiles automatically when changes detected
 *
 * State:
 * - notReady: compilation in progress or compiler downloading
 * - Success(File): compilation succeeded (output file)
 * - exception: compilation failed
 *
 * @param name Module name
 * @param sources Reactive source of source directories
 * @param libraries Reactive source of .klib library files
 * @param target Target platform
 * @param outputKind Type of output to produce
 * @param outputDir Output directory
 * @param optimizations Enable release optimizations
 * @param debug Include debug information
 * @param additionalArgs Additional compiler arguments
 */
class ReactiveKotlinNativeCompile(
    val name: String,
    val sources: Reactive<Set<File>>,
    val libraries: Reactive<Set<File>> = emptySet<File>().asNativeReactive(),
    val target: KonanTarget = KonanTarget.host(),
    val outputKind: NativeOutputKind = NativeOutputKind.EXECUTABLE,
    val outputDir: File,
    val optimizations: Boolean = false,
    val debug: Boolean = true,
    val additionalArgs: List<String> = emptyList()
) : BaseReactive<File>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeSourcesListener: (() -> Unit)? = null
    private var removeLibrariesListener: (() -> Unit)? = null

    /**
     * The underlying compiler instance.
     */
    val compiler: KotlinNativeCompile by lazy {
        KotlinNativeCompile(
            name = name,
            sourceRoots = { sources.state.getOrNull()?.filter { it.isDirectory }?.toSet() ?: emptySet() },
            libraries = { libraries.state.getOrNull() ?: emptySet() },
            target = target,
            outputKind = outputKind,
            outputDir = outputDir,
            optimizations = optimizations,
            debug = debug,
            additionalArgs = additionalArgs
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

    /**
     * Callback when compiler is being downloaded.
     */
    var onCompilerDownloading: (() -> Unit)? = null

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
            state = ReactiveState.exception(IllegalStateException("No source directories"))
            return
        }

        // Mark as compiling
        state = ReactiveState.notReady
        onCompileStart?.invoke()

        // Compile in background
        scope.launch {
            try {
                // Ensure compiler is installed first
                if (!KonanCompiler.default().isInstalled()) {
                    onCompilerDownloading?.invoke()
                }

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
 * Create a reactive Kotlin/Native compiler for producing an executable.
 */
fun reactiveKotlinNativeExecutable(
    name: String,
    sources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>> = emptySet<File>().asNativeReactive(),
    target: KonanTarget = KonanTarget.host(),
    outputDir: File,
    optimizations: Boolean = false,
    debug: Boolean = true
): ReactiveKotlinNativeCompile = ReactiveKotlinNativeCompile(
    name = name,
    sources = sources,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.EXECUTABLE,
    outputDir = outputDir,
    optimizations = optimizations,
    debug = debug
)

/**
 * Create a reactive Kotlin/Native compiler for producing a .klib library.
 */
fun reactiveKotlinNativeLibrary(
    name: String,
    sources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>> = emptySet<File>().asNativeReactive(),
    target: KonanTarget = KonanTarget.host(),
    outputDir: File
): ReactiveKotlinNativeCompile = ReactiveKotlinNativeCompile(
    name = name,
    sources = sources,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.LIBRARY,
    outputDir = outputDir,
    optimizations = false,
    debug = false
)

/**
 * Create a reactive Kotlin/Native compiler for producing an Apple framework.
 */
fun reactiveKotlinNativeFramework(
    name: String,
    sources: Reactive<Set<File>>,
    libraries: Reactive<Set<File>> = emptySet<File>().asNativeReactive(),
    target: KonanTarget,
    outputDir: File,
    static: Boolean = false
): ReactiveKotlinNativeCompile {
    require(target.family in listOf(TargetFamily.OSX, TargetFamily.IOS, TargetFamily.WATCHOS, TargetFamily.TVOS)) {
        "Frameworks are only supported on Apple platforms, got: $target"
    }
    return ReactiveKotlinNativeCompile(
        name = name,
        sources = sources,
        libraries = libraries,
        target = target,
        outputKind = if (static) NativeOutputKind.STATIC_FRAMEWORK else NativeOutputKind.FRAMEWORK,
        outputDir = outputDir,
        optimizations = false,
        debug = true
    )
}
