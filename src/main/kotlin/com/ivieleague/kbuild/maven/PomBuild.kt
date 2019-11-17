package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Producer
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

    fun dependencies(filter: (Dependency) -> Boolean): Producer<Library> {
        return {
            MavenAether.libraries(
                dependencies = model.dependencies.filter(filter).map { it.aether() },
                repositories = model.repositories.map { it.aether() } + MavenAether.defaultRepositories
            )
        }
    }

    val compileDependencies: Producer<Library> get() = dependencies { it.dependencyScope.includeInCompilation() }
    val distributionDependencies: Producer<Library> get() = dependencies { it.dependencyScope.includeInDistribution() }
    val testCompileDependencies: Producer<Library> get() = dependencies { it.dependencyScope.includeInCompilation() || it.dependencyScope == DependencyScope.Test }
    val testExecutionDependencies: Producer<Library> get() = dependencies { it.dependencyScope.includeInDistribution() || it.dependencyScope == DependencyScope.Test }

    override fun invoke(): File {
        DefaultModelWriter().write(pomFile.also { it.parentFile.mkdirs() }, mapOf(), model)
        return pomFile
    }
}