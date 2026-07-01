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

    /** Opt-in annotations passed to the compiler via -opt-in= for every enabled target. */
    open val optIns: List<String> = emptyList()

    /**
     * Raw compiler arguments appended last, after all other options (the escape hatch).
     * Maps to freeArgs on JVM/JS and appended CLI args on Native.
     */
    open val freeCompilerArgs: List<String> = emptyList()

    /** Kotlin language version, e.g. "2.1". Null means the compiler's own default. */
    open val languageVersion: String? = null

    /** Kotlin API version, e.g. "2.0". Null means the compiler's own default. */
    open val apiVersion: String? = null

    /** Treat all compiler warnings as errors (-Werror / allWarningsAsErrors). */
    open val allWarningsAsErrors: Boolean = false

    val projectIdentifier: ProjectIdentifier get() = ProjectIdentifier(group, name, version)
    open val buildDir: File get() = projectRoot.resolve("build")
}
