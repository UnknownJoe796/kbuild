package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import java.io.File

/**
 * Compiles Kotlin/JVM sources reactively.
 *
 * The compilation is cached based on input values (sources, classpath).
 * When any input reactive changes, the compilation will re-run.
 *
 * @param name Module name for the compilation
 * @param sourceRoots Reactive set of source root directories
 * @param classpathJars Reactive set of classpath JAR files
 * @param arguments Optional compiler arguments configuration
 * @param cache Directory for incremental compilation cache
 * @param outputFolder Directory for compiled class files
 * @return The output folder containing compiled classes
 */
suspend fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File
): File {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return withContext(Dispatchers.IO) {
        kotlinJvmCompileBlocking(
            name = name,
            sourceRoots = sources,
            classpathJars = classpath,
            arguments = arguments,
            cache = cache,
            outputFolder = outputFolder
        )
    }
}

/**
 * Non-incremental Kotlin/JVM compilation (one-shot, no caching).
 */
suspend fun kotlinJvmCompileNonIncremental(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    outputFolder: File
): File {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return withContext(Dispatchers.IO) {
        kotlinJvmCompileNonIncrementalBlocking(
            name = name,
            sourceRoots = sources,
            classpathJars = classpath,
            arguments = arguments,
            outputFolder = outputFolder
        )
    }
}

private fun collectSourceFiles(sourceRoots: Set<File>): List<File> =
    sourceRoots.asSequence().flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" || it.extension == "java" }
        // Canonical paths keep sources consistent with the canonical output/working directories,
        // which the incremental compiler relies on when mapping sources to their outputs.
        .map { it.canonicalFile }
        .toList()

/**
 * Builds a [JvmCompilationOperation] for the given sources and applies caller-provided
 * compiler arguments.
 *
 * Arguments are expressed through the familiar [K2JVMCompilerArguments] surface and then
 * forwarded to the Build Tools API as argument strings, so existing configurers keep working.
 */
@OptIn(ExperimentalBuildToolsApi::class)
private fun compilationOperationBuilder(
    name: String,
    sourceFiles: List<File>,
    classpathJars: Set<File>,
    outputFolder: File,
    enableContextParameters: Boolean,
    arguments: Configurer<K2JVMCompilerArguments>
): JvmCompilationOperation.Builder {
    val args = K2JVMCompilerArguments().also {
        it.moduleName = name
        it.classpath = classpathJars.joinToString(File.pathSeparator) { jar -> jar.absolutePath }
        it.noStdlib = true  // Stdlib is on the classpath already
        if (enableContextParameters) it.contextParameters = true
        arguments(it)
    }
    val builder = BuildToolsApi.jvm.jvmCompilationOperationBuilder(
        sourceFiles.map { it.toPath() },
        outputFolder.toPath()
    )
    builder.compilerArguments.applyArgumentStrings(ArgumentUtils.convertArgumentsToStringListNoDefaults(args))
    return builder
}

/**
 * Runs a built compilation operation and maps a non-success result to a [Kotlin.CompilationException].
 */
@OptIn(ExperimentalBuildToolsApi::class)
private fun runCompilation(operation: JvmCompilationOperation, outputFolder: File): File {
    val logger = BtaMessageLogger()
    val result = BuildToolsApi.toolchains.createBuildSession().use { session ->
        session.executeOperation(operation, BuildToolsApi.toolchains.createInProcessExecutionPolicy(), logger)
    }
    if (result != CompilationResult.COMPILATION_SUCCESS) {
        throw Kotlin.CompilationException(logger.messages)
    }
    return outputFolder
}

/**
 * Blocking incremental Kotlin/JVM compilation.
 * Use [kotlinJvmCompile] for reactive usage.
 *
 * Source changes are detected with [SourceFileTracker] and handed to the Build Tools API's
 * snapshot-based incremental compilation, which computes the dirty set and manages stale
 * outputs internally.
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun kotlinJvmCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File,
    enableContextParameters: Boolean = false
): File {
    val sourceFiles = collectSourceFiles(sourceRoots)
    cache.mkdirs()
    outputFolder.mkdirs()

    val tracker = SourceFileTracker.forCache(cache)
    val changes = tracker.computeChanges(sourceFiles)

    // If no changes and output already exists, skip compilation entirely
    if (!changes.isFirstBuild && changes.isEmpty &&
        outputFolder.walkTopDown().any { it.extension == "class" }
    ) {
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
            println("No source file changes detected, skipping compilation")
        }
        return outputFolder
    }

    if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
        if (changes.isFirstBuild) println("First build - full compilation")
        else println("Incremental: ${changes.modified.size} modified, ${changes.removed.size} removed")
    }

    val snapshotManager = ClasspathSnapshotManager.forCache(cache)
    val dependencySnapshots = snapshotManager.snapshotFiles(classpathJars).map { it.toPath() }

    // Use canonical paths everywhere: the incremental runner canonicalizes its working/output
    // directories before checking that OUTPUT_DIRS contains them, so the paths we pass must match.
    val workingDir = cache.canonicalFile
    val classesDir = outputFolder.canonicalFile
    val builder = compilationOperationBuilder(name, sourceFiles, classpathJars, classesDir, enableContextParameters, arguments)

    val icConfig = builder.snapshotBasedIcConfigurationBuilder(
        workingDir.toPath(),
        // Let the Build Tools API compute the dirty set from its own source snapshots; this also
        // makes it manage removal of stale outputs for changed/removed files.
        SourcesChanges.ToBeCalculated,
        dependencySnapshots,
        snapshotManager.shrunkSnapshotFile.toPath()
    ).also {
        // The incremental runner requires both the destination and its working directory here.
        it.set(JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS, setOf(classesDir.toPath(), workingDir.toPath()))
        // Precise backup removes (and restores on failure) the outputs of changed source files,
        // preventing stale class files from colliding with freshly compiled ones.
        it.set(JvmSnapshotBasedIncrementalCompilationConfiguration.BACKUP_CLASSES, true)
    }.build()
    builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icConfig)

    return runCompilation(builder.build(), outputFolder)
}

/**
 * Blocking non-incremental Kotlin/JVM compilation.
 * Use [kotlinJvmCompileNonIncremental] for reactive usage.
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun kotlinJvmCompileNonIncrementalBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    outputFolder: File
): File {
    val sourceFiles = collectSourceFiles(sourceRoots)
    outputFolder.mkdirs()
    val builder = compilationOperationBuilder(name, sourceFiles, classpathJars, outputFolder, false, arguments)
    return runCompilation(builder.build(), outputFolder)
}
