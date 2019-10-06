package com.ivieleague.skate

import java.awt.Desktop
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

val home: File get() = File(System.getProperty("user.home"))

fun download(url: String, to: File): File {
    var lastPrint = System.currentTimeMillis()
    URL(url).openStream().buffered().use { input ->
        FileOutputStream(to).use { output ->
            var bytesCopied: Long = 0
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                output.write(buffer, 0, bytes)
                bytesCopied += bytes
                bytes = input.read(buffer)

                val now = System.currentTimeMillis()
                if (now - lastPrint > 5000) {
                    val mb = bytesCopied / (1024 * 1024)
                    println("Download at ${bytesCopied / mb}mb - ${url}")
                    lastPrint = now
                }
            }
        }
    }
    return to
}

fun File.cachedFrom(url: String): File {
    if (this.exists()) return this
    this.parentFile.mkdirs()
    var lastPrint = System.currentTimeMillis()
    URL(url).openStream().buffered().use { input ->
        FileOutputStream(this).use { output ->
            var bytesCopied: Long = 0
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                output.write(buffer, 0, bytes)
                bytesCopied += bytes
                bytes = input.read(buffer)

                val now = System.currentTimeMillis()
                if (now - lastPrint > 5000) {
                    val mb = bytesCopied / (1024 * 1024)
                    println("Download at ${bytesCopied / mb}mb - ${url}")
                    lastPrint = now
                }
            }
        }
    }
    return this
}

fun execute(vararg command: String): Int {
    val p = ProcessBuilder().command(*command).inheritIO().start()
    p.waitFor()
    return p.exitValue()
}

fun execute(
    command: Array<String>,
    directory: File = File(""),
    environment: Map<String, String> = mapOf(),
    abandonAfterMilliseconds: Long = Long.MAX_VALUE
): Int {
    val p = ProcessBuilder()
        .command(*command)
        .directory(directory)
        .apply {
            environment().putAll(environment)
        }
        .inheritIO()
        .start()
    p.waitFor(abandonAfterMilliseconds, TimeUnit.MILLISECONDS)
    return p.exitValue()
}

@Suppress("UNCHECKED_CAST")
val userProperties: Map<String, String> by lazy {
    try {
        val props = Properties().apply { load(FileInputStream(home.resolve("user.properties"))) }
        props as Map<String, String>
    } catch (e: Exception) {
        mapOf<String, String>()
    }
}

fun File.launch() = Desktop.getDesktop().browse(this.toURI())

fun File.statusHash(): Int {
    return when {
        !this.exists() -> 0
        this.isDirectory -> this.listFiles()?.sumBy { it.statusHash() } ?: 0
        else -> this.lastModified().hashCode() + this.toString().hashCode() + this.length().hashCode()
    }
}