package com.ivieleague.kbuild.jvm

import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.async
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import java.io.File
import java.util.jar.Manifest

/**
 * Builds a JAR file reactively.
 *
 * The build is cached based on input values.
 * When the folders reactive changes, the JAR will be rebuilt.
 *
 * @param manifest JAR manifest to include
 * @param folders Reactive set of folders to include in the JAR
 * @param output The output JAR file
 * @return The output JAR file
 */
context(ctx: ReactiveContext)
fun jarBuild(
    manifest: Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (KBuild)")
    },
    folders: Reactive<Set<File>>,
    output: File
): File {
    val inputFolders = folders()

    return async(inputFolders, output) {
        jarBuildBlocking(
            manifest = manifest,
            folders = inputFolders,
            output = output
        )
    }
}

/**
 * Blocking JAR build.
 * Use [jarBuild] for reactive usage.
 */
fun jarBuildBlocking(
    manifest: Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (KBuild)")
    },
    folders: Set<File>,
    output: File
): File {
    output.parentFile.mkdirs()
    return Jar.from(output, manifest, *folders.toTypedArray()).file
}

// Legacy class-based API for backwards compatibility
@Deprecated("Use jarBuild function with ReactiveContext instead")
class JarBuild(
    val manifest: Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (KBuild)")
    },
    val folders: () -> Set<File>,
    val output: File
) : () -> File {
    override fun invoke(): File = jarBuildBlocking(
        manifest = manifest,
        folders = folders(),
        output = output
    )
}
