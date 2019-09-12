package com.ivieleague.kbuild.common

import java.io.File

interface Buildable {
    fun build(): File
}