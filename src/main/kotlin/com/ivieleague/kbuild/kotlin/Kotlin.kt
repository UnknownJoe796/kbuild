package com.ivieleague.kbuild.kotlin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.skate.statusHash
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

object Kotlin {

    class CompilationException(val messages: List<CompilationMessage>) :
        Exception(messages.filter { it.severity <= CompilerMessageSeverity.WARNING }.joinToString("; ") { it.message + " at " + it.location }
            ?: "An unknown error occurred")

    val version = Version(1, 3, 60)

    val standardLibraryJvm: Set<Library> by lazy { MavenAether.libraries(standardLibraryJvmId) }
    val standardLibraryJvmId = "org.jetbrains.kotlin:kotlin-stdlib:$version"

    val standardLibraryJs: Set<Library> by lazy { MavenAether.libraries(standardLibraryJsId) }
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

    private data class PublicDeclarationCacheEntry(
        var file: String = "",
        var statusHash: Int = 0,
        var api: Set<String> = setOf()
    )

    fun publicDeclarations(files: Sequence<File>, cache: File): Set<String> {
        val cachedApi =
            if (cache.exists()) ObjectMapper().readValue<HashMap<String, PublicDeclarationCacheEntry>>(cache) else hashMapOf()
        val out = HashSet<String>()
        files.forEach {
            val rel = it.relativeTo(cache).toString()
            val existing = cachedApi[rel]
            val statusHash = it.statusHash()
            if (existing?.statusHash == statusHash) {
                out.addAll(existing.api)
            } else {
                val partial = HashSet<String>()
                it.publicDeclarations(partial)
                cachedApi[rel] = PublicDeclarationCacheEntry(file = rel, statusHash = statusHash, api = partial)
                out.addAll(partial)
            }
        }
        ObjectMapper().writeValue(cache, cachedApi)
        return out
    }

}