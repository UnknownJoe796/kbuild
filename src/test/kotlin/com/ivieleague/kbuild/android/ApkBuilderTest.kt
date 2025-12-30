package com.ivieleague.kbuild.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for APK building tools.
 * These tests require Android SDK to be installed.
 */
class ApkBuilderTest {

    private fun skipIfNoSdk(): Boolean {
        if (!AndroidSdk.isAvailable()) {
            println("Skipping test: Android SDK not available")
            return true
        }
        return false
    }

    @Test
    fun `debugKeystore creates keystore file`() {
        val projectDir = File("build/run/ApkBuilderKeystoreTest")
        projectDir.deleteRecursively()
        projectDir.mkdirs()

        val keystore = ApkBuilder.debugKeystore(projectDir)

        assertTrue(keystore.file.exists())
        assertTrue(keystore.file.absolutePath.contains("debug.keystore"))
        assertTrue(keystore.storePassword.isNotEmpty())
        assertTrue(keystore.alias.isNotEmpty())
    }

    @Test
    fun `debugKeystore reuses existing keystore`() {
        val projectDir = File("build/run/ApkBuilderKeystoreReuseTest")
        projectDir.deleteRecursively()
        projectDir.mkdirs()

        val keystore1 = ApkBuilder.debugKeystore(projectDir)
        val modifiedTime1 = keystore1.file.lastModified()

        // Wait a bit to ensure different timestamp if recreated
        Thread.sleep(100)

        val keystore2 = ApkBuilder.debugKeystore(projectDir)
        val modifiedTime2 = keystore2.file.lastModified()

        // Should be the same file, not recreated
        assertTrue(keystore1.file == keystore2.file)
        assertTrue(modifiedTime1 == modifiedTime2)
    }

    @Test
    fun `ApkBuilder can be instantiated with SDK`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk)

        assertNotNull(builder)
        assertTrue(builder.targetSdk > 0)
    }

    @Test
    fun `ApkBuilder locates tools correctly`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()

        // These should not throw
        val aapt2 = sdk.aapt2()
        val d8 = sdk.d8()
        val zipalign = sdk.zipalign()
        val apksigner = sdk.apksigner()

        assertTrue(aapt2.exists(), "aapt2 should exist")
        assertTrue(d8.exists(), "d8 should exist")
        assertTrue(zipalign.exists(), "zipalign should exist")
        assertTrue(apksigner.exists(), "apksigner should exist")
    }

    @Test
    fun `compileResources handles empty directory`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk)

        val emptyResDir = File("build/run/ApkBuilderEmptyRes")
        emptyResDir.deleteRecursively()
        emptyResDir.mkdirs()

        val outputDir = File("build/run/ApkBuilderEmptyResOutput")
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val flatFiles = builder.compileResources(emptyResDir, outputDir)

        assertTrue(flatFiles.isEmpty())
    }

    @Test
    fun `compileResources handles non-existent directory`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk)

        val nonExistent = File("build/run/ApkBuilderNonExistent")
        nonExistent.deleteRecursively()

        val outputDir = File("build/run/ApkBuilderNonExistentOutput")

        val flatFiles = builder.compileResources(nonExistent, outputDir)

        assertTrue(flatFiles.isEmpty())
    }

    @Test
    fun `compileResources compiles valid resources`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk, verbose = true)

        // Create a test resource directory
        val resDir = File("build/run/ApkBuilderCompileResTest/res")
        resDir.deleteRecursively()
        resDir.mkdirs()

        // Create a values directory with strings.xml
        val valuesDir = resDir.resolve("values")
        valuesDir.mkdirs()
        valuesDir.resolve("strings.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Test App</string>
            </resources>
        """.trimIndent())

        val outputDir = File("build/run/ApkBuilderCompileResTest/output")
        outputDir.mkdirs()

        val flatFiles = builder.compileResources(resDir, outputDir)

        assertTrue(flatFiles.isNotEmpty(), "Should have compiled at least one resource")
        assertTrue(flatFiles.all { it.extension == "flat" }, "All outputs should be .flat files")
    }

    @Test
    fun `linkResources creates APK from resources`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk, verbose = true)

        val testDir = File("build/run/ApkBuilderLinkTest")
        testDir.deleteRecursively()
        testDir.mkdirs()

        // Create manifest
        val manifest = AndroidManifest.forSimpleApp(
            packageName = "com.example.linktest",
            appName = "Link Test"
        )
        val manifestFile = manifest.writeTo(testDir.resolve("AndroidManifest.xml"))

        // Create resources
        val resDir = testDir.resolve("res/values")
        resDir.mkdirs()
        resDir.resolve("strings.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Link Test</string>
            </resources>
        """.trimIndent())

        // Compile resources
        val flatDir = testDir.resolve("flat")
        val flatFiles = builder.compileResources(testDir.resolve("res"), flatDir)

        // Link resources
        val outputApk = testDir.resolve("output.apk")
        val result = builder.linkResources(
            flatFiles = flatFiles,
            manifest = manifestFile,
            outputApk = outputApk
        )

        assertTrue(result.exists(), "APK should be created")
        assertTrue(result.length() > 0, "APK should have content")
    }

    @Test
    fun `signature verification works`() {
        if (skipIfNoSdk()) return

        val sdk = AndroidSdk.get()
        val builder = ApkBuilder(sdk)

        // Create a simple test APK
        val testDir = File("build/run/ApkBuilderSignVerifyTest")
        testDir.deleteRecursively()
        testDir.mkdirs()

        // Create manifest
        val manifest = AndroidManifest.forSimpleApp(
            packageName = "com.example.signtest",
            appName = "Sign Test"
        )
        manifest.writeTo(testDir.resolve("AndroidManifest.xml"))

        // Create resources
        val resDir = testDir.resolve("res/values")
        resDir.mkdirs()
        resDir.resolve("strings.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Sign Test</string>
            </resources>
        """.trimIndent())

        // Build APK
        val flatFiles = builder.compileResources(testDir.resolve("res"), testDir.resolve("flat"))
        builder.linkResources(flatFiles, testDir.resolve("AndroidManifest.xml"), testDir.resolve("base.apk"))

        // Align
        val aligned = builder.align(testDir.resolve("base.apk"), testDir.resolve("aligned.apk"))

        // Sign
        val keystore = ApkBuilder.debugKeystore(testDir)
        val signed = builder.sign(aligned, keystore, testDir.resolve("signed.apk"))

        // Verify
        val verification = builder.verifySignature(signed)
        assertTrue(verification.valid, "Signed APK should verify: ${verification.output}")
    }
}
