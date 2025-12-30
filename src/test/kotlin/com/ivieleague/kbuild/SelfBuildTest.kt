package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.*
import com.ivieleague.kbuild.intellij.IntelliJModuleBuild
import com.ivieleague.kbuild.intellij.IntelliJProjectBuild
import com.ivieleague.kbuild.junit.JUnitRun
import com.ivieleague.kbuild.jvm.JarBuild
import com.ivieleague.kbuild.jvm.Manifest
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.kotlin.KotlinWithJavaCompile
import com.ivieleague.kbuild.maven.*
import kotlin.test.Test
import java.io.File

@Suppress("DEPRECATION")
class SelfBuildTest {
    object Project {
        val aetherVersion = "1.0.0.v20140518"
        val mavenVersion = "3.1.0"
        val projectIdentifier: ProjectIdentifier = ProjectIdentifier("com.ivieleague:kbuild:0.0.1")

        val root = File(".")
        val pom by lazy {
            PomBuild(
                projectIdentifier = projectIdentifier,
                pomFile = root.resolve("kbuild/maven.pom"),
                configure = {
                    repositories = listOf(
                        Repository("https://lightningkite-maven.s3.us-west-2.amazonaws.com", "lightningkite")
                    )
                    dependencies = listOf(
                        Dependency(Kotlin.standardLibraryJvmId),
                        Dependency("org.jetbrains.kotlin:kotlin-reflect:${Kotlin.version}"),
                        Dependency("com.lightningkite:reactive-jvm:6.0.0-prerelease-26"),
                        Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3"),
                        Dependency("org.eclipse.aether:aether-api:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-impl:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-util:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-connector-basic:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-transport-file:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-transport-http:$aetherVersion"),
                        Dependency("org.apache.maven:maven-aether-provider:$mavenVersion"),
                        Dependency("org.apache.commons:commons-text:1.11.0"),
                        Dependency("org.redundent:kotlin-xml-builder:1.9.1"),
                        Dependency("org.jetbrains.kotlin:kotlin-compiler-embeddable:${Kotlin.version}"),
                        Dependency("org.jetbrains.kotlin:kotlin-native-utils:${Kotlin.version}"),
                        Dependency("org.jasypt:jasypt:1.9.3"),
                        Dependency("org.junit.jupiter:junit-jupiter-api:5.8.1"),
                        Dependency("org.junit.jupiter:junit-jupiter-engine:5.8.1"),
                        Dependency("org.junit.platform:junit-platform-launcher:1.10.2"),
                        // Interactive CLI
                        Dependency("org.jline:jline:3.26.3"),
                        Dependency("org.jetbrains.kotlin:kotlin-scripting-jsr223:${Kotlin.version}"),
                        Dependency(Kotlin.standardLibraryTestJunit5Id, DependencyScope.Test)
                    )
                }
            )
        }
        val sources: Producer<File> = { setOf(File("src/main/kotlin")) }
        val testSources: Producer<File> = { setOf(File("src/test/kotlin")) }
        val build = KotlinWithJavaCompile(
            name = projectIdentifier.name,
            sourceRoots = sources,
            classpathJars = pom.compileDependencies.default,
            arguments = {
                contextParameters = true
            },
            cache = root.resolve("kbuild/main"),
            outputFolder = root.resolve("kbuild/main")
        )

        val jar = JarBuild(
            manifest = Manifest(),
            folders = build + { root.resolve("resources") }.asProducer(),
            output = root.resolve("kbuild/libs/${projectIdentifier.nameDashVersion}.jar")
        )

        val mavenPublish = MavenDeploy(
            pom = pom,
            default = jar,
            sources = JarBuild(folders = sources, output = root.resolve("kbuild/sources.jar"))
        )

        val intelliJ = IntelliJProjectBuild(
            root = root,
            modules = setOf(
                IntelliJModuleBuild(
                    projectRoot = root,
                    root = root,
                    name = projectIdentifier.name,
                    sourceRoots = sources,
                    libraries = pom.compileDependencies
                )
            )
        )

        val junit = JUnitRun(
            testModule = KotlinJvmCompile(
                name = projectIdentifier.name + "-test",
                sourceRoots = testSources,
                classpathJars = pom.testCompileDependencies.default + build,
                arguments = {
                    contextParameters = true
                },
                cache = root.resolve("kbuild/kotlin/compileTestKotlin"),
                outputFolder = root.resolve("kbuild/classes/kotlin/test")
            ),
            classpath = build + pom.testExecutionDependencies.default
        )

    }

    @Test
    fun build() {
        println("My sources: ${Project.sources()}")
        Project.mavenPublish.deploy(MavenAether.local)
    }
}