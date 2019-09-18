package com.ivieleague.kbuild

import java.util.*

val __lazyMap = WeakHashMap<Any, HashMap<String, Any?>>()

@Suppress("UNCHECKED_CAST")
inline fun <Owner, Value> Owner.globalLazy(key: String, calculate: Owner.() -> Value): Value =
    __lazyMap
        .getOrPut(this) { HashMap() }
        .getOrPut(key) { calculate() } as Value

@Suppress("UNCHECKED_CAST")
inline fun <Owner, Value> Owner.globalLazy(
    key: String,
    recalculateIf: Owner.() -> Boolean,
    calculate: Owner.() -> Value
): Value =
    __lazyMap
        .getOrPut(this) { HashMap() }
        .let { map ->
            if (recalculateIf()) {
                val value = calculate()
                map[key] = value
                value
            } else map.getOrPut(key) { calculate() } as Value
        }

@Suppress("UNCHECKED_CAST")
inline fun <Owner, Value> Owner.globalLazy(key: String, vararg factors: Any?, calculate: Owner.() -> Value): Value =
    globalLazy(
        key = key,
        recalculateIf = {
            val subKey = key + "__factors"
            val myMap = __lazyMap.getOrPut(this) { HashMap() }
            val existing = myMap[subKey] as Array<out Any?>
            val same = existing.contentEquals(factors)
            myMap[subKey] = factors
            !same
        },
        calculate = calculate
    )