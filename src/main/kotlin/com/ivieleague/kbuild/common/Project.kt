package com.ivieleague.kbuild.common

import java.io.File

/**
 * Shared base for all kbuild project configurations.
 *
 * Holds the identity fields every project type needs so they are not duplicated
 * across [com.ivieleague.kbuild.standard.JvmLibrary], [com.ivieleague.kbuild.standard.JvmApp],
 * [com.ivieleague.kbuild.standard.MultiplatformLibrary], and
 * [com.ivieleague.kbuild.standard.MultiplatformApp].
 */
abstract class Project {
    abstract val name: String
    abstract val projectRoot: File

    open val group: String = "com.example"
    open val version: Version = Version("1.0.0-SNAPSHOT")
    open val enableContextParameters: Boolean = false

    val projectIdentifier: ProjectIdentifier get() = ProjectIdentifier(group, name, version)
    open val buildDir: File get() = projectRoot.resolve("build")
}
