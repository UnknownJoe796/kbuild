package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.JvmRunnable
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJVMModule
import org.junit.Test
import java.io.File

class KotlinJVMModuleTest {
    @Test
    fun testVanilla() {
        // The script
        val km = object : KotlinJVMModule, JvmRunnable {
            override val mainClass: String get() = "com.test.MainKt"
            override val root: File get() = File("build/run/temp/$name")
            override val version: Version
                get() = Version(
                    0,
                    0,
                    1
                )
            override val libraries: Set<Library> get() = Kotlin.standardLibraryJvm
        }

        // Testing the script
        km.root.deleteRecursively()

        km.sourceRoots.first().resolve("Main.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            object AdditionalMaterials {
                val message = "Hello World!"
            }
            fun main() = println(AdditionalMaterials.message)
        """.trimIndent()
        )

        val standardOut = grabStandardOut {
            km.run()
        }
        println("Output: $standardOut")
        assert(standardOut.contains("Hello World!"))
    }

}