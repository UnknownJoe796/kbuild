package com.ivieleague.kbuild.common

import java.io.File

interface HasSourceRoots : Module {
    val sourceRoots: List<File> get() = listOf(root.resolve("src"))
}