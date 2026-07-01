package com.ivieleague.kbuild.standard

import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.common.DependencyScope
import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Project
import com.ivieleague.kbuild.common.Repository
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.jarBuildBlocking
import com.ivieleague.kbuild.junit.junitRunBlocking
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import com.ivieleague.kbuild.maven.toMaven
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.ivieleague.kbuild.maven.PomDeveloper
import com.ivieleague.kbuild.maven.PomLicense
import com.ivieleague.kbuild.maven.PomMetadata
import com.ivieleague.kbuild.maven.applyTo
import org.apache.maven.model.Model
import org.apache.maven.model.io.DefaultModelWriter
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File
import java.util.jar.Manifest

/**
 * A standardized JVM library build configuration with sensible defaults.
 *
 * Extend this class to create your own JVM library configuration:
 * ```
 * object MyLibrary : JvmLibrary() {
 *     override val name = "my-library"
 *     override val projectRoot = File(".")
 *     override val group = "com.example"
 *     override val version = Version("1.0.0")
 *
 *     override suspend fun dependencies() = super.dependencies() + setOf(
 *         dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
 *     )
 *
 *     // Example with kotlinx.serialization plugin
 *     override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
 * }
 *
 * // In build script:
 * MyLibrary.compile()
 * MyLibrary.jar()
 * MyLibrary.test()
 * MyLibrary.publish()
 * ```
 */
abstract class JvmLibrary : Project() {
    open val jvmTarget: String = "17"

    /**
     * Main dependencies for this library.
     * This is a suspend function to allow async resolution.
     */
    open suspend fun dependencies(): Set<Dependency> = emptySet()

    /**
     * Test-only dependencies.
     */
    open suspend fun testDependencies(): Set<Dependency> = emptySet()

    /**
     * Compiler plugin JARs to apply during compilation.
     * This is a suspend function to allow async resolution from Maven.
     *
     * Example usage with kotlinx.serialization:
     * ```
     * override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
     * ```
     */
    open suspend fun compilerPlugins(): Set<File> = emptySet()

    // ============== POM metadata (publishing) ==============

    open val pomName: String get() = name
    open val pomDescription: String? = null
    open val pomUrl: String? = null
    open val pomLicenses: List<PomLicense> = emptyList()
    open val pomDevelopers: List<PomDeveloper> = emptyList()
    open val pomScmUrl: String? = null

    // Directory layout (convention over configuration)
    open val srcDir: File get() = projectRoot.resolve("src/main/kotlin")
    open val testSrcDir: File get() = projectRoot.resolve("src/test/kotlin")
    open val resourcesDir: File get() = projectRoot.resolve("src/main/resources")
    open val testResourcesDir: File get() = projectRoot.resolve("src/test/resources")
    open val classesDir: File get() = buildDir.resolve("classes/kotlin/main")
    open val testClassesDir: File get() = buildDir.resolve("classes/kotlin/test")
    open val cacheDir: File get() = buildDir.resolve("kotlin/cache")
    open val testCacheDir: File get() = buildDir.resolve("kotlin/test-cache")
    open val libsDir: File get() = buildDir.resolve("libs")
    open val publishDir: File get() = buildDir.resolve("publish")

    // ============== Dependency Resolution ==============

    /**
     * Get all default dependencies (Kotlin stdlib + user dependencies).
     */
    protected suspend fun defaultDependencies(): Set<Dependency> =
        setOf(kotlinStdlib()) + dependencies()

    /**
     * Get all test dependencies (JUnit 5 + Kotlin test + user test dependencies).
     */
    protected suspend fun defaultTestDependencies(): Set<Dependency> = setOf(
        kotlinTest(),
        junitJupiter(),
        junitPlatformLauncher()
    ) + testDependencies()

    /**
     * Resolve compile dependencies to Library objects.
     */
    suspend fun resolveCompileDependencies(): Set<Library> {
        val deps = defaultDependencies()
        return MavenAether.libraries(
            dependencies = deps.filter { it.scope.includeInCompilation() }.map { it.aether() }
        )
    }

    /**
     * Resolve compile dependencies to JAR files.
     */
    suspend fun resolveCompileClasspath(): Set<File> {
        return resolveCompileDependencies().mapTo(mutableSetOf()) { it.default }
    }

    /**
     * Resolve test dependencies to JAR files.
     */
    suspend fun resolveTestClasspath(): Set<File> {
        val compileDeps = defaultDependencies().filter { it.scope.includeInCompilation() }
        val testDeps = defaultTestDependencies()
        return MavenAether.libraries(
            dependencies = (compileDeps + testDeps).map { it.aether() }
        ).mapTo(mutableSetOf()) { it.default }
    }

    // ============== Source Watching ==============

    /**
     * Create a reactive watch for source files.
     */
    fun watchSources(): Reactive<Set<File>> {
        return if (srcDir.exists()) {
            DirectoryWatch(srcDir, "**/*.kt")
        } else {
            Constant(emptySet())
        }
    }

    /**
     * Create a reactive watch for test source files.
     */
    fun watchTestSources(): Reactive<Set<File>> {
        return if (testSrcDir.exists()) {
            DirectoryWatch(testSrcDir, "**/*.kt")
        } else {
            Constant(emptySet())
        }
    }

    // ============== Compilation ==============

    /**
     * Compile main sources.
     *
     * Uses the reactive [kotlinJvmCompile] with [watchSources] so that file-system changes are
     * tracked when this method is called from inside a [reactiveSuspending] watch loop (--watch
     * mode).  One-shot calls are unaffected: accessing a Reactive outside an active tracking
     * scope simply returns its current value and compiles exactly once.
     */
    suspend fun compile(): File = coroutineScope {
        // Resolve classpath and plugins in parallel
        val classpathDeferred = async { resolveCompileClasspath() }
        val pluginsDeferred = async { compilerPlugins() }

        val classpath = classpathDeferred.await()
        val plugins = pluginsDeferred.await()

        val lib = this@JvmLibrary
        // kotlinJvmCompile calls sourceRoots() inside a reactive context (if one is active),
        // registering the DirectoryWatch as a dependency so --watch re-runs on source changes.
        kotlinJvmCompile(
            name = name,
            sourceRoots = watchSources(),
            classpathJars = Constant(classpath),
            arguments = {
                jvmTarget = lib.jvmTarget
                if (lib.enableContextParameters) contextParameters = true
                if (lib.optIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + lib.optIns.toTypedArray()
                if (lib.freeCompilerArgs.isNotEmpty()) freeArgs = freeArgs + lib.freeCompilerArgs
                lib.languageVersion?.let { languageVersion = it }
                lib.apiVersion?.let { apiVersion = it }
                if (lib.allWarningsAsErrors) allWarningsAsErrors = true
                if (plugins.isNotEmpty()) {
                    val existing = pluginClasspaths ?: emptyArray()
                    pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
                }
            },
            cache = cacheDir,
            outputFolder = classesDir
        )
    }

    /**
     * Compile test sources.
     */
    suspend fun compileTest(): File = coroutineScope {
        // Compile main sources first
        val mainClasses = compile()

        // Resolve test classpath and plugins in parallel
        val testClasspathDeferred = async { resolveTestClasspath() }
        val pluginsDeferred = async { compilerPlugins() }

        val testClasspath = testClasspathDeferred.await() + mainClasses
        val plugins = pluginsDeferred.await()

        val lib = this@JvmLibrary
        withContext(Dispatchers.IO) {
            kotlinJvmCompileBlocking(
                name = "$name-test",
                sourceRoots = setOf(testSrcDir).filter { it.exists() }.toSet(),
                classpathJars = testClasspath,
                arguments = {
                    jvmTarget = lib.jvmTarget
                    if (enableContextParameters) contextParameters = true
                    if (lib.optIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + lib.optIns.toTypedArray()
                    if (lib.freeCompilerArgs.isNotEmpty()) freeArgs = freeArgs + lib.freeCompilerArgs
                    lib.languageVersion?.let { languageVersion = it }
                    lib.apiVersion?.let { apiVersion = it }
                    if (lib.allWarningsAsErrors) allWarningsAsErrors = true
                    if (plugins.isNotEmpty()) {
                        val existing = pluginClasspaths ?: emptyArray()
                        pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
                    }
                },
                cache = testCacheDir,
                outputFolder = testClassesDir,
                enableContextParameters = enableContextParameters
            )
        }
    }

    // ============== JAR Building ==============

    /**
     * Build a JAR from compiled classes.
     */
    suspend fun jar(): File {
        val classes = compile()
        val jarFile = libsDir.resolve("$name-${version}.jar")
        withContext(Dispatchers.IO) {
            jarBuildBlocking(createManifest(), setOf(classes), jarFile)
        }
        return jarFile
    }

    /**
     * Build a sources JAR.
     */
    suspend fun sourcesJar(): File {
        val jarFile = libsDir.resolve("$name-${version}-sources.jar")
        withContext(Dispatchers.IO) {
            jarBuildBlocking(createManifest(), setOf(srcDir).filter { it.exists() }.toSet(), jarFile)
        }
        return jarFile
    }

    protected open fun createManifest(): Manifest = Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue("Implementation-Title", name)
        mainAttributes.putValue("Implementation-Version", version.toString())
        mainAttributes.putValue("Created-By", "KBuild")
    }

    // ============== Testing ==============

    /**
     * Run tests.
     */
    suspend fun test(): Set<TestResult> {
        val testClasses = compileTest()
        val testClasspath = resolveTestClasspath() + classesDir
        return withContext(Dispatchers.IO) {
            junitRunBlocking(testClasses, testClasspath)
        }
    }

    // ============== Publishing ==============

    /**
     * Publish to a Maven repository.
     */
    suspend fun publish(repository: Repository = Repository.mavenLocal) {
        val jarFile = jar()
        val sourcesJar = sourcesJar()
        val pomFile = withContext(Dispatchers.IO) { createPom() }

        val mainArtifact = DefaultArtifact(
            group, name, null, "jar", version.toString()
        ).setFile(jarFile)

        val artifacts = listOf(
            mainArtifact,
            SubArtifact(mainArtifact, null, "pom", pomFile),
            SubArtifact(mainArtifact, "sources", "jar", sourcesJar)
        )

        MavenAether.deploy(repository.toAether(), artifacts)
        println("Published $group:$name:$version to ${repository.url}")
    }

    private suspend fun createPom(): File {
        val deps = dependencies()
        val pomFile = publishDir.resolve("$name-${version}.pom")
        pomFile.parentFile.mkdirs()

        val model = Model().apply {
            modelVersion = "4.0.0"
            groupId = group
            artifactId = name
            this.version = this@JvmLibrary.version.toString()
            packaging = "jar"
            deps.forEach { dep ->
                dependencies.add(dep.toMaven())
            }
            PomMetadata(
                name = pomName,
                description = pomDescription,
                url = pomUrl,
                licenses = pomLicenses,
                developers = pomDevelopers,
                scmUrl = pomScmUrl
            ).applyTo(this)
        }

        DefaultModelWriter().write(pomFile, mapOf<String, Any>(), model)
        return pomFile
    }

    // ============== Scaffold ==============

    /**
     * Create the standard directory structure.
     */
    fun scaffold() {
        srcDir.mkdirs()
        testSrcDir.mkdirs()
        resourcesDir.mkdirs()
        testResourcesDir.mkdirs()

        // Create a sample file if empty
        val sampleFile = srcDir.resolve("${name.replaceFirstChar { it.uppercase() }}.kt")
        if (!sampleFile.exists() && srcDir.listFiles()?.isEmpty() != false) {
            sampleFile.writeText("""
                package ${group}.${name.replace("-", ".")}

                /**
                 * Main entry point for $name library.
                 */
                class ${name.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }} {
                    fun greet(): String = "Hello from $name!"
                }
            """.trimIndent())
        }
    }

    companion object {
        fun kotlinStdlib(version: String = Kotlin.versionString) =
            Dependency("org.jetbrains.kotlin:kotlin-stdlib:$version")

        fun kotlinTest(version: String = Kotlin.versionString) =
            Dependency("org.jetbrains.kotlin:kotlin-test-junit5:$version", DependencyScope.Test)

        fun junitJupiter(version: String = "5.10.2") =
            Dependency("org.junit.jupiter:junit-jupiter:$version", DependencyScope.Test)

        fun junitPlatformLauncher(version: String = "1.10.2") =
            Dependency("org.junit.platform:junit-platform-launcher:$version", DependencyScope.Test)

        /** Helper to create a dependency from a coordinate string. */
        fun dependency(path: String): Dependency = Dependency(path)
    }
}
