package com.ivieleague.kbuild.vite

import com.ivieleague.kbuild.npm.NpmDependency
import com.ivieleague.kbuild.npm.NpmProject
import com.ivieleague.kbuild.npm.PackageJson
import java.io.File

/**
 * Manages a Vite-based web project with Kotlin/JS integration.
 *
 * Vite is a modern frontend build tool that provides:
 * - Instant dev server startup with native ES modules
 * - Hot Module Replacement (HMR) for fast development
 * - Optimized production builds with Rollup
 *
 * This class handles:
 * - Vite configuration generation for Kotlin/JS output
 * - Dev server management
 * - Production builds
 * - HTML template management
 *
 * Example usage:
 * ```kotlin
 * val vite = ViteProject(
 *     projectDir = File("web"),
 *     kotlinOutputDir = File("build/js"),
 *     title = "My Kotlin App"
 * )
 *
 * // Set up project
 * vite.scaffold()
 *
 * // Start dev server
 * val server = vite.startDevServer()
 * println("Dev server running at ${server.url}")
 *
 * // Build for production
 * vite.build()
 * ```
 */
class ViteProject(
    val projectDir: File,
    val kotlinOutputDir: File,
    val title: String = "Kotlin Web App",
    val port: Int = 5173,
    val host: String = "localhost"
) {
    val npmProject = NpmProject(projectDir)
    val srcDir: File = projectDir.resolve("src")
    val publicDir: File = projectDir.resolve("public")
    val distDir: File = projectDir.resolve("dist")

    /**
     * Vite configuration options.
     */
    data class Config(
        val base: String = "/",
        val outDir: String = "dist",
        val sourcemap: Boolean = true,
        val minify: Boolean = true,
        val cssCodeSplit: Boolean = true,
        val assetsInlineLimit: Int = 4096,
        val plugins: List<String> = emptyList(),
        val define: Map<String, String> = emptyMap(),
        val resolve: ResolveConfig = ResolveConfig()
    )

    data class ResolveConfig(
        val alias: Map<String, String> = emptyMap()
    )

    /**
     * Generate the vite.config.js file.
     */
    fun generateViteConfig(config: Config = Config()): File {
        val configFile = projectDir.resolve("vite.config.js")

        val kotlinPath = kotlinOutputDir.relativeTo(projectDir).path.replace("\\", "/")

        configFile.writeText("""
            import { defineConfig } from 'vite';
            import { resolve } from 'path';

            export default defineConfig({
                root: '.',
                base: '${config.base}',
                publicDir: 'public',

                server: {
                    port: $port,
                    host: '$host',
                    open: false,
                    watch: {
                        // Watch Kotlin output directory for changes
                        ignored: ['!**/$kotlinPath/**']
                    }
                },

                resolve: {
                    alias: {
                        '@kotlin': resolve(__dirname, '$kotlinPath'),
                        ${config.resolve.alias.entries.joinToString(",\n                        ") { (k, v) ->
                            "'$k': resolve(__dirname, '$v')"
                        }}
                    }
                },

                build: {
                    outDir: '${config.outDir}',
                    sourcemap: ${config.sourcemap},
                    minify: ${if (config.minify) "'esbuild'" else "false"},
                    cssCodeSplit: ${config.cssCodeSplit},
                    assetsInlineLimit: ${config.assetsInlineLimit},
                    rollupOptions: {
                        input: {
                            main: resolve(__dirname, 'index.html')
                        }
                    }
                },

                define: {
                    ${config.define.entries.joinToString(",\n                    ") { (k, v) ->
                        "'$k': $v"
                    }}
                },

                optimizeDeps: {
                    // Don't pre-bundle Kotlin output
                    exclude: ['@kotlin']
                }
            });
        """.trimIndent())

        return configFile
    }

    /**
     * Generate the index.html file.
     */
    fun generateIndexHtml(
        entryPoint: String = "src/main.js",
        bodyContent: String = """<div id="root"></div>""",
        headContent: String = ""
    ): File {
        val indexFile = projectDir.resolve("index.html")

        indexFile.writeText("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                $headContent
            </head>
            <body>
                $bodyContent
                <script type="module" src="/$entryPoint"></script>
            </body>
            </html>
        """.trimIndent())

        return indexFile
    }

    /**
     * Generate the main.js entry point that loads Kotlin code.
     *
     * @param kotlinModuleName Name of the Kotlin module (usually project name)
     * @param initFunction Optional initialization function to call
     */
    fun generateMainJs(
        kotlinModuleName: String,
        initFunction: String? = null
    ): File {
        srcDir.mkdirs()
        val mainFile = srcDir.resolve("main.js")

        val initCall = if (initFunction != null) {
            "\n\n// Initialize Kotlin application\n$kotlinModuleName.$initFunction();"
        } else ""

        mainFile.writeText("""
            // Import Kotlin compiled output
            import * as $kotlinModuleName from '@kotlin/$kotlinModuleName.js';

            // Import styles
            import './style.css';

            console.log('Kotlin module loaded:', $kotlinModuleName);
            $initCall

            // Enable HMR for development
            if (import.meta.hot) {
                import.meta.hot.accept('@kotlin/$kotlinModuleName.js', (newModule) => {
                    console.log('Kotlin module updated via HMR');
                    // Optionally reinitialize on HMR
                    ${if (initFunction != null) "newModule.$initFunction();" else ""}
                });
            }
        """.trimIndent())

        return mainFile
    }

    /**
     * Generate a basic CSS file.
     */
    fun generateStyleCss(): File {
        srcDir.mkdirs()
        val styleFile = srcDir.resolve("style.css")

        styleFile.writeText("""
            :root {
                font-family: Inter, system-ui, Avenir, Helvetica, Arial, sans-serif;
                line-height: 1.5;
                font-weight: 400;

                color-scheme: light dark;
                color: rgba(255, 255, 255, 0.87);
                background-color: #242424;

                font-synthesis: none;
                text-rendering: optimizeLegibility;
                -webkit-font-smoothing: antialiased;
                -moz-osx-font-smoothing: grayscale;
            }

            body {
                margin: 0;
                display: flex;
                place-items: center;
                min-width: 320px;
                min-height: 100vh;
            }

            #root {
                max-width: 1280px;
                margin: 0 auto;
                padding: 2rem;
                text-align: center;
            }

            @media (prefers-color-scheme: light) {
                :root {
                    color: #213547;
                    background-color: #ffffff;
                }
            }
        """.trimIndent())

        return styleFile
    }

    /**
     * Generate package.json with Vite dependencies.
     */
    fun generatePackageJson(
        additionalDependencies: List<NpmDependency> = emptyList()
    ): PackageJson {
        val packageJson = PackageJson(
            name = projectDir.name,
            version = "1.0.0",
            type = "module",
            scripts = mapOf(
                "dev" to "vite",
                "build" to "vite build",
                "preview" to "vite preview",
                "clean" to "rm -rf dist node_modules/.vite"
            ),
            devDependencies = mapOf(
                "vite" to "^5.0.0"
            )
        ).withDependencies(additionalDependencies)

        packageJson.writeTo(npmProject.packageJsonFile)
        return packageJson
    }

    /**
     * Create the public directory for static assets.
     */
    fun createPublicDir(): File {
        publicDir.mkdirs()

        // Create a placeholder favicon
        val favicon = publicDir.resolve("favicon.svg")
        if (!favicon.exists()) {
            favicon.writeText("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                    <text y=".9em" font-size="90">🚀</text>
                </svg>
            """.trimIndent())
        }

        return publicDir
    }

    /**
     * Scaffold a complete Vite project for Kotlin/JS.
     *
     * Creates:
     * - package.json with Vite dependencies
     * - vite.config.js configured for Kotlin output
     * - index.html
     * - src/main.js entry point
     * - src/style.css
     * - public/ directory for static assets
     *
     * @param kotlinModuleName Name of the Kotlin module
     * @param initFunction Optional init function to call from Kotlin
     */
    fun scaffold(
        kotlinModuleName: String,
        initFunction: String? = null,
        config: Config = Config()
    ): ScaffoldResult {
        println("Scaffolding Vite project at: $projectDir")

        projectDir.mkdirs()
        srcDir.mkdirs()

        val packageJson = generatePackageJson()
        val viteConfig = generateViteConfig(config)
        val indexHtml = generateIndexHtml()
        val mainJs = generateMainJs(kotlinModuleName, initFunction)
        val styleCss = generateStyleCss()
        val publicDir = createPublicDir()

        // Create .gitignore
        projectDir.resolve(".gitignore").writeText("""
            node_modules/
            dist/
            .vite/
            *.local
        """.trimIndent())

        println("Vite project scaffolded!")
        println()
        println("Next steps:")
        println("  1. Build your Kotlin/JS code to: $kotlinOutputDir")
        println("  2. cd ${projectDir.name}")
        println("  3. npm install")
        println("  4. npm run dev")

        return ScaffoldResult(
            projectDir = projectDir,
            packageJson = npmProject.packageJsonFile,
            viteConfig = viteConfig,
            indexHtml = indexHtml,
            mainJs = mainJs,
            styleCss = styleCss,
            publicDir = publicDir
        )
    }

    data class ScaffoldResult(
        val projectDir: File,
        val packageJson: File,
        val viteConfig: File,
        val indexHtml: File,
        val mainJs: File,
        val styleCss: File,
        val publicDir: File
    )

    /**
     * Install npm dependencies.
     */
    fun install(): Boolean {
        require(NpmProject.isNpmAvailable()) { "npm is not available" }
        println("Installing npm dependencies...")
        return npmProject.install()
    }

    /**
     * Ensure dependencies are installed.
     */
    fun ensureInstalled(): Boolean {
        if (npmProject.isInstalled()) return true
        return install()
    }

    /**
     * Start the Vite dev server.
     *
     * @return A ViteDevServer instance to control the server
     */
    fun startDevServer(): ViteDevServer {
        ensureInstalled()
        return ViteDevServer.start(projectDir, port, host)
    }

    /**
     * Build for production.
     *
     * @return The dist directory containing built files
     */
    fun build(): BuildResult {
        require(NpmProject.isNpmAvailable()) { "npm is not available" }
        ensureInstalled()

        println("Building for production...")

        val process = ProcessBuilder("npm", "run", "build")
            .directory(projectDir)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()

        return BuildResult(
            success = exitCode == 0,
            distDir = distDir,
            exitCode = exitCode
        )
    }

    data class BuildResult(
        val success: Boolean,
        val distDir: File,
        val exitCode: Int
    )

    /**
     * Preview the production build.
     *
     * @return The preview server process
     */
    fun preview(): Process {
        require(distDir.exists()) { "dist directory not found. Run build() first." }

        return ProcessBuilder("npm", "run", "preview")
            .directory(projectDir)
            .inheritIO()
            .start()
    }

    /**
     * Clean build artifacts.
     */
    fun clean() {
        if (distDir.exists()) {
            distDir.deleteRecursively()
            println("Cleaned: $distDir")
        }

        val viteCache = projectDir.resolve("node_modules/.vite")
        if (viteCache.exists()) {
            viteCache.deleteRecursively()
            println("Cleaned: $viteCache")
        }
    }

    companion object {
        /**
         * Check if Vite is available in the project.
         */
        fun isViteInstalled(projectDir: File): Boolean {
            return projectDir.resolve("node_modules/.bin/vite").exists() ||
                   projectDir.resolve("node_modules/vite").exists()
        }
    }
}

/**
 * Extension to create a Vite project from an existing directory.
 */
fun File.asViteProject(
    kotlinOutputDir: File,
    title: String = "Kotlin Web App"
): ViteProject = ViteProject(
    projectDir = this,
    kotlinOutputDir = kotlinOutputDir,
    title = title
)
