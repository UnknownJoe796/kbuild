package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.kotlin.KotlinModule
import com.ivieleague.kbuild.kotlin.automaticSemanticVersioning
import org.junit.Test
import java.io.File

class KotlinPublicAPITest {

    @Test
    fun myVersion() {
        val myModule = object : KotlinModule {
            override val version: Version
                get() = automaticSemanticVersioning()
            override val root: File
                get() = File(".")
            override val sourceRoots: Set<File>
                get() = setOf(File("src/main/kotlin"))
        }
        println("Version: ${myModule.version}")
    }
}