package com.ivieleague.kbuild.intellij

import org.redundent.kotlin.xml.Node
import java.io.File

interface IntelliJConfigStrategy {
    fun configs(script: File, commands: Map<String, String>): List<Node>
}