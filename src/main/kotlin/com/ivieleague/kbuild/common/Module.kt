package com.ivieleague.kbuild.common

import java.io.File

interface Module {
    val projectIdentifier: ProjectIdentifier
        get() = ProjectIdentifier(
            group = this::class.java.name.substringBeforeLast('.'),
            name = this::class.java.name.substringAfterLast('.'),
            version = Version("0.0.1")
        )
    val root: File
}

