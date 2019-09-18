package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.TestModule
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.maven.MavenAether
import java.io.File

interface JUnit4Module : JvmModule, TestModule {
    val forModule: JvmModule

    override val group: String get() = forModule.group
    override val name: String get() = forModule.name
    override val version: Version get() = forModule.version
    override val root: File get() = forModule.root
    override val buildFolder: File
        get() = super<JvmModule>.buildFolder.resolve("test")
    override val outFolder: File
        get() = super<JvmModule>.outFolder.resolve("test")
    override val isTestModule: Boolean get() = true

//    override val mainClass: String
//        get() = "org.junit.runner.JUnitCore"

    override val libraries: List<Library>
        get() = MavenAether.libraries("junit:junit:jar:4.12") + forModule.libraries

    val allPossibleTests get() = Jar.listJavaClasses(build())

    override fun test(testFilter: (String) -> Boolean) {
        val loaded = JVM.load(this.jvmClassPaths)
        val annotationClass = loaded.loadClass("org.junit.Test")
        val tests = allPossibleTests
            .asSequence()
            .filter(testFilter)
            .map { loaded.loadClass(it) }
            .filter {
                it.methods.any { it.annotations.any { annotationClass.isInstance(it) } }
            }
            .toList()
            .toTypedArray()
        val result = loaded.loadClass("org.junit.runner.JUnitCore").getDeclaredMethod(
            "runClasses",
            arrayOf<Class<*>>()::class.java
        ).invoke(loaded, tests).untyped()
//        val failures = result.get("failures")?.asList()?.map {
//            it[""]
//        }
//        val typedResults = tests.map {
//            TestResult(
//                testName = it.name,
//                passed = failures.an
//            )
//        }
//        println(result)
//        println(result.listMethods().joinToString())
//        println("runTime: " + result.get("runTime"))
//        println("runCount: " + result.get("runCount"))
//        println("ignoreCount: " + result.get("ignoreCount"))
//        println("failureCount: " + result.get("failureCount"))
//        println("failures: " + result.get("failures")?.asList()?.map { it?.get("trace")?.value as? String })
    }
}

