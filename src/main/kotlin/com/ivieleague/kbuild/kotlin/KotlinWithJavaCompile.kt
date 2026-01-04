package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.asReactive
import com.ivieleague.kbuild.java.javaCompileBlocking
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File

/**
 * Compiles Kotlin and Java sources together reactively.
 *
 * First compiles Kotlin, then Java with Kotlin classes on the classpath.
 * The compilation is cached based on input values.
 *
 * @param name Module name
 * @param sourceRoots Reactive set of source root directories
 * @param classpathJars Reactive set of classpath JAR files
 * @param arguments Kotlin compiler arguments
 * @param additionalJavaCompilerArguments Extra Java compiler arguments
 * @param cache Directory for caching
 * @param outputFolder Directory for compiled class files
 * @return Set containing both Kotlin and Java output folders
 */
suspend fun kotlinWithJavaCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    cache: File,
    outputFolder: File
): Set<File> {
    val sources = sourceRoots()
    val classpath = classpathJars()

    return withContext(Dispatchers.IO) {
        kotlinWithJavaCompileBlocking(
            name = name,
            sourceRoots = sources,
            classpathJars = classpath,
            arguments = arguments,
            additionalJavaCompilerArguments = additionalJavaCompilerArguments,
            cache = cache,
            outputFolder = outputFolder
        )
    }
}

/**
 * Blocking Kotlin + Java compilation.
 * Use [kotlinWithJavaCompile] for reactive usage.
 */
fun kotlinWithJavaCompileBlocking(
    name: String,
    sourceRoots: Set<File>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    cache: File,
    outputFolder: File
): Set<File> {
    // First compile Kotlin (non-incremental to ensure clean state for Java)
    val kotlinOutput = kotlinJvmCompileNonIncrementalBlocking(
        name = name,
        sourceRoots = sourceRoots,
        classpathJars = classpathJars + sourceRoots,
        arguments = arguments,
        outputFolder = outputFolder.resolve("kotlin")
    )

    // Then compile Java with Kotlin classes on classpath
    val javaOutput = javaCompileBlocking(
        name = name,
        sourceRoots = sourceRoots,
        classpathJars = classpathJars + kotlinOutput,
        additionalJavaCompilerArguments = additionalJavaCompilerArguments,
        cache = cache.resolve("java"),
        outputFolder = outputFolder.resolve("java")
    )

    return setOf(javaOutput, kotlinOutput)
}
