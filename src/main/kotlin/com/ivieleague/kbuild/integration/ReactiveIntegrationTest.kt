package com.ivieleague.kbuild.integration

import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.junit.ReactiveJUnitRun
import com.ivieleague.kbuild.junit.TestRunSummary
import com.ivieleague.kbuild.server.ReactiveServerProcess
import com.ivieleague.kbuild.server.ServerState
import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * State of an integration test run.
 */
sealed class IntegrationTestState {
    /** Waiting for servers to be ready. */
    data class WaitingForServers(val pendingServers: Set<String>) : IntegrationTestState()

    /** Servers ready, tests running. */
    object RunningTests : IntegrationTestState()

    /** Tests completed successfully. */
    data class Completed(val summary: TestRunSummary) : IntegrationTestState()

    /** Test execution failed. */
    data class Failed(val error: Throwable) : IntegrationTestState()
}

/**
 * Coordinates integration tests with one or more servers.
 *
 * This class:
 * - Waits for all servers to be healthy before running tests
 * - Reruns tests automatically when any server restarts
 * - Provides reactive state for UI/coordination
 *
 * Usage:
 * ```
 * val integration = ReactiveIntegrationTest(
 *     servers = mapOf("api" to apiServer, "worker" to workerServer),
 *     testRun = reactiveTestRun
 * )
 *
 * integration.onTestRunComplete = { summary ->
 *     println("Integration tests: ${summary.passed}/${summary.total} passed")
 * }
 *
 * // Add listener to activate
 * val remove = integration.addListener { state ->
 *     when (val s = state.getOrNull()) {
 *         is IntegrationTestState.Completed -> println("All tests passed!")
 *         is IntegrationTestState.Failed -> println("Tests failed: ${s.error}")
 *         else -> {}
 *     }
 * }
 * ```
 *
 * @param servers Map of server name to ReactiveServerProcess
 * @param testRun The reactive test runner to execute
 * @param retryDelayMs Delay between server health check retries
 * @param maxWaitForServersMs Maximum time to wait for servers to be ready
 */
class ReactiveIntegrationTest(
    val servers: Map<String, ReactiveServerProcess>,
    val testRun: ReactiveJUnitRun,
    val retryDelayMs: Long = 500,
    val maxWaitForServersMs: Long = 60_000
) : BaseReactive<IntegrationTestState>() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var serverListenerRemovers: List<() -> Unit> = emptyList()
    private var testListenerRemover: (() -> Unit)? = null
    private var lastServerStates: Map<String, ServerState> = emptyMap()

    /**
     * Callback when integration test run completes.
     */
    var onTestRunComplete: ((TestRunSummary) -> Unit)? = null

    /**
     * Callback when servers become ready.
     */
    var onServersReady: (() -> Unit)? = null

    /**
     * Callback when waiting for servers.
     */
    var onWaitingForServers: ((Set<String>) -> Unit)? = null

    init {
        state = ReactiveState(IntegrationTestState.WaitingForServers(servers.keys))
    }

    override fun activate() {
        // Subscribe to all servers
        serverListenerRemovers = servers.map { (name, server) ->
            server.addListener { onServerStateChanged(name) }
        }

        // Subscribe to test results
        testListenerRemover = testRun.addListener { onTestStateChanged() }

        // Check initial state
        checkServersAndRun()
    }

    override fun deactivate() {
        serverListenerRemovers.forEach { it.invoke() }
        serverListenerRemovers = emptyList()
        testListenerRemover?.invoke()
        testListenerRemover = null
    }

    private fun onServerStateChanged(serverName: String) {
        val currentStates = servers.mapValues { it.value.state.getOrNull() ?: ServerState.Stopped }

        // Detect if a server restarted (was Running, now Running again after Restarting)
        val previousState = lastServerStates[serverName]
        val currentState = currentStates[serverName]

        if (previousState is ServerState.Restarting && currentState is ServerState.Running) {
            // Server just finished restarting - trigger test rerun
            lastServerStates = currentStates
            checkServersAndRun()
            return
        }

        lastServerStates = currentStates
        checkServersAndRun()
    }

    private fun onTestStateChanged() {
        val testState = testRun.state

        if (testState.ready && testState.success) {
            val results = testState.getOrNull()
            if (results != null) {
                val summary = TestRunSummary(
                    total = results.size,
                    passed = results.count { it.passed },
                    failed = results.count { !it.passed },
                    results = results
                )
                state = ReactiveState(IntegrationTestState.Completed(summary))
                onTestRunComplete?.invoke(summary)
            }
        } else if (testState.exception != null) {
            state = ReactiveState(IntegrationTestState.Failed(testState.exception!!))
        }
    }

    private fun checkServersAndRun() {
        val notReadyServers = servers.filter { (_, server) ->
            val serverState = server.state.getOrNull()
            serverState !is ServerState.Running
        }.keys

        if (notReadyServers.isNotEmpty()) {
            state = ReactiveState(IntegrationTestState.WaitingForServers(notReadyServers))
            onWaitingForServers?.invoke(notReadyServers)
            return
        }

        // All servers ready - tests will run via ReactiveJUnitRun's own reactivity
        state = ReactiveState(IntegrationTestState.RunningTests)
        onServersReady?.invoke()
    }

    /**
     * Start all servers and wait for them to be ready.
     *
     * @param waitForReady Block until all servers are ready
     */
    fun startServers(waitForReady: Boolean = true) {
        servers.values.forEach { it.start(waitForReady = false) }

        if (waitForReady) {
            scope.launch {
                waitForAllServersReady()
            }
        }
    }

    /**
     * Stop all servers.
     */
    fun stopServers() {
        servers.values.forEach { it.stop() }
    }

    /**
     * Wait for all servers to be in Running state.
     *
     * @param timeoutMs Maximum time to wait
     * @return true if all servers are ready, false if timeout
     */
    suspend fun waitForAllServersReady(timeoutMs: Long = maxWaitForServersMs): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val allRunning = servers.values.all { server ->
                server.state.getOrNull() is ServerState.Running
            }

            if (allRunning) {
                return true
            }

            // Check for failed servers
            val failedServer = servers.entries.find { (_, server) ->
                server.state.getOrNull() is ServerState.Failed
            }
            if (failedServer != null) {
                val failure = failedServer.value.state.getOrNull() as ServerState.Failed
                throw IllegalStateException(
                    "Server '${failedServer.key}' failed to start: ${failure.error.message}",
                    failure.error
                )
            }

            delay(retryDelayMs)
        }

        return false
    }

    /**
     * Get current server states.
     */
    val serverStates: Map<String, ServerState>
        get() = servers.mapValues { it.value.state.getOrNull() ?: ServerState.Stopped }

    /**
     * Check if all servers are currently running.
     */
    val allServersRunning: Boolean
        get() = servers.values.all { it.state.getOrNull() is ServerState.Running }
}

/**
 * Create a reactive integration test coordinator.
 */
fun reactiveIntegrationTest(
    servers: Map<String, ReactiveServerProcess>,
    testRun: ReactiveJUnitRun,
    retryDelayMs: Long = 500,
    maxWaitForServersMs: Long = 60_000
): ReactiveIntegrationTest = ReactiveIntegrationTest(
    servers = servers,
    testRun = testRun,
    retryDelayMs = retryDelayMs,
    maxWaitForServersMs = maxWaitForServersMs
)

/**
 * Create a reactive integration test with a single server.
 */
fun reactiveIntegrationTest(
    server: ReactiveServerProcess,
    serverName: String = "server",
    testRun: ReactiveJUnitRun
): ReactiveIntegrationTest = ReactiveIntegrationTest(
    servers = mapOf(serverName to server),
    testRun = testRun
)
