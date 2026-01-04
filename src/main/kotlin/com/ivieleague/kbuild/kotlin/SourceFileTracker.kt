package com.ivieleague.kbuild.kotlin

import java.io.File

/**
 * Tracks source file modifications to enable incremental compilation.
 *
 * This class maintains a persistent record of source files and their modification times,
 * allowing it to compute which files have been modified or removed since the last build.
 *
 * The tracker persists its state to a sibling directory of the cache, since the Kotlin
 * incremental compiler may clean the cache directory. The tracking file is stored in
 * `<cache>-tracker/source-file-tracker.txt`.
 *
 * @param cacheDir The directory used for incremental compilation cache
 */
class SourceFileTracker(private val cacheDir: File) {

    // Store tracking file OUTSIDE the cache dir because the Kotlin incremental compiler
    // may clean the cache directory during compilation
    private val trackerDir = cacheDir.parentFile.resolve("${cacheDir.name}-tracker")
    private val trackingFile = trackerDir.resolve("source-file-tracker.txt")

    /**
     * Represents the changes detected since the last tracked state.
     */
    data class Changes(
        /** Files that are new or have been modified (lastModified changed) */
        val modified: List<File>,
        /** Files that existed before but are now missing */
        val removed: List<File>,
        /** Whether this is the first build (no previous state) */
        val isFirstBuild: Boolean
    ) {
        /** True if there are no changes (nothing modified or removed) */
        val isEmpty: Boolean get() = modified.isEmpty() && removed.isEmpty()

        /** True if all files changed (first build or major changes) */
        val isFullRebuild: Boolean get() = isFirstBuild
    }

    /**
     * Computes changes between the current source files and the last tracked state.
     *
     * After calling this method, the tracker state is updated with the current files.
     * Call this at the START of compilation to get what changed.
     *
     * @param currentFiles All current source files
     * @return Changes detected since last build
     */
    fun computeChanges(currentFiles: List<File>): Changes {
        val previousState = loadState()
        val isFirstBuild = previousState.isEmpty()

        if (isFirstBuild) {
            // First build - save current state and report full rebuild
            saveState(currentFiles)
            return Changes(
                modified = currentFiles,
                removed = emptyList(),
                isFirstBuild = true
            )
        }

        // Compute modifications - files that are new or have changed lastModified
        val currentFileMap = currentFiles.associateBy { it.absolutePath }
        val modified = mutableListOf<File>()
        val removed = mutableListOf<File>()

        // Check for new or modified files
        for (file in currentFiles) {
            val previousModTime = previousState[file.absolutePath]
            val currentModTime = file.lastModified()

            if (previousModTime == null) {
                // New file
                modified.add(file)
            } else if (previousModTime != currentModTime) {
                // Modified file
                modified.add(file)
            }
        }

        // Check for removed files
        for (previousPath in previousState.keys) {
            if (!currentFileMap.containsKey(previousPath)) {
                removed.add(File(previousPath))
            }
        }

        // Save current state for next build
        saveState(currentFiles)

        return Changes(
            modified = modified,
            removed = removed,
            isFirstBuild = false
        )
    }

    /**
     * Resets the tracker state, forcing a full rebuild on next compilation.
     */
    fun reset() {
        if (trackingFile.exists()) {
            trackingFile.delete()
        }
    }

    /**
     * Loads the previous state from disk.
     * @return Map of absolute file path to last modification time
     */
    private fun loadState(): Map<String, Long> {
        if (!trackingFile.exists()) return emptyMap()

        return try {
            trackingFile.readLines()
                .filter { it.isNotBlank() && it.contains('\t') }
                .associate { line ->
                    val parts = line.split('\t', limit = 2)
                    parts[1] to parts[0].toLong()
                }
        } catch (e: Exception) {
            // If we can't read the file, treat as first build
            emptyMap()
        }
    }

    /**
     * Saves the current state to disk.
     */
    private fun saveState(files: List<File>) {
        try {
            trackerDir.mkdirs()
            val content = files.joinToString("\n") { file ->
                "${file.lastModified()}\t${file.absolutePath}"
            }
            trackingFile.writeText(content)
        } catch (e: Exception) {
            // Silently ignore write failures - worst case is we do a full rebuild
        }
    }

    companion object {
        /**
         * Gets or creates a tracker for a specific cache directory.
         * This maintains a cache of trackers to avoid re-reading state unnecessarily.
         */
        private val trackers = mutableMapOf<String, SourceFileTracker>()

        fun forCache(cacheDir: File): SourceFileTracker {
            val key = cacheDir.absolutePath
            return trackers.getOrPut(key) { SourceFileTracker(cacheDir) }
        }
    }
}
