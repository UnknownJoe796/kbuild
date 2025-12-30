package com.ivieleague.kbuild.vite

import com.ivieleague.kbuild.kmp.kmpProject
import com.ivieleague.kbuild.npm.NpmProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for WebProject that test the full workflow.
 */
class WebProjectIntegrationTest {

    @Test
    fun `full scaffold and structure verification`() {
        val root = File("build/run/WebProjectFullTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directory with Kotlin code
        val srcDir = root.resolve("src/jsMain/kotlin")
        srcDir.mkdirs()
        srcDir.resolve("App.kt").writeText("""
            object App {
                @JsExport
                fun main() {
                    console.log("Hello from Kotlin!")
                }

                @JsExport
                fun greet(name: String): String {
                    return "Hello, ${'$'}name!"
                }
            }

            fun main() {
                App.main()
            }
        """.trimIndent())

        // Create KMP project
        val kmpProject = kmpProject("fulltest", root) {
            js()
        }

        // Create and scaffold web project
        val webProject = kmpProject.webProject(
            title = "Full Integration Test"
        )

        val result = webProject.scaffold(initFunction = "App.main")

        // Verify all files were created
        assertTrue(result.webDir.exists(), "Web directory should exist")

        val packageJson = result.webDir.resolve("package.json")
        assertTrue(packageJson.exists(), "package.json should exist")

        val viteConfig = result.webDir.resolve("vite.config.js")
        assertTrue(viteConfig.exists(), "vite.config.js should exist")

        val indexHtml = result.webDir.resolve("index.html")
        assertTrue(indexHtml.exists(), "index.html should exist")

        val mainJs = result.webDir.resolve("src/main.js")
        assertTrue(mainJs.exists(), "src/main.js should exist")

        val styleCss = result.webDir.resolve("src/style.css")
        assertTrue(styleCss.exists(), "src/style.css should exist")

        // Verify content
        val viteConfigContent = viteConfig.readText()
        assertTrue(viteConfigContent.contains("@kotlin"), "Vite config should have @kotlin alias")
        assertTrue(viteConfigContent.contains("defineConfig"), "Vite config should use defineConfig")

        val indexContent = indexHtml.readText()
        assertTrue(indexContent.contains("Full Integration Test"), "index.html should have title")
        assertTrue(indexContent.contains("src/main.js"), "index.html should reference main.js")

        val mainJsContent = mainJs.readText()
        assertTrue(mainJsContent.contains("fulltest"), "main.js should import Kotlin module")
        assertTrue(mainJsContent.contains("App.main"), "main.js should call App.main")

        val packageJsonContent = packageJson.readText()
        assertTrue(packageJsonContent.contains("vite"), "package.json should have vite")
        assertTrue(packageJsonContent.contains("dev"), "package.json should have dev script")
    }

    @Test
    fun `npm install and vite available`() {
        if (!NpmProject.isNpmAvailable()) {
            println("Skipping test: npm not available")
            return
        }

        val root = File("build/run/WebProjectNpmTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create minimal source
        val srcDir = root.resolve("src/jsMain/kotlin")
        srcDir.mkdirs()
        srcDir.resolve("Main.kt").writeText("""
            fun main() { println("Hello") }
        """.trimIndent())

        val kmpProject = kmpProject("npmtest", root) {
            js()
        }

        val webProject = kmpProject.webProject()
        webProject.scaffold()

        // Install dependencies
        val installed = webProject.installDependencies()
        assertTrue(installed, "npm install should succeed")

        // Verify vite was installed
        val viteInstalled = ViteProject.isViteInstalled(webProject.webDir)
        assertTrue(viteInstalled, "Vite should be installed in node_modules")
    }

    @Test
    fun `scaffold with custom vite config`() {
        val root = File("build/run/WebProjectCustomConfigTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src/jsMain/kotlin")
        srcDir.mkdirs()
        srcDir.resolve("Main.kt").writeText("fun main() {}")

        val kmpProject = kmpProject("customconfig", root) {
            js()
        }

        val webProject = kmpProject.webProject(port = 4000)

        // Custom vite config
        val viteConfig = ViteProject.Config(
            base = "/myapp/",
            sourcemap = false,
            minify = true
        )

        webProject.scaffold(
            initFunction = null,  // No init function
            viteConfig = viteConfig
        )

        val configFile = webProject.webDir.resolve("vite.config.js")
        val content = configFile.readText()

        assertTrue(content.contains("base: '/myapp/'"), "Should have custom base")
        assertTrue(content.contains("port: 4000"), "Should have custom port")
        assertTrue(content.contains("sourcemap: false"), "Should have sourcemap disabled")
    }

    @Test
    fun `multiple web projects in same kmp project`() {
        val root = File("build/run/WebProjectMultiTest")
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src/jsMain/kotlin")
        srcDir.mkdirs()
        srcDir.resolve("Main.kt").writeText("fun main() {}")

        val kmpProject = kmpProject("multitest", root) {
            js()
        }

        // Create two different web projects
        val adminProject = kmpProject.webProject(
            webDir = root.resolve("admin"),
            title = "Admin Panel",
            port = 3000
        )

        val publicProject = kmpProject.webProject(
            webDir = root.resolve("public"),
            title = "Public Site",
            port = 3001
        )

        adminProject.scaffold()
        publicProject.scaffold()

        // Both should exist independently
        assertTrue(root.resolve("admin/package.json").exists())
        assertTrue(root.resolve("public/package.json").exists())

        val adminHtml = root.resolve("admin/index.html").readText()
        val publicHtml = root.resolve("public/index.html").readText()

        assertTrue(adminHtml.contains("Admin Panel"))
        assertTrue(publicHtml.contains("Public Site"))
    }
}
