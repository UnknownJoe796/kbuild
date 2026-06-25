package com.ivieleague.kbuild.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Swift Package Manager Package.swift generation.
 */
class SwiftPackageTest {

    @Test
    fun `SwiftPackage generates with correct name`() {
        val pkg = SwiftPackage(
            name = "MyFramework",
            platforms = listOf(SwiftPackage.Platform.iOS("14.0")),
            products = listOf(SwiftPackage.Product.Library("MyFramework", listOf("MyFramework"))),
            targets = listOf(SwiftPackage.Target.BinaryTarget("MyFramework", "MyFramework.xcframework"))
        )
        val content = pkg.generate()

        assertTrue(content.contains("name: \"MyFramework\""))
    }

    @Test
    fun `SwiftPackage includes swift-tools-version`() {
        val pkg = SwiftPackage(
            name = "Test",
            swiftToolsVersion = "5.9"
        )
        val content = pkg.generate()

        assertTrue(content.contains("// swift-tools-version: 5.9"))
    }

    @Test
    fun `SwiftPackage includes iOS platform`() {
        val pkg = SwiftPackage(
            name = "Test",
            platforms = listOf(SwiftPackage.Platform.iOS("15.0"))
        )
        val content = pkg.generate()

        assertTrue(content.contains(".iOS(.v15)"))
    }

    @Test
    fun `SwiftPackage includes multiple platforms`() {
        val pkg = SwiftPackage(
            name = "Test",
            platforms = listOf(
                SwiftPackage.Platform.iOS("14.0"),
                SwiftPackage.Platform.macOS("12.0")
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".iOS(.v14)"))
        assertTrue(content.contains(".macOS(.v12)"))
    }

    @Test
    fun `SwiftPackage includes library product`() {
        val pkg = SwiftPackage(
            name = "MyLib",
            products = listOf(SwiftPackage.Product.Library("MyLib", listOf("MyLib")))
        )
        val content = pkg.generate()

        assertTrue(content.contains(".library(name: \"MyLib\""))
        assertTrue(content.contains("targets: [\"MyLib\"]"))
    }

    @Test
    fun `SwiftPackage includes binary target`() {
        val pkg = SwiftPackage(
            name = "MyLib",
            targets = listOf(SwiftPackage.Target.BinaryTarget("MyLib", "MyLib.xcframework"))
        )
        val content = pkg.generate()

        assertTrue(content.contains(".binaryTarget(name: \"MyLib\", path: \"MyLib.xcframework\")"))
    }

    @Test
    fun `SwiftPackage includes remote binary target`() {
        val pkg = SwiftPackage(
            name = "MyLib",
            targets = listOf(
                SwiftPackage.Target.RemoteBinaryTarget(
                    "MyLib",
                    "https://example.com/MyLib.xcframework.zip",
                    "abc123checksum"
                )
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".binaryTarget(name: \"MyLib\""))
        assertTrue(content.contains("url: \"https://example.com/MyLib.xcframework.zip\""))
        assertTrue(content.contains("checksum: \"abc123checksum\""))
    }

    @Test
    fun `SwiftPackage includes GitHub dependency`() {
        val pkg = SwiftPackage(
            name = "MyApp",
            dependencies = listOf(
                SwiftPackage.Dependency.github("apple", "swift-log", "1.4.0")
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".package(url: \"https://github.com/apple/swift-log.git\""))
        assertTrue(content.contains("from: \"1.4.0\""))
    }

    @Test
    fun `SwiftPackage includes local dependency`() {
        val pkg = SwiftPackage(
            name = "MyApp",
            dependencies = listOf(
                SwiftPackage.Dependency.Local("../MyLocalPackage")
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".package(path: \"../MyLocalPackage\")"))
    }

    @Test
    fun `SwiftPackage forKotlinFramework creates correct structure`() {
        val pkg = SwiftPackage.forKotlinFramework(
            name = "SharedLib",
            xcframeworkPath = "SharedLib.xcframework",
            iosVersion = "15.0"
        )
        val content = pkg.generate()

        assertTrue(content.contains("name: \"SharedLib\""))
        assertTrue(content.contains(".iOS(.v15)"))
        assertTrue(content.contains(".library(name: \"SharedLib\""))
        assertTrue(content.contains(".binaryTarget(name: \"SharedLib\", path: \"SharedLib.xcframework\")"))
    }

    @Test
    fun `SwiftPackage forKotlinFramework includes macOS when specified`() {
        val pkg = SwiftPackage.forKotlinFramework(
            name = "SharedLib",
            iosVersion = "14.0",
            macosVersion = "12.0"
        )
        val content = pkg.generate()

        assertTrue(content.contains(".iOS(.v14)"))
        assertTrue(content.contains(".macOS(.v12)"))
    }

    @Test
    fun `SwiftPackage forRemoteKotlinFramework creates correct structure`() {
        val pkg = SwiftPackage.forRemoteKotlinFramework(
            name = "RemoteLib",
            url = "https://example.com/RemoteLib-1.0.0.xcframework.zip",
            checksum = "sha256checksum",
            iosVersion = "14.0"
        )
        val content = pkg.generate()

        assertTrue(content.contains("name: \"RemoteLib\""))
        assertTrue(content.contains("url: \"https://example.com/RemoteLib-1.0.0.xcframework.zip\""))
        assertTrue(content.contains("checksum: \"sha256checksum\""))
    }

    @Test
    fun `SwiftPackage writes to file correctly`() {
        val tempDir = File("build/run/SwiftPackageWriteTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val pkg = SwiftPackage.forKotlinFramework(
            name = "WriteTest",
            iosVersion = "14.0"
        )
        val file = pkg.writeTo(tempDir)

        assertEquals("Package.swift", file.name)
        assertTrue(file.exists())

        val content = file.readText()
        assertTrue(content.contains("name: \"WriteTest\""))
        assertTrue(content.contains("// swift-tools-version:"))
    }

    @Test
    fun `Platform parse works correctly`() {
        val ios = SwiftPackage.Platform.parse("iOS 15.0")
        assertTrue(ios.declaration.contains("iOS"))

        val macos = SwiftPackage.Platform.parse("macOS 12.0")
        assertTrue(macos.declaration.contains("macOS"))
    }

    @Test
    fun `SwiftPackage includes source target`() {
        val pkg = SwiftPackage(
            name = "MyLib",
            targets = listOf(
                SwiftPackage.Target.target("MyWrapper", listOf("MyBinary"))
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".target(name: \"MyWrapper\""))
        assertTrue(content.contains("dependencies: [\"MyBinary\"]"))
    }

    @Test
    fun `SwiftPackage includes test target`() {
        val pkg = SwiftPackage(
            name = "MyLib",
            targets = listOf(
                SwiftPackage.Target.testTarget("MyLibTests", listOf("MyLib"))
            )
        )
        val content = pkg.generate()

        assertTrue(content.contains(".testTarget(name: \"MyLibTests\""))
        assertTrue(content.contains("dependencies: [\"MyLib\"]"))
    }

    @Test
    fun `SwiftPackageDistribution prepareLocal creates correct package`() {
        val tempDir = File("build/run/SwiftPackageDistributionTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        // Create a mock xcframework directory
        val xcframeworkDir = tempDir.resolve("Test.xcframework")
        xcframeworkDir.mkdirs()
        xcframeworkDir.resolve("Info.plist").writeText("<plist></plist>")

        val distribution = SwiftPackageDistribution(
            name = "Test",
            version = "1.0.0",
            xcframework = xcframeworkDir,
            outputDir = tempDir,
            iosVersion = "14.0"
        )

        val pkg = distribution.prepareLocal()
        val content = pkg.generate()

        assertTrue(content.contains("name: \"Test\""))
        assertTrue(content.contains("path: \"Test.xcframework\""))
    }

    @Test
    fun `checksum calculation works`() {
        val tempDir = File("build/run/ChecksumTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val testFile = tempDir.resolve("test.txt")
        testFile.writeText("Hello, World!")

        val checksum = SwiftPackage.calculateChecksum(testFile)

        // SHA256 of "Hello, World!" is known
        assertEquals(64, checksum.length) // SHA256 produces 64 hex chars
        assertTrue(checksum.matches(Regex("[a-f0-9]+")))
    }
}
