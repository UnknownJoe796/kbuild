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
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.junit.junitRunBlocking
import com.ivieleague.kbuild.jvm.jarBuildBlocking
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
    val coreDependencies = listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
        "org.jetbrains.kotlin:kotlin-reflect:2.3.20",
        "com.lightningkite:reactive-jvm:6.0.0-prerelease-26",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3",
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20",
        "org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20",
        "org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20",
        "net.bytebuddy:byte-buddy:1.14.11",
        "net.bytebuddy:byte-buddy-agent:1.14.11",
        "org.jetbrains.kotlin:kotlin-scripting-jsr223:2.3.20",
        "org.jetbrains.kotlin:kotlin-native-utils:2.3.20",
        "com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.9",
        "com.google.devtools.ksp:symbol-processing-api:2.3.9",
        "com.google.devtools.ksp:symbol-processing-common-deps:2.3.9",
        "org.jline:jline:3.26.3",
        "org.eclipse.aether:aether-api:1.0.0.v20140518",
        "org.eclipse.aether:aether-impl:1.0.0.v20140518",
        "org.eclipse.aether:aether-util:1.0.0.v20140518",
        "org.eclipse.aether:aether-connector-basic:1.0.0.v20140518",
        "org.eclipse.aether:aether-transport-file:1.0.0.v20140518",
        "org.eclipse.aether:aether-transport-http:1.0.0.v20140518",
        "org.apache.maven:maven-aether-provider:3.1.0",
        "org.redundent:kotlin-xml-builder:1.9.1",
        "org.apache.commons:commons-text:1.11.0",
        "org.jasypt:jasypt:1.9.3",
        "io.methvin:directory-watcher:0.18.0",
        "org.junit.jupiter:junit-jupiter-api:5.8.1",
        "org.junit.jupiter:junit-jupiter-engine:5.8.1",
        "org.junit.platform:junit-platform-launcher:1.10.2"
    )

    val testDependencies = listOf(
        "org.jetbrains.kotlin:kotlin-test:2.3.20",
        "org.jetbrains.kotlin:kotlin-test-junit5:2.3.20"
    )

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

        kotlinJvmCompileBlocking(
            name = "kbuild-main",
            sourceRoots = setOf(srcMain),
            classpathJars = coreClasspath,
            arguments = {},
            cache = cacheDir.resolve("main"),
            outputFolder = classesDir,
            enableContextParameters = true
        )

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
            classpathJars = Constant(coreClasspath),
            arguments = {},
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

        kotlinJvmCompileBlocking(
            name = "kbuild-test",
            sourceRoots = setOf(srcTest),
            classpathJars = coreClasspath + testClasspath + setOf(classesDir),
            // Declare the main output as a friend module so tests can access `internal`
            // declarations of main — the same mechanism Gradle's test source set uses.
            arguments = { friendPaths = arrayOf(classesDir.absolutePath) },
            cache = cacheDir.resolve("test"),
            outputFolder = testClassesDir,
            enableContextParameters = true
        )

        println("  -> $testClassesDir")
        return testClassesDir
    }

    /**
     * Run tests.
     */
    fun test() {
        println("=== Running Tests ===")
        compileTest()

        val results: Set<TestResult> = junitRunBlocking(
            testModule = testClassesDir,
            classpath = setOf(classesDir) + coreClasspath + testClasspath
        )

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

        jarBuildBlocking(
            manifest = manifest,
            folders = setOf(classesDir),
            output = jarFile
        )

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

        jarBuildBlocking(
            manifest = manifest,
            folders = setOf(srcMain),
            output = jarFile
        )

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
            default = { mainJar },
            sources = { sources }
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
                ".pom" to pom()
            ),
            output = ::println
        )
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
