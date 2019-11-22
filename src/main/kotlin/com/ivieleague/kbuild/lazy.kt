package com.ivieleague.kbuild

import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

val __memoizationMap = ConcurrentHashMap<Any, Any?>()
@Suppress("UNCHECKED_CAST")
inline fun <R> Any.memoize(vararg factors: Any?, action: () -> R): R {
    val allFactors = listOf(this, *factors)
    return __memoizationMap.getOrPut(allFactors, action) as R
}

//inline fun <R> superMemoize(taskType: String, vararg factors: Any?, action: ()->R): R {
//    //check if hash -> value is stored
//    //if stored, return deserialized value
//    //if not stored, perform and store new value
//    //This might require establishing call uniqueness - how?  By storing call uniqueness, we don't need to store really old memoizations
//}
//
//fun Any?.memoizationHashCode(): Int = when(this){
//    is File -> when {
//        !this.exists() -> 0
//        this.isDirectory -> this.listFiles()?.sumBy { it.statusHash() } ?: 0
//        else -> this.path.hashCode() + this.lastModified().hashCode() + this.toString().hashCode() + this.length().hashCode()
//    }
//    is ()->Any? -> this().memoizationHashCode()
//    is Collection<*> -> this.sumBy { it.memoizationHashCode() }
//    else -> hashCode()
//}