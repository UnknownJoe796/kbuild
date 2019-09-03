package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.Library

interface HasJarLibraries {
    val jarLibraries: List<Library> get() = listOf()
}