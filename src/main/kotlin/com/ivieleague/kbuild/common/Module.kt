package com.ivieleague.kbuild.common

import java.io.File

interface Module {
    val group: String get() = this::class.java.name.substringBeforeLast('.')
    val name: String get() = this::class.java.name.substringAfterLast('.')
    val version: Version
    val root: File
    val isTestModule: Boolean get() = false
}