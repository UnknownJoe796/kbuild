package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.maven.*
import org.apache.maven.model.Dependency
import java.io.File

/**
 * Represents a Kotlin Multiplatform dependency.
 *
 * KMP dependencies have different artifacts for different targets:
 * - JVM: {artifactId}-jvm.jar
 * - JS: {artifactId}-js.klib
 * - Native: {artifactId}-{target}.klib (e.g., mylib-linuxx64.klib)
 *
 * This class helps resolve the correct artifact for each target.
 */
data class KmpDependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val scope: DependencyScope = DependencyScope.Compile
) {
    /**
     * Get the artifact ID for a specific target.
     */
    fun artifactIdForTarget(target: KmpTarget): String {
        return when (target) {
            KmpTarget.Jvm -> "$artifactId-jvm"
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> "$artifactId-js"
            KmpTarget.Wasm.Js -> "$artifactId-wasm-js"
            KmpTarget.Wasm.Wasi -> "$artifactId-wasm-wasi"
            is KmpTarget.Wasm -> "$artifactId-wasm"
            is KmpTarget.Native -> "$artifactId-${target.name.lowercase()}"
        }
    }

    /**
     * Get the artifact type for a specific target.
     */
    fun typeForTarget(target: KmpTarget): String {
        return when (target) {
            KmpTarget.Jvm -> "jar"
            else -> "klib"
        }
    }

    /**
     * Create a Maven Dependency for a specific target.
     */
    fun forTarget(target: KmpTarget): Dependency {
        return Dependency().apply {
            this.groupId = this@KmpDependency.groupId
            this.artifactId = artifactIdForTarget(target)
            this.version = this@KmpDependency.version
            this.type = typeForTarget(target)
            this.dependencyScope = this@KmpDependency.scope
        }
    }

    /**
     * Resolve libraries for a specific target.
     */
    suspend fun resolveForTarget(target: KmpTarget): Set<Library> {
        return try {
            when (target) {
                KmpTarget.Jvm -> {
                    // JVM uses standard JAR resolution
                    MavenAether.libraries(listOf(forTarget(target).aether()))
                }
                else -> {
                    // JS/Native use KLIB - need special resolution
                    val path = "$groupId:${artifactIdForTarget(target)}:$version"
                    MavenAether.librariesKlib(path)
                }
            }
        } catch (e: Exception) {
            // If target-specific artifact not found, return empty
            // This allows graceful handling when a library doesn't support all targets
            emptySet()
        }
    }

    companion object {
        /**
         * Parse a dependency string like "org.example:mylib:1.0.0"
         */
        fun parse(path: String, scope: DependencyScope = DependencyScope.Compile): KmpDependency {
            val parts = path.split(":")
            require(parts.size >= 3) { "Invalid dependency format: $path (expected group:artifact:version)" }
            return KmpDependency(
                groupId = parts[0],
                artifactId = parts[1],
                version = parts[2],
                scope = scope
            )
        }
    }
}

/**
 * DSL for creating a KMP dependency.
 */
fun kmpDependency(
    path: String,
    scope: DependencyScope = DependencyScope.Compile
): KmpDependency = KmpDependency.parse(path, scope)

/**
 * Kotlin standard library dependencies for KMP.
 */
object KotlinStdlib {
    val version = Kotlin.version.toString()

    /** Common stdlib (metadata) */
    val common = KmpDependency(
        groupId = "org.jetbrains.kotlin",
        artifactId = "kotlin-stdlib",
        version = version
    )

    /** JVM stdlib */
    fun jvm(): Dependency = Dependency(Kotlin.standardLibraryJvmId)

    /** JS stdlib */
    fun js(): Dependency = KlibDependency(Kotlin.standardLibraryJsId)

    /** Native stdlib - included in Kotlin/Native compiler */
    fun native(target: KmpTarget.Native): Set<File> {
        // Native stdlib is bundled with the compiler
        // The compiler automatically includes it
        return emptySet()
    }

    /**
     * Get stdlib for a specific target.
     */
    fun forTarget(target: KmpTarget): Dependency? {
        return when (target) {
            KmpTarget.Jvm -> jvm()
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> js()
            else -> null // Native stdlib is handled by compiler
        }
    }
}

/**
 * Kotlin test library dependencies for KMP.
 */
object KotlinTest {
    val version = Kotlin.version.toString()

    /** Common test library */
    val common = KmpDependency(
        groupId = "org.jetbrains.kotlin",
        artifactId = "kotlin-test",
        version = version,
        scope = DependencyScope.Test
    )

    /** JVM test with JUnit 5 */
    fun jvmJunit5(): Dependency = Dependency(Kotlin.standardLibraryTestJunit5Id, DependencyScope.Test)

    /** JVM test with JUnit 4 */
    fun jvmJunit(): Dependency = Dependency(Kotlin.standardLibraryTestJunitId, DependencyScope.Test)

    /** JS test library */
    fun js(): Dependency = KlibDependency(
        "org.jetbrains.kotlin:kotlin-test-js:$version",
        DependencyScope.Test
    )
}

/**
 * Collects resolved dependencies for all enabled targets.
 */
class KmpDependencyResolver(
    val targets: Set<KmpTarget>,
    val commonDependencies: Set<KmpDependency> = emptySet(),
    val targetDependencies: Map<KmpTarget, Set<Dependency>> = emptyMap()
) {
    /**
     * Resolve all dependencies for a specific target.
     *
     * This includes:
     * 1. Common KMP dependencies resolved for this target
     * 2. Target-specific dependencies
     * 3. Kotlin stdlib for this target
     */
    suspend fun resolveForTarget(target: KmpTarget): Set<Library> {
        val result = mutableSetOf<Library>()

        // Add Kotlin stdlib
        when (target) {
            KmpTarget.Jvm -> {
                KotlinStdlib.forTarget(target)?.let { dep ->
                    result.addAll(MavenAether.libraries(listOf(dep.aether())))
                }
            }
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> {
                // JS stdlib is a KLIB
                result.addAll(MavenAether.librariesKlib(Kotlin.standardLibraryJsId))
            }
            is KmpTarget.Native -> {
                // Native stdlib is bundled with the compiler - nothing to add
            }
            else -> {}
        }

        // Add common dependencies resolved for this target
        for (dep in commonDependencies) {
            result.addAll(dep.resolveForTarget(target))
        }

        // Add target-specific dependencies
        targetDependencies[target]?.forEach { dep ->
            when (target) {
                KmpTarget.Jvm -> result.addAll(MavenAether.libraries(listOf(dep.aether())))
                else -> {
                    // JS/Native deps are KLIBs
                    val path = "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    try {
                        result.addAll(MavenAether.librariesKlib(path))
                    } catch (e: Exception) {
                        // Ignore if not found
                    }
                }
            }
        }

        return result
    }

    /**
     * Resolve classpath JARs for JVM target.
     */
    suspend fun resolveJvmClasspath(): Set<File> {
        return resolveForTarget(KmpTarget.Jvm).map { it.default }.toSet()
    }

    /**
     * Resolve .klib files for a native target.
     */
    suspend fun resolveNativeLibraries(target: KmpTarget.Native): Set<File> {
        return resolveForTarget(target).map { it.default }.toSet()
    }

    /**
     * Resolve .klib files for JS target.
     */
    suspend fun resolveJsLibraries(): Set<File> {
        return resolveForTarget(KmpTarget.Js).map { it.default }.toSet()
    }

    /**
     * Resolve JVM test classpath including main classpath plus kotlin-test and JUnit 5.
     *
     * @param extraTestDeps Additional test dependencies (e.g., "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
     */
    suspend fun resolveJvmTestClasspath(vararg extraTestDeps: String): Set<File> {
        val mainClasspath = resolveJvmClasspath()

        val testDepsList = listOf(
            "org.jetbrains.kotlin:kotlin-test:${Kotlin.version}",
            "org.jetbrains.kotlin:kotlin-test-junit5:${Kotlin.version}"
        ) + extraTestDeps.toList()

        val testDeps = testDepsList.flatMap { dep ->
            MavenAether.libraries(dep, fetchSources = false).map { it.default }
        }.toSet()

        return mainClasspath + testDeps
    }
}
