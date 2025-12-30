package com.ivieleague.kbuild.cli

import kotlinx.coroutines.*
import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.TerminalBuilder
import java.io.File

/**
 * Interactive REPL for KBuild.
 *
 * Commands:
 * - `ls` - List available targets in current context
 * - `cd <name>` - Navigate into a nested object
 * - `cd ..` - Navigate back to parent
 * - `watch <expr>` - Run expression in watch mode
 * - `stop` - Stop the current watch
 * - `help` - Show help
 * - `exit` / `quit` - Exit REPL
 * - Any other input is evaluated as an expression
 */
class BuildRepl(
    private val rootBuild: Any,
    private val verbose: Boolean = false
) {
    private var currentContext: Any = rootBuild
    private val contextStack = mutableListOf<Any>()
    private var currentWatchJob: Job? = null
    private val engine = ExecutionEngine()

    /**
     * Start the interactive REPL.
     */
    fun start() {
        val terminal = TerminalBuilder.builder()
            .system(true)
            .build()

        val completer = buildCompleter()

        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .parser(DefaultParser())
            .history(DefaultHistory())
            .variable(LineReader.HISTORY_FILE, File(System.getProperty("user.home"), ".kbuild_history").toPath())
            .option(LineReader.Option.CASE_INSENSITIVE, true)
            .build()

        println("KBuild REPL - Type 'help' for commands")
        println("Current context: ${currentContext::class.simpleName}")
        println()

        while (true) {
            val prompt = buildPrompt()

            val line = try {
                reader.readLine(prompt)
            } catch (e: UserInterruptException) {
                // Ctrl+C
                if (currentWatchJob?.isActive == true) {
                    println("Stopping watch...")
                    currentWatchJob?.cancel()
                    currentWatchJob = null
                    continue
                } else {
                    println("Use 'exit' to quit")
                    continue
                }
            } catch (e: EndOfFileException) {
                // Ctrl+D
                break
            }

            if (line.isBlank()) continue

            try {
                processCommand(line.trim())
            } catch (e: Exception) {
                println("Error: ${e.message}")
                if (verbose) {
                    e.printStackTrace()
                }
            }
        }

        cleanup()
        println("Goodbye!")
    }

    private fun buildPrompt(): String {
        val contextName = currentContext::class.simpleName ?: "?"
        val watchIndicator = if (currentWatchJob?.isActive == true) "⟳ " else ""
        return "$watchIndicator$contextName> "
    }

    private fun buildCompleter(): Completer {
        return Completer { _, line, candidates ->
            val word = line.word().lowercase()

            // Built-in commands
            val commands = listOf("ls", "cd", "watch", "stop", "help", "exit", "quit")
            for (cmd in commands) {
                if (cmd.startsWith(word)) {
                    candidates.add(Candidate(cmd))
                }
            }

            // Available targets
            try {
                val targets = ExpressionEvaluator.listTargets(currentContext)
                for (target in targets) {
                    if (target.name.lowercase().startsWith(word)) {
                        val suffix = if (target.isFunction && target.parameters.isNotEmpty()) "(" else ""
                        candidates.add(Candidate(target.name + suffix))
                    }
                }
            } catch (e: Exception) {
                // Ignore completion errors
            }
        }
    }

    private fun processCommand(input: String) {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        when (command) {
            "help", "?" -> printHelp()
            "exit", "quit" -> {
                cleanup()
                System.exit(0)
            }
            "ls", "list" -> listTargets()
            "cd" -> navigate(args)
            "watch" -> startWatch(args)
            "stop" -> stopWatch()
            "run" -> runOnce(args)
            else -> runOnce(input)
        }
    }

    private fun printHelp() {
        println("""
            |KBuild REPL Commands:
            |
            |  ls                 List available targets in current context
            |  cd <name>          Navigate into a nested object
            |  cd ..              Navigate back to parent
            |  watch <expr>       Run expression in watch mode (re-runs on changes)
            |  stop               Stop the current watch
            |  run <expr>         Run expression once (same as just typing the expression)
            |  help               Show this help
            |  exit, quit         Exit REPL
            |
            |Expression syntax:
            |  compile            Property or no-arg function
            |  compile()          Explicit function call
            |  test(".*Foo")      Function with arguments
            |  project.buildJvm   Chained access
            |
            |Tips:
            |  - Use Tab for auto-completion
            |  - Use Ctrl+C to stop a running watch
            |  - ⟳ marks reactive targets (support watch mode)
        """.trimMargin())
    }

    private fun listTargets() {
        val targets = ExpressionEvaluator.listTargets(currentContext)

        val properties = targets.filter { !it.isFunction }
        val functions = targets.filter { it.isFunction }

        if (properties.isNotEmpty()) {
            println("Properties:")
            for (target in properties) {
                val marker = if (target.isReactive) "⟳" else " "
                val typeName = target.returnType.toString().substringAfterLast(".")
                println("  $marker ${target.name}: $typeName")
            }
        }

        if (functions.isNotEmpty()) {
            if (properties.isNotEmpty()) println()
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

        if (contextStack.isNotEmpty()) {
            println()
            println("  (cd .. to go back)")
        }
    }

    private fun navigate(path: String) {
        if (path.isBlank()) {
            println("Usage: cd <name> or cd ..")
            return
        }

        if (path == "..") {
            if (contextStack.isEmpty()) {
                println("Already at root context")
            } else {
                currentContext = contextStack.removeLast()
                println("Context: ${currentContext::class.simpleName}")
            }
            return
        }

        // Navigate into the specified member
        val parsed = ExpressionParser.parse(path)
        val result = ExpressionEvaluator.evaluate(currentContext, parsed)

        when (result) {
            is EvaluationResult.Value -> {
                val value = result.value
                if (value == null) {
                    println("Cannot navigate into null value")
                } else {
                    contextStack.add(currentContext)
                    currentContext = value
                    println("Context: ${currentContext::class.simpleName}")
                }
            }
            is EvaluationResult.Callable -> {
                // Invoke the callable to get the value
                val value = result.invoke()
                if (value == null) {
                    println("Cannot navigate into null value")
                } else {
                    contextStack.add(currentContext)
                    currentContext = value
                    println("Context: ${currentContext::class.simpleName}")
                }
            }
        }
    }

    private fun startWatch(expression: String) {
        if (expression.isBlank()) {
            println("Usage: watch <expression>")
            return
        }

        // Stop any existing watch
        currentWatchJob?.cancel()

        val parsed = ExpressionParser.parse(expression)
        val result = ExpressionEvaluator.evaluate(currentContext, parsed)

        when (result) {
            is EvaluationResult.Value -> {
                println("Cannot watch a value, only callable targets")
                return
            }
            is EvaluationResult.Callable -> {
                if (!result.isReactive) {
                    println("Warning: '${expression}' is not reactive, will only run once")
                }

                println("Watching '$expression'. Press Ctrl+C or type 'stop' to stop.")

                currentWatchJob = engine.execute(
                    callable = result.callable,
                    receiver = result.receiver,
                    args = result.args,
                    isReactive = result.isReactive,
                    mode = ExecutionMode.Watch(),
                    listener = ConsoleExecutionListener(verbose)
                )
            }
        }
    }

    private fun stopWatch() {
        if (currentWatchJob?.isActive == true) {
            currentWatchJob?.cancel()
            currentWatchJob = null
            println("Watch stopped")
        } else {
            println("No active watch")
        }
    }

    private fun runOnce(expression: String) {
        if (expression.isBlank()) return

        val parsed = ExpressionParser.parse(expression)
        val result = ExpressionEvaluator.evaluate(currentContext, parsed)

        when (result) {
            is EvaluationResult.Value -> {
                println("Result: ${result.value}")
            }
            is EvaluationResult.Callable -> {
                val listener = ConsoleExecutionListener(verbose)
                val job = engine.execute(
                    callable = result.callable,
                    receiver = result.receiver,
                    args = result.args,
                    isReactive = result.isReactive,
                    mode = ExecutionMode.Once,
                    listener = listener
                )

                // Wait for completion
                runBlocking {
                    job.join()
                }
            }
        }
    }

    private fun cleanup() {
        currentWatchJob?.cancel()
        engine.shutdown()
    }
}
