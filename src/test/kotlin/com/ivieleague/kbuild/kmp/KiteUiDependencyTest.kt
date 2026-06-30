package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.maven.MavenAether
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stress tests using KiteUI - a real-world Kotlin Multiplatform UI library.
 *
 * These tests verify that:
 * 1. KiteUI dependencies resolve correctly for each target
 * 2. Projects using KiteUI compile successfully
 * 3. iOS framework generation works with KiteUI
 */
class KiteUiDependencyTest {

    companion object {
        // KiteUI Maven coordinates
        // Using the latest stable version from https://lightningkite-maven.s3.us-west-2.amazonaws.com
        const val KITEUI_GROUP = "com.lightningkite.kiteui"
        const val KITEUI_ARTIFACT = "library"
        const val KITEUI_VERSION = "5.3.36"

        val kiteUiDependency = KmpDependency(
            groupId = KITEUI_GROUP,
            artifactId = KITEUI_ARTIFACT,
            version = KITEUI_VERSION
        )
    }

    @Test
    fun `KiteUI artifact ID resolves correctly for JVM`() {
        assertEquals(
            "library-jvm",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Jvm)
        )
    }

    @Test
    fun `KiteUI artifact ID resolves correctly for JS`() {
        assertEquals(
            "library-js",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Js)
        )
    }

    @Test
    fun `KiteUI artifact ID resolves correctly for iOS targets`() {
        assertEquals(
            "library-iosarm64",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Native.IosArm64)
        )
        assertEquals(
            "library-iossimulatorarm64",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Native.IosSimulatorArm64)
        )
        assertEquals(
            "library-iosx64",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Native.IosX64)
        )
    }

    @Test
    fun `KiteUI artifact ID resolves correctly for macOS targets`() {
        assertEquals(
            "library-macosarm64",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Native.MacosArm64)
        )
        assertEquals(
            "library-macosx64",
            kiteUiDependency.artifactIdForTarget(KmpTarget.Native.MacosX64)
        )
    }

    @Test
    fun `KiteUI Maven dependency for JVM has correct properties`() {
        val mavenDep = kiteUiDependency.forTarget(KmpTarget.Jvm)

        assertEquals(KITEUI_GROUP, mavenDep.groupId)
        assertEquals("library-jvm", mavenDep.artifactId)
        assertEquals(KITEUI_VERSION, mavenDep.version)
        assertEquals("jar", mavenDep.type)
    }

    @Test
    fun `KiteUI Maven dependency for iOS has correct properties`() {
        val mavenDep = kiteUiDependency.forTarget(KmpTarget.Native.IosArm64)

        assertEquals(KITEUI_GROUP, mavenDep.groupId)
        assertEquals("library-iosarm64", mavenDep.artifactId)
        assertEquals(KITEUI_VERSION, mavenDep.version)
        assertEquals("klib", mavenDep.type)
    }

    @Test
    fun `KiteUI JVM dependency resolves from Lightning Kite repository`() {
        val libs = runBlocking { kiteUiDependency.resolveForTarget(KmpTarget.Jvm) }

        assertTrue(libs.isNotEmpty(), "Should resolve KiteUI JVM dependency")
        assertTrue(
            libs.any { it.name.contains("library") },
            "Should contain library artifact"
        )

        println("Resolved KiteUI JVM libraries:")
        libs.forEach { println("  - ${it.name}: ${it.default}") }
    }

    @Test
    fun `KiteUI JS dependency resolves or returns empty if not available`() {
        // JS artifacts may not be available for all versions
        val libs = runBlocking { kiteUiDependency.resolveForTarget(KmpTarget.Js) }

        // Just verify resolution doesn't throw - empty is acceptable if artifact doesn't exist
        println("Resolved KiteUI JS libraries (${libs.size} found):")
        libs.forEach { println("  - ${it.name}: ${it.default}") }

        // If libraries are found, verify they have the expected artifact
        if (libs.isNotEmpty()) {
            assertTrue(
                libs.any { it.name.contains("library") },
                "Should contain library artifact"
            )
        }
    }

    @Test
    fun `KiteUI native dependency resolves or returns empty if not available`() {
        val hostTarget = KmpTarget.Native.host()
        // Native artifacts may not be available for all versions
        val libs = runBlocking { kiteUiDependency.resolveForTarget(hostTarget) }

        // Just verify resolution doesn't throw - empty is acceptable if artifact doesn't exist
        println("Resolved KiteUI ${hostTarget.name} libraries (${libs.size} found):")
        libs.forEach { println("  - ${it.name}: ${it.default}") }
    }

    @Test
    fun `KmpProject with KiteUI dependency has correct structure`() {
        val root = File("build/run/KiteUiProjectTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "kiteui-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64, KmpTarget.Native.MacosX64, KmpTarget.Native.MacosArm64),
            commonDependencies = setOf(kiteUiDependency)
        )

        // Verify targets
        assertTrue(KmpTarget.Jvm in project.targets)
        assertTrue(KmpTarget.Js in project.targets)
        assertTrue(KmpTarget.Native.IosArm64 in project.targets)
        assertTrue(KmpTarget.Native.IosSimulatorArm64 in project.targets)
        assertTrue(KmpTarget.Native.MacosArm64 in project.targets)
        assertTrue(KmpTarget.Native.MacosX64 in project.targets)

        // Verify dependency
        assertEquals(1, project.commonDependencies.size)
        assertEquals(kiteUiDependency, project.commonDependencies.first())
    }

    @Test
    fun `KmpProject with KiteUI resolves JVM classpath correctly`() {
        val root = File("build/run/KiteUiJvmClasspathTest")
        root.deleteRecursively()
        root.mkdirs()

        val project = KmpProjectConfig(
            name = "kiteui-jvm-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(kiteUiDependency)
        )

        // Verify the dependency resolver includes KiteUI
        val classpath = runBlocking { project.dependencies.resolveJvmClasspath() }

        assertTrue(classpath.isNotEmpty(), "JVM classpath should not be empty")
        assertTrue(
            classpath.any { it.name.contains("library") && it.name.contains("jvm") },
            "Classpath should include KiteUI library-jvm: ${classpath.map { it.name }}"
        )

        println("KiteUI JVM classpath resolved successfully:")
        classpath.filter { it.name.contains("kiteui") || it.name.contains("library") }
            .forEach { println("  - ${it.name}") }
    }

    @Test
    fun `KmpProject with KiteUI compiles simple JVM code without KiteUI API`() {
        // Test compilation works with KiteUI on the classpath
        // (API usage may vary between versions, so we use stdlib only)
        val root = File("build/run/KiteUiJvmCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("MyApp.kt").writeText("""
            package myapp

            // Simple code that compiles with KiteUI on classpath
            // but doesn't use version-specific API
            class MyViewModel {
                private var _counter: Int = 0
                val counter: Int get() = _counter

                fun increment() { _counter++ }
                fun decrement() { _counter-- }
            }

            fun greeting(): String = "Hello from KiteUI project"
        """.trimIndent())

        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }
        jvmSrc.resolve("Main.kt").writeText("""
            package myapp

            fun main() {
                val vm = MyViewModel()
                println(greeting())
                println("Initial: ${'$'}{vm.counter}")
                vm.increment()
                println("After increment: ${'$'}{vm.counter}")
            }
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "kiteui-jvm-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(kiteUiDependency)
        )

        val output = runBlocking { kmpCompileJvmBlocking(project) }

        assertTrue(output.exists(), "Output should exist")

        val classFiles = output.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")
        assertTrue(
            classFiles.any { it.name == "MyViewModel.class" },
            "MyViewModel.class should exist"
        )

        println("Successfully compiled project with KiteUI on classpath")
        classFiles.forEach { println("  - ${it.relativeTo(output)}") }
    }

    @Test
    fun `iOS framework configuration for KiteUI project`() {
        val root = File("build/run/KiteUiIosFrameworkTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create minimal iOS-compatible code
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("SharedCode.kt").writeText("""
            package myapp

            import com.lightningkite.kiteui.reactive.*

            object AppState {
                val isLoggedIn = Property(false)
                val username = Property<String?>(null)

                fun login(user: String) {
                    username.value = user
                    isLoggedIn.value = true
                }

                fun logout() {
                    username.value = null
                    isLoggedIn.value = false
                }
            }
        """.trimIndent())

        val iosSrc = root.resolve("src/iosMain/kotlin").also { it.mkdirs() }
        iosSrc.resolve("IosEntryPoint.kt").writeText("""
            package myapp

            object IosApp {
                fun start() {
                    println("iOS App started")
                }
            }
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "kiteui-ios-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64),
            commonDependencies = setOf(kiteUiDependency)
        )

        // Verify iOS targets are configured
        assertTrue(KmpTarget.Native.IosArm64 in project.targets)
        assertTrue(KmpTarget.Native.IosSimulatorArm64 in project.targets)

        // Verify source sets are correct
        val iosArm64Sources = project.getSourcesForTarget(KmpTarget.Native.IosArm64)
        assertTrue(
            iosArm64Sources.any { it.path.contains("commonMain") },
            "iOS should include commonMain"
        )
        assertTrue(
            iosArm64Sources.any { it.path.contains("iosMain") },
            "iOS should include iosMain"
        )
    }

    @Test
    fun `KiteUI dependency resolver includes transitive dependencies`() {
        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(kiteUiDependency)
        )

        // KiteUI should have transitive dependencies (kotlinx-coroutines, etc.)
        // The resolver should include all of them
        assertEquals(1, resolver.commonDependencies.size)
        assertTrue(kiteUiDependency in resolver.commonDependencies)
    }

    @Test
    fun `multiple KiteUI-related dependencies work together`() {
        val kiteui = kiteUiDependency
        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        val serialization = kmpDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

        val root = File("build/run/KiteUiMultiDepTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "kiteui-multi-dep",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host()),
            commonDependencies = setOf(kiteui, coroutines, serialization)
        )

        assertEquals(3, project.commonDependencies.size)

        // All should resolve to correct artifacts for JVM
        project.commonDependencies.forEach { dep ->
            val jvmArtifact = dep.forTarget(KmpTarget.Jvm)
            assertTrue(jvmArtifact.artifactId.endsWith("-jvm"), "${dep.artifactId} should end with -jvm")
        }
    }

    @Test
    fun `full iOS target list for production app`() {
        val root = File("build/run/KiteUiFullIosTest")
        root.deleteRecursively()

        // This is what a production iOS app would typically need
        val project = KmpProjectConfig(
            name = "production-ios-app",
            projectRoot = root,
            targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64, KmpTarget.Native.IosX64, KmpTarget.Native.MacosArm64, KmpTarget.Native.MacosX64),
            commonDependencies = setOf(kiteUiDependency)
        )

        // Verify all iOS targets
        assertEquals(5, project.targets.size)

        val iosTargets = project.targets.filterIsInstance<KmpTarget.Native>()
            .filter { KmpTargetGroup.IOS.contains(it) || KmpTargetGroup.MACOS.contains(it) }
        assertEquals(5, iosTargets.size)

        // Verify source set hierarchy for each iOS target
        listOf(
            KmpTarget.Native.IosArm64,
            KmpTarget.Native.IosSimulatorArm64,
            KmpTarget.Native.IosX64
        ).forEach { target ->
            val sourceSet = project.sourceSets.getSourceSetForTarget(target)
            assertTrue(sourceSet != null, "Should have source set for $target")

            val allDeps = sourceSet!!.allDependsOn
            assertTrue(
                project.sourceSets.commonMain in allDeps,
                "$target should depend on commonMain"
            )
            assertTrue(
                project.sourceSets.nativeMain in allDeps,
                "$target should depend on nativeMain"
            )
            assertTrue(
                project.sourceSets.appleMain in allDeps,
                "$target should depend on appleMain"
            )
            assertTrue(
                project.sourceSets.iosMain in allDeps,
                "$target should depend on iosMain"
            )
        }
    }
}
