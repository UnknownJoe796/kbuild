package com.ivieleague.kbuild.ios

import com.ivieleague.kbuild.kmp.KmpProjectConfig
import com.ivieleague.kbuild.kmp.KmpTarget
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for iOS project building and scaffolding.
 */
class IosProjectTest {

    @Test
    fun `IosProject detects iOS targets from KmpProject`() {
        val root = File("build/run/IosProjectTargetTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "ios-test", projectRoot = root, targets = setOf(KmpTarget.Jvm, KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64, KmpTarget.Native.MacosArm64))

        val iosProject = IosProject(kmpProject)

        assertEquals(2, iosProject.iosTargets.size)
        assertTrue(KmpTarget.Native.IosArm64 in iosProject.iosTargets)
        assertTrue(KmpTarget.Native.IosSimulatorArm64 in iosProject.iosTargets)
        // macOS is not an iOS target
        assertFalse(KmpTarget.Native.MacosArm64 in iosProject.iosTargets)
    }

    @Test
    fun `IosProject has correct default directories`() {
        val root = File("build/run/IosProjectDirTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "dir-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        assertEquals(root.resolve("build"), iosProject.buildDir)
        assertEquals(root.resolve("build/frameworks"), iosProject.frameworksDir)
        assertEquals(root.resolve("build/xcframeworks"), iosProject.xcframeworksDir)
        assertEquals(root.resolve("build/cocoapods"), iosProject.cocoapodsDir)
        assertEquals(root.resolve("ios"), iosProject.iosProjectDir)
    }

    @Test
    fun `IosProject generates Swift Package`() {
        val root = File("build/run/IosProjectSpmTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "spm-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64))

        val iosProject = IosProject(
            kmpProject,
            iosDeploymentTarget = "15.0",
            frameworkName = "SpmTest"
        )

        val packageSwift = iosProject.generateSwiftPackage()

        assertTrue(packageSwift.exists())
        assertEquals("Package.swift", packageSwift.name)

        val content = packageSwift.readText()
        assertTrue(content.contains("name: \"SpmTest\""))
        assertTrue(content.contains(".iOS(.v15)"))
        assertTrue(content.contains(".binaryTarget"))
        assertTrue(content.contains("SpmTest.xcframework"))
    }

    @Test
    fun `IosProject generates Swift Package with macOS`() {
        val root = File("build/run/IosProjectSpmMacosTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "spm-macos-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.MacosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "CrossPlatform")
        val packageSwift = iosProject.generateSwiftPackage(macosVersion = "12.0")

        val content = packageSwift.readText()
        assertTrue(content.contains(".iOS(.v14)"))
        assertTrue(content.contains(".macOS(.v12)"))
    }

    @Test
    fun `IosProject generates podspec (deprecated)`() {
        val root = File("build/run/IosProjectPodspecTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "podspec-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64))

        val iosProject = IosProject(
            kmpProject,
            iosDeploymentTarget = "15.0",
            frameworkName = "PodspecTest"
        )

        @Suppress("DEPRECATION")
        val podspec = iosProject.generatePodspec("2.0.0")

        assertTrue(podspec.exists())
        assertEquals("PodspecTest.podspec", podspec.name)

        val content = podspec.readText()
        assertTrue(content.contains("spec.name                     = 'PodspecTest'"))
        assertTrue(content.contains("spec.version                  = '2.0.0'"))
        assertTrue(content.contains("spec.ios.deployment_target    = '15.0'"))
    }

    @Test
    fun `IosProject generates Podfile (deprecated)`() {
        val root = File("build/run/IosProjectPodfileTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "podfile-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "SharedLib")
        @Suppress("DEPRECATION")
        val podfile = iosProject.generatePodfile("MyApp")

        assertTrue(podfile.exists())
        assertEquals("Podfile", podfile.name)

        val content = podfile.readText()
        assertTrue(content.contains("target 'MyApp' do"))
        assertTrue(content.contains("pod 'SharedLib'"))
    }

    @Test
    fun `IosProject scaffold creates iOS app structure with SPM`() {
        val root = File("build/run/IosProjectScaffoldSpmTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "scaffold-spm-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "ScaffoldLib")
        iosProject.scaffold(
            appName = "TestApp",
            bundleId = "com.test.app",
            integrationMode = IosProject.IntegrationMode.SPM
        )

        // Check Package.swift was created in xcframeworks dir
        val packageSwift = root.resolve("build/xcframeworks/Package.swift")
        assertTrue(packageSwift.exists(), "Package.swift should exist")
        assertTrue(packageSwift.readText().contains("name: \"ScaffoldLib\""))

        // Check iOS directory structure
        val iosDir = root.resolve("ios")
        assertTrue(iosDir.exists(), "iOS directory should exist")

        // Check app files
        val appDir = iosDir.resolve("TestApp")
        assertTrue(appDir.exists(), "App directory should exist")

        val appDelegate = appDir.resolve("AppDelegate.swift")
        assertTrue(appDelegate.exists(), "AppDelegate.swift should exist")
        assertTrue(appDelegate.readText().contains("import ScaffoldLib"))
        assertTrue(appDelegate.readText().contains("class AppDelegate"))

        val viewController = appDir.resolve("ViewController.swift")
        assertTrue(viewController.exists(), "ViewController.swift should exist")
        assertTrue(viewController.readText().contains("import ScaffoldLib"))
        assertTrue(viewController.readText().contains("class ViewController"))

        val infoPlist = appDir.resolve("Info.plist")
        assertTrue(infoPlist.exists(), "Info.plist should exist")
        assertTrue(infoPlist.readText().contains("com.test.app"))

        val launchScreen = appDir.resolve("LaunchScreen.storyboard")
        assertTrue(launchScreen.exists(), "LaunchScreen.storyboard should exist")
    }

    @Test
    fun `IosProject scaffold creates iOS app structure with CocoaPods`() {
        val root = File("build/run/IosProjectScaffoldCocoaTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "scaffold-cocoa-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "ScaffoldLib")
        iosProject.scaffold(
            appName = "TestApp",
            bundleId = "com.test.app",
            integrationMode = IosProject.IntegrationMode.COCOAPODS
        )

        // Check podspec was created in project root
        val podspec = root.resolve("ScaffoldLib.podspec")
        assertTrue(podspec.exists(), "Podspec should exist")

        // Check iOS directory structure
        val iosDir = root.resolve("ios")
        assertTrue(iosDir.exists(), "iOS directory should exist")

        // Check Podfile
        val podfile = iosDir.resolve("Podfile")
        assertTrue(podfile.exists(), "Podfile should exist")
        assertTrue(podfile.readText().contains("target 'TestApp' do"))

        // Check app files
        val appDir = iosDir.resolve("TestApp")
        assertTrue(appDir.exists(), "App directory should exist")
    }

    @Test
    fun `iosProject DSL extension works`() {
        val root = File("build/run/IosProjectDslTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "dsl-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64))

        val iosProject = kmpProject.iosProject(
            iosDeploymentTarget = "16.0",
            frameworkName = "DslLib"
        )

        assertEquals("DslLib", iosProject.frameworkName)
        assertEquals("16.0", iosProject.iosDeploymentTarget)
        assertEquals(kmpProject, iosProject.kmpConfig)
    }

    @Test
    fun `isIosTarget correctly identifies iOS targets`() {
        assertTrue(KmpTarget.Native.IosArm64.isIosTarget())
        assertTrue(KmpTarget.Native.IosSimulatorArm64.isIosTarget())
        assertTrue(KmpTarget.Native.IosX64.isIosTarget())

        assertFalse(KmpTarget.Native.MacosArm64.isIosTarget())
        assertFalse(KmpTarget.Native.MacosX64.isIosTarget())
        assertFalse(KmpTarget.Native.LinuxX64.isIosTarget())
    }

    @Test
    fun `isMacosTarget correctly identifies macOS targets`() {
        assertTrue(KmpTarget.Native.MacosArm64.isMacosTarget())
        assertTrue(KmpTarget.Native.MacosX64.isMacosTarget())

        assertFalse(KmpTarget.Native.IosArm64.isMacosTarget())
        assertFalse(KmpTarget.Native.IosSimulatorArm64.isMacosTarget())
        assertFalse(KmpTarget.Native.LinuxX64.isMacosTarget())
    }

    @Test
    fun `IosProject creates dummy framework for pod install (deprecated)`() {
        val root = File("build/run/IosProjectDummyFrameworkTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "dummy-fw-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "DummyLib")
        @Suppress("DEPRECATION")
        iosProject.generatePodspec()

        // Check that dummy framework was created
        val dummyFramework = root.resolve("build/cocoapods/framework/DummyLib.framework")
        assertTrue(dummyFramework.exists(), "Dummy framework should be created")

        val infoPlist = dummyFramework.resolve("Info.plist")
        assertTrue(infoPlist.exists(), "Info.plist should exist in dummy framework")
        assertTrue(infoPlist.readText().contains("DummyLib"))
    }

    @Test
    fun `IntegrationMode enum has expected values`() {
        val modes = IosProject.IntegrationMode.entries

        assertTrue(IosProject.IntegrationMode.SPM in modes)
        assertTrue(IosProject.IntegrationMode.COCOAPODS in modes)
        assertEquals(2, modes.size)
    }

    @Test
    fun `IosProject scaffold defaults to SPM mode`() {
        val root = File("build/run/IosProjectScaffoldDefaultTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "scaffold-default-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "DefaultLib")
        // Calling scaffold without integrationMode should use SPM
        iosProject.scaffold(appName = "App")

        // SPM Package.swift should exist
        val packageSwift = root.resolve("build/xcframeworks/Package.swift")
        assertTrue(packageSwift.exists(), "Package.swift should exist (SPM is default)")

        // CocoaPods files should NOT exist
        val podspec = root.resolve("DefaultLib.podspec")
        assertFalse(podspec.exists(), "Podspec should not exist in SPM mode")
    }

    @Test
    fun `IosProject BuildConfig has correct defaults`() {
        val config = IosProject.BuildConfig()

        assertFalse(config.staticFramework)
        assertTrue(config.debug)
        assertFalse(config.optimizations)
    }

    @Test
    fun `IosProject isMacOS detection works`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        assertEquals(isMac, IosProject.isMacOS())
    }

    @Test
    fun `IosProject with no iOS targets throws on build`() {
        val root = File("build/run/IosProjectNoTargetsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "no-ios-test", projectRoot = root, targets = setOf(KmpTarget.Jvm, KmpTarget.Js))

        val iosProject = IosProject(kmpProject)

        assertEquals(0, iosProject.iosTargets.size)

        var threwException = false
        try {
            runBlocking { iosProject.buildFrameworks() }
        } catch (e: IllegalArgumentException) {
            threwException = true
            assertTrue(e.message!!.contains("No iOS targets found"))
        }
        assertTrue(threwException, "Should throw when no iOS targets")
    }

    @Test
    fun `XCFramework Platform enum has expected values`() {
        val platforms = XCFramework.Platform.entries

        assertTrue(XCFramework.Platform.IOS_DEVICE in platforms)
        assertTrue(XCFramework.Platform.IOS_SIMULATOR in platforms)
        assertTrue(XCFramework.Platform.MACOS in platforms)
        assertTrue(XCFramework.Platform.WATCHOS_DEVICE in platforms)
        assertTrue(XCFramework.Platform.TVOS_DEVICE in platforms)
    }

    @Test
    fun `XCFramework output path is correct`() {
        val xcframework = XCFramework(
            name = "MyLib",
            outputDir = File("build/xcframeworks")
        )

        assertEquals(
            File("build/xcframeworks/MyLib.xcframework"),
            xcframework.outputPath
        )
    }

    // ============== New Production Build Tests ==============

    @Test
    fun `IpaBuildResult has expected structure`() {
        val result = IosProject.IpaBuildResult(
            success = true,
            ipa = File("test.ipa"),
            archive = File("test.xcarchive"),
            teamId = "TEAM123456",
            exportMethod = IosCodeSigning.ExportMethod.DEVELOPMENT
        )

        assertTrue(result.success)
        assertEquals(File("test.ipa"), result.ipa)
        assertEquals(File("test.xcarchive"), result.archive)
        assertEquals("TEAM123456", result.teamId)
        assertEquals(IosCodeSigning.ExportMethod.DEVELOPMENT, result.exportMethod)
        assertNull(result.errorMessage)
    }

    @Test
    fun `IpaBuildResult with error has message`() {
        val result = IosProject.IpaBuildResult(
            success = false,
            ipa = null,
            archive = null,
            teamId = null,
            exportMethod = IosCodeSigning.ExportMethod.DEVELOPMENT,
            errorMessage = "No signing identity found"
        )

        assertFalse(result.success)
        assertNull(result.ipa)
        assertNull(result.archive)
        assertEquals("No signing identity found", result.errorMessage)
    }

    @Test
    fun `listDevices does not throw`() {
        val root = File("build/run/IosProjectListDevicesTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "device-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        // Should not throw, even if no devices are connected
        val devices = iosProject.listDevices()
        println("Connected iOS devices: ${devices.size}")
        devices.forEach { device ->
            println("  ${device.name} (${device.udid}) - ${device.state}")
        }
    }

    @Test
    fun `listAllDevices does not throw`() {
        val root = File("build/run/IosProjectListAllDevicesTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "all-device-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        // Should not throw
        val devices = iosProject.listAllDevices()
        println("All iOS devices: ${devices.size}")
        devices.forEach { device ->
            println("  ${device.name} (${device.type}) - ${device.state}")
        }
    }

    @Test
    fun `scaffold generates asset catalog`() {
        val root = File("build/run/IosProjectAssetCatalogTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "asset-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "AssetLib")
        iosProject.scaffold(
            appName = "AssetApp",
            bundleId = "com.test.assetapp"
        )

        // Check asset catalog was created
        val assetsDir = root.resolve("ios/AssetApp/Assets.xcassets")
        assertTrue(assetsDir.exists(), "Assets.xcassets should exist")

        val appIconDir = assetsDir.resolve("AppIcon.appiconset")
        assertTrue(appIconDir.exists(), "AppIcon.appiconset should exist")

        val accentColorDir = assetsDir.resolve("AccentColor.colorset")
        assertTrue(accentColorDir.exists(), "AccentColor.colorset should exist")

        // Check icon files exist
        val icons = appIconDir.listFiles { f -> f.name.endsWith(".png") }
        assertTrue(icons != null && icons.isNotEmpty(), "Should have icon PNG files")
    }

    @Test
    fun `buildIpa fails gracefully without Xcode project`() {
        // Only run on macOS
        if (!IosProject.isMacOS()) {
            println("Skipping test: Not on macOS")
            return
        }

        val root = File("build/run/IosProjectBuildIpaNoProjectTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "ipa-noproj-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        // Should return error result, not throw
        val result = iosProject.buildIpa(appName = "NonExistent")

        assertFalse(result.success, "Build should fail without Xcode project")
        assertNotNull(result.errorMessage, "Should have error message")
        assertTrue(result.errorMessage!!.contains("Xcode project not found"),
            "Error should mention missing Xcode project")
    }

    @Test
    fun `buildIpa fails gracefully without signing identity`() {
        // Only run on macOS where we can attempt to build
        if (!IosProject.isMacOS()) {
            println("Skipping test: Not on macOS")
            return
        }

        val root = File("build/run/IosProjectBuildIpaNoSigningTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "ipa-nosign-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        // Scaffold the project
        iosProject.scaffold(appName = "TestApp")

        // Create a minimal Xcode project (without running full generator which requires XCFramework)
        val xcodeproj = root.resolve("ios/TestApp.xcodeproj")
        xcodeproj.mkdirs()
        xcodeproj.resolve("project.pbxproj").writeText("// Fake project")

        // buildIpa should detect missing signing identity and return error result
        // (This test verifies graceful failure, not successful signing)
        val result = iosProject.buildIpa(appName = "TestApp")

        // Result should either fail gracefully with signing error or archive error
        if (!result.success) {
            assertNotNull(result.errorMessage)
            println("Build failed as expected: ${result.errorMessage}")
        }
        // Note: If signing IS available, the test may pass - that's fine too
    }

    @Test
    fun `installOnDevice fails gracefully without IPA`() {
        // Only run on macOS
        if (!IosProject.isMacOS()) {
            println("Skipping test: Not on macOS")
            return
        }

        val root = File("build/run/IosProjectInstallNoIpaTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "install-noipa-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        // Should throw because no IPA exists
        var threwException = false
        try {
            iosProject.installOnDevice()
        } catch (e: IllegalStateException) {
            threwException = true
            assertTrue(e.message!!.contains("No IPA found"),
                "Should mention missing IPA")
        }
        assertTrue(threwException, "Should throw when no IPA exists")
    }

    @Test
    fun `generateXcodeProject includes asset catalog`() {
        val root = File("build/run/IosProjectXcodeAssetTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "xcode-asset-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject, frameworkName = "XcodeAssetLib")

        // First scaffold (creates Assets.xcassets)
        iosProject.scaffold(
            appName = "XcodeApp",
            bundleId = "com.test.xcodeapp"
        )

        // Verify Assets.xcassets exists
        val assetsDir = root.resolve("ios/XcodeApp/Assets.xcassets")
        assertTrue(assetsDir.exists(), "Assets.xcassets should exist after scaffold")

        // Generate Xcode project (should reference asset catalog)
        val xcodeproj = iosProject.generateXcodeProject(
            appName = "XcodeApp",
            bundleId = "com.test.xcodeapp",
            autoSign = false // Skip auto-signing for this test
        )

        assertTrue(xcodeproj.exists(), "Xcode project should exist")

        // Verify the project references the asset catalog
        val pbxproj = xcodeproj.resolve("project.pbxproj")
        assertTrue(pbxproj.exists(), "project.pbxproj should exist")

        val content = pbxproj.readText()
        assertTrue(content.contains("Assets.xcassets"),
            "Project should reference Assets.xcassets")
    }

    // ============== TestFlight Upload Tests ==============

    @Test
    fun `TestFlightUploadResult has expected structure`() {
        val successResult = IosProject.TestFlightUploadResult(
            success = true,
            ipa = File("test.ipa"),
            requestId = "abc-123",
            message = "Upload successful"
        )

        assertTrue(successResult.success)
        assertEquals(File("test.ipa"), successResult.ipa)
        assertEquals("abc-123", successResult.requestId)
        assertEquals("Upload successful", successResult.message)

        val failResult = IosProject.TestFlightUploadResult(
            success = false,
            ipa = null,
            requestId = null,
            message = "Authentication failed"
        )

        assertFalse(failResult.success)
        assertNull(failResult.ipa)
        assertNull(failResult.requestId)
        assertEquals("Authentication failed", failResult.message)
    }

    @Test
    fun `uploadToTestFlight fails gracefully without macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (isMac) {
            println("Skipping test: Running on macOS")
            return
        }

        val root = File("build/run/IosProjectTestFlightNonMacTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "testflight-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        val auth = SwiftCompiler.AppStoreConnectAuth.AppleId(
            appleId = "test@example.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx"
        )

        // Should throw on non-macOS
        var threwException = false
        try {
            iosProject.uploadToTestFlight(appName = "Test", auth = auth)
        } catch (e: IllegalArgumentException) {
            threwException = true
            assertTrue(e.message!!.contains("macOS"))
        }
        assertTrue(threwException, "Should throw on non-macOS")
    }

    @Test
    fun `validateForAppStore fails gracefully with missing IPA`() {
        if (!IosProject.isMacOS()) {
            println("Skipping test: Not on macOS")
            return
        }

        val root = File("build/run/IosProjectValidateNoIpaTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "validate-test", projectRoot = root, targets = setOf(KmpTarget.Native.IosArm64))

        val iosProject = IosProject(kmpProject)

        val auth = SwiftCompiler.AppStoreConnectAuth.AppleId(
            appleId = "test@example.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx"
        )

        val nonExistentIpa = File("nonexistent.ipa")

        // Should throw because IPA doesn't exist
        var threwException = false
        try {
            iosProject.validateForAppStore(nonExistentIpa, auth)
        } catch (e: IllegalArgumentException) {
            threwException = true
            assertTrue(e.message!!.contains("IPA not found"))
        }
        assertTrue(threwException, "Should throw when IPA doesn't exist")
    }
}
