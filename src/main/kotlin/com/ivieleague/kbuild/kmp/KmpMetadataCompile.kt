package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.native.KonanCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.util.zip.ZipFile

/**
 * Compiles the project's shared (common) source sets to Kotlin metadata klibs.
 *
 * This is the input for the *root* coordinate's published artifact (e.g.
 * `reactive-1.0-SNAPSHOT.jar`), which a Kotlin Multiplatform consumer compiles its own
 * commonMain against. Each shared source set (one with no single target — commonMain and any
 * intermediate like nativeMain) is compiled with `K2MetadataCompiler` in dependsOn order, so a
 * source set can refine the ones above it.
 *
 * Returns a map of source-set name to the compiled klib directory (each containing `default/`).
 * Source sets without sources on disk are skipped — they contribute nothing to publish.
 */
suspend fun kmpCompileMetadata(config: KmpProjectConfig): Map<String, File> =
    withContext(Dispatchers.IO) {
        // Shared source sets are the intermediate ones (no single owning target); commonMain is
        // the root of this set. Order by hierarchy depth so parents compile before children.
        val sharedSourceSets = config.sourceSets.getMainSourceSets()
            .filter { it.targets.isEmpty() }
            .filter { sourceSet -> sourceSet.sourceDirectories.any { it.exists() && it.hasKotlinSources() } }
            .sortedBy { it.allDependsOn.size }

        if (sharedSourceSets.isEmpty()) return@withContext emptyMap()

        // The metadata classpath is the *shared* API of each dependency. kotlin-stdlib is special:
        // its root coordinate is the JVM jar, not a common klib, so use the platform-agnostic common
        // stdlib klib bundled in the Kotlin/Native distribution instead.
        val commonStdlib = KonanCompiler().ensureInstalled().resolve("klib/common/stdlib")

        // Every other common dependency's root coordinate is a Hierarchical-MPP metadata jar whose
        // entries are grouped by the dependency's own source sets (e.g. `commonMain/default/...`).
        // The metadata compiler reads flat klibs (`default/...`), so each source set we compile must
        // extract the matching source set's klib from each dependency — the "metadata dependency
        // transformation" Gradle performs internally.
        val depMetadataJars = config.commonDependencies
            .filterNot { it.artifactId == "kotlin-stdlib" }
            .map { MavenAether.singleArtifactFile("${it.groupId}:${it.artifactId}:${it.version}") }
        val depWorkDir = config.buildDir.resolve("metadata/deps")

        val contextParameters = config.nativeCompilerArguments.contains("-Xcontext-parameters")

        val results = mutableMapOf<String, File>()
        for (sourceSet in sharedSourceSets) {
            val outputDir = config.buildDir.resolve("metadata/${sourceSet.name}")
            outputDir.deleteRecursively()
            outputDir.parentFile.mkdirs()

            val ownSources = sourceSet.sourceDirectories
                .filter { it.exists() }
                .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
                .map { it.absolutePath }

            // Refine the already-compiled parent source sets so this set sees their declarations.
            val refines = sourceSet.dependsOn
                .mapNotNull { results[it.name] }
                .map { it.absolutePath }

            val depKlibs = depMetadataJars.map {
                dependencyKlibForSourceSet(it, sourceSet.name, depWorkDir).absolutePath
            }

            compileMetadataSourceSet(
                moduleName = "${config.name}_${sourceSet.name}",
                sources = ownSources,
                classpath = listOf(commonStdlib.absolutePath) + depKlibs + refines,
                refinesPaths = refines,
                destination = outputDir,
                contextParameters = contextParameters
            )
            results[sourceSet.name] = outputDir
        }
        results
    }

private fun File.hasKotlinSources(): Boolean =
    walkTopDown().any { it.extension == "kt" }

/**
 * For each common dependency, the names of the shared source sets it publishes in its own
 * `kotlin-project-structure-metadata.json`. This is what Gradle uses to attribute each shared
 * source set's `moduleDependency` list: a dependency is listed for an intermediate source set only
 * when it actually publishes that source set.
 *
 * kotlin-stdlib (and any dependency with no multiplatform structure metadata) maps to the empty set,
 * so it ends up attributed to commonMain only — every common dependency implicitly provides commonMain.
 *
 * Note a dependency may *declare* a source set in this JSON (e.g. coroutines' `appleMain`/`iosMain`)
 * without carrying a physical `<sourceSet>/` klib directory in its jar, so the JSON — not the jar
 * layout — is the source of truth.
 */
suspend fun kmpDependencySourceSets(config: KmpProjectConfig): Map<KmpDependency, Set<String>> =
    withContext(Dispatchers.IO) {
        config.commonDependencies.associateWith { dep ->
            if (dep.artifactId == "kotlin-stdlib") return@associateWith emptySet()
            val jar = MavenAether.singleArtifactFile("${dep.groupId}:${dep.artifactId}:${dep.version}")
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("META-INF/kotlin-project-structure-metadata.json")
                    ?: return@associateWith emptySet()
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                Json.parseToJsonElement(text).jsonObject["projectStructure"]!!
                    .jsonObject["sourceSets"]!!.jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    .toSet()
            }
        }
    }

/**
 * Extract a flat klib for [sourceSetName] from a Hierarchical-MPP dependency metadata jar (whose
 * entries are grouped as `<sourceSet>/default/...`). Falls back to the dependency's commonMain when
 * it has no source set of that exact name, and returns the jar unchanged when it is not HMPP-packed.
 */
private fun dependencyKlibForSourceSet(metadataJar: File, sourceSetName: String, workDir: File): File {
    ZipFile(metadataJar).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        val prefix = listOf("$sourceSetName/", "commonMain/").firstOrNull { p -> names.any { it.startsWith(p) } }
            ?: return metadataJar
        val out = workDir.resolve("${metadataJar.nameWithoutExtension}-${prefix.removeSuffix("/")}")
        out.deleteRecursively()
        out.mkdirs()
        zip.entries().asSequence().filter { it.name.startsWith(prefix) && !it.isDirectory }.forEach { entry ->
            val dest = out.resolve(entry.name.removePrefix(prefix))
            dest.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return out
    }
}

private fun compileMetadataSourceSet(
    moduleName: String,
    sources: List<String>,
    classpath: List<String>,
    refinesPaths: List<String>,
    destination: File,
    contextParameters: Boolean
) {
    val collector = Kotlin.CompilationMessageCollector()
    val code = KotlinMetadataCompiler().exec(
        collector,
        Services.EMPTY,
        K2MetadataCompilerArguments().apply {
            this.moduleName = moduleName
            this.destination = destination.absolutePath
            freeArgs = sources
            if (classpath.isNotEmpty()) this.classpath = classpath.joinToString(File.pathSeparator)
            if (refinesPaths.isNotEmpty()) this.refinesPaths = refinesPaths.toTypedArray()
            multiPlatform = true
            expectActualClasses = true
            if (contextParameters) this.contextParameters = true
        }
    )

    for (message in collector.messages) {
        if (message.severity <= CompilerMessageSeverity.WARNING) {
            println("${message.message} at ${message.location}")
        }
    }

    if (code != ExitCode.OK) {
        throw Kotlin.CompilationException(collector.messages)
    }
}
