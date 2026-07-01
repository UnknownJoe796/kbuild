package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.java.javaCompile
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File

/**
 * Compiles Kotlin and Java sources together.
 *
 * First compiles Kotlin, then Java with Kotlin classes on the classpath. Reads [sourceRoots] via
 * `invoke()` so it participates in reactive dependency tracking when called inside a reactive scope.
 * [classpathJars] is a resolved, static input.
 *
 * @param name Module name
 * @param sourceRoots Reactive set of source root directories
 * @param classpathJars Resolved set of classpath JAR files
 * @param arguments Kotlin compiler arguments
 * @param additionalJavaCompilerArguments Extra Java compiler arguments
 * @param cache Directory for caching
 * @param outputFolder Directory for compiled class files
 * @return Set containing both Kotlin and Java output folders
 */
suspend fun kotlinWithJavaCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Set<File>,
    arguments: Configurer<K2JVMCompilerArguments> = {},
    additionalJavaCompilerArguments: Map<String, String> = mapOf(),
    cache: File,
    outputFolder: File
): Set<File> {
    val sources = sourceRoots()

    // First compile Kotlin (non-incremental to ensure clean state for Java)
    val kotlinOutput = kotlinJvmCompileNonIncremental(
        name = name,
        sourceRoots = Constant(sources),
        classpathJars = classpathJars + sources,
        arguments = arguments,
        outputFolder = outputFolder.resolve("kotlin")
    )

    // Then compile Java with Kotlin classes on classpath
    val javaOutput = javaCompile(
        name = name,
        sourceRoots = Constant(sources),
        classpathJars = classpathJars + kotlinOutput,
        additionalJavaCompilerArguments = additionalJavaCompilerArguments,
        cache = cache.resolve("java"),
        outputFolder = outputFolder.resolve("java")
    )

    return setOf(javaOutput, kotlinOutput)
}
