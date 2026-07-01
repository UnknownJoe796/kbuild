/**
 * KBuild Bootstrap Build
 *
 * This Build.kt builds KBuild using KBuild - the ultimate dogfooding.
 *
 * Bootstrap process:
 * 1. First build: Use Gradle to build kbuild JAR
 * 2. Subsequent builds: Use kbuild to rebuild itself
 *
 * Usage:
 *   ./run-kbuild.sh Build.compile      # Compile sources
 *   ./run-kbuild.sh Build.compileTest  # Compile tests
 *   ./run-kbuild.sh Build.test         # Run tests
 *   ./run-kbuild.sh Build.jar          # Create JAR
 *   ./run-kbuild.sh Build.sourcesJar   # Create sources JAR
 *   ./run-kbuild.sh Build.publishLocal # Publish to Maven Local
 *   ./run-kbuild.sh Build.publishS3    # Publish to S3
 *   ./run-kbuild.sh Build.clean        # Clean build outputs
 *   ./run-kbuild.sh Build.build        # Full build (clean, compile, test, jar)
 *
 * Watch mode (reactive):
 *   ./run-kbuild.sh Build.compile --watch   # Recompile on source changes
 */
package build

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.intellij.IntelliJModuleBuild
import com.ivieleague.kbuild.intellij.IntelliJProjectBuild
import com.ivieleague.kbuild.kotlin.SerializationPlugin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.ivieleague.kbuild.junit.junitRun
import com.ivieleague.kbuild.jvm.jarBuild
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.MavenDeploy
import com.ivieleague.kbuild.maven.PomBuild
import com.ivieleague.kbuild.maven.S3MavenPublish
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.core.Constant
import kotlinx.coroutines.runBlocking
import org.eclipse.aether.repository.RemoteRepository
import java.io.File
import java.util.jar.Manifest

object Build {
    // ===== Project Layout =====
    val projectRoot = File(".")
    val srcMain = projectRoot.resolve("src/main/kotlin")
    val srcTest = projectRoot.resolve("src/test/kotlin")
    val buildDir = projectRoot.resolve("kbuild-out")
    val classesDir = buildDir.resolve("classes/main")
    val testClassesDir = buildDir.resolve("classes/test")
    val cacheDir = buildDir.resolve("cache")
    val libsDir = buildDir.resolve("libs")

    // ===== Project Metadata =====
    val groupId = "com.ivieleague"
    val artifactId = "kbuild"
    val version = "1.0-SNAPSHOT"

    // ===== Maven Repositories =====
    val repositories = listOf(
        MavenAether.central,
        MavenAether.local,
        RemoteRepository.Builder(
            "lightningkite",
            "default",
            "https://lightningkite-maven.s3.us-west-2.amazonaws.com"
        ).build()
    )

    // ===== Dependencies =====
    // Single source of truth: bootstrap/dependencies.txt (also read by the Gradle escape hatch).
    // Edit dependency versions there, not here, so the two builds can never drift apart.
    private fun directDependencies(vararg scopes: String): List<String> {
        val wanted = scopes.toSet()
        return projectRoot.resolve("bootstrap/dependencies.txt").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts[0] in wanted) parts[1] else null
            }
    }

    // Runtime-only deps go in the compile classpath too: kbuild resolves transitively and runs from
    // the same set, and a superset compile classpath is harmless (the public API is what we use).
    val coreDependencies = directDependencies("core", "runtime")

    val testDependencies = directDependencies("test")

    // ===== Helpers =====
    private fun Set<Library>.toFiles(): Set<File> = mapTo(HashSet()) { it.default }

    // ===== Resolved Dependencies (lazy, cached) =====
    val coreLibraries: Set<Library> by lazy {
        println("Resolving core dependencies...")
        coreDependencies.flatMap { coord ->
            runBlocking {
                MavenAether.libraries(
                    path = coord,
                    repositories = repositories,
                    output = System.out,
                    fetchSources = false
                )
            }
        }.toSet()
    }

    val coreClasspath: Set<File> by lazy { coreLibraries.toFiles() }

    val testLibraries: Set<Library> by lazy {
        println("Resolving test dependencies...")
        testDependencies.flatMap { coord ->
            runBlocking {
                MavenAether.libraries(
                    path = coord,
                    repositories = repositories,
                    output = System.out,
                    fetchSources = false
                )
            }
        }.toSet()
    }

    val testClasspath: Set<File> by lazy { testLibraries.toFiles() }

    // kbuild's own sources use @Serializable (e.g. CompilationMessage, TestResult, PackageJson),
    // so every compilation of kbuild must apply the kotlinx.serialization compiler plugin —
    // exactly as the from-source bootstrap does. Without it, the compiled classes lack their
    // generated serializers and fail at runtime with "Serializer ... not found".
    val serializationPluginJar: File by lazy {
        runBlocking { SerializationPlugin.pluginJar() }
    }

    // ===== Build Targets =====

    // ===== Reactive Sources (for watch mode) =====
    val mainSources = DirectoryWatch(srcMain, "**/*.kt")
    val testSources = DirectoryWatch(srcTest, "**/*.kt")

    /**
     * Compile main sources (blocking).
     */
    fun compile(): File {
        println("=== Compiling KBuild ===")
        classesDir.mkdirs()

        runBlocking {
            kotlinJvmCompile(
                name = "kbuild-main",
                sourceRoots = Constant(setOf(srcMain)),
                classpathJars = coreClasspath,
                arguments = { pluginClasspaths = (pluginClasspaths ?: emptyArray()) + serializationPluginJar.absolutePath },
                cache = cacheDir.resolve("main"),
                outputFolder = classesDir,
                enableContextParameters = true
            )
        }

        println("  -> $classesDir")
        return classesDir
    }

    /**
     * Compile main sources (reactive - for watch mode).
     * Use with: ./run-kbuild.sh Build.compileReactive --watch
     */
    suspend fun compileReactive(): File {
        println("=== Compiling KBuild (Reactive) ===")
        classesDir.mkdirs()

        return kotlinJvmCompile(
            name = "kbuild-main",
            sourceRoots = mainSources,
            classpathJars = coreClasspath,
            arguments = { pluginClasspaths = (pluginClasspaths ?: emptyArray()) + serializationPluginJar.absolutePath },
            cache = cacheDir.resolve("main"),
            outputFolder = classesDir
        )
    }

    /**
     * Compile test sources.
     */
    fun compileTest(): File {
        println("=== Compiling Tests ===")
        compile()
        testClassesDir.mkdirs()

        runBlocking {
            kotlinJvmCompile(
                name = "kbuild-test",
                sourceRoots = Constant(setOf(srcTest)),
                classpathJars = coreClasspath + testClasspath + setOf(classesDir),
                // Declare the main output as a friend module so tests can access `internal`
                // declarations of main — the same mechanism Gradle's test source set uses.
                arguments = {
                    friendPaths = arrayOf(classesDir.absolutePath)
                    pluginClasspaths = (pluginClasspaths ?: emptyArray()) + serializationPluginJar.absolutePath
                },
                cache = cacheDir.resolve("test"),
                outputFolder = testClassesDir,
                enableContextParameters = true
            )
        }

        println("  -> $testClassesDir")
        return testClassesDir
    }

    /**
     * Run tests.
     */
    fun test() {
        println("=== Running Tests ===")
        compileTest()

        val results: Set<TestResult> = runBlocking {
            junitRun(
                testModule = Constant(testClassesDir),
                classpath = setOf(classesDir) + coreClasspath + testClasspath
            )
        }

        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        val total = results.size

        println()
        println("Tests: $passed passed, $failed failed (total: $total)")

        if (failed > 0) {
            println("\nFailed tests:")
            results.filter { !it.passed }.forEach { result ->
                println("  - ${result.identifier}: ${result.error ?: "failed"}")
            }
            throw RuntimeException("$failed tests failed")
        }
    }

    /**
     * Create JAR file.
     */
    fun jar(): File {
        println("=== Creating JAR ===")
        compile()
        libsDir.mkdirs()

        val jarFile = libsDir.resolve("kbuild-$version.jar")

        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", "KBuild")
            mainAttributes.putValue("Implementation-Version", version)
        }

        runBlocking {
            jarBuild(
                manifest = manifest,
                folders = setOf(classesDir),
                output = jarFile
            )
        }

        println("  -> $jarFile")
        return jarFile
    }

    /**
     * Create sources JAR.
     */
    fun sourcesJar(): File {
        println("=== Creating Sources JAR ===")

        libsDir.mkdirs()
        val jarFile = libsDir.resolve("kbuild-$version-sources.jar")

        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }

        runBlocking {
            jarBuild(
                manifest = manifest,
                folders = setOf(srcMain),
                output = jarFile
            )
        }

        println("  -> $jarFile")
        return jarFile
    }

    /**
     * Publish to Maven Local.
     */
    fun publishLocal() {
        println("=== Publishing to Maven Local ===")

        val mainJar = jar()
        val sources = sourcesJar()

        val pomFile = libsDir.resolve("kbuild-$version.pom")
        val pom = PomBuild(
            projectIdentifier = com.ivieleague.kbuild.common.ProjectIdentifier(
                group = groupId,
                name = artifactId,
                version = com.ivieleague.kbuild.common.Version(version)
            ),
            pomFile = pomFile
        ) {
            packaging = "jar"
            name = "KBuild"
            description = "Reactive build library for Kotlin"

            coreDependencies.forEach { coord ->
                val parts = coord.split(":")
                dependencies.add(org.apache.maven.model.Dependency().apply {
                    groupId = parts[0]
                    artifactId = parts[1]
                    this.version = parts[2]
                    scope = "compile"
                })
            }
        }

        val deploy = MavenDeploy(
            pom = pom,
            default = mainJar,
            sources = sources
        )
        deploy.deploy(MavenAether.local)

        println("Published to ~/.m2/repository/${groupId.replace('.', '/')}/$artifactId/$version/")
    }

    /**
     * Publish to S3 Maven repository.
     *
     * Requires environment variables:
     * - AWS_ACCESS_KEY_ID
     * - AWS_SECRET_ACCESS_KEY
     * - AWS_REGION (optional, defaults to us-west-2)
     */
    fun publishS3(bucket: String = "lightningkite-maven") {
        println("=== Publishing to S3: $bucket ===")

        val mainJar = jar()
        val sources = sourcesJar()

        val pomFile = libsDir.resolve("kbuild-$version.pom")
        val pom = PomBuild(
            projectIdentifier = com.ivieleague.kbuild.common.ProjectIdentifier(
                group = groupId,
                name = artifactId,
                version = com.ivieleague.kbuild.common.Version(version)
            ),
            pomFile = pomFile
        ) {
            packaging = "jar"
            name = "KBuild"
            description = "Reactive build library for Kotlin"

            coreDependencies.forEach { coord ->
                val parts = coord.split(":")
                dependencies.add(org.apache.maven.model.Dependency().apply {
                    groupId = parts[0]
                    artifactId = parts[1]
                    this.version = parts[2]
                    scope = "compile"
                })
            }
        }

        val s3 = S3MavenPublish.fromEnvironment(
            bucketName = bucket,
            region = System.getenv("AWS_REGION") ?: "us-west-2"
        )

        s3.publish(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            artifacts = mapOf(
                "" to mainJar,
                "-sources" to sources,
                ".pom" to pom.write()
            ),
            output = ::println
        )
    }

    /**
     * Generate IntelliJ IDEA project files (.idea/ + kbuild.iml) for developing kbuild itself.
     *
     * Produces a single JVM module whose source roots are src/main/kotlin (production) and
     * src/test/kotlin (tests), with every resolved dependency wired in as a project library
     * (classes + sources, so navigation into dependency sources works). This drives kbuild's
     * own IntelliJ generators directly — no Gradle and no GradleIdeBuild bridge.
     *
     * The generated .idea folder and .iml file are gitignored.
     */
    fun ide(): File {
        println("=== Generating IntelliJ project for kbuild ===")
        println("Resolving dependencies with sources (for IDE navigation)...")

        val libraries = (coreDependencies + testDependencies).flatMap { coord ->
            runBlocking {
                MavenAether.libraries(
                    path = coord,
                    repositories = repositories,
                    output = System.out,
                    fetchSources = true
                )
            }
        }.toSet()

        // Canonical paths so the module's source-folder URLs are clean relative paths.
        val root = projectRoot.canonicalFile
        val module = IntelliJModuleBuild(
            projectRoot = root,
            name = artifactId,
            sourceRoots = { setOf(srcMain.canonicalFile) },
            testSourceRoots = { setOf(srcTest.canonicalFile) },
            libraries = { libraries }
        )
        IntelliJProjectBuild(root = root, modules = setOf(module))()

        println("  -> ${root.resolve(".idea")}")
        println("  -> ${root.resolve("$artifactId.iml")}")
        return root
    }

    /**
     * Clean build outputs.
     */
    fun clean() {
        println("Cleaning build directory...")
        buildDir.deleteRecursively()
        println("Done.")
    }

    /**
     * Full build: clean, compile, test, jar.
     */
    fun build() {
        clean()
        compile()
        test()
        jar()
        println("\n=== Build Complete ===")
    }
}
