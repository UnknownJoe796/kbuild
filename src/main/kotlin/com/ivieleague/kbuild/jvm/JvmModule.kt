package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Buildable
import com.ivieleague.kbuild.common.HasLibraries
import com.ivieleague.kbuild.common.Module
import java.io.File

interface JvmModule : Module, HasLibraries, HasJvmClassPaths, Buildable, CreatesJar {
    override val jvmClassPaths: List<File>
        get() = listOf(build()) + libraries.map { it.default }

    val classpathOutput get() = outFolder.resolve("classpath")
    val jarManifest get() = Jar.defaultManifest()

    fun buildManifest(): File {
        val file = classpathOutput
            .resolve("META-INF")
            .also { it.mkdirs() }
            .resolve("MANIFEST.MF")
        file.outputStream().buffered().use {
            jarManifest.write(it)
        }
        return file
    }

    val distributionOutput: File get() = outFolder.resolve("$name.jar")
    override fun createJar(): File {
        build()
        buildManifest()
        val dFile = distributionOutput
        Jar.create(classpathOutput, dFile)
        return dFile
    }
}