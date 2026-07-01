package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import java.io.File

/**
 * Compiles Kotlin/JVM sources incrementally.
 *
 * Reads [sourceRoots] via `invoke()` so that, inside a reactive scope (e.g. `reactiveSuspending {}`),
 * the source watch is registered as a dependency and the compile re-runs when sources change; called
 * without an active scope it simply reads the current value and compiles once. [classpathJars] is a
 * resolved, static input and is used directly.
 *
 * Compilation runs out-of-process in the Kotlin daemon (see [DaemonJvmCompile]) alongside the JS and
 * metadata daemon compiles and the native konanc subprocesses. Source changes are detected with
 * [SourceFileTracker]; classpath ABI snapshots are produced in-process (the only remaining in-process
 * compiler step, self-guarded by [ClasspathSnapshotManager]) and handed to the daemon, which computes
 * the dirty set and manages stale outputs internally.
 *
 * @param name Module name for the compilation
 * @param sourceRoots Reactive set of source root directories
 * @param classpathJars Resolved set of classpath JAR files
 * @param arguments Optional compiler arguments configuration
 * @param cache Directory for incremental compilation cache
 * @param outputFolder Directory for compiled class files
 * @return The output folder containing compiled classes
 */
suspend fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File,
    enableContextParameters: Boolean = false
): File {
    val sources = sourceRoots()

    return withContext(Dispatchers.IO) {
        val sourceFiles = collectSourceFiles(sources)
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
            return@withContext outputFolder
        }

        if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
            if (changes.isFirstBuild) println("First build - full compilation")
            else println("Incremental: ${changes.modified.size} modified, ${changes.removed.size} removed")
        }

        // Classpath snapshotting is the only remaining in-process compiler operation; it self-guards
        // (see ClasspathSnapshotManager) so concurrent JVM targets don't snapshot at once. Cached and
        // fast, so this serialization costs little.
        val snapshotManager = ClasspathSnapshotManager.forCache(cache)
        val dependencySnapshots = snapshotManager.snapshotFiles(classpathJars)

        // Use canonical paths everywhere: the incremental runner canonicalizes its working/output
        // directories before checking that OUTPUT_DIRS contains them, so the paths we pass must match.
        val workingDir = cache.canonicalFile
        val classesDir = outputFolder.canonicalFile

        val errors = DaemonJvmCompile.compile(
            mapOf(
                "sources" to sourceFiles.map { it.absolutePath },
                "output" to classesDir.absolutePath,
                "args" to compilerArgStrings(name, classpathJars, enableContextParameters, arguments),
                "daemonRunDir" to DaemonJvmCompile.daemonRunDir.absolutePath,
                "debug" to (Settings.outputLevel <= Settings.OutputLevel.Debug),
                "workingDir" to workingDir.absolutePath,
                "dependencySnapshots" to dependencySnapshots.map { it.absolutePath },
                "shrunkSnapshot" to snapshotManager.shrunkSnapshotFile.absolutePath,
                "outputDirs" to listOf(classesDir.absolutePath, workingDir.absolutePath)
            )
        )
        failIfErrors(errors)
        outputFolder
    }
}

/**
 * Non-incremental Kotlin/JVM compilation (one-shot, no caching).
 *
 * See [kotlinJvmCompile] for the reactive-vs-one-shot semantics of [sourceRoots].
 */
suspend fun kotlinJvmCompileNonIncremental(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    outputFolder: File
): File {
    val sources = sourceRoots()

    return withContext(Dispatchers.IO) {
        val sourceFiles = collectSourceFiles(sources)
        outputFolder.mkdirs()

        val errors = DaemonJvmCompile.compile(
            mapOf(
                "sources" to sourceFiles.map { it.absolutePath },
                "output" to outputFolder.canonicalFile.absolutePath,
                "args" to compilerArgStrings(name, classpathJars, false, arguments),
                "daemonRunDir" to DaemonJvmCompile.daemonRunDir.absolutePath,
                "debug" to (Settings.outputLevel <= Settings.OutputLevel.Debug)
            )
        )
        failIfErrors(errors)
        outputFolder
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
 * Renders the caller-provided compiler configuration to argument strings.
 *
 * Arguments are expressed through the familiar [K2JVMCompilerArguments] surface and then forwarded
 * to the daemon as argument strings, so existing configurers keep working. The strings cross the
 * isolated-classloader boundary into [DaemonJvmCompile] unchanged.
 */
private fun compilerArgStrings(
    name: String,
    classpathJars: Set<File>,
    enableContextParameters: Boolean,
    arguments: Configurer<K2JVMCompilerArguments>
): List<String> {
    val args = K2JVMCompilerArguments().also {
        it.moduleName = name
        it.classpath = classpathJars.joinToString(File.pathSeparator) { jar -> jar.absolutePath }
        it.noStdlib = true  // Stdlib is on the classpath already
        if (enableContextParameters) it.contextParameters = true
        arguments(it)
    }
    return ArgumentUtils.convertArgumentsToStringListNoDefaults(args)
}

/** Maps daemon error messages to a [Kotlin.CompilationException]. */
private fun failIfErrors(errors: List<String>) {
    if (errors.isNotEmpty()) {
        throw Kotlin.CompilationException(errors.map { Kotlin.CompilationMessage(CompilerMessageSeverity.ERROR, it) })
    }
}
