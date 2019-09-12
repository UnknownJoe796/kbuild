package com.ivieleague.kbuild.common

import java.io.File

interface Project {
    val root: File
    val modules: List<Module>
}