package samples.reactiveserver

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Simple HTTP server for demonstrating reactive server management.
 *
 * Endpoints:
 * - GET /health - Health check endpoint
 * - GET /hello - Returns greeting
 * - GET /compute - Does some computation
 */
fun main() {
    val port = 8080
    val server = HttpServer.create(InetSocketAddress(port), 0)

    // Health check endpoint
    server.createContext("/health") { exchange ->
        val response = """{"status": "healthy"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    // Hello endpoint
    server.createContext("/hello") { exchange ->
        val name = exchange.requestURI.query?.substringAfter("name=") ?: "World"
        val response = greet(name)
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    // Compute endpoint
    server.createContext("/compute") { exchange ->
        val n = exchange.requestURI.query?.substringAfter("n=")?.toIntOrNull() ?: 10
        val result = fibonacci(n)
        val response = """{"n": $n, "result": $result}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    server.executor = null
    server.start()

    println("Server started on port $port")
    println("Endpoints:")
    println("  GET /health - Health check")
    println("  GET /hello?name=Name - Greeting")
    println("  GET /compute?n=10 - Fibonacci computation")

    // Keep server running
    Thread.currentThread().join()
}

/**
 * Generate a greeting message.
 */
fun greet(name: String): String {
    return "Hello, $name!"
}

/**
 * Compute the nth Fibonacci number.
 */
fun fibonacci(n: Int): Long {
    if (n <= 1) return n.toLong()
    var a = 0L
    var b = 1L
    repeat(n - 1) {
        val temp = a + b
        a = b
        b = temp
    }
    return b
}
