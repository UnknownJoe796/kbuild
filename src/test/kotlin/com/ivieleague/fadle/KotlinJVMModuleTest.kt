package com.ivieleague.fadle

import com.ivieleague.fadle.jvm.JvmRunnable
import com.ivieleague.fadle.kotlin.Kotlin
import com.ivieleague.fadle.kotlin.KotlinJVMModule
import com.ivieleague.fadle.maven.*
import org.apache.maven.model.Model
import org.junit.Test
import java.io.File

class KotlinJVMModuleTest {
    @Test
    fun testVanilla() {
        // The script
        val km = object : KotlinJVMModule, JvmRunnable {
            override val mainClass: String get() = "com.test.TestKt"
            override val root: File get() = File("build/run/temp")
            override val version: Version get() = Version(0, 0, 1)
            override val jvmJarLibraries: List<Library> get() = listOf(Kotlin.standardLibrary)
        }

        // Testing the script
        km.root.deleteRecursively()

        km.kotlinSourceRoots.first().resolve("test.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            fun main() = println("Hello World!")
        """.trimIndent()
        )

        val standardOut = grabStandardOut {
            km.run()
        }
        println("Output: $standardOut")
        assert(standardOut.contains("Hello World!"))
        assert(km.kotlinJarOutput.exists())
    }

    @Test
    fun testMaven() {
        // The script
        val km = object : KotlinJVMModule, HasMavenInformation, JvmRunnable {
            override val mainClass: String get() = "com.test.TestKt"
            override val root: File get() = File("build/run/temp")
            override val version: Version get() = Version(0, 0, 1)
            override val mavenModel: Model by lazy {
                defaultMavenModel().apply {
                    organization =
                        Organization("Lightning Kite", "https://lightningkite.com")
                    licenses = listOf(Licenses.Apache2())
                    description = "A test project"
                    contributors = listOf(Contributor("Joseph Ivie", "josephivie@gmail.com"))

                    repositories = listOf()
                    dependencies = listOf(
                        Dependency("com.fasterxml.jackson.core:jackson-databind:2.9.9.3"),
                        Dependency(Kotlin.standardLibraryId)
                    )
                }
            }
        }

        // Testing the script
        km.root.deleteRecursively()

        km.kotlinSourceRoots.first().resolve("test.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package com.test
            import com.fasterxml.jackson.databind.ObjectMapper
            data class User(val firstName: String, val lastName: String, val email: String)
            fun main() = println(ObjectMapper().writeValueAsString(User("Joseph", "Ivie", "josephivie@gmail.com")))
        """.trimIndent()
        )


        assert(km.buildPom().exists())

        val standardOut = grabStandardOut {
            km.run()
        }
        println("Output: $standardOut")
        assert(standardOut.contains("""{"firstName":"Joseph","lastName":"Ivie","email":"josephivie@gmail.com"}"""))
        assert(km.kotlinJarOutput.exists())
    }
}