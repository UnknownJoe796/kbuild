package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.kotlin.KotlinJVMModule
import java.io.File

interface KotlinJUnit4Module : JUnit4Module, KotlinJVMModule {
    override val forModule: KotlinJVMModule
    override val sourceRoots: Set<File>
        get() = setOf(root.resolve("test")) + forModule.sourceRoots
}