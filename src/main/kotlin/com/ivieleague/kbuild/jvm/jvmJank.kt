package com.ivieleague.kbuild.jvm

import java.lang.reflect.Method


fun Class<*>.getDeclaredMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? = try {
    getDeclaredMethod(name, *parameterTypes)
} catch (e: NoSuchMethodException) {
    null
}

data class JankUntypedWrapper(val value: Any) {
    operator fun get(key: String): JankUntypedWrapper? {
        return value::class.java.getDeclaredMethodOrNull("get${key.capitalize()}")?.invoke(value)
            ?.let { JankUntypedWrapper(it) }
    }

    fun asList(): List<JankUntypedWrapper?> = (value as List<*>).map {
        it?.let {
            JankUntypedWrapper(
                it
            )
        }
    }

    fun listMethods(): List<String> = value::class.java.methods.map { it.name }
}

fun Any.untyped(): JankUntypedWrapper = JankUntypedWrapper(this)