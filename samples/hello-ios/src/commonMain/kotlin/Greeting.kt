package com.example.helloios

/**
 * Shared greeting class that works across all platforms.
 */
class Greeting {
    private val platform: Platform = Platform()

    fun greet(): String {
        return "Hello from Kotlin/Native!\nRunning on: ${platform.name}"
    }

    fun platformInfo(): Map<String, String> = mapOf(
        "platform" to platform.name,
        "version" to "1.0.0",
        "kotlin" to "2.0.0"
    )
}
