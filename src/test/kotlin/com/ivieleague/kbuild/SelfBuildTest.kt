package com.ivieleague.kbuild

import com.ivieleague.kbuild.antlr.Antlr4GenerateSource
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
import org.junit.Test
import java.io.File

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
                    repositories = listOf()
                    dependencies = listOf(
                        Dependency(Kotlin.standardLibraryJvmId),
                        Dependency("org.antlr", "antlr4-runtime", "4.5"),
                        Dependency("org.eclipse.aether:aether-api:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-impl:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-util:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-connector-basic:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-transport-file:$aetherVersion"),
                        Dependency("org.eclipse.aether:aether-transport-http:$aetherVersion"),
                        Dependency("org.apache.maven:maven-aether-provider:$mavenVersion"),
                        Dependency("org.apache.commons:commons-text:1.8"),
                        Dependency("org.redundent:kotlin-xml-builder:1.5.2"),
                        Dependency("org.slf4j:slf4j-nop:1.7.27"),
                        Dependency("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.10"),
                        Dependency("org.jetbrains.kotlin:kotlin-compiler-embeddable:${Kotlin.version}"),
                        Dependency("org.jetbrains.kotlin:kotlin-script-util:${Kotlin.version}"),
                        Dependency("org.jetbrains.kotlin:kotlin-script-runtime:${Kotlin.version}"),
                        Dependency("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${Kotlin.version}"),
                        Dependency("org.jetbrains.kotlin:kotlin-native-utils:${Kotlin.version}"),
                        Dependency("org.jasypt", "jasypt", "1.9.3"),
                        Dependency("junit:junit:4.12", DependencyScope.Test)
                    )
                }
            )
        }
        val antlr = Antlr4GenerateSource(
            sources = { setOf(root.resolve("src/main/antlr")) },
            output = root.resolve("kbuild/generated-src/antlr/main")
        )
        val sources: Producer<File> = { setOf(File("src/main/kotlin")) } + antlr.asProducer()
        val testSources: Producer<File> = { setOf(File("src/test/kotlin")) }
        val build = KotlinWithJavaCompile(
            name = projectIdentifier.name,
            sourceRoots = sources,
            classpathJars = pom.compileDependencies.default,
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