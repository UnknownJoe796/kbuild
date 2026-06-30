package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.common.DependencyScope
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.compareVersions
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.maven.*
import org.apache.maven.model.Dependency as MavenDep
import java.io.File

/**
 * Get the artifact ID for a specific KMP target.
 */
fun Dependency.artifactIdForTarget(target: KmpTarget): String {
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
 * Get the artifact type for a specific KMP target (jar for JVM, klib for others).
 */
fun Dependency.typeForTarget(target: KmpTarget): String {
    return when (target) {
        KmpTarget.Jvm -> "jar"
        else -> "klib"
    }
}

/**
 * Create a Maven Dependency for a specific KMP target (used in POM generation and resolution).
 */
fun Dependency.forTarget(target: KmpTarget): MavenDep {
    return MavenDep().apply {
        this.groupId = this@forTarget.groupId
        this.artifactId = artifactIdForTarget(target)
        this.version = this@forTarget.version
        this.type = typeForTarget(target)
        this.dependencyScope = this@forTarget.scope
    }
}

/**
 * Resolve libraries for a specific KMP target.
 *
 * Prefers variant-aware Gradle Module Metadata (`.module`) resolution. Falls back to
 * convention-based artifact-name guessing when the library publishes no `.module`.
 */
suspend fun Dependency.resolveForTarget(target: KmpTarget): Set<Library> {
    MavenAether.resolveKmpForTarget(groupId, artifactId, version, target)?.let { return it }

    return try {
        when (target) {
            KmpTarget.Jvm -> {
                MavenAether.libraries(listOf(forTarget(target).aether()))
            }
            else -> {
                val path = "$groupId:${artifactIdForTarget(target)}:$version"
                MavenAether.librariesKlib(path)
            }
        }
    } catch (e: Exception) {
        emptySet()
    }
}

/**
 * DSL helper for creating a kbuild Dependency from a coordinate string.
 */
fun kmpDependency(
    path: String,
    scope: DependencyScope = DependencyScope.Compile
): Dependency = Dependency.parse(path, scope)

/**
 * Kotlin standard library dependencies for KMP.
 */
object KotlinStdlib {
    val version = Kotlin.version.toString()

    val common = Dependency(
        groupId = "org.jetbrains.kotlin",
        artifactId = "kotlin-stdlib",
        version = version
    )

    fun jvm(): MavenDep = Dependency(Kotlin.standardLibraryJvmId).toMaven()

    fun js(): MavenDep = KlibDependency(Kotlin.standardLibraryJsId)

    fun native(target: KmpTarget.Native): Set<File> = emptySet()

    fun forTarget(target: KmpTarget): MavenDep? {
        return when (target) {
            KmpTarget.Jvm -> jvm()
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> js()
            else -> null
        }
    }
}

/**
 * Kotlin test library dependencies for KMP.
 */
object KotlinTest {
    val version = Kotlin.version.toString()

    val common = Dependency(
        groupId = "org.jetbrains.kotlin",
        artifactId = "kotlin-test",
        version = version,
        scope = DependencyScope.Test
    )

    fun jvmJunit5(): MavenDep = Dependency(Kotlin.standardLibraryTestJunit5Id, DependencyScope.Test).toMaven()

    fun jvmJunit(): MavenDep = Dependency(Kotlin.standardLibraryTestJunitId, DependencyScope.Test).toMaven()

    fun js(): MavenDep = KlibDependency(
        "org.jetbrains.kotlin:kotlin-test-js:$version",
        DependencyScope.Test
    )
}

/**
 * Collects resolved dependencies for all enabled targets.
 */
class KmpDependencyResolver(
    val targets: Set<KmpTarget>,
    val commonDependencies: Set<Dependency> = emptySet(),
    val targetDependencies: Map<KmpTarget, Set<Dependency>> = emptyMap()
) {
    /**
     * Resolve all dependencies for a specific target.
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
                result.addAll(MavenAether.librariesKlib(Kotlin.standardLibraryJsId))
            }
            is KmpTarget.Native -> { /* bundled with compiler */ }
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
                    val path = "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    try {
                        result.addAll(MavenAether.librariesKlib(path))
                    } catch (e: Exception) { }
                }
            }
        }

        return result.resolveVersionConflicts(
            kotlinVersion = Kotlin.version.toString(),
            reResolve = { group, artifact, pinnedVersion, sample ->
                val extension = if (sample.default.name.endsWith(".klib")) "klib" else "jar"
                val path = "$group:$artifact:$pinnedVersion"
                Library(name = path, default = MavenAether.singleArtifactFile(path, extension))
            }
        )
    }

    suspend fun resolveJvmClasspath(): Set<File> =
        resolveForTarget(KmpTarget.Jvm).map { it.default }.toSet()

    suspend fun resolveNativeLibraries(target: KmpTarget.Native): Set<File> =
        resolveForTarget(target).map { it.default }.toSet()

    suspend fun resolveJsLibraries(): Set<File> =
        resolveForTarget(KmpTarget.Js).map { it.default }.toSet()

    /**
     * Resolve JVM test classpath including main classpath plus kotlin-test and JUnit 5.
     *
     * @param extraTestDeps Additional test dependencies as kbuild [Dependency] objects.
     */
    suspend fun resolveJvmTestClasspath(vararg extraTestDeps: Dependency): Set<File> {
        val mainClasspath = resolveJvmClasspath()

        val testDepsList = listOf(
            Dependency("org.jetbrains.kotlin:kotlin-test:${Kotlin.version}"),
            Dependency("org.jetbrains.kotlin:kotlin-test-junit5:${Kotlin.version}")
        ) + extraTestDeps.toList()

        val testDeps = testDepsList.flatMap { dep ->
            MavenAether.libraries(
                "${dep.groupId}:${dep.artifactId}:${dep.version}",
                fetchSources = false
            ).map { it.default }
        }.toSet()

        return mainClasspath + testDeps
    }
}

private fun Library.isKotlinFirstParty(): Boolean {
    val parts = name.split(':')
    return parts.size >= 2 && parts[0] == "org.jetbrains.kotlin" && parts[1].startsWith("kotlin-")
}

/**
 * Collapse a resolved compile classpath to exactly one artifact per `group:artifact`.
 *
 * Two versions of one module cannot coexist on a compile classpath: klibs collide by `unique_name`
 * (a hard error) and JVM jars shadow each other unpredictably. Different transitive paths routinely
 * request different versions, so kbuild resolves the conflict the way Gradle does by default:
 *
 * - **Highest version wins** for ordinary modules.
 * - **Kotlin first-party** (`org.jetbrains.kotlin:kotlin-*`) is instead **pinned to [kotlinVersion]**
 *   — the version of kbuild's embedded compiler — regardless of what transitives declare, so the
 *   stdlib/runtime never drifts ABI-incompatible with the compiler. This mirrors the version
 *   alignment the Kotlin Gradle plugin applies to its own modules.
 *
 * [reResolve] supplies a pinned coordinate that isn't already on the classpath — only reached when a
 * transitive pulls a `kotlin-*` module at some other version and no [kotlinVersion] copy is present.
 * It receives an existing same-module artifact as a packaging sample and returns the [kotlinVersion]
 * artifact.
 */
suspend fun Set<Library>.resolveVersionConflicts(
    kotlinVersion: String,
    reResolve: suspend (group: String, artifact: String, pinnedVersion: String, sample: Library) -> Library
): Set<Library> {
    val best = LinkedHashMap<String, Library>()
    val passthrough = LinkedHashSet<Library>()
    for (library in this) {
        val parts = library.name.split(':')
        if (parts.size < 3) {
            passthrough.add(library)
            continue
        }
        val key = "${parts[0]}:${parts[1]}"
        val candidateVersion = parts[2]
        val existing = best[key]
        when {
            existing == null -> best[key] = library
            library.isKotlinFirstParty() -> if (candidateVersion == kotlinVersion) best[key] = library
            compareVersions(candidateVersion, existing.name.split(':')[2]) > 0 -> best[key] = library
        }
    }
    val pinned = best.values.map { library ->
        if (library.isKotlinFirstParty()) {
            val parts = library.name.split(':')
            if (parts[2] != kotlinVersion) {
                try {
                    reResolve(parts[0], parts[1], kotlinVersion, library)
                } catch (e: Exception) {
                    library
                }
            } else library
        } else library
    }
    return (pinned + passthrough).toSet()
}
