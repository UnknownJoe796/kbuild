package demo.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Simple HTTP server for hot reload demo.
 *
 * Edit the response message and watch it update automatically!
 */
fun main() {
    val port = 8080
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/") { exchange ->
        // Change this message and save - the server will restart with the new message!
        val response = """
            <!DOCTYPE html>
            <html>
            <head><title>Hot Reload Demo</title></head>
            <body>
                <h1>Hello from Hot Reload Demo!</h1>
                <p>Version: 1.0</p>
                <p>Edit Server.kt and save to see changes automatically.</p>
                <p>Server started at: ${java.time.LocalTime.now()}</p>
            </body>
            </html>
        """.trimIndent()

        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    server.createContext("/api/status") { exchange ->
        val response = """{"status": "ok", "message": "Server is running"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    server.executor = null
    server.start()

    println("Server started on http://localhost:$port")
    println("Press Ctrl+C to stop")

    // Keep running until interrupted
    Thread.currentThread().join()
}
