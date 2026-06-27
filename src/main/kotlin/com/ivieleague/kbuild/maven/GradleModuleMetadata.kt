package com.ivieleague.kbuild.maven

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gradle Module Metadata (`.module`) schema.
 *
 * Published alongside the POM by Gradle (and the Kotlin plugin), this file is what makes
 * variant-aware resolution of KMP libraries possible. The POM only describes a single JVM-shaped
 * artifact; the `.module` file enumerates every published variant (jvm/js/wasm/native-per-target)
 * with its attributes, files, and per-variant dependencies. A *root* KMP module carries no files
 * of its own — each platform variant only holds an `available-at` redirect to a per-target module
 * (e.g. `kotlinx-coroutines-core` → `kotlinx-coroutines-core-jvm`). Convention-based artifact-name
 * guessing cannot follow those redirects, which is why reading this file is required.
 *
 * Only the fields kbuild needs to drive resolution are modeled; unknown keys are ignored by the
 * reader's lenient [kotlinx.serialization.json.Json] instance.
 *
 * Spec: https://github.com/gradle/gradle/blob/master/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md
 */
@Serializable
data class GradleModuleMetadata(
    val formatVersion: String,
    val component: GmmComponent,
    val variants: List<GmmVariant> = emptyList()
)

/**
 * Identifies the module a `.module` file belongs to.
 *
 * Note: in a *per-target* module this is a back-reference to the *root* module (its `url`/coords
 * point back at the root, not at the per-target artifact). Therefore the per-target artifact's real
 * coordinates come from the root variant's [GmmVariant.availableAt], never from this component.
 */
@Serializable
data class GmmComponent(
    val group: String,
    val module: String,
    val version: String,
    val url: String? = null,
    val attributes: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class GmmVariant(
    val name: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
    val files: List<GmmFile> = emptyList(),
    val dependencies: List<GmmDependency> = emptyList(),
    val dependencyConstraints: List<GmmDependencyConstraint> = emptyList(),
    @SerialName("available-at") val availableAt: GmmAvailableAt? = null
) {
    /** Reads a string-valued attribute, or null if absent / not a string primitive. */
    fun attribute(key: String): String? = (attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
data class GmmFile(
    val name: String,
    val url: String,
    val size: Long? = null,
    val sha512: String? = null,
    val sha256: String? = null,
    val sha1: String? = null,
    val md5: String? = null
)

@Serializable
data class GmmDependency(
    val group: String,
    val module: String,
    val version: GmmVersionConstraint? = null,
    val attributes: Map<String, JsonElement> = emptyMap(),
    val endorseStrictVersions: Boolean = false
) {
    fun attribute(key: String): String? = (attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * A dependency's version requirement.
 *
 * kbuild currently honors only [requires] (the resolved version Gradle would pick when no other
 * constraint wins). TODO: implement the `strictly`/`rejects`/`prefers` version algebra and BOM
 * (platform) alignment for full Gradle parity — deferred deliberately; see [GmmDependency.attribute]
 * filtering of `org.gradle.category == "platform"` in the resolver.
 */
@Serializable
data class GmmVersionConstraint(
    val requires: String? = null,
    val strictly: String? = null,
    val prefers: String? = null,
    val rejects: List<String> = emptyList()
) {
    /** The version kbuild resolves with: prefer an exact requirement, then strictly, then prefers. */
    val resolved: String? get() = requires ?: strictly ?: prefers
}

@Serializable
data class GmmDependencyConstraint(
    val group: String,
    val module: String,
    val version: GmmVersionConstraint? = null
)

/**
 * Redirect from a root KMP variant to the per-target module that actually holds the artifact.
 *
 * Use [group]/[module]/[version] for the follow-up Aether fetch — NOT [url], which is a
 * repository-relative path to the `.module` file rather than artifact coordinates.
 */
@Serializable
data class GmmAvailableAt(
    val url: String,
    val group: String,
    val module: String,
    val version: String
)
