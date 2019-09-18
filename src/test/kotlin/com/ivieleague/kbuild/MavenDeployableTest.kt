package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.JvmRunnable
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJVMModule
import com.ivieleague.kbuild.maven.*
import org.apache.maven.model.Model
import org.junit.Test
import java.io.File

class MavenDeployableTest {
    @Test
    fun testMavenDeploy() {
        // The script
        val km = object : KotlinJVMModule, MavenDeployable, JvmRunnable, MavenDeployableWithSources {
            override val name: String get() = "testmavendeploy"
            override val mainClass: String get() = "com.test.TestKt"
            override val root: File get() = File("build/run/temp/$name")
            override val version: Version
                get() = Version(
                    0,
                    0,
                    1
                )
            override val defaultFile: File get() = distribution()
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
                        Dependency(Kotlin.standardLibraryJvmId)
                    )
                }
            }
        }

        // Testing the script
        km.root.deleteRecursively()

        km.sourceRoots.first().resolve("test.kt").also { it.parentFile.mkdirs() }.writeText(
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
        assert(km.classpathOutput.exists())

        km.deploy(MavenAether.local)
    }
}