package com.ivieleague.kbuild.kotlin

import com.lightningkite.reactive.context.reactiveSuspending
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the --watch silent-inert bug.
 *
 * The bug: standard build targets called `kotlinJvmCompileBlocking` (and the KMP *Blocking
 * variants) which accept a plain `Set<File>`.  When wrapped in a `reactiveSuspending {}` loop
 * by the CLI, no `Reactive<T>` was ever accessed via `invoke()`/`await()`, so the reactive
 * dependency tracker never registered anything and the loop never re-triggered on source changes.
 *
 * The fix: standard build methods now delegate to the reactive `kotlinJvmCompile` (and
 * `kmpCompileJvm*` equivalents) which accept `Reactive<Set<File>>` and call `sourceRoots()`
 * inside the reactive scope, registering the source watch as a dependency.
 *
 * This test drives [kotlinJvmCompile] directly with a [Signal] (a mutable Reactive) instead of
 * a real [DirectoryWatch].  Swapping the Signal value is synchronous and deterministic — no
 * filesystem-event timing is involved.  The test proves that:
 *  1. The first compilation runs exactly once.
 *  2. Changing the Signal value triggers a second compilation run.
 *  3. The second run sees the new source set.
 */
class ReactiveCompileWatchTest {

    private val jvmStdlib: Set<File> by lazy {
        runBlocking { Kotlin.standardLibraryJvm().mapNotNull { it.default }.toSet() }
    }

    @Test
    fun `kotlinJvmCompile reruns inside reactiveSuspending when sourceRoots signal changes`() {
        val root = File("build/run/ReactiveCompileWatchTest/jvm-signal-rerun")
        root.deleteRecursively()

        // Two small source directories with one class each
        val srcDir1 = root.resolve("src1").also { it.mkdirs() }
        srcDir1.resolve("ClassOne.kt").writeText("class ClassOne")

        val srcDir2 = root.resolve("src2").also { it.mkdirs() }
        srcDir2.resolve("ClassTwo.kt").writeText("class ClassTwo")

        val outputDir = root.resolve("output")
        val cacheDir  = root.resolve("cache")

        // Signal starts pointing at srcDir1.  Passed directly to kotlinJvmCompile as the
        // Reactive<Set<File>> sourceRoots argument so that invoke() registers it as a dependency.
        val sourceSignal = Signal<Set<File>>(setOf(srcDir1))

        val compiledCount = AtomicInteger(0)
        val firstDone  = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // reactiveSuspending registers a DependencyChangeListener in the coroutine context.
            // kotlinJvmCompile calls sourceRoots() which calls await() on the Signal, registering
            // it as a dependency.  When signal.value changes, onDependencyChange() fires and
            // startCalculation() re-runs this block.
            scope.reactiveSuspending {
                kotlinJvmCompile(
                    name        = "reactive-watch-test",
                    sourceRoots = sourceSignal,
                    classpathJars = Constant(jvmStdlib),
                    cache         = cacheDir,
                    outputFolder  = outputDir
                )
                when (compiledCount.incrementAndGet()) {
                    1 -> firstDone.countDown()
                    2 -> secondDone.countDown()
                }
            }

            // Wait for the first compile (first-build; no cache yet — allow generous timeout)
            assertTrue(
                firstDone.await(120, TimeUnit.SECONDS),
                "First compilation should complete within timeout"
            )
            assertEquals(1, compiledCount.get(), "Compiled exactly once after initial setup")

            // Verify the first source was compiled
            val classesAfterFirst = outputDir.walkTopDown()
                .filter { it.extension == "class" }
                .map { it.name }
                .toSet()
            assertTrue(
                "ClassOne.class" in classesAfterFirst,
                "ClassOne.class should be present after first compile; found: $classesAfterFirst"
            )

            // ---- The key reactivity assertion ----
            // Changing the signal value triggers onDependencyChange() synchronously, which calls
            // startCalculation() and schedules the reactiveSuspending block to re-run.
            sourceSignal.value = setOf(srcDir2)

            assertTrue(
                secondDone.await(120, TimeUnit.SECONDS),
                "Second compilation should be triggered by signal change and complete within timeout"
            )
            assertEquals(2, compiledCount.get(), "Compiled a second time after signal change")

            // Verify the second source was compiled
            val classesAfterSecond = outputDir.walkTopDown()
                .filter { it.extension == "class" }
                .map { it.name }
                .toSet()
            assertTrue(
                "ClassTwo.class" in classesAfterSecond,
                "ClassTwo.class should be present after second compile; found: $classesAfterSecond"
            )
        } finally {
            scope.cancel()
        }
    }
}
