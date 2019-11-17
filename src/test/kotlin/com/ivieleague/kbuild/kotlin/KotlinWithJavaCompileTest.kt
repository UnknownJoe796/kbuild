package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.default
import com.ivieleague.kbuild.grabStandardOut
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.maven.MavenAether
import org.junit.Test
import java.io.File

class KotlinWithJavaCompileTest {
    @Test
    fun build() {
        val root = File("build/run/KotlinWithJavaCompileTest")
        root.deleteRecursively()
        val task = KotlinWithJavaCompile(
            name = "KotlinWithJavaCompileTest",
            sourceRoots = {
                val src = root.resolve("src")
                src.mkdirs()
                src.resolve("main.kt").writeText(
                    """
                    package com.test
                    import com.test.Resources
                    fun main() = println(Resources.message)
                """.trimIndent()
                )
                src.resolve("com/test/Resources.java").apply { parentFile.mkdirs() }.writeText(
                    """
                    package com.test;
                    public class Resources {
                        public static String message = "Hello World!";
                    }
                """.trimIndent()
                )
                setOf(src)
            },
            classpathJars = { MavenAether.libraries(Kotlin.standardLibraryJvmId) }.default,
            arguments = {},
            cache = root.resolve("cache"),
            outputFolder = root.resolve("out")
        )
        assert(grabStandardOut {
            JVM.runMain((task.classpathJars() + task()).toList(), "com.test.MainKt", arrayOf())
        }.contains("Hello World!"))
    }
}