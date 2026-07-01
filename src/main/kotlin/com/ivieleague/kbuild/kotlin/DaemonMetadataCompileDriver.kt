@file:OptIn(ExperimentalBuildToolsApi::class, DelicateBuildToolsApi::class)

package com.ivieleague.kbuild.kotlin

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.DelicateBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.metadata.KotlinMetadataPlatformToolchain
import java.io.File

/**
 * Runs Kotlin **metadata** (commonMain / intermediate source-set) compilation in the Kotlin daemon
 * via the Build Tools API ([KotlinMetadataPlatformToolchain]).
 *
 * The shared source sets are compiled in a single daemon session in `dependsOn` order (a set may
 * refine the ones compiled before it), so the [request] carries an ordered list of units.
 *
 * Like the JVM and JS drivers, this object is loaded by, and runs entirely inside, the shared
 * isolated `URLClassLoader` (see [IsolatedCompiler]); it therefore takes and returns only JDK types.
 * Because metadata now runs in the daemon too, **no** compilation runs in the kbuild process — the
 * in-process permit and the "fork the loser" child JVM are gone.
 */
object DaemonMetadataCompileDriver {
    private val toolchains: KotlinToolchains by lazy {
        KotlinToolchains.loadImplementation(DaemonMetadataCompileDriver::class.java.classLoader)
    }
    private val metadata: KotlinMetadataPlatformToolchain by lazy {
        toolchains.getToolchain(KotlinMetadataPlatformToolchain::class.java)
    }

    /**
     * Compile the metadata source-set chain via the daemon. [request] keys (all JDK types):
     * - `units` (List<Map<String, Any?>>): the source sets in refines order, each a map of:
     *   - `sources` (List<String>): absolute source file paths.
     *   - `destination` (String): output directory for the produced metadata KLIB.
     *   - `args` (List<String>): rendered [org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments]
     *     strings (module name, classpath, refines paths, `-Xmulti-platform`, etc.); the caller renders these.
     * - `daemonRunDir` (String): directory for the daemon's run files (stable so the daemon is reused).
     * - `debug` (Boolean): verbose logging.
     *
     * @return an empty list on success, otherwise the collected error messages.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun compile(request: Map<String, Any?>): List<String> {
        val units = request["units"] as List<Map<String, Any?>>
        val debug = request["debug"] as? Boolean ?: false
        val daemonRunDir = File(request["daemonRunDir"] as String).toPath()

        val logger = CollectingLogger(debug)
        val policy = toolchains.daemonExecutionPolicyBuilder()
            .also { it[ExecutionPolicy.WithDaemon.DAEMON_RUN_DIR_PATH] = daemonRunDir }
            .build()

        return toolchains.createBuildSession().use { session ->
            for (unit in units) {
                val sources = (unit["sources"] as List<String>).map { File(it).toPath() }
                val destination = File(unit["destination"] as String).toPath()
                val args = unit["args"] as List<String>

                val builder = metadata.metadataKlibCompilationOperationBuilder(sources, destination)
                builder.compilerArguments.applyArgumentStrings(args)
                val result = session.executeOperation(builder.build(), policy, logger)
                if (result != CompilationResult.COMPILATION_SUCCESS) return@use logger.errors
            }
            emptyList()
        }
    }
}
