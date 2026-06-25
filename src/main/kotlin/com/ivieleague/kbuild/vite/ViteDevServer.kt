package com.ivieleague.kbuild.vite

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Manages the Vite development server.
 *
 * The Vite dev server provides:
 * - Instant server start with native ES modules
 * - Hot Module Replacement (HMR) for instant updates
 * - Optimized dependency pre-bundling
 * - Rich error overlay
 *
 * Example usage:
 * ```kotlin
 * val server = ViteDevServer.start(projectDir)
 * println("Server running at: ${server.url}")
 *
 * // ... do development work ...
 *
 * server.stop()
 * ```
 */
class ViteDevServer private constructor(
    val projectDir: File,
    val port: Int,
    val host: String,
    private val process: Process
) {
    val url: String = "http://$host:$port"

    /**
     * Check if the server is running.
     */
    val isRunning: Boolean
        get() = process.isAlive

    /**
     * Wait for the server to be ready (responding to HTTP requests).
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if server is ready, false if timeout
     */
    fun waitForReady(timeoutMs: Long = 30000): Boolean {
        val startTime = System.currentTimeMillis()
        val checkInterval = 200L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (!process.isAlive) {
                println("Vite process exited unexpectedly")
                return false
            }

            if (isReady()) {
                return true
            }

            Thread.sleep(checkInterval)
        }

        return false
    }

    /**
     * Check if the server is ready to accept requests.
     */
    fun isReady(): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "HEAD"
            connection.responseCode in 200..399
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop the dev server.
     */
    fun stop() {
        if (!process.isAlive) return

        println("Stopping Vite dev server...")

        // Send SIGTERM for graceful shutdown
        process.destroy()

        // Wait for graceful shutdown
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            // Force kill if still running
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }

        println("Vite dev server stopped")
    }

    /**
     * Restart the dev server.
     */
    fun restart(): ViteDevServer {
        stop()
        return start(projectDir, port, host)
    }

    /**
     * Open the app in the default browser.
     */
    fun openInBrowser() {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("mac") -> arrayOf("open", url)
            os.contains("win") -> arrayOf("cmd", "/c", "start", url)
            else -> arrayOf("xdg-open", url)
        }

        try {
            ProcessBuilder(*command).start()
        } catch (e: Exception) {
            println("Could not open browser: ${e.message}")
        }
    }

    /**
     * The server output stream for logging.
     */
    val outputStream get() = process.inputStream

    /**
     * The server error stream.
     */
    val errorStream get() = process.errorStream

    companion object {
        /**
         * Start a Vite dev server.
         *
         * @param projectDir The Vite project directory
         * @param port The port to run on (default 5173)
         * @param host The host to bind to (default localhost)
         * @param waitForReady Whether to wait for the server to be ready
         * @return A ViteDevServer instance
         */
        fun start(
            projectDir: File,
            port: Int = 5173,
            host: String = "localhost",
            waitForReady: Boolean = true
        ): ViteDevServer {
            require(projectDir.exists()) { "Project directory does not exist: $projectDir" }

            val packageJson = projectDir.resolve("package.json")
            require(packageJson.exists()) { "package.json not found in $projectDir" }

            // Check if port is available
            if (!isPortAvailable(port)) {
                throw IllegalStateException("Port $port is already in use")
            }

            println("Starting Vite dev server on port $port...")

            // Use npx to run vite, or npm run dev
            val process = ProcessBuilder("npm", "run", "dev", "--", "--port", port.toString(), "--host", host)
                .directory(projectDir)
                .redirectErrorStream(false)
                .start()

            val server = ViteDevServer(projectDir, port, host, process)

            // Start output reader threads
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("[vite] $line")
                }
            }.apply {
                isDaemon = true
                start()
            }

            Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    System.err.println("[vite:err] $line")
                }
            }.apply {
                isDaemon = true
                start()
            }

            if (waitForReady) {
                if (server.waitForReady()) {
                    println("Vite dev server ready at: ${server.url}")
                } else {
                    server.stop()
                    throw RuntimeException("Vite dev server failed to start")
                }
            }

            return server
        }

        /**
         * Check if a port is available.
         */
        fun isPortAvailable(port: Int): Boolean {
            return try {
                java.net.ServerSocket(port).use { true }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Find an available port starting from the given port.
         */
        fun findAvailablePort(startPort: Int = 5173, maxPort: Int = 5273): Int {
            for (port in startPort..maxPort) {
                if (isPortAvailable(port)) {
                    return port
                }
            }
            throw RuntimeException("No available port found in range $startPort-$maxPort")
        }
    }
}

/**
 * Reactive Vite dev server that automatically restarts on configuration changes.
 */
class ReactiveViteDevServer(
    val projectDir: File,
    val port: Int = 5173,
    val host: String = "localhost",
    val watchPaths: List<File> = listOf(),
    val onRestart: (() -> Unit)? = null
) {
    private var server: ViteDevServer? = null
    private var watchThread: Thread? = null
    @Volatile private var running = false

    /**
     * Start the reactive dev server.
     */
    fun start(): ViteDevServer {
        if (server != null) {
            throw IllegalStateException("Server already running")
        }

        server = ViteDevServer.start(projectDir, port, host)
        running = true

        // Start file watcher if paths are specified
        if (watchPaths.isNotEmpty()) {
            startFileWatcher()
        }

        return server!!
    }

    private fun startFileWatcher() {
        val timestamps = mutableMapOf<File, Long>()

        // Initialize timestamps
        for (path in watchPaths) {
            if (path.exists()) {
                collectTimestamps(path, timestamps)
            }
        }

        watchThread = Thread {
            while (running) {
                try {
                    Thread.sleep(1000)

                    var changed = false
                    for (path in watchPaths) {
                        if (path.exists() && hasChanged(path, timestamps)) {
                            changed = true
                            break
                        }
                    }

                    if (changed && running) {
                        println("Configuration changed, restarting Vite...")
                        onRestart?.invoke()
                        server = server?.restart()

                        // Update timestamps
                        timestamps.clear()
                        for (path in watchPaths) {
                            if (path.exists()) {
                                collectTimestamps(path, timestamps)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "vite-file-watcher"
            start()
        }
    }

    private fun collectTimestamps(file: File, map: MutableMap<File, Long>) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { collectTimestamps(it, map) }
        } else {
            map[file] = file.lastModified()
        }
    }

    private fun hasChanged(file: File, timestamps: MutableMap<File, Long>): Boolean {
        if (file.isDirectory) {
            return file.listFiles()?.any { hasChanged(it, timestamps) } ?: false
        }

        val lastKnown = timestamps[file]
        val current = file.lastModified()

        if (lastKnown == null || current != lastKnown) {
            timestamps[file] = current
            return true
        }
        return false
    }

    /**
     * Stop the reactive dev server.
     */
    fun stop() {
        running = false
        watchThread?.interrupt()
        watchThread = null
        server?.stop()
        server = null
    }

    /**
     * The current server instance.
     */
    val currentServer: ViteDevServer? get() = server

    /**
     * Get the server URL.
     */
    val url: String?
        get() = server?.url
}
