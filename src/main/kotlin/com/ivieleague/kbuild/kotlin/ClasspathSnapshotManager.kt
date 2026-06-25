package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation
import java.io.File

/**
 * Manages classpath snapshots for incremental compilation.
 *
 * Classpath snapshotting enables true incremental compilation by tracking which
 * classes in the classpath have changed. Without it, any source file change
 * triggers a full rebuild.
 *
 * Snapshots are cached based on JAR path and modification time, so unchanged
 * JARs don't need to be re-analyzed. Snapshots and the shrunk snapshot live in a
 * sibling directory of the incremental cache so the compiler does not delete them
 * when it cleans its working directory.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class ClasspathSnapshotManager(cacheDir: File) {
    // Use canonical paths to normalize and avoid issues with working directory changes
    private val cacheDir = cacheDir.canonicalFile
    private val snapshotDir = this.cacheDir.parentFile.resolve("classpath-snapshots")
    private val metadataFile = snapshotDir.resolve("metadata.txt")

    /** Where the compiler stores the shrunk view of the classpath snapshot between builds. */
    val shrunkSnapshotFile: File = snapshotDir.resolve("shrunk-classpath-snapshot.bin")

    /**
     * Generates (or reuses cached) per-JAR snapshot files for the given classpath.
     *
     * @return the snapshot files in classpath order, suitable for passing as the
     *   `dependenciesSnapshotFiles` of the BTA incremental compilation configuration.
     */
    fun snapshotFiles(classpathJars: Set<File>): List<File> {
        snapshotDir.mkdirs()

        val snapshotFiles = mutableListOf<File>()
        val previousMetadata = loadMetadata()
        val currentMetadata = mutableMapOf<String, Long>()
        val logger = BtaMessageLogger()

        BuildToolsApi.toolchains.createBuildSession().use { session ->
            val policy = BuildToolsApi.toolchains.createInProcessExecutionPolicy()
            for (jar in classpathJars) {
                if (!jar.exists()) continue

                val jarPath = jar.absolutePath
                val jarModTime = jar.lastModified()
                val snapshotFile = getSnapshotFile(jar)

                val cachedModTime = previousMetadata[jarPath]
                if (cachedModTime == jarModTime && snapshotFile.exists()) {
                    snapshotFiles.add(snapshotFile)
                    currentMetadata[jarPath] = jarModTime
                } else {
                    if (Settings.outputLevel <= Settings.OutputLevel.Debug) {
                        println("  Generating snapshot for ${jar.name}")
                    }
                    val operation = BuildToolsApi.jvm.classpathSnapshottingOperationBuilder(jar.toPath())
                        .also { it.set(JvmClasspathSnapshottingOperation.GRANULARITY, ClassSnapshotGranularity.CLASS_LEVEL) }
                        .build()
                    val snapshot = session.executeOperation(operation, policy, logger)
                    snapshotFile.parentFile?.mkdirs()
                    snapshot.saveSnapshot(snapshotFile)
                    snapshotFiles.add(snapshotFile)
                    currentMetadata[jarPath] = jarModTime
                }
            }
        }

        saveMetadata(currentMetadata)
        return snapshotFiles
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

    private fun loadMetadata(): Map<String, Long> {
        if (!metadataFile.exists()) return emptyMap()
        return metadataFile.readLines()
            .filter { it.contains('\t') }
            .associate { line ->
                val parts = line.split('\t', limit = 2)
                parts[1] to parts[0].toLong()
            }
    }

    private fun saveMetadata(metadata: Map<String, Long>) {
        val content = metadata.entries.joinToString("\n") { (path, modTime) -> "$modTime\t$path" }
        metadataFile.writeText(content)
    }

    companion object {
        private val managers = mutableMapOf<String, ClasspathSnapshotManager>()

        fun forCache(cacheDir: File): ClasspathSnapshotManager {
            val key = cacheDir.absolutePath
            return managers.getOrPut(key) { ClasspathSnapshotManager(cacheDir) }
        }
    }
}
