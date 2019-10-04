package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.HasLibraries
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Module
import org.apache.maven.model.Model
import org.apache.maven.model.io.DefaultModelWriter
import java.io.File

interface HasMavenInformation : Module, HasLibraries {
    val mavenModel: Model

    val pomFile: File get() = root.resolve("out/pom.xml")

    fun buildPom(): File {
        DefaultModelWriter().write(pomFile.also { it.parentFile.mkdirs() }, mapOf(), mavenModel)
        return pomFile
    }

    override val libraries: Set<Library>
        get() = mavenModel.libraries()
}

/**
 * Creates a Maven [Model] populated with the group id, artifact id, and version.
 */
fun HasMavenInformation.defaultMavenModel(): Model {
    return Model().also {
        it.groupId = this.group
        it.artifactId = this.name
        it.version = this.version.toString()
    }
}