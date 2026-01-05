package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.incremental.ClasspathChanges
import org.jetbrains.kotlin.incremental.ClasspathSnapshotFiles
import java.io.File

/**
 * Manages classpath snapshots for incremental compilation.
 *
 * Classpath snapshotting enables true incremental compilation by tracking which
 * classes in the classpath have changed. Without it, any source file change
 * triggers a full rebuild.
 *
 * Snapshots are cached based on JAR path and modification time, so unchanged
 * JARs don't need to be re-analyzed.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class ClasspathSnapshotManager(cacheDir: File) {
    // Use canonical paths to normalize and avoid issues with working directory changes
    private val cacheDir = cacheDir.canonicalFile
    // Put snapshots OUTSIDE the cache dir to avoid deletion by IncrementalCompilerRunner
    // which cleans its working directory on first builds
    private val snapshotDir = this.cacheDir.parentFile.resolve("classpath-snapshots")
    private val metadataFile = snapshotDir.resolve("metadata.txt")

    // Lazy-load the compilation service
    private val compilationService: CompilationService by lazy {
        CompilationService.loadImplementation(ClasspathSnapshotManager::class.java.classLoader)
    }

    /**
     * Creates ClasspathChanges for the incremental compiler.
     *
     * @param classpathJars The current classpath JARs
     * @param isFirstBuild Whether this is the first build (no previous state)
     * @return ClasspathChanges to pass to IncrementalJvmCompilerRunner
     */
    fun createClasspathChanges(
        classpathJars: Set<File>,
        isFirstBuild: Boolean
    ): ClasspathChanges {
        // Ensure the snapshot directory exists (compiler will write shrunk-classpath-snapshot.bin here)
        snapshotDir.mkdirs()

        // Generate or load cached snapshots for each classpath entry
        val snapshotFiles = mutableListOf<File>()
        val previousMetadata = loadMetadata()
        val currentMetadata = mutableMapOf<String, Long>()

        for (jar in classpathJars) {
            if (!jar.exists()) continue

            val jarPath = jar.absolutePath
            val jarModTime = jar.lastModified()
            val snapshotFile = getSnapshotFile(jar)

            // Check if we have a valid cached snapshot
            val cachedModTime = previousMetadata[jarPath]
            if (cachedModTime == jarModTime && snapshotFile.exists()) {
                // Use cached snapshot
                snapshotFiles.add(snapshotFile)
                currentMetadata[jarPath] = jarModTime
            } else {
                // Generate new snapshot
                try {
                    if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                        println("  Generating snapshot for ${jar.name}")
                    }
                    val snapshot = compilationService.calculateClasspathSnapshot(
                        jar,
                        ClassSnapshotGranularity.CLASS_LEVEL
                    )
                    snapshotFile.parentFile?.mkdirs()
                    snapshot.saveSnapshot(snapshotFile)
                    snapshotFiles.add(snapshotFile)
                    currentMetadata[jarPath] = jarModTime
                } catch (e: Exception) {
                    if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                        println("  Failed to snapshot ${jar.name}: ${e.message}")
                    }
                    // Skip this JAR - compilation will still work, just less incrementally optimal
                }
            }
        }

        // Save updated metadata
        saveMetadata(currentMetadata)

        val classpathSnapshotFiles = ClasspathSnapshotFiles(
            currentClasspathEntrySnapshotFiles = snapshotFiles,
            classpathSnapshotDir = snapshotDir
        )

        return if (isFirstBuild || !classpathSnapshotFiles.shrunkPreviousClasspathSnapshotFile.exists()) {
            // First build or missing previous snapshot - let compiler compute everything
            ClasspathChanges.ClasspathSnapshotEnabled.NotAvailableDueToMissingClasspathSnapshot(
                classpathSnapshotFiles
            )
        } else {
            // Incremental build - let compiler compute classpath changes
            ClasspathChanges.ClasspathSnapshotEnabled.IncrementalRun.ToBeComputedByIncrementalCompiler(
                classpathSnapshotFiles
            )
        }
    }

    /**
     * Gets the snapshot file path for a JAR.
     * Uses a hash of the JAR path to avoid filesystem issues with long paths.
     */
    private fun getSnapshotFile(jar: File): File {
        val hash = jar.absolutePath.hashCode().toUInt().toString(16)
        val safeName = jar.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        return snapshotDir.resolve("$safeName-$hash.snapshot")
    }

    /**
     * Loads the metadata file mapping JAR paths to their modification times.
     */
    private fun loadMetadata(): Map<String, Long> {
        if (!metadataFile.exists()) return emptyMap()

        return try {
            metadataFile.readLines()
                .filter { it.contains('\t') }
                .associate { line ->
                    val parts = line.split('\t', limit = 2)
                    parts[1] to parts[0].toLong()
                }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Saves the metadata file.
     */
    private fun saveMetadata(metadata: Map<String, Long>) {
        try {
            val content = metadata.entries.joinToString("\n") { (path, modTime) ->
                "$modTime\t$path"
            }
            metadataFile.writeText(content)
        } catch (e: Exception) {
            // Ignore - worst case we regenerate snapshots next time
        }
    }

    companion object {
        private val managers = mutableMapOf<String, ClasspathSnapshotManager>()

        fun forCache(cacheDir: File): ClasspathSnapshotManager {
            val key = cacheDir.absolutePath
            return managers.getOrPut(key) { ClasspathSnapshotManager(cacheDir) }
        }
    }
}
