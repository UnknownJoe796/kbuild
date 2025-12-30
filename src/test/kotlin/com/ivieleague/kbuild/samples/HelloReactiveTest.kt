package com.ivieleague.kbuild.samples

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.ReactiveKotlinCompile
import com.ivieleague.kbuild.kotlin.asReactive
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test that exercises the reactive build system with a real project.
 */
class HelloReactiveTest {

    @Test
    fun `builds sample project reactively`() {
        // Use the actual sample project
        val projectRoot = File("samples/hello-reactive")
        val srcDir = projectRoot.resolve("src/main/kotlin")
        val buildDir = projectRoot.resolve("build")
        val cacheDir = buildDir.resolve("cache")
        val outputDir = buildDir.resolve("classes")

        // Clean build directory
        buildDir.deleteRecursively()
        buildDir.mkdirs()

        println("Project root: ${projectRoot.absolutePath}")
        println("Source dir: ${srcDir.absolutePath}")
        println("Source exists: ${srcDir.exists()}")

        // Get dependencies
        val dependencies = MavenAether.libraries(Kotlin.standardLibraryJvmId)
            .map { it.default }
            .toSet()
        println("Dependencies: ${dependencies.size} jars")

        // Create reactive source watcher
        val sources = DirectoryWatch(srcDir, "**/*.kt")
        println("Initial sources: ${sources.value.size} files")
        sources.value.forEach { println("  - ${it.relativeTo(srcDir)}") }

        // Create reactive compilation
        val compile = ReactiveKotlinCompile(
            name = "hello-reactive",
            sources = sources,
            classpath = dependencies.asReactive(),
            cache = cacheDir,
            outputFolder = outputDir
        )

        val latch = CountDownLatch(1)
        var buildSucceeded = false
        var buildError: String? = null

        val removeListener = compile.addListener {
            val state = compile.state
            println("State: ready=${state.ready}, success=${state.success}, exception=${state.exception}")
            when {
                state.success -> {
                    buildSucceeded = true
                    latch.countDown()
                }
                state.exception != null -> {
                    buildError = state.exception?.message
                    latch.countDown()
                }
            }
        }

        try {
            val completed = latch.await(120, TimeUnit.SECONDS)
            assertTrue(completed, "Build should complete within timeout")

            if (buildError != null) {
                println("Build error: $buildError")
            }
            assertTrue(buildSucceeded, "Build should succeed. Error: $buildError")

            // Verify output
            val classFiles = outputDir.walkTopDown().filter { it.extension == "class" }.toList()
            println("Compiled ${classFiles.size} class files:")
            classFiles.forEach { println("  - ${it.relativeTo(outputDir)}") }

            assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")
            assertTrue(
                classFiles.any { it.name == "HelloKt.class" },
                "Should have HelloKt.class"
            )
        } finally {
            removeListener()
        }
    }

    @Test
    fun `detects file changes and recompiles`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "kbuild-reactive-test-${System.currentTimeMillis()}")
        val srcDir = tempDir.resolve("src").also { it.mkdirs() }
        val buildDir = tempDir.resolve("build")
        val cacheDir = buildDir.resolve("cache")
        val outputDir = buildDir.resolve("classes")

        try {
            // Create initial source
            srcDir.resolve("App.kt").writeText("""
                package test
                fun version() = 1
            """.trimIndent())

            val dependencies = MavenAether.libraries(Kotlin.standardLibraryJvmId)
                .map { it.default }
                .toSet()

            val sources = DirectoryWatch(srcDir, "**/*.kt")

            val compile = ReactiveKotlinCompile(
                name = "test-module",
                sources = sources,
                classpath = dependencies.asReactive(),
                cache = cacheDir,
                outputFolder = outputDir
            )

            var buildCount = 0
            val secondBuildLatch = CountDownLatch(1)

            val removeListener = compile.addListener {
                if (compile.state.success) {
                    buildCount++
                    println("Build #$buildCount completed")

                    if (buildCount == 1) {
                        // After first build, modify the source and trigger rescan
                        // (WatchService on macOS is slow, so we manually trigger)
                        Thread {
                            Thread.sleep(500) // Wait for things to settle
                            println("Modifying source file...")
                            srcDir.resolve("App.kt").writeText("""
                                package test
                                fun version() = 2  // Changed!
                            """.trimIndent())
                            Thread.sleep(100) // Ensure file is written
                            println("Triggering rescan...")
                            sources.rescan() // Manually trigger rescan for test reliability
                        }.start()
                    } else if (buildCount >= 2) {
                        secondBuildLatch.countDown()
                    }
                }
            }

            try {
                val completed = secondBuildLatch.await(60, TimeUnit.SECONDS)
                assertTrue(completed, "Should detect change and rebuild")
                assertTrue(buildCount >= 2, "Should have built at least twice, got $buildCount")
            } finally {
                removeListener()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
