package com.ivieleague.kbuild.server

import com.lightningkite.reactive.core.BaseReactive
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState
import java.io.File

/**
 * Server state representation.
 */
sealed class ServerState {
    /** Server is not running and not attempting to start. */
    object Stopped : ServerState()

    /** Server is starting up, waiting for health check. */
    object Starting : ServerState()

    /** Server is running and healthy. */
    data class Running(val port: Int?, val pid: Long?) : ServerState()

    /** Server is restarting due to code changes. */
    object Restarting : ServerState()

    /** Server failed to start or crashed. */
    data class Failed(val error: Throwable) : ServerState()
}

/**
 * A reactive server process that automatically restarts when dependencies change.
 *
 * This integrates with the reactive build system:
 * - Monitors compilation output for changes
 * - Restarts server automatically on recompilation
 * - Provides reactive state for UI/coordination
 *
 * State transitions:
 * - Stopped -> Starting (on start)
 * - Starting -> Running (health check passes)
 * - Starting -> Failed (timeout or crash)
 * - Running -> Restarting (code changes detected)
 * - Restarting -> Running (restart successful)
 * - Any -> Stopped (on stop)
 *
 * @param mainClass The main class to run
 * @param compiledModules Reactive compilation outputs to watch
 * @param additionalClasspath Additional classpath entries (dependencies)
 * @param jvmArgs JVM arguments
 * @param programArgs Program arguments
 * @param workingDirectory Working directory
 * @param environment Environment variables
 * @param port Server port (for health checks)
 * @param healthCheckPath Health check endpoint
 * @param healthCheckTimeoutMs Health check timeout
 * @param shutdownTimeoutMs Graceful shutdown timeout
 * @param autoRestart Whether to automatically restart on changes
 */
class ReactiveServerProcess(
    val mainClass: String,
    val compiledModules: List<Reactive<File>>,
    val additionalClasspath: Reactive<Set<File>>,
    val jvmArgs: List<String> = emptyList(),
    val programArgs: List<String> = emptyList(),
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val port: Int? = null,
    val healthCheckPath: String = "/health",
    val healthCheckTimeoutMs: Long = 30_000,
    val shutdownTimeoutMs: Long = 10_000,
    val autoRestart: Boolean = true
) : BaseReactive<ServerState>() {

    private var serverProcess: ServerProcess? = null
    private var listenerRemovers: List<() -> Unit> = emptyList()
    private var isActivated = false
    private var lastClasspathHash: Int = 0

    /**
     * Callback for server output lines.
     */
    var onOutput: ((String) -> Unit)? = { println("[SERVER] $it") }

    /**
     * Callback for server error lines.
     */
    var onError: ((String) -> Unit)? = { System.err.println("[SERVER ERROR] $it") }

    /**
     * Callback when server becomes ready.
     */
    var onReady: (() -> Unit)? = null

    /**
     * Callback when server stops.
     */
    var onStopped: ((Int) -> Unit)? = null

    /**
     * Callback when restarting due to code changes.
     */
    var onRestarting: (() -> Unit)? = null

    init {
        state = ReactiveState(ServerState.Stopped)
    }

    override fun activate() {
        isActivated = true

        // Subscribe to all compiled modules
        listenerRemovers = buildList {
            compiledModules.forEach { module ->
                add(module.addListener { onDependencyChanged() })
            }
            add(additionalClasspath.addListener { onDependencyChanged() })
        }
    }

    override fun deactivate() {
        isActivated = false
        listenerRemovers.forEach { it.invoke() }
        listenerRemovers = emptyList()
        stopServer()
    }

    private fun onDependencyChanged() {
        if (!autoRestart) return

        // Check if all modules are ready
        val allReady = compiledModules.all { it.state.ready && it.state.success }
        val classpathReady = additionalClasspath.state.ready && additionalClasspath.state.success

        if (!allReady || !classpathReady) {
            // Wait for compilation to complete
            return
        }

        // Check if classpath actually changed
        val currentClasspath = buildClasspath()
        val currentHash = currentClasspath.hashCode()

        if (currentHash == lastClasspathHash && serverProcess?.isRunning == true) {
            // No actual changes
            return
        }

        lastClasspathHash = currentHash

        // Restart if running
        if (serverProcess?.isRunning == true) {
            restartServer()
        }
    }

    private fun buildClasspath(): List<File> = buildList {
        compiledModules.forEach { module ->
            module.state.getOrNull()?.let { add(it) }
        }
        additionalClasspath.state.getOrNull()?.let { addAll(it) }
    }

    /**
     * Start the server.
     *
     * @param waitForReady Block until server is ready
     */
    fun start(waitForReady: Boolean = true) {
        if (serverProcess?.isRunning == true) {
            return
        }

        // Check compilation state
        val anyFailed = compiledModules.any { it.state.exception != null }
        if (anyFailed) {
            val error = compiledModules.firstNotNullOfOrNull { it.state.exception }
                ?: Exception("Compilation failed")
            state = ReactiveState(ServerState.Failed(error))
            return
        }

        val anyNotReady = compiledModules.any { !it.state.ready }
        if (anyNotReady) {
            // Wait for compilation - will be triggered by listener
            state = ReactiveState(ServerState.Starting)
            return
        }

        val classpath = buildClasspath()
        if (classpath.isEmpty()) {
            state = ReactiveState(ServerState.Failed(IllegalStateException("Empty classpath")))
            return
        }

        lastClasspathHash = classpath.hashCode()
        state = ReactiveState(ServerState.Starting)

        val process = ServerProcess(
            mainClass = mainClass,
            classpath = classpath,
            jvmArgs = jvmArgs,
            programArgs = programArgs,
            workingDirectory = workingDirectory,
            environment = environment,
            port = port,
            healthCheckPath = healthCheckPath,
            healthCheckTimeoutMs = healthCheckTimeoutMs,
            shutdownTimeoutMs = shutdownTimeoutMs
        )

        process.onOutput = onOutput
        process.onError = onError
        process.onReady = {
            state = ReactiveState(ServerState.Running(port, getProcessId(process)))
            onReady?.invoke()
        }
        process.onStopped = { exitCode ->
            if (state.getOrNull() !is ServerState.Restarting) {
                state = ReactiveState(ServerState.Stopped)
            }
            onStopped?.invoke(exitCode)
        }

        serverProcess = process

        try {
            process.start(waitForReady)
            if (waitForReady) {
                state = ReactiveState(ServerState.Running(port, getProcessId(process)))
            }
        } catch (e: Exception) {
            state = ReactiveState(ServerState.Failed(e))
        }
    }

    /**
     * Stop the server.
     */
    fun stop() {
        stopServer()
        state = ReactiveState(ServerState.Stopped)
    }

    private fun stopServer() {
        serverProcess?.stop()
        serverProcess = null
    }

    /**
     * Restart the server.
     */
    fun restart() {
        restartServer()
    }

    private fun restartServer() {
        state = ReactiveState(ServerState.Restarting)
        onRestarting?.invoke()

        stopServer()

        val classpath = buildClasspath()
        if (classpath.isEmpty()) {
            state = ReactiveState(ServerState.Failed(IllegalStateException("Empty classpath after restart")))
            return
        }

        lastClasspathHash = classpath.hashCode()

        val process = ServerProcess(
            mainClass = mainClass,
            classpath = classpath,
            jvmArgs = jvmArgs,
            programArgs = programArgs,
            workingDirectory = workingDirectory,
            environment = environment,
            port = port,
            healthCheckPath = healthCheckPath,
            healthCheckTimeoutMs = healthCheckTimeoutMs,
            shutdownTimeoutMs = shutdownTimeoutMs
        )

        process.onOutput = onOutput
        process.onError = onError
        process.onReady = {
            state = ReactiveState(ServerState.Running(port, getProcessId(process)))
            onReady?.invoke()
        }
        process.onStopped = { exitCode ->
            if (state.getOrNull() !is ServerState.Restarting) {
                state = ReactiveState(ServerState.Stopped)
            }
            onStopped?.invoke(exitCode)
        }

        serverProcess = process

        try {
            process.start(waitForReady = true)
            state = ReactiveState(ServerState.Running(port, getProcessId(process)))
        } catch (e: Exception) {
            state = ReactiveState(ServerState.Failed(e))
        }
    }

    /**
     * Check if server is healthy.
     */
    fun checkHealth(): Boolean = serverProcess?.checkHealth() ?: false

    /**
     * Whether server is currently running.
     */
    val isRunning: Boolean
        get() = serverProcess?.isRunning ?: false

    private fun getProcessId(process: ServerProcess): Long? {
        // ProcessHandle was added in Java 9
        return try {
            ProcessHandle.current().pid()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Create a reactive server that auto-restarts on code changes.
 */
fun reactiveServer(
    mainClass: String,
    compiledModules: List<Reactive<File>>,
    additionalClasspath: Reactive<Set<File>>,
    port: Int? = null,
    jvmArgs: List<String> = emptyList(),
    programArgs: List<String> = emptyList(),
    healthCheckPath: String = "/health"
): ReactiveServerProcess = ReactiveServerProcess(
    mainClass = mainClass,
    compiledModules = compiledModules,
    additionalClasspath = additionalClasspath,
    jvmArgs = jvmArgs,
    programArgs = programArgs,
    port = port,
    healthCheckPath = healthCheckPath
)
