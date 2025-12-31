package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.jvm.jarBuildBlocking
import com.ivieleague.kbuild.maven.MavenAether
import org.apache.maven.model.Model
import org.apache.maven.model.io.DefaultModelWriter
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File
import java.util.jar.Manifest

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
 * val config = kmpProject("mylib", File(".")) {
 *     jvm()
 *     js()
 *     nativeHost()
 * }
 *
 * kmpPublishAll(
 *     config = config,
 *     projectIdentifier = ProjectIdentifier("com.example", "mylib", Version(1, 0, 0)),
 *     repository = MavenAether.local
 * )
 * ```
 */
class KmpPublisher(
    val config: KmpProjectConfig,
    val projectIdentifier: ProjectIdentifier,
    val outputDir: File = config.buildDir.resolve("publish"),
    val pomConfigure: (Model) -> Unit = {}
) {
    private val publishDir = outputDir.resolve("maven")

    /**
     * Create a POM for an artifact.
     */
    private fun createPom(artifactId: String, packaging: String): File {
        val pomFile = publishDir.resolve("$artifactId.pom")
        pomFile.parentFile.mkdirs()

        val model = Model().apply {
            modelVersion = "4.0.0"
            groupId = projectIdentifier.group
            this.artifactId = artifactId
            version = projectIdentifier.version.toString()
            this.packaging = packaging
            pomConfigure(this)
        }

        DefaultModelWriter().write(pomFile, mapOf<String, Any>(), model)
        return pomFile
    }

    /**
     * Publish the JVM artifact.
     */
    fun publishJvm(repository: RemoteRepository = MavenAether.local): List<Artifact> {
        if (KmpTarget.Jvm !in config.targets) return emptyList()

        val artifactId = "${config.name}-jvm"
        val classesDir = kmpCompileJvmBlocking(config)

        // Create JAR
        val jarFile = publishDir.resolve("$artifactId.jar")
        jarBuildBlocking(
            manifest = Manifest(),
            folders = setOf(classesDir),
            output = jarFile
        )

        // Create sources JAR
        val sourcesFile = publishDir.resolve("$artifactId-sources.jar")
        jarBuildBlocking(
            manifest = Manifest(),
            folders = config.getSourcesForTarget(KmpTarget.Jvm),
            output = sourcesFile
        )

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
        if (config.targets.none { it is KmpTarget.Js || it == KmpTarget.Js }) return emptyList()

        val artifactId = "${config.name}-js"
        val klibFile = kmpCompileJsKlibBlocking(config)

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
        if (target !in config.targets) return emptyList()

        val artifactId = "${config.name}-${target.name.lowercase()}"
        val klibFile = kmpCompileNativeKlibBlocking(config, target)

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
        val artifactId = config.name

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
        if (KmpTarget.Jvm in config.targets) {
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
                "url": "../${config.name}-jvm/${projectIdentifier.version}/${config.name}-jvm-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${config.name}-jvm",
                "version": "${projectIdentifier.version}"
              }
            }
            """.trimIndent())
        }

        // JS variant
        if (config.targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
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
                "url": "../${config.name}-js/${projectIdentifier.version}/${config.name}-js-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${config.name}-js",
                "version": "${projectIdentifier.version}"
              }
            }
            """.trimIndent())
        }

        // Native variants
        for (target in config.targets.filterIsInstance<KmpTarget.Native>()) {
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
                "url": "../${config.name}-$targetName/${projectIdentifier.version}/${config.name}-$targetName-${projectIdentifier.version}.pom",
                "group": "${projectIdentifier.group}",
                "module": "${config.name}-$targetName",
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
    "module": "${config.name}",
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

        for (target in config.targets.filterIsInstance<KmpTarget.Native>()) {
            results[target.name] = publishNative(target, repository)
        }

        // Publish root metadata last
        results["metadata"] = publishMetadata(repository)

        return results
    }
}

/**
 * Publish all KMP artifacts to a repository.
 */
fun kmpPublishAll(
    config: KmpProjectConfig,
    projectIdentifier: ProjectIdentifier,
    repository: RemoteRepository = MavenAether.local,
    outputDir: File = config.buildDir.resolve("publish"),
    pomConfigure: (Model) -> Unit = {}
): Map<String, List<Artifact>> {
    return KmpPublisher(config, projectIdentifier, outputDir, pomConfigure).publishAll(repository)
}

/**
 * Create a publisher for a KMP project.
 */
fun KmpProjectConfig.publisher(
    projectIdentifier: ProjectIdentifier,
    outputDir: File = buildDir.resolve("publish"),
    pomConfigure: (Model) -> Unit = {}
): KmpPublisher = KmpPublisher(this, projectIdentifier, outputDir, pomConfigure)
