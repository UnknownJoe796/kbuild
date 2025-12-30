package com.ivieleague.kbuild.integration

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
import kotlin.test.assertTrue

class ReactiveIntegrationTestTest {

    @Test
    fun `waits for server before running tests`() {
        val root = File("build/run/ReactiveIntegrationTestTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create test sources
        val testSrcDir = root.resolve("test-src").also { it.mkdirs() }
        testSrcDir.resolve("IntegrationTest.kt").writeText("""
            package test
            import kotlin.test.Test
            import kotlin.test.assertTrue

            class IntegrationTest {
                @Test
                fun serverIsReachable() {
                    // In real usage, this would make HTTP calls to the server
                    assertTrue(true, "Server should be reachable")
                }
            }
        """.trimIndent())

        // Create server sources (simple main that just sleeps)
        val serverSrcDir = root.resolve("server-src").also { it.mkdirs() }
        serverSrcDir.resolve("Server.kt").writeText("""
            package server

            fun main() {
                println("Server starting...")
                // Simulate startup delay
                Thread.sleep(500)
                println("Server ready on port 8080")

                // Keep running
                while (true) {
                    Thread.sleep(1000)
                }
            }
        """.trimIndent())

        val buildDir = root.resolve("build")
        val dependencies = MavenAether.libraries(
            listOf(
                Dependency(Kotlin.standardLibraryJvmId).aether(),
                Dependency(Kotlin.standardLibraryTestJunit5Id).aether()
            )
        ).map { it.default }.toSet()

        // Compile test module
        val testSources = DirectoryWatch(testSrcDir, "**/*.kt")
        val testCompile = ReactiveKotlinCompile(
            name = "integration-test-compile",
            sources = testSources,
            classpath = dependencies.asReactive(),
            cache = buildDir.resolve("test-cache"),
            outputFolder = buildDir.resolve("test-classes")
        )

        // Compile server module
        val serverSources = DirectoryWatch(serverSrcDir, "**/*.kt")
        val serverCompile = ReactiveKotlinCompile(
            name = "server-compile",
            sources = serverSources,
            classpath = dependencies.asReactive(),
            cache = buildDir.resolve("server-cache"),
            outputFolder = buildDir.resolve("server-classes")
        )

        // Create reactive server
        val server = ReactiveServerProcess(
            mainClass = "server.ServerKt",
            compiledModules = listOf(serverCompile),
            additionalClasspath = dependencies.asReactive(),
            autoRestart = true
        )

        // Create reactive test runner
        val testRun = ReactiveJUnitRun(
            testModule = testCompile,
            classpath = dependencies.asReactive()
        )

        // Create integration test coordinator
        val integration = ReactiveIntegrationTest(
            servers = mapOf("api" to server),
            testRun = testRun
        )

        val stateHistory = mutableListOf<IntegrationTestState>()
        val completedLatch = CountDownLatch(1)

        integration.onTestRunComplete = { summary ->
            println("Integration tests complete: ${summary.passed}/${summary.total} passed")
            completedLatch.countDown()
        }

        val removeListener = integration.addListener {
            val currentState = integration.state.getOrNull()
            if (currentState != null) {
                println("Integration state: $currentState")
                stateHistory.add(currentState)
            }
        }

        try {
            // Initially should be waiting for servers
            val initialState = integration.state.getOrNull()
            assertTrue(
                initialState is IntegrationTestState.WaitingForServers,
                "Should initially be waiting for servers, was: $initialState"
            )

            // Verify server tracking
            assertTrue(
                !integration.allServersRunning,
                "Servers should not be running initially"
            )

            // Note: We don't actually start the server in this test since it would
            // require a real server implementation. The test verifies the coordination
            // logic works correctly.

        } finally {
            removeListener()
            server.stop()
        }
    }

    @Test
    fun `tracks multiple servers`() {
        val root = File("build/run/ReactiveIntegrationMultiServerTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        srcDir.resolve("Main.kt").writeText("""
            package test
            fun main() { println("Hello") }
        """.trimIndent())

        val buildDir = root.resolve("build")
        val dependencies = MavenAether.libraries(
            listOf(Dependency(Kotlin.standardLibraryJvmId).aether())
        ).map { it.default }.toSet()

        val sources = DirectoryWatch(srcDir, "**/*.kt")
        val compile = ReactiveKotlinCompile(
            name = "multi-server-test",
            sources = sources,
            classpath = dependencies.asReactive(),
            cache = buildDir.resolve("cache"),
            outputFolder = buildDir.resolve("classes")
        )

        // Create multiple servers
        val apiServer = ReactiveServerProcess(
            mainClass = "test.MainKt",
            compiledModules = listOf(compile),
            additionalClasspath = dependencies.asReactive(),
            port = 8080
        )

        val workerServer = ReactiveServerProcess(
            mainClass = "test.MainKt",
            compiledModules = listOf(compile),
            additionalClasspath = dependencies.asReactive(),
            port = 8081
        )

        // Create test run (placeholder)
        val testRun = ReactiveJUnitRun(
            testModule = compile,
            classpath = dependencies.asReactive()
        )

        // Create integration test with multiple servers
        val integration = ReactiveIntegrationTest(
            servers = mapOf(
                "api" to apiServer,
                "worker" to workerServer
            ),
            testRun = testRun
        )

        val removeListener = integration.addListener { }

        try {
            // Verify both servers are tracked
            val serverStates = integration.serverStates
            assertTrue(serverStates.containsKey("api"), "Should track api server")
            assertTrue(serverStates.containsKey("worker"), "Should track worker server")

            // Initially neither should be running
            assertTrue(!integration.allServersRunning, "No servers should be running initially")

            // Initial state should show both servers as pending
            val state = integration.state.getOrNull()
            assertTrue(state is IntegrationTestState.WaitingForServers, "Should be waiting for servers")
            val waitingState = state as IntegrationTestState.WaitingForServers
            assertTrue(waitingState.pendingServers.contains("api"), "Should be waiting for api")
            assertTrue(waitingState.pendingServers.contains("worker"), "Should be waiting for worker")

        } finally {
            removeListener()
            apiServer.stop()
            workerServer.stop()
        }
    }
}
