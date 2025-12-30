package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.jvm.JarBuild
import com.ivieleague.kbuild.jvm.Manifest
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.PomBuild
import org.apache.maven.model.Model
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File

/**
 * Publishes a Kotlin Multiplatform project to Maven repositories.
 *
 * KMP projects publish multiple artifacts:
 * - Root artifact with Gradle Module Metadata
 * - JVM artifact: {name}-jvm.jar
 * - JS artifact: {name}-js.klib
 * - Native artifacts: {name}-{target}.klib (e.g., mylib-linuxx64.klib)
 *
 * Example:
 * ```
 * val publish = KmpPublish(
 *     project = myKmpProject,
 *     projectIdentifier = ProjectIdentifier("com.example", "mylib", Version(1, 0, 0)),
 *     outputDir = File("build/publish")
 * )
 *
 * publish.publishAll(MavenAether.local)
 * ```
 */
class KmpPublish(
    val project: KmpProject,
    val projectIdentifier: ProjectIdentifier,
    val outputDir: File,
    val pomConfigure: (Model) -> Unit = {}
) {
    private val publishDir = outputDir.resolve("maven")

    /**
     * Create a POM for an artifact.
     */
    private fun createPom(artifactId: String, packaging: String): File {
        val pomFile = publishDir.resolve("$artifactId.pom")
        val model = Model().apply {
            groupId = projectIdentifier.group
            this.artifactId = artifactId
            version = projectIdentifier.version.toString()
            this.packaging = packaging
            pomConfigure(this)
        }
        org.apache.maven.model.io.DefaultModelWriter().write(
            pomFile.also { it.parentFile.mkdirs() },
            mapOf(),
            model
        )
        return pomFile
    }

    /**
     * Publish the JVM artifact.
     */
    fun publishJvm(repository: RemoteRepository = MavenAether.local): List<Artifact> {
        if (KmpTarget.Jvm !in project.targets) return emptyList()

        val artifactId = "${project.name}-jvm"
        val classesDir = project.buildJvm() ?: return emptyList()

        // Create JAR
        val jarFile = publishDir.resolve("$artifactId.jar")
        val jarBuild = JarBuild(
            manifest = Manifest(),
            folders = { setOf(classesDir) },
            output = jarFile
        )
        jarBuild()

        // Create sources JAR
        val sourcesFile = publishDir.resolve("$artifactId-sources.jar")
        val sourcesJar = JarBuild(
            manifest = Manifest(),
            folders = { project.getSourcesForTarget(KmpTarget.Jvm) },
            output = sourcesFile
        )
        sourcesJar()

        // Create POM
        val pomFile = createPom(artifactId, "jar")

        // Build artifacts
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "jar",
            projectIdentifier.version.toString()
        ).setFile(jarFile)

        val artifacts = listOf(
            mainArtifact,
            SubArtifact(mainArtifact, null, "pom", pomFile),
            SubArtifact(mainArtifact, "sources", "jar", sourcesFile)
        )

        MavenAether.deploy(repository, artifacts)
        println("Published $artifactId to $repository")

        return artifacts
    }

    /**
     * Publish the JS artifact.
     */
    fun publishJs(repository: RemoteRepository = MavenAether.local): List<Artifact> {
        if (project.targets.none { it is KmpTarget.Js || it == KmpTarget.Js }) return emptyList()

        val artifactId = "${project.name}-js"
        val klibFile = project.buildJs() ?: return emptyList()

        // Create POM
        val pomFile = createPom(artifactId, "klib")

        // Build artifacts
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "klib",
            projectIdentifier.version.toString()
        ).setFile(klibFile)

        val artifacts = listOf(
            mainArtifact,
            SubArtifact(mainArtifact, null, "pom", pomFile)
        )

        MavenAether.deploy(repository, artifacts)
        println("Published $artifactId to $repository")

        return artifacts
    }

    /**
     * Publish a native artifact.
     */
    fun publishNative(
        target: KmpTarget.Native,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (target !in project.targets) return emptyList()

        val artifactId = "${project.name}-${target.name.lowercase()}"
        val klibFile = project.buildNative(target)

        // Create POM
        val pomFile = createPom(artifactId, "klib")

        // Build artifacts
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "klib",
            projectIdentifier.version.toString()
        ).setFile(klibFile)

        val artifacts = listOf(
            mainArtifact,
            SubArtifact(mainArtifact, null, "pom", pomFile)
        )

        MavenAether.deploy(repository, artifacts)
        println("Published $artifactId to $repository")

        return artifacts
    }

    /**
     * Publish the root/metadata artifact with Gradle Module Metadata.
     */
    fun publishMetadata(repository: RemoteRepository = MavenAether.local): List<Artifact> {
        val artifactId = project.name

        // Create module.json (Gradle Module Metadata)
        val moduleFile = publishDir.resolve("$artifactId.module")
        moduleFile.parentFile.mkdirs()
        moduleFile.writeText(generateGradleModuleMetadata())

        // Create POM (no packaging, just metadata)
        val pomFile = createPom(artifactId, "pom")

        // Build artifacts - use pom as the main artifact type
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "pom",
            projectIdentifier.version.toString()
        ).setFile(pomFile)

        val artifacts = listOf(
            mainArtifact,
            SubArtifact(mainArtifact, null, "module", moduleFile)
        )

        MavenAether.deploy(repository, artifacts)
        println("Published $artifactId metadata to $repository")

        return artifacts
    }

    /**
     * Generate Gradle Module Metadata (module.json).
     *
     * This allows Gradle to properly resolve the correct variant for each platform.
     */
    private fun generateGradleModuleMetadata(): String {
        val variants = mutableListOf<String>()

        // JVM variant
        if (KmpTarget.Jvm in project.targets) {
            variants.add("""
            {
              "name": "jvmApiElements",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.jvm.version": 17,
                "org.gradle.libraryelements": "jar",
                "org.gradle.usage": "java-api",
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../${project.name}-jvm/${projectIdentifier.version}/${project.name}-jvm-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${project.name}-jvm",
                "version": "${projectIdentifier.version}"
              }
            }
            """.trimIndent())
        }

        // JS variant
        if (project.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            variants.add("""
            {
              "name": "jsApiElements",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.js.compiler": "ir",
                "org.jetbrains.kotlin.platform.type": "js"
              },
              "available-at": {
                "url": "../${project.name}-js/${projectIdentifier.version}/${project.name}-js-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${project.name}-js",
                "version": "${projectIdentifier.version}"
              }
            }
            """.trimIndent())
        }

        // Native variants
        for (target in project.targets.filterIsInstance<KmpTarget.Native>()) {
            val targetName = target.name.lowercase()
            val konanTarget = target.konanTarget.targetName
            variants.add("""
            {
              "name": "${targetName}ApiElements",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "$konanTarget",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../${project.name}-$targetName/${projectIdentifier.version}/${project.name}-$targetName-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${project.name}-$targetName",
                "version": "${projectIdentifier.version}"
              }
            }
            """.trimIndent())
        }

        return """
{
  "formatVersion": "1.1",
  "component": {
    "group": "${projectIdentifier.group}",
    "module": "${project.name}",
    "version": "${projectIdentifier.version}",
    "attributes": {
      "org.gradle.status": "release"
    }
  },
  "createdBy": {
    "kbuild": {
      "version": "1.0.0"
    }
  },
  "variants": [
    ${variants.joinToString(",\n    ")}
  ]
}
        """.trimIndent()
    }

    /**
     * Publish all artifacts to the repository.
     */
    fun publishAll(repository: RemoteRepository = MavenAether.local): Map<String, List<Artifact>> {
        val results = mutableMapOf<String, List<Artifact>>()

        // Publish platform-specific artifacts first
        results["jvm"] = publishJvm(repository)
        results["js"] = publishJs(repository)

        for (target in project.targets.filterIsInstance<KmpTarget.Native>()) {
            results[target.name] = publishNative(target, repository)
        }

        // Publish root metadata last
        results["metadata"] = publishMetadata(repository)

        return results
    }
}

/**
 * DSL for creating a KMP publish configuration.
 */
fun KmpProject.publish(
    projectIdentifier: ProjectIdentifier,
    outputDir: File = buildDir.resolve("publish"),
    pomConfigure: (Model) -> Unit = {}
): KmpPublish = KmpPublish(
    project = this,
    projectIdentifier = projectIdentifier,
    outputDir = outputDir,
    pomConfigure = pomConfigure
)
