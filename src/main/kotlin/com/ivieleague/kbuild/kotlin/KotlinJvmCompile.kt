package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporterImpl
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.ChangedFiles
import org.jetbrains.kotlin.incremental.ClasspathChanges
import org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunner
import org.jetbrains.kotlin.incremental.classpathAsList
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

/**
 * Blocking incremental Kotlin/JVM compilation.
 * Use [kotlinJvmCompile] for reactive usage.
 *
 * This function tracks source file modifications and uses the Kotlin incremental compiler
 * with Known changed files to enable true incremental compilation without Gradle.
 */
fun kotlinJvmCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File,
    enableContextParameters: Boolean = false
): File {
    val allKotlinSourceFiles = sourceRoots.asSequence().flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" || it.extension == "java" }.toList()

    IncrementalCompilation.setIsEnabledForJvm(true)
    setIdeaIoUseFallback()
    cache.mkdirs()

    // Track file changes to enable incremental compilation
    val tracker = SourceFileTracker.forCache(cache)
    val changes = tracker.computeChanges(allKotlinSourceFiles)

    // If no changes and output already exists, skip compilation entirely
    if (!changes.isFirstBuild && changes.isEmpty) {
        val hasOutput = outputFolder.exists() && outputFolder.walkTopDown().any { it.extension == "class" }
        if (hasOutput) {
            if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
                println("No source file changes detected, skipping compilation")
            }
            return outputFolder
        }
    }

    // Determine changed files for the incremental compiler
    val changedFiles = if (changes.isFirstBuild) {
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
            println("First build - full compilation")
        }
        // First build: use ToBeComputed so compiler can establish baseline
        ChangedFiles.DeterminableFiles.ToBeComputed
    } else {
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
            println("Incremental: ${changes.modified.size} modified, ${changes.removed.size} removed")
        }
        // Incremental build: tell compiler exactly which source files changed
        ChangedFiles.DeterminableFiles.Known(
            modified = changes.modified,
            removed = changes.removed
        )
    }

    // Create classpath changes with snapshotting for true incremental compilation
    val classpathSnapshotManager = ClasspathSnapshotManager.forCache(cache)
    val classpathChanges = classpathSnapshotManager.createClasspathChanges(
        classpathJars = classpathJars,
        isFirstBuild = changes.isFirstBuild
    )

    val collector = Kotlin.CompilationMessageCollector()

    val code = IncrementalJvmCompilerRunner(
        workingDir = cache,
        reporter = BuildReporter(object : ICReporter {
            override fun report(message: () -> String, severity: ICReporter.ReportSeverity) {
                if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                    println("$severity: ${message()}")
                }
            }

            override fun reportCompileIteration(
                incremental: Boolean,
                sourceFiles: Collection<File>,
                exitCode: ExitCode
            ) {
                if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
                    println("Compile iteration: incremental=$incremental, files=${sourceFiles.size}, exit=$exitCode")
                }
            }

            override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {
                if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                    println("reportMarkDirty: $affectedFiles; $reason")
                }
            }

            override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {
                if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                    println("reportMarkDirtyClass: $affectedFiles; $classFqName")
                }
            }

            override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {
                if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                    println("reportMarkDirtyMember: $affectedFiles; $scope; $name")
                }
            }
        }, BuildMetricsReporterImpl()),
        outputDirs = listOf(outputFolder, cache),
        // Use classpath snapshotting for true incremental compilation
        classpathChanges = classpathChanges,
        // Include both Kotlin and Java source files for mixed compilation
        kotlinSourceFilesExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS + setOf("java")
    ).compile(
        allSourceFiles = allKotlinSourceFiles,
        args = K2JVMCompilerArguments().also {
            it.moduleName = name
            it.classpathAsList = classpathJars.toList()
            it.destination = outputFolder.toString()
            it.noStdlib = true  // Stdlib is on classpath already
            if (enableContextParameters) {
                it.contextParameters = true
            }
            it.arguments()
        },
        messageCollector = collector,
        changedFiles = changedFiles
    )

    for (message in collector.messages) {
        if (message.severity <= CompilerMessageSeverity.WARNING) {
            println("${message.message} at ${message.location}")
        }
    }
    if (code != ExitCode.OK) {
        throw Kotlin.CompilationException(collector.messages)
    }
    return outputFolder
}

/**
 * Blocking non-incremental Kotlin/JVM compilation.
 * Use [kotlinJvmCompileNonIncremental] for reactive usage.
 */
fun kotlinJvmCompileNonIncrementalBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    outputFolder: File
): File {
    val allKotlinSourceFiles = sourceRoots.asSequence().flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" || it.extension == "java" }.toList()
    val collector = Kotlin.CompilationMessageCollector()
    val code = K2JVMCompiler().exec(
        messageCollector = collector,
        services = Services.EMPTY,
        arguments = K2JVMCompilerArguments().also {
            it.moduleName = name
            it.classpathAsList = classpathJars.toList()
            it.freeArgs = allKotlinSourceFiles.map { it.absolutePath }
            it.noStdlib = true
            it.destination = outputFolder.toString()
            it.arguments()
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
    return outputFolder
}
