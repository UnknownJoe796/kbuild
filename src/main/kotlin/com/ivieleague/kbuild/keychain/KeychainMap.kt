package com.ivieleague.kbuild.keychain

import java.util.concurrent.CancellationException

class KeychainMap : MutableMap<String, String?> by HashMap(), KeychainInterface {
    override fun set(key: String, value: String?) {
        this[key] = value
    }

    override fun getOrPrompt(key: String): String {
        return this[key] ?: throw CancellationException()
    }
}