package com.ivieleague.kbuild.jvm

interface JvmRunnable : HasJvmJars {
    val mainClass: String
    fun run(vararg arguments: String) {
        JVM.runMain(this.jvmJars, mainClass, arguments)
    }
}