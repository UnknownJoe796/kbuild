package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.HasTestModule
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.JvmRunnable
import com.ivieleague.kbuild.jvm.KotlinJUnit4Module
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJVMModule
import org.junit.Test
import java.io.File

class KotlinJUnit4ModuleTest {
    @Test
    fun testVanilla() {
        // The script
        val km = main@ object : KotlinJVMModule, JvmRunnable, HasTestModule {
            override val mainClass: String get() = "com.test.MainKt"
            override val root: File get() = File("build/run/temp/$name")
            override val version: Version
                get() = Version(
                    0,
                    0,
                    1
                )
            override val libraries: Set<Library> get() = Kotlin.standardLibraryJvm

            val me = this
            override val test
                get() = object : KotlinJUnit4Module {
                    override val forModule get() = me
                }
        }

        // Testing the script
        km.root.deleteRecursively()

        km.root.resolve("src/main.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            val mainMessage = "Hello World!"
            fun main() = println(mainMessage)
        """.trimIndent()
        )
        km.root.resolve("test/MainTest.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            import org.junit.Test
            class MainTest(){
                @Test
                fun messageIsCorrect() {
                    assert(mainMessage == "Hello World!")
                }
                @Test
                fun logicWorks() {
                    assert(1 + 1 == 2)
                }
                @Test
                fun mainCausesNoExceptions() {
                    main()
                }
                @Test
                fun fails() {
                    throw Exception("My Message")
                }
            }
        """.trimIndent()
        )

        val standardOut = grabStandardOut {
            km.run()
        }
        println("Output: $standardOut")
        assert(standardOut.contains("Hello World!"))
        assert(km.classpathOutput.exists())

        println("Tests: ${km.test.tests}")
        km.test.test().forEach { key, value ->
            println(value)
        }
    }

}