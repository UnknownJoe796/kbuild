package com.ivieleague.kbuild.common

import java.io.File

interface Distributable {
    fun distribution(): File
}