package com.ivieleague.kbuild.samples

import com.ivieleague.kbuild.junit.ReactiveJUnitRun
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.ReactiveKotlinCompile
import com.ivieleague.kbuild.kotlin.asReactive
import com.ivieleague.kbuild.maven.Dependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import com.ivieleague.kbuild.server.ReactiveServerProcess
import com.ivieleague.kbuild.server.ServerState
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the reactive-server sample project.
 * This validates that Phase 2 features work together.
 */
class ReactiveServerSampleTest {

    @Test
    fun `sample project compiles and runs tests`() {
        val root = File("samples/reactive-server")
        val buildDir = root.resolve("build")
        buildDir.deleteRecursively()

        val srcDir = root.resolve("src/main/kotlin")
        val testSrcDir = root.resolve("src/test/kotlin")

        // Dependencies
        val kotlinDeps = MavenAether.libraries(
            listOf(Dependency(Kotlin.standardLibraryJvmId).aether())
        ).map { it.default }.toSet()

        val testDeps = MavenAether.libraries(
            listOf(
                Dependency(Kotlin.standardLibraryJvmId).aether(),
                Dependency(Kotlin.standardLibraryTestJunit5Id).aether()
            )
        ).map { it.default }.toSet()

        // File watching
        val mainSources = DirectoryWatch(srcDir, "**/*.kt")
        val testSources = DirectoryWatch(testSrcDir, "**/*.kt")

        // Compile main sources
        val mainCompile = ReactiveKotlinCompile(
            name = "reactive-server-main",
            sources = mainSources,
            classpath = kotlinDeps.asReactive(),
            cache = buildDir.resolve("cache/main"),
            outputFolder = buildDir.resolve("classes/main")
        )

        // Create a reactive classpath that includes main compile output
        // This is the pattern for setting up cross-module dependencies
        val testClasspathReactive = object : com.lightningkite.reactive.core.BaseReactive<Set<File>>() {
            private var mainListener: (() -> Unit)? = null

            override fun activate() {
                mainListener = mainCompile.addListener { update() }
                update()
            }

            override fun deactivate() {
                mainListener?.invoke()
            }

            private fun update() {
                val mainState = mainCompile.state
                if (!mainState.ready) {
                    state = com.lightningkite.reactive.core.ReactiveState.notReady
                    return
                }
                if (mainState.exception != null) {
                    state = com.lightningkite.reactive.core.ReactiveState.exception(mainState.exception!!)
                    return
                }
                val mainOutput = mainState.getOrNull()
                state = com.lightningkite.reactive.core.ReactiveState(
                    testDeps + (mainOutput?.let { setOf(it) } ?: emptySet())
                )
            }
        }

        // Compile test sources (depends on main via reactive classpath)
        val testCompile = ReactiveKotlinCompile(
            name = "reactive-server-test",
            sources = testSources,
            classpath = testClasspathReactive,
            cache = buildDir.resolve("cache/test"),
            outputFolder = buildDir.resolve("classes/test")
        )

        // Reactive test runner
        val testRun = ReactiveJUnitRun(
            testModule = testCompile,
            mainModule = mainCompile,
            classpath = testDeps.asReactive()
        )

        val latch = CountDownLatch(1)
        var passedTests = 0
        var failedTests = 0

        testRun.onTestRunComplete = { results ->
            passedTests = results.count { it.passed }
            failedTests = results.count { !it.passed }
            println("Tests complete: $passedTests passed, $failedTests failed")
            latch.countDown()
        }

        val removeListener = testRun.addListener { }

        try {
            val completed = latch.await(120, TimeUnit.SECONDS)
            assertTrue(completed, "Tests should complete within timeout")
            assertTrue(passedTests >= 3, "Should have at least 3 passing tests, had $passedTests")
            assertEquals(0, failedTests, "Should have no failing tests")
        } finally {
            removeListener()
        }
    }

    @Test
    fun `server process can be created from compilation`() {
        val root = File("samples/reactive-server")
        val buildDir = root.resolve("build")
        val srcDir = root.resolve("src/main/kotlin")

        val kotlinDeps = MavenAether.libraries(
            listOf(Dependency(Kotlin.standardLibraryJvmId).aether())
        ).map { it.default }.toSet()

        val mainSources = DirectoryWatch(srcDir, "**/*.kt")
        val mainCompile = ReactiveKotlinCompile(
            name = "reactive-server-main-2",
            sources = mainSources,
            classpath = kotlinDeps.asReactive(),
            cache = buildDir.resolve("cache/main2"),
            outputFolder = buildDir.resolve("classes/main2")
        )

        // Create reactive server (don't start it)
        val server = ReactiveServerProcess(
            mainClass = "samples.reactiveserver.ServerKt",
            compiledModules = listOf(mainCompile),
            additionalClasspath = kotlinDeps.asReactive(),
            port = 8081,
            autoRestart = true
        )

        val removeListener = server.addListener { }

        try {
            // Verify initial state is Stopped
            val state = server.state.getOrNull()
            assertTrue(state is ServerState.Stopped, "Server should initially be stopped, was: $state")

            // Verify server is not running
            assertTrue(!server.isRunning, "Server should not be running")
        } finally {
            removeListener()
        }
    }
}
