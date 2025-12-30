package com.ivieleague.kbuild.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for iOS Podspec generation.
 */
class IosPodspecTest {

    @Test
    fun `podspec generates with correct name`() {
        val podspec = IosPodspec(
            name = "MyFramework",
            version = "1.0.0"
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.name                     = 'MyFramework'"))
        assertTrue(content.contains("spec.version                  = '1.0.0'"))
    }

    @Test
    fun `podspec includes iOS deployment target`() {
        val podspec = IosPodspec(
            name = "TestLib",
            iosDeploymentTarget = "15.0"
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.ios.deployment_target    = '15.0'"))
    }

    @Test
    fun `podspec includes framework path`() {
        val podspec = IosPodspec(
            name = "SharedLib",
            frameworkPath = "build/frameworks/SharedLib.framework"
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.vendored_frameworks      = 'build/frameworks/SharedLib.framework'"))
    }

    @Test
    fun `podspec includes libraries`() {
        val podspec = IosPodspec(
            name = "NativeLib",
            libraries = listOf("c++", "sqlite3")
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.libraries                = 'c++', 'sqlite3'"))
    }

    @Test
    fun `podspec includes framework existence check`() {
        val podspec = IosPodspec(
            name = "MyFramework",
            frameworkPath = "build/MyFramework.framework"
        )
        val content = podspec.generate()

        assertTrue(content.contains("if !Dir.exist?('build/MyFramework.framework')"))
        assertTrue(content.contains("Kotlin framework 'MyFramework' doesn't exist yet"))
    }

    @Test
    fun `podspec includes build script when enabled`() {
        val podspec = IosPodspec(
            name = "BuildableLib",
            buildScriptEnabled = true
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.script_phases = ["))
        assertTrue(content.contains(":name => 'Build BuildableLib'"))
        assertTrue(content.contains(":execution_position => :before_compile"))
    }

    @Test
    fun `podspec excludes build script when disabled`() {
        val podspec = IosPodspec(
            name = "StaticLib",
            buildScriptEnabled = false
        )
        val content = podspec.generate()

        assertTrue(!content.contains("script_phases"))
    }

    @Test
    fun `podspec includes custom build command`() {
        val customScript = """
            echo "Building framework"
            ./build.sh
        """.trimIndent()

        val podspec = IosPodspec(
            name = "CustomBuild",
            buildCommand = customScript
        )
        val content = podspec.generate()

        assertTrue(content.contains("echo \"Building framework\""))
        assertTrue(content.contains("./build.sh"))
    }

    @Test
    fun `podspec includes dependencies`() {
        val podspec = IosPodspec(
            name = "AppWithDeps",
            dependencies = listOf(
                IosPodspec.PodDependency("Alamofire", "5.0"),
                IosPodspec.PodDependency("SwiftJSON")
            )
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.dependency 'Alamofire', '5.0'"))
        assertTrue(content.contains("spec.dependency 'SwiftJSON'"))
    }

    @Test
    fun `podspec forKbuild creates correct build script`() {
        val podspec = IosPodspec.forKbuild(
            name = "KbuildApp",
            version = "2.0.0",
            iosDeploymentTarget = "16.0"
        )
        val content = podspec.generate()

        assertTrue(content.contains("spec.version                  = '2.0.0'"))
        assertTrue(content.contains("spec.ios.deployment_target    = '16.0'"))
        assertTrue(content.contains("case \"\$PLATFORM_NAME\""))
        assertTrue(content.contains("TARGET=\"iosArm64\""))
        assertTrue(content.contains("TARGET=\"iosSimulatorArm64\""))
    }

    @Test
    fun `podspec writes to file correctly`() {
        val tempDir = File("build/run/IosPodspecTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val podspec = IosPodspec(
            name = "FileTest",
            version = "1.2.3"
        )
        val file = podspec.writeTo(tempDir)

        assertEquals("FileTest.podspec", file.name)
        assertTrue(file.exists())

        val content = file.readText()
        assertTrue(content.contains("spec.name                     = 'FileTest'"))
        assertTrue(content.contains("spec.version                  = '1.2.3'"))
    }

    @Test
    fun `podfile generates with correct platform`() {
        val podfile = IosPodfile(
            platformVersion = "15.0",
            projectName = "MyApp"
        )
        val content = podfile.generate()

        assertTrue(content.contains("platform :ios, '15.0'"))
        assertTrue(content.contains("target 'MyApp' do"))
        assertTrue(content.contains("use_frameworks!"))
    }

    @Test
    fun `podfile includes local pods`() {
        val podfile = IosPodfile(
            projectName = "TestApp",
            localPods = listOf(
                IosPodfile.LocalPodReference("SharedLib", "../shared")
            )
        )
        val content = podfile.generate()

        assertTrue(content.contains("pod 'SharedLib', :path => '../shared'"))
    }

    @Test
    fun `podfile includes remote pods`() {
        val podfile = IosPodfile(
            projectName = "TestApp",
            pods = listOf(
                IosPodfile.PodReference("Alamofire", "5.4"),
                IosPodfile.PodReference("SwiftyJSON")
            )
        )
        val content = podfile.generate()

        assertTrue(content.contains("pod 'Alamofire', '5.4'"))
        assertTrue(content.contains("pod 'SwiftyJSON'"))
    }

    @Test
    fun `podfile forKotlinFramework creates correct structure`() {
        val podfile = IosPodfile.forKotlinFramework(
            projectName = "App",
            frameworkName = "Shared",
            frameworkPath = "../shared",
            platformVersion = "14.0"
        )
        val content = podfile.generate()

        assertTrue(content.contains("platform :ios, '14.0'"))
        assertTrue(content.contains("target 'App' do"))
        assertTrue(content.contains("pod 'Shared', :path => '../shared'"))
    }

    @Test
    fun `podfile writes to directory`() {
        val tempDir = File("build/run/IosPodfileTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val podfile = IosPodfile(
            projectName = "WriteTest"
        )
        val file = podfile.writeTo(tempDir)

        assertEquals("Podfile", file.name)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("target 'WriteTest' do"))
    }
}
