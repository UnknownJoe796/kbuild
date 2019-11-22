package com.ivieleague.kbuild.antlr

import com.ivieleague.kbuild.common.Producer
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.memoize
import com.ivieleague.skate.statusHash
import java.io.File

class Antlr4GenerateSource(
    val sources: Producer<File>,
    val output: File
) : () -> File {
    override fun invoke(): File {
        val antlrSource = sources()
        val jars = MavenAether.libraries("org.antlr:antlr4:4.7.2").map { it.default }
        val loaded = JVM.load(jars)
        val toolClass = loaded.loadClass("org.antlr.v4.Tool")
        val toolClassConstructor = toolClass.getConstructor(arrayOf<String>()::class.java)
        val toolClassProcessMethod = toolClass.getMethod("processGrammarsOnCommandLine")
        antlrSource.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "g4" }.forEach {
            memoize(it.absolutePath + it.statusHash()) {
                println("Compiling $it")
                val tool = toolClassConstructor.newInstance(
                    arrayOf(
                        "-o",
                        output.path,
                        "-lib",
                        antlrSource.joinToString(File.pathSeparator),
                        it.absolutePath
                    )
                )
                toolClassProcessMethod.invoke(tool)
                it
            }
        }
        return output
    }
}

//File memoization: Given inputs and outputs, check stored stuff to see if it matches
//memoize(inputStatuses, outputStatuses) {}