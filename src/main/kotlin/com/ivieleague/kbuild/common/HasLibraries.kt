package com.ivieleague.kbuild.common

interface HasLibraries {
    val libraries: List<Library> get() = listOf()
}