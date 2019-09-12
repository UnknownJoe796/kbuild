package com.ivieleague.kbuild.common

import java.io.File

data class Library(
    val name: String,
    val default: File,
    val javadoc: File? = null,
    val sources: File? = null
) {
    val fileSafeName: String get() = name.replace(':', '_').replace('.', '_')
}