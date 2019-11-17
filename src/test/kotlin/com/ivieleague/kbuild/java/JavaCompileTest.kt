package com.ivieleague.kbuild.java

import com.ivieleague.kbuild.grabStandardOut
import com.ivieleague.kbuild.jvm.JVM
import org.junit.Test
import java.io.File

class JavaCompileTest {
    @Test
    fun build() {
        val root = File("build/run/JavaCompileTest")
        root.deleteRecursively()
        val task = JavaCompile(
            name = "JavaCompileTest",
            sourceRoots = {
                val src = root.resolve("src/com/test/Main")
                src.mkdirs()
                src.resolve("Main.java").writeText(
                    """
                    package com.test;
                    public class Main {
                        public static String message = "Hello World!";
                        public static void main(String args[]) {
                            System.out.println("Hello World!");
                        }
                    }
                """.trimIndent()
                )
                setOf(src)
            },
            classpathJars = { setOf() },
            cache = root.resolve("cache"),
            outputFolder = root.resolve("out")
        )
        task()
        assert(grabStandardOut {
            JVM.runMain((task.classpathJars() + task.outputFolder).toList(), "com.test.Main", arrayOf())
        }.contains("Hello World!"))
    }
}