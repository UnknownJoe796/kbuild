package com.ivieleague.kbuild.common

import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import com.lightningkite.reactive.core.ReactiveState

/**
 * Configuration lambda for builder-style APIs.
 */
typealias Configurer<T> = T.() -> Unit

operator fun <T> Configurer<T>.plus(other: Configurer<T>): Configurer<T> = { this@plus(); other() }

/**
 * A constant reactive that never changes.
 */
fun <T> constantReactive(value: T): Reactive<T> = object : Reactive<T> {
    override val state: ReactiveState<T> = ReactiveState(value)
    override fun addListener(listener: () -> Unit): () -> Unit = { }
}

/**
 * Creates a constant reactive set from the given items.
 */
fun <T> reactiveSetOf(vararg items: T): Reactive<Set<T>> = constantReactive(setOf(*items))

/**
 * Creates a constant reactive set from this set.
 */
fun <T> Set<T>.asReactive(): Reactive<Set<T>> = constantReactive(this)

/**
 * Merges multiple reactive sets into one.
 */
suspend fun <T> merge(vararg items: Reactive<Set<T>>): Set<T> = items.fold(setOf()) { a, b -> a + b() }

/**
 * Merges this collection of reactive sets into one.
 */
suspend fun <T> Collection<Reactive<Set<T>>>.merge(): Set<T> = fold(setOf()) { a, b -> a + b() }

/**
 * Combines two reactive sets.
 */
suspend operator fun <T> Reactive<Set<T>>.plus(other: Reactive<Set<T>>): Set<T> = this() + other()
