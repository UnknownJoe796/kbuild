package com.ivieleague.kbuild.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.jar.Manifest

/**
 * Builds a JAR file from the given [folders].
 *
 * A JAR build has no time-varying input to watch, so all inputs are plain values; the function stays
 * `suspend` only to dispatch the IO work off the caller's thread.
 *
 * @param manifest JAR manifest to include
 * @param folders Set of folders to include in the JAR
 * @param output The output JAR file
 * @return The output JAR file
 */
suspend fun jarBuild(
    manifest: Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (KBuild)")
    },
    folders: Set<File>,
    output: File
): File = withContext(Dispatchers.IO) {
    output.parentFile.mkdirs()
    Jar.from(output, manifest, *folders.toTypedArray()).file
}
