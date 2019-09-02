package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.Library

interface HasJarLibraries {
    val jvmJarLibraries: List<Library> get() = listOf()
}