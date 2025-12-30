package com.ivieleague.kbuild.cli

import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.test.*

class ExecutionEngineTest {

    // ==================== Test Fixtures ====================

    class TestBuild {
        var callCount = 0

        fun doWork(): String {
            callCount++
            return "done"
        }

        fun failingWork(): String {
            throw RuntimeException("Intentional failure")
        }

        fun slowWork(): String {
            Thread.sleep(100)
            return "slow done"
        }

        suspend fun suspendingWork(): String {
            delay(50)
            return "suspended done"
        }

        val simpleProperty = "property value"
    }

    /**
     * Test listener that records all events.
     */
    class RecordingListener : ExecutionListener {
        val events = CopyOnWriteArrayList<String>()
        val results = CopyOnWriteArrayList<ExecutionResult>()
        private val resultLatch = CountDownLatch(1)
        private val stopLatch = CountDownLatch(1)

        override fun onStart(expression: String) {
            events.add("start:$expression")
        }

        override fun onResult(result: ExecutionResult) {
            events.add("result:${result::class.simpleName}")
            results.add(result)
            resultLatch.countDown()
        }

        override fun onRerun(reason: String) {
            events.add("rerun:$reason")
        }

        override fun onStop() {
            events.add("stop")
            stopLatch.countDown()
        }

        fun awaitResult(timeout: Long = 5000): Boolean {
            return resultLatch.await(timeout, TimeUnit.MILLISECONDS)
        }

        fun awaitStop(timeout: Long = 5000): Boolean {
            return stopLatch.await(timeout, TimeUnit.MILLISECONDS)
        }
    }

    // ==================== ExecutionMode Tests ====================

    @Test
    fun `ExecutionMode Once is singleton`() {
        assertSame(ExecutionMode.Once, ExecutionMode.Once)
    }

    @Test
    fun `ExecutionMode Watch has default debounce`() {
        val watch = ExecutionMode.Watch()
        assertEquals(100L, watch.debounceMs)
    }

    @Test
    fun `ExecutionMode Watch allows custom debounce`() {
        val watch = ExecutionMode.Watch(debounceMs = 500)
        assertEquals(500L, watch.debounceMs)
    }

    // ==================== ExecutionResult Tests ====================

    @Test
    fun `ExecutionResult Success holds value and duration`() {
        val result = ExecutionResult.Success("test value", 123L)
        assertEquals("test value", result.value)
        assertEquals(123L, result.durationMs)
    }

    @Test
    fun `ExecutionResult Success can hold null value`() {
        val result = ExecutionResult.Success(null, 0L)
        assertNull(result.value)
    }

    @Test
    fun `ExecutionResult Error holds exception and duration`() {
        val exception = RuntimeException("test error")
        val result = ExecutionResult.Error(exception, 50L)
        assertSame(exception, result.exception)
        assertEquals(50L, result.durationMs)
    }

    @Test
    fun `ExecutionResult Loading is singleton`() {
        assertSame(ExecutionResult.Loading, ExecutionResult.Loading)
    }

    // ==================== ExecutionEngine Once Mode Tests ====================

    @Test
    fun `execute once calls function and reports success`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "doWork" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())
        assertTrue(listener.awaitStop())

        assertEquals(1, build.callCount)
        assertTrue("start:doWork" in listener.events)
        assertTrue("result:Success" in listener.events)
        assertTrue("stop" in listener.events)

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)
        assertEquals("done", result.value)

        engine.shutdown()
    }

    @Test
    fun `execute once reports error on failure`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "failingWork" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())
        assertTrue(listener.awaitStop())

        assertTrue("result:Error" in listener.events)

        val result = listener.results.first()
        assertIs<ExecutionResult.Error>(result)
        // The exception may be wrapped, check cause or message
        val exceptionMessage = result.exception.message ?: result.exception.cause?.message
        assertEquals("Intentional failure", exceptionMessage)

        engine.shutdown()
    }

    @Test
    fun `execute once can read property`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberProperties.find { it.name == "simpleProperty" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)
        assertEquals("property value", result.value)

        engine.shutdown()
    }

    @Test
    fun `execute once measures duration`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "slowWork" }!!

        engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)
        assertTrue(result.durationMs >= 100, "Duration should be at least 100ms, was ${result.durationMs}ms")

        engine.shutdown()
    }

    @Test
    fun `execute once handles suspend functions`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "suspendingWork" }!!

        engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)
        assertEquals("suspended done", result.value)

        engine.shutdown()
    }

    // ==================== ExecutionEngine Watch Mode Tests ====================

    @Test
    fun `execute watch non-reactive runs once and stays alive`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "doWork" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Watch(),
            listener = listener
        )

        assertTrue(listener.awaitResult())
        assertTrue(job.isActive, "Job should stay active in watch mode")

        assertEquals(1, build.callCount)

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)

        // Cancel the job
        job.cancel()
        assertTrue(listener.awaitStop())

        engine.shutdown()
    }

    @Test
    fun `execute watch can be cancelled`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "doWork" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Watch(),
            listener = listener
        )

        assertTrue(listener.awaitResult())

        job.cancel()

        assertTrue(listener.awaitStop())
        assertTrue("stop" in listener.events)

        engine.shutdown()
    }

    // ==================== ExecutionEngine Shutdown Tests ====================

    @Test
    fun `shutdown cancels running jobs`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        val callable = build::class.memberFunctions.find { it.name == "doWork" }!!

        val job = engine.execute(
            callable = callable,
            receiver = build,
            args = emptyList(),
            isReactive = false,
            mode = ExecutionMode.Watch(),
            listener = listener
        )

        assertTrue(listener.awaitResult())
        assertTrue(job.isActive)

        engine.shutdown()

        // Give time for cancellation to propagate
        Thread.sleep(100)
        assertTrue(job.isCancelled || job.isCompleted)
    }

    // ==================== ConsoleExecutionListener Tests ====================

    @Test
    fun `ConsoleExecutionListener formats null value`() {
        // Test via reflection since formatValue is private
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        assertEquals("null", formatValue.invoke(listener, null as Any?))
    }

    @Test
    fun `ConsoleExecutionListener formats Unit value`() {
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        assertEquals("(completed)", formatValue.invoke(listener, Unit))
    }

    @Test
    fun `ConsoleExecutionListener formats collection`() {
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        val result = formatValue.invoke(listener, listOf(1, 2, 3)) as String
        assertTrue(result.contains("3 items"))
    }

    @Test
    fun `ConsoleExecutionListener formats map`() {
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        val result = formatValue.invoke(listener, mapOf("a" to 1, "b" to 2)) as String
        assertTrue(result.contains("2 entries"))
    }

    @Test
    fun `ConsoleExecutionListener formats File`() {
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        val file = java.io.File("/tmp/test.txt")
        val result = formatValue.invoke(listener, file) as String
        assertTrue(result.contains("test.txt"))
    }

    @Test
    fun `ConsoleExecutionListener truncates long strings`() {
        val listener = ConsoleExecutionListener()
        val formatValue = listener::class.java.getDeclaredMethod("formatValue", Any::class.java)
        formatValue.isAccessible = true

        val longString = "a".repeat(300)
        val result = formatValue.invoke(listener, longString) as String
        assertEquals(200, result.length)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `full workflow with ExpressionParser and Evaluator`() {
        val engine = ExecutionEngine()
        val build = TestBuild()
        val listener = RecordingListener()

        // Parse expression
        val expr = ExpressionParser.parse("doWork()")

        // Evaluate to get callable
        val evalResult = ExpressionEvaluator.evaluate(build, expr)
        assertIs<EvaluationResult.Callable>(evalResult)

        // Execute
        engine.execute(
            callable = evalResult.callable,
            receiver = evalResult.receiver,
            args = evalResult.args,
            isReactive = evalResult.isReactive,
            mode = ExecutionMode.Once,
            listener = listener
        )

        assertTrue(listener.awaitResult())

        val result = listener.results.first()
        assertIs<ExecutionResult.Success>(result)
        assertEquals("done", result.value)

        engine.shutdown()
    }
}
