package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.skate.statusHash
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

object Kotlin {

    class CompilationException(val messages: List<CompilationMessage>) :
        Exception(messages.filter { it.severity <= CompilerMessageSeverity.WARNING }.joinToString("; ") { it.message + " at " + it.location }
            ?: "An unknown error occurred")

    val version = Version(2, 2, 0)

    val standardLibraryJvm: Set<Library> by lazy { MavenAether.libraries(standardLibraryJvmId) }
    val standardLibraryJvmId = "org.jetbrains.kotlin:kotlin-stdlib:$version"

    val standardLibraryTest: Set<Library> by lazy { MavenAether.libraries(standardLibraryTestId) }
    val standardLibraryTestId = "org.jetbrains.kotlin:kotlin-test:$version"

    val standardLibraryTestJunit: Set<Library> by lazy { MavenAether.libraries(standardLibraryTestJunitId) }
    val standardLibraryTestJunitId = "org.jetbrains.kotlin:kotlin-test-junit:$version"

    val standardLibraryTestJunit5: Set<Library> by lazy { MavenAether.libraries(standardLibraryTestJunit5Id) }
    val standardLibraryTestJunit5Id = "org.jetbrains.kotlin:kotlin-test-junit5:$version"

    val standardLibraryJs: Set<Library> by lazy { MavenAether.libraries(standardLibraryJsId) }
    val standardLibraryJsId = "org.jetbrains.kotlin:kotlin-stdlib-js:$version"

    @Serializable
    data class CompilationMessage(
        val severity: CompilerMessageSeverity,
        val message: String,
        val location: CompilerMessageSourceLocation2? = null
    )

    @Serializable
    data class CompilerMessageSourceLocation2(
        val path: String,
        val line: Int,
        val column: Int,
    )

    class CompilationMessageCollector : MessageCollector {
        val messages = ArrayList<CompilationMessage>()

        override fun clear() {
            messages.clear()
        }

        override fun hasErrors(): Boolean = messages.any { it.severity.isError }

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?
        ) {
            messages.add(CompilationMessage(severity, message, location?.let {
                CompilerMessageSourceLocation2(it.path, it.line, it.column)
            }))
        }
    }

    private data class PublicDeclarationCacheEntry(
        var file: String = "",
        var statusHash: Int = 0,
        var api: Set<String> = setOf()
    )

}