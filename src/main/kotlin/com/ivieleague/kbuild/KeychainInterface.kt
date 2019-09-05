package com.ivieleague.kbuild

interface KeychainInterface {
    operator fun get(key: String): String?
    fun getOrPrompt(key: String): String
    operator fun set(key: String, value: String?)
}

