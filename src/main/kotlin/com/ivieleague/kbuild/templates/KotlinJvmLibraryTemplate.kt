package com.ivieleague.kbuild.templates

import com.ivieleague.kbuild.common.*
import com.ivieleague.kbuild.intellij.IntelliJModuleBuild
import com.ivieleague.kbuild.intellij.IntelliJProjectBuild
import com.ivieleague.kbuild.junit.JUnitRun
import com.ivieleague.kbuild.jvm.JarBuild
import com.ivieleague.kbuild.jvm.Manifest
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.maven.MavenDeploy
import com.ivieleague.kbuild.maven.PomBuild
import org.apache.maven.model.Model
import java.io.File

abstract class KotlinJvmLibraryTemplate {
    abstract val projectIdentifier: ProjectIdentifier
    abstract val pomConfigure: Configurer<Model>

    open val root = File(".")
    open val pom by lazy {
        PomBuild(
            projectIdentifier = projectIdentifier,
            pomFile = root.resolve("build/maven.pom"),
            configure = pomConfigure
        )
    }
    open val sources: Producer<File> = { setOf(File("src/main/kotlin")) }
    open val testSources: Producer<File> = { setOf(File("src/test/kotlin")) }
    val build by lazy {
        KotlinJvmCompile(
            name = projectIdentifier.name,
            sourceRoots = sources,
            classpathJars = pom.compileDependencies.default,
            cache = root.resolve("build/kotlin/compileKotlin"),
            outputFolder = root.resolve("build/classes/kotlin/main")
        )
    }
    val jar by lazy {
        JarBuild(
            manifest = Manifest(),
            folders = build.asProducer() + { root.resolve("resources") }.asProducer(),
            output = root.resolve("build/libs/${projectIdentifier.nameDashVersion}.jar")
        )
    }
    val mavenPublish by lazy {
        MavenDeploy(
            pom = pom,
            default = jar,
            sources = JarBuild(folders = sources, output = root.resolve("build/sources.jar"))
        )
    }
    val intelliJ by lazy {
        IntelliJProjectBuild(
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
    }
    val junit by lazy {
        JUnitRun(
            testModule = KotlinJvmCompile(
                name = projectIdentifier.name + "-test",
                sourceRoots = testSources,
                classpathJars = pom.testCompileDependencies.default + build.asProducer(),
                cache = root.resolve("build/kotlin/compileTestKotlin"),
                outputFolder = root.resolve("build/classes/kotlin/test")
            ),
            classpath = build.asProducer() + pom.testExecutionDependencies.default
        )
    }
}