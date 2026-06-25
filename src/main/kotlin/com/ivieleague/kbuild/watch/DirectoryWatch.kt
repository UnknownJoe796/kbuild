package com.ivieleague.kbuild.watch

import com.lightningkite.reactive.core.BaseReactiveValue
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.hashing.FileHasher
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A reactive file watcher that monitors a directory for changes and provides
 * a reactive set of files matching a glob pattern.
 *
 * Uses native file system APIs via io.methvin:directory-watcher:
 * - macOS: FSEvents (efficient tree watching)
 * - Linux: inotify
 * - Windows: ReadDirectoryChangesW
 *
 * The watch is lazy - it only starts monitoring when the first listener is added,
 * and stops when the last listener is removed.
 *
 * The reactive value type is `Set<File>`, but content-only edits (same file set,
 * changed bytes) are also detected by tracking modification times. A monotonic
 * version counter embedded in the returned set instance makes `await()` /
 * `reactiveSuspending {}` detect mtime changes even though the file-path set is
 * structurally equal — no call sites need to change.
 *
 * @param root The root directory to watch
 * @param globPattern Glob pattern to filter files (e.g., "**&#47;*.kt")
 * @param debounceMs Debounce time in milliseconds to batch rapid changes
 */
class DirectoryWatch(
    val root: File,
    val globPattern: String = "**/*",
    val debounceMs: Long = 100
) : BaseReactiveValue<Set<File>>(VersionedFileSet(scanFiles(root, normalizeGlobPattern(globPattern)), 0L)) {

    /**
     * A `Set<File>` that embeds a monotonic version number so that content-only
     * edits (same paths, changed bytes) produce a value that compares NOT-equal
     * to the previous one.
     *
     * When both operands are `VersionedFileSet` the version is included in the
     * equality check; when compared to any other `Set` type the standard
     * content-based equality is used, preserving the `Set` contract for external
     * code.
     */
    private class VersionedFileSet(
        val files: Set<File>,
        val version: Long
    ) : AbstractSet<File>() {
        override val size: Int get() = files.size
        override fun iterator(): Iterator<File> = files.iterator()
        override fun contains(element: File): Boolean = files.contains(element)

        override fun equals(other: Any?): Boolean {
            if (other is VersionedFileSet) return version == other.version && files == other.files
            return super.equals(other)  // content-based equality for other Set types
        }

        // hashCode uses only file content so the Set contract holds for cross-type comparisons.
        override fun hashCode(): Int = files.hashCode()
    }

    private var watcher: DirectoryWatcher? = null
    private var watchThread: Thread? = null
    private var debounceExecutor: ScheduledExecutorService? = null
    private var debounceFuture: ScheduledFuture<*>? = null
    private val pathMatcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:${normalizeGlobPattern(globPattern)}")

    /** Files that changed - accumulated during debounce period */
    private val pendingChanges = mutableSetOf<File>()
    private val pendingChangesLock = Any()

    /** Snapshot of changed files from the last notification (available to listeners) */
    @Volatile
    private var lastChangedFiles: Set<File> = emptySet()

    /** Monotonically increasing counter; incremented on every detected change so that
     *  content-only edits produce a new VersionedFileSet that compares NOT-equal. */
    private var versionCounter: Long = 0L

    /** Last known modification times for change detection */
    private var lastModTimes: Map<File, Long> = value.associateWith { it.lastModified() }

    /** Cached canonical root path for event handling */
    private val canonicalRootPath by lazy { root.canonicalFile.toPath() }

    override fun activate() {
        debounceExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DirectoryWatch-debounce-${root.name}").apply { isDaemon = true }
        }

        try {
            // Use canonical path to resolve symlinks (important for macOS /var/folders symlink)
            val canonicalPath = root.canonicalFile.toPath()
            watcher = DirectoryWatcher.builder()
                .path(canonicalPath)
                // Use file hashing to detect content changes (not just create/delete)
                .fileHasher(FileHasher.LAST_MODIFIED_TIME)
                .listener { event -> handleEvent(event) }
                .build()

            // Start watching in background thread
            watchThread = Thread({
                try {
                    watcher?.watch()
                } catch (e: InterruptedException) {
                    // Normal shutdown
                } catch (e: Exception) {
                    if (watcher != null) {
                        System.err.println("DirectoryWatch error: ${e.message}")
                    }
                }
            }, "DirectoryWatch-${root.name}").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            System.err.println("Failed to create DirectoryWatcher: ${e.message}")
        }
    }

    private fun handleEvent(event: DirectoryChangeEvent) {
        val eventPath = event.path().toFile().canonicalFile.toPath()

        val relativePath = try {
            canonicalRootPath.relativize(eventPath)
        } catch (e: Exception) {
            return // Path not under root
        }

        // Get the actual file (using canonical path)
        val file = eventPath.toFile()

        // Check if file matches our glob pattern
        val matches = try {
            pathMatcher.matches(relativePath)
        } catch (e: Exception) {
            false
        }

        if (matches) {
            synchronized(pendingChangesLock) {
                pendingChanges.add(file)
            }
            scheduleRescan()
        } else if (event.eventType() == DirectoryChangeEvent.EventType.CREATE && file.isDirectory) {
            // New directory - might contain matching files
            scheduleRescan()
        }
    }

    override fun deactivate() {
        debounceFuture?.cancel(false)
        debounceExecutor?.shutdownNow()
        debounceExecutor = null
        try {
            watcher?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        watcher = null
        watchThread?.interrupt()
        watchThread = null
        synchronized(pendingChangesLock) {
            pendingChanges.clear()
        }
        lastChangedFiles = emptySet()
    }

    private fun scheduleRescan() {
        debounceFuture?.cancel(false)
        debounceFuture = debounceExecutor?.schedule({
            rescan()
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Get the files that changed in the most recent update.
     * This returns the files that triggered the current notification.
     */
    fun getChangedFiles(): Set<File> = lastChangedFiles

    /**
     * Manually trigger a rescan of the directory.
     * Detects both file additions/deletions and modifications.
     */
    fun rescan() {
        val newFiles = scanFilesWithMatcher(root, pathMatcher)
        val newModTimes = newFiles.associateWith { it.lastModified() }

        // Capture and clear pending changes BEFORE notifying
        val changedFiles: Set<File>
        synchronized(pendingChangesLock) {
            changedFiles = pendingChanges.toSet()
            pendingChanges.clear()
        }

        // Compare file paths (content-based Set equality) and mtimes.
        // Note: AbstractSet.equals() gives content-based comparison even though
        // `value` is a VersionedFileSet at runtime.
        val fileSetChanged = newFiles != value
        val anyFileModified = newModTimes != lastModTimes

        lastModTimes = newModTimes

        if (fileSetChanged || anyFileModified) {
            lastChangedFiles = changedFiles
            // Always use a new VersionedFileSet so that the reactive value
            // compares NOT-equal to the previous one via VersionedFileSet.equals().
            // This ensures await()/reactiveSuspending{} detects content-only edits
            // (where the file-path set is unchanged but mtimes differ).
            value = VersionedFileSet(newFiles, ++versionCounter)
        }
    }

    companion object {
        /**
         * Normalizes a glob pattern to work correctly with Java's PathMatcher.
         */
        private fun normalizeGlobPattern(pattern: String): String {
            if (pattern.startsWith("**/")) {
                return "{,**/}" + pattern.substring(3)
            }
            val idx = pattern.indexOf("/**/")
            if (idx >= 0) {
                val prefix = pattern.substring(0, idx + 1)
                val suffix = pattern.substring(idx + 4)
                return "$prefix{,**/}$suffix"
            }
            return pattern
        }

        private fun scanFiles(root: File, globPattern: String): Set<File> {
            if (!root.exists()) return emptySet()
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")
            return scanFilesWithMatcher(root, matcher)
        }

        private fun scanFilesWithMatcher(root: File, matcher: PathMatcher): Set<File> {
            if (!root.exists()) return emptySet()
            val rootPath = root.toPath()
            return root.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    try {
                        val relativePath = rootPath.relativize(file.toPath())
                        matcher.matches(relativePath)
                    } catch (e: Exception) {
                        false
                    }
                }
                .toSet()
        }
    }
}

/**
 * Creates a reactive directory watcher for the specified directory and pattern.
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
