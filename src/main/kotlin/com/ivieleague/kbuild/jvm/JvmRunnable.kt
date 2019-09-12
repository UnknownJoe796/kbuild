package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Runnable

interface JvmRunnable : HasJvmClassPaths, Runnable {
    val mainClass: String
    override fun run(vararg arguments: String): Int {
        return JVM.runMain(this.jvmClassPaths, mainClass, arguments)
    }
}