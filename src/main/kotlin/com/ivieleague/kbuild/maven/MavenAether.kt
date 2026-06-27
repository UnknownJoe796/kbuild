package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.memoize
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.deployment.DeployRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Maven dependencies reactively.
 *
 * The resolution is cached based on input values.
 * When the dependencies reactive changes, resolution will re-run.
 *
 * @param dependencies Reactive list of dependencies to resolve
 * @param repositories Repositories to search
 * @param output Stream for logging
 * @return Set of resolved libraries
 */
suspend fun mavenLibraries(
    dependencies: Reactive<List<Dependency>>,
    repositories: List<RemoteRepository> = MavenAether.defaultRepositories,
    output: PrintStream = System.out
): Set<Library> {
    val deps = dependencies()

    return MavenAether.libraries(
        dependencies = deps,
        repositories = repositories,
        output = output,
        fetchSources = false
    )
}

/**
 * Resolves a single Maven dependency path reactively.
 *
 * @param path Maven coordinate (e.g., "group:artifact:version")
 * @param repositories Repositories to search
 * @param output Stream for logging
 * @return Set of resolved libraries
 */
suspend fun mavenLibrary(
    path: Reactive<String>,
    repositories: List<RemoteRepository> = MavenAether.defaultRepositories,
    output: PrintStream = System.out
): Set<Library> {
    val p = path()

    return MavenAether.libraries(
        path = p,
        repositories = repositories,
        output = output,
        fetchSources = false
    )
}

/**
 * Used for resolving Maven dependencies.
 * This object provides the core Maven/Aether integration.
 */
object MavenAether {
    private val repositorySystem: RepositorySystem = run {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)

        locator.getService(RepositorySystem::class.java)
    }

    private val session = run {
        val session = MavenRepositorySystemUtils.newSession()

        val localRepo = LocalRepository(File(File(System.getProperty("user.home")), ".maven-cache"))
        session.localRepositoryManager = repositorySystem.newLocalRepositoryManager(session, localRepo)

        session
    }

    // Persistent cache file for resolved library paths
    private val cacheFile = File(File(System.getProperty("user.home")), ".maven-cache/library-cache.txt")
    private val persistentCache: MutableMap<String, Library> = ConcurrentHashMap<String, Library>().also { cache ->
        // Load from disk on startup
        if (cacheFile.exists()) {
            try {
                cacheFile.readLines().chunked(4).forEach { lines ->
                    if (lines.size >= 2) {
                        val name = lines[0]
                        val defaultPath = lines[1]
                        val defaultFile = File(defaultPath)
                        if (defaultFile.exists()) {
                            val docsFile = lines.getOrNull(2)?.let { File(it) }?.takeIf { it.exists() }
                            val sourcesFile = lines.getOrNull(3)?.let { File(it) }?.takeIf { it.exists() }
                            cache[name] = Library(name, defaultFile, docsFile, sourcesFile)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore cache load errors
            }
        }
    }

    private fun savePersistentCache() {
        try {
            cacheFile.parentFile.mkdirs()
            cacheFile.writeText(
                persistentCache.values.joinToString("\n") { lib ->
                    "${lib.name}\n${lib.default?.absolutePath ?: ""}\n${lib.documentation?.absolutePath ?: ""}\n${lib.sources?.absolutePath ?: ""}"
                }
            )
        } catch (e: Exception) {
            // Ignore cache save errors
        }
    }

    fun DependencyNode.allArtifacts(): Sequence<Artifact> =
        (if (this.artifact != null) sequenceOf(this.artifact) else sequenceOf()) + this.children.asSequence().flatMap { it.allArtifacts() }

    /**
     * Resolves a klib (Kotlin library) artifact directly.
     * Used for Kotlin/JS and Kotlin/Native artifacts which use klib packaging.
     *
     * @param path Maven coordinate (e.g., "org.jetbrains.kotlin:kotlin-stdlib-js:2.2.0")
     */
    fun librariesKlib(
        path: String,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out
    ): Set<Library> {
        val parts = path.split(":")
        require(parts.size == 3) { "Invalid path format: $path. Expected group:artifact:version" }
        val (groupId, artifactId, version) = parts

        val artifact = DefaultArtifact(groupId, artifactId, "klib", version)
        val id = "$groupId:$artifactId:$version"

        // Check persistent cache first
        persistentCache[id]?.let { cached ->
            if (cached.default?.exists() == true) {
                return setOf(cached)
            }
        }

        output.println("Obtaining $id (klib)")

        val result = repositorySystem.resolveArtifact(
            session,
            ArtifactRequest(artifact, repositories, null)
        )

        if (!result.isResolved) {
            throw IllegalStateException("Could not resolve $id: ${result.exceptions.joinToString { it.message ?: "" }}")
        }

        val library = Library(name = id, default = result.artifact.file)
        persistentCache[id] = library
        savePersistentCache()

        return setOf(library)
    }

    /**
     * Resolve a single artifact file by coordinate and packaging, with no transitive resolution.
     *
     * Needed for the commonMain metadata classpath: a KMP library's shared (common) API is its
     * root-coordinate artifact (e.g. `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` →
     * the commonMain metadata klib). Transitive `collectDependencies` would instead pull the
     * platform variants via the POM, which are not valid metadata-compilation inputs.
     *
     * @param path Maven coordinate (group:artifact:version)
     * @param extension Artifact packaging extension (jar for metadata klibs)
     */
    fun singleArtifactFile(
        path: String,
        extension: String = "jar",
        repositories: List<RemoteRepository> = defaultRepositories
    ): File {
        val parts = path.split(":")
        require(parts.size == 3) { "Invalid path format: $path. Expected group:artifact:version" }
        val (groupId, artifactId, version) = parts

        val result = repositorySystem.resolveArtifact(
            session,
            ArtifactRequest(DefaultArtifact(groupId, artifactId, null, extension, version), repositories, null)
        )
        if (!result.isResolved) {
            throw IllegalStateException("Could not resolve $path ($extension): ${result.exceptions.joinToString { it.message ?: "" }}")
        }
        return result.artifact.file
    }

    // ---- Gradle Module Metadata (variant-aware) resolution ----
    //
    // Lenient because `.module` files carry many keys (capabilities, thirdPartyCompatibility,
    // createdBy, …) kbuild does not model; we only read what drives variant selection.
    private val moduleMetadataJson = Json { ignoreUnknownKeys = true }

    /**
     * Fetch and parse a Gradle Module Metadata (`.module`) file for the given coordinate.
     *
     * Returns null when no `.module` is published (pre-2019 / plain-Maven libraries) or resolution
     * fails for any reason — the signal callers use to fall back to POM/convention resolution.
     * The file is cached to `~/.maven-cache` by Aether like any other artifact.
     */
    fun fetchModuleMetadata(
        group: String,
        artifact: String,
        version: String,
        repositories: List<RemoteRepository> = defaultRepositories
    ): GradleModuleMetadata? = try {
        val result = repositorySystem.resolveArtifact(
            session,
            ArtifactRequest(DefaultArtifact(group, artifact, null, "module", version), repositories, null)
        )
        if (result.isResolved) moduleMetadataJson.decodeFromString<GradleModuleMetadata>(result.artifact.file.readText())
        else null
    } catch (e: Exception) {
        null
    }

    /**
     * Select the published variant of [metadata] that satisfies [target].
     *
     * Matches Gradle's attribute-based variant selection for the dimensions kbuild needs:
     * `org.gradle.category == library`, the Kotlin platform type, the native target (for native),
     * and usage (api vs runtime). Returns null when the module publishes nothing for [target].
     *
     * @param preferApi pick `*-api` usage variants (compile classpath); false picks `*-runtime`.
     */
    fun selectVariant(
        metadata: GradleModuleMetadata,
        target: KmpTarget,
        preferApi: Boolean = true
    ): GmmVariant? {
        val platformType = when (target) {
            KmpTarget.Jvm -> "jvm"
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> "js"
            KmpTarget.Wasm.Js -> "wasm-js"
            KmpTarget.Wasm.Wasi -> "wasm-wasi"
            is KmpTarget.Wasm -> "wasm-js"
            is KmpTarget.Native -> "native"
        }
        val nativeTargetName = (target as? KmpTarget.Native)?.konanTarget?.targetName
        // api/runtime usages differ by platform: jvm uses java-*, others kotlin-*. kotlin-metadata
        // is the common (shared-source) variant and is never a real per-target classpath input.
        val acceptedUsages = if (preferApi) setOf("java-api", "kotlin-api") else setOf("java-runtime", "kotlin-runtime")

        return metadata.variants.firstOrNull { variant ->
            variant.attribute("org.gradle.category") == "library" &&
                variant.attribute("org.jetbrains.kotlin.platform.type") == platformType &&
                (nativeTargetName == null || variant.attribute("org.jetbrains.kotlin.native.target") == nativeTargetName) &&
                variant.attribute("org.gradle.usage") in acceptedUsages
        }
    }

    /**
     * Resolve a KMP library for a single [target] using its Gradle Module Metadata, following the
     * root → per-target `available-at` redirect and pulling transitive variant dependencies.
     *
     * Returns null when the root coordinate has no `.module` (caller should fall back to
     * convention-based resolution); returns an empty set when a `.module` exists but publishes
     * nothing for [target] (the library genuinely does not support it).
     *
     * Deferrals: `strictly`/`rejects`/`prefers` version algebra is not implemented (uses the
     * declared `requires`); BOM/platform (`org.gradle.category == platform`) dependencies are
     * filtered out rather than aligned. See [GmmVersionConstraint].
     */
    fun resolveKmpForTarget(
        group: String,
        artifact: String,
        version: String,
        target: KmpTarget,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out
    ): Set<Library>? = resolveKmpForTarget(group, artifact, version, target, repositories, output, HashSet())

    private fun resolveKmpForTarget(
        group: String,
        artifact: String,
        version: String,
        target: KmpTarget,
        repositories: List<RemoteRepository>,
        output: PrintStream,
        visited: MutableSet<String>
    ): Set<Library>? {
        if (!visited.add("$group:$artifact:$version")) return emptySet()

        val rootMetadata = fetchModuleMetadata(group, artifact, version, repositories) ?: return null
        val rootVariant = selectVariant(rootMetadata, target) ?: return emptySet()

        // A root KMP variant only redirects; follow it once to the per-target module, whose
        // coordinates come from `available-at` (the per-target component back-references the root).
        val artifactCoord: Triple<String, String, String>
        val resolvedVariant: GmmVariant
        val redirect = rootVariant.availableAt
        if (redirect != null) {
            artifactCoord = Triple(redirect.group, redirect.module, redirect.version)
            val perTarget = fetchModuleMetadata(redirect.group, redirect.module, redirect.version, repositories)
            if (perTarget == null) {
                // Edge case: a published library (e.g. one kbuild itself produced) whose per-target
                // `.module` is missing. Fall back to convention using the redirect coordinates.
                return resolveConvention(redirect.group, redirect.module, redirect.version, target, repositories, output)
            }
            resolvedVariant = selectVariant(perTarget, target) ?: return emptySet()
        } else {
            // No redirect: this `.module` describes the requested coordinate directly (either a
            // single-target publication, or a per-target module fetched on its own). The artifact
            // lives at the requested coordinate — NOT at component coords, which in a per-target
            // module are a back-reference to the root and would point at a non-existent artifact.
            artifactCoord = Triple(group, artifact, version)
            resolvedVariant = rootVariant
        }

        val result = LinkedHashSet<Library>()

        // The artifact's packaging is authoritative from the file extension, not the platform type.
        val fileUrl = resolvedVariant.files.firstOrNull()?.url
        if (fileUrl != null) {
            val extension = if (fileUrl.endsWith(".klib")) "klib" else "jar"
            val (g, a, v) = artifactCoord
            result.add(resolveArtifact(DefaultArtifact(g, a, null, extension, v), repositories, output, fetchSources = false))
        }

        // Pull transitive dependencies declared by the resolved variant. Platform (BOM) deps are
        // filtered (alignment deferred); recurse through GMM so each dep's own redirects are followed.
        for (dep in resolvedVariant.dependencies) {
            if (dep.attribute("org.gradle.category") == "platform") continue
            val depVersion = dep.version?.resolved ?: continue
            val sub = resolveKmpForTarget(dep.group, dep.module, depVersion, target, repositories, output, visited)
                ?: resolveConvention(dep.group, dep.module, depVersion, target, repositories, output)
            result.addAll(sub)
        }

        savePersistentCache()
        return result
    }

    /**
     * Convention-based fallback for a coordinate that has no Gradle Module Metadata: resolve the
     * JVM jar transitively, or the klib directly for non-JVM targets. Empty on failure so a missing
     * optional transitive dependency never aborts the whole resolution.
     */
    private fun resolveConvention(
        group: String,
        artifact: String,
        version: String,
        target: KmpTarget,
        repositories: List<RemoteRepository>,
        output: PrintStream
    ): Set<Library> = try {
        val path = "$group:$artifact:$version"
        if (target == KmpTarget.Jvm) runBlocking { libraries(path, repositories, output) }
        else librariesKlib(path, repositories, output)
    } catch (e: Exception) {
        emptySet()
    }

    /**
     * Resolves dependencies in parallel using coroutines.
     *
     * @param path Maven coordinate (e.g., "group:artifact:version")
     * @param repositories Repositories to search
     * @param output Stream for logging
     * @param fetchSources Whether to also fetch javadoc and sources (slower but needed for IDE)
     * @param parallelism Maximum number of concurrent resolutions
     */
    suspend fun libraries(
        path: String,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = false,
        parallelism: Int = 8
    ) = libraries(
        dependencies = listOf(Dependency(path).aether()),
        repositories = repositories,
        output = output,
        fetchSources = fetchSources,
        parallelism = parallelism
    )

    /**
     * Resolves dependencies in parallel using coroutines.
     *
     * @param dependencies List of Maven dependencies to resolve
     * @param repositories Repositories to search
     * @param output Stream for logging
     * @param fetchSources Whether to also fetch javadoc and sources (slower but needed for IDE)
     * @param parallelism Maximum number of concurrent resolutions
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun libraries(
        dependencies: List<Dependency>,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = false,
        parallelism: Int = 8
    ): Set<Library> = withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
        val dependencyResults: CollectResult = repositorySystem.collectDependencies(
            session,
            CollectRequest(dependencies, null, repositories)
        )

        when (dependencyResults.exceptions.size) {
            0 -> {}
            1 -> throw dependencyResults.exceptions.first()
            else -> throw Exception("Several exceptions: ${dependencyResults.exceptions.joinToString("\n") {
                it?.message ?: "?"
            }}")
        }

        val artifacts = dependencyResults.root.allArtifacts().toList()

        artifacts.map { artifact ->
            async {
                resolveArtifact(artifact, repositories, output, fetchSources)
            }
        }.awaitAll().toSet().also {
            // Save cache after resolution
            savePersistentCache()
        }
    }

    /**
     * Resolve a single artifact, using persistent cache.
     */
    private fun resolveArtifact(
        artifact: Artifact,
        repositories: List<RemoteRepository>,
        output: PrintStream,
        fetchSources: Boolean
    ): Library {
        val id = artifact.run { "$groupId:$artifactId:$version" }

        // Check persistent cache first. Only reuse a cached entry if it satisfies this
        // request: an entry resolved earlier without sources must not shadow a later
        // sources-fetching request (e.g. IDE generation), or sources would never attach.
        persistentCache[id]?.let { cached ->
            if (cached.default?.exists() == true && (!fetchSources || cached.sources != null)) {
                return cached
            }
        }

        synchronized(output) {
            output.println("Obtaining $id")
        }

        val library = Library(
            name = id,
            default = repositorySystem.resolveArtifact(
                session,
                ArtifactRequest(artifact, repositories, null)
            ).let { result ->
                if (result.isResolved)
                    result.artifact.file
                else
                    throw IllegalStateException("Could not resolve $id: ${result.exceptions.joinToString { it.message ?: "" }}")
            },
            documentation = if (fetchSources) {
                try {
                    repositorySystem.resolveArtifact(
                        session,
                        ArtifactRequest(artifact.javadoc(), repositories, null)
                    ).let { result ->
                        if (result.isResolved) result.artifact.file else null
                    }
                } catch (e: Exception) {
                    null
                }
            } else null,
            sources = if (fetchSources) {
                try {
                    repositorySystem.resolveArtifact(
                        session,
                        ArtifactRequest(artifact.sources(), repositories, null)
                    ).let { result ->
                        if (result.isResolved) result.artifact.file else null
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
        )

        persistentCache[id] = library
        return library
    }

    fun deploy(remoteRepository: RemoteRepository, artifacts: List<Artifact>) {
        val result = repositorySystem.deploy(session, DeployRequest().apply {
            this.repository = remoteRepository
            this.artifacts = artifacts
        })
    }

    val central = RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
    val google = RemoteRepository.Builder("google", "default", "https://dl.google.com/dl/android/maven2/").build()
    val local = RemoteRepository.Builder(
        "local",
        "default",
        "file://" + File(File(System.getProperty("user.home")), ".m2/repository").invariantSeparatorsPath
    ).build()
    val lightningKite = RemoteRepository.Builder(
        "lightningkite",
        "default",
        "https://lightningkite-maven.s3.us-west-2.amazonaws.com"
    ).build()
    val defaultRepositories = listOf(
        central,
        google,
        local,
        lightningKite
    )

    fun Artifact.javadoc() = DefaultArtifact(this.groupId, this.artifactId, "javadoc", "jar", this.version)
    fun Artifact.sources() = DefaultArtifact(this.groupId, this.artifactId, "sources", "jar", this.version)
}
