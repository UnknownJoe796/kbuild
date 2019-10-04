package com.ivieleague.kbuild.common

interface HasLibraries {
    val libraries: Set<Library> get() = setOf()
}