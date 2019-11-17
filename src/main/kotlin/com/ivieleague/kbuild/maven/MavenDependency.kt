package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.ProjectIdentifier
import org.apache.maven.model.Dependency
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Exclusion

data class MavenDependency(val dependency: Dependency) : () -> Set<Library> {
    override operator fun invoke(): Set<Library> {
        val main = org.eclipse.aether.graph.Dependency(
            DefaultArtifact(
                dependency.groupId,
                dependency.artifactId,
                dependency.classifier,
                dependency.version
            ),
            dependency.scope,
            dependency.isOptional,
            dependency.exclusions.map { Exclusion(it.groupId, it.artifactId, null, null) }
        )
        return MavenAether.libraries(listOf(main))
    }

    constructor(projectIdentifier: ProjectIdentifier) : this(
        Dependency(
            projectIdentifier.group,
            projectIdentifier.name,
            projectIdentifier.version.toString()
        )
    )
}

fun Dependency.producer() = MavenDependency(this)