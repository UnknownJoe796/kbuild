package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Producer
import org.redundent.kotlin.xml.Node
import java.io.File


class IntelliJModuleBuild(
    val projectRoot: File,
    val root: File = projectRoot,
    val name: String = root.name,
    val sourceRoots: Producer<File>,
    val libraries: Producer<Library>,
    val isTestModule: Boolean = false
) : () -> File {

    override operator fun invoke(): File = root.resolve("$name.iml").apply {
        writeText(Node("module").apply {
            val moduleRootVar = "\$MODULE_ROOT\$"
            includeXmlProlog = true
            attributes["type"] = "JAVA_MODULE"
            attributes["version"] = "4"
            "component"("name" to "NewModuleRootManager", "inherit-compiler-output" to "true") {
                "exclude-output"()
                "content"("url" to "file://$moduleRootVar/${root.invariantSeparatorsPath}") {
                    for (src in sourceRoots()) {
                        val rel = src.relativeTo(root).invariantSeparatorsPath
                        "sourceFolder"(
                            "url" to "file://$moduleRootVar/${rel}",
                            "isTestSource" to isTestModule.toString()
                        )
                    }
                }
                "orderEntry"("type" to "inheritedJdk")
                "orderEntry"("type" to "sourceFolder", "forTests" to "false")
                for (lib in libraries()) {
                    lib.intelliJLibraryFile(projectRoot)
                    "orderEntry"("type" to "library", "level" to "project", "name" to lib.fileSafeName)
                }
            }
        }.toString(prettyFormat = true))
    }
}