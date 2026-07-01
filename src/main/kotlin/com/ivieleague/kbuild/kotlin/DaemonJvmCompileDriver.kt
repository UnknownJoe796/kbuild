@file:OptIn(ExperimentalBuildToolsApi::class, DelicateBuildToolsApi::class)

package com.ivieleague.kbuild.kotlin

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.DelicateBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.io.File

/**
 * Runs a Kotlin/JVM compilation in the Kotlin daemon (a separate process) via the Build Tools API.
 *
 * This object is loaded by, and runs entirely inside, the isolated `URLClassLoader` set up by
 * [DaemonJvmCompile] — that is mandatory because the BTA implementation derives the daemon's
 * compiler classpath from `(its own class).classLoader as URLClassLoader`. Since that isolated
 * loader carries its own copies of the BTA api/impl types, callers must not pass BTA objects across
 * the boundary; [compile] therefore takes and returns only JDK types (Strings/Lists/Maps).
 *
 * The toolchain is resolved once and reused so successive compiles reconnect to the same warm
 * daemon.
 */
object DaemonJvmCompileDriver {
    private val toolchains: KotlinToolchains by lazy {
        KotlinToolchains.loadImplementation(DaemonJvmCompileDriver::class.java.classLoader)
    }
    private val jvm: JvmPlatformToolchain by lazy { toolchains.getToolchain(JvmPlatformToolchain::class.java) }

    /**
     * Compile via the daemon. [request] keys (all JDK types so they cross the classloader boundary):
     * - `sources` (List<String>): absolute source file paths.
     * - `output` (String): output directory for class files.
     * - `args` (List<String>): rendered [org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments]
     *   strings (including `-classpath`); produced on the caller side.
     * - `daemonRunDir` (String): directory for the daemon's run files (stable so the daemon is reused).
     * - `debug` (Boolean): verbose logging.
     * - Incremental (omit all to compile non-incrementally): `workingDir` (String),
     *   `dependencySnapshots` (List<String>), `shrunkSnapshot` (String), `outputDirs` (List<String>).
     *
     * @return an empty list on success, otherwise the collected error messages.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun compile(request: Map<String, Any?>): List<String> {
        val sources = (request["sources"] as List<String>).map { File(it).toPath() }
        val output = File(request["output"] as String).toPath()
        val args = request["args"] as List<String>
        val debug = request["debug"] as? Boolean ?: false
        val daemonRunDir = File(request["daemonRunDir"] as String).toPath()

        val builder = jvm.jvmCompilationOperationBuilder(sources, output)
        builder.compilerArguments.applyArgumentStrings(args)

        val workingDir = request["workingDir"] as String?
        if (workingDir != null) {
            val depSnapshots = (request["dependencySnapshots"] as List<String>).map { File(it).toPath() }
            val shrunkSnapshot = File(request["shrunkSnapshot"] as String).toPath()
            val outputDirs = (request["outputDirs"] as List<String>).map { File(it).toPath() }.toSet()
            val icConfig = builder.snapshotBasedIcConfigurationBuilder(
                File(workingDir).toPath(),
                // Let the daemon compute the dirty set from the source snapshots and manage stale outputs.
                SourcesChanges.ToBeCalculated,
                depSnapshots,
                shrunkSnapshot
            ).also {
                // The incremental runner needs both the destination and its working directory here.
                it.set(JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS, outputDirs)
                // Precise backup removes (and restores on failure) outputs of changed sources, preventing
                // stale class files from colliding with freshly compiled ones.
                it.set(JvmSnapshotBasedIncrementalCompilationConfiguration.BACKUP_CLASSES, true)
            }.build()
            builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icConfig)
        }

        val logger = CollectingLogger(debug)
        val policy = toolchains.daemonExecutionPolicyBuilder()
            .also { it[ExecutionPolicy.WithDaemon.DAEMON_RUN_DIR_PATH] = daemonRunDir }
            .build()
        val result = toolchains.createBuildSession().use { session ->
            session.executeOperation(builder.build(), policy, logger)
        }
        return if (result == CompilationResult.COMPILATION_SUCCESS) emptyList() else logger.errors
    }
}
