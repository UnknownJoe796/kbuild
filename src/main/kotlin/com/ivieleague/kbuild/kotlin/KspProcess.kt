package com.ivieleague.kbuild.kotlin

import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.processing.KSPJsConfig
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPNativeConfig
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.ivieleague.kbuild.maven.MavenAether
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Simple logger that collects KSP messages for error reporting.
 */
class KBuildKspLogger(private val printLogs: Boolean = true) : KSPLogger {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun logging(message: String, symbol: KSNode?) {
        if (printLogs) println("[KSP] $message")
    }

    override fun info(message: String, symbol: KSNode?) {
        if (printLogs) println("[KSP INFO] $message")
    }

    override fun warn(message: String, symbol: KSNode?) {
        warnings.add(message)
        if (printLogs) println("[KSP WARN] $message")
    }

    override fun error(message: String, symbol: KSNode?) {
        errors.add(message)
        if (printLogs) System.err.println("[KSP ERROR] $message")
    }

    override fun exception(e: Throwable) {
        errors.add("${e::class.simpleName}: ${e.message}")
        if (printLogs) e.printStackTrace()
    }
}

/**
 * Exception thrown when KSP processing fails.
 */
class KspProcessingException(
    val errors: List<String>,
    val warnings: List<String>
) : RuntimeException("KSP processing failed with ${errors.size} error(s):\n${errors.joinToString("\n")}")

/**
 * Load symbol processor providers from the given classpath JARs.
 */
private fun loadProcessors(processorClasspath: Set<File>): List<SymbolProcessorProvider> {
    if (processorClasspath.isEmpty()) return emptyList()

    val classloader = URLClassLoader(
        processorClasspath.map { it.toURI().toURL() }.toTypedArray(),
        SymbolProcessorProvider::class.java.classLoader
    )
    return ServiceLoader.load(SymbolProcessorProvider::class.java, classloader).toList()
}

/**
 * Common configuration for KSP builders.
 * All paths are converted to absolute paths for compatibility with KSP2.
 */
private fun <T : KSPConfig.Builder> T.configureCommon(
    name: String,
    sourceRoots: Set<File>,
    libraries: Set<File>,
    processorOptions: Map<String, String>,
    kotlinOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File,
    projectBaseDir: File? = null,
    incremental: Boolean = false
): T = apply {
    // Convert all paths to absolute for KSP2 compatibility
    val absKotlinOutputDir = kotlinOutputDir.absoluteFile
    val absResourceOutputDir = resourceOutputDir.absoluteFile
    val absClassOutputDir = classOutputDir.absoluteFile
    val absCacheDir = cacheDir.absoluteFile
    val absOutputBaseDir = absKotlinOutputDir.parentFile

    // Project base dir should be a common ancestor of all paths
    val absProjectBaseDir = projectBaseDir?.absoluteFile
        ?: absOutputBaseDir.parentFile
        ?: File(".").absoluteFile

    moduleName = name
    this.sourceRoots = sourceRoots.flatMap { root ->
        val absRoot = root.absoluteFile
        if (absRoot.isDirectory) listOf(absRoot) else emptyList()
    }
    this.libraries = libraries.map { it.absoluteFile }
    this.processorOptions = processorOptions
    this.kotlinOutputDir = absKotlinOutputDir
    this.resourceOutputDir = absResourceOutputDir
    this.classOutputDir = absClassOutputDir
    this.cachesDir = absCacheDir
    this.projectBaseDir = absProjectBaseDir
    this.outputBaseDir = absOutputBaseDir
    this.incremental = incremental
    // Use reasonable defaults for language/API version
    this.languageVersion = "2.0"
    this.apiVersion = "2.0"
}

// =============================================================================
// JVM KSP Processing
// =============================================================================

/**
 * Run KSP processing for JVM target (blocking).
 *
 * @param name Module name
 * @param sourceRoots Source directories to process
 * @param classpathJars Classpath JARs for symbol resolution
 * @param processorClasspath JARs containing KSP processors
 * @param processorOptions Options to pass to processors
 * @param kotlinOutputDir Directory for generated Kotlin files
 * @param javaOutputDir Directory for generated Java files
 * @param resourceOutputDir Directory for generated resources
 * @param classOutputDir Directory for generated class files
 * @param cacheDir Directory for KSP cache
 * @param jvmTarget JVM target version (e.g., "17")
 * @return Set of output directories containing generated sources
 */
fun kspJvmProcessBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    javaOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File,
    jvmTarget: String = "17"
): Set<File> {
    val providers = loadProcessors(processorClasspath)
    if (providers.isEmpty()) {
        println("No KSP processors found in classpath")
        return emptySet()
    }

    // Ensure output directories exist
    kotlinOutputDir.mkdirs()
    javaOutputDir.mkdirs()
    resourceOutputDir.mkdirs()
    classOutputDir.mkdirs()
    cacheDir.mkdirs()

    val config = KSPJvmConfig.Builder().apply {
        configureCommon(
            name = name,
            sourceRoots = sourceRoots,
            libraries = classpathJars,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )
        this.javaOutputDir = javaOutputDir
        this.jvmTarget = jvmTarget
    }.build()

    val logger = KBuildKspLogger()
    val result = KotlinSymbolProcessing(config, providers, logger).execute()

    if (result != KotlinSymbolProcessing.ExitCode.OK) {
        throw KspProcessingException(logger.errors, logger.warnings)
    }

    // Return directories that have generated content
    return setOfNotNull(
        kotlinOutputDir.takeIf { it.walkTopDown().any { f -> f.extension == "kt" } },
        javaOutputDir.takeIf { it.walkTopDown().any { f -> f.extension == "java" } }
    )
}

/**
 * Run KSP processing for JVM target (reactive).
 */
suspend fun kspJvmProcess(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    javaOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File,
    jvmTarget: String = "17"
): Set<File> {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return withContext(Dispatchers.IO) {
        kspJvmProcessBlocking(
            name = name,
            sourceRoots = sources,
            classpathJars = classpath,
            processorClasspath = processorClasspath,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            javaOutputDir = javaOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir,
            jvmTarget = jvmTarget
        )
    }
}

// =============================================================================
// JS KSP Processing
// =============================================================================

/**
 * Run KSP processing for JS target (blocking).
 *
 * @param name Module name
 * @param sourceRoots Source directories to process
 * @param libraries KLIB files for symbol resolution
 * @param processorClasspath JARs containing KSP processors
 * @param processorOptions Options to pass to processors
 * @param kotlinOutputDir Directory for generated Kotlin files
 * @param resourceOutputDir Directory for generated resources
 * @param classOutputDir Directory for generated class files (KLIB output)
 * @param cacheDir Directory for KSP cache
 * @param backend JS backend: "IR" or "LEGACY" (default: "IR")
 * @return Set of output directories containing generated sources
 */
fun kspJsProcessBlocking(
    name: String,
    sourceRoots: Set<File>,
    libraries: Set<File>,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File,
    backend: String = "IR"
): Set<File> {
    val providers = loadProcessors(processorClasspath)
    if (providers.isEmpty()) {
        println("No KSP processors found in classpath")
        return emptySet()
    }

    // Ensure output directories exist
    kotlinOutputDir.mkdirs()
    resourceOutputDir.mkdirs()
    classOutputDir.mkdirs()
    cacheDir.mkdirs()

    val config = KSPJsConfig.Builder().apply {
        configureCommon(
            name = name,
            sourceRoots = sourceRoots,
            libraries = libraries,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )
        this.backend = backend
    }.build()

    val logger = KBuildKspLogger()
    val result = KotlinSymbolProcessing(config, providers, logger).execute()

    if (result != KotlinSymbolProcessing.ExitCode.OK) {
        throw KspProcessingException(logger.errors, logger.warnings)
    }

    return setOfNotNull(
        kotlinOutputDir.takeIf { it.walkTopDown().any { f -> f.extension == "kt" } }
    )
}

/**
 * Run KSP processing for JS target (reactive).
 */
suspend fun kspJsProcess(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File,
    backend: String = "IR"
): Set<File> {
    val sources = sourceRoots()
    val libs = libraries()

    return withContext(Dispatchers.IO) {
        kspJsProcessBlocking(
            name = name,
            sourceRoots = sources,
            libraries = libs,
            processorClasspath = processorClasspath,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir,
            backend = backend
        )
    }
}

// =============================================================================
// Native KSP Processing
// =============================================================================

/**
 * Run KSP processing for Native target (blocking).
 *
 * @param name Module name
 * @param sourceRoots Source directories to process
 * @param libraries KLIB files for symbol resolution
 * @param target Native target (e.g., "macos_arm64", "linux_x64", "mingw_x64")
 * @param processorClasspath JARs containing KSP processors
 * @param processorOptions Options to pass to processors
 * @param kotlinOutputDir Directory for generated Kotlin files
 * @param resourceOutputDir Directory for generated resources
 * @param classOutputDir Directory for generated class files (KLIB output)
 * @param cacheDir Directory for KSP cache
 * @return Set of output directories containing generated sources
 */
fun kspNativeProcessBlocking(
    name: String,
    sourceRoots: Set<File>,
    libraries: Set<File>,
    target: String,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File
): Set<File> {
    val providers = loadProcessors(processorClasspath)
    if (providers.isEmpty()) {
        println("No KSP processors found in classpath")
        return emptySet()
    }

    // Ensure output directories exist
    kotlinOutputDir.mkdirs()
    resourceOutputDir.mkdirs()
    classOutputDir.mkdirs()
    cacheDir.mkdirs()

    val config = KSPNativeConfig.Builder().apply {
        configureCommon(
            name = name,
            sourceRoots = sourceRoots,
            libraries = libraries,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )
        this.target = target
    }.build()

    val logger = KBuildKspLogger()
    val result = KotlinSymbolProcessing(config, providers, logger).execute()

    if (result != KotlinSymbolProcessing.ExitCode.OK) {
        throw KspProcessingException(logger.errors, logger.warnings)
    }

    return setOfNotNull(
        kotlinOutputDir.takeIf { it.walkTopDown().any { f -> f.extension == "kt" } }
    )
}

/**
 * Run KSP processing for Native target (reactive).
 */
suspend fun kspNativeProcess(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    libraries: Reactive<Set<File>>,
    target: String,
    processorClasspath: Set<File>,
    processorOptions: Map<String, String> = emptyMap(),
    kotlinOutputDir: File,
    resourceOutputDir: File,
    classOutputDir: File,
    cacheDir: File
): Set<File> {
    val sources = sourceRoots()
    val libs = libraries()

    return withContext(Dispatchers.IO) {
        kspNativeProcessBlocking(
            name = name,
            sourceRoots = sources,
            libraries = libs,
            target = target,
            processorClasspath = processorClasspath,
            processorOptions = processorOptions,
            kotlinOutputDir = kotlinOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )
    }
}

// =============================================================================
// Kotlinx Serialization Compiler Plugin
// =============================================================================

/**
 * Helper object for the kotlinx.serialization compiler plugin.
 *
 * Unlike KSP processors, kotlinx.serialization is a compiler plugin that runs
 * during Kotlin compilation. Use [pluginJar] to get the plugin JAR, then pass
 * it to [kotlinJvmCompileBlocking] via the `arguments` parameter:
 *
 * ```kotlin
 * kotlinJvmCompileBlocking(
 *     name = "my-app",
 *     sourceRoots = sources,
 *     classpathJars = classpath + SerializationPlugin.runtimeClasspath(),
 *     arguments = { SerializationPlugin.configure(this) },
 *     cache = cacheDir,
 *     outputFolder = outputDir
 * )
 * ```
 */
object SerializationPlugin {
    private var cachedPluginJar: File? = null
    private var cachedRuntimeLibs: Set<File>? = null

    /**
     * The Kotlin version to use for the serialization plugin.
     * Should match the Kotlin compiler version being used.
     */
    var kotlinVersion: String = "2.2.20"

    /**
     * The kotlinx.serialization runtime library version.
     */
    var serializationVersion: String = "1.6.3"

    /**
     * Get the kotlinx.serialization compiler plugin JAR.
     * Downloads from Maven if not already cached.
     *
     * Uses the embeddable version which is compatible with kotlin-compiler-embeddable.
     */
    suspend fun pluginJar(): File {
        cachedPluginJar?.let { if (it.exists()) return it }

        val libs = MavenAether.libraries(
            path = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:$kotlinVersion",
            fetchSources = false
        )
        val jar = libs.firstOrNull { it.name.contains("serialization-compiler-plugin") }?.default
            ?: throw IllegalStateException("Could not resolve kotlinx.serialization compiler plugin for Kotlin $kotlinVersion")

        cachedPluginJar = jar
        return jar
    }

    /**
     * Get the kotlinx.serialization-json runtime library and its dependencies.
     * Add these to your compilation classpath when using @Serializable.
     */
    suspend fun runtimeClasspath(): Set<File> {
        cachedRuntimeLibs?.let { return it }

        val libs = MavenAether.libraries(
            path = "org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion",
            fetchSources = false
        )
        val files = libs.mapNotNull { it.default }.toSet()
        cachedRuntimeLibs = files
        return files
    }

    /**
     * Get only the core serialization runtime (without JSON support).
     * Smaller dependency footprint if you only need custom serializers.
     */
    suspend fun coreRuntimeClasspath(): Set<File> {
        val libs = MavenAether.libraries(
            path = "org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion",
            fetchSources = false
        )
        return libs.mapNotNull { it.default }.toSet()
    }

    /**
     * Configure compiler arguments to enable the serialization plugin.
     *
     * Usage:
     * ```kotlin
     * val pluginJar = SerializationPlugin.pluginJar()  // in suspend context
     * kotlinJvmCompileBlocking(
     *     arguments = { SerializationPlugin.configure(this, pluginJar) },
     *     ...
     * )
     * ```
     */
    fun configure(args: K2JVMCompilerArguments, pluginJar: File) {
        val existingPlugins = args.pluginClasspaths ?: emptyArray()
        args.pluginClasspaths = existingPlugins + pluginJar.absolutePath
    }

    /**
     * Convenience function that returns a configurer for use with kotlinJvmCompileBlocking.
     *
     * Usage:
     * ```kotlin
     * val configurer = SerializationPlugin.configurer()  // in suspend context
     * kotlinJvmCompileBlocking(
     *     arguments = configurer,
     *     ...
     * )
     * ```
     */
    suspend fun configurer(): K2JVMCompilerArguments.() -> Unit {
        val jar = pluginJar()
        return { configure(this, jar) }
    }
}
