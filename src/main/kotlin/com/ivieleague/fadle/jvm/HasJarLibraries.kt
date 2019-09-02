package com.ivieleague.fadle.jvm

import com.ivieleague.fadle.Library

interface HasJarLibraries {
    val jvmJarLibraries: List<Library> get() = listOf()
}