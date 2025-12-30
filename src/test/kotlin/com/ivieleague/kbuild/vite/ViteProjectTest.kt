package com.ivieleague.kbuild.vite

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Vite project generation and management.
 */
class ViteProjectTest {

    @Test
    fun `ViteProject creates with correct paths`() {
        val projectDir = File("build/run/ViteProjectTest")
        val kotlinOutputDir = File("build/run/ViteProjectTest/kotlin-output")

        val vite = ViteProject(
            projectDir = projectDir,
            kotlinOutputDir = kotlinOutputDir,
            title = "Test App"
        )

        assertEquals(projectDir, vite.projectDir)
        assertEquals(kotlinOutputDir, vite.kotlinOutputDir)
        assertEquals("Test App", vite.title)
        assertEquals(5173, vite.port)
    }

    @Test
    fun `ViteProject generates vite config`() {
        val tempDir = File("build/run/ViteConfigTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val kotlinDir = tempDir.resolve("build/js")

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = kotlinDir,
            port = 3000
        )

        val configFile = vite.generateViteConfig()

        assertTrue(configFile.exists())
        assertEquals("vite.config.js", configFile.name)

        val content = configFile.readText()
        assertTrue(content.contains("import { defineConfig } from 'vite'"))
        assertTrue(content.contains("port: 3000"))
        assertTrue(content.contains("@kotlin"))
    }

    @Test
    fun `ViteProject generates index html`() {
        val tempDir = File("build/run/ViteIndexHtmlTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js"),
            title = "My Test App"
        )

        val indexFile = vite.generateIndexHtml()

        assertTrue(indexFile.exists())
        assertEquals("index.html", indexFile.name)

        val content = indexFile.readText()
        assertTrue(content.contains("My Test App"))
        assertTrue(content.contains("src/main.js"))
        assertTrue(content.contains("<div id=\"root\">"))
    }

    @Test
    fun `ViteProject generates main js entry point`() {
        val tempDir = File("build/run/ViteMainJsTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        val mainFile = vite.generateMainJs(
            kotlinModuleName = "myapp",
            initFunction = "main"
        )

        assertTrue(mainFile.exists())
        assertEquals("main.js", mainFile.name)

        val content = mainFile.readText()
        assertTrue(content.contains("import * as myapp from '@kotlin/myapp.js'"))
        assertTrue(content.contains("myapp.main()"))
        assertTrue(content.contains("import.meta.hot"))
    }

    @Test
    fun `ViteProject generates style css`() {
        val tempDir = File("build/run/ViteStyleCssTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        val styleFile = vite.generateStyleCss()

        assertTrue(styleFile.exists())
        assertEquals("style.css", styleFile.name)

        val content = styleFile.readText()
        assertTrue(content.contains(":root"))
        assertTrue(content.contains("#root"))
    }

    @Test
    fun `ViteProject generates package json`() {
        val tempDir = File("build/run/VitePackageJsonTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        val packageJson = vite.generatePackageJson()

        val packageFile = tempDir.resolve("package.json")
        assertTrue(packageFile.exists())

        val content = packageFile.readText()
        assertTrue(content.contains("vite"))
        assertTrue(content.contains("\"dev\""))
        assertTrue(content.contains("\"build\""))
    }

    @Test
    fun `ViteProject scaffold creates complete structure`() {
        val tempDir = File("build/run/ViteScaffoldTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("build/js"),
            title = "Scaffold Test"
        )

        val result = vite.scaffold(
            kotlinModuleName = "testapp",
            initFunction = "main"
        )

        assertTrue(result.projectDir.exists())
        assertTrue(result.packageJson.exists())
        assertTrue(result.viteConfig.exists())
        assertTrue(result.indexHtml.exists())
        assertTrue(result.mainJs.exists())
        assertTrue(result.styleCss.exists())
        assertTrue(result.publicDir.exists())

        // Check gitignore was created
        val gitignore = tempDir.resolve(".gitignore")
        assertTrue(gitignore.exists())
        assertTrue(gitignore.readText().contains("node_modules/"))
    }

    @Test
    fun `ViteProject creates public directory with favicon`() {
        val tempDir = File("build/run/VitePublicDirTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        val publicDir = vite.createPublicDir()

        assertTrue(publicDir.exists())
        assertTrue(publicDir.isDirectory)

        val favicon = publicDir.resolve("favicon.svg")
        assertTrue(favicon.exists())
    }

    @Test
    fun `ViteProject Config has correct defaults`() {
        val config = ViteProject.Config()

        assertEquals("/", config.base)
        assertEquals("dist", config.outDir)
        assertTrue(config.sourcemap)
        assertTrue(config.minify)
    }

    @Test
    fun `ViteProject generates config with custom settings`() {
        val tempDir = File("build/run/ViteCustomConfigTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        val config = ViteProject.Config(
            base = "/myapp/",
            sourcemap = false,
            minify = false
        )

        val configFile = vite.generateViteConfig(config)
        val content = configFile.readText()

        assertTrue(content.contains("base: '/myapp/'"))
        assertTrue(content.contains("sourcemap: false"))
        assertTrue(content.contains("minify: false"))
    }

    @Test
    fun `ViteProject distDir is correct`() {
        val tempDir = File("build/run/ViteDistDirTest")

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        assertEquals(tempDir.resolve("dist"), vite.distDir)
    }

    @Test
    fun `isViteInstalled returns false when node_modules missing`() {
        val tempDir = File("build/run/ViteNotInstalledTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val result = ViteProject.isViteInstalled(tempDir)

        kotlin.test.assertFalse(result)
    }

    @Test
    fun `ViteProject clean works on non-existent dirs`() {
        val tempDir = File("build/run/ViteCleanTest")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vite = ViteProject(
            projectDir = tempDir,
            kotlinOutputDir = tempDir.resolve("js")
        )

        // Should not throw
        vite.clean()
    }

    @Test
    fun `asViteProject extension works`() {
        val tempDir = File("build/run/ViteExtensionTest")
        val kotlinDir = tempDir.resolve("build/js")

        val vite = tempDir.asViteProject(
            kotlinOutputDir = kotlinDir,
            title = "Extension Test"
        )

        assertEquals(tempDir, vite.projectDir)
        assertEquals(kotlinDir, vite.kotlinOutputDir)
        assertEquals("Extension Test", vite.title)
    }
}
