package com.ivieleague.kbuild.junit

import com.ivieleague.kbuild.common.Producer
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.grabStandardError
import com.ivieleague.kbuild.grabStandardOut
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.jvm.JankUntypedWrapper
import com.ivieleague.kbuild.jvm.untyped
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.*

class JUnitRun(
    val testModule: () -> File,
    val classpath: Producer<File>
) : Producer<TestResult>, (Set<String>) -> Set<TestResult> {
    val tests: Set<String>
        get() {
            val testModuleResult = testModule()
            val loaded = JVM.load(this.classpath().toList() + testModuleResult)
            val annotationClass = loaded.loadClass("org.junit.Test")
            val classes = JVM.listJavaClasses(testModuleResult)
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

    override fun invoke(): Set<TestResult> = invoke(tests)


    override operator fun invoke(tests: Set<String>): Set<TestResult> {
        val loaded = JVM.load(this.classpath().toList() + testModule())
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
        println()
        return tests.mapTo(HashSet()) { testId ->
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
            print(if (result.passed) "-" else "X")
            result
        }.also { println(" Complete.") }
    }

}