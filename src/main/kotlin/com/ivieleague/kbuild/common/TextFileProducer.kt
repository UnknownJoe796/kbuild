package com.ivieleague.kbuild.common

import java.io.File

class TextFileProducer(val path: File, val contents: String) : () -> File {
    override fun invoke(): File {
        return path.also { it.parentFile.mkdirs(); it.writeText(contents) }
    }
}