package com.ivieleague.kbuild.cli

import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod

/**
 * Information about a target (property or function) that can be invoked.
 */
data class TargetInfo(
    val name: String,
    val callable: KCallable<*>,
    val returnType: KType,
    val isReactive: Boolean,
    val isFunction: Boolean,
    val parameters: List<ParameterInfo>
) {
    data class ParameterInfo(val name: String?, val type: KType, val isOptional: Boolean)
}

/**
 * Result of evaluating an expression.
 */
sealed class EvaluationResult {
    /** A concrete value was produced */
    data class Value(val value: Any?) : EvaluationResult()

    /** The expression resolved to a callable that can be invoked */
    data class Callable(
        val callable: KCallable<*>,
        val receiver: Any?,
        val isReactive: Boolean,
        val args: List<Any?>
    ) : EvaluationResult() {

        /** Invoke the callable (for non-reactive functions) */
        fun invoke(): Any? {
            callable.isAccessible = true
            return when (callable) {
                is KFunction<*> -> {
                    val allArgs = buildList {
                        receiver?.let { add(it) }
                        addAll(args)
                    }
                    callable.call(*allArgs.toTypedArray())
                }
                is KProperty<*> -> {
                    if (receiver != null) {
                        (callable as KProperty1<Any, *>).get(receiver)
                    } else {
                        (callable as KProperty0<*>).get()
                    }
                }
                else -> throw IllegalStateException("Unknown callable type: $callable")
            }
        }
    }
}

/**
 * Evaluates expressions against objects using reflection.
 */
object ExpressionEvaluator {

    /**
     * Evaluate an expression starting from a root context.
     *
     * @param rootContext Map of name -> object for top-level identifiers
     * @param expression The parsed expression
     * @return The evaluation result (value or callable)
     */
    fun evaluate(rootContext: Map<String, Any>, expression: Expression): EvaluationResult {
        if (expression.steps.isEmpty()) {
            throw IllegalArgumentException("Expression has no steps")
        }

        val firstStep = expression.steps.first()
        val firstName = when (firstStep) {
            is ExpressionStep.Identifier -> firstStep.name
            is ExpressionStep.MethodCall -> firstStep.name
        }

        val rootObject = rootContext[firstName]
            ?: throw IllegalArgumentException("Unknown identifier: $firstName. Available: ${rootContext.keys}")

        // If there's only one step and it's a method call, we need to invoke it
        if (expression.steps.size == 1 && firstStep is ExpressionStep.MethodCall) {
            // This is calling a top-level object as a function - which would be invoke()
            return evaluateCallOnObject(rootObject, "invoke", firstStep.args)
        }

        // If there's only one step and it's an identifier, return the object
        if (expression.steps.size == 1) {
            return EvaluationResult.Value(rootObject)
        }

        // Navigate through the remaining steps
        return evaluateSteps(rootObject, expression.steps.drop(1))
    }

    /**
     * Evaluate an expression starting from a single root object.
     */
    fun evaluate(root: Any, expression: Expression): EvaluationResult {
        if (expression.steps.isEmpty()) {
            return EvaluationResult.Value(root)
        }

        // Check if first step matches the root object's class name
        val firstStep = expression.steps.first()
        val firstName = when (firstStep) {
            is ExpressionStep.Identifier -> firstStep.name
            is ExpressionStep.MethodCall -> firstStep.name
        }

        val rootClassName = root::class.simpleName
        val stepsToUse = if (firstName == rootClassName) {
            // Skip the first step since it's the class name
            if (expression.steps.size == 1 && firstStep is ExpressionStep.MethodCall) {
                return evaluateCallOnObject(root, "invoke", firstStep.args)
            }
            expression.steps.drop(1)
        } else {
            expression.steps
        }

        if (stepsToUse.isEmpty()) {
            return EvaluationResult.Value(root)
        }

        return evaluateSteps(root, stepsToUse)
    }

    private fun evaluateSteps(current: Any, steps: List<ExpressionStep>): EvaluationResult {
        var obj: Any = current

        for ((index, step) in steps.withIndex()) {
            val isLast = index == steps.lastIndex

            when (step) {
                is ExpressionStep.Identifier -> {
                    if (isLast) {
                        // Last step - return as callable or value
                        return evaluateMember(obj, step.name)
                    } else {
                        // Intermediate step - must resolve to a value
                        obj = resolveMember(obj, step.name)
                            ?: throw IllegalArgumentException("Cannot resolve '${step.name}' on ${obj::class.simpleName}")
                    }
                }
                is ExpressionStep.MethodCall -> {
                    if (isLast) {
                        // Last step - return callable with args
                        return evaluateCallOnObject(obj, step.name, step.args)
                    } else {
                        // Intermediate step - invoke and continue
                        val result = evaluateCallOnObject(obj, step.name, step.args)
                        obj = when (result) {
                            is EvaluationResult.Value -> result.value
                                ?: throw IllegalArgumentException("${step.name}() returned null, cannot continue")
                            is EvaluationResult.Callable -> result.invoke()
                                ?: throw IllegalArgumentException("${step.name}() returned null, cannot continue")
                        }
                    }
                }
            }
        }

        return EvaluationResult.Value(obj)
    }

    /**
     * Evaluate a member access, returning either a Callable or Value.
     */
    private fun evaluateMember(obj: Any, name: String): EvaluationResult {
        val kClass = obj::class

        // Try to find a property first
        val property = kClass.memberProperties.find { it.name == name }
        if (property != null) {
            property.isAccessible = true
            val isReactive = isReactive(property)

            // If it's reactive, return as callable so ExecutionEngine can provide context
            if (isReactive) {
                return EvaluationResult.Callable(property, obj, isReactive = true, args = emptyList())
            }

            // Otherwise get the value
            @Suppress("UNCHECKED_CAST")
            val value = (property as KProperty1<Any, *>).get(obj)
            return EvaluationResult.Value(value)
        }

        // Try to find a no-arg function or a function where all params have defaults
        val function = kClass.memberFunctions.find {
            it.name == name && getUserValueParameters(it).all { p -> p.isOptional }
        }
        if (function != null) {
            function.isAccessible = true
            val isReactive = isReactive(function)
            return EvaluationResult.Callable(function, obj, isReactive, args = emptyList())
        }

        // Try companion object
        val companion = kClass.companionObject
        if (companion != null) {
            val companionInstance = kClass.companionObjectInstance
            if (companionInstance != null) {
                val companionProperty = companion.memberProperties.find { it.name == name }
                if (companionProperty != null) {
                    companionProperty.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val value = (companionProperty as KProperty1<Any, *>).get(companionInstance)
                    return EvaluationResult.Value(value)
                }
            }
        }

        throw IllegalArgumentException("No member '$name' found on ${kClass.simpleName}")
    }

    /**
     * Evaluate a method call on an object.
     */
    private fun evaluateCallOnObject(obj: Any, name: String, args: List<Any?>): EvaluationResult {
        val kClass = obj::class

        // Find matching function
        val candidates = kClass.memberFunctions.filter { it.name == name }

        if (candidates.isEmpty()) {
            throw IllegalArgumentException("No function '$name' found on ${kClass.simpleName}")
        }

        // Find best match based on argument count (excluding context parameters)
        val function = candidates.find { fn ->
            getUserValueParameters(fn).size == args.size
        } ?: candidates.find { fn ->
            // Try to match with optional parameters
            val userParams = getUserValueParameters(fn)
            userParams.count { !it.isOptional } <= args.size &&
            userParams.size >= args.size
        } ?: throw IllegalArgumentException(
            "No matching overload for $name(${args.size} args) on ${kClass.simpleName}. " +
            "Available: ${candidates.map { "${it.name}(${getUserValueParameters(it).size} args)" }}"
        )

        function.isAccessible = true
        val isReactive = isReactive(function)
        return EvaluationResult.Callable(function, obj, isReactive, args)
    }

    /**
     * Resolve a member to get its value (for intermediate steps).
     */
    private fun resolveMember(obj: Any, name: String): Any? {
        val result = evaluateMember(obj, name)
        return when (result) {
            is EvaluationResult.Value -> result.value
            is EvaluationResult.Callable -> result.invoke()
        }
    }

    /**
     * Check if a callable is reactive (is a suspend function).
     */
    fun isReactive(callable: KCallable<*>): Boolean {
        // Check if the function is a suspend function
        if (callable is KFunction<*>) {
            return callable.isSuspend
        }
        return false
    }

    /**
     * Get the user-facing value parameters.
     */
    private fun getUserValueParameters(function: KFunction<*>): List<KParameter> {
        return function.valueParameters
    }

    /**
     * List all available targets on an object.
     */
    fun listTargets(obj: Any): List<TargetInfo> {
        val kClass = obj::class
        val targets = mutableListOf<TargetInfo>()

        // Add properties
        for (prop in kClass.memberProperties) {
            if (prop.visibility != KVisibility.PUBLIC) continue

            targets.add(TargetInfo(
                name = prop.name,
                callable = prop,
                returnType = prop.returnType,
                isReactive = isReactive(prop),
                isFunction = false,
                parameters = emptyList()
            ))
        }

        // Add functions (excluding standard Object methods)
        val excludedMethods = setOf("equals", "hashCode", "toString", "copy", "component1",
            "component2", "component3", "component4", "component5")

        for (fn in kClass.memberFunctions) {
            if (fn.visibility != KVisibility.PUBLIC) continue
            if (fn.name in excludedMethods) continue
            val userParams = getUserValueParameters(fn)
            if (fn.name.startsWith("get") && userParams.isEmpty()) continue // Skip getters

            val params = userParams.map { param ->
                TargetInfo.ParameterInfo(
                    name = param.name,
                    type = param.type,
                    isOptional = param.isOptional
                )
            }

            targets.add(TargetInfo(
                name = fn.name,
                callable = fn,
                returnType = fn.returnType,
                isReactive = isReactive(fn),
                isFunction = true,
                parameters = params
            ))
        }

        return targets.sortedWith(
            compareBy({ !it.isFunction }, { it.name })
        )
    }
}
