package com.ivieleague.kbuild.java

import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.tools.ToolProvider

/**
 * Compiles Java sources reactively.
 *
 * The compilation is cached based on input values (sources, classpath).
 * When any input reactive changes, the compilation will re-run.
 *
 * @param name Module name for the compilation
 * @param sourceRoots Reactive set of source root directories
 * @param classpathJars Reactive set of classpath JAR files
 * @param additionalJavaCompilerArguments Extra compiler arguments
 * @param cache Directory for cache files
 * @param outputFolder Directory for compiled class files
 * @return The output folder containing compiled classes
 */
suspend fun javaCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    cache: File,
    outputFolder: File
): File {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return withContext(Dispatchers.IO) {
        javaCompileBlocking(
            name = name,
            sourceRoots = sources,
            classpathJars = classpath,
            additionalJavaCompilerArguments = additionalJavaCompilerArguments,
            cache = cache,
            outputFolder = outputFolder
        )
    }
}

/**
 * Blocking Java compilation.
 * Use [javaCompile] for reactive usage.
 */
fun javaCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    cache: File,
    outputFolder: File
): File {
    val inputFiles = sourceRoots.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "java" }.toList()

    // If there are no Java files, just ensure output folder exists and return
    if (inputFiles.isEmpty()) {
        outputFolder.mkdirs()
        return outputFolder
    }

    val compiler = ToolProvider.getSystemJavaCompiler()!!
    cache.mkdirs()
    val sourcesListFile = cache.resolve("sources.txt").also { it.writeText(inputFiles.joinToString("\n")) }
    val classpaths = classpathJars.joinToString(File.pathSeparator)
    val args = listOf(
        "-d",
        outputFolder.path,
        "-classpath",
        classpaths
    ) + additionalJavaCompilerArguments.entries.flatMap { listOf(it.key, it.value) } + ("@$sourcesListFile")
    val result = compiler.run(null, System.out, System.err, *args.toTypedArray())
    if (result != 0) throw IllegalStateException("Java compiler failed, returned $result")
    return outputFolder
}
