package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJSModule
import org.junit.Test
import java.io.File

class KotlinJsModuleTest {
    @Test
    fun testVanilla() {
        // The script
        val km = object : KotlinJSModule {
            override val root: File get() = File("build/run/temp/$name")
            override val version: Version
                get() = Version(
                    0,
                    0,
                    1
                )
            override val libraries: Set<Library> get() = Kotlin.standardLibraryJs
        }

        // Testing the script
        km.root.deleteRecursively()

        km.sourceRoots.first().resolve("test.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            fun main() = println("Hello World!")
        """.trimIndent()
        )

        km.build()
        assert(km.kotlinJarOutput.exists())
    }
}