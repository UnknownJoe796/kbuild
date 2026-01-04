package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.memoize
import com.lightningkite.reactive.context.invoke
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.*
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

    return MavenAether.librariesParallel(
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

    return MavenAether.librariesParallel(
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

    fun libraries(
        path: String,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = true
    ) = libraries(dependencies = listOf(Dependency(path).aether()), repositories = repositories, output = output, fetchSources = fetchSources)

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
     * Resolves dependencies sequentially (legacy method).
     * For better performance, use [librariesParallel] instead.
     */
    fun libraries(
        dependencies: List<Dependency>,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = true
    ): Set<Library> {
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

        return dependencyResults.root.allArtifacts()
            .map { resolveArtifact(it, repositories, output, fetchSources) }
            .toSet()
    }

    /**
     * Resolves dependencies in parallel using coroutines (suspend version).
     * Significantly faster than sequential resolution.
     *
     * @param path Maven coordinate (e.g., "group:artifact:version")
     * @param repositories Repositories to search
     * @param output Stream for logging
     * @param fetchSources Whether to also fetch javadoc and sources (slower but needed for IDE)
     * @param parallelism Maximum number of concurrent resolutions
     */
    suspend fun librariesParallel(
        path: String,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = false,
        parallelism: Int = 8
    ) = librariesParallel(
        dependencies = listOf(Dependency(path).aether()),
        repositories = repositories,
        output = output,
        fetchSources = fetchSources,
        parallelism = parallelism
    )

    /**
     * Resolves dependencies in parallel using coroutines (suspend version).
     * Significantly faster than sequential resolution.
     *
     * @param dependencies List of Maven dependencies to resolve
     * @param repositories Repositories to search
     * @param output Stream for logging
     * @param fetchSources Whether to also fetch javadoc and sources (slower but needed for IDE)
     * @param parallelism Maximum number of concurrent resolutions
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun librariesParallel(
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
     * Resolves dependencies in parallel (blocking version).
     * Use [librariesParallel] for the suspend version.
     * This is useful in contexts where suspend is not available (e.g., lazy initialization).
     */
    fun librariesParallelBlocking(
        path: String,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = false,
        parallelism: Int = 8
    ): Set<Library> = runBlocking {
        librariesParallel(path, repositories, output, fetchSources, parallelism)
    }

    /**
     * Resolves dependencies in parallel (blocking version).
     * Use [librariesParallel] for the suspend version.
     * This is useful in contexts where suspend is not available (e.g., lazy initialization).
     */
    fun librariesParallelBlocking(
        dependencies: List<Dependency>,
        repositories: List<RemoteRepository> = defaultRepositories,
        output: PrintStream = System.out,
        fetchSources: Boolean = false,
        parallelism: Int = 8
    ): Set<Library> = runBlocking {
        librariesParallel(dependencies, repositories, output, fetchSources, parallelism)
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

        // Check persistent cache first
        persistentCache[id]?.let { cached ->
            if (cached.default?.exists() == true) {
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

    fun bintray(organization: String, repository: String) = RemoteRepository.Builder(
        "bintray/$organization/$repository",
        "default",
        "https://api.bintray.com/maven/$organization/$repository"
    ).build()

    fun Artifact.javadoc() = DefaultArtifact(this.groupId, this.artifactId, "javadoc", "jar", this.version)
    fun Artifact.sources() = DefaultArtifact(this.groupId, this.artifactId, "sources", "jar", this.version)
}
