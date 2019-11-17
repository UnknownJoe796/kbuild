package com.ivieleague.kbuild.common

typealias Producer<T> = () -> Set<T>

fun <T> producerOf(vararg items: T): Producer<T> = { setOf(*items) }
fun <T> merge(vararg items: Producer<T>): Producer<T> = { items.fold(setOf()) { a, b -> a + b() } }
fun <T> Collection<Producer<T>>.merge(): Producer<T> = { fold(setOf()) { a, b -> a + b() } }
operator fun <T> Producer<T>.plus(other: Producer<T>): Producer<T> = { this() + other() }
fun <T> (() -> T).asProducer(): Producer<T> = { setOf(this()) }

typealias Configurer<T> = T.() -> Unit

operator fun <T> Configurer<T>.plus(other: Configurer<T>): Configurer<T> = { this@plus(); other() }