package com.ivieleague.kbuild.cli

import com.lightningkite.reactive.context.ReactiveContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ExpressionEvaluatorTest {

    // ==================== Test Fixtures ====================

    class SimpleBuild {
        val name = "SimpleBuild"
        val version = "1.0.0"
        var count = 0

        fun build(): String = "built"
        fun increment() { count++ }
        fun greet(name: String): String = "Hello, $name"
        fun add(a: Long, b: Long): Long = a + b
        fun withDefault(x: Long, y: Long = 10): Long = x + y
    }

    class NestedBuild {
        val project = Project()
        val config = Config()

        class Project {
            val name = "nested-project"
            val sources = listOf("src/main/kotlin")
            fun compile(): String = "compiled"
        }

        class Config {
            val debug = true
            fun getPort(): Int = 8080
        }
    }

    object SingletonBuild {
        val instance = "singleton"
        fun run(): String = "running"
    }

    class BuildWithReactive {
        // Simulate a reactive function by having ReactiveContext as first parameter
        // In real code this would be a context receiver, but for testing reflection we use this
        val regularProp = "regular"

        fun regularMethod(): String = "regular"

        // Note: Can't easily test actual context receivers with reflection,
        // but we can test the detection logic
    }

    // ==================== Property Access ====================

    @Test
    fun `evaluate single property`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("name")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals("SimpleBuild", result.value)
    }

    @Test
    fun `evaluate nested property`() {
        val build = NestedBuild()
        val expr = ExpressionParser.parse("project.name")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals("nested-project", result.value)
    }

    @Test
    fun `evaluate deeply nested property`() {
        val build = NestedBuild()
        val expr = ExpressionParser.parse("project.sources")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals(listOf("src/main/kotlin"), result.value)
    }

    @Test
    fun `evaluate with class name prefix skipped`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("SimpleBuild.name")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals("SimpleBuild", result.value)
    }

    // ==================== Method Calls ====================

    @Test
    fun `evaluate no-arg method returns callable`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("build")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("built", result.invoke())
    }

    @Test
    fun `evaluate explicit method call`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("build()")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("built", result.invoke())
    }

    @Test
    fun `evaluate method with string arg`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("""greet("World")""")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("Hello, World", result.invoke())
    }

    @Test
    fun `evaluate method with multiple args`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("add(3, 5)")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals(8L, result.invoke())
    }

    @Test
    fun `evaluate nested method call`() {
        val build = NestedBuild()
        val expr = ExpressionParser.parse("project.compile()")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("compiled", result.invoke())
    }

    @Test
    fun `evaluate chained property then method`() {
        val build = NestedBuild()
        val expr = ExpressionParser.parse("config.getPort()")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals(8080, result.invoke())
    }

    // ==================== Object/Singleton Support ====================

    @Test
    fun `evaluate object property`() {
        val expr = ExpressionParser.parse("instance")
        val result = ExpressionEvaluator.evaluate(SingletonBuild, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals("singleton", result.value)
    }

    @Test
    fun `evaluate object method`() {
        val expr = ExpressionParser.parse("run()")
        val result = ExpressionEvaluator.evaluate(SingletonBuild, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("running", result.invoke())
    }

    // ==================== Root Context Map ====================

    @Test
    fun `evaluate with root context map`() {
        val build = SimpleBuild()
        val context = mapOf("Build" to build)
        val expr = ExpressionParser.parse("Build.name")
        val result = ExpressionEvaluator.evaluate(context, expr)

        assertIs<EvaluationResult.Value>(result)
        assertEquals("SimpleBuild", result.value)
    }

    @Test
    fun `evaluate method call with root context map`() {
        val build = SimpleBuild()
        val context = mapOf("Build" to build)
        val expr = ExpressionParser.parse("Build.build()")
        val result = ExpressionEvaluator.evaluate(context, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals("built", result.invoke())
    }

    @Test
    fun `evaluate unknown root identifier throws`() {
        val context = mapOf("Build" to SimpleBuild())
        val expr = ExpressionParser.parse("Unknown.name")

        assertFailsWith<IllegalArgumentException> {
            ExpressionEvaluator.evaluate(context, expr)
        }
    }

    // ==================== Error Cases ====================

    @Test
    fun `evaluate unknown property throws`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("nonexistent")

        assertFailsWith<IllegalArgumentException> {
            ExpressionEvaluator.evaluate(build, expr)
        }
    }

    @Test
    fun `evaluate unknown method throws`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("nonexistent()")

        assertFailsWith<IllegalArgumentException> {
            ExpressionEvaluator.evaluate(build, expr)
        }
    }

    @Test
    fun `evaluate method with wrong arg count throws`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("add(1)")  // add needs 2 args

        assertFailsWith<IllegalArgumentException> {
            ExpressionEvaluator.evaluate(build, expr)
        }
    }

    @Test
    fun `evaluate chain through null throws`() {
        class NullableNested {
            val maybeProject: NestedBuild.Project? = null
        }
        val build = NullableNested()
        val expr = ExpressionParser.parse("maybeProject.name")

        assertFailsWith<IllegalArgumentException> {
            ExpressionEvaluator.evaluate(build, expr)
        }
    }

    // ==================== listTargets ====================

    @Test
    fun `listTargets returns properties`() {
        val build = SimpleBuild()
        val targets = ExpressionEvaluator.listTargets(build)

        val propertyNames = targets.filter { !it.isFunction }.map { it.name }
        assertTrue("name" in propertyNames)
        assertTrue("version" in propertyNames)
        assertTrue("count" in propertyNames)
    }

    @Test
    fun `listTargets returns functions`() {
        val build = SimpleBuild()
        val targets = ExpressionEvaluator.listTargets(build)

        val functionNames = targets.filter { it.isFunction }.map { it.name }
        assertTrue("build" in functionNames)
        assertTrue("increment" in functionNames)
        assertTrue("greet" in functionNames)
        assertTrue("add" in functionNames)
    }

    @Test
    fun `listTargets excludes standard object methods`() {
        val build = SimpleBuild()
        val targets = ExpressionEvaluator.listTargets(build)

        val names = targets.map { it.name }
        assertFalse("equals" in names)
        assertFalse("hashCode" in names)
        assertFalse("toString" in names)
    }

    @Test
    fun `listTargets includes parameter info`() {
        val build = SimpleBuild()
        val targets = ExpressionEvaluator.listTargets(build)

        val greet = targets.find { it.name == "greet" }!!
        assertTrue(greet.isFunction)
        assertEquals(1, greet.parameters.size)
        assertEquals("name", greet.parameters[0].name)

        val add = targets.find { it.name == "add" }!!
        assertEquals(2, add.parameters.size)
    }

    @Test
    fun `listTargets marks optional parameters`() {
        val build = SimpleBuild()
        val targets = ExpressionEvaluator.listTargets(build)

        val withDefault = targets.find { it.name == "withDefault" }!!
        assertEquals(2, withDefault.parameters.size)
        assertFalse(withDefault.parameters[0].isOptional)  // x is required
        assertTrue(withDefault.parameters[1].isOptional)   // y has default
    }

    // ==================== isReactive ====================

    @Test
    fun `isReactive returns false for regular properties`() {
        val build = BuildWithReactive()
        val targets = ExpressionEvaluator.listTargets(build)

        val regularProp = targets.find { it.name == "regularProp" }!!
        assertFalse(regularProp.isReactive)
    }

    @Test
    fun `isReactive returns false for regular methods`() {
        val build = BuildWithReactive()
        val targets = ExpressionEvaluator.listTargets(build)

        val regularMethod = targets.find { it.name == "regularMethod" }!!
        assertFalse(regularMethod.isReactive)
    }

    // ==================== Callable invoke ====================

    @Test
    fun `callable invoke executes function`() {
        val build = SimpleBuild()
        build.count = 0

        val expr = ExpressionParser.parse("increment()")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        result.invoke()

        assertEquals(1, build.count)
    }

    @Test
    fun `callable invoke with receiver`() {
        val build = SimpleBuild()
        val expr = ExpressionParser.parse("build")
        val result = ExpressionEvaluator.evaluate(build, expr)

        assertIs<EvaluationResult.Callable>(result)
        assertEquals(build, result.receiver)
        assertEquals("built", result.invoke())
    }
}
