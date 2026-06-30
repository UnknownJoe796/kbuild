package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.DependencyScope
import com.ivieleague.kbuild.common.Library
import org.apache.maven.model.*
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Exclusion

// ---------------------------------------------------------------------------
// DependencyScope is now defined in com.ivieleague.kbuild.common.DependencyScope.
// It is re-exported here for backward compatibility so existing `import
// com.ivieleague.kbuild.maven.DependencyScope` lines in tests continue to compile.
// ---------------------------------------------------------------------------

fun Dependency(
    path: String,
    scope: DependencyScope = DependencyScope.Compile,
    type: String = "jar"
): Dependency {
    return Dependency().apply {
        this.groupId = path.substringBefore(':')
        this.artifactId = path.substringAfter(':').substringBefore(':')
        this.version = path.substringAfterLast(':')
        this.dependencyScope = scope
        this.type = type
    }
}

/**
 * Create a dependency for a .klib artifact (Kotlin Multiplatform library).
 */
fun KlibDependency(
    path: String,
    scope: DependencyScope = DependencyScope.Compile
): Dependency = Dependency(path, scope, type = "klib")

fun Dependency(
    groupId: String,
    artifactId: String,
    version: String,
    scope: DependencyScope = DependencyScope.Compile
): Dependency {
    return Dependency().apply {
        this.groupId = groupId
        this.artifactId = artifactId
        this.version = version
        this.dependencyScope = scope
    }
}

fun Repository(
    url: String,
    id: String = url.substringAfter("://").filter { it.isLetterOrDigit() },
    name: String = id
): Repository {
    return Repository().apply {
        this.url = url
        this.name = name
        this.id = id
    }
}

fun Organization(name: String, url: String): Organization = Organization().apply {
    this.name = name
    this.url = url
}

fun License(
    name: String,
    url: String,
    distribution: String? = null,
    comments: String? = null
): License = License().apply {
    this.name = name
    this.url = url
    this.distribution = distribution
    this.comments = comments
}

object Licenses {
    fun MIT() = License("MIT", "https://opensource.org/licenses/MIT")
    fun Apache2() = License("Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0")
}

fun Contributor(
    name: String,
    email: String,
    timezone: String? = null,
    roles: List<String> = listOf(),
    organization: Organization? = null
): Contributor = Contributor().apply {
    this.name = name
    this.email = email
    this.timezone = timezone
    this.roles = roles
    this.organization = organization?.name
    this.organizationUrl = organization?.url
}

/** Extension property mapping Maven Dependency's string scope to [DependencyScope]. */
var Dependency.dependencyScope: DependencyScope
    get() = DependencyScope[this.scope]
    set(value) {
        this.scope = value.toString()
    }

suspend fun Model.libraries(): Set<Library> {
    return MavenAether.libraries(
        dependencies = this@libraries.dependencies.map { it.aether() },
        repositories = listOf(MavenAether.central) + this@libraries.repositories.map { it.aether() }
    )
}

// ---------------------------------------------------------------------------
// Converters: kbuild Dependency → Maven/Aether types.
// ---------------------------------------------------------------------------

/**
 * Convert a kbuild [com.ivieleague.kbuild.common.Dependency] to an [org.apache.maven.model.Dependency]
 * for use in POM generation and Maven resolution internals.
 */
fun com.ivieleague.kbuild.common.Dependency.toMaven(): Dependency {
    return Dependency().apply {
        this.groupId = this@toMaven.groupId
        this.artifactId = this@toMaven.artifactId
        this.version = this@toMaven.version
        this.dependencyScope = this@toMaven.scope
    }
}

/**
 * Convert a kbuild [com.ivieleague.kbuild.common.Dependency] directly to an Aether dependency
 * for use in [MavenAether.libraries] calls.
 */
fun com.ivieleague.kbuild.common.Dependency.aether() = org.eclipse.aether.graph.Dependency(
    DefaultArtifact(groupId, artifactId, null, "jar", version),
    scope.toString(),
    false,
    emptyList<Exclusion>()
)
