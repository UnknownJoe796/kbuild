package com.ivieleague.kbuild.watch

import com.lightningkite.reactive.core.BaseReactiveValue
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * A reactive file watcher that monitors a directory for changes and provides
 * a reactive set of files matching a glob pattern.
 *
 * The watch is lazy - it only starts monitoring when the first listener is added,
 * and stops when the last listener is removed.
 *
 * @param root The root directory to watch
 * @param globPattern Glob pattern to filter files (e.g., "&#42;&#42;/&#42;.kt")
 * @param debounceMs Debounce time in milliseconds to batch rapid changes
 */
class DirectoryWatch(
    val root: File,
    val globPattern: String = "**/*",
    val debounceMs: Long = 100
) : BaseReactiveValue<Set<File>>(scanFiles(root, normalizeGlobPattern(globPattern))) {

    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private var debounceExecutor: ScheduledExecutorService? = null
    private var debounceFuture: ScheduledFuture<*>? = null
    private val pathMatcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:${normalizeGlobPattern(globPattern)}")
    private var lastModTimes: Map<File, Long> = value.associateWith { it.lastModified() }

    override fun activate() {
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        debounceExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DirectoryWatch-debounce-${root.name}").apply { isDaemon = true }
        }

        // Register all directories recursively
        registerDirectories(root.toPath(), ws)

        // Start watch thread
        watchThread = Thread({
            try {
                while (true) {
                    val key = ws.take()
                    val dir = key.watchable() as Path

                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue

                        @Suppress("UNCHECKED_CAST")
                        val ev = event as WatchEvent<Path>
                        val changedPath = dir.resolve(ev.context())

                        // If a new directory was created, register it
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE && changedPath.isDirectory()) {
                            registerDirectories(changedPath, ws)
                        }

                        // Schedule debounced rescan
                        scheduleRescan()
                    }

                    if (!key.reset()) {
                        break
                    }
                }
            } catch (e: ClosedWatchServiceException) {
                // Normal shutdown
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "DirectoryWatch-${root.name}")
        watchThread?.isDaemon = true
        watchThread?.start()
    }

    override fun deactivate() {
        debounceFuture?.cancel(false)
        debounceExecutor?.shutdownNow()
        debounceExecutor = null
        watchService?.close()
        watchService = null
        watchThread?.interrupt()
        watchThread = null
    }

    private fun registerDirectories(start: Path, ws: WatchService) {
        if (!start.toFile().exists()) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    dir.register(
                        ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                    )
                } catch (e: Exception) {
                    // Directory might have been deleted
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun scheduleRescan() {
        debounceFuture?.cancel(false)
        debounceFuture = debounceExecutor?.schedule({
            rescan()
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Manually trigger a rescan of the directory.
     * Useful for testing or when immediate updates are needed.
     * Detects both file additions/deletions and modifications (by checking lastModified).
     */
    fun rescan() {
        // Use cached pathMatcher for efficiency
        val newFiles = scanFilesWithMatcher(root, pathMatcher)
        val newModTimes = newFiles.associateWith { it.lastModified() }

        // Check if file set changed OR if any file was modified
        val fileSetChanged = newFiles != value
        val anyFileModified = newModTimes != lastModTimes

        lastModTimes = newModTimes

        if (fileSetChanged) {
            value = newFiles
        } else if (anyFileModified) {
            // File content changed but set is same - need to notify listeners manually
            // because BaseReactiveValue only notifies on value change
            notifyListeners()
        }
    }

    /**
     * Force notification of all listeners.
     * Used when files are modified in-place without adding/removing files.
     */
    fun notifyListeners() {
        invokeAllListeners()
    }

    companion object {
        /**
         * Normalizes a glob pattern to work correctly with Java's PathMatcher.
         *
         * Java's PathMatcher interprets double-star-slash patterns as matching only files
         * in subdirectories, not files in the root. This function converts such patterns
         * to use alternation syntax which matches files at any depth including the root.
         *
         * For example: "STAR-STAR/x.kt" becomes "{,STAR-STAR/}x.kt"
         */
        private fun normalizeGlobPattern(pattern: String): String {
            // Handle patterns starting with **/
            if (pattern.startsWith("**/")) {
                return "{,**/}" + pattern.substring(3)
            }
            // Handle patterns with /**/ in the middle (e.g., src/**/*.kt)
            val idx = pattern.indexOf("/**/")
            if (idx >= 0) {
                val prefix = pattern.substring(0, idx + 1) // include the /
                val suffix = pattern.substring(idx + 4)    // skip /**/
                return "$prefix{,**/}$suffix"
            }
            return pattern
        }

        /**
         * Initial scan used in constructor (creates PathMatcher).
         */
        private fun scanFiles(root: File, globPattern: String): Set<File> {
            if (!root.exists()) return emptySet()
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")
            return scanFilesWithMatcher(root, matcher)
        }

        /**
         * Optimized scan using a pre-created PathMatcher (avoids recreation on every rescan).
         */
        private fun scanFilesWithMatcher(root: File, matcher: PathMatcher): Set<File> {
            if (!root.exists()) return emptySet()
            val rootPath = root.toPath()
            return root.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    val relativePath = rootPath.relativize(file.toPath())
                    matcher.matches(relativePath)
                }
                .toSet()
        }
    }
}

/**
 * Creates a reactive directory watcher for the specified directory and pattern.
 *
 * @param pattern Glob pattern to filter files (e.g., "&#42;&#42;/&#42;.kt")
 * @param debounceMs Debounce time in milliseconds
 */
fun File.watch(pattern: String = "**/*", debounceMs: Long = 100): DirectoryWatch {
    return DirectoryWatch(this, pattern, debounceMs)
}

/**
 * Convenience function to watch a directory for Kotlin source files.
 */
fun File.watchKotlin(debounceMs: Long = 100): DirectoryWatch {
    return DirectoryWatch(this, "**/*.kt", debounceMs)
}

/**
 * Convenience function to watch a directory for Java source files.
 */
fun File.watchJava(debounceMs: Long = 100): DirectoryWatch {
    return DirectoryWatch(this, "**/*.java", debounceMs)
}
