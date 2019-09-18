package com.ivieleague.kbuild.js

import com.fasterxml.jackson.databind.ObjectMapper
import com.ivieleague.kbuild.common.Module
import java.io.File

interface NodeModule : Module {

    val nodePrivate: Boolean get() = true
    val hasSideEffects: Boolean get() = false
    val nodeDependencies: Map<String, String> get() = mapOf()
    val nodeDevDependencies: Map<String, String> get() = mapOf()
    val nodeScripts: Map<String, String> get() = mapOf()

    fun nodePackageFile(): File {
        return root.resolve("package.json").also {
            it.writeText(
                ObjectMapper().writeValueAsString(
                    mapOf(
                        "name" to this.group + "/" + this.name,
                        "version" to this.version.toString(),
                        "dependencies" to nodeDependencies,
                        "devDependencies" to nodeDevDependencies,
                        "scripts" to nodeScripts,
                        "private" to nodePrivate,
                        "sideEffects" to hasSideEffects
                    )
                )
            )
        }
    }

    fun npmInstall(): Int {
        nodePackageFile()
        return Node.npm(root, "install")
    }

    fun nodeRunScript(script: String): Int {
        npmInstall()
        return Node.npm(root, "run", script)
    }
}