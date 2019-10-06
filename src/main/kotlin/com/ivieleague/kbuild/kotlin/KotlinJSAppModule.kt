package com.ivieleague.kbuild.kotlin

import org.jetbrains.kotlin.js.dce.DCELogLevel
import org.jetbrains.kotlin.js.dce.DeadCodeElimination
import org.jetbrains.kotlin.js.dce.InputFile
import org.jetbrains.kotlin.js.dce.InputResource
import java.io.File
import java.util.zip.ZipFile

interface KotlinJSAppModule : KotlinJSModule {
    fun dceTest() {
        DeadCodeElimination.run(
            inputFiles = collectInputFilesFromDirectory(this.root, this.kotlinOutputFolder),
            rootReachableNames = setOf(kotlinArguments.main!!),
            logConsumer = { level, string ->
                when (level) {
                    DCELogLevel.INFO -> {
                    }
                    DCELogLevel.WARN -> println("DCE Warning: $string")
                    DCELogLevel.ERROR -> System.err.println("DCE Error: $string")
                }
            }
        )
    }
}

private fun collectInputFiles(baseDir: File, file: File): List<InputFile> {
    val fileName = file.name
    return when {
        file.isDirectory -> {
            collectInputFilesFromDirectory(baseDir, file)
        }
        file.isFile -> {
            when {
                fileName.endsWith(".js") -> {
                    listOf(singleInputFile(baseDir, file))
                }
                fileName.endsWith(".zip") || fileName.endsWith(".jar") -> {
                    collectInputFilesFromZip(baseDir, file)
                }
                else -> {
                    println("Invalid file name '${file.absolutePath}'; must end either with '.js', '.zip' or '.jar'")
                    emptyList()
                }
            }
        }
        else -> {
            println("Source file or directory not found: $fileName")
            emptyList()
        }
    }
}

private fun singleInputFile(baseDir: File, file: File): InputFile {
    val moduleName = getModuleNameFromPath(file.toString())
    val pathToSourceMapCandidate = File(file.path + ".map")
    val pathToSourceMap = if (pathToSourceMapCandidate.exists()) pathToSourceMapCandidate else null
    return InputFile(
        InputResource.file(file.path), pathToSourceMap?.let { InputResource.file(it.path) },
        File(baseDir, "$moduleName.js").absolutePath, moduleName
    )
}

private fun collectInputFilesFromZip(baseDir: File, file: File): List<InputFile> {
    return ZipFile(file).use { zipFile ->
        zipFile.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { it.name.endsWith(".js") }
            .filter { zipFile.getEntry(it.name.metaJs()) != null }
            .distinctBy { it.name }
            .map { entry ->
                val moduleName = getModuleNameFromPath(entry.name)
                val pathToSourceMapCandidate = "${entry.name}.map"
                val pathToSourceMap =
                    if (zipFile.getEntry(pathToSourceMapCandidate) != null) pathToSourceMapCandidate else null
                InputFile(
                    InputResource.zipFile(file.path, entry.name),
                    pathToSourceMap?.let { InputResource.zipFile(file.path, it) },
                    File(baseDir, "$moduleName.js").absolutePath,
                    moduleName
                )
            }
            .toList()
    }
}

private fun collectInputFilesFromDirectory(baseDir: File, file: File): List<InputFile> {
    return file.walkTopDown().asSequence()
        .filter { !it.isDirectory }
        .filter { it.name.endsWith(".js") }
        .filter { File(it.path.metaJs()).exists() }
        .map { entry ->
            val moduleName = getModuleNameFromPath(entry.name)
            val pathToSourceMapCandidate = "${entry.path}.map"
            val pathToSourceMap = if (File(pathToSourceMapCandidate).exists()) pathToSourceMapCandidate else null
            InputFile(
                InputResource.file(entry.path), pathToSourceMap?.let { InputResource.file(it) },
                File(baseDir, "$moduleName.js").absolutePath, moduleName
            )
        }
        .toList()
}

private fun String.metaJs() = removeSuffix(".js") + ".meta.js"

private fun getModuleNameFromPath(path: String): String {
    val dotIndex = path.lastIndexOf('.')
    val slashIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return path.substring(slashIndex + 1, if (dotIndex < 0) path.length else dotIndex)
}