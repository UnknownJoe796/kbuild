package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.common.Library
import org.redundent.kotlin.xml.Node
import java.io.File


fun Library.intelliJLibraryFile(projectRoot: File) {
    projectRoot.resolve(".idea/libraries/$fileSafeName").writeText(Node("component").apply {
        attributes["name"] = "libraryTable"
        "library" {
            fun File.url(): String = when (extension) {
                "" -> "file://${invariantSeparatorsPath}/"
                "jar" -> "jar://${invariantSeparatorsPath}!/"
                else -> "file://${invariantSeparatorsPath}/"
            }

            attributes["name"] = fileSafeName
            "CLASSES" {
                "root"("url" to default.url())
            }
            "JAVADOC" {
                if (javadoc != null) {
                    "root"("url" to javadoc.url())
                }
            }
            "SOURCES" {
                if (sources != null) {
                    "root"("url" to sources.url())
                }
            }
        }
    }.toString(true))
}