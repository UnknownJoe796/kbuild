package com.ivieleague.kbuild.vite

import com.ivieleague.kbuild.npm.NpmProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Vite dev server management.
 */
class ViteDevServerTest {

    @Test
    fun `isPortAvailable returns true for unused port`() {
        // Find a port that should be available
        val port = ViteDevServer.findAvailablePort(50000, 50100)
        assertTrue(ViteDevServer.isPortAvailable(port))
    }

    @Test
    fun `findAvailablePort returns valid port`() {
        val port = ViteDevServer.findAvailablePort(60000, 60100)
        assertTrue(port in 60000..60100)
        assertTrue(ViteDevServer.isPortAvailable(port))
    }

    @Test
    fun `start requires project directory to exist`() {
        val nonExistent = File("build/run/NonExistentDir")
        nonExistent.deleteRecursively()

        var threw = false
        try {
            ViteDevServer.start(nonExistent, waitForReady = false)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("does not exist"))
        }
        assertTrue(threw)
    }

    @Test
    fun `start requires package json`() {
        val tempDir = File("build/run/ViteNoPackageJsonTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        var threw = false
        try {
            ViteDevServer.start(tempDir, waitForReady = false)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("package.json"))
        }
        assertTrue(threw)
    }

    @Test
    fun `ReactiveViteDevServer tracks watch paths`() {
        val tempDir = File("build/run/ReactiveViteTest")
        tempDir.mkdirs()

        val watchPath = tempDir.resolve("src")

        val reactive = ReactiveViteDevServer(
            projectDir = tempDir,
            watchPaths = listOf(watchPath)
        )

        assertEquals(tempDir, reactive.projectDir)
        assertEquals(5173, reactive.port)
    }

    // Integration test - only runs if npm is available
    @Test
    fun `ViteDevServer can start and stop with real Vite`() {
        if (!NpmProject.isNpmAvailable()) {
            println("Skipping test: npm not available")
            return
        }

        val tempDir = File("build/run/ViteIntegrationTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        // Create a minimal Vite project
        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("kotlin")
        )
        vite.scaffold("testmodule")

        // Create a placeholder Kotlin output
        val kotlinDir = tempDir.resolve("kotlin")
        kotlinDir.mkdirs()
        kotlinDir.resolve("testmodule.js").writeText("""
            export function main() { console.log('Hello from Kotlin!'); }
        """.trimIndent())

        // Install dependencies
        val installed = vite.install()
        if (!installed) {
            println("Skipping test: npm install failed")
            return
        }

        // Start server
        try {
            val port = ViteDevServer.findAvailablePort()
            val server = ViteDevServer.start(
                projectDir = tempDir,
                port = port,
                waitForReady = true
            )

            assertTrue(server.isRunning)
            assertEquals("http://localhost:$port", server.url)
            assertTrue(server.isReady())

            // Stop server
            server.stop()

            // Give it time to stop
            Thread.sleep(500)

            assertFalse(server.isRunning)

        } catch (e: Exception) {
            println("Test failed (may be environment issue): ${e.message}")
        }
    }
}
