package com.ivieleague.kbuild.intellij

import java.io.File

object IntelliJ {

    fun launch(withFileOrFolder: File) {
        val osName = System.getProperty("os.name")
        val executable = when {
            osName.contains("win", true) -> File("C:\\Program Files\\JetBrains")
                .takeIf { it.exists() }
                ?.listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
                ?.resolve("bin/idea64.exe")
            else -> null
        }?.absolutePath ?: "idea"
        println("Launching IntelliJ using: $executable \"$withFileOrFolder\"")
        ProcessBuilder().command(executable, withFileOrFolder.toString()).start()
    }

//    fun workspaceFile(mainClass: String): Node = Node("project").apply {
//        includeXmlProlog = true
//        attributes["version"] = "4"
//        "component"("name" to "RunManager") {
//            "configuration"("name" to "Run", "type" to "JetRunConfigurationType", "factoryName" to "Kotlin") {
//                "module"("name" to "project")
//                "option"("name" to "VM_PARAMETERS")
//                "option"("name" to "PROGRAM_PARAMETERS")
//                "option"("name" to "ALTERNATIVE_JRE_PATH_ENABLED")
//                "option"("name" to "ALTERNATIVE_JRE_PATH")
//                "option"("name" to "PASS_PARENT_ENVS", "value" to "true")
//                "option"("name" to "MAIN_CLASS_NAME", "value" to mainClass)
//                "option"("name" to "WORKING_DIRECTORY")
//                "method"("v" to "2") {
//                    "option"("name" to "Make", "enabled" to "true")
//                }
//            }
//        }
//    }
}
