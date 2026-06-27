package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.jvm.Jar
import com.ivieleague.kbuild.jvm.jarBuild
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.maven.GpgSigner
import com.ivieleague.kbuild.maven.MavenAether
import com.lightningkite.reactive.core.Constant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.io.DefaultModelWriter
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File
import java.security.MessageDigest
import java.util.jar.Manifest

/**
 * Publishes a Kotlin Multiplatform project to Maven repositories with a layout that is
 * byte-compatible with the Gradle Kotlin Multiplatform plugin's output, so a Gradle (or Maven)
 * consumer can resolve a kbuild-published multiplatform library.
 *
 * A complete KMP publication is a *root* coordinate plus one coordinate per target:
 * - The root (`<name>`) carries the commonMain metadata klib, common sources, the Gradle Module
 *   Metadata (`.module`) that redirects consumers to each target, the POM, and
 *   `kotlin-tooling-metadata.json`.
 * - Each target (`<name>-jvm`, `<name>-js`, `<name>-<native>`) carries its platform artifact, its
 *   own `.module` (with file checksums and dependencies), sources, and POM. Native targets also
 *   carry an (empty) `-metadata.jar` for their metadata variant.
 *
 * Every published file is GPG-signed (`.asc`) when a [signer] is provided — Maven Central requires
 * signatures, and Gradle signs by default.
 */
class KmpPublisher(
    val config: KmpProjectConfig,
    val projectIdentifier: ProjectIdentifier,
    val outputDir: File = config.buildDir.resolve("publish"),
    val pomConfigure: (Model) -> Unit = {},
    /** When non-null, every deployed artifact is GPG-signed and the `.asc` files are deployed too. */
    val signer: GpgSigner? = null,
    val compileJvm: (suspend () -> File)? = null,
    val compileJs: (suspend () -> File)? = null,
    val compileNative: (suspend (KmpTarget.Native) -> File)? = null
) {
    private val publishDir = outputDir.resolve("maven")
    private val group = projectIdentifier.group
    private val rootName = config.name
    private val version = projectIdentifier.version.toString()

    /** Gradle marks SNAPSHOT components as "integration" and final releases as "release". */
    private val gradleStatus = if (version.endsWith("SNAPSHOT")) "integration" else "release"

    /**
     * The full set of declared *shared* (intermediate, target-less) main source sets for the enabled
     * targets — commonMain plus any intermediates like nativeMain/appleMain/iosMain. This is derived
     * from the declared source-set hierarchy, NOT from which source sets have files on disk, so the
     * published project-structure metadata describes the same hierarchy Gradle does even when an
     * intermediate is empty. Sorted by name to match Gradle's ordering.
     */
    private val sharedSourceSets: List<SourceSet> =
        config.sourceSets.getMainSourceSets().filter { it.targets.isEmpty() }.sortedBy { it.name }

    /** Module-metadata dependencies always reference each dependency's *root* coordinate. */
    private val moduleDependencies: List<Map<String, Any>> = config.commonDependencies
        .sortedBy { "${it.groupId}:${it.artifactId}" }
        .map {
            linkedMapOf(
                "group" to it.groupId,
                "module" to it.artifactId,
                "version" to linkedMapOf<String, Any>("requires" to it.version)
            )
        }

    // ============== Per-target publishing ==============

    suspend fun publishJvm(
        classesDir: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (KmpTarget.Jvm !in config.targets) return emptyList()
        val artifactId = "$rootName-jvm"

        val compiledClasses = classesDir ?: compileJvm?.invoke() ?: kmpCompileJvm(config)
        val jarFile = jar(artifactId, Constant(setOf(compiledClasses)))
        val sourcesFile = sourcesJar(artifactId, config.getSourcesForTarget(KmpTarget.Jvm))

        val module = targetModuleFile(artifactId, "jar", jvmVariantSpecs(), mapOf(FileKind.MAIN to jarFile, FileKind.SOURCES to sourcesFile))
        val pomFile = createPom(artifactId, "jar", targetPomDependencies(KmpTarget.Jvm))

        return deployTarget(artifactId, "jar", jarFile, sourcesFile, null, module, pomFile, repository)
    }

    suspend fun publishJs(
        klibFile: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (config.targets.none { it is KmpTarget.Js }) return emptyList()
        val artifactId = "$rootName-js"

        val compiledKlib = klibFile ?: compileJs?.invoke() ?: kmpCompileJsKlib(config)
        val sourcesFile = sourcesJar(artifactId, config.getSourcesForTarget(KmpTarget.Js))

        val module = targetModuleFile(artifactId, "klib", jsVariantSpecs(), mapOf(FileKind.MAIN to compiledKlib, FileKind.SOURCES to sourcesFile))
        val pomFile = createPom(artifactId, "klib", targetPomDependencies(KmpTarget.Js))

        return deployTarget(artifactId, "klib", compiledKlib, sourcesFile, null, module, pomFile, repository)
    }

    suspend fun publishNative(
        target: KmpTarget.Native,
        klibFile: File? = null,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        if (target !in config.targets) return emptyList()
        val artifactId = "$rootName-${target.name.lowercase()}"

        val compiledKlib = klibFile ?: compileNative?.invoke(target) ?: kmpCompileNativeKlib(config, target)
        val sourcesFile = sourcesJar(artifactId, config.getSourcesForTarget(target))
        // Native targets publish a (near-empty) metadata jar for their metadata variant; Gradle
        // emits one even when there is no host-specific metadata to carry.
        val metadataFile = emptyMetadataJar(artifactId)

        val module = targetModuleFile(
            artifactId,
            "klib",
            nativeVariantSpecs(target),
            mapOf(FileKind.MAIN to compiledKlib, FileKind.SOURCES to sourcesFile, FileKind.METADATA to metadataFile)
        )
        val pomFile = createPom(artifactId, "klib", targetPomDependencies(target))

        return deployTarget(artifactId, "klib", compiledKlib, sourcesFile, metadataFile, module, pomFile, repository)
    }

    /** Deploy a target coordinate: main artifact, sources, optional native metadata jar, module, pom. */
    private fun deployTarget(
        artifactId: String,
        mainExtension: String,
        mainFile: File,
        sourcesFile: File,
        metadataFile: File?,
        moduleFile: File,
        pomFile: File,
        repository: RemoteRepository
    ): List<Artifact> {
        val main = DefaultArtifact(group, artifactId, null, mainExtension, version).setFile(mainFile)
        val artifacts = buildList {
            add(main)
            add(SubArtifact(main, null, "pom", pomFile))
            add(SubArtifact(main, null, "module", moduleFile))
            add(SubArtifact(main, "sources", "jar", sourcesFile))
            if (metadataFile != null) add(SubArtifact(main, "metadata", "jar", metadataFile))
        }
        deploy(repository, artifacts)
        println("Published $artifactId to $repository")
        return artifacts
    }

    // ============== Root (metadata) publishing ==============

    /**
     * Publish the root coordinate: commonMain metadata klib, common sources, Gradle Module
     * Metadata, POM, and kotlin-tooling-metadata.json.
     *
     * @param metadataKlibs Compiled shared-source-set klib directories (see [kmpCompileMetadata]).
     */
    suspend fun publishMetadata(
        metadataKlibs: Map<String, File>,
        repository: RemoteRepository = MavenAether.local
    ): List<Artifact> {
        val metadataJar = buildRootMetadataJar(metadataKlibs)
        val sourcesFile = sourcesJar(rootName, config.sourceSets.commonMain.sourceDirectories.filter { it.exists() }.toSet())
        val toolingFile = writeFile("$rootName-tooling.json", generateKotlinToolingMetadata())
        val moduleFile = writeFile("$rootName.module", generateRootModule(metadataJar, sourcesFile))
        val pomFile = createPom(rootName, "jar", rootPomDependencies())

        val main = DefaultArtifact(group, rootName, null, "jar", version).setFile(metadataJar)
        val artifacts = listOf(
            main,
            SubArtifact(main, null, "pom", pomFile),
            SubArtifact(main, null, "module", moduleFile),
            SubArtifact(main, "sources", "jar", sourcesFile),
            SubArtifact(main, "kotlin-tooling-metadata", "json", toolingFile)
        )
        deploy(repository, artifacts)
        println("Published $rootName metadata to $repository")
        return artifacts
    }

    suspend fun publishAll(repository: RemoteRepository = MavenAether.local): Map<String, List<Artifact>> = coroutineScope {
        val hasJvm = KmpTarget.Jvm in config.targets
        val hasJs = config.targets.any { it is KmpTarget.Js }

        // Resolve the platform classpaths up front and install the native distribution once
        // (MavenAether's Aether session is not safe for concurrent use). The per-target compiles
        // below are pre-resolved, so during the fan-out only the metadata compile touches the
        // resolver — keeping it the single resolver user.
        val jvmClasspath = if (hasJvm) config.dependencies.resolveJvmClasspath() else null
        val jsLibraries = if (hasJs) config.dependencies.resolveJsLibraries() else null
        val nativeCompilers = kmpNativeLibraryCompilers(config)

        // Compile every target concurrently. The mechanisms do not conflict: JVM goes to the
        // out-of-process Kotlin daemon, JS and the metadata compile use the in-process compiler
        // (serialized against each other by InProcessCompileLock), and natives are konanc
        // subprocesses — so they overlap freely.
        val jvmCompiled = jvmClasspath?.let { cp -> async(Dispatchers.IO) { kmpCompileJvmBlocking(config, cp) } }
        val jsCompiled = jsLibraries?.let { libs -> async(Dispatchers.IO) { kmpCompileJsKlibBlocking(config, libs) } }
        val nativeCompiled = nativeCompilers.mapValues { (_, compiler) -> async(Dispatchers.IO) { compiler.invoke() } }
        val metadataCompiled = async(Dispatchers.IO) { kmpCompileMetadata(config) }

        // Package and deploy sequentially (signing and Aether deploy are not parallelized).
        val results = mutableMapOf<String, List<Artifact>>()
        if (jvmCompiled != null) results["jvm"] = publishJvm(classesDir = jvmCompiled.await(), repository = repository)
        if (jsCompiled != null) results["js"] = publishJs(klibFile = jsCompiled.await(), repository = repository)
        for ((target, deferred) in nativeCompiled) {
            results[target.name] = publishNative(target, klibFile = deferred.await(), repository = repository)
        }
        results["metadata"] = publishMetadata(metadataCompiled.await(), repository)
        results
    }

    // ============== Artifact building ==============

    private suspend fun jar(artifactId: String, folders: com.lightningkite.reactive.core.Reactive<Set<File>>): File =
        jarBuild(manifest = Manifest(), folders = folders, output = publishDir.resolve("$artifactId.jar"))

    private suspend fun sourcesJar(artifactId: String, sourceDirs: Set<File>): File =
        jarBuild(manifest = Manifest(), folders = Constant(sourceDirs), output = publishDir.resolve("$artifactId-sources.jar"))

    /** An empty jar (just a manifest) — matches Gradle's native metadata jar. */
    private suspend fun emptyMetadataJar(artifactId: String): File {
        val staging = publishDir.resolve("$artifactId-metadata-staging").apply { mkdirs() }
        return jarBuild(manifest = Manifest(), folders = Constant(setOf(staging)), output = publishDir.resolve("$artifactId-metadata.jar"))
    }

    /**
     * Build the root metadata jar: each shared source set's compiled klib placed under its source
     * set name (e.g. `commonMain/default/...`) plus `META-INF/kotlin-project-structure-metadata.json`.
     * This is the layout a multiplatform consumer's metadata compilation reads.
     */
    private suspend fun buildRootMetadataJar(metadataKlibs: Map<String, File>): File = withContext(Dispatchers.IO) {
        val staging = publishDir.resolve("$rootName-metadata-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        for ((sourceSetName, klibDir) in metadataKlibs) {
            klibDir.copyRecursively(staging.resolve(sourceSetName), overwrite = true)
        }
        // The manifest and the project-structure metadata both live under META-INF. We pack the jar
        // from the staging tree directly (rather than via jarBuild, whose manifest handling reserves
        // META-INF/ and would drop our sibling META-INF entries).
        staging.resolve("META-INF").mkdirs()
        staging.resolve("META-INF/MANIFEST.MF").writeText("Manifest-Version: 1.0\r\n\r\n")
        staging.resolve("META-INF/kotlin-project-structure-metadata.json")
            .writeText(generateProjectStructureMetadata(kmpDependencySourceSets(config)))
        val output = publishDir.resolve("$rootName.jar")
        output.parentFile.mkdirs()
        Jar.from(output, staging)
        output
    }

    private fun writeFile(name: String, content: String): File =
        publishDir.resolve(name).apply { parentFile.mkdirs(); writeText(content) }

    private fun createPom(artifactId: String, packaging: String, dependencies: List<Dependency>): File {
        val pomFile = publishDir.resolve("$artifactId.pom")
        pomFile.parentFile.mkdirs()
        val model = Model().apply {
            modelVersion = "4.0.0"
            groupId = group
            this.artifactId = artifactId
            version = this@KmpPublisher.version
            this.packaging = packaging
            this.dependencies = dependencies
            pomConfigure(this)
        }
        DefaultModelWriter().write(pomFile, mapOf<String, Any>(), model)
        return pomFile
    }

    // ============== POM dependencies ==============

    private fun rootPomDependencies(): List<Dependency> = config.commonDependencies.map {
        Dependency().apply {
            groupId = it.groupId; artifactId = it.artifactId; version = it.version; scope = "runtime"
        }
    }

    private fun targetPomDependencies(target: KmpTarget): List<Dependency> = config.commonDependencies.map {
        Dependency().apply {
            groupId = it.groupId; artifactId = platformArtifactId(it, target); version = it.version; scope = "compile"
        }
    }

    /**
     * The platform-published artifactId for a dependency. Most KMP libraries append a target suffix
     * (e.g. `-jvm`, `-js`, `-iosarm64`), but kotlin-stdlib publishes its JVM and Native variants at
     * the root coordinate (only its JS variant carries a suffix).
     */
    private fun platformArtifactId(dep: KmpDependency, target: KmpTarget): String {
        if (dep.artifactId == "kotlin-stdlib") {
            return if (target is KmpTarget.Js) "kotlin-stdlib-js" else "kotlin-stdlib"
        }
        return dep.artifactIdForTarget(target)
    }

    // ============== Module metadata ==============

    private enum class FileKind { MAIN, SOURCES, METADATA }

    /** A variant of a target coordinate: its Gradle name, attributes, which file it carries, and whether it lists dependencies. */
    private class VariantSpec(
        val name: String,
        val attributes: Map<String, Any>,
        val fileKind: FileKind,
        val includeDependencies: Boolean
    )

    private fun jvmVariantSpecs() = listOf(
        VariantSpec("jvmApiElements-published", jvmAttributes("java-api"), FileKind.MAIN, true),
        VariantSpec("jvmRuntimeElements-published", jvmAttributes("java-runtime"), FileKind.MAIN, true),
        VariantSpec("jvmSourcesElements-published", jvmSourcesAttributes(), FileKind.SOURCES, false)
    )

    private fun jsVariantSpecs() = listOf(
        VariantSpec("jsApiElements-published", jsAttributes("kotlin-api"), FileKind.MAIN, true),
        VariantSpec("jsRuntimeElements-published", jsAttributes("kotlin-runtime"), FileKind.MAIN, true),
        VariantSpec("jsSourcesElements-published", jsSourcesAttributes(), FileKind.SOURCES, false)
    )

    private fun nativeVariantSpecs(target: KmpTarget.Native) = listOf(
        VariantSpec("${target.name}ApiElements-published", nativeAttributes(target, "kotlin-api", klib = true), FileKind.MAIN, true),
        VariantSpec("${target.name}SourcesElements-published", nativeSourcesAttributes(target), FileKind.SOURCES, false),
        VariantSpec("${target.name}MetadataElements-published", nativeAttributes(target, "kotlin-metadata", klib = true), FileKind.METADATA, true)
    )

    private fun jvmAttributes(usage: String) = linkedMapOf<String, Any>(
        "org.gradle.category" to "library",
        "org.gradle.jvm.environment" to "standard-jvm",
        "org.gradle.libraryelements" to "jar",
        "org.gradle.usage" to usage,
        "org.jetbrains.kotlin.platform.type" to "jvm"
    )

    private fun jvmSourcesAttributes() = linkedMapOf<String, Any>(
        "org.gradle.category" to "documentation",
        "org.gradle.dependency.bundling" to "external",
        "org.gradle.docstype" to "sources",
        "org.gradle.jvm.environment" to "standard-jvm",
        "org.gradle.libraryelements" to "jar",
        "org.gradle.usage" to "java-runtime",
        "org.jetbrains.kotlin.platform.type" to "jvm"
    )

    private fun jsAttributes(usage: String) = linkedMapOf<String, Any>(
        "org.gradle.category" to "library",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to usage,
        "org.jetbrains.kotlin.js.compiler" to "ir",
        "org.jetbrains.kotlin.platform.type" to "js"
    )

    private fun jsSourcesAttributes() = linkedMapOf<String, Any>(
        "org.gradle.category" to "documentation",
        "org.gradle.dependency.bundling" to "external",
        "org.gradle.docstype" to "sources",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to "kotlin-runtime",
        "org.jetbrains.kotlin.js.compiler" to "ir",
        "org.jetbrains.kotlin.platform.type" to "js"
    )

    private fun nativeAttributes(target: KmpTarget.Native, usage: String, klib: Boolean) = linkedMapOf<String, Any>().apply {
        if (klib) put("artifactType", "org.jetbrains.kotlin.klib")
        put("org.gradle.category", "library")
        put("org.gradle.jvm.environment", "non-jvm")
        put("org.gradle.usage", usage)
        put("org.jetbrains.kotlin.native.target", target.konanTarget.targetName)
        put("org.jetbrains.kotlin.platform.type", "native")
    }

    private fun nativeSourcesAttributes(target: KmpTarget.Native) = linkedMapOf<String, Any>(
        "org.gradle.category" to "documentation",
        "org.gradle.dependency.bundling" to "external",
        "org.gradle.docstype" to "sources",
        "org.gradle.jvm.environment" to "non-jvm",
        "org.gradle.usage" to "kotlin-runtime",
        "org.jetbrains.kotlin.native.target" to target.konanTarget.targetName,
        "org.jetbrains.kotlin.platform.type" to "native"
    )

    /** Build a target coordinate's `.module`: each variant with its file's checksums and dependencies. */
    private fun targetModuleFile(
        artifactId: String,
        mainExtension: String,
        specs: List<VariantSpec>,
        files: Map<FileKind, File>
    ): File {
        fun publishedName(kind: FileKind): String = when (kind) {
            FileKind.MAIN -> "$artifactId-$version.$mainExtension"
            FileKind.SOURCES -> "$artifactId-$version-sources.jar"
            FileKind.METADATA -> "$artifactId-$version-metadata.jar"
        }
        val variants = specs.map { spec ->
            linkedMapOf<String, Any>(
                "name" to spec.name,
                "attributes" to spec.attributes
            ).apply {
                if (spec.includeDependencies && moduleDependencies.isNotEmpty()) put("dependencies", moduleDependencies)
                put("files", listOf(fileEntry(publishedName(spec.fileKind), files.getValue(spec.fileKind))))
            }
        }
        val module = linkedMapOf<String, Any>(
            "formatVersion" to "1.1",
            // The target component points back at the root component, which owns the publication.
            "component" to linkedMapOf(
                "url" to "../../$rootName/$version/$rootName-$version.module",
                "group" to group,
                "module" to rootName,
                "version" to version,
                "attributes" to linkedMapOf("org.gradle.status" to gradleStatus)
            ),
            "createdBy" to createdBy(),
            "variants" to variants
        )
        return writeFile("$artifactId.module", json(module))
    }

    /** Build the root `.module`: inline metadata variants + every target variant redirected via `available-at`. */
    private fun generateRootModule(metadataJar: File, sourcesJar: File): String {
        val variants = mutableListOf<Map<String, Any>>()

        variants.add(linkedMapOf(
            "name" to "metadataApiElements",
            "attributes" to linkedMapOf<String, Any>(
                "org.gradle.category" to "library",
                "org.gradle.jvm.environment" to "non-jvm",
                "org.gradle.usage" to "kotlin-metadata",
                "org.jetbrains.kotlin.platform.type" to "common"
            ),
            "dependencies" to moduleDependencies,
            "files" to listOf(fileEntry("$rootName-$version.jar", metadataJar))
        ))
        variants.add(linkedMapOf(
            "name" to "metadataSourcesElements",
            "attributes" to linkedMapOf<String, Any>(
                "org.gradle.category" to "documentation",
                "org.gradle.dependency.bundling" to "external",
                "org.gradle.docstype" to "sources",
                "org.gradle.jvm.environment" to "non-jvm",
                "org.gradle.usage" to "kotlin-runtime",
                "org.jetbrains.kotlin.platform.type" to "common"
            ),
            "files" to listOf(fileEntry("$rootName-$version-sources.jar", sourcesJar))
        ))

        for ((target, specs) in orderedTargetSpecs()) {
            val targetCoord = "$rootName-${targetSuffix(target)}"
            for (spec in specs) {
                variants.add(linkedMapOf(
                    "name" to spec.name,
                    "attributes" to spec.attributes,
                    "available-at" to linkedMapOf(
                        "url" to "../../$targetCoord/$version/$targetCoord-$version.module",
                        "group" to group,
                        "module" to targetCoord,
                        "version" to version
                    )
                ))
            }
        }

        val module = linkedMapOf<String, Any>(
            "formatVersion" to "1.1",
            "component" to linkedMapOf(
                "group" to group,
                "module" to rootName,
                "version" to version,
                "attributes" to linkedMapOf("org.gradle.status" to gradleStatus)
            ),
            "createdBy" to createdBy(),
            "variants" to variants
        )
        return json(module)
    }

    /** Targets in a stable order (natives, then js, then jvm) with their variant specs. */
    private fun orderedTargetSpecs(): List<Pair<KmpTarget, List<VariantSpec>>> = buildList {
        config.targets.filterIsInstance<KmpTarget.Native>().sortedBy { it.name }.forEach { add(it to nativeVariantSpecs(it)) }
        if (config.targets.any { it is KmpTarget.Js }) add(KmpTarget.Js to jsVariantSpecs())
        if (KmpTarget.Jvm in config.targets) add(KmpTarget.Jvm to jvmVariantSpecs())
    }

    private fun targetSuffix(target: KmpTarget): String = when (target) {
        KmpTarget.Jvm -> "jvm"
        is KmpTarget.Js -> "js"
        is KmpTarget.Native -> target.name.lowercase()
        else -> target.name.lowercase()
    }

    private fun createdBy() = linkedMapOf("kbuild" to linkedMapOf("version" to KBUILD_VERSION))

    /**
     * A module file entry. [publishedName] must be the deployed filename (with version) — that is
     * the URL a consumer resolves; the staging [file] only supplies size and checksums.
     */
    private fun fileEntry(publishedName: String, file: File): Map<String, Any> = linkedMapOf(
        "name" to publishedName,
        "url" to publishedName,
        "size" to file.length(),
        "sha512" to file.hash("SHA-512"),
        "sha256" to file.hash("SHA-256"),
        "sha1" to file.hash("SHA-1"),
        "md5" to file.hash("MD5")
    )

    // ============== Auxiliary metadata documents ==============

    /**
     * The `kotlin-project-structure-metadata.json` packed inside the root metadata jar; describes
     * the shared source sets and which published variants include them, so a consumer's metadata
     * compilation knows where to find each source set's declarations.
     *
     * The `sourceSets` list mirrors the full declared shared hierarchy (see [sharedSourceSets]) — not
     * just the source sets with files on disk — because a consumer compiling against an intermediate
     * source set (e.g. nativeMain) needs that source set described even when this library leaves it
     * empty. [depSourceSets] supplies, per dependency, which shared source sets it itself publishes,
     * so each source set's `moduleDependency` lists exactly the dependencies that contribute to it.
     */
    private fun generateProjectStructureMetadata(depSourceSets: Map<KmpDependency, Set<String>>): String {
        val sharedNames = sharedSourceSets.map { it.name }.toSet()

        val sourceSets = sharedSourceSets.map { sourceSet ->
            val name = sourceSet.name
            // Native-only intermediates carry cinterop-commonization metadata and can only be compiled
            // on a specific host; Gradle marks them with a cinterop directory and hostSpecific=true.
            val nativeOnly = isNativeOnlySharedSourceSet(sourceSet)
            linkedMapOf<String, Any>(
                "name" to name,
                "dependsOn" to sourceSet.dependsOn.map { it.name }.filter { it in sharedNames }.sorted(),
                "moduleDependency" to moduleDependenciesFor(name, depSourceSets)
            ).apply {
                if (nativeOnly) put("sourceSetCInteropMetadataDirectory", "$name-cinterop")
                put("binaryLayout", "klib")
                if (nativeOnly) put("hostSpecific", "true")
            }
        }
        // Each platform variant resolves the shared source sets in its hierarchy chain.
        val variants = orderedTargetSpecs().flatMap { (target, specs) ->
            val leaf = config.sourceSets.getSourceSetForTarget(target)
            val chain = (listOfNotNull(leaf) + (leaf?.allDependsOn ?: emptySet()))
                .map { it.name }.filter { it in sharedNames }
            specs.filter { it.fileKind == FileKind.MAIN }.map { spec ->
                linkedMapOf<String, Any>("name" to spec.name.removeSuffix("-published"), "sourceSet" to chain)
            }
        }
        return json(linkedMapOf(
            "projectStructure" to linkedMapOf(
                "formatVersion" to "0.3.3",
                "isPublishedAsRoot" to "true",
                "variants" to variants,
                "sourceSets" to sourceSets
            )
        ))
    }

    /**
     * A shared source set is native-only when every enabled target whose hierarchy includes it is a
     * Native target (e.g. nativeMain/appleMain/iosMain when only iOS targets sit below them). Such
     * source sets are host-specific in Gradle's metadata; commonMain is not, since JVM/JS sit below it.
     */
    private fun isNativeOnlySharedSourceSet(sourceSet: SourceSet): Boolean {
        val usingTargets = config.targets.filter { target ->
            val leaf = config.sourceSets.getSourceSetForTarget(target) ?: return@filter false
            sourceSet.name in (listOf(leaf.name) + leaf.allDependsOn.map { it.name })
        }
        return usingTargets.isNotEmpty() && usingTargets.all { it is KmpTarget.Native }
    }

    /**
     * The dependencies that contribute to [sourceSetName]'s metadata. commonMain receives every
     * common dependency; an intermediate receives a dependency only when that dependency itself
     * publishes a source set of the same name (per [depSourceSets]). Dependency order is preserved.
     */
    private fun moduleDependenciesFor(
        sourceSetName: String,
        depSourceSets: Map<KmpDependency, Set<String>>
    ): List<String> = config.commonDependencies
        .filter { dep -> sourceSetName == "commonMain" || sourceSetName in depSourceSets[dep].orEmpty() }
        .map { "${it.groupId}:${it.artifactId}" }

    /** Diagnostic metadata describing the build that produced this publication. */
    private fun generateKotlinToolingMetadata(): String {
        val projectTargets = mutableListOf<Map<String, Any>>()
        for (target in config.targets.filterIsInstance<KmpTarget.Native>().sortedBy { it.name }) {
            projectTargets.add(linkedMapOf(
                "target" to "native",
                "platformType" to "native",
                "extras" to linkedMapOf("native" to linkedMapOf(
                    "konanTarget" to target.konanTarget.targetName,
                    "konanVersion" to Kotlin.versionString
                ))
            ))
        }
        if (config.targets.any { it is KmpTarget.Js }) {
            projectTargets.add(linkedMapOf("target" to "js", "platformType" to "js"))
        }
        if (KmpTarget.Jvm in config.targets) {
            projectTargets.add(linkedMapOf("target" to "jvm", "platformType" to "jvm"))
        }
        projectTargets.add(linkedMapOf("target" to "metadata", "platformType" to "common"))

        return json(linkedMapOf(
            "schemaVersion" to "1.1.0",
            "buildSystem" to "kbuild",
            "buildSystemVersion" to KBUILD_VERSION,
            "buildPlugin" to "com.ivieleague.kbuild",
            "buildPluginVersion" to Kotlin.versionString,
            "projectSettings" to linkedMapOf(
                "isHmppEnabled" to true,
                "isCompatibilityMetadataVariantEnabled" to false,
                "isKPMEnabled" to false
            ),
            "projectTargets" to projectTargets
        ))
    }

    // ============== Deploy / signing ==============

    private fun deploy(repository: RemoteRepository, artifacts: List<Artifact>) {
        val toDeploy = signer?.signAll(artifacts) ?: artifacts
        MavenAether.deploy(repository, toDeploy)
    }

    companion object {
        private const val KBUILD_VERSION = "1.0"
    }
}

private fun File.hash(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Minimal pretty-printing JSON serializer for the maps/lists assembled above. */
private fun json(value: Any?, indent: String = ""): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
        separator = ",\n", prefix = "{\n", postfix = "\n$indent}"
    ) { (k, v) -> "$indent  ${json(k.toString())}: ${json(v, "$indent  ")}" }
    is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(
        separator = ",\n", prefix = "[\n", postfix = "\n$indent]"
    ) { "$indent  ${json(it, "$indent  ")}" }
    else -> error("Unsupported JSON value: $value")
}

/**
 * Publish all KMP artifacts to a repository.
 */
suspend fun kmpPublishAll(
    config: KmpProjectConfig,
    projectIdentifier: ProjectIdentifier,
    repository: RemoteRepository = MavenAether.local,
    outputDir: File = config.buildDir.resolve("publish"),
    pomConfigure: (Model) -> Unit = {},
    signer: GpgSigner? = null
): Map<String, List<Artifact>> =
    KmpPublisher(config, projectIdentifier, outputDir, pomConfigure, signer).publishAll(repository)

/**
 * Create a publisher for a KMP project.
 */
fun KmpProjectConfig.publisher(
    projectIdentifier: ProjectIdentifier,
    outputDir: File = buildDir.resolve("publish"),
    pomConfigure: (Model) -> Unit = {},
    signer: GpgSigner? = null,
    compileJvm: (suspend () -> File)? = null,
    compileJs: (suspend () -> File)? = null,
    compileNative: (suspend (KmpTarget.Native) -> File)? = null
): KmpPublisher = KmpPublisher(
    this, projectIdentifier, outputDir, pomConfigure, signer, compileJvm, compileJs, compileNative
)
