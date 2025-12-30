#!/usr/bin/env kotlin

/**
 * Build script for the hello-android sample project.
 *
 * This demonstrates using AndroidProject to build an Android APK
 * without Gradle or Android Gradle Plugin.
 *
 * Usage:
 *   ./Build.kt scaffold   - Set up the Android project structure
 *   ./Build.kt build      - Build the APK
 *   ./Build.kt install    - Install APK on device/emulator
 *   ./Build.kt run        - Launch the app
 *   ./Build.kt clean      - Clean build artifacts
 */

@file:DependsOn("com.ivieleague:kbuild:1.0.0")

import com.ivieleague.kbuild.android.AndroidProject
import com.ivieleague.kbuild.android.androidProject
import com.ivieleague.kbuild.kmp.kmpProject
import java.io.File

val projectRoot = File(".")

// Create KMP project with JVM target (required for Android)
val kmpProject = kmpProject("hello-android", projectRoot) {
    jvm()
}

// Create Android project
val android = kmpProject.androidProject(
    packageName = "com.example.helloandroid",
    minSdk = 24,
    targetSdk = 34,
    versionCode = 1,
    versionName = "1.0.0"
)

// Handle command line arguments
when (args.firstOrNull()) {
    "scaffold" -> {
        println("Setting up Android project...")
        android.scaffold(
            appName = "Hello Android",
            mainActivity = ".MainActivity"
        )
        println()
        println("Done! Next steps:")
        println("  1. ./Build.kt build")
        println("  2. ./Build.kt install")
        println("  3. ./Build.kt run")
    }

    "build" -> {
        if (!AndroidProject.isAvailable()) {
            println("ERROR: Android SDK not found!")
            println("Please set ANDROID_HOME environment variable or install Android Studio.")
            System.exit(1)
        }

        println("Building Android APK...")
        val result = android.build()

        println()
        println("Build complete!")
        println("APK: ${result.apk}")
    }

    "install" -> {
        println("Installing APK...")
        val success = android.install()
        if (!success) {
            println("Install failed. Is a device connected?")
            println("Check with: adb devices")
            System.exit(1)
        }
    }

    "run" -> {
        println("Launching app...")
        android.run()
    }

    "build-run" -> {
        println("Building and running...")
        android.build()
        android.install()
        android.run()
    }

    "devices" -> {
        println("Connected devices:")
        val devices = android.listDevices()
        if (devices.isEmpty()) {
            println("  (none)")
        } else {
            devices.forEach { device ->
                println("  ${device.serial} - ${device.model ?: device.device ?: "unknown"} (${device.state})")
            }
        }
    }

    "emulators" -> {
        println("Available emulators:")
        val emulators = android.listEmulators()
        if (emulators.isEmpty()) {
            println("  (none)")
        } else {
            emulators.forEach { println("  $it") }
        }
    }

    "logcat" -> {
        val lines = args.getOrNull(1)?.toIntOrNull() ?: 100
        println("Recent logs (last $lines lines):")
        println(android.logcat(lines))
    }

    "clean" -> {
        android.clean()
    }

    "stop" -> {
        println("Stopping app...")
        android.stop()
    }

    "clear" -> {
        println("Clearing app data...")
        android.clearData()
    }

    else -> {
        println("""
            Hello Android Sample
            ====================

            Usage: ./Build.kt <command>

            Commands:
              scaffold   - Set up the Android project structure
              build      - Build the APK
              install    - Install APK on device/emulator
              run        - Launch the app
              build-run  - Build, install, and run in one step
              devices    - List connected devices
              emulators  - List available emulators
              logcat     - View app logs (add number for line count)
              stop       - Stop the app
              clear      - Clear app data
              clean      - Clean build artifacts

            Quick Start:
              1. ./Build.kt scaffold
              2. ./Build.kt build
              3. ./Build.kt install
              4. ./Build.kt run

            Requirements:
              - Android SDK (set ANDROID_HOME)
              - Device/emulator for install and run
        """.trimIndent())
    }
}
