package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
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
import java.util.Properties

/**
 * Tracks source file modification times to enable proper incremental compilation.
 */
private object FileChangeTracker {
    /**
     * Calculate which files changed since the last build.
     * @param sourceFiles Current list of source files
     * @param cacheDir Directory to store timestamp tracking data
     * @return ChangedFiles.Known if we can determine changes, ChangedFiles.Unknown for first build
     */
    fun getChangedFiles(sourceFiles: List<File>, cacheDir: File): ChangedFiles {
        // Store timestamps in a sibling directory to avoid being cleaned by incremental compiler
        val timestampDir = cacheDir.parentFile.resolve("${cacheDir.name}-timestamps")
        val timestampFile = timestampDir.resolve("source-timestamps.properties")
        val previousTimestamps = loadTimestamps(timestampFile)

        // First build - no previous state
        if (previousTimestamps.isEmpty()) {
            saveTimestamps(timestampFile, sourceFiles)
            return ChangedFiles.Unknown
        }

        // Build maps for O(1) lookup - use absolutePath as key to avoid repeated conversions
        val currentFileMap = sourceFiles.associateBy { it.absolutePath }
        val currentPaths = currentFileMap.keys  // This is already a Set (O(1) contains)
        val previousPaths = previousTimestamps.keys  // Also a Set

        // Single pass through previous timestamps to find modified and removed
        val modified = mutableListOf<File>()
        val removed = mutableListOf<File>()
        for ((path, lastModified) in previousTimestamps) {
            val file = currentFileMap[path]
            if (file == null) {
                // File was removed
                removed.add(File(path))
            } else if (file.lastModified() != lastModified) {
                // File was modified
                modified.add(file)
            }
        }

        // Find new files (in current but not in previous) - O(n) with Set lookup
        for (path in currentPaths) {
            if (path !in previousPaths) {
                modified.add(File(path))
            }
        }

        // Save current state for next build
        saveTimestamps(timestampFile, sourceFiles)

        return ChangedFiles.DeterminableFiles.Known(modified, removed)
    }

    private fun loadTimestamps(file: File): Map<String, Long> {
        if (!file.exists()) return emptyMap()
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.entries.associate { (k, v) -> k.toString() to v.toString().toLong() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveTimestamps(file: File, sourceFiles: List<File>) {
        try {
            file.parentFile?.mkdirs()
            val props = Properties()
            for (f in sourceFiles) {
                props.setProperty(f.absolutePath, f.lastModified().toString())
            }
            file.outputStream().use { props.store(it, "Source file timestamps for incremental compilation") }
        } catch (e: Exception) {
            // Ignore write failures - worst case we'll do a full rebuild
        }
    }
}

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
context(ctx: ReactiveContext)
fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    cache: File,
    outputFolder: File
): File {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return async(name, sources, classpath, outputFolder) {
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
context(ctx: ReactiveContext)
fun kotlinJvmCompileNonIncremental(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    outputFolder: File
): File {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return async(name, sources, classpath, outputFolder) {
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

    // Check if we can skip compilation entirely (no changes and output exists)
    val changedFiles = FileChangeTracker.getChangedFiles(allKotlinSourceFiles, cache)
    if (changedFiles is ChangedFiles.DeterminableFiles.Known &&
        changedFiles.modified.isEmpty() &&
        changedFiles.removed.isEmpty() &&
        outputFolder.exists() &&
        outputFolder.walkTopDown().any { it.extension == "class" }) {
        println("No source changes detected, skipping compilation")
        return outputFolder
    }

    IncrementalCompilation.setIsEnabledForJvm(true)
    setIdeaIoUseFallback()
    cache.mkdirs()
    val collector = Kotlin.CompilationMessageCollector()
    val code = IncrementalJvmCompilerRunner(
        workingDir = cache,
        reporter = BuildReporter(object : ICReporter {
            override fun report(message: () -> String, severity: ICReporter.ReportSeverity) {
                println("$severity: ${message()}")
            }

            override fun reportCompileIteration(
                incremental: Boolean,
                sourceFiles: Collection<File>,
                exitCode: ExitCode
            ) {
                println("Iteration complete.  Incremental: $incremental, Source: ${sourceFiles.joinToString()}, Exit: $exitCode")
            }

            override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {
                println("reportMarkDirty: $affectedFiles; $reason")
            }

            override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {
                println("reportMarkDirtyClass: $affectedFiles; $classFqName")
            }

            override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {
                println("reportMarkDirtyMember: $affectedFiles; $scope; $name")
            }
        }, BuildMetricsReporterImpl()),
        outputDirs = listOf(outputFolder, cache),
        classpathChanges = ClasspathChanges.ClasspathSnapshotDisabled,
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
        changedFiles = changedFiles  // Already computed above
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
