package com.ivieleague.kbuild

import com.ivieleague.kbuild.common.Configurer
import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.maven.Dependency
import com.ivieleague.kbuild.maven.DependencyScope
import com.ivieleague.kbuild.templates.KotlinJvmLibraryTemplate
import org.apache.maven.model.Model

class SelfBuildTest {
    object Project : KotlinJvmLibraryTemplate() {
        override val projectIdentifier: ProjectIdentifier = ProjectIdentifier("com.ivieleague:kbuild:0.0.1")
        override val pomConfigure: Configurer<Model> = {
            repositories = listOf()
            dependencies = listOf(
                Dependency(Kotlin.standardLibraryJvmId),
                Dependency("junit:junit:4.12", DependencyScope.Test)
            )
        }
    }
}