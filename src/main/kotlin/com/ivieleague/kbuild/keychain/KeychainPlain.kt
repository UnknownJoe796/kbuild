package com.ivieleague.kbuild.keychain

import java.io.File
import java.util.*
import java.util.concurrent.CancellationException

object KeychainPlain : KeychainInterface {
    var file: File = File(System.getProperty("user.home")).resolve("keychain.properties")

    private var inMemoryLastLoad: Long = System.currentTimeMillis()
    private var inMemoryExpiresTime: Long = 5000L
    private var inMemory: Properties? = null
        set(value) {
            field = value
            inMemoryLastLoad = System.currentTimeMillis()
        }
    val properties: Properties
        get() {
            if (System.currentTimeMillis() > inMemoryLastLoad + inMemoryExpiresTime) {
                inMemory = null
            }
            inMemory?.let { return it }
            if (!file.exists()) {
                val properties = Properties().apply {
                    setProperty("kbuild", "true")
                }
                inMemory = properties
                file.parentFile.mkdirs()
                file.outputStream().buffered().use {
                    properties.store(it, null)
                }
                return properties
            }
            val properties = Properties().apply {
                file.inputStream().buffered().use {
                    load(it)
                }
            }
            assert(properties.containsKey("kbuild"))
            inMemory = properties
            return properties
        }

    override operator fun get(key: String): String? = properties.getProperty(key)
    override fun getOrPrompt(key: String): String {
        get(key)?.let { return it }
        val value = requestSetPassword(key)
        set(key, value)
        return value
    }

    override operator fun set(key: String, value: String?) {
        val properties = properties
        properties.setProperty(key, value)
        file.outputStream().buffered().use {
            properties.store(it, null)
        }
    }

    private fun requestSetPassword(prompt: String): String {
        val scanner = Scanner(System.`in`)
        while (true) {
            println("Please enter your new password for '$prompt' or N to cancel")
            val first = scanner.nextLine()
            if (first.equals("n", true)) throw CancellationException()
            println("Again, to confirm.")
            val second = scanner.nextLine()
            if (first == second) {
                return first
            } else continue
        }
    }

    @JvmStatic
    fun main(vararg args: String) {
        println("--Keychain Access--")
        val scanner = Scanner(System.`in`)
        label@ while (true) {
            println("[S]et, [G]et, [P]rompt, [D]elete, [C]lear or [Q]uit?")
            when (scanner.nextLine()) {
                "S", "s" -> {
                    println("Enter the key to set:")
                    val key = scanner.nextLine()
                    println("Enter the value:")
                    val value = scanner.nextLine()
                    set(key, value)
                }
                "G", "g" -> {
                    println("Enter the key to get:")
                    val key = scanner.nextLine()
                    println("Value: ${get(key)}")
                }
                "P", "p" -> {
                    println("Enter the key get prompted for:")
                    val key = scanner.nextLine()
                    println("Value: ${getOrPrompt(key)}")
                }
                "D", "d" -> {
                    println("Enter the key to delete:")
                    val key = scanner.nextLine()
                    set(key, null)
                    println("Key deleted.")
                }
                "C", "c" -> {
                    println("Are you sure? [y/n]")
                    when (scanner.nextLine()) {
                        "y", "Y" -> file.delete()
                        else -> println("Cancelled clear.")
                    }
                }
                "Q", "q" -> {
                    break@label
                }
                else -> {

                }
            }
        }
    }
}