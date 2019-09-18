package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.HasSourceRoots
import com.ivieleague.kbuild.jvm.Jar
import java.io.File

interface MavenDeployableWithSources : MavenDeployable,
    HasSourceRoots {
    override val sourcesFile: File?
        get() {
            val output = root.resolve("out/sources.jar")
            val additional = root.resolve("out/sources")
            additional.resolve("META-INF")
                .also { it.mkdirs() }
                .resolve("MANIFEST.MF")
                .takeIf { !it.exists() }
                ?.outputStream()?.buffered()?.use {
                    Jar.defaultManifest().write(it)
                }
            Jar.create(sourceRoots.asSequence() + sequenceOf(additional), output)
            return output
        }
}