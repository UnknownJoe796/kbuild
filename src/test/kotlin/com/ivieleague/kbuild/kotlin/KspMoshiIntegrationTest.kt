package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.maven.MavenAether
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test that uses the Moshi KSP processor to generate JSON adapters.
 */
class KspMoshiIntegrationTest {

    @Test
    fun `kspJvmProcessBlocking generates Moshi adapters`() {
        val root = File("build/run/KspMoshiIntegrationTest").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kotlinOutputDir = root.resolve("generated/kotlin")
        val javaOutputDir = root.resolve("generated/java")
        val resourceOutputDir = root.resolve("generated/resources")
        val classOutputDir = root.resolve("generated/classes")
        val cacheDir = root.resolve("cache")

        // Create a data class with Moshi annotations
        srcDir.resolve("User.kt").writeText("""
            package example

            import com.squareup.moshi.JsonClass

            @JsonClass(generateAdapter = true)
            data class User(
                val name: String,
                val age: Int,
                val email: String?
            )
        """.trimIndent())

        // Resolve Moshi dependencies
        println("Resolving Moshi dependencies...")
        val moshiLibs = MavenAether.librariesParallel(
            path = "com.squareup.moshi:moshi:1.15.2",
            fetchSources = false
        )
        val moshiClasspath = moshiLibs.mapNotNull { it.default }.toSet()
        println("Moshi classpath: ${moshiClasspath.map { it.name }}")

        // Resolve Moshi KSP processor
        println("Resolving Moshi KSP processor...")
        val moshiCodegenLibs = MavenAether.librariesParallel(
            path = "com.squareup.moshi:moshi-kotlin-codegen:1.15.2",
            fetchSources = false
        )
        val processorClasspath = moshiCodegenLibs.mapNotNull { it.default }.toSet()
        println("Processor classpath: ${processorClasspath.map { it.name }}")

        // Run KSP
        println("Running KSP...")
        val result = kspJvmProcessBlocking(
            name = "moshi-test",
            sourceRoots = setOf(srcDir),
            classpathJars = moshiClasspath,
            processorClasspath = processorClasspath,
            kotlinOutputDir = kotlinOutputDir,
            javaOutputDir = javaOutputDir,
            resourceOutputDir = resourceOutputDir,
            classOutputDir = classOutputDir,
            cacheDir = cacheDir
        )

        println("KSP result directories: $result")

        // Verify generated adapter exists
        val generatedFiles = kotlinOutputDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        println("Generated Kotlin files:")
        generatedFiles.forEach { file ->
            println("  - ${file.relativeTo(kotlinOutputDir)}")
            println("    Content preview: ${file.readText().take(200)}...")
        }

        assertTrue(result.isNotEmpty(), "KSP should generate output directories")
        assertTrue(generatedFiles.isNotEmpty(), "KSP should generate Kotlin files")

        // Check for the adapter file
        val adapterFile = generatedFiles.find { it.name.contains("UserJsonAdapter") }
        assertTrue(adapterFile != null, "Should generate UserJsonAdapter.kt, found: ${generatedFiles.map { it.name }}")

        // Verify the generated code contains expected content
        val adapterContent = adapterFile!!.readText()
        assertTrue(adapterContent.contains("class UserJsonAdapter"), "Should contain UserJsonAdapter class")
        assertTrue(adapterContent.contains("fromJson"), "Should contain fromJson method")
        assertTrue(adapterContent.contains("toJson"), "Should contain toJson method")

        println("SUCCESS: Moshi adapter generated correctly!")
    }

    @Test
    fun `generated Moshi adapter compiles with Kotlin compiler`() {
        val root = File("build/run/KspMoshiCompileTest").absoluteFile
        root.deleteRecursively()
        root.mkdirs()

        val srcDir = root.resolve("src").also { it.mkdirs() }
        val kspKotlinOutputDir = root.resolve("ksp/kotlin")
        val kspJavaOutputDir = root.resolve("ksp/java")
        val kspResourceOutputDir = root.resolve("ksp/resources")
        val kspClassOutputDir = root.resolve("ksp/classes")
        val kspCacheDir = root.resolve("ksp/cache")
        val compileOutputDir = root.resolve("classes")
        val compileCache = root.resolve("compile-cache")

        // Create source with Moshi annotation
        srcDir.resolve("Person.kt").writeText("""
            package example

            import com.squareup.moshi.JsonClass

            @JsonClass(generateAdapter = true)
            data class Person(
                val firstName: String,
                val lastName: String,
                val age: Int
            )
        """.trimIndent())

        // Resolve dependencies
        println("Resolving dependencies...")
        val moshiLibs = MavenAether.librariesParallel(
            path = "com.squareup.moshi:moshi:1.15.2",
            fetchSources = false
        )
        val moshiClasspath = moshiLibs.mapNotNull { it.default }.toSet()

        val moshiCodegenLibs = MavenAether.librariesParallel(
            path = "com.squareup.moshi:moshi-kotlin-codegen:1.15.2",
            fetchSources = false
        )
        val processorClasspath = moshiCodegenLibs.mapNotNull { it.default }.toSet()

        // Run KSP
        println("Running KSP...")
        val generatedDirs = kspJvmProcessBlocking(
            name = "moshi-compile-test",
            sourceRoots = setOf(srcDir),
            classpathJars = moshiClasspath,
            processorClasspath = processorClasspath,
            kotlinOutputDir = kspKotlinOutputDir,
            javaOutputDir = kspJavaOutputDir,
            resourceOutputDir = kspResourceOutputDir,
            classOutputDir = kspClassOutputDir,
            cacheDir = kspCacheDir
        )

        assertTrue(generatedDirs.isNotEmpty(), "KSP should generate files")

        // Combine original sources with generated sources
        val allSourceRoots = setOf(srcDir) + generatedDirs
        println("Source roots for compilation: ${allSourceRoots.map { it.absolutePath }}")

        // Compile with Kotlin compiler
        println("Compiling generated code...")
        val compiledOutput = kotlinJvmCompileBlocking(
            name = "moshi-compile-test",
            sourceRoots = allSourceRoots,
            classpathJars = moshiClasspath,
            cache = compileCache,
            outputFolder = compileOutputDir
        )

        assertTrue(compiledOutput.exists(), "Compilation output should exist")

        // Verify compiled classes exist
        val compiledClasses = compileOutputDir.walkTopDown()
            .filter { it.extension == "class" }
            .toList()

        println("Compiled classes:")
        compiledClasses.forEach { println("  - ${it.relativeTo(compileOutputDir)}") }

        assertTrue(compiledClasses.any { it.name == "Person.class" }, "Should compile Person class")
        assertTrue(compiledClasses.any { it.name.contains("PersonJsonAdapter") }, "Should compile PersonJsonAdapter")

        println("SUCCESS: Generated Moshi adapter compiles correctly!")
    }
}
