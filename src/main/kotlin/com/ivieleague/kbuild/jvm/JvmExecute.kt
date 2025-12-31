package com.ivieleague.kbuild.jvm

import java.io.File

data class JvmExecute(
    val mainClass: String,
    val classpath: () -> Set<File>
) : () -> Any?, (Array<String>) -> Any? {
    override operator fun invoke(): Any? {
        return JVM.runMain(classpath().toList(), mainClass, arrayOf<String>())
    }

    override operator fun invoke(arguments: Array<String>): Any? {
        return JVM.runMain(classpath().toList(), mainClass, arguments)
    }
}