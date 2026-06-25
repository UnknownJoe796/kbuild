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

    val version = Version(2, 2, 20)

    val standardLibraryJvmId = "org.jetbrains.kotlin:kotlin-stdlib:$version"
    private var cachedStandardLibraryJvm: Set<Library>? = null
    suspend fun standardLibraryJvm(): Set<Library> {
        cachedStandardLibraryJvm?.let { return it }
        return MavenAether.libraries(standardLibraryJvmId).also { cachedStandardLibraryJvm = it }
    }

    val standardLibraryTestId = "org.jetbrains.kotlin:kotlin-test:$version"
    private var cachedStandardLibraryTest: Set<Library>? = null
    suspend fun standardLibraryTest(): Set<Library> {
        cachedStandardLibraryTest?.let { return it }
        return MavenAether.libraries(standardLibraryTestId).also { cachedStandardLibraryTest = it }
    }

    val standardLibraryTestJunitId = "org.jetbrains.kotlin:kotlin-test-junit:$version"
    private var cachedStandardLibraryTestJunit: Set<Library>? = null
    suspend fun standardLibraryTestJunit(): Set<Library> {
        cachedStandardLibraryTestJunit?.let { return it }
        return MavenAether.libraries(standardLibraryTestJunitId).also { cachedStandardLibraryTestJunit = it }
    }

    val standardLibraryTestJunit5Id = "org.jetbrains.kotlin:kotlin-test-junit5:$version"
    private var cachedStandardLibraryTestJunit5: Set<Library>? = null
    suspend fun standardLibraryTestJunit5(): Set<Library> {
        cachedStandardLibraryTestJunit5?.let { return it }
        return MavenAether.libraries(standardLibraryTestJunit5Id).also { cachedStandardLibraryTestJunit5 = it }
    }

    val standardLibraryJsId = "org.jetbrains.kotlin:kotlin-stdlib-js:$version"
    val standardLibraryJs: Set<Library> by lazy { MavenAether.librariesKlib(standardLibraryJsId) }

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