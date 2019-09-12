package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.skate.cachedFrom
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

object Kotlin {

    class CompilationException(val messages: List<CompilationMessage>) :
        Exception(messages.filter { it.severity <= CompilerMessageSeverity.WARNING }.joinToString("; ") { it.message }
            ?: "An unknown error occurred")

    val version = Version(1, 3, 50)
    val standardLibrary: Library by lazy {
        val folder = File(System.getProperty("user.home")).resolve(".kbuild/KotlinStandardLibrary")
        val baseUrl =
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$version/kotlin-stdlib-$version"
        Library(
            name = "kotlin-stdlib",
            default = folder.resolve("kotlin-stdlib-$version.jar").cachedFrom("$baseUrl.jar"),
            javadoc = folder.resolve("kotlin-stdlib-$version-javadoc.jar").cachedFrom(
                "$baseUrl-javadoc.jar"
            ),
            sources = folder.resolve("kotlin-stdlib-$version-sources.jar").cachedFrom(
                "$baseUrl-sources.jar"
            )
        )
    }
    val standardLibraryId = "org.jetbrains.kotlin:kotlin-stdlib:$version"

    data class CompilationMessage(
        val severity: CompilerMessageSeverity,
        val message: String,
        val location: CompilerMessageLocation? = null
    )

    class CompilationMessageCollector : MessageCollector {
        val messages = ArrayList<CompilationMessage>()

        override fun clear() {
            messages.clear()
        }

        override fun hasErrors(): Boolean = messages.any { it.severity.isError }

        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
            messages.add(CompilationMessage(severity, message, location))
        }
    }
}