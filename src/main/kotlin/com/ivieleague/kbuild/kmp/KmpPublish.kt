package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.jvm.jarBuild
import com.ivieleague.kbuild.maven.MavenAether
import com.lightningkite.reactive.core.Constant
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
 * All publish methods are suspend functions that:
 * - Accept optional pre-compiled artifacts for pluggability
 * - Default to calling suspend compile functions for reactivity
 *
 * Example with default compilation:
 * ```
 * val publisher = config.publisher(projectId)
 * publisher.publishAll()  // Uses internal suspend compilation
 * ```
 *
 * Example with custom compilation:
 * ```
 * val myClassesDir = myCustomCompile()  // Your own compilation
 * publisher.publishJvm(classesDir = myClassesDir)
 * ```
 */
class KmpPublisher(
    val config: KmpProjectConfig,
    val projectIdentifier: ProjectIdentifier,
    val outputDir: File = config.buildDir.resolve("publish"),
    val pomConfigure: (Model) -> Unit = {},
    /**
     * Optional custom JVM compile function. If null, uses kmpCompileJvm.
     */
    val compileJvm: (suspend () -> File)? = null,
    /**
     * Optional custom JS compile function. If null, uses kmpCompileJsKlib.
     */
    val compileJs: (suspend () -> File)? = null,
    /**
     * Optional custom Native compile function. If null, uses kmpCompileNativeKlib.
     */
    val compileNative: (suspend (KmpTarget.Native) -> File)? = null
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
     *
     * @param classesDir Pre-compiled classes directory. If null, compiles using compileJvm or kmpCompileJvm.
     * @param repository Target repository (defaults to local Maven)
     */
    suspend fun publishJvm(
        classesDir: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (KmpTarget.Jvm !in config.targets) return emptyList()

        val artifactId = "${config.name}-jvm"

        // Use provided classes, custom compile function, or default suspend compile
        val compiledClasses = classesDir
            ?: compileJvm?.invoke()
            ?: kmpCompileJvm(config)

        // Create JAR
        val jarFile = publishDir.resolve("$artifactId.jar")
        jarBuild(
            manifest = Manifest(),
            folders = Constant(setOf(compiledClasses)),
            output = jarFile
        )

        // Create sources JAR
        val sourcesFile = publishDir.resolve("$artifactId-sources.jar")
        jarBuild(
            manifest = Manifest(),
            folders = Constant(config.getSourcesForTarget(KmpTarget.Jvm)),
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
     *
     * @param klibFile Pre-compiled KLIB file. If null, compiles using compileJs or kmpCompileJsKlib.
     * @param repository Target repository (defaults to local Maven)
     */
    suspend fun publishJs(
        klibFile: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (config.targets.none { it is KmpTarget.Js || it == KmpTarget.Js }) return emptyList()

        val artifactId = "${config.name}-js"

        // Use provided klib, custom compile function, or default suspend compile
        val compiledKlib = klibFile
            ?: compileJs?.invoke()
            ?: kmpCompileJsKlib(config)

        // Create POM
        val pomFile = createPom(artifactId, "klib")

        // Build artifacts
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "klib",
            projectIdentifier.version.toString()
        ).setFile(compiledKlib)

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
     *
     * @param target Native target to publish
     * @param klibFile Pre-compiled KLIB file. If null, compiles using compileNative or kmpCompileNativeKlib.
     * @param repository Target repository (defaults to local Maven)
     */
    suspend fun publishNative(
        target: KmpTarget.Native,
        klibFile: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (target !in config.targets) return emptyList()

        val artifactId = "${config.name}-${target.name.lowercase()}"

        // Use provided klib, custom compile function, or default suspend compile
        val compiledKlib = klibFile
            ?: compileNative?.invoke(target)
            ?: kmpCompileNativeKlib(config, target)

        // Create POM
        val pomFile = createPom(artifactId, "klib")

        // Build artifacts
        val mainArtifact = DefaultArtifact(
            projectIdentifier.group,
            artifactId,
            null,
            "klib",
            projectIdentifier.version.toString()
        ).setFile(compiledKlib)

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
    suspend fun publishMetadata(repository: RemoteRepository = MavenAether.local): List<Artifact> {
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
    suspend fun publishAll(repository: RemoteRepository = MavenAether.local): Map<String, List<Artifact>> {
        val results = mutableMapOf<String, List<Artifact>>()

        // Publish platform-specific artifacts first
        results["jvm"] = publishJvm(repository = repository)
        results["js"] = publishJs(repository = repository)

        for (target in config.targets.filterIsInstance<KmpTarget.Native>()) {
            results[target.name] = publishNative(target, repository = repository)
        }

        // Publish root metadata last
        results["metadata"] = publishMetadata(repository)

        return results
    }
}

/**
 * Publish all KMP artifacts to a repository.
 */
suspend fun kmpPublishAll(
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
    pomConfigure: (Model) -> Unit = {},
    compileJvm: (suspend () -> File)? = null,
    compileJs: (suspend () -> File)? = null,
    compileNative: (suspend (KmpTarget.Native) -> File)? = null
): KmpPublisher = KmpPublisher(
    this,
    projectIdentifier,
    outputDir,
    pomConfigure,
    compileJvm,
    compileJs,
    compileNative
)
