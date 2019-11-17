package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Producer
import java.io.File
import java.util.jar.Manifest

class JarBuild(
    val manifest: Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (KBuild)")
    },
    val folders: Producer<File>,
    val output: File
) : () -> File {
    override fun invoke(): File {
        return Jar.from(output, manifest, *folders().toTypedArray()).file
    }
}