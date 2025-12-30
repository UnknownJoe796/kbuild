package com.ivieleague.kbuild.common

import com.lightningkite.reactive.context.ReactiveContext
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
context(ctx: ReactiveContext)
fun <T> merge(vararg items: Reactive<Set<T>>): Set<T> = items.fold(setOf()) { a, b -> a + b() }

/**
 * Merges this collection of reactive sets into one.
 */
context(ctx: ReactiveContext)
fun <T> Collection<Reactive<Set<T>>>.merge(): Set<T> = fold(setOf()) { a, b -> a + b() }

/**
 * Combines two reactive sets.
 */
context(ctx: ReactiveContext)
operator fun <T> Reactive<Set<T>>.plus(other: Reactive<Set<T>>): Set<T> = this() + other()

// Legacy Producer support for gradual migration
@Deprecated("Use Reactive<Set<T>> instead", ReplaceWith("Reactive<Set<T>>"))
typealias Producer<T> = () -> Set<T>

@Deprecated("Use reactiveSetOf instead", ReplaceWith("reactiveSetOf(*items)"))
fun <T> producerOf(vararg items: T): () -> Set<T> = { setOf(*items) }

@Deprecated("Use Set<T>.asReactive() instead")
fun <T> (() -> T).asProducer(): () -> Set<T> = { setOf(this()) }

// Legacy operators for Producer types
@Deprecated("Use Reactive<Set<T>> with context receivers instead")
@Suppress("DEPRECATION")
operator fun <T> Producer<T>.plus(other: Producer<T>): Producer<T> = { this() + other() }

@Deprecated("Use merge() with Reactive instead")
@Suppress("DEPRECATION")
fun <T> legacyMerge(vararg items: Producer<T>): Producer<T> = { items.fold(setOf()) { a, b -> a + b() } }

@Deprecated("Use Collection<Reactive>.merge() instead")
@Suppress("DEPRECATION")
fun <T> Collection<Producer<T>>.legacyMerge(): Producer<T> = { fold(setOf()) { a, b -> a + b() } }
