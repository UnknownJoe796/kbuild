package com.ivieleague.kbuild

import java.io.File

interface Runnable {
    fun run(vararg args: String): Int
}

interface Buildable {
    fun build(): File
}

interface HasTestModule {
    val test: Runnable
}

interface HasPrepare {
    fun prepare(): Unit
}

interface Distributable {
    fun distribution(): File
}