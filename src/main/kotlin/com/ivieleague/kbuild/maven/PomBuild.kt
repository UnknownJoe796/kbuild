package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.ProjectIdentifier
import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.io.DefaultModelWriter
import java.io.File

class PomBuild(
    val projectIdentifier: ProjectIdentifier,
    val pomFile: File,
    val configure: Configurer<Model>
) : () -> File {

    val model = Model().also {
        it.groupId = projectIdentifier.group
        it.artifactId = projectIdentifier.name
        it.version = projectIdentifier.version.toString()
    }.also(configure)

    suspend fun dependencies(filter: (Dependency) -> Boolean): Set<Library> {
        return MavenAether.libraries(
            dependencies = model.dependencies.filter(filter).map { it.aether() },
            repositories = model.repositories.map { it.aether() } + MavenAether.defaultRepositories
        )
    }

    suspend fun compileDependencies(): Set<Library> = dependencies { it.dependencyScope.includeInCompilation() }
    suspend fun distributionDependencies(): Set<Library> = dependencies { it.dependencyScope.includeInDistribution() }
    suspend fun testCompileDependencies(): Set<Library> = dependencies { it.dependencyScope.includeInCompilation() || it.dependencyScope == DependencyScope.Test }
    suspend fun testExecutionDependencies(): Set<Library> = dependencies { it.dependencyScope.includeInDistribution() || it.dependencyScope == DependencyScope.Test }

    override fun invoke(): File {
        DefaultModelWriter().write(pomFile.also { it.parentFile.mkdirs() }, mapOf(), model)
        return pomFile
    }
}