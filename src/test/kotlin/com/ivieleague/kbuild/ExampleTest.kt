package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.common.asProducer
import com.ivieleague.kbuild.common.default
import com.ivieleague.kbuild.common.plus
import com.ivieleague.kbuild.intellij.IntelliJModuleBuild
import com.ivieleague.kbuild.intellij.IntelliJProjectBuild
import com.ivieleague.kbuild.junit.JUnitRun
import com.ivieleague.kbuild.jvm.*
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.maven.*
import org.junit.Test
import java.io.File

class ExampleTest {

    class Project() {
        val root = File("./build/run/first")
        val projectIdentifier = ProjectIdentifier("com.ivieleague:kbuild-test-first:0.0.1")
        val pom = PomBuild(
            projectIdentifier = projectIdentifier, ///
            pomFile = root.resolve("build/maven.pom") ///
        ) {
            repositories = listOf()
            dependencies = listOf(
                Dependency(Kotlin.standardLibraryJvmId),
                Dependency("junit:junit:4.12", DependencyScope.Test)
            )
        }
        val sources = {
            val result = root.resolve("src")
            result.mkdirs()
            result.resolve("main.kt").writeText(
                """
                    package com.test
                    object AdditionalMaterials {
                        val message = "Hello World!"
                    }
                    fun main() = println(AdditionalMaterials.message)
                """.trimIndent()
            )
            result
        }
        val testSources = {
            val result = root.resolve("test")
            result.mkdirs()
            result.resolve("MainTest.kt").writeText(
                """
                    package com.test
                    import org.junit.Test
                    class MainTest(){
                        @Test
                        fun messageIsCorrect() {
                            assert(AdditionalMaterials.message == "Hello World!")
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
            result
        }
        val build = KotlinJvmCompile(
            name = "First", ///
            sourceRoots = sources.asProducer(),
            classpathJars = pom.compileDependencies.default,
            cache = root.resolve("build/cache"), ///
            outputFolder = root.resolve("build/out") ///
        )
        val jar = JarBuild(
            manifest = Manifest("Main-Class" to "com.test.MainKt"),
            folders = build.asProducer() + { root.resolve("resources") }.asProducer(),
            output = root.resolve("build/out.jar") ///
        )
        val mavenPublish = MavenDeploy(
            pom = pom,
            default = jar,
            sources = JarBuild(folders = sources.asProducer(), output = root.resolve("build/sources.jar"))
        )
        val intelliJ = IntelliJProjectBuild(
            root = root,
            modules = setOf(
                IntelliJModuleBuild(
                    projectRoot = root,
                    root = root,
                    name = "First",
                    sourceRoots = sources.asProducer(),
                    libraries = pom.compileDependencies
                )
            )
        )
        val run = JvmExecute("com.test.MainKt", build.asProducer() + pom.distributionDependencies.default)
        val junit = JUnitRun(
            testModule = KotlinJvmCompile(
                name = "FirstTests",
                sourceRoots = testSources.asProducer(),
                classpathJars = pom.testCompileDependencies.default + build.asProducer(),
                cache = root.resolve("build/cache-test"),
                outputFolder = root.resolve("build/out-test")
            ),
            classpath = build.asProducer() + pom.testExecutionDependencies.default
        )
    }

    @Test
    fun creationIsLight() {
        val start = System.currentTimeMillis()
        val project = Project()
        val end = System.currentTimeMillis()
        assert(end - start < 10)
    }

    @Test
    fun run() {
        assert(grabStandardOut {
            Project().run()
        }.contains("Hello World!"))
    }

    @Test
    fun runJar() {
        val project = Project()
        val jar = project.jar()
        assert(grabStandardOut {
            Jar(jar).execute(project.build.classpathJars().toList())
        }.contains("Hello World!"))
    }

    @Test
    fun runJvm() {
        val project = Project()
        val built = project.build()
        assert(grabStandardOut {
            JVM.runMain(project.build.classpathJars().toList() + built, project.run.mainClass, arrayOf())
        }.contains("Hello World!"))
    }

    @Test
    fun deployLocal() {
        Project().mavenPublish.deploy(MavenAether.local)
    }

    @Test
    fun intelliJ() {
        Project().intelliJ()
    }

    @Test
    fun junit() {
        Project().junit()
    }
}