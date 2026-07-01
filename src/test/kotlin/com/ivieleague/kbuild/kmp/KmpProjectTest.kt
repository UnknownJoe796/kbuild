package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Dependency
import com.lightningkite.reactive.core.Constant
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KmpProjectTest {

    @Test
    fun `source set hierarchy has correct dependencies`() {
        val root = File("build/run/KmpSourceSetTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Jvm, KmpTarget.Native.MacosArm64)
        )

        // Check jvmMain depends on commonMain
        assertTrue(hierarchy.commonMain in hierarchy.jvmMain.dependsOn)

        // Check native hierarchy
        assertTrue(hierarchy.commonMain in hierarchy.nativeMain.dependsOn)
        assertTrue(hierarchy.nativeMain in hierarchy.appleMain.dependsOn)
        assertTrue(hierarchy.appleMain in hierarchy.macosMain.dependsOn)
        assertTrue(hierarchy.macosMain in hierarchy.macosArm64Main.dependsOn)

        // Check allDependsOn includes transitive dependencies
        val macosArm64AllDeps = hierarchy.macosArm64Main.allDependsOn
        assertTrue(hierarchy.commonMain in macosArm64AllDeps)
        assertTrue(hierarchy.nativeMain in macosArm64AllDeps)
        assertTrue(hierarchy.appleMain in macosArm64AllDeps)
        assertTrue(hierarchy.macosMain in macosArm64AllDeps)
    }

    @Test
    fun `source set uses standard layout`() {
        val root = File("build/run/KmpLayoutTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Jvm),
            useStandardLayout = true
        )

        assertEquals(
            setOf(root.resolve("src/commonMain/kotlin")),
            hierarchy.commonMain.sourceDirectories
        )

        assertEquals(
            setOf(root.resolve("src/jvmMain/kotlin")),
            hierarchy.jvmMain.sourceDirectories
        )
    }

    @Test
    fun `getMainSourceSets returns correct source sets for enabled targets`() {
        val root = File("build/run/KmpMainSourceSetsTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Jvm, KmpTarget.Js)
        )

        val mainSourceSets = hierarchy.getMainSourceSets()

        assertTrue(hierarchy.commonMain in mainSourceSets)
        assertTrue(hierarchy.jvmMain in mainSourceSets)
        assertTrue(hierarchy.jsMain in mainSourceSets)

        // Native should not be included since no native targets enabled
        assertTrue(hierarchy.nativeMain !in mainSourceSets)
    }

    @Test
    fun `KmpTarget groups contain correct targets`() {
        assertTrue(KmpTarget.Native.MacosArm64 in KmpTargetGroup.APPLE.targets)
        assertTrue(KmpTarget.Native.IosArm64 in KmpTargetGroup.IOS.targets)
        assertTrue(KmpTarget.Native.LinuxX64 in KmpTargetGroup.LINUX.targets)
        assertTrue(KmpTarget.Native.MingwX64 in KmpTargetGroup.MINGW.targets)

        // POSIX should include Linux and Apple but not Windows
        assertTrue(KmpTarget.Native.LinuxX64 in KmpTargetGroup.POSIX.targets)
        assertTrue(KmpTarget.Native.MacosArm64 in KmpTargetGroup.POSIX.targets)
        assertTrue(KmpTarget.Native.MingwX64 !in KmpTargetGroup.POSIX.targets)
    }

    @Test
    fun `KmpProject builder DSL works`() {
        val root = File("build/run/KmpBuilderTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "test-lib",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host()),
            commonDependencies = setOf(Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"))
        )

        assertTrue(KmpTarget.Jvm in project.targets)
        assertTrue(KmpTarget.Js in project.targets)
        assertTrue(project.targets.any { it is KmpTarget.Native })
        assertEquals(1, project.commonDependencies.size)
    }

    @Test
    fun `KmpProject compiles JVM target`() {
        val root = File("build/run/KmpJvmCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directories
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }

        // Create common source (no expect/actual for simple test)
        commonSrc.resolve("Common.kt").writeText("""
            package mylib

            fun greet(name: String): String = "Hello, ${'$'}name!"
        """.trimIndent())

        // Create JVM-specific code
        jvmSrc.resolve("Platform.kt").writeText("""
            package mylib

            fun platformName(): String = "JVM"

            fun main() {
                println(greet(platformName()))
            }
        """.trimIndent())

        val config = KmpProjectConfig(name = "kmp-test", projectRoot = root, targets = setOf(KmpTarget.Jvm))

        val output = runBlocking { kmpCompileJvm(config, sourceRoots = Constant(config.getSourcesForTarget(KmpTarget.Jvm))) }

        assertTrue(output.exists(), "Output should exist: $output")

        // Check that class files were generated
        val classFiles = output.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")

        println("Compiled ${classFiles.size} class files to $output")
        classFiles.forEach { println("  - ${it.relativeTo(output)}") }
    }

    @Test
    fun `KmpProject compiles Native target`() {
        val root = File("build/run/KmpNativeCompileTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directories using the host target's source set name
        val hostTarget = KmpTarget.Native.host()
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        val nativeSrc = root.resolve("src/nativeMain/kotlin").also { it.mkdirs() }

        // Create common source (no expect/actual for simple test)
        commonSrc.resolve("Common.kt").writeText("""
            package mylib

            fun greet(name: String): String = "Hello, ${'$'}name!"
        """.trimIndent())

        // Create native-specific code
        nativeSrc.resolve("Platform.kt").writeText("""
            package mylib

            fun platformName(): String = "Native"

            fun main() {
                println(greet(platformName()))
            }
        """.trimIndent())

        val config = KmpProjectConfig(name = "kmp-native-test", projectRoot = root, targets = setOf(hostTarget))

        val output = runBlocking { kmpCompileNativeKlib(config, hostTarget) }

        assertTrue(output.exists(), "Native build should produce output: $output")
        assertTrue(output.extension == "klib", "Output should be a .klib file: $output")

        println("Compiled native library: $output")
    }
}
