package com.ivieleague.fadle

import java.io.File

interface Module {
    val group: String get() = this::class.java.name.substringBeforeLast('.')
    val name: String get() = this::class.java.name.substringAfterLast('.')
    val version: Version
    val root: File
}