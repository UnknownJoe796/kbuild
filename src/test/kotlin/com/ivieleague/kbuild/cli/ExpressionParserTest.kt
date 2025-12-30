package com.ivieleague.kbuild.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionParserTest {

    // ==================== Basic Parsing ====================

    @Test
    fun `parse single identifier`() {
        val expr = ExpressionParser.parse("Build")
        assertEquals(1, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
    }

    @Test
    fun `parse chained identifiers`() {
        val expr = ExpressionParser.parse("Build.compile")
        assertEquals(2, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.Identifier("compile"), expr.steps[1])
    }

    @Test
    fun `parse deeply chained identifiers`() {
        val expr = ExpressionParser.parse("Build.project.module.sources")
        assertEquals(4, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.Identifier("project"), expr.steps[1])
        assertEquals(ExpressionStep.Identifier("module"), expr.steps[2])
        assertEquals(ExpressionStep.Identifier("sources"), expr.steps[3])
    }

    // ==================== Method Calls ====================

    @Test
    fun `parse method call with no args`() {
        val expr = ExpressionParser.parse("Build.compile()")
        assertEquals(2, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.MethodCall("compile", emptyList()), expr.steps[1])
    }

    @Test
    fun `parse method call with string arg`() {
        val expr = ExpressionParser.parse("""Build.test(".*Foo")""")
        assertEquals(2, expr.steps.size)
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("test", methodCall.name)
        assertEquals(1, methodCall.args.size)
        assertEquals(".*Foo", methodCall.args[0])
    }

    @Test
    fun `parse method call with single quoted string`() {
        val expr = ExpressionParser.parse("Build.test('pattern')")
        assertEquals(2, expr.steps.size)
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("test", methodCall.name)
        assertEquals("pattern", methodCall.args[0])
    }

    @Test
    fun `parse method call with integer arg`() {
        val expr = ExpressionParser.parse("Build.run(42)")
        assertEquals(2, expr.steps.size)
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("run", methodCall.name)
        assertEquals(42L, methodCall.args[0])
    }

    @Test
    fun `parse method call with double arg`() {
        val expr = ExpressionParser.parse("Build.config(3.14)")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(3.14, methodCall.args[0])
    }

    @Test
    fun `parse method call with boolean args`() {
        val expr = ExpressionParser.parse("Build.config(true, false)")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(2, methodCall.args.size)
        assertEquals(true, methodCall.args[0])
        assertEquals(false, methodCall.args[1])
    }

    @Test
    fun `parse method call with null arg`() {
        val expr = ExpressionParser.parse("Build.config(null)")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(null, methodCall.args[0])
    }

    @Test
    fun `parse method call with multiple args`() {
        val expr = ExpressionParser.parse("""Build.config("name", 42, true)""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(3, methodCall.args.size)
        assertEquals("name", methodCall.args[0])
        assertEquals(42L, methodCall.args[1])
        assertEquals(true, methodCall.args[2])
    }

    // ==================== Mixed Chains ====================

    @Test
    fun `parse identifier then method call`() {
        val expr = ExpressionParser.parse("Build.project.compile()")
        assertEquals(3, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.Identifier("project"), expr.steps[1])
        assertEquals(ExpressionStep.MethodCall("compile", emptyList()), expr.steps[2])
    }

    @Test
    fun `parse method call then identifier`() {
        val expr = ExpressionParser.parse("Build.getProject().sources")
        assertEquals(3, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.MethodCall("getProject", emptyList()), expr.steps[1])
        assertEquals(ExpressionStep.Identifier("sources"), expr.steps[2])
    }

    @Test
    fun `parse chained method calls`() {
        val expr = ExpressionParser.parse("Build.configure().build()")
        assertEquals(3, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.MethodCall("configure", emptyList()), expr.steps[1])
        assertEquals(ExpressionStep.MethodCall("build", emptyList()), expr.steps[2])
    }

    // ==================== String Handling ====================

    @Test
    fun `parse string with escaped quotes`() {
        val expr = ExpressionParser.parse("""Build.test("hello \"world\"")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("hello \"world\"", methodCall.args[0])
    }

    @Test
    fun `parse string with escaped newline`() {
        val expr = ExpressionParser.parse("""Build.test("line1\nline2")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("line1\nline2", methodCall.args[0])
    }

    @Test
    fun `parse string with escaped tab`() {
        val expr = ExpressionParser.parse("""Build.test("col1\tcol2")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("col1\tcol2", methodCall.args[0])
    }

    @Test
    fun `parse string with dot inside`() {
        val expr = ExpressionParser.parse("""Build.test("com.example.Test")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("com.example.Test", methodCall.args[0])
    }

    @Test
    fun `parse string with comma inside`() {
        val expr = ExpressionParser.parse("""Build.test("a, b, c")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(1, methodCall.args.size)
        assertEquals("a, b, c", methodCall.args[0])
    }

    @Test
    fun `parse string with parentheses inside`() {
        val expr = ExpressionParser.parse("""Build.test("foo(bar)")""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals("foo(bar)", methodCall.args[0])
    }

    // ==================== Whitespace Handling ====================

    @Test
    fun `parse with leading and trailing whitespace`() {
        val expr = ExpressionParser.parse("  Build.compile  ")
        assertEquals(2, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("Build"), expr.steps[0])
        assertEquals(ExpressionStep.Identifier("compile"), expr.steps[1])
    }

    @Test
    fun `parse method call with spaces around parens`() {
        val expr = ExpressionParser.parse("Build.compile ()")
        assertEquals(2, expr.steps.size)
        assertEquals(ExpressionStep.MethodCall("compile", emptyList()), expr.steps[1])
    }

    @Test
    fun `parse method args with spaces`() {
        val expr = ExpressionParser.parse("""Build.config( "a" , 42 , true )""")
        val methodCall = expr.steps[1] as ExpressionStep.MethodCall
        assertEquals(3, methodCall.args.size)
        assertEquals("a", methodCall.args[0])
        assertEquals(42L, methodCall.args[1])
        assertEquals(true, methodCall.args[2])
    }

    // ==================== Identifier Names ====================

    @Test
    fun `parse identifier with underscore`() {
        val expr = ExpressionParser.parse("Build._private.my_method")
        assertEquals(3, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("_private"), expr.steps[1])
        assertEquals(ExpressionStep.Identifier("my_method"), expr.steps[2])
    }

    @Test
    fun `parse identifier with numbers`() {
        val expr = ExpressionParser.parse("Build.module2.version3")
        assertEquals(3, expr.steps.size)
        assertEquals(ExpressionStep.Identifier("module2"), expr.steps[1])
        assertEquals(ExpressionStep.Identifier("version3"), expr.steps[2])
    }

    // ==================== Error Cases ====================

    @Test
    fun `parse empty string throws`() {
        assertFailsWith<IllegalArgumentException> {
            ExpressionParser.parse("")
        }
    }

    @Test
    fun `parse whitespace only throws`() {
        assertFailsWith<IllegalArgumentException> {
            ExpressionParser.parse("   ")
        }
    }

    @Test
    fun `parse unclosed parenthesis throws`() {
        assertFailsWith<IllegalArgumentException> {
            ExpressionParser.parse("Build.method(")
        }
    }

    @Test
    fun `parse invalid identifier throws`() {
        assertFailsWith<IllegalArgumentException> {
            ExpressionParser.parse("Build.123invalid")
        }
    }

    // ==================== toString ====================

    @Test
    fun `expression toString for identifiers`() {
        val expr = ExpressionParser.parse("Build.compile")
        assertEquals("Build.compile", expr.toString())
    }

    @Test
    fun `expression toString for method call`() {
        val expr = ExpressionParser.parse("Build.test()")
        assertEquals("Build.test()", expr.toString())
    }

    @Test
    fun `expression toString for method with args`() {
        val expr = ExpressionParser.parse("""Build.test("pattern", 42)""")
        assertEquals("""Build.test("pattern", 42)""", expr.toString())
    }

    @Test
    fun `expression toString for method with null arg`() {
        val expr = ExpressionParser.parse("Build.test(null)")
        assertEquals("Build.test(null)", expr.toString())
    }

    @Test
    fun `expression toString for method with boolean args`() {
        val expr = ExpressionParser.parse("Build.config(true, false)")
        assertEquals("Build.config(true, false)", expr.toString())
    }
}
