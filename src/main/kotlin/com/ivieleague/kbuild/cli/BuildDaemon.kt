package com.ivieleague.kbuild.cli

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Background daemon for fast build execution.
 *
 * The daemon keeps the build loaded in memory and accepts commands via a TCP socket.
 * This provides fast startup times for repeated build invocations.
 *
 * Protocol: Line-delimited JSON over TCP.
 */
class BuildDaemon(
    private val projectPath: File,
    private val buildClass: String,
    private val port: Int = findAvailablePort()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val engine = ExecutionEngine(scope)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var build: Any? = null
    private var serverSocket: ServerSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val pidFile: Path = projectPath.toPath().resolve(".kbuild-daemon.pid")

    companion object {
        fun findAvailablePort(): Int {
            ServerSocket(0).use { socket ->
                return socket.localPort
            }
        }

        /**
         * Try to connect to an existing daemon.
         * Returns the connection if successful, null otherwise.
         */
        fun tryConnect(projectPath: File): DaemonClient? {
            val pidFile = projectPath.toPath().resolve(".kbuild-daemon.pid")
            if (!Files.exists(pidFile)) return null

            return try {
                val info = Files.readString(pidFile).trim().split(":")
                val port = info[0].toInt()
                val pid = info.getOrNull(1)?.toLong()

                // Try to connect
                val socket = Socket("localhost", port)
                socket.soTimeout = 5000

                DaemonClient(socket)
            } catch (e: Exception) {
                // Daemon not running, clean up stale pid file
                try { Files.deleteIfExists(pidFile) } catch (e: Exception) {}
                null
            }
        }
    }

    /**
     * Start the daemon server.
     */
    fun start() {
        // Check if daemon is already running
        val existingClient = tryConnect(projectPath)
        if (existingClient != null) {
            println("Daemon already running on port ${existingClient.port}")
            existingClient.close()
            return
        }

        // Load the build
        println("Loading build class '$buildClass'...")
        build = loadBuild()
        if (build == null) {
            println("Error: Could not load build class '$buildClass'")
            return
        }

        // Start server
        serverSocket = ServerSocket(port)
        println("KBuild daemon started on port $port")
        println("PID: ${ProcessHandle.current().pid()}")

        // Write PID file
        Files.writeString(pidFile, "$port:${ProcessHandle.current().pid()}")

        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })

        // Accept connections
        scope.launch {
            while (isActive) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                } catch (e: SocketException) {
                    if (isActive) throw e
                }
            }
        }

        // Keep main thread alive
        runBlocking {
            scope.coroutineContext[Job]?.join()
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream(), true)

            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val request = json.decodeFromString<DaemonRequest>(line)
                    val response = handleRequest(request)
                    writer.println(json.encodeToString(response))
                }
            } catch (e: Exception) {
                val response = DaemonResponse(
                    id = "error",
                    status = "error",
                    error = e.message
                )
                try {
                    writer.println(json.encodeToString(response))
                } catch (e: Exception) {
                    // Ignore write errors
                }
            }
        }
    }

    private suspend fun handleRequest(request: DaemonRequest): DaemonResponse {
        return when (request.command) {
            "ping" -> DaemonResponse(request.id, "ok", value = "pong")
            "run" -> runExpression(request)
            "watch" -> watchExpression(request)
            "list" -> listTargets(request)
            "cancel" -> cancelJob(request)
            "stop" -> {
                scope.launch { shutdown() }
                DaemonResponse(request.id, "ok", value = "stopping")
            }
            else -> DaemonResponse(request.id, "error", error = "Unknown command: ${request.command}")
        }
    }

    private suspend fun runExpression(request: DaemonRequest): DaemonResponse {
        val expression = request.expression
            ?: return DaemonResponse(request.id, "error", error = "Missing expression")

        val buildObj = build
            ?: return DaemonResponse(request.id, "error", error = "Build not loaded")

        return try {
            val parsed = ExpressionParser.parse(expression)
            val result = ExpressionEvaluator.evaluate(buildObj, parsed)

            when (result) {
                is EvaluationResult.Value -> {
                    DaemonResponse(request.id, "ok", value = result.value?.toString())
                }
                is EvaluationResult.Callable -> {
                    val resultDeferred = CompletableDeferred<DaemonResponse>()

                    val job = engine.execute(
                        callable = result.callable,
                        receiver = result.receiver,
                        args = result.args,
                        isReactive = result.isReactive,
                        mode = ExecutionMode.Once,
                        listener = object : ExecutionListener {
                            override fun onResult(executionResult: ExecutionResult) {
                                val response = when (executionResult) {
                                    is ExecutionResult.Success -> DaemonResponse(
                                        request.id,
                                        "ok",
                                        value = executionResult.value?.toString(),
                                        durationMs = executionResult.durationMs
                                    )
                                    is ExecutionResult.Error -> DaemonResponse(
                                        request.id,
                                        "error",
                                        error = executionResult.exception.message,
                                        durationMs = executionResult.durationMs
                                    )
                                    ExecutionResult.Loading -> DaemonResponse(
                                        request.id,
                                        "loading"
                                    )
                                }
                                resultDeferred.complete(response)
                            }
                        }
                    )

                    activeJobs[request.id] = job
                    try {
                        resultDeferred.await()
                    } finally {
                        activeJobs.remove(request.id)
                    }
                }
            }
        } catch (e: Exception) {
            DaemonResponse(request.id, "error", error = e.message)
        }
    }

    private fun watchExpression(request: DaemonRequest): DaemonResponse {
        val expression = request.expression
            ?: return DaemonResponse(request.id, "error", error = "Missing expression")

        val buildObj = build
            ?: return DaemonResponse(request.id, "error", error = "Build not loaded")

        return try {
            val parsed = ExpressionParser.parse(expression)
            val result = ExpressionEvaluator.evaluate(buildObj, parsed)

            when (result) {
                is EvaluationResult.Value -> {
                    DaemonResponse(request.id, "error", error = "Cannot watch a value")
                }
                is EvaluationResult.Callable -> {
                    // Cancel any existing watch with same ID
                    activeJobs[request.id]?.cancel()

                    val job = engine.execute(
                        callable = result.callable,
                        receiver = result.receiver,
                        args = result.args,
                        isReactive = result.isReactive,
                        mode = ExecutionMode.Watch(),
                        listener = object : ExecutionListener {
                            override fun onResult(executionResult: ExecutionResult) {
                                // Results are streamed back in watch mode
                                // The client needs to keep reading responses
                            }
                        }
                    )

                    activeJobs[request.id] = job
                    DaemonResponse(request.id, "watching", value = expression)
                }
            }
        } catch (e: Exception) {
            DaemonResponse(request.id, "error", error = e.message)
        }
    }

    private fun listTargets(request: DaemonRequest): DaemonResponse {
        val buildObj = build
            ?: return DaemonResponse(request.id, "error", error = "Build not loaded")

        val targets = ExpressionEvaluator.listTargets(buildObj)
        val targetStrings = targets.map { target ->
            val marker = if (target.isReactive) "⟳" else " "
            val params = if (target.isFunction) {
                target.parameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.type.toString().substringAfterLast(".")}"
                }
            } else ""
            val signature = if (target.isFunction) "(${params})" else ""
            "$marker ${target.name}$signature: ${target.returnType.toString().substringAfterLast(".")}"
        }

        return DaemonResponse(request.id, "ok", value = targetStrings.joinToString("\n"))
    }

    private fun cancelJob(request: DaemonRequest): DaemonResponse {
        val jobId = request.jobId ?: request.id
        val job = activeJobs[jobId]

        return if (job != null) {
            job.cancel()
            activeJobs.remove(jobId)
            DaemonResponse(request.id, "ok", value = "cancelled")
        } else {
            DaemonResponse(request.id, "error", error = "No active job with ID: $jobId")
        }
    }

    private fun shutdown() {
        println("Shutting down daemon...")

        // Cancel all active jobs
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }

        // Delete PID file
        try {
            Files.deleteIfExists(pidFile)
        } catch (e: Exception) {
            // Ignore
        }

        // Cancel scope
        scope.cancel()

        println("Daemon stopped")
    }

    private fun loadBuild(): Any? {
        // First, try to load from project-local script file
        val buildFile = projectPath.resolve("$buildClass.kt")
        if (buildFile.exists()) {
            val result = loadBuildFromScript(buildFile, buildClass)
            if (result != null) return result
        }

        // Also check for Build.kt with the class name inside
        val genericBuildFile = projectPath.resolve("Build.kt")
        if (genericBuildFile.exists() && buildClass != "Build") {
            val result = loadBuildFromScript(genericBuildFile, buildClass)
            if (result != null) return result
        }

        // Try to find the build class on the classpath
        val possibleNames = listOf(
            buildClass,
            "${projectPath.name}.$buildClass"
        )

        for (name in possibleNames) {
            try {
                val clazz = Class.forName(name)
                try {
                    val instanceField = clazz.getField("INSTANCE")
                    return instanceField.get(null)
                } catch (e: NoSuchFieldException) {
                    return clazz.getDeclaredConstructor().newInstance()
                }
            } catch (e: ClassNotFoundException) {
                continue
            }
        }

        // Final fallback: try generic Build.kt with default class name
        if (genericBuildFile.exists()) {
            return loadBuildFromScript(genericBuildFile, buildClass)
        }

        return null
    }

    private fun loadBuildFromScript(file: File, className: String): Any? {
        try {
            val buildCacheDir = projectPath.resolve(".kbuild")
            val classesDir = buildCacheDir.resolve("classes")
            val cacheDir = buildCacheDir.resolve("cache")

            // Check if recompilation is needed
            val needsRecompile = !classesDir.exists() ||
                file.lastModified() > (classesDir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0)

            if (needsRecompile) {
                println("Compiling ${file.name}...")

                // Get kbuild's classpath
                val kbuildClasspath = getKBuildClasspath()

                // Create a dedicated source directory with just the build script
                val buildSrcDir = buildCacheDir.resolve("src")
                buildSrcDir.mkdirs()
                val buildScriptCopy = buildSrcDir.resolve(file.name)
                file.copyTo(buildScriptCopy, overwrite = true)

                kotlinJvmCompileBlocking(
                    name = "build-script",
                    sourceRoots = setOf(buildSrcDir),
                    classpathJars = kbuildClasspath,
                    arguments = {},
                    cache = cacheDir,
                    outputFolder = classesDir,
                    enableContextParameters = true
                )
            }

            // Load the compiled class
            val classLoader = URLClassLoader(
                arrayOf(classesDir.toURI().toURL()),
                this::class.java.classLoader
            )

            // Find the class - try with package prefix from the file
            val packageName = extractPackageName(file)
            val fullClassName = if (packageName != null) "$packageName.$className" else className

            val clazz = try {
                classLoader.loadClass(fullClassName)
            } catch (e: ClassNotFoundException) {
                classLoader.loadClass(className)
            }

            // Get INSTANCE for Kotlin object
            return try {
                val instanceField = clazz.getField("INSTANCE")
                instanceField.get(null)
            } catch (e: NoSuchFieldException) {
                clazz.getDeclaredConstructor().newInstance()
            }
        } catch (e: Kotlin.CompilationException) {
            println("Compilation failed:")
            e.messages.filter { it.severity.isError }.forEach { msg ->
                println("  ${msg.message} at ${msg.location}")
            }
            return null
        } catch (e: Exception) {
            println("Error loading ${file.name}: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun extractPackageName(file: File): String? {
        val packageRegex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val content = file.readText()
        return packageRegex.find(content)?.groupValues?.get(1)
    }

    private fun getKBuildClasspath(): Set<File> {
        val classLoader = this::class.java.classLoader
        val classpath = mutableSetOf<File>()

        // Try to get from system property first
        System.getProperty("kbuild.classpath")?.let { cp ->
            cp.split(File.pathSeparator).forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    classpath.add(file)
                }
            }
        }

        // Also try to get from URLClassLoader if available
        if (classLoader is URLClassLoader) {
            classLoader.urLs.forEach { url ->
                if (url.protocol == "file") {
                    classpath.add(File(url.toURI()))
                }
            }
        }

        // Fallback: try to find from java.class.path
        if (classpath.isEmpty()) {
            System.getProperty("java.class.path")?.split(File.pathSeparator)?.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    classpath.add(file)
                }
            }
        }

        return classpath
    }
}

/**
 * Daemon communication protocol.
 */
@Serializable
data class DaemonRequest(
    val id: String,
    val command: String,
    val expression: String? = null,
    val jobId: String? = null
)

@Serializable
data class DaemonResponse(
    val id: String,
    val status: String,
    val value: String? = null,
    val error: String? = null,
    val durationMs: Long? = null
)

/**
 * Client for connecting to a running daemon.
 */
class DaemonClient(private val socket: Socket) : Closeable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = PrintWriter(socket.getOutputStream(), true)
    private val json = Json { ignoreUnknownKeys = true }

    val port: Int get() = socket.port

    fun send(request: DaemonRequest): DaemonResponse {
        writer.println(json.encodeToString(request))
        val line = reader.readLine() ?: throw IOException("Connection closed")
        return json.decodeFromString(line)
    }

    fun ping(): Boolean {
        return try {
            val response = send(DaemonRequest("ping", "ping"))
            response.status == "ok"
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try { reader.close() } catch (e: Exception) {}
        try { writer.close() } catch (e: Exception) {}
        try { socket.close() } catch (e: Exception) {}
    }
}
