package com.ivieleague.kbuild.intellij

import org.redundent.kotlin.xml.Node
import java.io.File

object SkateIntelliJConfigStrategy : IntelliJConfigStrategy {
    override fun configs(script: File, commands: Map<String, String>): List<Node> {
        if (System.getProperty("os.name").contains("win", true)) {
            val batch = File("run_skate.bat").absoluteFile
            batch.writeText("skate %*")
            return listOf(Node("configuration").apply {
                attributes["name"] = "Interactive Build File"
                attributes["type"] = "BatchConfigurationType"
                attributes["factoryName"] = "Batch"
                "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                "option"("name" to "WORKING_DIRECTORY", "value" to script.absoluteFile.parent)
                "option"("name" to "PARENT_ENVS", "value" to "true")
                "option"("name" to "SCRIPT_NAME", "value" to batch.absolutePath)
                "option"("name" to "PARAMETERS", "value" to "-i ${script.name}")
                "method"("v" to "2")
            }) + commands.entries.map { (name, command) ->
                Node("configuration").apply {
                    attributes["name"] = name
                    attributes["type"] = "BatchConfigurationType"
                    attributes["factoryName"] = "Batch"
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
                attributes["name"] = "Interactive Build File"
                attributes["type"] = "ShConfigurationType"
                "option"("name" to "INTERPRETER_PATH", "value" to "")
                "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                //TODO: ensure working directory
                "option"("name" to "SCRIPT_PATH", "value" to bash.absolutePath)
                "option"("name" to "SCRIPT_OPTIONS", "value" to "-i ${script.name}")
                "method"("v" to "2")
            }) + commands.entries.map { (name, command) ->
                Node("configuration").apply {
                    attributes["name"] = name
                    attributes["type"] = "ShConfigurationType"
                    "option"("name" to "INTERPRETER_PATH", "value" to "")
                    "option"("name" to "INTERPRETER_OPTIONS", "value" to "")
                    //TODO: ensure working directory
                    "option"("name" to "SCRIPT_PATH", "value" to bash.absolutePath)
                    "option"("name" to "SCRIPT_OPTIONS", "value" to "${script.name} $command")
                    "method"("v" to "2")
                }
            }
        }

    }
}

/*
*
    <configuration name="BatchTest" type="BatchConfigurationType" factoryName="Batch">
      <option name="INTERPRETER_OPTIONS" value="" />
      <option name="WORKING_DIRECTORY" value="" />
      <option name="PARENT_ENVS" value="true" />
      <module name="" />
      <option name="SCRIPT_NAME" value="path/to/batch/script" />
      <option name="PARAMETERS" value="arg1 arg2" />
      <method v="2">
        <option name="Make" enabled="true" />
      </method>
    </configuration>

    <configuration name="ShellTest" type="ShConfigurationType">
      <option name="SCRIPT_PATH" value="path/to/shell/script" />
      <option name="SCRIPT_OPTIONS" value="opt1 opt2" />
      <option name="INTERPRETER_PATH" value="" />
      <option name="INTERPRETER_OPTIONS" value="" />
      <method v="2" />
    </configuration>*/