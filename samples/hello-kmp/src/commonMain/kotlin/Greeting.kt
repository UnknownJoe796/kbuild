package com.example

/**
 * Common code shared across all platforms.
 */
class Greeting {
    private val platform = Platform()

    fun greet(): String = "Hello from ${platform.name}!"
}

/**
 * Platform-specific implementation.
 * Each platform provides its own implementation.
 */
expect class Platform() {
    val name: String
}
