package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.keychain.Keychain
import com.ivieleague.kbuild.keychain.KeychainMap
import com.ivieleague.kbuild.kotlin.Kotlin
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.junit.Test
import java.io.File

class PomBuildTest {
    val pomRepos = listOf(
        Repository("https://dl.bintray.com/lightningkite/com.lightningkite.kotlin", "BintrayLightningKiteKotlin")
    )
    val pomId = ProjectIdentifier("com.test:test:1.0.0")
    val pomDeps = listOf(
        Dependency(Kotlin.standardLibraryJvmId),
        Dependency("junit:junit:4.12", DependencyScope.Test)
    )
    val pomOrg = Organization("Ivie League", "https://ivieleague.com")
    val pomContributors =
        listOf(Contributor("Joseph Ivie", "josephivie@gmail.com", roles = listOf("Head"), organization = pomOrg))
    val pomBuild = PomBuild(
        projectIdentifier = pomId,
        pomFile = File("build/run/PomBuildTest/pom.xml"),
        configure = {
            repositories = pomRepos
            dependencies = pomDeps
            organization = pomOrg
            contributors = pomContributors
        }
    )

    init {
        Keychain = KeychainMap()
    }

    @Test
    fun build() {
        pomBuild()
        val direct = pomBuild.model
        val fromFile = pomBuild.pomFile.bufferedReader().use { MavenXpp3Reader().read(it) }
        assert(fromFile.repositories[0].name == pomRepos[0].name)
        assert(fromFile.repositories[0].url == pomRepos[0].url)
        assert(fromFile.groupId == pomId.group)
        assert(fromFile.artifactId == pomId.name)
        assert(fromFile.version == pomId.version.toString())
    }

    @Test
    fun dependencies() {
        val compileDeps = pomBuild.compileDependencies()
        assert(compileDeps.any { it.default.toString().contains("kotlin-stdlib") })
        assert(compileDeps.none { it.default.toString().contains("junit") })
        val testDeps = pomBuild.testCompileDependencies()
        assert(testDeps.any { it.default.toString().contains("kotlin-stdlib") })
        assert(testDeps.any { it.default.toString().contains("junit") })
    }
}

