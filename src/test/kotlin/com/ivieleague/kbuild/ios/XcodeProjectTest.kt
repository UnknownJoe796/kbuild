package com.ivieleague.kbuild.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Xcode project generation.
 */
class XcodeProjectTest {

    @Test
    fun `XcodeProject generates with correct name`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        val content = project.generate()

        assertTrue(content.contains("TestApp"))
        assertTrue(content.contains("com.test.app"))
    }

    @Test
    fun `XcodeProject includes deployment target`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app",
            deploymentTarget = "15.0"
        )
        val content = project.generate()

        assertTrue(content.contains("IPHONEOS_DEPLOYMENT_TARGET = 15.0"))
    }

    @Test
    fun `XcodeProject includes Swift version`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app",
            swiftVersion = "5.9"
        )
        val content = project.generate()

        assertTrue(content.contains("SWIFT_VERSION = 5.9"))
    }

    @Test
    fun `XcodeProject adds Swift files`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addSwiftFile("AppDelegate.swift")
        project.addSwiftFile("ViewController.swift")

        val content = project.generate()

        assertTrue(content.contains("AppDelegate.swift"))
        assertTrue(content.contains("ViewController.swift"))
        assertTrue(content.contains("sourcecode.swift"))
    }

    @Test
    fun `XcodeProject adds storyboards`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addStoryboard("LaunchScreen.storyboard")

        val content = project.generate()

        assertTrue(content.contains("LaunchScreen.storyboard"))
        assertTrue(content.contains("file.storyboard"))
    }

    @Test
    fun `XcodeProject adds Info plist`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addInfoPlist("Info.plist")

        val content = project.generate()

        assertTrue(content.contains("Info.plist"))
        assertTrue(content.contains("text.plist.xml"))
    }

    @Test
    fun `XcodeProject adds asset catalogs`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addAssetCatalog("Assets.xcassets")

        val content = project.generate()

        assertTrue(content.contains("Assets.xcassets"))
        assertTrue(content.contains("folder.assetcatalog"))
    }

    @Test
    fun `XcodeProject generates valid pbxproj structure`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        val content = project.generate()

        // Check required sections
        assertTrue(content.contains("/* Begin PBXBuildFile section */"))
        assertTrue(content.contains("/* Begin PBXFileReference section */"))
        assertTrue(content.contains("/* Begin PBXGroup section */"))
        assertTrue(content.contains("/* Begin PBXNativeTarget section */"))
        assertTrue(content.contains("/* Begin PBXProject section */"))
        assertTrue(content.contains("/* Begin XCBuildConfiguration section */"))
        assertTrue(content.contains("/* Begin XCConfigurationList section */"))
    }

    @Test
    fun `XcodeProject includes Debug and Release configurations`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        val content = project.generate()

        assertTrue(content.contains("name = Debug;"))
        assertTrue(content.contains("name = Release;"))
    }

    @Test
    fun `XcodeProject writes to directory correctly`() {
        val tempDir = File("build/run/XcodeProjectWriteTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val project = XcodeProject(
            name = "WriteTest",
            bundleId = "com.test.writetest"
        )
        project.addSwiftFile("Main.swift")

        val xcodeproj = project.writeTo(tempDir)

        assertEquals("WriteTest.xcodeproj", xcodeproj.name)
        assertTrue(xcodeproj.exists())
        assertTrue(xcodeproj.isDirectory)

        val pbxproj = xcodeproj.resolve("project.pbxproj")
        assertTrue(pbxproj.exists())

        val content = pbxproj.readText()
        assertTrue(content.contains("WriteTest"))
        assertTrue(content.contains("Main.swift"))
    }

    @Test
    fun `XcodeProject forApp creates correct structure`() {
        val tempDir = File("build/run/XcodeProjectForAppTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        // Create app directory with files
        val appDir = tempDir.resolve("TestApp")
        appDir.mkdirs()
        appDir.resolve("AppDelegate.swift").writeText("// AppDelegate")
        appDir.resolve("ViewController.swift").writeText("// ViewController")
        appDir.resolve("Info.plist").writeText("<plist></plist>")
        appDir.resolve("LaunchScreen.storyboard").writeText("<xml/>")

        val project = XcodeProject.forApp(
            name = "TestApp",
            bundleId = "com.test.app",
            appDir = appDir
        )
        val content = project.generate()

        assertTrue(content.contains("AppDelegate.swift"))
        assertTrue(content.contains("ViewController.swift"))
        assertTrue(content.contains("Info.plist"))
        assertTrue(content.contains("LaunchScreen.storyboard"))
    }

    @Test
    fun `XcodeProject includes development team when specified`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app",
            developmentTeam = "ABC123XYZ"
        )
        val content = project.generate()

        assertTrue(content.contains("DEVELOPMENT_TEAM = ABC123XYZ"))
    }

    @Test
    fun `XcodeProject includes organization name`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app",
            organizationName = "My Company"
        )
        val content = project.generate()

        assertTrue(content.contains("ORGANIZATIONNAME = \"My Company\""))
    }

    @Test
    fun `XcodeProject generates unique IDs`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addSwiftFile("File1.swift")
        project.addSwiftFile("File2.swift")

        val content = project.generate()

        // IDs should be 24-character hex strings
        val idPattern = Regex("[A-F0-9]{24}")
        val matches = idPattern.findAll(content).toList()

        // Should have multiple unique IDs
        val uniqueIds = matches.map { it.value }.toSet()
        assertTrue(uniqueIds.size > 5, "Should have multiple unique IDs")
    }

    @Test
    fun `XcodeProject includes build phases`() {
        val project = XcodeProject(
            name = "TestApp",
            bundleId = "com.test.app"
        )
        project.addSwiftFile("Main.swift")
        project.addStoryboard("LaunchScreen.storyboard")

        val content = project.generate()

        assertTrue(content.contains("/* Begin PBXSourcesBuildPhase section */"))
        assertTrue(content.contains("/* Begin PBXResourcesBuildPhase section */"))
        assertTrue(content.contains("/* Begin PBXFrameworksBuildPhase section */"))
    }

    @Test
    fun `XcodeProject includes product reference`() {
        val project = XcodeProject(
            name = "MyApp",
            bundleId = "com.test.myapp"
        )
        val content = project.generate()

        assertTrue(content.contains("MyApp.app"))
        assertTrue(content.contains("wrapper.application"))
        assertTrue(content.contains("com.apple.product-type.application"))
    }
}
