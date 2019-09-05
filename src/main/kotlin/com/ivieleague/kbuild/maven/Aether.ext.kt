package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.Keychain
import org.apache.maven.model.Dependency
import org.apache.maven.model.Repository
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Exclusion
import org.eclipse.aether.repository.Authentication
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.RepositoryPolicy
import org.eclipse.aether.util.repository.AuthenticationBuilder

fun Repository.aether() = RemoteRepository.Builder(id, "default", url)
    .setSnapshotPolicy(
        RepositoryPolicy(
            snapshots.isEnabled,
            snapshots.updatePolicy,
            snapshots.checksumPolicy
        )
    )
    .setReleasePolicy(
        RepositoryPolicy(
            releases.isEnabled,
            releases.updatePolicy,
            releases.checksumPolicy
        )
    )
    .setAuthentication(Keychain.aether(id))
    .build()

fun Keychain.aether(repository: String): Authentication? {
    val user = this["maven:$repository:username"] ?: return null
    return this["maven:$repository:password"]?.let { pass ->
        AuthenticationBuilder().addUsername(user).addPassword(pass).build()
    }
}

fun Keychain.aetherOrPrompt(repository: String): Authentication {
    val user = this.getOrPrompt("maven:$repository:username")
    val pass = this.getOrPrompt("maven:$repository:password")
    return AuthenticationBuilder().addUsername(user).addPassword(pass).build()
}

fun RemoteRepository.authenticated(): RemoteRepository {
    return RemoteRepository.Builder(id, "default", url)
        .setSnapshotPolicy(this.getPolicy(true))
        .setReleasePolicy(this.getPolicy(false))
        .setAuthentication(Keychain.aetherOrPrompt(id))
        .build()
}

fun Dependency.aether() = org.eclipse.aether.graph.Dependency(
    DefaultArtifact(
        groupId,
        artifactId,
        classifier,
        type,
        version
    ),
    scope,
    isOptional,
    exclusions.map { Exclusion(it.groupId, it.artifactId, null, null) }
)