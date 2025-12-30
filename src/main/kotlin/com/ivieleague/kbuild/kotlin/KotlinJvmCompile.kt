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
    outputFolder: File
): File {
    val allKotlinSourceFiles = sourceRoots.asSequence().flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" || it.extension == "java" }.toList()
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
        kotlinSourceFilesExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
    ).compile(
        allSourceFiles = allKotlinSourceFiles,
        args = K2JVMCompilerArguments().also {
            it.moduleName = name
            it.classpathAsList = classpathJars.toList()
            it.destination = outputFolder.toString()
            it.arguments()
        },
        messageCollector = collector,
        changedFiles = ChangedFiles.Unknown
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

// Legacy class-based API for backwards compatibility
@Deprecated("Use kotlinJvmCompile function with ReactiveContext instead")
class KotlinJvmCompile(
    val name: String,
    val sourceRoots: () -> Set<File>,
    val classpathJars: () -> Set<File>,
    val arguments: Configurer<K2JVMCompilerArguments> = {},
    val cache: File,
    val outputFolder: File
) : () -> File {
    override operator fun invoke(): File = kotlinJvmCompileBlocking(
        name = name,
        sourceRoots = sourceRoots(),
        classpathJars = classpathJars(),
        arguments = arguments,
        cache = cache,
        outputFolder = outputFolder
    )

    fun nonIncremental(): File = kotlinJvmCompileNonIncrementalBlocking(
        name = name,
        sourceRoots = sourceRoots(),
        classpathJars = classpathJars(),
        arguments = arguments,
        outputFolder = outputFolder
    )
}
