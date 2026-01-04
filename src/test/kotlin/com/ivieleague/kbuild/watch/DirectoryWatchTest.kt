package com.ivieleague.kbuild.watch

import com.lightningkite.reactive.context.CalculationContext
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.context.reactiveScope
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryWatchTest {

    @Test
    fun `scans initial files correctly`() {
        val tempDir = createTempDir("watch-test")
        try {
            // Create some test files
            tempDir.resolve("test1.kt").writeText("// test 1")
            tempDir.resolve("test2.kt").writeText("// test 2")
            tempDir.resolve("test.java").writeText("// java file")
            tempDir.resolve("subdir").mkdir()
            tempDir.resolve("subdir/test3.kt").writeText("// test 3")

            val watch = DirectoryWatch(tempDir, "**/*.kt")

            // Should have 3 Kotlin files
            assertEquals(3, watch.value.size)
            assertTrue(watch.value.any { it.name == "test1.kt" })
            assertTrue(watch.value.any { it.name == "test2.kt" })
            assertTrue(watch.value.any { it.name == "test3.kt" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detects file changes when listener is active`() {
        val tempDir = createTempDir("watch-test")
        try {
            // Create initial file
            tempDir.resolve("test1.kt").writeText("// test 1")

            val watch = DirectoryWatch(tempDir, "**/*.kt", debounceMs = 50)

            val latch = CountDownLatch(1)
            var changeDetected = false

            // Add listener to activate watching
            val removeListener = watch.addListener {
                if (watch.value.size == 2) {
                    changeDetected = true
                    latch.countDown()
                }
            }

            try {
                // Initial state
                assertEquals(1, watch.value.size)

                // Add a new file
                Thread.sleep(100) // Give watcher time to start
                tempDir.resolve("test2.kt").writeText("// test 2")

                // Wait for change detection
                val detected = latch.await(3, TimeUnit.SECONDS)

                assertTrue(detected, "Change should be detected within timeout")
                assertTrue(changeDetected, "Listener should have been called with 2 files")
                assertEquals(2, watch.value.size)
            } finally {
                removeListener()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `stops watching when all listeners are removed`() {
        val tempDir = createTempDir("watch-test")
        try {
            tempDir.resolve("test1.kt").writeText("// test 1")

            val watch = DirectoryWatch(tempDir, "**/*.kt", debounceMs = 50)

            // Add and remove listener
            val removeListener = watch.addListener {}
            Thread.sleep(100) // Give time to start
            removeListener()
            Thread.sleep(100) // Give time to stop

            // Add a file while not watching
            val beforeCount = watch.value.size
            tempDir.resolve("test2.kt").writeText("// test 2")
            Thread.sleep(200)

            // Value should not have changed since we're not watching
            // (Note: the value won't update until a listener is added again)
            assertEquals(beforeCount, watch.value.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detects file modifications`() {
        val tempDir = createTempDir("watch-test")
        try {
            // Create initial file
            val testFile = tempDir.resolve("test1.kt")
            testFile.writeText("// version 1")
            Thread.sleep(100) // Ensure timestamp is different

            val watch = DirectoryWatch(tempDir, "**/*.kt", debounceMs = 50)

            val latch = CountDownLatch(1)
            var modificationDetected = false
            var changedFilesList: Set<File> = emptySet()

            // Add listener to activate watching
            val removeListener = watch.addListener {
                // Check if this is a modification (not initial run)
                val changed = watch.getChangedFiles()
                if (changed.isNotEmpty()) {
                    modificationDetected = true
                    changedFilesList = changed
                    latch.countDown()
                }
            }

            try {
                // Give watcher time to start
                Thread.sleep(500)

                // Modify the file and force sync to disk (required for macOS FSEvents)
                java.io.FileOutputStream(testFile).use { fos ->
                    fos.write("// version 2".toByteArray())
                    fos.fd.sync()
                }

                // Wait for change detection
                val detected = latch.await(5, TimeUnit.SECONDS)

                assertTrue(detected, "Modification should be detected within timeout")
                assertTrue(modificationDetected, "Modification flag should be set")
                assertTrue(changedFilesList.any { it.name == "test1.kt" }, "Changed files should include test1.kt")
            } finally {
                removeListener()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `works with reactiveScope like Build_kt uses it`() {
        val tempDir = createTempDir("watch-reactive-test")
        try {
            // Create initial file
            val testFile = tempDir.resolve("test1.kt")
            testFile.writeText("// version 1")
            Thread.sleep(100)

            val sourceWatch = DirectoryWatch(tempDir, "**/*.kt", debounceMs = 100)
            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
            )

            val latch = CountDownLatch(1)
            var rebuildTriggered = false
            var isFirstRun = true

            // This is exactly how Build.kt uses it (CoroutineScope is a CalculationContext)
            scope.reactiveScope {
                sourceWatch()  // Subscribe to changes

                if (isFirstRun) {
                    isFirstRun = false
                    return@reactiveScope
                }

                // This block re-executes when sources change
                rebuildTriggered = true
                latch.countDown()
            }

            // Give watcher time to start
            Thread.sleep(500)

            // Modify the file
            java.io.FileOutputStream(testFile).use { fos ->
                fos.write("// version 2".toByteArray())
                fos.fd.sync()
            }

            // Wait for rebuild to be triggered
            val triggered = latch.await(5, TimeUnit.SECONDS)

            scope.cancel()

            assertTrue(triggered, "Rebuild should be triggered within timeout")
            assertTrue(rebuildTriggered, "rebuildTriggered flag should be set")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}
