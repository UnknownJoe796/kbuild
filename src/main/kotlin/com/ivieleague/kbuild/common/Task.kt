package com.ivieleague.kbuild.common

import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

private fun Any.execute(string: String): Any? {
    val func =
        this::class.memberFunctions.find { it.name == string && it.parameters.size == 1 && it.parameters.first().kind == KParameter.Kind.INSTANCE }
    if (func != null) {
        return func.call(this)
    }
    val field = (this::class.memberProperties.find { it.name == string } as? KProperty1<Any?, Any?>)
    if (field != null) {
        return field.get(this)
    }

    return "Could not find anything to execute named '$string'."
}

private fun Any.executeComplex(string: String): Any? {
    val parts = string.split('.')
    var current: Any? = this
    for (part in parts) {
        current = current?.execute(part)
    }
    return current
}

fun Project.asMain(vararg args: String) = args.forEach { executeComplex(it) }

fun main(vararg args: String) {
    val test = object {
        val x: Int = 32
        fun hello() = println("Hello World!")
    }
    println(test.execute("x"))
    println(test.execute("hello"))
}