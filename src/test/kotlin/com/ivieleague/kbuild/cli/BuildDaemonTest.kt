package com.ivieleague.kbuild.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import kotlin.test.*

class BuildDaemonTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== DaemonRequest Serialization ====================

    @Test
    fun `DaemonRequest serializes correctly`() {
        val request = DaemonRequest(
            id = "req-1",
            command = "run",
            expression = "Build.compile"
        )

        val serialized = json.encodeToString(request)
        assertTrue(serialized.contains("\"id\":\"req-1\""))
        assertTrue(serialized.contains("\"command\":\"run\""))
        assertTrue(serialized.contains("\"expression\":\"Build.compile\""))
    }

    @Test
    fun `DaemonRequest deserializes correctly`() {
        val jsonStr = """{"id":"req-1","command":"run","expression":"Build.compile"}"""
        val request = json.decodeFromString<DaemonRequest>(jsonStr)

        assertEquals("req-1", request.id)
        assertEquals("run", request.command)
        assertEquals("Build.compile", request.expression)
    }

    @Test
    fun `DaemonRequest with null expression`() {
        val request = DaemonRequest(
            id = "req-1",
            command = "list"
        )

        assertNull(request.expression)

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<DaemonRequest>(serialized)

        assertNull(deserialized.expression)
    }

    @Test
    fun `DaemonRequest with jobId`() {
        val request = DaemonRequest(
            id = "req-1",
            command = "cancel",
            jobId = "job-123"
        )

        assertEquals("job-123", request.jobId)

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<DaemonRequest>(serialized)

        assertEquals("job-123", deserialized.jobId)
    }

    @Test
    fun `DaemonRequest ignores unknown fields`() {
        val jsonStr = """{"id":"req-1","command":"ping","unknownField":"value"}"""
        val request = json.decodeFromString<DaemonRequest>(jsonStr)

        assertEquals("req-1", request.id)
        assertEquals("ping", request.command)
    }

    // ==================== DaemonResponse Serialization ====================

    @Test
    fun `DaemonResponse serializes correctly`() {
        val response = DaemonResponse(
            id = "resp-1",
            status = "ok",
            value = "result"
        )

        val serialized = json.encodeToString(response)
        assertTrue(serialized.contains("\"id\":\"resp-1\""))
        assertTrue(serialized.contains("\"status\":\"ok\""))
        assertTrue(serialized.contains("\"value\":\"result\""))
    }

    @Test
    fun `DaemonResponse deserializes correctly`() {
        val jsonStr = """{"id":"resp-1","status":"ok","value":"result"}"""
        val response = json.decodeFromString<DaemonResponse>(jsonStr)

        assertEquals("resp-1", response.id)
        assertEquals("ok", response.status)
        assertEquals("result", response.value)
    }

    @Test
    fun `DaemonResponse with error`() {
        val response = DaemonResponse(
            id = "resp-1",
            status = "error",
            error = "Something went wrong"
        )

        assertEquals("error", response.status)
        assertEquals("Something went wrong", response.error)
        assertNull(response.value)
    }

    @Test
    fun `DaemonResponse with duration`() {
        val response = DaemonResponse(
            id = "resp-1",
            status = "ok",
            value = "done",
            durationMs = 150L
        )

        assertEquals(150L, response.durationMs)

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<DaemonResponse>(serialized)

        assertEquals(150L, deserialized.durationMs)
    }

    @Test
    fun `DaemonResponse with null fields`() {
        val response = DaemonResponse(
            id = "resp-1",
            status = "loading"
        )

        assertNull(response.value)
        assertNull(response.error)
        assertNull(response.durationMs)
    }

    @Test
    fun `DaemonResponse ignores unknown fields`() {
        val jsonStr = """{"id":"resp-1","status":"ok","extraField":123}"""
        val response = json.decodeFromString<DaemonResponse>(jsonStr)

        assertEquals("resp-1", response.id)
        assertEquals("ok", response.status)
    }

    // ==================== BuildDaemon.findAvailablePort ====================

    @Test
    fun `findAvailablePort returns valid port`() {
        val port = BuildDaemon.findAvailablePort()
        assertTrue(port > 0)
        assertTrue(port < 65536)
    }

    @Test
    fun `findAvailablePort returns different ports on subsequent calls`() {
        // Note: This test is probabilistic but should pass in practice
        val ports = (1..5).map { BuildDaemon.findAvailablePort() }.toSet()
        // At least some should be different (unless system is heavily loaded)
        assertTrue(ports.size >= 2, "Expected at least 2 different ports, got: $ports")
    }

    @Test
    fun `findAvailablePort returns usable port`() {
        val port = BuildDaemon.findAvailablePort()

        // Should be able to bind to this port
        val socket = ServerSocket(port)
        socket.close()
    }

    // ==================== BuildDaemon.tryConnect ====================

    @Test
    fun `tryConnect returns null when no daemon running`() {
        val tempDir = createTempDir()
        try {
            val result = BuildDaemon.tryConnect(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `tryConnect returns null when pid file missing`() {
        val tempDir = createTempDir()
        try {
            // No .kbuild-daemon.pid file exists
            val result = BuildDaemon.tryConnect(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `tryConnect cleans up stale pid file`() {
        val tempDir = createTempDir()
        try {
            // Create a stale pid file pointing to a port that's not listening
            val pidFile = File(tempDir, ".kbuild-daemon.pid")
            pidFile.writeText("65432:12345")  // Non-existent port and PID

            val result = BuildDaemon.tryConnect(tempDir)
            assertNull(result)

            // Pid file should be cleaned up
            assertFalse(pidFile.exists(), "Stale pid file should be deleted")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== Protocol Message Types ====================

    @Test
    fun `ping command request`() {
        val request = DaemonRequest(id = "1", command = "ping")
        assertEquals("ping", request.command)
    }

    @Test
    fun `run command request`() {
        val request = DaemonRequest(
            id = "1",
            command = "run",
            expression = "Build.compile"
        )
        assertEquals("run", request.command)
        assertEquals("Build.compile", request.expression)
    }

    @Test
    fun `watch command request`() {
        val request = DaemonRequest(
            id = "1",
            command = "watch",
            expression = "Build.compile"
        )
        assertEquals("watch", request.command)
    }

    @Test
    fun `list command request`() {
        val request = DaemonRequest(id = "1", command = "list")
        assertEquals("list", request.command)
    }

    @Test
    fun `cancel command request`() {
        val request = DaemonRequest(
            id = "1",
            command = "cancel",
            jobId = "watch-1"
        )
        assertEquals("cancel", request.command)
        assertEquals("watch-1", request.jobId)
    }

    @Test
    fun `stop command request`() {
        val request = DaemonRequest(id = "1", command = "stop")
        assertEquals("stop", request.command)
    }

    // ==================== Response Status Types ====================

    @Test
    fun `response status ok`() {
        val response = DaemonResponse(id = "1", status = "ok", value = "pong")
        assertEquals("ok", response.status)
    }

    @Test
    fun `response status error`() {
        val response = DaemonResponse(id = "1", status = "error", error = "Not found")
        assertEquals("error", response.status)
    }

    @Test
    fun `response status loading`() {
        val response = DaemonResponse(id = "1", status = "loading")
        assertEquals("loading", response.status)
    }

    @Test
    fun `response status watching`() {
        val response = DaemonResponse(id = "1", status = "watching", value = "Build.compile")
        assertEquals("watching", response.status)
    }

    // ==================== Round-trip Serialization ====================

    @Test
    fun `DaemonRequest round trip`() {
        val original = DaemonRequest(
            id = "test-123",
            command = "run",
            expression = "Build.test(\"pattern\")",
            jobId = "job-456"
        )

        val json = Json.encodeToString(original)
        val restored = Json.decodeFromString<DaemonRequest>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `DaemonResponse round trip`() {
        val original = DaemonResponse(
            id = "test-123",
            status = "ok",
            value = "Build completed successfully",
            error = null,
            durationMs = 1234L
        )

        val json = Json.encodeToString(original)
        val restored = Json.decodeFromString<DaemonResponse>(json)

        assertEquals(original, restored)
    }

    // ==================== Helper ====================

    private fun createTempDir(): File {
        return java.nio.file.Files.createTempDirectory("daemon-test").toFile()
    }
}
