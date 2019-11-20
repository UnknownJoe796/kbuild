package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.common.default
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.maven.MavenAether
import org.junit.Test
import java.io.File

class IntelliJTest {
    @Test
    fun build() {
        val obj = object {
            val root = File("build/run/IntelliJTest")

            init {
                root.deleteRecursively()
                root.mkdirs()
            }

            val libraries = { MavenAether.libraries(Kotlin.standardLibraryJvmId) }
            val build = KotlinJvmCompile(
                name = "IntelliJTest",
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
                classpathJars = libraries.default,
                arguments = {},
                cache = root.resolve("cache"),
                outputFolder = root.resolve("out")
            )
            val task = IntelliJProjectBuild(
                root = root,
                modules = setOf(
                    IntelliJModuleBuild(
                        projectRoot = root,
                        root = root,
                        name = "Main",
                        sourceRoots = build.sourceRoots,
                        libraries = libraries,
                        isTestModule = false
                    )
                )
            )
        }
        obj.build()
        obj.task()
//        IntelliJ.launch(obj.root)
    }
}