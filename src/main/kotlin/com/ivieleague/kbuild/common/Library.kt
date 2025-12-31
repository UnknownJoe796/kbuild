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

val (() -> Set<Library>).default: () -> Set<File> get() = { this().mapTo(HashSet()) { it.default } }
val (() -> Set<Library>).documentation: () -> Set<File> get() = { this().mapNotNullTo(HashSet()) { it.documentation } }
val (() -> Set<Library>).sources: () -> Set<File> get() = { this().mapNotNullTo(HashSet()) { it.sources } }