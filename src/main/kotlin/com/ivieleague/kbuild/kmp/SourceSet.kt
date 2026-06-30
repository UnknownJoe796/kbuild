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
