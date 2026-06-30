package com.ivieleague.kbuild.vite

import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.kmp.KmpProjectConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for WebProject orchestration.
 */
class WebProjectTest {

    @Test
    fun `WebProject creates with correct paths`() {
        val root = File("build/run/WebProjectPathsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "webtest", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(kmpProject)

        assertEquals(kmpProject, webProject.kmpConfig)
        assertEquals(root.resolve("web"), webProject.webDir)
        assertEquals(root.resolve("build/js"), webProject.jsOutputDir)
        assertEquals("webtest", webProject.title)
    }

    @Test
    fun `WebProject custom paths work`() {
        val root = File("build/run/WebProjectCustomPathsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "customweb", projectRoot = root, targets = setOf(KmpTarget.Js))

        val customWebDir = root.resolve("frontend")
        val webProject = WebProject(
            kmpConfig = kmpProject,
            webDir = customWebDir,
            title = "Custom Title",
            port = 3000
        )

        assertEquals(customWebDir, webProject.webDir)
        assertEquals("Custom Title", webProject.title)
        assertEquals(3000, webProject.port)
    }

    @Test
    fun `hasJsTarget returns true when JS target present`() {
        val root = File("build/run/WebProjectHasJsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "jsproject", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(kmpProject)

        assertTrue(webProject.hasJsTarget())
    }

    @Test
    fun `hasJsTarget returns false when no JS target`() {
        val root = File("build/run/WebProjectNoJsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "jvmonly", projectRoot = root, targets = setOf(KmpTarget.Jvm))

        val webProject = WebProject(kmpProject)

        assertFalse(webProject.hasJsTarget())
    }

    @Test
    fun `scaffold requires JS target`() {
        val root = File("build/run/WebProjectScaffoldNoJsTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "nojsproject", projectRoot = root, targets = setOf(KmpTarget.Jvm))

        val webProject = WebProject(kmpProject)

        var threw = false
        try {
            webProject.scaffold()
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("JS target"))
        }
        assertTrue(threw)
    }

    @Test
    fun `scaffold creates Vite project structure`() {
        val root = File("build/run/WebProjectScaffoldTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "scaffoldweb", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(kmpProject)
        val result = webProject.scaffold()

        assertTrue(result.webDir.exists())
        assertTrue(result.scaffoldResult.packageJson.exists())
        assertTrue(result.scaffoldResult.viteConfig.exists())
        assertTrue(result.scaffoldResult.indexHtml.exists())
        assertTrue(result.scaffoldResult.mainJs.exists())
    }

    @Test
    fun `webProject extension creates WebProject`() {
        val root = File("build/run/WebProjectExtensionTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "exttest", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = kmpProject.webProject(
            title = "Extension Test",
            port = 4000
        )

        assertEquals(kmpProject, webProject.kmpConfig)
        assertEquals("Extension Test", webProject.title)
        assertEquals(4000, webProject.port)
    }

    @Test
    fun `isWebProject returns false for empty dir`() {
        val tempDir = File("build/run/IsWebProjectTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        assertFalse(WebProject.isWebProject(tempDir))
    }

    @Test
    fun `isWebProject returns true when web dir has package json`() {
        val tempDir = File("build/run/IsWebProjectPositiveTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val webDir = tempDir.resolve("web")
        webDir.mkdirs()
        webDir.resolve("package.json").writeText("{}")

        assertTrue(WebProject.isWebProject(tempDir))
    }

    @Test
    fun `KotlinFileWatcher initializes correctly`() {
        val sourceDir = File("build/run/KotlinFileWatcherTest")
        sourceDir.mkdirs()

        var changed = false
        val watcher = KotlinFileWatcher(
            sourceDir = sourceDir,
            debounceMs = 100,
            onChanged = { changed = true }
        )

        // Just verify it doesn't throw
        watcher.start()
        watcher.stop()
    }

    @Test
    fun `clean works on non-existent directories`() {
        val root = File("build/run/WebProjectCleanTest")
        root.deleteRecursively()
        root.mkdirs()

        val kmpProject = KmpProjectConfig(name = "cleantest", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(kmpProject)

        // Should not throw
        webProject.clean()
    }

    @Test
    fun `distDir is correct`() {
        val root = File("build/run/WebProjectDistDirTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "disttest", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(kmpProject)

        assertEquals(root.resolve("web/dist"), webProject.distDir)
    }

    @Test
    fun `viteProject is correctly configured`() {
        val root = File("build/run/WebProjectViteTest")
        root.deleteRecursively()

        val kmpProject = KmpProjectConfig(name = "vitetest", projectRoot = root, targets = setOf(KmpTarget.Js))

        val webProject = WebProject(
            kmpConfig = kmpProject,
            title = "Vite Config Test",
            port = 8080
        )

        assertEquals(root.resolve("web"), webProject.viteProject.projectDir)
        assertEquals(root.resolve("build/js"), webProject.viteProject.kotlinOutputDir)
        assertEquals("Vite Config Test", webProject.viteProject.title)
        assertEquals(8080, webProject.viteProject.port)
    }
}
