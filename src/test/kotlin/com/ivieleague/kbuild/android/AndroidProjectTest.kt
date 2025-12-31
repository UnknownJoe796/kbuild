package com.ivieleague.kbuild.android

import com.ivieleague.kbuild.kmp.kmpProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for AndroidProject orchestration.
 */
class AndroidProjectTest {

    @Test
    fun `AndroidProject has correct defaults`() {
        val root = File("build/run/AndroidProjectDefaultsTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("defaults-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.defaults"
        )

        assertEquals("com.example.defaults", android.packageName)
        assertEquals(24, android.minSdk)
        assertEquals(34, android.targetSdk)
        assertEquals(1, android.versionCode)
        assertEquals("1.0", android.versionName)
        assertEquals(root.resolve("android"), android.androidDir)
        assertEquals(root.resolve("build/outputs/apk"), android.outputsDir)
    }

    @Test
    fun `AndroidProject with custom configuration`() {
        val root = File("build/run/AndroidProjectCustomTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("custom-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.custom",
            minSdk = 26,
            targetSdk = 33,
            versionCode = 5,
            versionName = "2.1.0",
            androidDir = root.resolve("app")
        )

        assertEquals("com.example.custom", android.packageName)
        assertEquals(26, android.minSdk)
        assertEquals(33, android.targetSdk)
        assertEquals(5, android.versionCode)
        assertEquals("2.1.0", android.versionName)
        assertEquals(root.resolve("app"), android.androidDir)
    }

    @Test
    fun `scaffold creates project structure`() {
        val root = File("build/run/AndroidProjectScaffoldTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directory
        val srcDir = root.resolve("src/jvmMain/kotlin")
        srcDir.mkdirs()

        val kmpProject = kmpProject("scaffold-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.scaffold",
            minSdk = 24,
            targetSdk = 34
        )

        val result = android.scaffold(
            appName = "Scaffold Test",
            mainActivity = ".MainActivity"
        )

        // Verify directories exist
        assertTrue(result.androidDir.exists(), "Android dir should exist")
        assertTrue(result.manifestFile.exists(), "Manifest should exist")
        assertTrue(result.resourcesDir.exists(), "Resources dir should exist")

        // Verify manifest content
        val manifestContent = result.manifestFile.readText()
        assertTrue(manifestContent.contains("com.example.scaffold"), "Manifest should have package name")
        assertTrue(manifestContent.contains(".MainActivity"), "Manifest should have MainActivity")
        assertTrue(manifestContent.contains("android.intent.action.MAIN"), "Manifest should have MAIN action")
        assertTrue(manifestContent.contains("android.intent.category.LAUNCHER"), "Manifest should have LAUNCHER category")

        // Verify strings.xml
        val stringsFile = result.resourcesDir.resolve("values/strings.xml")
        assertTrue(stringsFile.exists(), "strings.xml should exist")
        assertTrue(stringsFile.readText().contains("Scaffold Test"), "strings.xml should have app name")

        // Verify styles.xml
        val stylesFile = result.resourcesDir.resolve("values/styles.xml")
        assertTrue(stylesFile.exists(), "styles.xml should exist")
        assertTrue(stylesFile.readText().contains("Theme.App"), "styles.xml should have theme")

        // Verify layout
        val layoutFile = result.resourcesDir.resolve("layout/activity_main.xml")
        assertTrue(layoutFile.exists(), "activity_main.xml should exist")
        assertTrue(layoutFile.readText().contains("LinearLayout"), "Layout should have LinearLayout")

        // Verify MainActivity.kt was created
        val activityFile = srcDir.resolve("com/example/scaffold/MainActivity.kt")
        assertTrue(activityFile.exists(), "MainActivity.kt should exist")
        val activityContent = activityFile.readText()
        assertTrue(activityContent.contains("class MainActivity"), "Should have MainActivity class")
        assertTrue(activityContent.contains("Activity()"), "Should extend Activity")
    }

    @Test
    fun `scaffold handles custom activity name`() {
        val root = File("build/run/AndroidProjectCustomActivityTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src/jvmMain/kotlin")
        srcDir.mkdirs()

        val kmpProject = kmpProject("custom-activity-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.customactivity"
        )

        android.scaffold(
            appName = "Custom Activity App",
            mainActivity = ".ui.HomeActivity"
        )

        // Verify manifest has correct activity
        val manifestContent = android.manifestFile.readText()
        assertTrue(manifestContent.contains(".ui.HomeActivity"), "Manifest should have custom activity name")

        // Verify Kotlin file was created at correct path
        val activityFile = srcDir.resolve("com/example/customactivity/ui/HomeActivity.kt")
        assertTrue(activityFile.exists(), "HomeActivity.kt should exist at correct path")
        assertTrue(activityFile.readText().contains("class HomeActivity"), "Should have HomeActivity class")
    }

    @Test
    fun `extension function creates AndroidProject`() {
        val root = File("build/run/AndroidProjectExtensionTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("extension-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.extension",
            minSdk = 26
        )

        assertEquals(kmpProject, android.kmpConfig)
        assertEquals("com.example.extension", android.packageName)
        assertEquals(26, android.minSdk)
    }

    @Test
    fun `isAvailable returns SDK availability`() {
        val available = AndroidProject.isAvailable()

        // This test just verifies the method works
        // The result depends on whether SDK is installed
        println("Android SDK available: $available")
    }

    @Test
    fun `multiple android projects from same kmp project`() {
        val root = File("build/run/AndroidProjectMultiTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("multi-test", root) {
            jvm()
        }

        val mainApp = kmpProject.androidProject(
            packageName = "com.example.main",
            androidDir = root.resolve("app")
        )

        val demoApp = kmpProject.androidProject(
            packageName = "com.example.demo",
            androidDir = root.resolve("demo")
        )

        // They should be independent
        assertEquals(root.resolve("app"), mainApp.androidDir)
        assertEquals(root.resolve("demo"), demoApp.androidDir)
        assertEquals("com.example.main", mainApp.packageName)
        assertEquals("com.example.demo", demoApp.packageName)
    }

    @Test
    fun `resource directories are correctly configured`() {
        val root = File("build/run/AndroidProjectResourceDirsTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("resource-dirs-test", root) {
            jvm()
        }

        val android = kmpProject.androidProject(
            packageName = "com.example.resources"
        )

        assertEquals(android.androidDir.resolve("res"), android.resourcesDir)
        assertEquals(android.androidDir.resolve("assets"), android.assetsDir)
        assertEquals(android.androidDir.resolve("jniLibs"), android.nativeLibsDir)
        assertEquals(android.androidDir.resolve("AndroidManifest.xml"), android.manifestFile)
    }

    @Test
    fun `build requires JVM target`() {
        val root = File("build/run/AndroidProjectNoJvmTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = kmpProject("no-jvm-test", root) {
            js()  // Only JS, no JVM
        }

        var exceptionThrown = false
        try {
            val android = kmpProject.androidProject(
                packageName = "com.example.nojvm"
            )
            android.scaffold()
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("JVM") == true, "Error should mention JVM target")
        }

        assertTrue(exceptionThrown, "Should throw exception when no JVM target")
    }
}
