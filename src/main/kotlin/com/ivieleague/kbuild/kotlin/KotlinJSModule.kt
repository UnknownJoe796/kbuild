package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Buildable
import com.ivieleague.kbuild.common.HasLibraries
import com.ivieleague.kbuild.common.HasSourceRoots
import com.ivieleague.kbuild.common.Module
import com.ivieleague.kbuild.jvm.CreatesJar
import com.ivieleague.kbuild.jvm.Jar
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.incremental.EmptyICReporter
import org.jetbrains.kotlin.incremental.IncrementalJsCompilerRunner
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import java.io.File

interface KotlinJSModule : Module, HasSourceRoots, Buildable, HasLibraries, CreatesJar {
    val kotlinBuildFolder: File get() = buildFolder.resolve("kotlin").also { it.parentFile.mkdirs() }
    val kotlinOutputFolder: File get() = root.resolve("out/kotlin")
    val kotlinClasspathOutput: File get() = kotlinOutputFolder.resolve(name)
    val kotlinJarOutput: File get() = outFolder.resolve("$name.jar")

    private val allKotlinSourceFiles get() = sourceRoots.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" }.toList()

    val kotlinArguments: K2JSCompilerArguments
        get() = K2JSCompilerArguments().also { arguments ->
            arguments.freeArgs = allKotlinSourceFiles.map { it.toString() }.toList()
            arguments.noStdlib = true
            arguments.metaInfo = true
            arguments.sourceMap = true
            arguments.moduleKind = ""
            arguments.libraries = libraries
                .filter {
                    if (it.default.extension == "jar") {
                        Jar.jarFiles(it.default).any { it.endsWith(".js") }
                    } else {
                        it.default.listFiles()?.any { it.extension == "js" } ?: false
                    }
                }
                .joinToString(File.pathSeparator) { it.default.absolutePath }
            arguments.outputFile = kotlinClasspathOutput.toString() + ".js"
        }

    override fun build(): File {
        IncrementalCompilation.setIsEnabledForJs(true)
        setIdeaIoUseFallback()
        kotlinBuildFolder.mkdirs()
        val collector = Kotlin.CompilationMessageCollector()
        val code = IncrementalJsCompilerRunner(
            workingDir = File(kotlinBuildFolder, "cache"),
            reporter = EmptyICReporter,
            buildHistoryFile = File(kotlinBuildFolder, "build-history.bin"),
            modulesApiHistory = EmptyModulesApiHistory
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
        kotlinOutputFolder
            .resolve("META-INF")
            .also { it.mkdirs() }
            .resolve("MANIFEST.MF")
            .takeIf { !it.exists() }
            ?.outputStream()?.buffered()?.use {
                Jar.defaultManifest().write(it)
            }
        Jar.create(kotlinOutputFolder, kotlinJarOutput)
        return kotlinJarOutput
    }


    val jarManifest get() = Jar.defaultManifest()

    fun manifestFile(): File {
        val file = kotlinOutputFolder
            .resolve("META-INF")
            .also { it.mkdirs() }
            .resolve("MANIFEST.MF")
        file.outputStream().buffered().use {
            jarManifest.write(it)
        }
        return file
    }

    val distributionOutput: File get() = outFolder.resolve("distribution.jar")
    override fun createJar(): File {
        build()
        manifestFile()
        val dFile = distributionOutput
        Jar.create(kotlinOutputFolder, dFile)
        return dFile
    }

//    fun dceTest() {
//        val inputFile = InputFile()
//        DeadCodeElimination.run(
//            inputFiles = listOf(),
//            rootReachableNames = setOf(kotlinArguments.main!!),
//            logConsumer =
//        )
//    }
}