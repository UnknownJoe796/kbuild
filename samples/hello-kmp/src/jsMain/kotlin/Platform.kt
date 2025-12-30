package com.example

actual class Platform actual constructor() {
    actual val name: String = "JavaScript"
}

fun main() {
    println(Greeting().greet())
}
