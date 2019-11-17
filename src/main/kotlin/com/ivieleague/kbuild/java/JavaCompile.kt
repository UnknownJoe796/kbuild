package com.ivieleague.kbuild.java

import com.ivieleague.kbuild.common.Producer
import java.io.File
import javax.tools.ToolProvider

class JavaCompile(
    val name: String,
    val sourceRoots: Producer<File>,
    val classpathJars: Producer<File>,
    val additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    val cache: File,
    val outputFolder: File
) : () -> File {
    override fun invoke(): File {
        //TODO: Caching compilations
        val compiler = ToolProvider.getSystemJavaCompiler()!!
        val inputFiles = sourceRoots().asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "java" }
        cache.mkdirs()
        val sourcesListFile = cache.resolve("sources.txt").also { it.writeText(inputFiles.joinToString("\n")) }
        val classpaths = classpathJars().joinToString(File.pathSeparator)
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
}