package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.Producer
import com.ivieleague.kbuild.common.plus
import com.ivieleague.kbuild.java.JavaCompile
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File

class KotlinWithJavaCompile(
    val name: String,
    val sourceRoots: Producer<File>,
    val classpathJars: Producer<File>,
    val arguments: Configurer<K2JVMCompilerArguments> = {},
    val additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    val cache: File,
    val outputFolder: File
) : Producer<File> {
    val kotlin = KotlinJvmCompile(
        name = name,
        sourceRoots = sourceRoots,
        classpathJars = classpathJars + sourceRoots,
        arguments = arguments,
        cache = cache.resolve("kotlin"),
        outputFolder = outputFolder.resolve("kotlin")
    )
    val java = JavaCompile(
        name = name,
        sourceRoots = sourceRoots,
        classpathJars = classpathJars + { setOf(kotlin.nonIncremental()) },
        additionalJavaCompilerArguments = additionalJavaCompilerArguments,
        cache = cache.resolve("java"),
        outputFolder = outputFolder.resolve("java")
    )

    override fun invoke(): Set<File> = setOf(java(), kotlin.outputFolder)
}