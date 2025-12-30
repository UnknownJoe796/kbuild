package com.ivieleague.kbuild.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for AndroidManifest generation.
 */
class AndroidManifestTest {

    @Test
    fun `basic manifest generation`() {
        val manifest = AndroidManifest(
            packageName = "com.example.test",
            versionCode = 1,
            versionName = "1.0.0",
            minSdk = 24,
            targetSdk = 34
        )

        val xml = manifest.generate()

        assertTrue(xml.contains("package=\"com.example.test\""))
        assertTrue(xml.contains("android:versionCode=\"1\""))
        assertTrue(xml.contains("android:versionName=\"1.0.0\""))
        assertTrue(xml.contains("android:minSdkVersion=\"24\""))
        assertTrue(xml.contains("android:targetSdkVersion=\"34\""))
    }

    @Test
    fun `manifest with permissions`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addPermission(AndroidManifest.Permission.INTERNET)
        manifest.addPermission(AndroidManifest.Permission.CAMERA)
        manifest.addPermission(AndroidManifest.Permission.ACCESS_FINE_LOCATION)

        val xml = manifest.generate()

        assertTrue(xml.contains("android.permission.INTERNET"))
        assertTrue(xml.contains("android.permission.CAMERA"))
        assertTrue(xml.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `manifest with activity`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addActivity(
            name = ".MainActivity",
            exported = true,
            launchMode = AndroidManifest.LaunchMode.SINGLE_TOP
        ) {
            intentFilter {
                action(AndroidManifest.Intent.ACTION_MAIN)
                category(AndroidManifest.Intent.CATEGORY_LAUNCHER)
            }
        }

        val xml = manifest.generate()

        assertTrue(xml.contains("android:name=\".MainActivity\""))
        assertTrue(xml.contains("android:exported=\"true\""))
        assertTrue(xml.contains("android:launchMode=\"singleTop\""))
        assertTrue(xml.contains("android.intent.action.MAIN"))
        assertTrue(xml.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `manifest with service`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addService(
            name = ".MyService",
            exported = false,
            foregroundServiceType = "location"
        ) {
            intentFilter {
                action("com.example.MY_ACTION")
            }
        }

        val xml = manifest.generate()

        assertTrue(xml.contains("android:name=\".MyService\""))
        assertTrue(xml.contains("android:exported=\"false\""))
        assertTrue(xml.contains("android:foregroundServiceType=\"location\""))
        assertTrue(xml.contains("com.example.MY_ACTION"))
    }

    @Test
    fun `manifest with receiver`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addReceiver(
            name = ".BootReceiver",
            exported = true
        ) {
            intentFilter {
                action("android.intent.action.BOOT_COMPLETED")
            }
        }

        val xml = manifest.generate()

        assertTrue(xml.contains("android:name=\".BootReceiver\""))
        assertTrue(xml.contains("<receiver"))
        assertTrue(xml.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `manifest with content provider`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addProvider(
            name = ".MyProvider",
            authorities = "com.example.test.provider",
            exported = false,
            grantUriPermissions = true
        )

        val xml = manifest.generate()

        assertTrue(xml.contains("android:name=\".MyProvider\""))
        assertTrue(xml.contains("android:authorities=\"com.example.test.provider\""))
        assertTrue(xml.contains("android:grantUriPermissions=\"true\""))
    }

    @Test
    fun `manifest with application metadata`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.applicationLabel = "My App"
        manifest.applicationTheme = "@style/AppTheme"
        manifest.applicationIcon = "@mipmap/ic_launcher"
        manifest.debuggable = true

        manifest.addMetaData("API_KEY", value = "abc123")

        val xml = manifest.generate()

        assertTrue(xml.contains("android:label=\"My App\""))
        assertTrue(xml.contains("android:theme=\"@style/AppTheme\""))
        assertTrue(xml.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(xml.contains("android:debuggable=\"true\""))
        assertTrue(xml.contains("android:name=\"API_KEY\""))
        assertTrue(xml.contains("android:value=\"abc123\""))
    }

    @Test
    fun `manifest with features`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addFeature("android.hardware.camera", required = true)
        manifest.addFeature("android.hardware.camera.autofocus", required = false)

        val xml = manifest.generate()

        assertTrue(xml.contains("android:name=\"android.hardware.camera\""))
        assertTrue(xml.contains("android:required=\"true\""))
        assertTrue(xml.contains("android:name=\"android.hardware.camera.autofocus\""))
        assertTrue(xml.contains("android:required=\"false\""))
    }

    @Test
    fun `manifest with activity config changes`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addActivity(
            name = ".VideoActivity",
            exported = false,
            configChanges = listOf(
                AndroidManifest.ConfigChanges.ORIENTATION,
                AndroidManifest.ConfigChanges.SCREEN_SIZE,
                AndroidManifest.ConfigChanges.KEYBOARD_HIDDEN
            )
        )

        val xml = manifest.generate()

        assertTrue(xml.contains("android:configChanges=\"orientation|screenSize|keyboardHidden\""))
    }

    @Test
    fun `manifest with intent filter data`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addActivity(
            name = ".DeepLinkActivity",
            exported = true
        ) {
            intentFilter {
                action(AndroidManifest.Intent.ACTION_VIEW)
                category(AndroidManifest.Intent.CATEGORY_DEFAULT)
                category(AndroidManifest.Intent.CATEGORY_BROWSABLE)
                data(scheme = "https", host = "example.com", pathPrefix = "/app")
            }
        }

        val xml = manifest.generate()

        assertTrue(xml.contains("android:scheme=\"https\""))
        assertTrue(xml.contains("android:host=\"example.com\""))
        assertTrue(xml.contains("android:pathPrefix=\"/app\""))
    }

    @Test
    fun `forSimpleApp creates correct manifest`() {
        val manifest = AndroidManifest.forSimpleApp(
            packageName = "com.example.myapp",
            mainActivity = ".MainActivity",
            appName = "My App",
            minSdk = 26,
            targetSdk = 34
        )

        val xml = manifest.generate()

        assertTrue(xml.contains("package=\"com.example.myapp\""))
        assertTrue(xml.contains("android:name=\".MainActivity\""))
        assertTrue(xml.contains("android:label=\"My App\""))
        assertTrue(xml.contains("android:minSdkVersion=\"26\""))
        assertTrue(xml.contains("android.intent.action.MAIN"))
        assertTrue(xml.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `writeTo creates file`() {
        val manifest = AndroidManifest(
            packageName = "com.example.test",
            versionCode = 1,
            versionName = "1.0"
        )

        val outputDir = File("build/run/ManifestOutputTest")
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val file = manifest.writeTo(outputDir.resolve("AndroidManifest.xml"))

        assertTrue(file.exists())
        assertEquals("AndroidManifest.xml", file.name)

        val content = file.readText()
        assertTrue(content.startsWith("<?xml"))
        assertTrue(content.contains("com.example.test"))
    }

    @Test
    fun `writeToDir uses default filename`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        val outputDir = File("build/run/ManifestDirTest")
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val file = manifest.writeToDir(outputDir)

        assertTrue(file.exists())
        assertEquals(outputDir.resolve("AndroidManifest.xml"), file)
    }

    @Test
    fun `duplicate permissions are not added twice`() {
        val manifest = AndroidManifest(packageName = "com.example.test")

        manifest.addPermission(AndroidManifest.Permission.INTERNET)
        manifest.addPermission(AndroidManifest.Permission.INTERNET)
        manifest.addPermission(AndroidManifest.Permission.INTERNET)

        val xml = manifest.generate()

        // Count occurrences
        val count = xml.split("android.permission.INTERNET").size - 1
        assertEquals(1, count)
    }
}
