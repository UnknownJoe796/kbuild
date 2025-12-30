package samples.hellojs

/**
 * A simple Kotlin/JS example.
 */

fun greet(name: String): String {
    return "Hello, $name from Kotlin/JS!"
}

fun fibonacci(n: Int): Long {
    if (n <= 1) return n.toLong()
    var a = 0L
    var b = 1L
    repeat(n - 1) {
        val temp = a + b
        a = b
        b = temp
    }
    return b
}

fun main() {
    println(greet("World"))
    println("Fibonacci(10) = ${fibonacci(10)}")
    println("Fibonacci(20) = ${fibonacci(20)}")
}
