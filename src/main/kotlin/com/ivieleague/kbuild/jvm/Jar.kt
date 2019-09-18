package com.ivieleague.kbuild.jvm

import java.io.*
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest


object Jar {

    fun defaultManifest(): Manifest = Manifest().also {
        it.mainAttributes.putValue("Manifest-Version", "1.0")
        it.mainAttributes.putValue("Created-By", System.getProperty("java.version") + " (Fadle)")
    }

    fun create(fromDirectory: File, intoJar: File) {
        JarOutputStream(FileOutputStream(intoJar)).use { stream ->
            add(fromDirectory, stream)
        }
    }

    fun create(fromDirectories: Sequence<File>, intoJar: File) {
        JarOutputStream(FileOutputStream(intoJar)).use { stream ->
            fromDirectories.forEach { fromDirectory ->
                add(fromDirectory, stream)
            }
        }
    }

    fun jarFiles(jar: File): List<String> {
        val list = ArrayList<String>()
        FileInputStream(jar).use {
            val j = JarInputStream(it)
            while (true) {
                val entry = j.nextJarEntry
                if (entry != null) {
                    list.add(entry.name)
                } else {
                    break
                }
            }
        }
        return list
    }

    fun listJavaClasses(classpath: File): List<String> {
        return if (classpath.extension == "jar") {
            jarFiles(classpath).filter { it.endsWith(".class") }
                .map { it.replace('/', '.').replace('\\', '.').removeSuffix(".class") }
        } else {
            classpath.walkTopDown().filter { it.extension == ".class" }.map {
                it.relativeTo(classpath).path.replace(
                    '/',
                    '.'
                ).replace('\\', '.').removeSuffix(".class")
            }.toList()
        }
    }

    @Throws(IOException::class)
    private fun add(source: File, target: JarOutputStream, base: File = source) {
        val relativePath = source.relativeTo(base).invariantSeparatorsPath
        if (source.isDirectory) {
            var name = relativePath
            if (!name.isEmpty()) {
                if (!name.endsWith("/"))
                    name += "/"
                val entry = JarEntry(name)
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