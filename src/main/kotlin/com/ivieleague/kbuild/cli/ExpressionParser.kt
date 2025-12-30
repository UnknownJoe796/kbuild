package com.ivieleague.kbuild.cli

/**
 * Represents a single step in an expression path.
 *
 * Examples:
 * - `Build` -> Identifier("Build")
 * - `compile` -> Identifier("compile") or MethodCall("compile", emptyList())
 * - `test(".*Foo")` -> MethodCall("test", listOf(".*Foo"))
 */
sealed class ExpressionStep {
    /** Simple identifier - could be property access or no-arg method */
    data class Identifier(val name: String) : ExpressionStep()

    /** Explicit method call with arguments */
    data class MethodCall(val name: String, val args: List<Any?>) : ExpressionStep()
}

/**
 * A parsed expression representing a path through objects.
 *
 * Example: `Build.project.compile()` becomes:
 * Expression(listOf(Identifier("Build"), Identifier("project"), MethodCall("compile", emptyList())))
 */
data class Expression(val steps: List<ExpressionStep>) {
    override fun toString(): String = steps.joinToString(".") { step ->
        when (step) {
            is ExpressionStep.Identifier -> step.name
            is ExpressionStep.MethodCall -> "${step.name}(${step.args.joinToString(", ") { formatArg(it) }})"
        }
    }

    private fun formatArg(arg: Any?): String = when (arg) {
        null -> "null"
        is String -> "\"$arg\""
        is Number -> arg.toString()
        is Boolean -> arg.toString()
        else -> arg.toString()
    }
}

/**
 * Parses Kotlin-like dot-notation expressions.
 *
 * Supported syntax:
 * - `Build` - single identifier
 * - `Build.compile` - chained identifiers
 * - `Build.compile()` - method call with no args
 * - `Build.test("pattern")` - method call with string arg
 * - `Build.config(42, true)` - method call with multiple args
 */
object ExpressionParser {

    private val IDENTIFIER_REGEX = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")
    private val METHOD_CALL_REGEX = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*\((.*)\)""")

    /**
     * Parse an expression string into an Expression.
     *
     * @throws IllegalArgumentException if the expression is invalid
     */
    fun parse(input: String): Expression {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Expression cannot be empty")
        }

        val steps = mutableListOf<ExpressionStep>()
        var remaining = trimmed

        while (remaining.isNotEmpty()) {
            // Skip leading dot if present (except for first step)
            if (remaining.startsWith(".")) {
                remaining = remaining.substring(1)
            }

            // Find the next step (up to next dot or end)
            val (step, rest) = parseNextStep(remaining)
            steps.add(step)
            remaining = rest
        }

        return Expression(steps)
    }

    private fun parseNextStep(input: String): Pair<ExpressionStep, String> {
        // Check for method call: name(args)
        val methodMatch = findMethodCall(input)
        if (methodMatch != null) {
            val (name, args, remaining) = methodMatch
            return ExpressionStep.MethodCall(name, args) to remaining
        }

        // Otherwise, it's an identifier - find where it ends
        val dotIndex = findNextDot(input)
        val identifier = if (dotIndex == -1) input else input.substring(0, dotIndex)
        val remaining = if (dotIndex == -1) "" else input.substring(dotIndex)

        if (!IDENTIFIER_REGEX.matches(identifier)) {
            throw IllegalArgumentException("Invalid identifier: $identifier")
        }

        return ExpressionStep.Identifier(identifier) to remaining
    }

    /**
     * Find a method call at the start of input.
     * Returns (name, args, remaining) or null if not a method call.
     */
    private fun findMethodCall(input: String): Triple<String, List<Any?>, String>? {
        // Find identifier
        val nameMatch = IDENTIFIER_REGEX.matchAt(input, 0) ?: return null
        val name = nameMatch.value
        val afterName = input.substring(name.length).trimStart()

        // Must have opening paren
        if (!afterName.startsWith("(")) return null

        // Find matching closing paren
        val closeIndex = findMatchingParen(afterName)
        if (closeIndex == -1) {
            throw IllegalArgumentException("Unclosed parenthesis in: $input")
        }

        val argsString = afterName.substring(1, closeIndex)
        val args = parseArgs(argsString)
        val remaining = afterName.substring(closeIndex + 1)

        return Triple(name, args, remaining)
    }

    /**
     * Find the index of the closing paren that matches the opening paren at index 0.
     */
    private fun findMatchingParen(input: String): Int {
        if (!input.startsWith("(")) return -1

        var depth = 0
        var inString = false
        var stringChar = ' '
        var i = 0

        while (i < input.length) {
            val c = input[i]

            if (inString) {
                if (c == stringChar && (i == 0 || input[i - 1] != '\\')) {
                    inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }

        return -1
    }

    /**
     * Find the next dot that's not inside parentheses or strings.
     */
    private fun findNextDot(input: String): Int {
        var depth = 0
        var inString = false
        var stringChar = ' '

        for (i in input.indices) {
            val c = input[i]

            if (inString) {
                if (c == stringChar && (i == 0 || input[i - 1] != '\\')) {
                    inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '(' -> depth++
                    ')' -> depth--
                    '.' -> if (depth == 0) return i
                }
            }
        }

        return -1
    }

    /**
     * Parse comma-separated arguments.
     */
    private fun parseArgs(argsString: String): List<Any?> {
        val trimmed = argsString.trim()
        if (trimmed.isEmpty()) return emptyList()

        val args = mutableListOf<Any?>()
        var current = StringBuilder()
        var depth = 0
        var inString = false
        var stringChar = ' '

        for (i in trimmed.indices) {
            val c = trimmed[i]

            if (inString) {
                current.append(c)
                if (c == stringChar && (i == 0 || trimmed[i - 1] != '\\')) {
                    inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                        current.append(c)
                    }
                    '(' -> {
                        depth++
                        current.append(c)
                    }
                    ')' -> {
                        depth--
                        current.append(c)
                    }
                    ',' -> {
                        if (depth == 0) {
                            args.add(parseValue(current.toString().trim()))
                            current = StringBuilder()
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
            }
        }

        // Don't forget the last argument
        val lastArg = current.toString().trim()
        if (lastArg.isNotEmpty()) {
            args.add(parseValue(lastArg))
        }

        return args
    }

    /**
     * Parse a single value (string, number, boolean, null).
     */
    private fun parseValue(value: String): Any? {
        return when {
            value == "null" -> null
            value == "true" -> true
            value == "false" -> false
            value.startsWith("\"") && value.endsWith("\"") -> {
                value.substring(1, value.length - 1).unescape()
            }
            value.startsWith("'") && value.endsWith("'") -> {
                value.substring(1, value.length - 1).unescape()
            }
            value.contains(".") -> value.toDoubleOrNull() ?: value
            else -> value.toLongOrNull() ?: value
        }
    }

    private fun String.unescape(): String {
        return this
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
    }
}
