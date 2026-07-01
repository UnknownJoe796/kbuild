package com.ivieleague.kbuild.kotlin

import java.io.File

/**
 * Boundary that hands a Kotlin/JVM compilation to [DaemonJvmCompileDriver] running inside the shared
 * isolated `URLClassLoader` (see [IsolatedCompiler] for why the isolation is required).
 *
 * Running JVM compilation in the Kotlin daemon (a separate process) is what lets it overlap the
 * single in-process compilation (the commonMain metadata compile) and the konanc native subprocesses.
 */
internal object DaemonJvmCompile {
    /** Stable per-user daemon run-files directory (shared with the JS driver so the daemon is reused). */
    val daemonRunDir: File get() = IsolatedCompiler.daemonRunDir

    private val driver: (Map<String, Any?>) -> List<String> by lazy {
        IsolatedCompiler.driver("com.ivieleague.kbuild.kotlin.DaemonJvmCompileDriver")
    }

    /**
     * Compile via the daemon. See [DaemonJvmCompileDriver.compile] for the [request] contract.
     * @return an empty list on success, otherwise the collected error messages.
     */
    fun compile(request: Map<String, Any?>): List<String> = driver(request)
}
