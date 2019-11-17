package com.ivieleague.kbuild.jvm

import java.io.*
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.collections.ArrayList

inline class Jar(val file: File) {
    fun getManifest(): Manifest? {
        return JarInputStream(file.inputStream()).use { stream ->
            stream.manifest
        }
    }

    fun getEntries(): List<JarEntry> {
        val list = ArrayList<JarEntry>()
        FileInputStream(file).use {
            val j = JarInputStream(it)
            while (true) {
                val entry = j.nextJarEntry
                if (entry != null) {
                    list.add(entry)
                } else {
                    break
                }
            }
        }
        return list
    }

    fun inputStream(path: String): InputStream? {
        JarInputStream(file.inputStream()).use { j ->
            while (true) {
                val entry = j.nextJarEntry
                if (entry?.name?.trim() == path) {
                    return j
                } else {
                    break
                }
            }
        }
        return null
    }

    fun file(path: String): File? {
        return inputStream(path)?.use { stream ->
            val temp = File.createTempFile("JarFile", path.substringAfterLast('/').filter { it.isLetterOrDigit() })
            temp.outputStream().use { out ->
                stream.copyTo(out)
            }
            temp
        }
    }

    fun execute(classpath: List<File>, vararg arguments: String): Int {
        return JVM.runJar(mainJar = file, jars = classpath, arguments = arguments)
    }

    private class JarCreation {
        val alreadyTaken = HashSet<String>()

        @Throws(IOException::class)
        fun add(source: File, target: JarOutputStream, base: File = source) {
            if (!source.exists()) return
            val relativePath = source.relativeTo(base).invariantSeparatorsPath.let {
                if (source.isDirectory) {
                    it.trimEnd('/') + "/"
                } else {
                    it
                }
            }
            if (alreadyTaken.add(relativePath)) {
                if (source.isDirectory) {
                    if (!relativePath.isEmpty()) {
                        val entry = JarEntry(relativePath)
                        entry.time = source.lastModified()
                        target.putNextEntry(entry)
                        target.closeEntry()
                    }
                    source.listFiles()!!.forEach { nestedFile ->
                        add(nestedFile, target, base)
                    }
                    return
                } else {
                    val entry = JarEntry(relativePath)
                    entry.time = source.lastModified()
                    target.putNextEntry(entry)
                    BufferedInputStream(FileInputStream(source)).copyTo(target)
                    target.closeEntry()
                }
            }
        }

        fun addFolder(path: String, target: JarOutputStream) {
            if (alreadyTaken.add(path)) {
                target.putNextEntry(JarEntry(path))
                target.closeEntry()
            }
        }

        fun addFile(path: String, target: JarOutputStream, write: (stream: OutputStream) -> Unit) {
            if (alreadyTaken.add(path)) {
                target.putNextEntry(JarEntry(path))
                write(target)
                target.closeEntry()
            }
        }
    }

    companion object {
        fun from(into: File, manifest: Manifest, vararg directories: File): Jar {
            val c = JarCreation()
            JarOutputStream(FileOutputStream(into)).use { stream ->
                c.addFolder("META-INF/", stream)
                c.addFile("META-INF/MANIFEST.MF", stream) { manifest.write(it) }
                for (x in directories) {
                    c.add(source = x, target = stream)
                }
            }

            return Jar(into)
        }

        fun from(into: File, vararg directories: File): Jar {
            val c = JarCreation()
            JarOutputStream(FileOutputStream(into)).use { stream ->
                for (x in directories) {
                    c.add(source = x, target = stream)
                }
            }

            return Jar(into)
        }
    }
}