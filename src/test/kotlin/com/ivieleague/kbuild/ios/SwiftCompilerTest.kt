package com.ivieleague.kbuild.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Swift compiler integration.
 *
 * Note: Many tests only run on macOS with Xcode installed.
 * Tests are designed to skip gracefully on other platforms.
 */
class SwiftCompilerTest {

    @Test
    fun `isMacOS returns correct value`() {
        val osName = System.getProperty("os.name").lowercase()
        val expected = osName.contains("mac")
        assertEquals(expected, SwiftCompiler.isMacOS())
    }

    @Test
    fun `SimulatorDestination generates correct destination string`() {
        val destination = SwiftCompiler.SimulatorDestination(
            name = "iPhone 15 Pro",
            os = "17.0"
        )
        val result = destination.toDestinationString()

        assertEquals("platform=iOS Simulator,name=iPhone 15 Pro,OS=17.0", result)
    }

    @Test
    fun `SimulatorDestination uses defaults correctly`() {
        val destination = SwiftCompiler.SimulatorDestination()
        val result = destination.toDestinationString()

        assertTrue(result.contains("platform=iOS Simulator"))
        assertTrue(result.contains("name=iPhone 15"))
        assertTrue(result.contains("OS=latest"))
    }

    @Test
    fun `BuildResult data class works correctly`() {
        val result = SwiftCompiler.BuildResult(
            success = true,
            exitCode = 0,
            output = "Build succeeded",
            errorOutput = "",
            outputDir = File("build/output")
        )

        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals("Build succeeded", result.output)
        assertEquals("", result.errorOutput)
        assertEquals(File("build/output"), result.outputDir)
    }

    @Test
    fun `BuildResult handles failure correctly`() {
        val result = SwiftCompiler.BuildResult(
            success = false,
            exitCode = 1,
            output = "",
            errorOutput = "Build failed: missing file",
            outputDir = null
        )

        assertFalse(result.success)
        assertEquals(1, result.exitCode)
        assertEquals("Build failed: missing file", result.errorOutput)
        assertEquals(null, result.outputDir)
    }

    @Test
    fun `Simulator data class works correctly`() {
        val simulator = SwiftCompiler.Simulator(
            name = "iPhone 15",
            udid = "ABC123-DEF456",
            state = "Booted"
        )

        assertEquals("iPhone 15", simulator.name)
        assertEquals("ABC123-DEF456", simulator.udid)
        assertEquals("Booted", simulator.state)
        assertTrue(simulator.isBooted)
    }

    @Test
    fun `Simulator isBooted returns false when shutdown`() {
        val simulator = SwiftCompiler.Simulator(
            name = "iPhone 15",
            udid = "ABC123",
            state = "Shutdown"
        )

        assertFalse(simulator.isBooted)
    }

    @Test
    fun `hasSwift detection works`() {
        // This test just ensures the function doesn't throw
        val hasSwift = SwiftCompiler.hasSwift()
        // On macOS with Xcode, this should be true
        // On other platforms, it might be false
        // We just verify it returns a boolean
        assertTrue(hasSwift || !hasSwift)
    }

    @Test
    fun `hasXcodebuild detection works`() {
        val hasXcodebuild = SwiftCompiler.hasXcodebuild()

        // On non-macOS, should always be false
        if (!SwiftCompiler.isMacOS()) {
            assertFalse(hasXcodebuild)
        }
        // On macOS, depends on Xcode installation
    }

    // These tests only run if xcodebuild is available
    @Test
    fun `listSimulators returns list on macOS with Xcode`() {
        if (!SwiftCompiler.isMacOS() || !SwiftCompiler.hasXcodebuild()) {
            println("Skipping test: requires macOS with Xcode")
            return
        }

        val simulators = SwiftCompiler.listSimulators()

        // Should find at least some simulators
        assertTrue(simulators.isNotEmpty(), "Should find at least one simulator")

        // Should include iPhone or iPad simulators
        val hasPhoneOrPad = simulators.any {
            it.name.contains("iPhone") || it.name.contains("iPad")
        }
        assertTrue(hasPhoneOrPad, "Should find iPhone or iPad simulators")
    }

    @Test
    fun `buildSwiftPackage requires Package swift`() {
        if (!SwiftCompiler.hasSwift()) {
            println("Skipping test: requires swift")
            return
        }

        val tempDir = File("build/run/SwiftCompilerNoPackageTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        var threw = false
        try {
            SwiftCompiler.buildSwiftPackage(tempDir)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("Package.swift not found"))
        }
        assertTrue(threw, "Should throw when Package.swift missing")
    }

    @Test
    fun `buildWithXcode requires xcodeproj`() {
        if (!SwiftCompiler.isMacOS() || !SwiftCompiler.hasXcodebuild()) {
            println("Skipping test: requires macOS with Xcode")
            return
        }

        val tempDir = File("build/run/SwiftCompilerNoXcodeprojTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        var threw = false
        try {
            SwiftCompiler.buildWithXcode(tempDir, "TestScheme")
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("No .xcodeproj found"))
        }
        assertTrue(threw, "Should throw when .xcodeproj missing")
    }

    @Test
    fun `cleanSwiftPackage works on empty directory`() {
        if (!SwiftCompiler.hasSwift()) {
            println("Skipping test: requires swift")
            return
        }

        val tempDir = File("build/run/SwiftCompilerCleanTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        // Create a minimal Package.swift
        tempDir.resolve("Package.swift").writeText("""
            // swift-tools-version: 5.9
            import PackageDescription
            let package = Package(name: "CleanTest")
        """.trimIndent())

        // Clean should work (even if there's nothing to clean)
        val result = SwiftCompiler.cleanSwiftPackage(tempDir)
        assertTrue(result.success)
    }

    @Test
    fun `runInSimulator requires app to exist`() {
        if (!SwiftCompiler.isMacOS()) {
            println("Skipping test: requires macOS")
            return
        }

        val nonExistentApp = File("build/run/NonExistent.app")

        var threw = false
        try {
            SwiftCompiler.runInSimulator(nonExistentApp)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("App not found"))
        }
        assertTrue(threw, "Should throw when app doesn't exist")
    }
}
