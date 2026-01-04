/**
 * Hot Reload Demo
 *
 * This demonstrates KBuild's hot reload capability for server development:
 * - Watches source files for changes
 * - Recompiles when files change
 * - Restarts the server with the new code
 *
 * Run with: kbuild -b Build Build.dev
 */
package demo.build

import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.ivieleague.kbuild.server.ServerProcess
import com.lightningkite.reactive.context.CalculationContext
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.context.reactiveScope
import kotlinx.coroutines.*
import java.io.File

object Build {
    val projectRoot = File(".")
    val srcDir = projectRoot.resolve("src")
    val buildDir = projectRoot.resolve("build")
    val classesDir = buildDir.resolve("classes")
    val cacheDir = buildDir.resolve("cache")

    // Dependencies
    val kotlinStdlib by lazy {
        MavenAether.librariesParallelBlocking(
            path = "org.jetbrains.kotlin:kotlin-stdlib:2.2.0",
            output = System.out
        ).mapNotNull { it.default }.toSet()
    }

    /**
     * Compile the server
     */
    fun compile(): File {
        println("Compiling...")
        classesDir.mkdirs()

        kotlinJvmCompileBlocking(
            name = "hot-reload-server",
            sourceRoots = setOf(srcDir),
            classpathJars = kotlinStdlib,
            arguments = {},
            cache = cacheDir,
            outputFolder = classesDir
        )

        println("  -> $classesDir")
        return classesDir
    }

    /**
     * Run the server once
     */
    fun run() {
        compile()

        println("Starting server...")
        val classpath = (kotlinStdlib + setOf(classesDir))
            .joinToString(File.pathSeparator) { it.absolutePath }

        ProcessBuilder("java", "-cp", classpath, "demo.server.ServerKt")
            .inheritIO()
            .start()
            .waitFor()
    }

    /**
     * Development mode with hot reload
     *
     * This is the killer feature! Watches source files and automatically
     * recompiles and restarts the server when changes are detected.
     */
    fun dev() {
        println("=== Hot Reload Development Mode ===")
        println("Watching ${srcDir.absolutePath} for changes...")
        println("Server will restart automatically when you save changes.")
        println("Press Ctrl+C to stop.\n")

        // Create reactive file watcher
        val sourceWatch = DirectoryWatch(srcDir, "**/*.kt", debounceMs = 300)

        // Track current server process
        var serverProcess: Process? = null

        fun startServer() {
            // Kill existing server if running
            serverProcess?.let { proc ->
                if (proc.isAlive) {
                    println("Stopping previous server...")
                    proc.destroyForcibly()
                    proc.waitFor()
                }
            }

            // Compile
            try {
                compile()
            } catch (e: Exception) {
                println("Compilation failed: ${e.message}")
                return
            }

            // Start new server
            println("Starting server...")
            val classpath = (kotlinStdlib + setOf(classesDir))
                .joinToString(File.pathSeparator) { it.absolutePath }

            serverProcess = ProcessBuilder("java", "-cp", classpath, "demo.server.ServerKt")
                .inheritIO()
                .start()

            println("Server started. Open http://localhost:8080 in your browser.\n")
        }

        // Initial build and start
        startServer()

        // Set up reactive scope that reruns when sources change
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        var lastChangeTime = 0L
        scope.reactiveScope {
            // Access the reactive value - this registers the dependency
            val sources = sourceWatch()
            val currentTime = System.currentTimeMillis()

            // Skip the initial run (we already started)
            if (lastChangeTime == 0L) {
                lastChangeTime = currentTime
                return@reactiveScope
            }

            // This block re-executes whenever sources change
            println("\n[${java.time.LocalTime.now()}] Detected changes in ${sources.size} source files")
            lastChangeTime = currentTime

            startServer()
        }

        // Handle shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
            serverProcess?.destroyForcibly()
            scope.cancel()
        })

        // Keep the main thread alive
        runBlocking {
            awaitCancellation()
        }
    }

    /**
     * Clean build outputs
     */
    fun clean() {
        println("Cleaning build directory...")
        buildDir.deleteRecursively()
        println("Done.")
    }
}
