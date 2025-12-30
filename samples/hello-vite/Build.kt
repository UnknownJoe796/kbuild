#!/usr/bin/env kotlin

/**
 * Build script for the hello-vite sample project.
 *
 * This demonstrates using WebProject to build a Kotlin/JS web app with Vite.
 *
 * Usage:
 *   ./Build.kt scaffold   - Set up the Vite project
 *   ./Build.kt dev        - Start dev server with HMR
 *   ./Build.kt build      - Build for production
 *   ./Build.kt clean      - Clean build artifacts
 */

@file:DependsOn("com.ivieleague:kbuild:1.0.0")

import com.ivieleague.kbuild.kmp.kmpProject
import com.ivieleague.kbuild.vite.webProject
import java.io.File

val projectRoot = File(".")

// Create KMP project with JS target
val kmpProject = kmpProject("hello-vite", projectRoot) {
    js()
}

// Create web project
val webProject = kmpProject.webProject(
    title = "Hello Vite + Kotlin"
)

// Handle command line arguments
when (args.firstOrNull()) {
    "scaffold" -> {
        println("Setting up Vite project...")
        webProject.scaffold(initFunction = "App.main")
        println()
        println("Done! Next steps:")
        println("  1. ./Build.kt dev")
    }

    "dev" -> {
        println("Starting development server...")
        val server = webProject.dev(openBrowser = true)

        // Keep running until interrupted
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
        })

        // Wait forever (until Ctrl+C)
        Thread.currentThread().join()
    }

    "dev-watch" -> {
        println("Starting development server with file watching...")
        val session = webProject.devWatch(openBrowser = true)

        Runtime.getRuntime().addShutdownHook(Thread {
            session.stop()
        })

        Thread.currentThread().join()
    }

    "build" -> {
        println("Building for production...")
        val result = webProject.build()
        result.printSummary()
    }

    "clean" -> {
        webProject.clean()
    }

    "compile" -> {
        println("Compiling Kotlin to JavaScript...")
        val output = webProject.compileKotlin()
        println("Output: $output")
    }

    else -> {
        println("""
            Hello Vite + Kotlin Sample
            ==========================

            Usage: ./Build.kt <command>

            Commands:
              scaffold   - Set up the Vite project structure
              dev        - Start dev server (compiles Kotlin + runs Vite)
              dev-watch  - Start dev server with Kotlin file watching
              build      - Build for production
              compile    - Only compile Kotlin to JS
              clean      - Clean build artifacts

            Quick Start:
              1. ./Build.kt scaffold
              2. cd web && npm install
              3. ./Build.kt dev
        """.trimIndent())
    }
}
