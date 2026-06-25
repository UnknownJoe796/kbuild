package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.maven.MavenAether
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test that demonstrates using the kotlinx.serialization compiler plugin.
 *
 * Unlike KSP (which is a separate tool), kotlinx.serialization is a compiler plugin
 * that runs as part of the Kotlin compilation process.
 */
class KotlinxSerializationTest {

    @Test
    fun `compile with kotlinx serialization plugin using helper`() {
        val root = File("build/run/KotlinxSerializationTest").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val outputDir = root.resolve("classes")
        val cacheDir = root.resolve("cache")

        // Create a data class with @Serializable annotation
        srcDir.resolve("Message.kt").writeText("""
            package example

            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.decodeFromString

            @Serializable
            data class Message(
                val sender: String,
                val content: String,
                val timestamp: Long
            )

            fun main() {
                val message = Message("Alice", "Hello!", System.currentTimeMillis())
                val json = Json.encodeToString(message)
                println("Serialized: ${'$'}json")

                val decoded = Json.decodeFromString<Message>(json)
                println("Decoded: ${'$'}decoded")
            }
        """.trimIndent())

        // Use the SerializationPlugin helper!
        println("Getting serialization runtime classpath...")
        val serializationClasspath = runBlocking { SerializationPlugin.runtimeClasspath() }
        println("Serialization classpath: ${serializationClasspath.map { it.name }}")

        println("Getting serialization plugin JAR...")
        val serializationConfigurer = runBlocking { SerializationPlugin.configurer() }

        // Compile with the serialization plugin using the helper
        println("Compiling with serialization plugin...")
        val compiledOutput = kotlinJvmCompileBlocking(
            name = "serialization-test",
            sourceRoots = setOf(srcDir),
            classpathJars = serializationClasspath,
            arguments = serializationConfigurer,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(compiledOutput.exists(), "Compilation output should exist")

        // Verify compiled classes exist
        val compiledClasses = outputDir.walkTopDown()
            .filter { it.extension == "class" }
            .toList()

        println("Compiled classes:")
        compiledClasses.forEach { println("  - ${it.relativeTo(outputDir)}") }

        // Should have:
        // - Message.class (the data class)
        // - Message$$serializer.class (generated serializer)
        // - MessageKt.class (the main function)
        assertTrue(compiledClasses.any { it.name == "Message.class" }, "Should compile Message class")
        assertTrue(
            compiledClasses.any { it.name.contains("serializer") },
            "Should generate serializer class. Found: ${compiledClasses.map { it.name }}"
        )

        println("SUCCESS: kotlinx.serialization plugin works with helper!")
    }

    @Test
    fun `serialization plugin works alongside KSP`() {
        val root = File("build/run/SerializationWithKspTest").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kspKotlinOutputDir = root.resolve("ksp/kotlin")
        val kspJavaOutputDir = root.resolve("ksp/java")
        val kspResourceOutputDir = root.resolve("ksp/resources")
        val kspClassOutputDir = root.resolve("ksp/classes")
        val kspCacheDir = root.resolve("ksp/cache")
        val outputDir = root.resolve("classes")
        val cacheDir = root.resolve("compile-cache")

        // Create a data class with BOTH @Serializable and @JsonClass (Moshi)
        srcDir.resolve("User.kt").writeText("""
            package example

            import kotlinx.serialization.Serializable
            import com.squareup.moshi.JsonClass

            // Using kotlinx.serialization
            @Serializable
            data class User(
                val id: Long,
                val name: String,
                val email: String?
            )

            // Using Moshi (processed by KSP)
            @JsonClass(generateAdapter = true)
            data class Profile(
                val userId: Long,
                val bio: String,
                val followers: Int
            )
        """.trimIndent())

        // Use helpers for dependencies
        println("Resolving dependencies...")
        val serializationClasspath = runBlocking { SerializationPlugin.runtimeClasspath() }
        val moshiLibs = runBlocking {
            MavenAether.libraries(
                path = "com.squareup.moshi:moshi:1.15.2",
                fetchSources = false
            )
        }
        val combinedClasspath = serializationClasspath + moshiLibs.mapNotNull { it.default }.toSet()

        // Get Moshi KSP processor
        val moshiCodegenLibs = runBlocking {
            MavenAether.libraries(
                path = "com.squareup.moshi:moshi-kotlin-codegen:1.15.2",
                fetchSources = false
            )
        }
        val moshiProcessorClasspath = moshiCodegenLibs.mapNotNull { it.default }.toSet()

        // Step 1: Run KSP for Moshi
        println("Running KSP for Moshi...")
        val generatedSources = kspJvmProcessBlocking(
            name = "combined-test",
            sourceRoots = setOf(srcDir),
            classpathJars = combinedClasspath,
            processorClasspath = moshiProcessorClasspath,
            kotlinOutputDir = kspKotlinOutputDir,
            javaOutputDir = kspJavaOutputDir,
            resourceOutputDir = kspResourceOutputDir,
            classOutputDir = kspClassOutputDir,
            cacheDir = kspCacheDir
        )

        println("KSP generated sources: $generatedSources")

        // Step 2: Compile with serialization plugin (includes KSP-generated sources)
        println("Compiling with serialization plugin...")
        val allSourceRoots = setOf(srcDir) + generatedSources
        val serializationConfigurer = runBlocking { SerializationPlugin.configurer() }

        val compiledOutput = kotlinJvmCompileBlocking(
            name = "combined-test",
            sourceRoots = allSourceRoots,
            classpathJars = combinedClasspath,
            arguments = serializationConfigurer,
            cache = cacheDir,
            outputFolder = outputDir
        )

        assertTrue(compiledOutput.exists(), "Compilation should succeed")

        val compiledClasses = outputDir.walkTopDown()
            .filter { it.extension == "class" }
            .toList()

        println("Compiled classes:")
        compiledClasses.forEach { println("  - ${it.relativeTo(outputDir)}") }

        // Verify both processors worked
        assertTrue(
            compiledClasses.any { it.name.contains("serializer") },
            "Should have kotlinx.serialization generated serializer"
        )
        assertTrue(
            compiledClasses.any { it.name.contains("ProfileJsonAdapter") },
            "Should have Moshi-generated JsonAdapter"
        )

        println("SUCCESS: kotlinx.serialization + KSP work together!")
    }
}
