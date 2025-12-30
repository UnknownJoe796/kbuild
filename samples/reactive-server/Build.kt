package samples.reactiveserver

import com.ivieleague.kbuild.integration.ReactiveIntegrationTest
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

/**
 * Example build script demonstrating Phase 2 features:
 * - Reactive server process management
 * - Reactive testing
 * - Integration test coordination
 *
 * This shows how to set up a development environment where:
 * 1. Server restarts automatically when code changes
 * 2. Tests rerun automatically when code changes
 * 3. Integration tests wait for server to be ready
 */
object Build {

    val root = File("samples/reactive-server")
    val buildDir = root.resolve("build")
    val srcDir = root.resolve("src/main/kotlin")
    val testSrcDir = root.resolve("src/test/kotlin")

    // Dependencies
    val kotlinDeps = MavenAether.libraries(
        listOf(
            Dependency(Kotlin.standardLibraryJvmId).aether()
        )
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

    // Reactive compilation
    val mainCompile = ReactiveKotlinCompile(
        name = "reactive-server-main",
        sources = mainSources,
        classpath = kotlinDeps.asReactive(),
        cache = buildDir.resolve("cache/main"),
        outputFolder = buildDir.resolve("classes/main")
    )

    val testCompile = ReactiveKotlinCompile(
        name = "reactive-server-test",
        sources = testSources,
        classpath = (testDeps + buildDir.resolve("classes/main")).asReactive(),
        cache = buildDir.resolve("cache/test"),
        outputFolder = buildDir.resolve("classes/test")
    )

    // Reactive server
    val server = ReactiveServerProcess(
        mainClass = "samples.reactiveserver.ServerKt",
        compiledModules = listOf(mainCompile),
        additionalClasspath = kotlinDeps.asReactive(),
        port = 8080,
        healthCheckPath = "/health",
        autoRestart = true
    )

    // Reactive test runner
    val testRun = ReactiveJUnitRun(
        testModule = testCompile,
        mainModule = mainCompile,
        classpath = testDeps.asReactive()
    )

    // Integration test coordinator
    val integrationTest = ReactiveIntegrationTest(
        servers = mapOf("api" to server),
        testRun = testRun
    )

    /**
     * Run the development environment with hot reload.
     */
    fun dev() {
        println("Starting reactive server development environment...")
        println("Server will restart automatically when code changes.")
        println("Tests will rerun automatically when code changes.")
        println()

        // Set up callbacks
        server.onOutput = { println("[SERVER] $it") }
        server.onError = { System.err.println("[SERVER ERROR] $it") }
        server.onReady = { println("\n✓ Server ready on port 8080\n") }
        server.onRestarting = { println("\n↻ Server restarting due to code changes...\n") }

        testRun.onTestComplete = { result ->
            val status = if (result.passed) "✓" else "✗"
            println("  $status ${result.identifier}")
        }

        testRun.onTestRunComplete = { results ->
            val passed = results.count { it.passed }
            val failed = results.count { !it.passed }
            println("\n  Test Results: $passed passed, $failed failed\n")
        }

        // Activate and subscribe to changes
        val serverListener = server.addListener {
            when (val state = server.state.getOrNull()) {
                is ServerState.Stopped -> println("Server stopped")
                is ServerState.Starting -> println("Server starting...")
                is ServerState.Running -> println("Server running (pid: ${state.pid})")
                is ServerState.Restarting -> println("Server restarting...")
                is ServerState.Failed -> println("Server failed: ${state.error.message}")
                null -> {}
            }
        }

        val testListener = testRun.addListener {
            val state = testRun.state
            if (state.ready && state.success) {
                println("Tests completed")
            } else if (state.exception != null) {
                println("Tests failed: ${state.exception}")
            }
        }

        // Start the server
        server.start(waitForReady = true)

        // Keep running
        val latch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
            serverListener()
            testListener()
            server.stop()
            latch.countDown()
        })

        println("Press Ctrl+C to stop")
        latch.await()
    }

    /**
     * Run tests once and exit.
     */
    fun test() {
        println("Running tests...")

        val latch = CountDownLatch(1)
        var exitCode = 0

        testRun.onTestRunComplete = { results ->
            val passed = results.count { it.passed }
            val failed = results.count { !it.passed }
            println("\nTest Results: $passed passed, $failed failed")

            results.filter { !it.passed }.forEach { result ->
                println("\n  FAILED: ${result.identifier}")
                result.error?.let { println("    $it") }
            }

            exitCode = if (failed > 0) 1 else 0
            latch.countDown()
        }

        val listener = testRun.addListener { }

        try {
            latch.await()
        } finally {
            listener()
        }

        System.exit(exitCode)
    }
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "dev" -> Build.dev()
        "test" -> Build.test()
        else -> {
            println("Usage: Build.kt <command>")
            println()
            println("Commands:")
            println("  dev   - Start development server with hot reload")
            println("  test  - Run tests once and exit")
        }
    }
}
