package com.ivieleague.kbuild.kotlin

import java.io.File

/**
 * Boundary that hands a Kotlin/JS compilation to [DaemonJsCompileDriver] running inside the shared
 * isolated `URLClassLoader` (see [IsolatedCompiler]). Runs in the Kotlin daemon (a separate process),
 * so JS overlaps the in-process metadata compile and the konanc native subprocesses.
 */
internal object DaemonJsCompile {
    /** Shared with the JVM driver so both reconnect to the same warm daemon. */
    val daemonRunDir: File get() = IsolatedCompiler.daemonRunDir

    private val driver: (Map<String, Any?>) -> List<String> by lazy {
        IsolatedCompiler.driver("com.ivieleague.kbuild.kotlin.DaemonJsCompileDriver")
    }

    /**
     * Compile via the daemon. See [DaemonJsCompileDriver.compile] for the [request] contract.
     * @return an empty list on success, otherwise the collected error messages.
     */
    fun compile(request: Map<String, Any?>): List<String> = driver(request)
}
