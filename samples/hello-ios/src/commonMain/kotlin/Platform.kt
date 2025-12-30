package com.example.helloios

/**
 * Platform-specific information.
 *
 * Each platform (iOS, Android, JVM, etc.) provides its own implementation.
 */
expect class Platform() {
    /**
     * Human-readable name of the current platform.
     */
    val name: String
}
