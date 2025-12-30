@file:Suppress("DEPRECATION")

import com.ivieleague.kbuild.common.*
import com.ivieleague.kbuild.intellij.IntelliJModuleBuild
import com.ivieleague.kbuild.intellij.IntelliJProjectBuild
import com.ivieleague.kbuild.junit.JUnitRun
import com.ivieleague.kbuild.jvm.JarBuild
import com.ivieleague.kbuild.jvm.Manifest
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.KotlinJvmCompile
import com.ivieleague.kbuild.kotlin.KotlinWithJavaCompile
import com.ivieleague.kbuild.maven.*
import java.io.File

/**
 * KBuild's self-build definition.
 *
 * Usage with CLI:
 *   kbuild Build.compile      # Compile main sources
 *   kbuild Build.jar          # Build JAR
 *   kbuild Build.test         # Run tests
 *   kbuild Build.publishLocal # Publish to local Maven
 *   kbuild Build.intellij     # Generate IntelliJ files
 *   kbuild --list             # List all targets
 */
object Build {
    private val aetherVersion = "1.0.0.v20140518"
    private val mavenVersion = "3.1.0"

    val projectIdentifier = ProjectIdentifier("com.ivieleague:kbuild:0.0.1")
    val root = File(".")

    // ==================== Dependencies ====================

    val pom by lazy {
        PomBuild(
            projectIdentifier = projectIdentifier,
            pomFile = root.resolve("kbuild/maven.pom"),
            configure = {
                repositories = listOf(
                    Repository("https://lightningkite-maven.s3.us-west-2.amazonaws.com", "lightningkite")
                )
                dependencies = listOf(
                    // Kotlin
                    Dependency(Kotlin.standardLibraryJvmId),
                    Dependency("org.jetbrains.kotlin:kotlin-reflect:${Kotlin.version}"),
                    Dependency("org.jetbrains.kotlin:kotlin-compiler-embeddable:${Kotlin.version}"),
                    Dependency("org.jetbrains.kotlin:kotlin-native-utils:${Kotlin.version}"),
                    Dependency("org.jetbrains.kotlin:kotlin-scripting-jsr223:${Kotlin.version}"),

                    // Reactive
                    Dependency("com.lightningkite:reactive-jvm:6.0.0-prerelease-26"),

                    // Serialization
                    Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3"),

                    // Maven/Aether
                    Dependency("org.eclipse.aether:aether-api:$aetherVersion"),
                    Dependency("org.eclipse.aether:aether-impl:$aetherVersion"),
                    Dependency("org.eclipse.aether:aether-util:$aetherVersion"),
                    Dependency("org.eclipse.aether:aether-connector-basic:$aetherVersion"),
                    Dependency("org.eclipse.aether:aether-transport-file:$aetherVersion"),
                    Dependency("org.eclipse.aether:aether-transport-http:$aetherVersion"),
                    Dependency("org.apache.maven:maven-aether-provider:$mavenVersion"),

                    // Utilities
                    Dependency("org.apache.commons:commons-text:1.11.0"),
                    Dependency("org.redundent:kotlin-xml-builder:1.9.1"),
                    Dependency("org.jasypt:jasypt:1.9.3"),

                    // CLI
                    Dependency("org.jline:jline:3.26.3"),

                    // Testing
                    Dependency("org.junit.jupiter:junit-jupiter-api:5.8.1"),
                    Dependency("org.junit.jupiter:junit-jupiter-engine:5.8.1"),
                    Dependency("org.junit.platform:junit-platform-launcher:1.10.2"),
                    Dependency(Kotlin.standardLibraryTestJunit5Id, DependencyScope.Test)
                )
            }
        )
    }

    // ==================== Source Sets ====================

    val sources: Producer<File> = { setOf(File("src/main/kotlin")) }
    val testSources: Producer<File> = { setOf(File("src/test/kotlin")) }
    val resources: Producer<File> = { setOf(File("resources")) }

    // ==================== Compilation ====================

    val compile = KotlinWithJavaCompile(
        name = projectIdentifier.name,
        sourceRoots = sources,
        classpathJars = pom.compileDependencies.default,
        arguments = {
            contextReceivers = true
        },
        cache = root.resolve("kbuild/main"),
        outputFolder = root.resolve("kbuild/main")
    )

    val compileTest = KotlinJvmCompile(
        name = projectIdentifier.name + "-test",
        sourceRoots = testSources,
        classpathJars = pom.testCompileDependencies.default + compile,
        arguments = {
            contextReceivers = true
        },
        cache = root.resolve("kbuild/kotlin/compileTestKotlin"),
        outputFolder = root.resolve("kbuild/classes/kotlin/test")
    )

    // ==================== Packaging ====================

    val jar = JarBuild(
        manifest = Manifest(),
        folders = compile + resources,
        output = root.resolve("kbuild/libs/${projectIdentifier.nameDashVersion}.jar")
    )

    val sourcesJar = JarBuild(
        folders = sources,
        output = root.resolve("kbuild/sources.jar")
    )

    // ==================== Testing ====================

    val test = JUnitRun(
        testModule = compileTest,
        classpath = compile + pom.testExecutionDependencies.default
    )

    // ==================== Publishing ====================

    val mavenPublish = MavenDeploy(
        pom = pom,
        default = jar,
        sources = sourcesJar
    )

    /** Publish to local Maven repository (~/.m2) */
    fun publishLocal() {
        mavenPublish.deploy(MavenAether.local)
        println("Published to local Maven repository (~/.m2/repository)")
    }

    // ==================== IDE ====================

    val intellij = IntelliJProjectBuild(
        root = root,
        modules = setOf(
            IntelliJModuleBuild(
                projectRoot = root,
                root = root,
                name = projectIdentifier.name,
                sourceRoots = sources,
                libraries = pom.compileDependencies
            )
        )
    )

    // ==================== Utilities ====================

    /** Clean all build outputs */
    fun clean() {
        root.resolve("kbuild").deleteRecursively()
        println("Cleaned build outputs")
    }

    /** Show build info */
    fun info() {
        println("KBuild Self-Build")
        println("  Version: ${projectIdentifier.version}")
        println("  Kotlin: ${Kotlin.version}")
        println("  Sources: ${sources()}")
        println("  Output: ${root.resolve("kbuild").absolutePath}")
    }
}
