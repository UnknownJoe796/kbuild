package com.ivieleague.kbuild.common

/**
 * Scope of a dependency: how it participates in compilation, testing, and distribution.
 */
enum class DependencyScope {
    Compile, Provided, Runtime, Test, System, Import;

    override fun toString(): String = this.name.lowercase()

    companion object {
        val reverseMap = DependencyScope.entries.associate { it.name.lowercase() to it }
        operator fun get(string: String): DependencyScope = reverseMap[string] ?: Compile
    }

    fun includeInDistribution() = when (this) {
        Compile -> true
        Runtime -> true
        else -> false
    }

    fun includeInCompilation() = when (this) {
        Compile -> true
        Provided -> true
        System -> true
        else -> false
    }
}

/**
 * A project dependency identified by Maven coordinates.
 *
 * The most concise way to create one is the string constructor:
 * ```kotlin
 * Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
 * Dependency("org.junit.jupiter:junit-jupiter:5.10.2", DependencyScope.Test)
 * ```
 */
data class Dependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val scope: DependencyScope = DependencyScope.Compile
) {
    /** Parse from a `"group:artifact:version"` coordinate string. */
    constructor(path: String, scope: DependencyScope = DependencyScope.Compile) : this(
        groupId = path.split(':')[0],
        artifactId = path.split(':')[1],
        version = path.split(':')[2],
        scope = scope
    )

    companion object {
        /** Parse from a `"group:artifact:version"` coordinate string. Equivalent to the string constructor. */
        fun parse(path: String, scope: DependencyScope = DependencyScope.Compile): Dependency =
            Dependency(path, scope)
    }
}
