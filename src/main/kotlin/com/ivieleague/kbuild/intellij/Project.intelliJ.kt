package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.common.Project
import org.redundent.kotlin.xml.Node

interface IntelliJProject : Project {

    fun intelliJ() {
        val projectRootIndicator = "\$PROJECT_DIR\$"
        val modFiles = modules.mapNotNull { (it as? IntelliJModule)?.intelliJModuleFile() }

        val ideaFolder = root.resolve(".idea").also { it.mkdirs() }
        ideaFolder.resolve("kotlinc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attributes["version"] = "4"
            "component"("name" to "Kotlin2JvmCompilerArguments") {
                "option"("name" to "jvmTarget", "value" to "1.8")
            }
            "component"("name" to "KotlinCommonCompilerArguments") {
                "option"("name" to "apiVersion", "value" to "1.3")
                "option"("name" to "languageVersion", "value" to "1.3")
            }
        }.toString(prettyFormat = true))
        ideaFolder.resolve("modules.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attributes["version"] = "4"
            "component"("name" to "ProjectModuleManager") {
                "modules"() {
                    for (modFile in modFiles) {
                        val rel = modFile.relativeTo(root).invariantSeparatorsPath
                        "module"(
                            "fileurl" to "file://$projectRootIndicator${rel}",
                            "filepath" to "file://$projectRootIndicator${rel}",
                            "group" to "group" //TODO
                        )
                    }
                }
            }
        }.toString(prettyFormat = true))
        ideaFolder.resolve("misc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attributes["version"] = "4"
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
        //TODO: Make workspace with tasks from each module

        modules.mapNotNull { (it as? IntelliJModule)?.libraries }.flatMap { it }.distinct().forEach {
            it.intelliJLibraryFile(root)
        }
    }
}