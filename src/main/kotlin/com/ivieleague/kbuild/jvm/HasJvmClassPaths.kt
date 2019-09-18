package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Module
import java.io.File

interface HasJvmClassPaths : Module {
    val jvmClassPaths: List<File>
}

