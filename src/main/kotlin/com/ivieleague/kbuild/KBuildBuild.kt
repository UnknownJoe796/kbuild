package com.ivieleague.kbuild

import com.ivieleague.kbuild.jvm.jarBuildBlocking
import com.ivieleague.kbuild.junit.junitRunBlocking
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.maven.MavenAether
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * Self-hosting build definition for KBuild.
 *
 * This allows KBuild to build itself using its own APIs.
 *
 * Usage:
 *   ./run-kbuild.sh Build.compile     # Compile sources
 *   ./run-kbuild.sh Build.jar         # Build JAR
 *   ./run-kbuild.sh Build.test        # Run tests
 *   ./run-kbuild.sh --list            # List all targets
 */
object KBuildBuild {
    val projectRoot: File = File(".")
    val buildDir: File = projectRoot.resolve("build/kbuild")
    val srcMain: File = projectRoot.resolve("src/main/kotlin")
    val srcTest: File = projectRoot.resolve("src/test/kotlin")

    /**
     * Dependencies needed for compilation (resolved in parallel, without sources/javadoc).
     */
    val dependencies: Set<File> by lazy {
        val deps = listOf(
            // Kotlin
            "org.jetbrains.kotlin:kotlin-stdlib:${Kotlin.version}",
            "org.jetbrains.kotlin:kotlin-reflect:${Kotlin.version}",
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:${Kotlin.version}",
            "org.jetbrains.kotlin:kotlin-scripting-jsr223:${Kotlin.version}",
            "org.jetbrains.kotlin:kotlin-native-utils:${Kotlin.version}",

            // Reactive
            "com.lightningkite:reactive-jvm:6.0.0-prerelease-26",

            // Serialization
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3",

            // REPL
            "org.jline:jline:3.26.3",

            // Maven/Aether
            "org.eclipse.aether:aether-api:1.0.0.v20140518",
            "org.eclipse.aether:aether-impl:1.0.0.v20140518",
            "org.eclipse.aether:aether-util:1.0.0.v20140518",
            "org.eclipse.aether:aether-connector-basic:1.0.0.v20140518",
            "org.eclipse.aether:aether-transport-file:1.0.0.v20140518",
            "org.eclipse.aether:aether-transport-http:1.0.0.v20140518",
            "org.apache.maven:maven-aether-provider:3.1.0",

            // Utilities
            "org.redundent:kotlin-xml-builder:1.9.1",
            "org.apache.commons:commons-text:1.11.0",
            "org.jasypt:jasypt:1.9.3",

            // JUnit
            "org.junit.jupiter:junit-jupiter-api:5.8.1",
            "org.junit.jupiter:junit-jupiter-engine:5.8.1",
            "org.junit.platform:junit-platform-launcher:1.10.2"
        )
        // Use parallel resolution without fetching sources/javadoc for compilation
        deps.flatMap { runBlocking { MavenAether.libraries(it, fetchSources = false) } }
            .mapNotNull { it.default }
            .toSet()
    }

    /**
     * Compile main sources.
     */
    fun compile(): File {
        println("Compiling KBuild sources...")
        val outputDir = buildDir.resolve("classes/main")

        val result = kotlinJvmCompileBlocking(
            name = "kbuild",
            sourceRoots = setOf(srcMain),
            classpathJars = dependencies,
            cache = buildDir.resolve("cache/main"),
            outputFolder = outputDir,
            enableContextParameters = true
        )

        println("Compiled to: $result")
        return result
    }

    /**
     * Compile test sources.
     */
    fun compileTest(): File {
        println("Compiling KBuild test sources...")
        val mainClasses = compile()
        val outputDir = buildDir.resolve("classes/test")

        val testDeps = dependencies + setOf(mainClasses) +
            runBlocking { MavenAether.libraries("org.jetbrains.kotlin:kotlin-test-junit5:${Kotlin.version}", fetchSources = false) }
                .mapNotNull { it.default }
                .toSet()

        val result = kotlinJvmCompileBlocking(
            name = "kbuild-test",
            sourceRoots = setOf(srcTest),
            classpathJars = testDeps,
            cache = buildDir.resolve("cache/test"),
            outputFolder = outputDir,
            enableContextParameters = true
        )

        println("Compiled tests to: $result")
        return result
    }

    /**
     * Build the KBuild JAR.
     */
    fun jar(): File {
        val mainClasses = compile()
        val outputJar = buildDir.resolve("libs/kbuild.jar")

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "com.ivieleague.kbuild.cli.KBuildCliKt"
            mainAttributes[Attributes.Name("Created-By")] = "KBuild"
        }

        jarBuildBlocking(
            manifest = manifest,
            folders = setOf(mainClasses),
            output = outputJar
        )

        println("Built JAR: $outputJar")
        return outputJar
    }

    /**
     * Additional test dependencies (kotlin-test-junit5).
     */
    val testDependencies: Set<File> by lazy {
        runBlocking { MavenAether.libraries("org.jetbrains.kotlin:kotlin-test-junit5:${Kotlin.version}", fetchSources = false) }
            .mapNotNull { it.default }
            .toSet()
    }

    /**
     * Run tests.
     */
    fun test(): Set<com.ivieleague.kbuild.common.TestResult> {
        val mainClasses = compile()
        val testClasses = compileTest()

        println("Running tests...")

        // Include test dependencies (kotlin-test-junit5) in runtime classpath
        val classpath = dependencies + testDependencies + mainClasses

        val results = junitRunBlocking(
            testModule = testClasses,
            classpath = classpath
        )

        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }

        println()
        println("Test Results: $passed passed, $failed failed")

        for (result in results.filter { !it.passed }) {
            println("  FAILED: ${result.identifier}")
            result.error?.let { println("    $it") }
        }

        return results
    }

    /**
     * Clean build outputs.
     */
    fun clean() {
        println("Cleaning build directory...")
        buildDir.deleteRecursively()
        println("Done.")
    }

    /**
     * Full build: compile, test, jar.
     */
    fun build(): File {
        compile()
        test()
        return jar()
    }

    /**
     * Print build info.
     */
    fun info() {
        println("KBuild Self-Hosting Build")
        println()
        println("Project Root: ${projectRoot.absolutePath}")
        println("Build Dir: ${buildDir.absolutePath}")
        println("Source Dir: ${srcMain.absolutePath}")
        println("Test Dir: ${srcTest.absolutePath}")
        println()
        println("Kotlin Version: ${Kotlin.version}")
        println()
        println("Dependencies: ${dependencies.size} files")
    }
}
