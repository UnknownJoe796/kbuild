package com.ivieleague.fadle.kotlin

import com.ivieleague.fadle.Module
import java.io.File

interface HasKotlinSourceRoots : Module {
    val kotlinSourceRoots: List<File>
        get() = listOf(root.resolve("src"))
}

//fun test(mavenModel: Model) {
//    mavenModel.addDependency(Dependency().)
//}

