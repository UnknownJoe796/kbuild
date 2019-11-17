package com.ivieleague.kbuild.intellij

import org.redundent.kotlin.xml.Node
import java.io.File

class IntelliJProjectBuild(
    val root: File,
    val modules: Set<IntelliJModuleBuild>
) : () -> File {

    /*

    Skate Options

    - Pull Skate from Maven; execute from there
    - Pull Skate from Github; execute from there
    - Use Kscript? different way of pulling in dependencies
    - Use KotlinC directly - no edit support, but it works maybe - it would have to be a KTS

    */

    fun skateConfiguration(name: String, args: String, workingDirectory: File): Node = Node("configuration").apply {
        attributes["name"] = name
        attributes["type"] = "JarApplication"
        "option"("name" to "JAR_PATH", "value" to "SKATEPATH")
        "option"("name" to "PROGRAM_PARAMETERS", "value" to args)
        "option"("name" to "WORKING_DIRECTORY", "value" to workingDirectory)
        "method"("v" to "2")
    }

    override operator fun invoke(): File = invoke(clean = false)

    operator fun invoke(clean: Boolean): File {
        val projectRootIndicator = "\$PROJECT_DIR\$"
        val modFiles = modules.map { it() }

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
        ideaFolder.resolve("workspace.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attributes["version"] = "4"
            addNode(skateConfiguration("", "", File("")))
        }.toString(prettyFormat = true))
        return root
    }
}