package com.ivieleague.kbuild.cli

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import kotlinx.coroutines.*
import java.io.File
import java.net.URLClassLoader
import kotlin.system.exitProcess

/**
 * Command-line interface for KBuild.
 *
 * Usage:
 * ```
 * kbuild <expression> [options]       Run a build target
 * kbuild --repl                       Interactive REPL mode
 * kbuild --daemon                     Start background daemon
 * kbuild --list                       List available targets
 *
 * Expression syntax:
 *   Build.compile                     Property or no-arg method
 *   Build.compile()                   Explicit method call
 *   Build.test(".*Foo")               Method with arguments
 *   Build.project.buildJvm            Chained access
 *
 * Options:
 *   --watch, -w                       Watch mode (re-run on changes)
 *   --project, -p <path>              Project root (default: .)
 *   --build, -b <class>               Build class name (default: Build)
 *   --verbose, -v                     Verbose output
 *   --repl                            Interactive REPL mode
 *   --daemon                          Start daemon process
 *   --list                            List available targets
 *   --help, -h                        Show help
 * ```
 */
object KBuildCli {

    data class CliOptions(
        val mode: Mode,
        val expression: String? = null,
        val projectPath: File = File("."),
        val buildClass: String = "Build",
        val verbose: Boolean = false,
        val watch: Boolean = false,
        val extraArgs: List<String> = emptyList()
    )

    enum class Mode {
        RUN,        // Run an expression
        REPL,       // Interactive REPL
        DAEMON,     // Start daemon
        LIST,       // List targets
        HELP,       // Show help
        VERSION     // Show version
    }

    fun parseArgs(args: Array<String>): CliOptions {
        var mode = Mode.HELP
        var expression: String? = null
        var projectPath = File(".")
        var buildClass = "Build"
        var verbose = false
        var watch = false
        val extraArgs = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--help" || arg == "-h" -> mode = Mode.HELP
                arg == "--version" -> mode = Mode.VERSION
                arg == "--repl" -> mode = Mode.REPL
                arg == "--daemon" -> mode = Mode.DAEMON
                arg == "--list" || arg == "-l" -> mode = Mode.LIST
                arg == "--watch" || arg == "-w" -> watch = true
                arg == "--verbose" || arg == "-v" -> verbose = true
                arg == "--project" || arg == "-p" -> {
                    projectPath = File(args.getOrElse(++i) { "." })
                }
                arg == "--build" || arg == "-b" -> {
                    buildClass = args.getOrElse(++i) { "Build" }
                }
                arg.startsWith("-") -> extraArgs.add(arg)
                expression == null && mode == Mode.HELP -> {
                    // First non-option argument is the expression
                    mode = Mode.RUN
                    expression = arg
                }
                else -> extraArgs.add(arg)
            }
            i++
        }

        return CliOptions(
            mode = mode,
            expression = expression,
            projectPath = projectPath,
            buildClass = buildClass,
            verbose = verbose,
            watch = watch,
            extraArgs = extraArgs
        )
    }

    fun run(options: CliOptions) {
        when (options.mode) {
            Mode.HELP -> printHelp()
            Mode.VERSION -> printVersion()
            Mode.LIST -> listTargets(options)
            Mode.REPL -> startRepl(options)
            Mode.DAEMON -> startDaemon(options)
            Mode.RUN -> runExpression(options)
        }
    }

    private fun printHelp() {
        println("""
            |KBuild - Reactive Build System for Kotlin
            |
            |Usage:
            |  kbuild <expression> [options]    Run a build target
            |  kbuild --repl                    Interactive REPL mode
            |  kbuild --daemon                  Start background daemon
            |  kbuild --list                    List available targets
            |
            |Expression Syntax:
            |  Build.compile                    Property or no-arg method
            |  Build.compile()                  Explicit method call
            |  Build.test(".*Foo")              Method with arguments
            |  Build.project.buildJvm           Chained access
            |
            |Options:
            |  --watch, -w                      Watch mode (re-run on changes)
            |  --project, -p <path>             Project root (default: .)
            |  --build, -b <class>              Build class name (default: Build)
            |  --verbose, -v                    Verbose output
            |  --list, -l                       List available targets
            |  --help, -h                       Show this help
            |  --version                        Show version
            |
            |Examples:
            |  kbuild Build.compile             Build once
            |  kbuild Build.compile --watch     Build and watch for changes
            |  kbuild Build.test                Run tests
            |  kbuild --list                    Show available targets
            |  kbuild --repl                    Start interactive mode
            |
            |REPL Commands:
            |  ls                               List targets in current context
            |  cd <name>                        Navigate into nested object
            |  <expression>                     Run expression
            |  watch <expression>               Run expression in watch mode
            |  help                             Show REPL help
            |  exit                             Exit REPL
        """.trimMargin())
    }

    private fun printVersion() {
        println("KBuild version 1.0.0-SNAPSHOT")
        try {
            println("Kotlin version ${com.ivieleague.kbuild.kotlin.Kotlin.version}")
        } catch (e: Exception) {
            // Kotlin class might not be available
        }
    }

    private fun listTargets(options: CliOptions) {
        val build = loadBuild(options)
        if (build == null) {
            println("Error: Could not load build class '${options.buildClass}'")
            exitProcess(1)
        }

        println("Available targets in ${build::class.simpleName}:")
        println()

        val targets = ExpressionEvaluator.listTargets(build)

        // Group by type
        val properties = targets.filter { !it.isFunction }
        val functions = targets.filter { it.isFunction }

        if (properties.isNotEmpty()) {
            println("Properties:")
            for (target in properties) {
                val marker = if (target.isReactive) "⟳" else " "
                val typeName = target.returnType.toString().substringAfterLast(".")
                println("  $marker ${target.name}: $typeName")
            }
            println()
        }

        if (functions.isNotEmpty()) {
            println("Functions:")
            for (target in functions) {
                val marker = if (target.isReactive) "⟳" else " "
                val params = target.parameters.joinToString(", ") { p ->
                    val name = p.name ?: "_"
                    val type = p.type.toString().substringAfterLast(".")
                    "$name: $type"
                }
                val returnType = target.returnType.toString().substringAfterLast(".")
                println("  $marker ${target.name}($params): $returnType")
            }
        }

        println()
        println("Legend: ⟳ = reactive (supports watch mode)")
    }

    private fun startRepl(options: CliOptions) {
        val build = loadBuild(options)
        if (build == null) {
            println("Error: Could not load build class '${options.buildClass}'")
            exitProcess(1)
        }

        val repl = BuildRepl(build, options.verbose)
        repl.start()
    }

    private fun startDaemon(options: CliOptions) {
        println("Starting KBuild daemon...")
        val daemon = BuildDaemon(options.projectPath, options.buildClass)
        daemon.start()
    }

    private fun runExpression(options: CliOptions) {
        val expression = options.expression
        if (expression == null) {
            println("Error: No expression provided")
            printHelp()
            exitProcess(1)
        }

        val build = loadBuild(options)
        if (build == null) {
            println("Error: Could not load build class '${options.buildClass}'")
            exitProcess(1)
        }

        // Parse the expression
        val parsed = try {
            ExpressionParser.parse(expression)
        } catch (e: Exception) {
            println("Error parsing expression: ${e.message}")
            exitProcess(1)
        }

        // Evaluate the expression
        val result = try {
            ExpressionEvaluator.evaluate(build, parsed)
        } catch (e: Exception) {
            println("Error evaluating expression: ${e.message}")
            if (options.verbose) {
                e.printStackTrace()
            }
            exitProcess(1)
        }

        // Execute
        val engine = ExecutionEngine()
        val listener = ConsoleExecutionListener(options.verbose)
        val mode = if (options.watch) ExecutionMode.Watch() else ExecutionMode.Once

        when (result) {
            is EvaluationResult.Value -> {
                // Just print the value
                println("Result: ${result.value}")
            }
            is EvaluationResult.Callable -> {
                val job = engine.execute(
                    callable = result.callable,
                    receiver = result.receiver,
                    args = result.args,
                    isReactive = result.isReactive,
                    mode = mode,
                    listener = listener
                )

                // Setup shutdown hook for watch mode
                if (options.watch) {
                    Runtime.getRuntime().addShutdownHook(Thread {
                        println("\nShutting down...")
                        job.cancel()
                        engine.shutdown()
                    })

                    println("Watching for changes. Press Ctrl+C to stop.")
                }

                // Wait for completion
                runBlocking {
                    job.join()
                }
            }
        }
    }

    private fun loadBuild(options: CliOptions): Any? {
        val className = options.buildClass

        // First, try to load from project-local script file
        // This takes precedence over classpath to allow project-specific builds
        val buildFile = options.projectPath.resolve("$className.kt")
        if (buildFile.exists()) {
            val result = loadBuildFromScript(buildFile, className)
            if (result != null) return result
        }

        // Also check for Build.kt with the class name inside
        val genericBuildFile = options.projectPath.resolve("Build.kt")
        if (genericBuildFile.exists() && className != "Build") {
            val result = loadBuildFromScript(genericBuildFile, className)
            if (result != null) return result
        }

        // Try to find the build class on the classpath
        val possibleNames = listOf(
            className,
            "${options.projectPath.name}.$className"
            // Note: Removed "com.ivieleague.kbuild.$className" to avoid picking up KBuild's own Build
        )

        for (name in possibleNames) {
            try {
                val clazz = Class.forName(name)

                // Try to get INSTANCE (Kotlin object)
                try {
                    val instanceField = clazz.getField("INSTANCE")
                    return instanceField.get(null)
                } catch (e: NoSuchFieldException) {
                    // Not a Kotlin object, try to construct
                    return clazz.getDeclaredConstructor().newInstance()
                }
            } catch (e: ClassNotFoundException) {
                continue
            }
        }

        // Final fallback: try generic Build.kt with default class name
        if (genericBuildFile.exists()) {
            return loadBuildFromScript(genericBuildFile, className)
        }

        return null
    }

    private fun loadBuildFromScript(file: File, className: String = "Build"): Any? {
        // Compile Build.kt using kbuild's own K2 compiler with context parameters
        try {
            val projectDir = file.parentFile ?: File(".")
            val buildCacheDir = projectDir.resolve(".kbuild")
            val classesDir = buildCacheDir.resolve("classes")
            val cacheDir = buildCacheDir.resolve("cache")

            // Check if recompilation is needed
            val needsRecompile = !classesDir.exists() ||
                file.lastModified() > (classesDir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0)

            if (needsRecompile) {
                println("Compiling ${file.name}...")

                // Get kbuild's classpath (the JAR we're running from + dependencies)
                val kbuildClasspath = getKBuildClasspath()

                // Create a dedicated source directory with just the build script
                // to avoid compiling all project source files
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
                // Try without package
                classLoader.loadClass(className)
            }

            // Get INSTANCE for Kotlin object
            return try {
                val instanceField = clazz.getField("INSTANCE")
                instanceField.get(null)
            } catch (e: NoSuchFieldException) {
                // Not a Kotlin object, try to construct
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
            if (System.getProperty("kbuild.verbose") == "true") {
                e.printStackTrace()
            }
            return null
        }
    }

    private fun extractPackageName(file: File): String? {
        val packageRegex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val content = file.readText()
        return packageRegex.find(content)?.groupValues?.get(1)
    }

    private fun getKBuildClasspath(): Set<File> {
        // Get the classpath from the current classloader
        val classLoader = this::class.java.classLoader
        val classpath = mutableSetOf<File>()

        // Try to get from system property first (set by launcher script)
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
 * Main entry point for the CLI.
 */
fun main(args: Array<String>) {
    val options = KBuildCli.parseArgs(args)
    KBuildCli.run(options)
}
