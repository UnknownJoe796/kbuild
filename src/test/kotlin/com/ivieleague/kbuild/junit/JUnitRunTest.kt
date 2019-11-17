package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.default
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.maven.Dependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import org.junit.Test
import java.io.File

class JUnitRunTest {
    @Test
    fun test() {
        val root = File("build/run/JUnitRunTest")
        val classpath = {
            MavenAether.libraries(
                listOf(
                    Dependency(Kotlin.standardLibraryJvmId).aether(),
                    Dependency("junit:junit:4.12").aether()
                )
            )
        }.default
        val task = JUnitRun(
            testModule = KotlinJvmCompile(
                name = "JUnitRunTest",
                sourceRoots = {
                    val src = root.resolve("src")
                    src.mkdirs()
                    src.resolve("test.kt").writeText(
                        """
                        package com.test
                        import org.junit.Test
                        class MainTest(){
                            @Test
                            fun canAccessKotlin() {
                                listOf(1, 2, 3)
                            }
                            @Test
                            fun logicWorks() {
                                assert(1 + 1 == 2)
                            }
                            @Test
                            fun fails() {
                                throw Exception("My Message")
                            }
                        }
                    """.trimIndent()
                    )
                    setOf(src)
                },
                classpathJars = classpath,
                cache = root.resolve("build/cache-test"),
                outputFolder = root.resolve("build/out-test")
            ),
            classpath = classpath
        )

        val results = task()
        assert(results.find { it.identifier == "com.test.MainTest.canAccessKotlin" }!!.passed)
        assert(results.find { it.identifier == "com.test.MainTest.logicWorks" }!!.passed)
        assert(!results.find { it.identifier == "com.test.MainTest.fails" }!!.passed)
    }
}