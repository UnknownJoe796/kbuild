package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Module
import java.io.File

interface HasKotlinSourceRoots : Module {
    val kotlinSourceRoots: List<File>
        get() = listOf(root.resolve("src"))
}

//fun test(mavenModel: Model) {
//    mavenModel.addDependency(Dependency().)
//}

