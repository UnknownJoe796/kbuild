package com.ivieleague.kbuild.antlr

import org.junit.Test
import java.io.File

class Antlr4GenerateSourceTest {
    @Test
    fun test() {
        val gen = Antlr4GenerateSource(
            sources = { setOf(File("src/main/antlr")) },
            output = File("build/run/Antlr4GenerateSourceTest/out")
        )
        gen.invoke()

        //Ensure files are identical, ignoring comments
        val comparedTo = File("build/generated-src/antlr/main/com/ivieleague/kotlinparser")
        for (file in gen.output.listFiles() ?: arrayOf()) {
            val other = comparedTo.resolve(file.name)
            println("Comparing ${file.name}")
            assert(file.exists())
            assert(other.exists())
            val myLines = file.readLines().filter { !it.trim().startsWith("//") }
            val otherLines = other.readLines().filter { !it.trim().startsWith("//") }
            assert(myLines.size == otherLines.size)
            for (index in myLines.indices) {
                assert(myLines[index] == otherLines[index])
            }
        }
    }
}