package com.ivieleague.kbuild.js

import com.ivieleague.kbuild.common.Module
import java.io.File

interface WebModule : Module {
    val resourceRoots: Set<File> get() = setOf(root.resolve("resources"))
}