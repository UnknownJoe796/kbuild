/**
 * Reactive Build Demo
 *
 * This demonstrates KBuild's reactive build capability - automatically recompiling
 * and retesting when source files change.
 *
 * Run with: kbuild -b Build Build.watch
 *
 * Or for a single build: kbuild -b Build Build.build
 */
package demo.build

import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.junit.junitRunBlocking
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.context.CalculationContext
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.context.reactiveScope
import com.lightningkite.reactive.core.constant
import kotlinx.coroutines.*
import java.io.File

object Build {
    val projectRoot = File(".")
    val srcDir = projectRoot.resolve("src")
    val buildDir = projectRoot.resolve("build")
    val classesDir = buildDir.resolve("classes")
    val testClassesDir = buildDir.resolve("test-classes")
    val cacheDir = buildDir.resolve("cache")

    // Dependencies
    val kotlinStdlib by lazy {
        MavenAether.librariesParallel(
            listOf("org.jetbrains.kotlin:kotlin-stdlib:2.2.0"),
            output = { println("  $it") }
        )
    }

    val testDependencies by lazy {
        MavenAether.librariesParallel(
            listOf(
                "org.jetbrains.kotlin:kotlin-test:2.2.0",
                "org.jetbrains.kotlin:kotlin-test-junit5:2.2.0",
                "org.junit.jupiter:junit-jupiter-api:5.10.0",
                "org.junit.jupiter:junit-jupiter-engine:5.10.0",
                "org.junit.platform:junit-platform-launcher:1.10.0"
            ),
            output = { println("  $it") }
        )
    }

    /**
     * Single build - compiles sources and runs tests once.
     */
    fun build() {
        println("=== Building... ===")
        val startTime = System.currentTimeMillis()

        // Compile main sources
        println("Compiling main sources...")
        kotlinJvmCompileBlocking(
            name = "demo-main",
            sourceRoots = setOf(srcDir),
            classpathJars = kotlinStdlib,
            arguments = {},
            cache = cacheDir.resolve("main"),
            outputFolder = classesDir
        )
        println("  -> $classesDir")

        // Compile test sources
        println("Compiling test sources...")
        kotlinJvmCompileBlocking(
            name = "demo-test",
            sourceRoots = setOf(srcDir),
            classpathJars = kotlinStdlib + testDependencies + setOf(classesDir),
            arguments = {},
            cache = cacheDir.resolve("test"),
            outputFolder = testClassesDir
        )
        println("  -> $testClassesDir")

        // Run tests
        println("Running tests...")
        val results = junitRunBlocking(
            testModule = testClassesDir,
            classpath = setOf(classesDir) + kotlinStdlib + testDependencies
        )

        val elapsed = System.currentTimeMillis() - startTime
        println()
        println("=== Build complete in ${elapsed}ms ===")
        println("Tests: ${results.passed} passed, ${results.failed} failed, ${results.skipped} skipped")

        if (results.failed > 0) {
            println("\nFailed tests:")
            results.failures.forEach { failure ->
                println("  - ${failure.testId}: ${failure.message}")
            }
        }
    }

    /**
     * Watch mode - continuously rebuilds when sources change.
     *
     * This demonstrates KBuild's reactive capability:
     * - DirectoryWatch monitors the src/ directory for .kt file changes
     * - When changes are detected, compilation and tests automatically rerun
     * - Uses debouncing to batch rapid changes (default 100ms)
     */
    fun watch() {
        println("=== Reactive Build Demo ===")
        println("Watching ${srcDir.absolutePath} for changes...")
        println("Press Ctrl+C to stop.\n")

        // Create reactive file watcher
        val sourceWatch = DirectoryWatch(srcDir, "**/*.kt", debounceMs = 200)

        // Run initial build
        println("Initial build...")
        build()
        println()

        // Set up reactive scope that reruns when sources change
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        scope.reactiveScope {
            // Access the reactive value - this registers the dependency
            val sources = sourceWatch()

            // This block re-executes whenever sources change
            println("\n[${java.time.LocalTime.now()}] Detected ${sources.size} source files")
            println("Rebuilding...")

            try {
                build()
            } catch (e: Exception) {
                println("Build failed: ${e.message}")
                e.printStackTrace()
            }
        }

        // Keep the main thread alive
        runBlocking {
            // Wait forever (until Ctrl+C)
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
