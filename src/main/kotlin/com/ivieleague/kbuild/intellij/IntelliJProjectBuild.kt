package com.ivieleague.kbuild.intellij

import org.redundent.kotlin.xml.Node
import java.io.File

class IntelliJProjectBuild(
    val root: File,
    val modules: Set<IntelliJModuleBuild>
) : () -> File {

    override operator fun invoke(): File = invoke(clean = false)

    operator fun invoke(clean: Boolean): File {
        val projectRootIndicator = "\$PROJECT_DIR\$"
        val modFiles = modules.map { it() }

        val ideaFolder = root.resolve(".idea").also { it.mkdirs() }
        ideaFolder.resolve("kotlinc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")
            "component"("name" to "Kotlin2JvmCompilerArguments") {
                "option"("name" to "jvmTarget", "value" to "17")
            }
            "component"("name" to "KotlinCommonCompilerArguments") {
                "option"("name" to "apiVersion", "value" to "1.9")
                "option"("name" to "languageVersion", "value" to "1.9")
            }
        }.toString(prettyFormat = true))
        ideaFolder.resolve("modules.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")
            "component"("name" to "ProjectModuleManager") {
                "modules"() {
                    for (modFile in modFiles) {
                        val rel = modFile.relativeTo(root).invariantSeparatorsPath
                        "module"(
                            "fileurl" to "file://$projectRootIndicator/${rel}",
                            "filepath" to "$projectRootIndicator/${rel}",
                            "group" to "group" //TODO
                        )
                    }
                }
            }
        }.toString(prettyFormat = true))
        ideaFolder.resolve("misc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")
            "component"(
                "name" to "ProjectRootManager",
                "version" to "2",
                "languageLevel" to "JDK_12",
                "default" to "true",
                "project-jdk-name" to "12",
                "project-jdk-type" to "JavaSDK"
            ) {
                "output"("url" to "file://$projectRootIndicator/out")
            }
        }.toString(prettyFormat = true))
        // Use flatMapTo with LinkedHashSet for O(1) deduplication instead of O(n) distinct()
        modules.flatMapTo(linkedSetOf()) { it.libraries() }.forEach { lib ->
            lib.intelliJLibraryFile(root)
        }
        return root
    }
}