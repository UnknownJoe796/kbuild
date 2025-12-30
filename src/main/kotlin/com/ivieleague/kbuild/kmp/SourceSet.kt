package com.ivieleague.kbuild.kmp

import org.apache.maven.model.Dependency
import java.io.File

/**
 * Represents a Kotlin Multiplatform source set.
 *
 * A source set is a collection of Kotlin sources that compile to one or more targets.
 * Source sets form a hierarchy where child source sets inherit from parent source sets.
 *
 * Standard hierarchy:
 * ```
 * commonMain
 * ├── jvmMain
 * ├── jsMain
 * ├── nativeMain
 * │   ├── appleMain
 * │   │   ├── iosMain
 * │   │   │   ├── iosArm64Main
 * │   │   │   ├── iosSimulatorArm64Main
 * │   │   │   └── iosX64Main
 * │   │   ├── macosMain
 * │   │   │   ├── macosX64Main
 * │   │   │   └── macosArm64Main
 * │   │   └── ...
 * │   ├── linuxMain
 * │   └── mingwMain
 * └── wasmMain
 * ```
 *
 * @param name The source set name (e.g., "commonMain", "jvmMain")
 * @param sourceDirectories Directories containing Kotlin sources
 * @param resourceDirectories Directories containing resources
 * @param dependsOn Parent source sets this inherits from
 * @param dependencies Maven dependencies specific to this source set
 * @param targets The targets this source set compiles to (empty for intermediate source sets)
 */
data class SourceSet(
    val name: String,
    val sourceDirectories: Set<File> = emptySet(),
    val resourceDirectories: Set<File> = emptySet(),
    val dependsOn: Set<SourceSet> = emptySet(),
    val dependencies: Set<Dependency> = emptySet(),
    val targets: Set<KmpTarget> = emptySet()
) {
    /**
     * All source directories including inherited from parent source sets.
     */
    val allSourceDirectories: Set<File> by lazy {
        sourceDirectories + dependsOn.flatMap { it.allSourceDirectories }
    }

    /**
     * All resource directories including inherited from parent source sets.
     */
    val allResourceDirectories: Set<File> by lazy {
        resourceDirectories + dependsOn.flatMap { it.allResourceDirectories }
    }

    /**
     * All dependencies including inherited from parent source sets.
     */
    val allDependencies: Set<Dependency> by lazy {
        dependencies + dependsOn.flatMap { it.allDependencies }
    }

    /**
     * All parent source sets (transitive).
     */
    val allDependsOn: Set<SourceSet> by lazy {
        dependsOn + dependsOn.flatMap { it.allDependsOn }
    }

    /**
     * Whether this is a main (production) source set.
     */
    val isMain: Boolean get() = name.endsWith("Main")

    /**
     * Whether this is a test source set.
     */
    val isTest: Boolean get() = name.endsWith("Test")

    /**
     * The base name without Main/Test suffix.
     */
    val baseName: String get() = name.removeSuffix("Main").removeSuffix("Test")

    override fun toString(): String = "SourceSet($name)"
    override fun hashCode(): Int = name.hashCode()
    override fun equals(other: Any?): Boolean = other is SourceSet && other.name == name
}

/**
 * Builder for creating source set hierarchies.
 */
class SourceSetBuilder(
    val name: String,
    val projectRoot: File
) {
    private var sourceDirectories: MutableSet<File> = mutableSetOf()
    private var resourceDirectories: MutableSet<File> = mutableSetOf()
    private var dependsOn: MutableSet<SourceSet> = mutableSetOf()
    private var dependencies: MutableSet<Dependency> = mutableSetOf()
    private var targets: MutableSet<KmpTarget> = mutableSetOf()

    /**
     * Add source directories.
     */
    fun srcDir(vararg dirs: File) = apply {
        sourceDirectories.addAll(dirs)
    }

    /**
     * Add source directory using standard layout.
     */
    fun srcDir(path: String) = apply {
        sourceDirectories.add(projectRoot.resolve(path))
    }

    /**
     * Use standard source directory layout: src/{name}/kotlin
     */
    fun useStandardLayout() = apply {
        sourceDirectories.add(projectRoot.resolve("src/$name/kotlin"))
        resourceDirectories.add(projectRoot.resolve("src/$name/resources"))
    }

    /**
     * Add resource directories.
     */
    fun resourceDir(vararg dirs: File) = apply {
        resourceDirectories.addAll(dirs)
    }

    /**
     * This source set depends on (inherits from) another source set.
     */
    fun dependsOn(vararg sourceSets: SourceSet) = apply {
        dependsOn.addAll(sourceSets)
    }

    /**
     * Add dependencies for this source set.
     */
    fun dependencies(vararg deps: Dependency) = apply {
        dependencies.addAll(deps)
    }

    /**
     * Add dependencies for this source set.
     */
    fun dependencies(deps: Collection<Dependency>) = apply {
        dependencies.addAll(deps)
    }

    /**
     * Specify targets this source set compiles to.
     */
    fun targets(vararg t: KmpTarget) = apply {
        targets.addAll(t)
    }

    fun build(): SourceSet = SourceSet(
        name = name,
        sourceDirectories = sourceDirectories.toSet(),
        resourceDirectories = resourceDirectories.toSet(),
        dependsOn = dependsOn.toSet(),
        dependencies = dependencies.toSet(),
        targets = targets.toSet()
    )
}

/**
 * DSL for building a source set.
 */
fun sourceSet(name: String, projectRoot: File, block: SourceSetBuilder.() -> Unit = {}): SourceSet =
    SourceSetBuilder(name, projectRoot).apply(block).build()
