package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.maven.MavenAether
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

object Kotlin {

    class CompilationException(val messages: List<CompilationMessage>) :
        Exception(messages.filter { it.severity <= CompilerMessageSeverity.WARNING }.joinToString("; ") { it.message + " at " + it.location }
            ?: "An unknown error occurred")

    val version = Version(1, 3, 50)

    val standardLibraryJvm: List<Library> by lazy { MavenAether.libraries(standardLibraryJvmId) }
    val standardLibraryJvmId = "org.jetbrains.kotlin:kotlin-stdlib:$version"

    val standardLibraryJs: List<Library> by lazy { MavenAether.libraries(standardLibraryJsId) }
    val standardLibraryJsId = "org.jetbrains.kotlin:kotlin-stdlib-js:$version"

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