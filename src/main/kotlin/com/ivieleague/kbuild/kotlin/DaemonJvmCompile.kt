package com.ivieleague.kbuild.kotlin

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * Boundary that hands a Kotlin/JVM compilation to [DaemonJvmCompileDriver] running inside an
 * isolated `URLClassLoader`.
 *
 * Why the isolated loader: the BTA daemon strategy derives the daemon's compiler classpath from
 * `(JvmCompilationOperationImpl::class.java.classLoader as URLClassLoader).urLs`, so the BTA
 * *implementation* must be loaded by a real `URLClassLoader`. kbuild is launched by the JDK
 * application classloader (not a `URLClassLoader`), so we build one over the current process
 * classpath whose parent is the platform loader: that forces the impl — and the driver — to load
 * here rather than delegate to the app loader, and exposes the classpath URLs the daemon needs.
 *
 * Running JVM compilation in a separate process is what lets it overlap the single in-process
 * compilation (JS / metadata) and the konanc native subprocesses.
 */
internal object DaemonJvmCompile {
    /**
     * Stable per-user run-files directory for kbuild's Kotlin daemon, so a warm daemon is reused
     * across compilations and builds instead of spawning a fresh JVM each time.
     */
    val daemonRunDir: File = File(System.getProperty("user.home"), ".kbuild/kotlin-daemon")

    /** The driver's `compile` method bound to its singleton instance, resolved once in the isolated loader. */
    private val driver: Pair<Any, Method> by lazy {
        val classpathUrls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        // Parent = platform loader (no application classes), so the driver and the BTA impl load from
        // these URLs rather than delegating to the non-URLClassLoader app loader.
        val loader = URLClassLoader(classpathUrls, ClassLoader.getSystemClassLoader().parent)
        val driverClass = loader.loadClass("com.ivieleague.kbuild.kotlin.DaemonJvmCompileDriver")
        // Kotlin `object` → the singleton lives in its INSTANCE field.
        val instance = driverClass.getField("INSTANCE").get(null)
        instance to driverClass.getMethod("compile", Map::class.java)
    }

    /**
     * Compile via the daemon. See [DaemonJvmCompileDriver.compile] for the [request] contract.
     * @return an empty list on success, otherwise the collected error messages.
     */
    @Suppress("UNCHECKED_CAST")
    fun compile(request: Map<String, Any?>): List<String> {
        val (instance, method) = driver
        return method.invoke(instance, request) as List<String>
    }
}
