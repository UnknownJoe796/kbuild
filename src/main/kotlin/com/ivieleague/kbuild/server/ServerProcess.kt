package com.ivieleague.kbuild.server

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Manages a JVM server process with lifecycle control.
 *
 * Features:
 * - Start/stop/restart the server
 * - Health check support (wait for server ready)
 * - Graceful shutdown (SIGTERM, wait, SIGKILL)
 * - Port management (check availability, detect conflicts)
 * - Output capture and streaming
 *
 * @param mainClass The main class to run
 * @param classpath Classpath JARs/directories
 * @param jvmArgs Additional JVM arguments
 * @param programArgs Program arguments
 * @param workingDirectory Working directory for the process
 * @param environment Additional environment variables
 * @param port The port the server listens on (for health checks)
 * @param healthCheckPath HTTP path to check for readiness (e.g., "/health")
 * @param healthCheckTimeoutMs Maximum time to wait for server to become ready
 * @param shutdownTimeoutMs Time to wait for graceful shutdown before force kill
 */
class ServerProcess(
    val mainClass: String,
    val classpath: List<File>,
    val jvmArgs: List<String> = emptyList(),
    val programArgs: List<String> = emptyList(),
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val port: Int? = null,
    val healthCheckPath: String = "/health",
    val healthCheckTimeoutMs: Long = 30_000,
    val shutdownTimeoutMs: Long = 10_000
) {
    private var process: Process? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null

    /**
     * Callback for stdout lines.
     */
    var onOutput: ((String) -> Unit)? = { println("[SERVER] $it") }

    /**
     * Callback for stderr lines.
     */
    var onError: ((String) -> Unit)? = { System.err.println("[SERVER ERROR] $it") }

    /**
     * Callback when server becomes ready (health check passes).
     */
    var onReady: (() -> Unit)? = null

    /**
     * Callback when server stops.
     */
    var onStopped: ((Int) -> Unit)? = null

    /**
     * Whether the server process is currently running.
     */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Start the server process.
     *
     * @param waitForReady If true, blocks until health check passes or timeout
     * @return true if started successfully (and ready if waitForReady=true)
     */
    fun start(waitForReady: Boolean = true): Boolean {
        if (isRunning) {
            return true
        }

        // Check port availability
        port?.let {
            if (!isPortAvailable(it)) {
                throw IllegalStateException("Port $it is already in use")
            }
        }

        val classpathString = classpath.joinToString(File.pathSeparator) { it.absolutePath }

        val command = buildList {
            add(findJava())
            addAll(jvmArgs)
            add("-cp")
            add(classpathString)
            add(mainClass)
            addAll(programArgs)
        }

        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(environment)

        process = builder.start()

        // Start output capture threads
        val proc = process!!
        outputThread = Thread({
            proc.inputStream.bufferedReader().forEachLine { line ->
                onOutput?.invoke(line)
            }
        }, "ServerProcess-stdout").apply {
            isDaemon = true
            start()
        }

        errorThread = Thread({
            proc.errorStream.bufferedReader().forEachLine { line ->
                onError?.invoke(line)
            }
        }, "ServerProcess-stderr").apply {
            isDaemon = true
            start()
        }

        // Monitor process exit
        Thread({
            val exitCode = proc.waitFor()
            onStopped?.invoke(exitCode)
        }, "ServerProcess-monitor").apply {
            isDaemon = true
            start()
        }

        if (waitForReady && port != null) {
            return waitForHealthy()
        }

        return true
    }

    /**
     * Stop the server process gracefully.
     *
     * Sends SIGTERM (or equivalent), waits for graceful shutdown,
     * then forces kill if needed.
     *
     * @return The exit code, or null if wasn't running
     */
    fun stop(): Int? {
        val proc = process ?: return null

        if (!proc.isAlive) {
            process = null
            return proc.exitValue()
        }

        // Request graceful shutdown
        proc.destroy()

        // Wait for graceful shutdown
        val exited = proc.waitFor(shutdownTimeoutMs, TimeUnit.MILLISECONDS)

        if (!exited) {
            // Force kill
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }

        process = null
        return if (proc.isAlive) null else proc.exitValue()
    }

    /**
     * Restart the server process.
     *
     * @param waitForReady If true, blocks until health check passes after restart
     * @return true if restarted successfully
     */
    fun restart(waitForReady: Boolean = true): Boolean {
        stop()
        return start(waitForReady)
    }

    /**
     * Wait for the server to pass health check.
     *
     * @param timeoutMs Maximum time to wait
     * @return true if healthy, false if timeout
     */
    fun waitForHealthy(timeoutMs: Long = healthCheckTimeoutMs): Boolean {
        if (port == null) {
            throw IllegalStateException("Cannot wait for healthy without a port configured")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastException: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            if (!isRunning) {
                throw IllegalStateException("Server process died while waiting for health check")
            }

            try {
                if (checkHealth()) {
                    onReady?.invoke()
                    return true
                }
            } catch (e: Exception) {
                lastException = e
            }

            Thread.sleep(100)
        }

        throw IllegalStateException(
            "Health check timed out after ${timeoutMs}ms",
            lastException
        )
    }

    /**
     * Check if the server is healthy.
     *
     * @return true if health check passes
     */
    fun checkHealth(): Boolean {
        if (port == null) return isRunning

        return try {
            val url = URL("http://localhost:$port$healthCheckPath")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wait for the process to exit.
     *
     * @param timeoutMs Maximum time to wait, or null for infinite
     * @return The exit code, or null if timeout
     */
    fun waitFor(timeoutMs: Long? = null): Int? {
        val proc = process ?: return null

        return if (timeoutMs != null) {
            if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.exitValue()
            } else {
                null
            }
        } else {
            proc.waitFor()
        }
    }

    companion object {
        /**
         * Check if a port is available.
         */
        fun isPortAvailable(port: Int): Boolean {
            return try {
                ServerSocket(port).use { true }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Find an available port.
         */
        fun findAvailablePort(startPort: Int = 8080, endPort: Int = 9000): Int {
            for (port in startPort..endPort) {
                if (isPortAvailable(port)) {
                    return port
                }
            }
            throw IllegalStateException("No available ports in range $startPort..$endPort")
        }

        /**
         * Find the Java executable.
         */
        fun findJava(): String {
            val javaHome = System.getProperty("java.home")
            val javaBin = File(javaHome, "bin/java")
            return if (javaBin.exists()) {
                javaBin.absolutePath
            } else {
                "java"
            }
        }
    }
}

/**
 * Configuration builder for ServerProcess.
 */
class ServerProcessBuilder {
    var mainClass: String = ""
    var classpath: List<File> = emptyList()
    var jvmArgs: MutableList<String> = mutableListOf()
    var programArgs: MutableList<String> = mutableListOf()
    var workingDirectory: File? = null
    var environment: MutableMap<String, String> = mutableMapOf()
    var port: Int? = null
    var healthCheckPath: String = "/health"
    var healthCheckTimeoutMs: Long = 30_000
    var shutdownTimeoutMs: Long = 10_000

    fun jvmArg(arg: String) { jvmArgs.add(arg) }
    fun programArg(arg: String) { programArgs.add(arg) }
    fun env(key: String, value: String) { environment[key] = value }

    fun build(): ServerProcess = ServerProcess(
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
}

/**
 * DSL for building a ServerProcess.
 */
fun serverProcess(block: ServerProcessBuilder.() -> Unit): ServerProcess {
    return ServerProcessBuilder().apply(block).build()
}
