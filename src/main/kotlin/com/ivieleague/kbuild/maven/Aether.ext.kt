package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.Keychain
import org.apache.maven.model.Dependency
import org.apache.maven.model.Repository
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Exclusion
import org.eclipse.aether.repository.Authentication
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.RepositoryPolicy

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
//    val parts = this["maven:$repository"] ?: return null
//    return when(parts.size){
//        0 -> null
//        1 -> ,
//        2 -> AuthenticationBuilder().addSecret(parts[0], parts[1]).build()
//        else -> null
//    }
//    return this["maven:$repository:secret"]?.takeIf { it.size == 2 }?.let {
//        AuthenticationBuilder().addSecret(it[0], it[1]).build()
//    } ?: this["maven:$repository:normal"]?.takeIf { it.size == 2 }?.let {
//        AuthenticationBuilder().addUsername(it[0]).addPassword(it[1]).build()
//    } ?:
    return null
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