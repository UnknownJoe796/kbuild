package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.Producer
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.EmptyICReporter
import org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunner
import org.jetbrains.kotlin.incremental.classpathAsList
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import java.io.File

class KotlinJvmCompile(
    val name: String,
    val sourceRoots: Producer<File>,
    val classpathJars: Producer<File>,
    val arguments: Configurer<K2JVMCompilerArguments> = {},
    val cache: File,
    val outputFolder: File
) : () -> File {
    override operator fun invoke(): File {
        val allKotlinSourceFiles = sourceRoots().asSequence().flatMap { it.walkTopDown() }
            .filter { it.extension == "kt" || it.extension == "java" }.toList()
        IncrementalCompilation.setIsEnabledForJvm(true)
        setIdeaIoUseFallback()
        cache.mkdirs()
        val collector = Kotlin.CompilationMessageCollector()
        val code = IncrementalJvmCompilerRunner(
            workingDir = cache,
            reporter = EmptyICReporter,
            usePreciseJavaTracking = true,
            outputFiles = emptyList(),
            buildHistoryFile = cache.resolve("build-history.bin"),
            modulesApiHistory = EmptyModulesApiHistory,
            kotlinSourceFilesExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
        ).compile(
            allSourceFiles = allKotlinSourceFiles,
            args = K2JVMCompilerArguments().also {
                it.moduleName = name
                it.classpathAsList = classpathJars().toList()
//                it.freeArgs = allKotlinSourceFiles.map { it.absolutePath }
                it.noStdlib = true
                it.destination = outputFolder.toString()
                it.arguments()
            },
            messageCollector = collector,
            providedChangedFiles = null
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

    fun nonIncremental(): File {
        val allKotlinSourceFiles = sourceRoots().asSequence().flatMap { it.walkTopDown() }
            .filter { it.extension == "kt" || it.extension == "java" }.toList()
        val collector = Kotlin.CompilationMessageCollector()
        val code = K2JVMCompiler().exec(
            messageCollector = collector,
            services = Services.EMPTY,
            arguments = K2JVMCompilerArguments().also {
                it.moduleName = name
                it.classpathAsList = classpathJars().toList()
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
}