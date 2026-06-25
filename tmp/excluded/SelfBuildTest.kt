package com.ivieleague.kbuild

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests that ensure the Build.kt self-build file compiles correctly with kbuild.
 *
 * This is a regression test to catch when API changes break the self-build capability.
 * The most common failure is when reactive function signatures change (e.g., context
 * parameters to suspend functions) without updating Build.kt.
 *
 * These tests use the actual kbuild CLI to compile Build.kt, ensuring the full
 * stack works correctly.
 */
class SelfBuildTest {

    private val projectRoot = File(".")
    private val kbuildDist = projectRoot.resolve("build/install/kbuild")

    private fun ensureKbuildInstalled() {
        if (!kbuildDist.resolve("bin/kbuild").exists()) {
            println("Installing kbuild distribution...")
            val process = ProcessBuilder("./gradlew", "installDist", "--quiet")
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                fail("Failed to install kbuild: $output")
            }
        }
    }

    private fun runKbuild(vararg args: String): Pair<Int, String> {
        val kbuildBin = kbuildDist.resolve("bin/kbuild")
        val process = ProcessBuilder(kbuildBin.absolutePath, *args)
            .directory(projectRoot)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor(5, TimeUnit.MINUTES)

        if (!exitCode) {
            process.destroyForcibly()
            return Pair(-1, "Timeout after 5 minutes\n$output")
        }

        return Pair(process.exitValue(), output)
    }

    @Test
    fun `Build_kt compiles successfully via kbuild CLI`() {
        ensureKbuildInstalled()

        // Clean any previous build output
        val buildOut = projectRoot.resolve("kbuild-out")
        if (buildOut.exists()) {
            buildOut.deleteRecursively()
        }

        // Run the self-build compile target
        val (exitCode, output) = runKbuild("Build.compile")

        println("=== kbuild Build.compile output ===")
        println(output)
        println("=== end output ===")

        assertTrue(exitCode == 0, "Build.compile should succeed, got exit code $exitCode")

        // Verify output was created
        val classesDir = buildOut.resolve("classes/main")
        assertTrue(classesDir.exists(), "Classes directory should exist: $classesDir")

        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")

        println("Self-build compiled ${classFiles.size} class files successfully")
    }

    @Test
    fun `Build_kt reactive function compileReactive is detected as suspend`() {
        // This test verifies that the Build.kt file uses the correct suspend-based API
        // by checking that compileReactive is marked as suspend, not using context parameters

        val buildKt = projectRoot.resolve("Build.kt")
        assertTrue(buildKt.exists(), "Build.kt should exist")

        val content = buildKt.readText()

        // Should NOT have context(ctx: ReactiveContext) for compileReactive
        val hasContextParameter = content.contains(Regex("""context\s*\(\s*ctx\s*:\s*ReactiveContext\s*\)\s*\n\s*fun\s+compileReactive"""))
        assertTrue(!hasContextParameter, "compileReactive should NOT use context(ctx: ReactiveContext) - it should be a suspend function")

        // Should have suspend fun compileReactive
        val hasSuspendFunction = content.contains(Regex("""suspend\s+fun\s+compileReactive"""))
        assertTrue(hasSuspendFunction, "compileReactive should be a suspend function")

        println("Build.kt correctly uses suspend function for compileReactive")
    }

    @Test
    fun `kbuild_src_Build_kt uses suspend functions`() {
        val kbuildSrcBuild = projectRoot.resolve(".kbuild/src/Build.kt")
        if (!kbuildSrcBuild.exists()) {
            println("Skipping: .kbuild/src/Build.kt does not exist")
            return
        }

        val content = kbuildSrcBuild.readText()

        // Should NOT have context(ctx: ReactiveContext) for compileReactive
        val hasContextParameter = content.contains(Regex("""context\s*\(\s*ctx\s*:\s*ReactiveContext\s*\)\s*\n\s*fun\s+compileReactive"""))
        assertTrue(!hasContextParameter, ".kbuild/src/Build.kt compileReactive should NOT use context parameters")

        // Should have suspend fun compileReactive
        val hasSuspendFunction = content.contains(Regex("""suspend\s+fun\s+compileReactive"""))
        assertTrue(hasSuspendFunction, ".kbuild/src/Build.kt compileReactive should be a suspend function")

        println(".kbuild/src/Build.kt correctly uses suspend function for compileReactive")
    }
}
