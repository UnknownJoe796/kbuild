package com.example.build

import com.ivieleague.kbuild.kmp.*
import java.io.File

/**
 * Sample Kotlin Multiplatform project build configuration.
 *
 * This demonstrates:
 * - Building for JVM, JS, and Native targets
 * - Sharing common code across platforms
 * - Platform-specific implementations using expect/actual
 *
 * Project structure:
 * ```
 * src/
 *   commonMain/kotlin/  - Shared code
 *   jvmMain/kotlin/     - JVM-specific code
 *   jsMain/kotlin/      - JavaScript-specific code
 *   nativeMain/kotlin/  - Native-specific code
 * ```
 */
object HelloKmpBuild {
    val projectRoot = File("samples/hello-kmp")

    val project = kmpProject("hello-kmp", projectRoot) {
        // Enable targets
        jvm()
        js()
        nativeHost() // Compile for current platform
    }

    fun buildAll() {
        println("Building Kotlin Multiplatform project...")
        project.printSummary()
        println()

        val results = project.buildAll()

        println("\nBuild Results:")
        for ((target, output) in results) {
            println("  ${target.name}: $output")
        }
    }

    fun buildJvm() {
        println("Building JVM target...")
        val output = project.buildJvm()
        println("JVM output: $output")
    }

    fun buildJs() {
        println("Building JS target...")
        val output = project.buildJs()
        println("JS output: $output")
    }

    fun buildNative() {
        println("Building Native target for host platform...")
        val target = KmpTarget.Native.host()
        val output = project.buildNative(target)
        println("Native output: $output")
    }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "jvm" -> HelloKmpBuild.buildJvm()
        "js" -> HelloKmpBuild.buildJs()
        "native" -> HelloKmpBuild.buildNative()
        else -> HelloKmpBuild.buildAll()
    }
}
