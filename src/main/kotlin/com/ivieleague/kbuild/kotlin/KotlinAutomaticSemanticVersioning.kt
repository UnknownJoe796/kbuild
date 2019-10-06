package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.common.Version

fun KotlinModule.automaticSemanticVersioning(): Version {
    val versionFile = root.resolve("gen/version.txt").apply { parentFile.mkdirs() }
    val apiFile = root.resolve("gen/api.txt").apply { parentFile.mkdirs() }
    val currentVersion = if (versionFile.exists()) Version(versionFile.readText()) else Version(0, 0, 0)
    val oldApi = if (apiFile.exists()) apiFile.readLines().toSet() else setOf()
    val currentApi =
        Kotlin.publicDeclarations(this.sourceRoots.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" },
            root.resolve("build/asv.json")
        )
    val removedDeclarations = oldApi.minus(currentApi)
    val newDeclarations = currentApi.minus(oldApi)
    var newVersion = currentVersion
    if (removedDeclarations.isNotEmpty()) {
        println("Compatibility has been broken in the public API:")
        println("--Removals--")
        removedDeclarations.forEach { println(it) }
        println("--Additions--")
        newDeclarations.forEach { println(it) }
        newVersion = newVersion.compatibilityBroken()
    } else if (newDeclarations.isNotEmpty()) {
        println("Additional features have been added the public API:")
        println("--Additions--")
        newDeclarations.forEach { println(it) }
        newVersion = newVersion.featureAdded()
    } else {
        return currentVersion

    }
    apiFile.bufferedWriter().use {
        for (line in currentApi.sorted()) {
            it.appendln(line)
        }
    }
    versionFile.writeText(newVersion.toString())
    return newVersion
}
