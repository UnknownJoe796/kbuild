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
        val j = JarInputStream(file.inputStream())
        while (true) {
            val entry = j.nextJarEntry ?: break
            if (entry.name == path) {
                // Return the stream positioned at this entry (caller must close)
                return j
            }
        }
        j.close()
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

        /**
         * Create a fat JAR (uber JAR) that includes all dependencies.
         *
         * @param output The output JAR file
         * @param manifest The JAR manifest (should include Main-Class for executable JARs)
         * @param classes List of directories containing compiled classes
         * @param jars List of dependency JAR files to include
         */
        fun fatJar(
            output: File,
            manifest: Manifest,
            classes: List<File>,
            jars: List<File>
        ): Jar {
            output.parentFile?.mkdirs()
            val c = JarCreation()

            JarOutputStream(FileOutputStream(output)).use { stream ->
                // Add manifest
                c.addFolder("META-INF/", stream)
                c.addFile("META-INF/MANIFEST.MF", stream) { manifest.write(it) }

                // Add classes from class directories
                for (classDir in classes) {
                    if (classDir.exists()) {
                        c.add(source = classDir, target = stream)
                    }
                }

                // Extract and add contents from dependency JARs
                for (jarFile in jars) {
                    if (!jarFile.exists()) continue

                    JarInputStream(FileInputStream(jarFile)).use { jarInput ->
                        var entry = jarInput.nextJarEntry
                        while (entry != null) {
                            // Skip META-INF files from dependencies (signatures, etc.)
                            // but allow services files
                            val name = entry.name
                            if (name.startsWith("META-INF/") &&
                                !name.startsWith("META-INF/services/") &&
                                name != "META-INF/" &&
                                (name.endsWith(".SF") || name.endsWith(".RSA") ||
                                 name.endsWith(".DSA") || name == "META-INF/MANIFEST.MF")) {
                                entry = jarInput.nextJarEntry
                                continue
                            }

                            // Add the entry if not already present
                            if (c.alreadyTaken.add(name)) {
                                if (entry.isDirectory) {
                                    stream.putNextEntry(JarEntry(name))
                                    stream.closeEntry()
                                } else {
                                    stream.putNextEntry(JarEntry(name))
                                    jarInput.copyTo(stream)
                                    stream.closeEntry()
                                }
                            }
                            entry = jarInput.nextJarEntry
                        }
                    }
                }
            }

            return Jar(output)
        }
    }
}