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
        val result = field.get(this)
        return if (result is Function0<*>)
            result()
        else
            result
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

fun Any.asMain(vararg args: String) = args.forEach { executeComplex(it) }
fun Any.asMainOptions(): List<String> =
    this::class.memberFunctions.map { it.name } + this::class.memberProperties.map { it.name }