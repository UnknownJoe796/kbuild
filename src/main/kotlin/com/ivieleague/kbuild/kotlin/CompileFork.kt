package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import java.io.File

/**
 * Runs an in-process-capable compile in a **forked kbuild JVM** so it can overlap another
 * in-process compile that already holds [InProcessCompileLock]. This is the "fork the loser" half of
 * the one-in-process-at-a-time invariant: JS and the commonMain metadata compile both use the
 * embeddable compiler, so when they are ready concurrently one runs in-process and the other forks
 * here. The child runs the *same* in-process compile code (see [CompileForkMain]) on kbuild's own
 * classpath and reports success/diagnostics back through a file.
 *
 * Modeled on `com.ivieleague.kbuild.junit.JUnitForkRunner`.
 */
internal object CompileFork {
    private val json = Json { classDiscriminator = "kind"; encodeDefaults = true }

    /** Compile a Kotlin/JS KLIB in a child JVM. Returns the produced klib; throws on failure. */
    fun jsKlib(
        name: String,
        sourceRoots: Set<File>,
        libraries: Set<File>,
        argStrings: List<String>,
        cache: File?,
        outputDir: File
    ): File {
        val request = JsKlibForkRequest(
            name = name,
            sourceRoots = sourceRoots.map { it.absolutePath },
            libraries = libraries.map { it.absolutePath },
            argStrings = argStrings,
            cache = cache?.absolutePath,
            outputDir = outputDir.absolutePath
        )
        runChild("js-klib", json.encodeToString(request))
        return outputDir.resolve("$name.klib")
    }

    /** Compile the (refines-ordered) metadata source-set chain in a child JVM. Throws on failure. */
    fun metadata(units: List<MetadataUnit>) {
        runChild("metadata", json.encodeToString(MetadataForkRequest(units)))
    }

    /**
     * Launch [CompileForkMain] on kbuild's own classpath, hand it the request, and surface failures
     * as a [Kotlin.CompilationException] (so callers handle forked and in-process failures alike).
     */
    private fun runChild(kind: String, requestJson: String) {
        val requestFile = File.createTempFile("kbuild-compile-req", ".json")
        val resultFile = File.createTempFile("kbuild-compile-res", ".json")
        try {
            if (Settings.outputLevel <= Settings.OutputLevel.Normal) {
                println("In-process permit busy; compiling $kind in a forked JVM")
            }
            requestFile.writeText(requestJson)
            val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val classpath = System.getProperty("kbuild.classpath") ?: System.getProperty("java.class.path")
            val command = listOf(
                javaBin, "-cp", classpath,
                "com.ivieleague.kbuild.kotlin.CompileForkMain",
                kind, requestFile.absolutePath, resultFile.absolutePath
            )
            val exit = ProcessBuilder(command).inheritIO().start().waitFor()
            val result = resultFile.takeIf { it.length() > 0 }?.let { json.decodeFromString<ForkResult>(it.readText()) }
                ?: throw Kotlin.CompilationException(
                    listOf(Kotlin.CompilationMessage(CompilerMessageSeverity.ERROR, "Forked compile produced no result (exit $exit)"))
                )
            if (!result.success) {
                throw Kotlin.CompilationException(
                    result.errors.map { Kotlin.CompilationMessage(CompilerMessageSeverity.ERROR, it) }
                )
            }
        } finally {
            requestFile.delete()
            resultFile.delete()
        }
    }
}

/** One metadata source set to compile (paths only, so it crosses the fork boundary). */
@Serializable
internal data class MetadataUnit(
    val moduleName: String,
    val sources: List<String>,
    val classpath: List<String>,
    val refinesPaths: List<String>,
    val destination: String,
    val contextParameters: Boolean
)

@Serializable
internal data class JsKlibForkRequest(
    val name: String,
    val sourceRoots: List<String>,
    val libraries: List<String>,
    val argStrings: List<String>,
    val cache: String?,
    val outputDir: String
)

@Serializable
internal data class MetadataForkRequest(val units: List<MetadataUnit>)

@Serializable
internal data class ForkResult(val success: Boolean, val errors: List<String> = emptyList())

/**
 * Entry point of a forked compile JVM (launched by [CompileFork]). Runs the requested in-process
 * compile on kbuild's classpath and writes a [ForkResult]. The child is single-purpose, so it does
 * not take the in-process permit; it force-exits afterward because the embeddable compiler can leave
 * non-daemon IntelliJ-platform threads that would otherwise keep the JVM alive.
 *
 * Arguments: `<kind> <requestFile> <resultFile>` where kind is `js-klib` or `metadata`.
 */
object CompileForkMain {
    private val json = Json { classDiscriminator = "kind"; encodeDefaults = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val kind = args[0]
        val requestText = File(args[1]).readText()
        val resultFile = File(args[2])

        val result = try {
            when (kind) {
                "js-klib" -> {
                    val request = json.decodeFromString<JsKlibForkRequest>(requestText)
                    kotlinJsCompileSync(
                        name = request.name,
                        sourceRoots = request.sourceRoots.map { File(it) }.toSet(),
                        libraries = request.libraries.map { File(it) }.toSet(),
                        // The caller already rendered its argument configurer to strings; re-apply them.
                        arguments = { parseCommandLineArguments(request.argStrings, this) },
                        outputMode = JsOutputMode.KLIB,
                        cache = request.cache?.let { File(it) },
                        outputDir = File(request.outputDir)
                    )
                    ForkResult(success = true)
                }
                "metadata" -> {
                    val request = json.decodeFromString<MetadataForkRequest>(requestText)
                    for (unit in request.units) {
                        com.ivieleague.kbuild.kmp.compileMetadataSourceSet(
                            moduleName = unit.moduleName,
                            sources = unit.sources,
                            classpath = unit.classpath,
                            refinesPaths = unit.refinesPaths,
                            destination = File(unit.destination),
                            contextParameters = unit.contextParameters
                        )
                    }
                    ForkResult(success = true)
                }
                else -> ForkResult(success = false, errors = listOf("Unknown forked compile kind: $kind"))
            }
        } catch (e: Kotlin.CompilationException) {
            ForkResult(success = false, errors = e.messages.map { it.message })
        } catch (e: Throwable) {
            ForkResult(success = false, errors = listOf(e.message ?: e.toString()))
        }

        resultFile.writeText(json.encodeToString(result))
        System.out.flush()
        System.err.flush()
        kotlin.system.exitProcess(0)
    }
}
