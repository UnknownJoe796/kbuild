package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.HasSourceRoots
import com.ivieleague.kbuild.jvm.JvmModule
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.incremental.EmptyICReporter
import org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunner
import org.jetbrains.kotlin.incremental.classpathAsList
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import java.io.File

interface KotlinJVMModule : KotlinModule, JvmModule, HasSourceRoots {
    val kotlinBuildFolder: File get() = buildFolder.resolve("kotlin").also { it.parentFile.mkdirs() }

    private val allKotlinSourceFiles get() = sourceRoots.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" }.toList()

    val kotlinArguments: K2JVMCompilerArguments
        get() = K2JVMCompilerArguments().also {
            it.moduleName = this.name
            it.classpathAsList = libraries.map { it.default }
            it.freeArgs = allKotlinSourceFiles.map { it.toString() }.toList()
            it.noStdlib = true
            it.destination = classpathOutput.toString()
    }

    override fun build(): File {
        IncrementalCompilation.setIsEnabledForJvm(true)
        setIdeaIoUseFallback()
        kotlinBuildFolder.mkdirs()
        val collector = Kotlin.CompilationMessageCollector()
        val code = IncrementalJvmCompilerRunner(
            workingDir = File(kotlinBuildFolder, "cache"),
            reporter = EmptyICReporter,
            usePreciseJavaTracking = true,
            outputFiles = emptyList(),
            buildHistoryFile = File(kotlinBuildFolder, "build-history.bin"),
            modulesApiHistory = EmptyModulesApiHistory,
            kotlinSourceFilesExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
        ).compile(
            allSourceFiles = allKotlinSourceFiles.toList(),
            args = kotlinArguments,
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
        return classpathOutput
    }

    //TODO: Potential extension: call Java compiler afterwards to handle .java files
}