package com.ivieleague.kbuild.kotlin

import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.processing.KSPJsConfig
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPNativeConfig
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
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
context(ctx: ReactiveContext)
fun kspJvmProcess(
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

    return async("ksp-jvm-$name", sources, classpath, kotlinOutputDir) {
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
context(ctx: ReactiveContext)
fun kspJsProcess(
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

    return async("ksp-js-$name", sources, libs, kotlinOutputDir) {
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
context(ctx: ReactiveContext)
fun kspNativeProcess(
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

    return async("ksp-native-$name", sources, libs, kotlinOutputDir) {
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
