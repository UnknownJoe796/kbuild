package com.ivieleague.kbuild.vite

import com.ivieleague.kbuild.kmp.KmpProjectConfig
import com.ivieleague.kbuild.kmp.kmpCompileJs
import com.ivieleague.kbuild.kotlin.JsModuleKind
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.npm.NpmDependency
import com.lightningkite.reactive.core.Constant
import java.io.File

/**
 * Orchestrates a full-stack web project with Kotlin/JS and Vite.
 *
 * This class provides end-to-end support for web development:
 * - Compiles Kotlin to JavaScript (ES modules)
 * - Configures Vite to bundle and serve the output
 * - Manages the dev server with HMR
 * - Builds for production
 *
 * Example usage:
 * ```kotlin
 * val kmpConfig = kmpConfig("myapp", projectRoot) {
 *     js()
 * }
 *
 * val webProject = WebProject(kmpConfig)
 *
 * // Set up everything
 * webProject.scaffold()
 *
 * // Development workflow
 * webProject.dev()  // Compiles Kotlin + starts Vite dev server
 *
 * // Production build
 * webProject.build()  // Compiles + bundles for production
 * ```
 */
class WebProject(
    val kmpConfig: KmpProjectConfig,
    val webDir: File = kmpConfig.projectRoot.resolve("web"),
    val title: String = kmpConfig.name,
    val port: Int = 5173
) {
    val buildDir: File = kmpConfig.buildDir
    val jsOutputDir: File = buildDir.resolve("js")
    val distDir: File = webDir.resolve("dist")

    val viteProject = ViteProject(
        projectDir = webDir,
        kotlinOutputDir = jsOutputDir,
        title = title,
        port = port
    )

    private var devServer: ViteDevServer? = null

    /**
     * Check if the KMP project has a JS target.
     */
    fun hasJsTarget(): Boolean {
        return kmpConfig.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }
    }

    /**
     * Scaffold the complete web project.
     *
     * Creates:
     * - Vite project structure (package.json, vite.config.js, etc.)
     * - HTML template with Kotlin module import
     * - Entry point that loads Kotlin output
     *
     * @param initFunction Optional Kotlin function to call on load
     * @param additionalDependencies Extra npm dependencies to install
     */
    fun scaffold(
        initFunction: String? = "main",
        additionalDependencies: List<NpmDependency> = emptyList(),
        viteConfig: ViteProject.Config = ViteProject.Config()
    ): ScaffoldResult {
        require(hasJsTarget()) {
            "KMP project must have a JS target. Add js() to your KmpProjectConfig."
        }

        println("Setting up web project: $title")
        println("  Kotlin output: $jsOutputDir")
        println("  Web directory: $webDir")

        val scaffoldResult = viteProject.scaffold(
            kotlinModuleName = kmpConfig.name,
            initFunction = initFunction,
            config = viteConfig
        )

        // Add any additional dependencies
        if (additionalDependencies.isNotEmpty()) {
            val packageJson = viteProject.npmProject.getOrCreatePackageJson(kmpConfig.name)
            viteProject.npmProject.writePackageJson(
                packageJson.withDependencies(additionalDependencies)
            )
        }

        return ScaffoldResult(
            webDir = webDir,
            viteProject = viteProject,
            scaffoldResult = scaffoldResult
        )
    }

    data class ScaffoldResult(
        val webDir: File,
        val viteProject: ViteProject,
        val scaffoldResult: ViteProject.ScaffoldResult
    )

    /**
     * Compile Kotlin to JavaScript.
     *
     * @return The output JS file
     */
    suspend fun compileKotlin(): File {
        require(hasJsTarget()) { "KMP project must have a JS target" }

        println("Compiling Kotlin to JavaScript...")

        // Use KmpProjectConfig's JS compilation for browser
        return kmpCompileJs(
            config = kmpConfig,
            sourceRoots = Constant(kmpConfig.getSourcesForTarget(KmpTarget.Js)),
            moduleKind = JsModuleKind.ES // ES modules for Vite
        )
    }

    /**
     * Start development mode.
     *
     * 1. Compiles Kotlin to JavaScript
     * 2. Installs npm dependencies
     * 3. Starts Vite dev server with HMR
     *
     * @param openBrowser Whether to open the browser automatically
     * @return The dev server instance
     */
    suspend fun dev(openBrowser: Boolean = true): ViteDevServer {
        require(hasJsTarget()) { "KMP project must have a JS target" }

        // Stop existing server if running
        devServer?.stop()

        // Compile Kotlin
        compileKotlin()

        // Start Vite
        val server = viteProject.startDevServer()
        devServer = server

        if (openBrowser) {
            server.openInBrowser()
        }

        println()
        println("Development server running!")
        println("  Local:   ${server.url}")
        println()
        println("Press Ctrl+C to stop")

        return server
    }

    /**
     * Start development mode with file watching.
     *
     * Automatically recompiles Kotlin when source files change.
     *
     * @param openBrowser Whether to open the browser automatically
     * @param onKotlinCompiled Callback when Kotlin is recompiled
     * @return The dev server instance
     */
    suspend fun devWatch(
        openBrowser: Boolean = true,
        onKotlinCompiled: (() -> Unit)? = null
    ): DevWatchSession {
        require(hasJsTarget()) { "KMP project must have a JS target" }

        // Initial compile
        compileKotlin()

        // Capture this for use in callback
        val project = this

        // Start Vite server
        val server = viteProject.startDevServer()
        devServer = server

        if (openBrowser) {
            server.openInBrowser()
        }

        // Start Kotlin file watcher
        val watcher = KotlinFileWatcher(
            sourceDir = kmpConfig.projectRoot.resolve("src"),
            onChanged = {
                println("Kotlin source changed, recompiling...")
                try {
                    kotlinx.coroutines.runBlocking { project.compileKotlin() }
                    println("Kotlin compiled successfully")
                    onKotlinCompiled?.invoke()
                } catch (e: Exception) {
                    System.err.println("Kotlin compilation failed: ${e.message}")
                }
            }
        )
        watcher.start()

        println()
        println("Development server running with file watching!")
        println("  Local:   ${server.url}")
        println()
        println("Watching for Kotlin source changes...")
        println("Press Ctrl+C to stop")

        return DevWatchSession(server, watcher)
    }

    data class DevWatchSession(
        val server: ViteDevServer,
        val watcher: KotlinFileWatcher
    ) {
        fun stop() {
            watcher.stop()
            server.stop()
        }
    }

    /**
     * Build for production.
     *
     * 1. Compiles Kotlin to optimized JavaScript
     * 2. Bundles with Vite/Rollup
     * 3. Outputs to dist/ directory
     *
     * @return The build result
     */
    suspend fun build(): BuildResult {
        require(hasJsTarget()) { "KMP project must have a JS target" }

        println("Building for production...")

        // Compile Kotlin with optimizations
        val jsFile = compileKotlin()

        // Build with Vite
        viteProject.ensureInstalled()
        val viteResult = viteProject.build()

        return BuildResult(
            success = viteResult.success,
            kotlinOutput = jsFile,
            distDir = viteResult.distDir,
            exitCode = viteResult.exitCode
        )
    }

    data class BuildResult(
        val success: Boolean,
        val kotlinOutput: File,
        val distDir: File,
        val exitCode: Int
    ) {
        fun printSummary() {
            if (success) {
                println()
                println("Build successful!")
                println("  Output: $distDir")
                println()
                println("To preview: npm run preview")
                println("To deploy: copy contents of $distDir to your web server")
            } else {
                println()
                println("Build failed with exit code $exitCode")
            }
        }
    }

    /**
     * Preview the production build.
     */
    fun preview(): Process {
        require(distDir.exists()) { "dist directory not found. Run build() first." }
        return viteProject.preview()
    }

    /**
     * Stop the dev server if running.
     */
    fun stop() {
        devServer?.stop()
        devServer = null
    }

    /**
     * Clean all build artifacts.
     */
    fun clean() {
        println("Cleaning web project...")

        if (jsOutputDir.exists()) {
            jsOutputDir.deleteRecursively()
            println("  Cleaned: $jsOutputDir")
        }

        viteProject.clean()

        println("Clean complete")
    }

    /**
     * Install npm dependencies.
     */
    fun installDependencies(): Boolean {
        return viteProject.install()
    }

    companion object {
        /**
         * Quick check if this looks like a web-capable project.
         */
        fun isWebProject(projectDir: File): Boolean {
            val webDir = projectDir.resolve("web")
            return webDir.exists() && webDir.resolve("package.json").exists()
        }
    }
}

/**
 * Simple file watcher for Kotlin sources.
 */
class KotlinFileWatcher(
    val sourceDir: File,
    val debounceMs: Long = 300,
    val onChanged: () -> Unit
) {
    private var watchThread: Thread? = null
    @Volatile private var running = false
    private val timestamps = mutableMapOf<File, Long>()

    fun start() {
        if (running) return

        running = true
        collectTimestamps(sourceDir)

        watchThread = Thread {
            var lastChange = 0L

            while (running) {
                try {
                    Thread.sleep(500)

                    if (hasChanges(sourceDir)) {
                        val now = System.currentTimeMillis()
                        // Debounce: only trigger if enough time has passed
                        if (now - lastChange > debounceMs) {
                            lastChange = now
                            onChanged()
                            collectTimestamps(sourceDir)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "kotlin-file-watcher"
            start()
        }
    }

    fun stop() {
        running = false
        watchThread?.interrupt()
        watchThread = null
    }

    private fun collectTimestamps(dir: File) {
        if (!dir.exists()) return

        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { timestamps[it] = it.lastModified() }
    }

    private fun hasChanges(dir: File): Boolean {
        if (!dir.exists()) return false

        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { file ->
                val lastKnown = timestamps[file]
                val current = file.lastModified()
                lastKnown == null || current != lastKnown
            }
    }
}

/**
 * DSL for creating a web project from a KMP project.
 */
fun KmpProjectConfig.webProject(
    webDir: File = this.projectRoot.resolve("web"),
    title: String = this.name,
    port: Int = 5173
): WebProject = WebProject(
    kmpConfig = this,
    webDir = webDir,
    title = title,
    port = port
)
