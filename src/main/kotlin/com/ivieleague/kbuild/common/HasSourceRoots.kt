package com.ivieleague.kbuild.common

import java.io.File

interface HasSourceRoots : Module {
    val sourceRoots: Set<File> get() = setOf(root.resolve("src"))
}