package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.kmp.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GradleIdeBuildTest {

    @Test
    fun `generates valid Gradle files for JVM-only project`() {
        val root = File("build/run/GradleIdeBuildTest/jvm-only").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val config = kmpProject("my-jvm-app", root) {
            jvm()
        }

        val result = GradleIdeBuild.generate(config)

        // Verify files exist
        assertTrue(result.resolve("settings.gradle.kts").exists(), "settings.gradle.kts should exist")
        assertTrue(result.resolve("build.gradle.kts").exists(), "build.gradle.kts should exist")
        assertTrue(result.resolve("gradle.properties").exists(), "gradle.properties should exist")
        assertTrue(result.resolve("gradle/wrapper/gradle-wrapper.properties").exists(), "wrapper properties should exist")

        // Verify content
        val buildGradle = result.resolve("build.gradle.kts").readText()
        assertTrue(buildGradle.contains("kotlin(\"multiplatform\")"), "Should use multiplatform plugin")
        assertTrue(buildGradle.contains("jvm()"), "Should declare JVM target")
        assertTrue(buildGradle.contains("IDE-ONLY"), "Should have IDE-only comment")

        println("Generated build.gradle.kts:")
        println(buildGradle)
    }

    @Test
    fun `generates valid Gradle files for multiplatform project`() {
        val root = File("build/run/GradleIdeBuildTest/multiplatform").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val config = kmpProject("my-kmp-lib", root) {
            jvm()
            js()
            macosArm64()
            iosArm64()
            iosSimulatorArm64()
            linuxX64()
            commonDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.0")
        }

        val result = GradleIdeBuild.generate(config)

        val buildGradle = result.resolve("build.gradle.kts").readText()

        // Verify all targets are present
        assertTrue(buildGradle.contains("jvm()"), "Should declare JVM target")
        assertTrue(buildGradle.contains("js(IR)"), "Should declare JS target")
        assertTrue(buildGradle.contains("macosArm64()"), "Should declare macOS ARM64 target")
        assertTrue(buildGradle.contains("iosArm64()"), "Should declare iOS ARM64 target")
        assertTrue(buildGradle.contains("iosSimulatorArm64()"), "Should declare iOS Simulator target")
        assertTrue(buildGradle.contains("linuxX64()"), "Should declare Linux X64 target")

        // Verify dependencies
        assertTrue(
            buildGradle.contains("kotlinx-coroutines-core"),
            "Should include coroutines dependency"
        )

        println("Generated build.gradle.kts:")
        println(buildGradle)
    }

    @Test
    fun `generates valid Gradle files for full Apple targets`() {
        val root = File("build/run/GradleIdeBuildTest/apple").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val config = kmpProject("apple-sdk", root) {
            macos()  // macosX64 + macosArm64
            ios()    // iosArm64 + iosSimulatorArm64
        }

        val result = GradleIdeBuild.generate(config)
        val buildGradle = result.resolve("build.gradle.kts").readText()

        assertTrue(buildGradle.contains("macosX64()"), "Should declare macOS X64")
        assertTrue(buildGradle.contains("macosArm64()"), "Should declare macOS ARM64")
        assertTrue(buildGradle.contains("iosArm64()"), "Should declare iOS ARM64")
        assertTrue(buildGradle.contains("iosSimulatorArm64()"), "Should declare iOS Simulator")

        println("Generated build.gradle.kts for Apple targets:")
        println(buildGradle)
    }

    @Test
    fun `generates multi-module project structure`() {
        val root = File("build/run/GradleIdeBuildTest/monorepo").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        // Create source directories
        root.resolve("shared/src/commonMain/kotlin").mkdirs()
        root.resolve("app-jvm/src/jvmMain/kotlin").mkdirs()
        root.resolve("app-ios/src/iosMain/kotlin").mkdirs()

        val sharedConfig = kmpProject("shared", root.resolve("shared")) {
            jvm()
            js()
            iosArm64()
            iosSimulatorArm64()
            commonDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
        }

        val jvmAppConfig = kmpProject("app-jvm", root.resolve("app-jvm")) {
            jvm()
        }

        val iosAppConfig = kmpProject("app-ios", root.resolve("app-ios")) {
            iosArm64()
            iosSimulatorArm64()
        }

        val result = GradleIdeBuild.generateMultiModule(
            rootDir = root,
            projectName = "my-monorepo",
            modules = mapOf(
                "shared" to sharedConfig,
                "app-jvm" to jvmAppConfig,
                "app-ios" to iosAppConfig
            )
        )

        // Verify root files
        assertTrue(result.resolve("settings.gradle.kts").exists(), "Root settings should exist")
        assertTrue(result.resolve("build.gradle.kts").exists(), "Root build should exist")

        // Verify module files
        assertTrue(result.resolve("shared/build.gradle.kts").exists(), "Shared module build should exist")
        assertTrue(result.resolve("app-jvm/build.gradle.kts").exists(), "JVM app build should exist")
        assertTrue(result.resolve("app-ios/build.gradle.kts").exists(), "iOS app build should exist")

        // Verify settings includes all modules
        val settings = result.resolve("settings.gradle.kts").readText()
        assertTrue(settings.contains("include(\":shared\")"), "Should include shared module")
        assertTrue(settings.contains("include(\":app-jvm\")"), "Should include JVM app module")
        assertTrue(settings.contains("include(\":app-ios\")"), "Should include iOS app module")

        println("Generated settings.gradle.kts:")
        println(settings)
        println()
        println("Generated shared/build.gradle.kts:")
        println(result.resolve("shared/build.gradle.kts").readText())
    }

    @Test
    fun `extension function works on KmpProjectConfig`() {
        val root = File("build/run/GradleIdeBuildTest/extension").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val config = kmpProject("test-lib", root) {
            jvm()
            js()
        }

        // Use extension function
        val result = config.generateIdeGradle()

        assertTrue(result.resolve("build.gradle.kts").exists(), "build.gradle.kts should exist")
    }
}
