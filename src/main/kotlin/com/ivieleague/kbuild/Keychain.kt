package com.ivieleague.kbuild

object Keychain {
    operator fun get(service: String): List<String>? = listOf()
    operator fun set(service: String, things: List<String>) {}
}