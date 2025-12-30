package com.ivieleague.kbuild.browser

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * A simple HTTP file server for serving test files to a browser.
 *
 * Uses the JDK built-in HttpServer to avoid external dependencies.
 *
 * @param root The root directory to serve files from
 * @param port Port to listen on (0 for auto-select)
 * @param host Host to bind to (default: localhost)
 */
class SimpleHttpServer(
    val root: File,
    val port: Int = 0,
    val host: String = "localhost"
) {
    private var server: HttpServer? = null

    /**
     * The actual port the server is listening on.
     * Only valid after start() is called.
     */
    val actualPort: Int
        get() = server?.address?.port ?: throw IllegalStateException("Server not started")

    /**
     * The base URL of the server.
     */
    val url: String
        get() = "http://$host:$actualPort"

    /**
     * Whether the server is currently running.
     */
    val isRunning: Boolean
        get() = server != null

    /**
     * Start the HTTP server.
     */
    fun start(): SimpleHttpServer {
        if (server != null) {
            throw IllegalStateException("Server already running")
        }

        val actualPort = if (port == 0) findAvailablePort() else port
        server = HttpServer.create(InetSocketAddress(host, actualPort), 0).apply {
            createContext("/") { exchange ->
                handleRequest(exchange)
            }
            executor = null // Use default executor
            start()
        }

        println("HTTP server started at $url")
        return this
    }

    /**
     * Stop the HTTP server.
     */
    fun stop() {
        server?.stop(0)
        server = null
        println("HTTP server stopped")
    }

    private fun handleRequest(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        val file = if (path.isEmpty()) {
            root.resolve("index.html")
        } else {
            root.resolve(path)
        }

        // Security: Ensure the resolved file is within the root directory
        if (!file.canonicalPath.startsWith(root.canonicalPath)) {
            sendError(exchange, 403, "Forbidden")
            return
        }

        if (!file.exists()) {
            sendError(exchange, 404, "Not Found: $path")
            return
        }

        if (file.isDirectory) {
            // Try index.html in directory
            val indexFile = file.resolve("index.html")
            if (indexFile.exists()) {
                serveFile(exchange, indexFile)
            } else {
                sendError(exchange, 403, "Directory listing not allowed")
            }
            return
        }

        serveFile(exchange, file)
    }

    private fun serveFile(exchange: HttpExchange, file: File) {
        val contentType = getContentType(file)
        val bytes = file.readBytes()

        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendError(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun getContentType(file: File): String {
        return when (file.extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    companion object {
        /**
         * Find an available port.
         */
        fun findAvailablePort(startPort: Int = 8000, endPort: Int = 9000): Int {
            for (port in startPort..endPort) {
                if (isPortAvailable(port)) {
                    return port
                }
            }
            throw RuntimeException("No available port found in range $startPort-$endPort")
        }

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
    }
}

/**
 * Create and start a simple HTTP server.
 */
fun simpleHttpServer(
    root: File,
    port: Int = 0,
    host: String = "localhost"
): SimpleHttpServer = SimpleHttpServer(root, port, host).start()
