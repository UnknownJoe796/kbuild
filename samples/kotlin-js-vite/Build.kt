/**
 * Kotlin/JS + Vite Demo
 *
 * Demonstrates Kotlin/JS compilation with Vite dev server:
 * - Compiles Kotlin to JavaScript (ES modules)
 * - Vite serves the JS with hot module replacement
 * - Watches for Kotlin source changes and recompiles
 *
 * Run with: kbuild -b Build Build.dev
 */
package demo.build

import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.context.CalculationContext
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.context.reactiveScope
import kotlinx.coroutines.*
import java.io.File

object Build {
    val projectRoot = File(".")
    val srcDir = projectRoot.resolve("src")
    val buildDir = projectRoot.resolve("build")
    val jsOutputDir = buildDir.resolve("js")
    val cacheDir = buildDir.resolve("cache")

    // Kotlin/JS standard library (klib format)
    val kotlinStdlibJs by lazy {
        Kotlin.standardLibraryJs.mapNotNull { it.default }.toSet()
    }

    /**
     * Compile Kotlin sources to JavaScript
     */
    fun compile(): File {
        println("Compiling Kotlin to JavaScript...")

        kotlinJsCompileBlocking(
            name = "app",
            sourceRoots = setOf(srcDir),
            libraries = kotlinStdlibJs,
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            cache = cacheDir,
            outputDir = jsOutputDir
        )

        println("  -> $jsOutputDir")
        return jsOutputDir
    }

    /**
     * Development mode with Vite
     *
     * Watches Kotlin sources, recompiles on change, Vite hot-reloads the browser.
     */
    fun dev() {
        println("=== Kotlin/JS + Vite Development Mode ===")
        println("Watching ${srcDir.absolutePath} for changes...")
        println()

        // Initial compilation
        try {
            compile()
        } catch (e: Exception) {
            println("Initial compilation failed: ${e.message}")
        }

        // Start Vite dev server
        println("Starting Vite dev server...")
        val viteProcess = ProcessBuilder("npx", "vite", "--host")
            .directory(projectRoot)
            .inheritIO()
            .start()

        // Watch for Kotlin source changes (uses native FSEvents on macOS)
        val sourceWatch = DirectoryWatch(srcDir, "**/*.kt", debounceMs = 300)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        var isFirstRun = true
        scope.reactiveScope {
            sourceWatch()  // Subscribe to changes

            if (isFirstRun) {
                isFirstRun = false
                return@reactiveScope
            }

            // Get the specific files that changed (provided by native watcher)
            val changedFiles = sourceWatch.getChangedFiles().map { it.relativeTo(srcDir.absoluteFile).path }
            println("\n[${java.time.LocalTime.now()}] Changed: ${changedFiles.joinToString(", ")}")

            try {
                val startTime = System.currentTimeMillis()
                compile()
                val duration = System.currentTimeMillis() - startTime
                println("Recompiled in ${duration}ms. Vite will hot-reload.")
            } catch (e: Exception) {
                println("Compilation failed: ${e.message}")
            }
        }

        // Handle shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
            viteProcess.destroyForcibly()
            scope.cancel()
        })

        // Wait for Vite to exit
        viteProcess.waitFor()
    }

    /**
     * Build for production
     */
    fun build() {
        compile()

        println("Building for production with Vite...")
        ProcessBuilder("npx", "vite", "build")
            .directory(projectRoot)
            .inheritIO()
            .start()
            .waitFor()

        println("Production build complete in build/dist/")
    }

    /**
     * Clean build outputs
     */
    fun clean() {
        println("Cleaning build directory...")
        buildDir.deleteRecursively()
        projectRoot.resolve("node_modules").deleteRecursively()
        println("Done.")
    }

    /**
     * Install npm dependencies (run once)
     */
    fun setup() {
        println("Installing npm dependencies...")
        ProcessBuilder("npm", "install")
            .directory(projectRoot)
            .inheritIO()
            .start()
            .waitFor()
        println("Setup complete. Run 'kbuild -b Build Build.dev' to start development.")
    }
}
