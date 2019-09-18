package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Library
import org.apache.maven.model.*

enum class DependencyScope {
    Compile, Provided, Runtime, Test, System, Import;

    override fun toString(): String = this.name.toLowerCase()

    companion object {
        val reverseMap = DependencyScope.values().associate { it.name.toLowerCase() to it }
        operator fun get(string: String): DependencyScope = reverseMap[string] ?: Compile
    }
}

fun Dependency(path: String, scope: DependencyScope = DependencyScope.Compile): Dependency {
    return Dependency().apply {
        this.groupId = path.substringBefore(':')
        this.artifactId = path.substringAfter(':').substringBefore(':')
        this.version = path.substringAfterLast(':')
        this.dependencyScope = scope
    }
}

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

var Dependency.dependencyScope: DependencyScope
    get() = DependencyScope[this.scope]
    set(value) {
        this.scope = value.toString()
    }

fun Model.libraries(): List<Library> {
    return MavenAether.libraries(
        dependencies = this.dependencies.map { it.aether() },
        repositories = listOf(MavenAether.central) + this.repositories.map { it.aether() }
    )
}