package com.ivieleague.kbuild.android

import com.ivieleague.kbuild.kmp.kmpProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for AndroidProject that test the full workflow.
 * These tests require Android SDK to be installed.
 */
class AndroidProjectIntegrationTest {

    private fun skipIfNoSdk(): Boolean {
        if (!AndroidSdk.isAvailable()) {
            println("Skipping test: Android SDK not available")
            return true
        }
        return false
    }

    @Test
    fun `full scaffold and structure verification`() {
        val root = File("build/run/AndroidProjectFullTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directory
        val srcDir = root.resolve("src/jvmMain/kotlin")
        srcDir.mkdirs()

        val kmpProject = kmpProject("fulltest", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.fulltest",
            minSdk = 24,
            targetSdk = 34,
            versionCode = 1,
            versionName = "1.0.0"
        )

        val result = android.scaffold(
            appName = "Full Test App",
            mainActivity = ".MainActivity"
        )

        // Verify all files were created
        assertTrue(result.androidDir.exists(), "Android dir should exist")
        assertTrue(result.manifestFile.exists(), "Manifest should exist")
        assertTrue(result.resourcesDir.exists(), "Resources dir should exist")

        // Verify manifest content
        val manifestContent = result.manifestFile.readText()
        assertTrue(manifestContent.contains("com.example.fulltest"), "Manifest should have package name")
        assertTrue(manifestContent.contains(".MainActivity"), "Manifest should have MainActivity")
        assertTrue(manifestContent.contains("android:minSdkVersion=\"24\""), "Manifest should have minSdk")
        assertTrue(manifestContent.contains("android:targetSdkVersion=\"34\""), "Manifest should have targetSdk")

        // Verify resources
        val stringsFile = result.resourcesDir.resolve("values/strings.xml")
        assertTrue(stringsFile.exists(), "strings.xml should exist")
        assertTrue(stringsFile.readText().contains("Full Test App"), "strings.xml should have app name")

        val stylesFile = result.resourcesDir.resolve("values/styles.xml")
        assertTrue(stylesFile.exists(), "styles.xml should exist")

        val layoutFile = result.resourcesDir.resolve("layout/activity_main.xml")
        assertTrue(layoutFile.exists(), "activity_main.xml should exist")

        // Verify MainActivity.kt
        val activityFile = srcDir.resolve("com/example/fulltest/MainActivity.kt")
        assertTrue(activityFile.exists(), "MainActivity.kt should exist")
        assertTrue(activityFile.readText().contains("class MainActivity"), "Should have MainActivity class")
    }

    @Test
    fun `full build pipeline with SDK`() {
        if (skipIfNoSdk()) return

        val root = File("build/run/AndroidBuildTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directory with Kotlin code
        val srcDir = root.resolve("src/jvmMain/kotlin/com/example/buildtest")
        srcDir.mkdirs()
        srcDir.resolve("MainActivity.kt").writeText("""
            package com.example.buildtest

            import android.app.Activity
            import android.os.Bundle

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                }
            }
        """.trimIndent())

        val kmpProject = kmpProject("buildtest", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.buildtest",
            minSdk = 24,
            targetSdk = 34
        )

        // Scaffold (creates manifest and resources)
        android.scaffold(appName = "Build Test")

        // Build APK
        val result = android.build()

        assertTrue(result.success, "Build should succeed")
        assertTrue(result.apk.exists(), "APK should exist: ${result.apk}")
        assertTrue(result.apk.length() > 0, "APK should have content")
        assertTrue(result.dexFile.exists(), "DEX file should exist")
        assertTrue(result.classesDir.exists(), "Classes dir should exist")

        println("Built APK: ${result.apk}")
        println("APK size: ${result.apk.length() / 1024} KB")
    }

    @Test
    fun `build with resources`() {
        if (skipIfNoSdk()) return

        val root = File("build/run/AndroidResourceBuildTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source
        val srcDir = root.resolve("src/jvmMain/kotlin/com/example/restest")
        srcDir.mkdirs()
        srcDir.resolve("MainActivity.kt").writeText("""
            package com.example.restest

            import android.app.Activity
            import android.os.Bundle
            import android.widget.TextView

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    val textView = findViewById<TextView>(R.id.textView)
                    textView.text = getString(R.string.app_name)
                }
            }
        """.trimIndent())

        val kmpProject = kmpProject("restest", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.restest"
        )

        // Scaffold creates resources
        android.scaffold(appName = "Resource Test App")

        // Add an extra drawable resource
        val drawableDir = android.resourcesDir.resolve("drawable")
        drawableDir.mkdirs()
        drawableDir.resolve("ic_test.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <shape xmlns:android="http://schemas.android.com/apk/res/android"
                android:shape="rectangle">
                <solid android:color="#FF0000"/>
            </shape>
        """.trimIndent())

        // Build
        val result = android.build()

        assertTrue(result.success, "Build with resources should succeed")
        assertTrue(result.apk.exists(), "APK should exist")

        println("Built APK with resources: ${result.apk}")
    }

    @Test
    fun `list devices works without error`() {
        if (skipIfNoSdk()) return

        val root = File("build/run/AndroidDeviceListTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("devicetest", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.devicetest"
        )

        // This should not throw
        val devices = android.listDevices()
        println("Connected devices: ${devices.size}")
        devices.forEach { device ->
            println("  ${device.serial}: ${device.model ?: device.device ?: "unknown"} (${device.state})")
        }
    }

    @Test
    fun `clean removes build artifacts`() {
        val root = File("build/run/AndroidCleanTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("cleantest", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.cleantest"
        )

        // Create some artifacts
        android.outputsDir.mkdirs()
        android.outputsDir.resolve("test.apk").writeText("fake apk")
        kmpProject.buildDir.resolve("intermediates/android").mkdirs()

        assertTrue(android.outputsDir.exists(), "Outputs should exist before clean")

        // Clean
        android.clean()

        assertTrue(!android.outputsDir.exists(), "Outputs should be removed after clean")
    }
}
