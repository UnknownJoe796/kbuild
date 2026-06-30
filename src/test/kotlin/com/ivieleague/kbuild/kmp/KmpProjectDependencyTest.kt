package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Dependency
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for KmpProject dependency integration.
 *
 * Verifies that:
 * 1. Common dependencies are available during JVM compilation
 * 2. Target-specific dependencies work correctly
 * 3. Dependencies chain through source sets properly
 * 4. Projects with multiple targets share common dependencies
 */
class KmpProjectDependencyTest {

    @Test
    fun `project with common dependency compiles JVM`() {
        val root = File("build/run/KmpProjectCommonDepTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source that uses kotlinx-serialization
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("SerializationTest.kt").writeText("""
            package mylib

            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.decodeFromString

            @Serializable
            data class Person(val name: String, val age: Int)

            fun serializePerson(person: Person): String = Json.encodeToString(person)

            fun deserializePerson(json: String): Person = Json.decodeFromString(json)
        """.trimIndent())

        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }
        jvmSrc.resolve("Main.kt").writeText("""
            package mylib

            fun main() {
                val person = Person("Alice", 30)
                val json = serializePerson(person)
                println("Serialized: ${'$'}json")

                val restored = deserializePerson(json)
                println("Deserialized: ${'$'}restored")
            }
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "serialization-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0"))
        )

        val output = runBlocking { kmpCompileJvmBlocking(project) }

        assertTrue(output.exists(), "Output should exist")

        val classFiles = output.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "Should have compiled class files")

        // Verify Person class was compiled
        assertTrue(
            classFiles.any { it.name == "Person.class" },
            "Person.class should exist"
        )

        println("Successfully compiled with kotlinx-serialization dependency")
        classFiles.forEach { println("  - ${it.relativeTo(output)}") }
    }

    @Test
    fun `project with multiple common dependencies compiles`() {
        val root = File("build/run/KmpProjectMultiDepTest")
        root.deleteRecursively()
        root.mkdirs()

        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("MultiDep.kt").writeText("""
            package mylib

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.runBlocking
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.encodeToString

            @Serializable
            data class Message(val text: String, val timestamp: Long)

            fun processMessage(msg: Message): String = runBlocking {
                delay(1)
                Json.encodeToString(msg)
            }
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "multi-dep-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(
                Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"),
                Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            )
        )

        assertEquals(2, project.commonDependencies.size, "Should have 2 common dependencies")

        val output = runBlocking { kmpCompileJvmBlocking(project) }

        assertTrue(output.exists(), "Output should exist")

        println("Successfully compiled with multiple dependencies")
    }

    @Test
    fun `project builder adds dependencies correctly`() {
        val root = File("build/run/KmpProjectBuilderDepTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "builder-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host()),
            commonDependencies = setOf(
                Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"),
                Dependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            ),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(Dependency("org.slf4j:slf4j-api:2.0.9"))
            )
        )

        // Verify common dependencies
        assertEquals(2, project.commonDependencies.size)
        assertTrue(
            project.commonDependencies.any { it.artifactId == "kotlinx-coroutines-core" }
        )
        assertTrue(
            project.commonDependencies.any { it.artifactId == "kotlinx-serialization-json" }
        )

        // Verify JVM-specific dependency
        assertEquals(1, project.targetDependencies[KmpTarget.Jvm]?.size)
        assertTrue(
            project.targetDependencies[KmpTarget.Jvm]?.any { it.artifactId == "slf4j-api" } == true
        )

        // Verify JS has no target-specific
        assertTrue(project.targetDependencies[KmpTarget.Js].isNullOrEmpty())
    }

    @Test
    fun `source sets include dependencies from hierarchy`() {
        val root = File("build/run/KmpSourceSetDepChainTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "sourceset-chain-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Native.host())
        )

        // Verify JVM source set chain
        val jvmSourceSet = project.sourceSets.jvmMain
        assertTrue(project.sourceSets.commonMain in jvmSourceSet.dependsOn)

        // Verify Native source set chain
        val hostTarget = KmpTarget.Native.host()
        val nativeSourceSet = project.sourceSets.getSourceSetForTarget(hostTarget)
        assertTrue(nativeSourceSet != null, "Should have source set for host target")

        val allDeps = nativeSourceSet!!.allDependsOn
        assertTrue(project.sourceSets.commonMain in allDeps, "Should have commonMain in chain")
        assertTrue(project.sourceSets.nativeMain in allDeps, "Should have nativeMain in chain")
    }

    @Test
    fun `KmpProject dependencies resolver is properly initialized`() {
        val root = File("build/run/KmpProjectResolverInitTest")
        root.deleteRecursively()

        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        val project = KmpProjectConfig(
            name = "resolver-init-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js),
            commonDependencies = setOf(coroutines)
        )

        // Verify dependencies resolver
        assertEquals(setOf(KmpTarget.Jvm, KmpTarget.Js), project.dependencies.targets)
        assertEquals(setOf(coroutines), project.dependencies.commonDependencies)
    }

    @Test
    fun `project getSourcesForTarget returns correct directories`() {
        val root = File("build/run/KmpGetSourcesTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directories
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("Common.kt").writeText("package mylib\nfun common() = 1")
        jvmSrc.resolve("Jvm.kt").writeText("package mylib\nfun jvm() = 2")

        val project = KmpProjectConfig(name = "sources-test", projectRoot = root, targets = setOf(KmpTarget.Jvm))

        val sources = project.getSourcesForTarget(KmpTarget.Jvm)

        // Should have both commonMain and jvmMain
        assertEquals(2, sources.size, "Should have 2 source directories")
        assertTrue(
            sources.any { it.path.contains("commonMain") },
            "Should include commonMain"
        )
        assertTrue(
            sources.any { it.path.contains("jvmMain") },
            "Should include jvmMain"
        )
    }

    @Test
    fun `project getSourcesForTarget for native includes full hierarchy`() {
        val root = File("build/run/KmpNativeSourcesTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create source directories
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        val nativeSrc = root.resolve("src/nativeMain/kotlin").also { it.mkdirs() }
        val appleSrc = root.resolve("src/appleMain/kotlin").also { it.mkdirs() }
        val macosSrc = root.resolve("src/macosMain/kotlin").also { it.mkdirs() }
        val macosArm64Src = root.resolve("src/macosArm64Main/kotlin").also { it.mkdirs() }

        // Create files so directories are detected
        commonSrc.resolve("Common.kt").writeText("package mylib")
        nativeSrc.resolve("Native.kt").writeText("package mylib")
        appleSrc.resolve("Apple.kt").writeText("package mylib")
        macosSrc.resolve("MacOS.kt").writeText("package mylib")
        macosArm64Src.resolve("MacosArm64.kt").writeText("package mylib")

        val project = KmpProjectConfig(name = "native-sources-test", projectRoot = root, targets = setOf(KmpTarget.Native.MacosArm64))

        val sources = project.getSourcesForTarget(KmpTarget.Native.MacosArm64)

        // Should include the full hierarchy
        assertEquals(5, sources.size, "Should have 5 source directories in hierarchy")
        assertTrue(sources.any { it.path.contains("commonMain") })
        assertTrue(sources.any { it.path.contains("nativeMain") })
        assertTrue(sources.any { it.path.contains("appleMain") })
        assertTrue(sources.any { it.path.contains("macosMain") })
        assertTrue(sources.any { it.path.contains("macosArm64Main") })
    }

    @Test
    fun `project with target-specific JVM dependency compiles`() {
        val root = File("build/run/KmpTargetDepTest")
        root.deleteRecursively()
        root.mkdirs()

        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }
        jvmSrc.resolve("Logging.kt").writeText("""
            package mylib

            import org.slf4j.LoggerFactory

            object Logger {
                private val log = LoggerFactory.getLogger(Logger::class.java)

                fun info(msg: String) = log.info(msg)
            }
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "target-dep-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(Dependency("org.slf4j:slf4j-api:2.0.9"))
            )
        )

        val output = runBlocking { kmpCompileJvmBlocking(project) }

        assertTrue(output.exists(), "Output should exist")

        println("Successfully compiled with JVM-specific slf4j dependency")
    }

    @Test
    fun `project printSummary shows targets and dependencies`() {
        val root = File("build/run/KmpSummaryTest")
        root.deleteRecursively()

        val project = KmpProjectConfig(
            name = "summary-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js),
            commonDependencies = setOf(Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"))
        )

        // Just verify it doesn't crash
        project.printSummary()

        // Basic verification
        assertEquals("summary-test", project.name)
        assertEquals(root, project.projectRoot)
        assertTrue(KmpTarget.Jvm in project.targets)
        assertTrue(KmpTarget.Js in project.targets)
    }

    @Test
    fun `project buildAll uses dependencies for each target`() {
        val root = File("build/run/KmpBuildAllDepTest")
        root.deleteRecursively()
        root.mkdirs()

        // Simple code that works on all platforms (no expect/actual - requires multiplatform compiler flags)
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("Common.kt").writeText("""
            package mylib

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.runBlocking

            fun platformGreeting(): String = "Hello from common"

            fun greetWithDelay(): String = runBlocking {
                delay(1)
                platformGreeting()
            }
        """.trimIndent())

        val jvmSrc = root.resolve("src/jvmMain/kotlin").also { it.mkdirs() }
        jvmSrc.resolve("Platform.kt").writeText("""
            package mylib

            fun jvmSpecific(): String = "JVM: " + platformGreeting()
        """.trimIndent())

        val project = KmpProjectConfig(
            name = "build-all-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"))
        )

        // Verify structure
        assertEquals(1, project.commonDependencies.size)

        // Build should work (creates compilers with proper dependencies)
        val jvmOutput = runBlocking { kmpCompileJvmBlocking(project) }
        assertTrue(jvmOutput.exists())
    }

    @Test
    fun `dependency resolution is isolated per target`() {
        val root = File("build/run/KmpDepIsolationTest")
        root.deleteRecursively()

        val jvmDep = Dependency("org.example:jvm-only:1.0.0")
        val jsDep = Dependency("org.example:js-only:1.0.0")
        val nativeDep = Dependency("org.example:native-only:1.0.0")

        val project = KmpProjectConfig(
            name = "isolation-test",
            projectRoot = root,
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js, KmpTarget.Native.host()),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(jvmDep),
                KmpTarget.Js to setOf(jsDep),
                KmpTarget.Native.host() to setOf(nativeDep)
            )
        )

        // Each target should only have its specific dependency
        assertEquals(1, project.targetDependencies[KmpTarget.Jvm]?.size)
        assertEquals(1, project.targetDependencies[KmpTarget.Js]?.size)
        assertEquals(1, project.targetDependencies[KmpTarget.Native.host()]?.size)

        // Verify isolation
        assertTrue(
            project.targetDependencies[KmpTarget.Jvm]?.none { it.artifactId == "js-only" } == true
        )
        assertTrue(
            project.targetDependencies[KmpTarget.Js]?.none { it.artifactId == "jvm-only" } == true
        )
    }

    @Test
    fun `kmpDependency helper creates correct dependency`() {
        val dep = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        assertEquals("org.jetbrains.kotlinx", dep.groupId)
        assertEquals("kotlinx-coroutines-core", dep.artifactId)
        assertEquals("1.7.3", dep.version)

        // Verify it resolves correctly for different targets
        assertEquals("kotlinx-coroutines-core-jvm", dep.artifactIdForTarget(KmpTarget.Jvm))
        assertEquals("kotlinx-coroutines-core-js", dep.artifactIdForTarget(KmpTarget.Js))
        assertEquals("kotlinx-coroutines-core-macosarm64", dep.artifactIdForTarget(KmpTarget.Native.MacosArm64))
    }

    @Test
    fun `project with no dependencies still compiles`() {
        val root = File("build/run/KmpNoDepTest")
        root.deleteRecursively()
        root.mkdirs()

        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("NoDeps.kt").writeText("""
            package mylib

            fun add(a: Int, b: Int): Int = a + b

            fun multiply(a: Int, b: Int): Int = a * b
        """.trimIndent())

        val project = KmpProjectConfig(name = "no-deps-test", projectRoot = root, targets = setOf(KmpTarget.Jvm))

        assertTrue(project.commonDependencies.isEmpty())
        assertTrue(project.targetDependencies.isEmpty())

        val output = runBlocking { kmpCompileJvmBlocking(project) }
        assertTrue(output.exists(), "Should compile even without dependencies")
    }
}
