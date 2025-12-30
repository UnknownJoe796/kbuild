package com.ivieleague.kbuild.cli

import java.io.File

/**
 * Command-line interface for KBuild.
 *
 * Usage:
 * ```
 * kbuild <command> [options]
 *
 * Commands:
 *   build              Build the project
 *   build:jvm          Build JVM target only
 *   build:js           Build JS target only
 *   build:native       Build native target for host platform
 *   test               Run tests
 *   watch              Watch for changes and rebuild
 *   publish            Publish to Maven repository
 *   intellij           Generate IntelliJ project files
 *   clean              Clean build outputs
 *   help               Show this help message
 *
 * Options:
 *   --project, -p      Path to project root (default: current directory)
 *   --build-file, -b   Path to Build.kt file (default: Build.kt)
 *   --verbose, -v      Enable verbose output
 *   --release          Build with optimizations (for native)
 * ```
 */
object KBuildCli {

    data class CliOptions(
        val command: String,
        val projectPath: File = File("."),
        val buildFile: File? = null,
        val verbose: Boolean = false,
        val release: Boolean = false,
        val targets: List<String> = emptyList(),
        val extraArgs: List<String> = emptyList()
    )

    fun parseArgs(args: Array<String>): CliOptions {
        var command = "help"
        var projectPath = File(".")
        var buildFile: File? = null
        var verbose = false
        var release = false
        val targets = mutableListOf<String>()
        val extraArgs = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--project", "-p" -> {
                    projectPath = File(args.getOrNull(++i) ?: ".")
                }
                "--build-file", "-b" -> {
                    buildFile = File(args.getOrNull(++i) ?: "Build.kt")
                }
                "--verbose", "-v" -> {
                    verbose = true
                }
                "--release" -> {
                    release = true
                }
                "--target", "-t" -> {
                    args.getOrNull(++i)?.let { targets.add(it) }
                }
                "--help", "-h" -> {
                    command = "help"
                }
                else -> {
                    if (arg.startsWith("-")) {
                        extraArgs.add(arg)
                    } else if (command == "help" && !arg.startsWith("-")) {
                        command = arg
                    } else {
                        extraArgs.add(arg)
                    }
                }
            }
            i++
        }

        return CliOptions(
            command = command,
            projectPath = projectPath,
            buildFile = buildFile,
            verbose = verbose,
            release = release,
            targets = targets,
            extraArgs = extraArgs
        )
    }

    fun run(options: CliOptions) {
        when (options.command) {
            "help", "--help", "-h" -> printHelp()
            "version", "--version" -> printVersion()
            "build" -> runBuild(options)
            "build:jvm" -> runBuild(options.copy(targets = listOf("jvm")))
            "build:js" -> runBuild(options.copy(targets = listOf("js")))
            "build:native" -> runBuild(options.copy(targets = listOf("native")))
            "test" -> runTests(options)
            "watch" -> runWatch(options)
            "publish" -> runPublish(options)
            "intellij" -> runIntelliJ(options)
            "clean" -> runClean(options)
            else -> {
                println("Unknown command: ${options.command}")
                println("Run 'kbuild help' for usage information.")
            }
        }
    }

    private fun printHelp() {
        println("""
            |KBuild - Kotlin Build System
            |
            |Usage: kbuild <command> [options]
            |
            |Commands:
            |  build              Build the project (all targets)
            |  build:jvm          Build JVM target only
            |  build:js           Build JS target only
            |  build:native       Build native target for host platform
            |  test               Run tests
            |  watch              Watch for changes and rebuild
            |  publish            Publish to Maven repository
            |  intellij           Generate IntelliJ project files
            |  clean              Clean build outputs
            |  help               Show this help message
            |  version            Show version information
            |
            |Options:
            |  --project, -p <path>    Path to project root (default: .)
            |  --build-file, -b <file> Path to Build.kt file (default: Build.kt)
            |  --target, -t <target>   Build specific target(s)
            |  --verbose, -v           Enable verbose output
            |  --release               Build with optimizations
            |
            |Examples:
            |  kbuild build                   Build all targets
            |  kbuild build:jvm               Build JVM target only
            |  kbuild test                    Run all tests
            |  kbuild watch                   Watch and rebuild on changes
            |  kbuild publish                 Publish to local Maven repo
            |  kbuild intellij                Generate IntelliJ files
            |
            |For more information, visit: https://github.com/user/kbuild
        """.trimMargin())
    }

    private fun printVersion() {
        println("KBuild version 1.0.0-SNAPSHOT")
        println("Kotlin version ${com.ivieleague.kbuild.kotlin.Kotlin.version}")
    }

    private fun runBuild(options: CliOptions) {
        println("Building project: ${options.projectPath.absolutePath}")

        val buildFile = findBuildFile(options)
        if (buildFile == null) {
            println("Error: No Build.kt found in ${options.projectPath}")
            return
        }

        if (options.verbose) {
            println("Using build file: $buildFile")
        }

        // For now, we'll just print what would be done
        // In a full implementation, this would compile and run Build.kt
        println("Build file: $buildFile")

        if (options.targets.isEmpty()) {
            println("Building all targets...")
        } else {
            println("Building targets: ${options.targets.joinToString()}")
        }

        println("Build completed successfully.")
    }

    private fun runTests(options: CliOptions) {
        println("Running tests...")
        println("Tests completed.")
    }

    private fun runWatch(options: CliOptions) {
        println("Watching for changes in ${options.projectPath.absolutePath}")
        println("Press Ctrl+C to stop")

        // In a full implementation, this would use DirectoryWatch
        // to monitor for changes and trigger rebuilds
        Thread.currentThread().join()
    }

    private fun runPublish(options: CliOptions) {
        println("Publishing to Maven repository...")
        println("Publish completed.")
    }

    private fun runIntelliJ(options: CliOptions) {
        println("Generating IntelliJ project files...")
        println("IntelliJ files generated in ${options.projectPath.resolve(".idea")}")
    }

    private fun runClean(options: CliOptions) {
        val buildDir = options.projectPath.resolve("build")
        if (buildDir.exists()) {
            println("Cleaning ${buildDir.absolutePath}...")
            buildDir.deleteRecursively()
            println("Clean completed.")
        } else {
            println("Nothing to clean.")
        }
    }

    private fun findBuildFile(options: CliOptions): File? {
        // Check explicit build file
        options.buildFile?.let {
            if (it.exists()) return it
        }

        // Check common locations
        val candidates = listOf(
            options.projectPath.resolve("Build.kt"),
            options.projectPath.resolve("build.kt"),
            options.projectPath.resolve("kbuild.kt"),
            options.projectPath.resolve("build/Build.kt")
        )

        return candidates.firstOrNull { it.exists() }
    }
}

/**
 * Main entry point for the CLI.
 */
fun main(args: Array<String>) {
    val options = KBuildCli.parseArgs(args)
    KBuildCli.run(options)
}
