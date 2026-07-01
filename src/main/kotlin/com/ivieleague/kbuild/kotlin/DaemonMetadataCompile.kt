package com.ivieleague.kbuild.kotlin

import java.io.File

/**
 * Boundary that hands a Kotlin metadata compilation to [DaemonMetadataCompileDriver] running inside
 * the shared isolated `URLClassLoader` (see [IsolatedCompiler]). Runs in the Kotlin daemon, sharing
 * the same warm daemon as the JVM and JS drivers.
 */
internal object DaemonMetadataCompile {
    /** Shared with the JVM/JS drivers so all reconnect to the same warm daemon. */
    val daemonRunDir: File get() = IsolatedCompiler.daemonRunDir

    private val driver: (Map<String, Any?>) -> List<String> by lazy {
        IsolatedCompiler.driver("com.ivieleague.kbuild.kotlin.DaemonMetadataCompileDriver")
    }

    /**
     * Compile via the daemon. See [DaemonMetadataCompileDriver.compile] for the [request] contract.
     * @return an empty list on success, otherwise the collected error messages.
     */
    fun compile(request: Map<String, Any?>): List<String> = driver(request)
}
