package com.ivieleague.kbuild

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static checks that Build.kt keeps using the reactive suspend-based API.
 *
 * These catch the most common self-build regression: reactive function signatures
 * changing (e.g. context parameters vs. suspend functions) without Build.kt being updated.
 *
 * Note: there is no longer a test here that drives `kbuild Build.compile` end-to-end.
 * That is now proven continuously by the bootstrap itself — `./run-kbuild.sh Build.compile`
 * compiles Build.kt against kbuild on every build — so an in-suite duplicate would be both
 * redundant and self-defeating: it deleted `kbuild-out`, which is the live test classpath
 * when the suite is run via kbuild's own `Build.test`.
 */
class SelfBuildTest {

    private val projectRoot = File(".")

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
