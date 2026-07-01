@file:OptIn(ExperimentalBuildToolsApi::class, DelicateBuildToolsApi::class)

package com.ivieleague.kbuild.kotlin

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.DelicateBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.js.IncrementalModule
import org.jetbrains.kotlin.buildtools.api.js.JsPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.js.operations.JsKlibCompilationOperation
import java.io.File

/**
 * Runs a Kotlin/JS compilation in the Kotlin daemon (a separate process) via the Build Tools API.
 *
 * K2 JS is a two-phase compile — sources → KLIB, then KLIB → JS — expressed here as BTA's
 * [JsPlatformToolchain.jsKlibCompilationOperationBuilder] followed (for JS output) by
 * [JsPlatformToolchain.jsLinkingOperationBuilder].
 *
 * Like [DaemonJvmCompileDriver], this object is loaded by, and runs entirely inside, the shared
 * isolated `URLClassLoader` (see [IsolatedCompiler]); it therefore takes and returns only JDK types.
 * Running in the daemon means JS no longer competes for the in-process compiler permit — it overlaps
 * the metadata compile and native subprocesses freely.
 */
object DaemonJsCompileDriver {
    private val toolchains: KotlinToolchains by lazy {
        KotlinToolchains.loadImplementation(DaemonJsCompileDriver::class.java.classLoader)
    }
    private val js: JsPlatformToolchain by lazy { toolchains.getToolchain(JsPlatformToolchain::class.java) }

    /**
     * Compile via the daemon. [request] keys (all JDK types so they cross the classloader boundary):
     * - `sources` (List<String>): absolute source file paths.
     * - `klibDir` (String): destination *directory* for the KLIB phase (BTA writes `<dir>/<name>.klib`).
     * - `klibFile` (String): the produced KLIB file path (`<klibDir>/<name>.klib`), used as the
     *   linking input.
     * - `klibArgs` (List<String>): rendered [org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments]
     *   strings for the KLIB phase; produced on the caller side.
     * - `outputMode` (String): `"KLIB"` to stop after the KLIB, or `"JS"` to also link to JavaScript.
     * - Incremental (all present together to enable history-based IC on the KLIB phase, else omit):
     *   `icRootProjectDir` (String), `icWorkingDir` (String), `icModuleName` (String),
     *   `icModuleBuildDir` (String). The IC module's output is [klibFile].
     * - `output` (String): output directory for the linked JS (required only when outputMode is `"JS"`).
     * - `linkArgs` (List<String>): rendered argument strings for the linking phase (JS mode only).
     * - `daemonRunDir` (String): directory for the daemon's run files (stable so the daemon is reused).
     * - `debug` (Boolean): verbose logging.
     *
     * @return an empty list on success, otherwise the collected error messages.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun compile(request: Map<String, Any?>): List<String> {
        val sources = (request["sources"] as List<String>).map { File(it).toPath() }
        val klibDir = File(request["klibDir"] as String).toPath()
        val klibFile = File(request["klibFile"] as String).toPath()
        val klibArgs = request["klibArgs"] as List<String>
        val outputMode = request["outputMode"] as String
        val debug = request["debug"] as? Boolean ?: false
        val daemonRunDir = File(request["daemonRunDir"] as String).toPath()

        val logger = CollectingLogger(debug)
        val policy = toolchains.daemonExecutionPolicyBuilder()
            .also { it[ExecutionPolicy.WithDaemon.DAEMON_RUN_DIR_PATH] = daemonRunDir }
            .build()

        return toolchains.createBuildSession().use { session ->
            // Phase 1: sources → KLIB (written to the klibDir as <klibDir>/<name>.klib).
            val klibBuilder = js.jsKlibCompilationOperationBuilder(sources, klibDir)
            klibBuilder.compilerArguments.applyArgumentStrings(klibArgs)

            // History-based incremental compilation for the KLIB (frontend) phase, when configured.
            // SourcesChanges.ToBeCalculated lets the compiler derive the dirty set from its own history.
            val icWorkingDir = request["icWorkingDir"] as String?
            if (icWorkingDir != null) {
                val module = IncrementalModule(
                    request["icModuleName"] as String,
                    klibFile,
                    File(request["icModuleBuildDir"] as String).toPath(),
                )
                val icConfig = klibBuilder.historyBasedIcConfigurationBuilder(
                    File(request["icRootProjectDir"] as String).toPath(),
                    File(icWorkingDir).toPath(),
                    SourcesChanges.ToBeCalculated,
                    listOf(module),
                ).build()
                klibBuilder.set(JsKlibCompilationOperation.INCREMENTAL_COMPILATION, icConfig)
            }

            val klibResult = session.executeOperation(klibBuilder.build(), policy, logger)
            if (klibResult != CompilationResult.COMPILATION_SUCCESS) return@use logger.errors

            if (outputMode == "JS") {
                // Phase 2: KLIB → JS.
                val jsDestination = File(request["output"] as String).toPath()
                val linkArgs = request["linkArgs"] as List<String>
                val linkBuilder = js.jsLinkingOperationBuilder(klibFile, jsDestination)
                linkBuilder.compilerArguments.applyArgumentStrings(linkArgs)
                val linkResult = session.executeOperation(linkBuilder.build(), policy, logger)
                if (linkResult != CompilationResult.COMPILATION_SUCCESS) return@use logger.errors
            }
            emptyList()
        }
    }
}
