package com.ivieleague.kbuild.maven

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File

class MavenDeploy(
    val pom: PomBuild,
    val default: () -> File,
    val sources: (() -> File)? = null,
    val documentation: (() -> File)? = null
) {

    fun artifacts(): List<Artifact> {
        val defaultArtifact = default().let {
            DefaultArtifact(
                pom.projectIdentifier.group,
                pom.projectIdentifier.name,
                null,
                it.extension,
                pom.projectIdentifier.version.toString()
            ).setFile(it)
        }
        val sourcesArtifact = sources?.invoke()?.let {
            SubArtifact(defaultArtifact, "sources", it.extension, it)
        }
        val documentationArtifact = documentation?.invoke()?.let {
            SubArtifact(defaultArtifact, "javadoc", it.extension, it)
        }
        val pomArtifact = SubArtifact(defaultArtifact, null, "pom", pom())
        return listOfNotNull(defaultArtifact, pomArtifact, sourcesArtifact, documentationArtifact)
    }

    fun deploy(to: RemoteRepository = MavenAether.local) {
        MavenAether.deploy(
            remoteRepository = to,
            artifacts = artifacts()
        )
    }
}