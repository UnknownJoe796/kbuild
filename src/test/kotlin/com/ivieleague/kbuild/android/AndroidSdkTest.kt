package com.ivieleague.kbuild.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Android SDK detection and tool location.
 */
class AndroidSdkTest {

    @Test
    fun `invalid directory is not a valid SDK`() {
        val fakeDir = File("build/run/FakeAndroidSdk")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        assertFalse(AndroidSdk.isValidSdk(fakeDir))
    }

    @Test
    fun `directory with build-tools is valid SDK`() {
        val fakeDir = File("build/run/FakeSdkWithBuildTools")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        fakeDir.resolve("build-tools").mkdirs()

        assertTrue(AndroidSdk.isValidSdk(fakeDir))
    }

    @Test
    fun `directory with platforms is valid SDK`() {
        val fakeDir = File("build/run/FakeSdkWithPlatforms")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        fakeDir.resolve("platforms").mkdirs()

        assertTrue(AndroidSdk.isValidSdk(fakeDir))
    }

    @Test
    fun `build-tools versions are detected and sorted descending`() {
        val fakeDir = File("build/run/FakeSdkVersions")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        // Create fake build-tools versions
        fakeDir.resolve("build-tools/30.0.2").mkdirs()
        fakeDir.resolve("build-tools/33.0.0").mkdirs()
        fakeDir.resolve("build-tools/34.0.0").mkdirs()
        fakeDir.resolve("build-tools/31.0.0").mkdirs()

        val sdk = AndroidSdk.fromPath(fakeDir)

        assertEquals(listOf("34.0.0", "33.0.0", "31.0.0", "30.0.2"), sdk.buildToolsVersions)
        assertEquals("34.0.0", sdk.latestBuildToolsVersion)
    }

    @Test
    fun `platform versions are detected and sorted descending`() {
        val fakeDir = File("build/run/FakeSdkPlatforms")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        // Create fake platforms
        fakeDir.resolve("platforms/android-28").mkdirs()
        fakeDir.resolve("platforms/android-34").mkdirs()
        fakeDir.resolve("platforms/android-30").mkdirs()
        fakeDir.resolve("platforms/android-33").mkdirs()
        fakeDir.resolve("build-tools/34.0.0").mkdirs() // Need build-tools too

        val sdk = AndroidSdk.fromPath(fakeDir)

        assertEquals(listOf(34, 33, 30, 28), sdk.platformVersions)
        assertEquals(34, sdk.latestPlatformVersion)
    }

    @Test
    fun `SDK detection works if SDK is available`() {
        // This test will pass if Android SDK is installed, skip otherwise
        val sdk = AndroidSdk.find()

        if (sdk != null) {
            println("Found Android SDK at: ${sdk.sdkRoot}")
            println("Build-tools versions: ${sdk.buildToolsVersions}")
            println("Platform versions: ${sdk.platformVersions}")

            assertNotNull(sdk.latestBuildToolsVersion)
            assertNotNull(sdk.latestPlatformVersion)
        } else {
            println("Android SDK not found, skipping detection test")
        }
    }

    @Test
    fun `validate detects missing build-tools`() {
        val fakeDir = File("build/run/FakeSdkNoBuildTools")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        fakeDir.resolve("platforms/android-34").mkdirs()

        val sdk = AndroidSdk.fromPath(fakeDir)
        val result = sdk.validate(requireAdb = false)

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("build-tools") })
    }

    @Test
    fun `validate detects missing platforms`() {
        val fakeDir = File("build/run/FakeSdkNoPlatforms")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        fakeDir.resolve("build-tools/34.0.0").mkdirs()

        val sdk = AndroidSdk.fromPath(fakeDir)
        val result = sdk.validate(requireAdb = false)

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("platform") || it.contains("Platform") })
    }

    @Test
    fun `toString includes key info`() {
        val fakeDir = File("build/run/FakeSdkToString")
        fakeDir.deleteRecursively()
        fakeDir.mkdirs()

        fakeDir.resolve("build-tools/34.0.0").mkdirs()
        fakeDir.resolve("platforms/android-34").mkdirs()

        val sdk = AndroidSdk.fromPath(fakeDir)
        val str = sdk.toString()

        assertTrue(str.contains("AndroidSdk"))
        assertTrue(str.contains("34.0.0") || str.contains("34"))
    }
}
