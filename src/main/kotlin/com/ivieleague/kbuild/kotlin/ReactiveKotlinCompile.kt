package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import com.lightningkite.reactive.core.BaseReactive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File

/**
 * A reactive Kotlin JVM compilation that automatically recompiles when sources or classpath change.
 *
 * This class wraps [KotlinJvmCompile] and exposes the compilation result as a [Reactive].
 * The compilation runs in a background thread and the state reflects:
 * - Loading: compilation in progress
 * - Success: compilation completed, value is the output folder
 * - Error: compilation failed
 *
 * @param name Module name for the compilation
 * @param sources Reactive set of source root directories
 * @param classpath Reactive set of classpath JAR files
 * @param arguments Optional compiler arguments configuration
 * @param cache Directory for incremental compilation cache
 * @param outputFolder Directory for compiled class files
 */
class ReactiveKotlinCompile(
    val name: String,
    val sources: Reactive<Set<File>>,
    val classpath: Reactive<Set<File>>,
    val arguments: Configurer<K2JVMCompilerArguments> = {},
    val cache: File,
    val outputFolder: File
) : BaseReactive<File>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var removeSourcesListener: (() -> Unit)? = null
    private var removeClasspathListener: (() -> Unit)? = null

    override fun activate() {
        // Subscribe to sources and classpath changes
        removeSourcesListener = sources.addListener { triggerRecompile() }
        removeClasspathListener = classpath.addListener { triggerRecompile() }

        // Initial compile
        triggerRecompile()
    }

    override fun deactivate() {
        removeSourcesListener?.invoke()
        removeClasspathListener?.invoke()
        removeSourcesListener = null
        removeClasspathListener = null
    }

    private fun triggerRecompile() {
        // Get current values
        val sourcesState = sources.state
        val classpathState = classpath.state

        // If either is not ready, we're not ready
        if (!sourcesState.ready || !classpathState.ready) {
            state = ReactiveState.notReady
            return
        }

        val currentSources = sourcesState.getOrNull() ?: return
        val currentClasspath = classpathState.getOrNull() ?: return

        // Mark as loading
        state = ReactiveState.notReady

        // Compile in background
        scope.launch {
            try {
                val compiler = KotlinJvmCompile(
                    name = name,
                    sourceRoots = { currentSources },
                    classpathJars = { currentClasspath },
                    arguments = arguments,
                    cache = cache,
                    outputFolder = outputFolder
                )
                val result = compiler()
                state = ReactiveState(result)
            } catch (e: Exception) {
                state = ReactiveState.exception(e)
            }
        }
    }
}

/**
 * Creates a reactive Kotlin JVM compilation.
 */
fun reactiveKotlinCompile(
    name: String,
    sources: Reactive<Set<File>>,
    classpath: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File
): ReactiveKotlinCompile = ReactiveKotlinCompile(
    name = name,
    sources = sources,
    classpath = classpath,
    arguments = arguments,
    cache = cache,
    outputFolder = outputFolder
)

/**
 * Extension to convert a static set of files to a Reactive for use with reactive compilation.
 */
fun Set<File>.asReactive(): Reactive<Set<File>> = object : Reactive<Set<File>> {
    override val state: ReactiveState<Set<File>> = ReactiveState(this@asReactive)
    override fun addListener(listener: () -> Unit): () -> Unit = { }
}
