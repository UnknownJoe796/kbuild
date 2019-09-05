package com.ivieleague.kbuild

import org.jasypt.util.text.AES256TextEncryptor
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.*
import java.util.concurrent.CancellationException

object Keychain {
    var file: File = File(System.getProperty("user.home")).resolve(".kbuild/keychain.encrypted.properties")

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
                val p = requestSetPassword("KBuild Keychain")
                this.passwordCached = p
                val properties = Properties().apply {
                    setProperty("kbuild", "true")
                }
                inMemory = properties
                file.parentFile.mkdirs()
                file.writeText(properties.storeString().encrypt(p))
                return properties
            }
            while (true) {
                val p = requestPassword()
                return try {
                    val properties = Properties().apply {
                        loadString(file.readText().decrypt(p))
                    }
                    assert(properties.containsKey("kbuild"))
                    inMemory = properties
                    properties
                } catch (e: Exception) {
                    println("That password was incorrect.")
                    passwordCached = null
                    continue
                }
            }
        }

    operator fun get(key: String): String? = properties.getProperty(key)
    fun getOrPrompt(key: String): String {
        get(key)?.let { return it }
        val value = requestSetPassword(key)
        set(key, value)
        return value
    }

    operator fun set(key: String, value: String?) {
        val properties = properties
        properties.setProperty(key, value)
        file.writeText(properties.storeString().encrypt(requestPassword()))
    }

    private var passwordCached: String? = null

    private fun requestPassword(): String {
        passwordCached?.let { return it }
        println("Please enter your KBuild keychain password or enter N to cancel.")
        val scanner = Scanner(System.`in`)
        val p = scanner.nextLine()
        if (p.equals("n", true)) throw CancellationException()
        passwordCached = p
        return p
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

fun String.encrypt(password: String): String {
    return AES256TextEncryptor().apply { setPassword(password) }.encrypt(this)
}

fun String.decrypt(password: String): String {
    return AES256TextEncryptor().apply { setPassword(password) }.decrypt(this)
}

fun Properties.storeString(): String {
    val stringWriter = StringWriter()
    this.store(stringWriter, null)
    return stringWriter.toString()
}

fun Properties.loadString(string: String) {
    val stringReader = StringReader(string)
    this.load(stringReader)
}