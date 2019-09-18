package com.ivieleague.kbuild.maven

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File

interface MavenDeployable : HasMavenInformation {
    val defaultFile: File
    val sourcesFile: File? get() = null
    val documentationFile: File? get() = null

    fun getMavenArtifacts(): List<Artifact> {
        val defaultArtifact = defaultFile.let {
            DefaultArtifact(this.group, this.name, null, defaultFile.extension, this.version.toString()).setFile(it)
        }
        val sourcesArtifact = sourcesFile?.let {
            SubArtifact(defaultArtifact, "sources", it.extension, it)
        }
        val documentationArtifact = documentationFile?.let {
            SubArtifact(defaultArtifact, "javadoc", it.extension, it)
        }
        val pomArtifact = SubArtifact(defaultArtifact, null, "pom", buildPom())
        return listOfNotNull(defaultArtifact, pomArtifact, sourcesArtifact, documentationArtifact)
    }

    fun deploy(remoteRepository: RemoteRepository) {
        MavenAether.deploy(
            remoteRepository = remoteRepository,
            artifacts = getMavenArtifacts()
        )
    }
}

