package com.ivieleague.kbuild.intellij

import org.redundent.kotlin.xml.Node
import java.io.File

object SkateIntelliJConfigStrategy : IntelliJConfigStrategy {
    override fun configs(script: File, commands: Map<String, String>): List<Node> {
        if (System.getProperty("os.name").contains("win", true)) {
            val batch = File("run_skate.bat").absoluteFile
            batch.writeText("skate %*")
            return listOf(Node("configuration").apply {
                attribute("name", "Interactive Build File")
                attribute("type", "BatchConfigurationType")
                attribute("factoryName", "Batch")
                "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                "option"("name" to "WORKING_DIRECTORY", "value" to script.absoluteFile.parent)
                "option"("name" to "PARENT_ENVS", "value" to "true")
                "option"("name" to "SCRIPT_NAME", "value" to batch.absolutePath)
                "option"("name" to "PARAMETERS", "value" to "-i ${script.name}")
                "method"("v" to "2")
            }) + commands.entries.map { (name, command) ->
                Node("configuration").apply {
                    attribute("name", name)
                    attribute("type", "BatchConfigurationType")
                    attribute("factoryName", "Batch")
                    "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                    "option"("name" to "WORKING_DIRECTORY", "value" to script.absoluteFile.parent)
                    "option"("name" to "PARENT_ENVS", "value" to "true")
                    "option"("name" to "SCRIPT_NAME", "value" to batch.absolutePath)
                    "option"("name" to "PARAMETERS", "value" to "${script.name} $command")
                    "method"("v" to "2")
                }
            }
        } else {
            val bash = File("run_skate.sh").absoluteFile
            bash.writeText("skate $@")
            bash.setExecutable(true)
            return listOf(Node("configuration").apply {
                attribute("name", "Interactive Build File")
                attribute("type", "ShConfigurationType")
                "option"("name" to "INTERPRETER_PATH", "value" to "")
                "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                "option"("name" to "SCRIPT_WORKING_DIRECTORY", "value" to script.absoluteFile.parent)
                "option"("name" to "SCRIPT_PATH", "value" to bash.absolutePath)
                "option"("name" to "SCRIPT_OPTIONS", "value" to "-i ${script.name}")
                "method"("v" to "2")
            }) + commands.entries.map { (name, command) ->
                Node("configuration").apply {
                    attribute("name", name)
                    attribute("type", "ShConfigurationType")
                    "option"("name" to "INTERPRETER_PATH", "value" to "")
                    "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                    "option"("name" to "SCRIPT_WORKING_DIRECTORY", "value" to script.absoluteFile.parent)
                    "option"("name" to "SCRIPT_PATH", "value" to bash.absolutePath)
                    "option"("name" to "SCRIPT_OPTIONS", "value" to "${script.name} $command")
                    "method"("v" to "2")
                }
            }
        }

    }
}