package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.common.Version
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class KmpPublishTest {

    @Test
    fun `generates Gradle Module Metadata`() {
        val root = File("build/run/KmpPublishMetadataTest")
        root.deleteRecursively()
        root.mkdirs()

        // Create minimal source
        val commonSrc = root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
        commonSrc.resolve("Lib.kt").writeText("""
            package mylib
            fun hello() = "Hello"
        """.trimIndent())

        val project = kmpProject("my-kmp-lib", root) {
            jvm()
            js()
        }

        val publish = KmpPublish(
            project = project,
            projectIdentifier = ProjectIdentifier("com.example", "my-kmp-lib", Version(1, 0, 0)),
            outputDir = root.resolve("build/publish")
        )

        // Generate metadata (don't actually publish)
        val moduleMetadata = publish.generateGradleModuleMetadataForTest()

        assertTrue(moduleMetadata.contains("\"formatVersion\": \"1.1\""), "Should have format version")
        assertTrue(moduleMetadata.contains("\"module\": \"my-kmp-lib\""), "Should have module name")
        assertTrue(moduleMetadata.contains("jvmApiElements"), "Should have JVM variant")
        assertTrue(moduleMetadata.contains("jsApiElements"), "Should have JS variant")
        assertTrue(moduleMetadata.contains("org.jetbrains.kotlin.platform.type"), "Should have platform attribute")

        println("Generated module metadata:\n$moduleMetadata")
    }
}

// Extension to expose metadata generation for testing
private fun KmpPublish.generateGradleModuleMetadataForTest(): String {
    val method = this::class.java.getDeclaredMethod("generateGradleModuleMetadata")
    method.isAccessible = true
    return method.invoke(this) as String
}
