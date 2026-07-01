package com.ivieleague.kbuild.kotlin

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * The isolated `URLClassLoader` that hosts the Build Tools API driver objects
 * ([DaemonJvmCompileDriver], [DaemonJsCompileDriver]).
 *
 * Why isolation is mandatory: the BTA daemon strategy derives the daemon's compiler classpath from
 * `(the BTA impl class).classLoader as URLClassLoader).urls`. kbuild is launched by the JDK
 * application classloader, which is *not* a `URLClassLoader`, so we build one over the current
 * process classpath whose parent is the platform loader. That forces the BTA impl — and the driver
 * objects — to load here rather than delegate to the app loader, and exposes the classpath URLs the
 * daemon needs.
 *
 * Both drivers share this one loader (and the single [daemonRunDir]) so the JVM and JS compiles
 * reconnect to the *same* warm Kotlin daemon instead of each spawning its own.
 *
 * Driver objects communicate only in JDK types (Strings/Lists/Maps) because BTA types loaded here
 * are distinct classes from the same types on the app classpath and must not cross the boundary.
 */
internal object IsolatedCompiler {
    /** Stable per-user run-files directory so a warm daemon is reused across compiles and builds. */
    val daemonRunDir: File = File(System.getProperty("user.home"), ".kbuild/kotlin-daemon")

    private val loader: URLClassLoader by lazy {
        val classpathUrls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        URLClassLoader(classpathUrls, ClassLoader.getSystemClassLoader().parent)
    }

    /**
     * Resolve a driver `object`'s `compile(Map)` method bound to its singleton instance, loaded in
     * the isolated loader. [driverClassName] must name a Kotlin `object` exposing
     * `fun compile(request: Map<String, Any?>): List<String>`.
     */
    fun driver(driverClassName: String): (Map<String, Any?>) -> List<String> {
        val driverClass = loader.loadClass(driverClassName)
        val instance = driverClass.getField("INSTANCE").get(null)  // Kotlin `object` singleton.
        val method: Method = driverClass.getMethod("compile", Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return { request -> method.invoke(instance, request) as List<String> }
    }
}
