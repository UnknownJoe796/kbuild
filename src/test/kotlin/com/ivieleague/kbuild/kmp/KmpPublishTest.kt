package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.common.Version
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.aether.repository.RemoteRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KmpPublishTest {

    /**
     * Publishes a minimal JVM+JS library to a throwaway file repository and verifies the
     * published Gradle Module Metadata: a root `.module` that redirects to each target, and a
     * per-target `.module` that carries the artifact files with checksums.
     */
    @Test
    fun `publishes complete module metadata`() = runBlocking {
        val root = File("build/run/KmpPublishMetadataTest")
        root.deleteRecursively()
        root.mkdirs()
        root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
            .resolve("Lib.kt").writeText("package mylib\nfun hello() = \"Hello\"\n")

        val repoDir = root.resolve("repo")
        val repo = RemoteRepository.Builder("test", "default", "file://" + repoDir.absolutePath).build()

        val project = kmpProject("my-kmp-lib", root) { jvm(); js() }
        val publisher = KmpPublisher(
            config = project,
            projectIdentifier = ProjectIdentifier("com.example", "my-kmp-lib", Version(1, 0, 0)),
            outputDir = root.resolve("build/publish")
            // no signer: keeps the test independent of a local GPG key
        )
        // Publish each target plus the root. The commonMain metadata-klib compile is covered by the
        // reactive integration build; here an empty metadata map keeps the test off the Kotlin/Native
        // toolchain while still exercising the full module/POM/root-redirect generation.
        publisher.publishJvm(repository = repo)
        publisher.publishJs(repository = repo)
        publisher.publishMetadata(metadataKlibs = emptyMap(), repository = repo)

        val base = repoDir.resolve("com/example")

        // Root module: inline metadata variants + available-at redirects to each target module.
        val rootModule = Json.parseToJsonElement(
            base.resolve("my-kmp-lib/1.0.0/my-kmp-lib-1.0.0.module").readText()
        ).jsonObject
        assertEquals("1.1", rootModule["formatVersion"]!!.jsonPrimitive.content)
        val rootVariantNames = rootModule.variantNames()
        assertTrue("metadataApiElements" in rootVariantNames, "root has commonMain metadata variant")
        assertTrue("jvmApiElements-published" in rootVariantNames, "root redirects JVM")
        assertTrue("jsApiElements-published" in rootVariantNames, "root redirects JS")
        val jvmRedirect = rootModule["variants"]!!.jsonArray
            .map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "jvmApiElements-published" }
        assertTrue(jvmRedirect.containsKey("available-at"), "JVM variant redirects via available-at")
        assertEquals("my-kmp-lib-jvm",
            jvmRedirect["available-at"]!!.jsonObject["module"]!!.jsonPrimitive.content)

        // Root commonMain metadata klib must actually be published and non-empty.
        val rootJar = base.resolve("my-kmp-lib/1.0.0/my-kmp-lib-1.0.0.jar")
        assertTrue(rootJar.exists() && rootJar.length() > 0, "commonMain metadata klib published")
        assertTrue(base.resolve("my-kmp-lib/1.0.0/my-kmp-lib-1.0.0-kotlin-tooling-metadata.json").exists(),
            "kotlin-tooling-metadata.json published")

        // Per-target JVM module: component points back at the root; variants carry files+checksums.
        val jvmModule = Json.parseToJsonElement(
            base.resolve("my-kmp-lib-jvm/1.0.0/my-kmp-lib-jvm-1.0.0.module").readText()
        ).jsonObject
        assertEquals("my-kmp-lib", jvmModule["component"]!!.jsonObject["module"]!!.jsonPrimitive.content)
        val jvmApi = jvmModule["variants"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "jvmApiElements-published" }
        val jvmFile = jvmApi["files"]!!.jsonArray.single().jsonObject
        assertEquals("my-kmp-lib-jvm-1.0.0.jar", jvmFile["url"]!!.jsonPrimitive.content)
        assertTrue(jvmFile.containsKey("sha512") && jvmFile.containsKey("md5"), "file entry has checksums")
    }

    private fun JsonObject.variantNames(): List<String> =
        this["variants"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
}
