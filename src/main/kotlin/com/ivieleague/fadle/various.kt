package com.ivieleague.fadle

import java.io.File

interface Runnable<Result> {
    fun run(vararg args: String): Result
}

interface Buildable {
    fun build(): File
}

interface HasTestModule {
    val test: Runnable<Boolean>
}

interface HasPrepare {
    fun prepare(): Unit
}

interface Distributable {
    fun distribution(): File
}