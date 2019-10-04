package com.ivieleague.kbuild

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

object NullOutputStream : OutputStream() {
    override fun write(b: Int) {
    }

    override fun write(b: ByteArray) {
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
    }

    override fun flush() {
    }

    override fun close() {
    }
}

inline fun grabStandardOut(action: () -> Unit): String {
    val realOut = System.out
    val bytes = ByteArrayOutputStream()
    try {
        System.setOut(PrintStream(bytes))
        action()
    } finally {
        System.setOut(realOut)
    }
    return bytes.toString(Charset.defaultCharset())
}

inline fun grabStandardError(action: () -> Unit): String {
    val realErr = System.err
    val bytes = ByteArrayOutputStream()
    try {
        System.setErr(PrintStream(bytes))
        action()
    } finally {
        System.setErr(realErr)
    }
    return bytes.toString(Charset.defaultCharset())
}

inline fun <Result> redirectStandardOut(into: PrintStream, action: () -> Result): Result {
    val realOut = System.out
    val result: Result = try {
        System.setOut(into)
        action()
    } finally {
        System.setOut(realOut)
    }
    return result
}

inline fun <Result> redirectStandardError(into: PrintStream, action: () -> Result): Result {
    val realErr = System.err
    val result: Result = try {
        System.setErr(into)
        action()
    } finally {
        System.setErr(realErr)
    }
    return result
}

inline fun <T> suppressStandardOutputAndError(action: () -> T): T {
    val realErr = System.err
    val realOut = System.out
    val result = try {
        System.setOut(PrintStream(NullOutputStream))
        System.setErr(PrintStream(NullOutputStream))
        action()
    } finally {
        System.setErr(realErr)
        System.setOut(realOut)
    }
    return result
}
