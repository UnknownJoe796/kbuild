package com.ivieleague.kbuild.common

import java.io.File

data class Library(
    val name: String,
    val default: File,
    val documentation: File? = null,
    val sources: File? = null
) {
    val fileSafeName: String get() = name.replace(':', '_').replace('.', '_')
}

val Producer<Library>.default: Producer<File> get() = { this().mapTo(HashSet()) { it.default } }
val Producer<Library>.documentation: Producer<File> get() = { this().mapNotNullTo(HashSet()) { it.documentation } }
val Producer<Library>.sources: Producer<File> get() = { this().mapNotNullTo(HashSet()) { it.sources } }