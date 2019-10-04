package com.ivieleague.kbuild.jvm

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.TestModule
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.grabStandardError
import com.ivieleague.kbuild.grabStandardOut
import com.ivieleague.kbuild.maven.MavenAether
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.*

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

    override val libraries: Set<Library>
        get() = MavenAether.libraries("junit:junit:jar:4.12") + forModule.libraries

    override val tests: Set<String>
        get() {
            val loaded = JVM.load(this.jvmClassPaths)
            val annotationClass = loaded.loadClass("org.junit.Test")
            val classes = Jar.listJavaClasses(build())
            return classes
                .asSequence()
                .map { loaded.loadClass(it) }
                .flatMap { c ->
                    c.methods
                        .asSequence()
                        .filter { it.annotations.any { annotationClass.isInstance(it) } }
                        .map { c.name + "." + it.name }
                }
                .toSet()
        }

    override fun test(tests: Set<String>): Map<String, TestResult> {
        val loaded = JVM.load(this.jvmClassPaths)
        val requestClass = loaded.loadClass("org.junit.runner.Request")
        val makeRequestMethod = requestClass.getMethod("method", Class::class.java, String::class.java)
        fun makeRequest(id: String): Any {
            return makeRequestMethod.invoke(
                requestClass,
                loaded.loadClass(id.substringBeforeLast('.')),
                id.substringAfterLast('.')
            )
        }

        val coreClass = loaded.loadClass("org.junit.runner.JUnitCore")
        val coreInstance = coreClass.getConstructor().newInstance()
        val runTestMethod = coreClass.getDeclaredMethod(
            "run",
            requestClass
        )
        return tests.associate { testId ->
            var stdOut = ""
            var stdErr = ""
            var duration = 0L
            var rawResult: JankUntypedWrapper? = null
            val runAt = Date()
            stdOut = grabStandardOut {
                stdErr = grabStandardError {
                    val start = System.nanoTime()
                    rawResult = runTestMethod.invoke(coreInstance, makeRequest(testId)).untyped()
                    duration = System.nanoTime() - start
                }
            }
            val result = TestResult(
                identifier = testId,
                passed = (rawResult!!.get("failureCount")!!.value as Int) == 0,
                standardOutput = stdOut,
                standardError = stdErr,
                error = rawResult!!.get("failures")?.asList()?.firstOrNull()?.get("exception")?.let { it.value as? Exception }?.let {
                    val bytes = ByteArrayOutputStream()
                    it.printStackTrace(PrintStream(bytes))
                    bytes.toString(Charset.defaultCharset())
                },
                durationSeconds = duration.div(1_000_000_000.0),
                runAt = runAt
            )
            testId to result
        }
    }
}