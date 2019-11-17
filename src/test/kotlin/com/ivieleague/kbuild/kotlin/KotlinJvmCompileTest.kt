package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.default
import com.ivieleague.kbuild.grabStandardOut
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.maven.MavenAether
import org.junit.Test
import java.io.File

class KotlinJvmCompileTest {
    @Test
    fun build() {
        val root = File("build/run/KotlinJvmCompileTest")
        val task = KotlinJvmCompile(
            name = "KotlinJvmCompileTest",
            sourceRoots = {
                val src = root.resolve("src")
                src.mkdirs()
                src.resolve("main.kt").writeText(
                    """
                    package com.test
                    object AdditionalMaterials {
                        val message = "Hello World!"
                    }
                    fun main() = println(AdditionalMaterials.message)
                """.trimIndent()
                )
                setOf(src)
            },
            classpathJars = { MavenAether.libraries(Kotlin.standardLibraryJvmId) }.default,
            arguments = {},
            cache = root.resolve("cache"),
            outputFolder = root.resolve("out")
        )
        task()
        assert(grabStandardOut {
            JVM.runMain((task.classpathJars() + task.outputFolder).toList(), "com.test.MainKt", arrayOf())
        }.contains("Hello World!"))
    }
}