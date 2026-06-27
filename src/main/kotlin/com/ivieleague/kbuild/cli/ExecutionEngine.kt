package com.ivieleague.kbuild.cli

import com.lightningkite.reactive.context.ReactiveLoading
import com.lightningkite.reactive.context.reactiveSuspending
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.jvm.isAccessible

/**
 * Execution mode for running build targets.
 */
sealed class ExecutionMode {
    /** Run once and return the result */
    object Once : ExecutionMode()

    /** Run reactively, re-executing when dependencies change */
    data class Watch(val debounceMs: Long = 100) : ExecutionMode()
}

/**
 * Result of a single execution.
 */
sealed class ExecutionResult {
    data class Success(val value: Any?, val durationMs: Long) : ExecutionResult()
    data class Error(val exception: Throwable, val durationMs: Long) : ExecutionResult()
    object Loading : ExecutionResult()
}

/**
 * Callback interface for execution events.
 */
interface ExecutionListener {
    fun onStart(expression: String) {}
    fun onResult(result: ExecutionResult) {}
    fun onRerun(reason: String) {}
    fun onStop() {}
}

/**
 * Engine for executing build targets in different modes.
 */
class ExecutionEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Execute a callable in the specified mode.
     *
     * @param callable The callable to execute
     * @param receiver The receiver object (for instance methods)
     * @param args Arguments to pass to the callable
     * @param isReactive Whether the callable expects a ReactiveContext
     * @param mode Execution mode (once or watch)
     * @param listener Callback for execution events
     * @return A Job that can be cancelled to stop execution
     */
    fun execute(
        callable: KCallable<*>,
        receiver: Any?,
        args: List<Any?>,
        isReactive: Boolean,
        mode: ExecutionMode,
        listener: ExecutionListener
    ): Job {
        return when (mode) {
            is ExecutionMode.Once -> executeOnce(callable, receiver, args, isReactive, listener)
            is ExecutionMode.Watch -> executeWatch(callable, receiver, args, isReactive, listener)
        }
    }

    /**
     * Execute a callable once and return.
     */
    private fun executeOnce(
        callable: KCallable<*>,
        receiver: Any?,
        args: List<Any?>,
        isReactive: Boolean,
        listener: ExecutionListener
    ): Job = scope.launch {
        listener.onStart(callable.name)

        try {
            val startTime = System.currentTimeMillis()

            val result = if (isReactive) {
                // Run in a reactive context that completes immediately
                executeInReactiveContext(callable, receiver, args)
            } else {
                invokeCallable(callable, receiver, args)
            }

            val duration = System.currentTimeMillis() - startTime
            listener.onResult(ExecutionResult.Success(result, duration))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listener.onResult(ExecutionResult.Error(e, 0))
        } finally {
            listener.onStop()
        }
    }

    /**
     * Execute a callable reactively, re-running when dependencies change.
     */
    private fun executeWatch(
        callable: KCallable<*>,
        receiver: Any?,
        args: List<Any?>,
        isReactive: Boolean,
        listener: ExecutionListener
    ): Job = scope.launch {
        listener.onStart(callable.name)

        if (!isReactive) {
            // Non-reactive functions can only be run once
            try {
                val startTime = System.currentTimeMillis()
                val result = invokeCallable(callable, receiver, args)
                val duration = System.currentTimeMillis() - startTime
                listener.onResult(ExecutionResult.Success(result, duration))
                // Keep alive but don't re-run
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                listener.onResult(ExecutionResult.Error(e, 0))
            } finally {
                listener.onStop()
            }
            return@launch
        }

        // Reactive execution using suspend-based reactivity
        var runCount = 0

        // Create a child scope for the reactive context
        val reactiveJob = Job(coroutineContext[Job])
        val reactiveScope = CoroutineScope(coroutineContext + reactiveJob)

        try {
            reactiveScope.reactiveSuspending {
                runCount++

                if (runCount > 1) {
                    listener.onRerun("Dependency changed")
                }

                val startTime = System.currentTimeMillis()

                try {
                    val result = invokeCallable(callable, receiver, args)
                    val duration = System.currentTimeMillis() - startTime
                    listener.onResult(ExecutionResult.Success(result, duration))
                    result
                } catch (e: ReactiveLoading) {
                    listener.onResult(ExecutionResult.Loading)
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val duration = System.currentTimeMillis() - startTime
                    listener.onResult(ExecutionResult.Error(e, duration))
                    throw e
                }
            }

            // Keep alive until cancelled
            awaitCancellation()
        } finally {
            reactiveJob.cancel()
            listener.onStop()
        }
    }

    /**
     * Execute a callable in a fresh reactive context (for one-shot suspend functions).
     */
    private suspend fun executeInReactiveContext(
        callable: KCallable<*>,
        receiver: Any?,
        args: List<Any?>
    ): Any? = coroutineScope {
        val result = CompletableDeferred<Any?>()

        reactiveSuspending {
            try {
                val value = invokeCallable(callable, receiver, args)
                result.complete(value)
                value
            } catch (e: ReactiveLoading) {
                // Wait for loading
                throw e
            } catch (e: Throwable) {
                result.completeExceptionally(e)
                throw e
            }
        }

        // Wait for the result with a timeout
        withTimeout(300_000) { // 5 minute timeout
            result.await()
        }
    }

    /**
     * Invoke a callable with the given receiver and arguments.
     */
    private suspend fun invokeCallable(
        callable: KCallable<*>,
        receiver: Any?,
        args: List<Any?>
    ): Any? {
        callable.isAccessible = true

        return when (callable) {
            is KFunction<*> -> {
                // Build parameter map for callBy (supports default parameters)
                val paramMap = buildMap {
                    val params = callable.parameters
                    var argIndex = 0

                    for (param in params) {
                        when (param.kind) {
                            KParameter.Kind.INSTANCE -> {
                                receiver?.let { put(param, it) }
                            }
                            KParameter.Kind.EXTENSION_RECEIVER -> {
                                receiver?.let { put(param, it) }
                            }
                            KParameter.Kind.VALUE -> {
                                if (argIndex < args.size) {
                                    put(param, args[argIndex])
                                    argIndex++
                                } else if (!param.isOptional) {
                                    throw IllegalArgumentException(
                                        "Missing required parameter: ${param.name}"
                                    )
                                }
                                // If optional and no arg provided, don't add to map (uses default)
                            }
                            else -> {
                                // Context parameters and other types are handled by the runtime
                            }
                        }
                    }
                }

                if (callable.isSuspend) {
                    callable.callSuspendBy(paramMap)
                } else {
                    callable.callBy(paramMap)
                }
            }
            is KProperty<*> -> {
                @Suppress("UNCHECKED_CAST")
                if (receiver != null) {
                    (callable as KProperty1<Any, *>).get(receiver)
                } else {
                    (callable as kotlin.reflect.KProperty0<*>).get()
                }
            }
            else -> throw IllegalArgumentException("Unknown callable type: ${callable::class}")
        }
    }

    /**
     * Stop all running executions.
     */
    fun shutdown() {
        scope.cancel()
    }
}

/**
 * Console-based execution listener that prints results to stdout.
 */
class ConsoleExecutionListener(
    private val verbose: Boolean = true
) : ExecutionListener {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Set when an executed target returned test results that included failures. */
    var hadTestFailures: Boolean = false
        private set

    override fun onStart(expression: String) {
        if (verbose) {
            println("[${timestamp()}] Running: $expression")
        }
    }

    override fun onResult(result: ExecutionResult) {
        when (result) {
            is ExecutionResult.Success -> {
                val testResults = result.value.asTestResults()
                if (testResults != null) {
                    printTestSummary(testResults, result.durationMs)
                    return
                }
                val formattedValue = formatValue(result.value)
                println("[${timestamp()}] ✓ Success (${result.durationMs}ms): $formattedValue")
            }
            is ExecutionResult.Error -> {
                println("[${timestamp()}] ✗ Error: ${result.exception.message}")
                if (verbose) {
                    result.exception.printStackTrace()
                }
            }
            is ExecutionResult.Loading -> {
                if (verbose) {
                    println("[${timestamp()}] ⟳ Loading...")
                }
            }
        }
    }

    override fun onRerun(reason: String) {
        println("[${timestamp()}] ⟳ Rerunning: $reason")
    }

    override fun onStop() {
        if (verbose) {
            println("[${timestamp()}] Stopped")
        }
    }

    private fun timestamp(): String = LocalTime.now().format(timeFormatter)

    /** Interpret a returned value as a non-empty collection of [TestResult], or null if it isn't one. */
    private fun Any?.asTestResults(): List<com.ivieleague.kbuild.common.TestResult>? {
        if (this !is Collection<*> || isEmpty()) return null
        if (!all { it is com.ivieleague.kbuild.common.TestResult }) return null
        @Suppress("UNCHECKED_CAST")
        return (this as Collection<com.ivieleague.kbuild.common.TestResult>).toList()
    }

    private fun printTestSummary(results: List<com.ivieleague.kbuild.common.TestResult>, durationMs: Long) {
        val failed = results.filter { !it.passed }
        val passed = results.size - failed.size
        if (failed.isEmpty()) {
            println("[${timestamp()}] ✓ Tests: $passed passed (${results.size} total) in ${durationMs}ms")
        } else {
            println("[${timestamp()}] ✗ Tests: $passed passed, ${failed.size} FAILED (${results.size} total) in ${durationMs}ms")
            failed.forEach { r -> println("    ✗ ${r.identifier}${r.error?.let { ": $it" } ?: ""}") }
            hadTestFailures = true
        }
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is Unit -> "(completed)"
        is Collection<*> -> "${value::class.simpleName}(${value.size} items)"
        is Map<*, *> -> "Map(${value.size} entries)"
        is java.io.File -> value.absolutePath
        else -> value.toString().take(200)
    }
}
