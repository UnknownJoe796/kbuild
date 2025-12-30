package com.example

actual class Platform actual constructor() {
    actual val name: String = "JVM (${System.getProperty("java.version")})"
}

fun main() {
    println(Greeting().greet())
}
