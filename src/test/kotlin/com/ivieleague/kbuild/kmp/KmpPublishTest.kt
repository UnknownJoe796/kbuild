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
import java.util.zip.ZipFile
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

    /**
     * The root jar's `kotlin-project-structure-metadata.json` must describe the full declared shared
     * source-set hierarchy for the enabled targets — including intermediates with no files on disk —
     * or a consumer compiling against (say) nativeMain has no metadata to resolve. This guards the
     * regression where only source sets with Kotlin files were listed.
     */
    @Test
    fun `project structure metadata lists full declared shared hierarchy`() = runBlocking {
        val root = File("build/run/KmpPublishStructureTest")
        root.deleteRecursively()
        root.mkdirs()
        // Only commonMain has sources; nativeMain/appleMain/iosMain are declared but empty on disk.
        root.resolve("src/commonMain/kotlin").also { it.mkdirs() }
            .resolve("Lib.kt").writeText("package mylib\nfun hello() = \"Hello\"\n")

        val repoDir = root.resolve("repo")
        val repo = RemoteRepository.Builder("test", "default", "file://" + repoDir.absolutePath).build()

        // iOS targets introduce the native intermediates; no common dependencies keeps this off the network.
        val project = kmpProject("my-kmp-lib", root) { jvm(); js(); iosArm64(); iosSimulatorArm64(); iosX64() }
        val publisher = KmpPublisher(
            config = project,
            projectIdentifier = ProjectIdentifier("com.example", "my-kmp-lib", Version(1, 0, 0)),
            outputDir = root.resolve("build/publish")
        )
        publisher.publishMetadata(metadataKlibs = emptyMap(), repository = repo)

        val rootJar = repoDir.resolve("com/example/my-kmp-lib/1.0.0/my-kmp-lib-1.0.0.jar")
        val structure = ZipFile(rootJar).use { zip ->
            val entry = zip.getEntry("META-INF/kotlin-project-structure-metadata.json")!!
            Json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonObject
        }["projectStructure"]!!.jsonObject

        val sourceSets = structure["sourceSets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("appleMain", "commonMain", "iosMain", "nativeMain"),
            sourceSets.map { it["name"]!!.jsonPrimitive.content },
            "declared shared hierarchy is published even when intermediates are empty on disk"
        )

        // Native intermediates are host-specific with a cinterop directory; commonMain is neither.
        val nativeMain = sourceSets.first { it["name"]!!.jsonPrimitive.content == "nativeMain" }
        assertEquals("true", nativeMain["hostSpecific"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("commonMain"),
            nativeMain["dependsOn"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        val commonMain = sourceSets.first { it["name"]!!.jsonPrimitive.content == "commonMain" }
        assertTrue(!commonMain.containsKey("hostSpecific"), "commonMain is not host-specific")

        // An iOS variant resolves the whole intermediate chain down to commonMain.
        val iosChain = structure["variants"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "iosArm64ApiElements" }["sourceSet"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("iosMain", "appleMain", "nativeMain", "commonMain"), iosChain)
    }

    /**
     * A native target coordinate publishes its own `.module` that points back at the root component
     * and carries each variant's file with checksums — the same shape verified for JVM above, but for
     * a Kotlin/Native target (klib) without needing the Kotlin/Native toolchain.
     */
    @Test
    fun `publishes native target module with checksums`() = runBlocking {
        val root = File("build/run/KmpPublishNativeModuleTest")
        root.deleteRecursively()
        root.mkdirs()
        val repoDir = root.resolve("repo")
        val repo = RemoteRepository.Builder("test", "default", "file://" + repoDir.absolutePath).build()

        // Publishing only copies the klib and records its size/checksums, so a stand-in file is enough
        // to exercise the module/checksum generation without running konanc.
        val fakeKlib = root.resolve("fake.klib").apply { writeText("stand-in klib payload") }

        val project = kmpProject("my-kmp-lib", root) { iosArm64() }
        val publisher = KmpPublisher(
            config = project,
            projectIdentifier = ProjectIdentifier("com.example", "my-kmp-lib", Version(1, 0, 0)),
            outputDir = root.resolve("build/publish")
        )
        publisher.publishNative(KmpTarget.Native.IosArm64, klibFile = fakeKlib, repository = repo)

        val base = repoDir.resolve("com/example")
        val module = Json.parseToJsonElement(
            base.resolve("my-kmp-lib-iosarm64/1.0.0/my-kmp-lib-iosarm64-1.0.0.module").readText()
        ).jsonObject
        assertEquals("my-kmp-lib", module["component"]!!.jsonObject["module"]!!.jsonPrimitive.content,
            "native module points back at the owning root component")
        val variantNames = module.variantNames()
        assertTrue("iosArm64ApiElements-published" in variantNames, "klib api variant")
        assertTrue("iosArm64SourcesElements-published" in variantNames, "sources variant")
        assertTrue("iosArm64MetadataElements-published" in variantNames, "metadata variant")

        val api = module["variants"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "iosArm64ApiElements-published" }
        val file = api["files"]!!.jsonArray.single().jsonObject
        assertEquals("my-kmp-lib-iosarm64-1.0.0.klib", file["url"]!!.jsonPrimitive.content)
        assertTrue(file.containsKey("sha512") && file.containsKey("md5"), "native file entry has checksums")
    }

    private fun JsonObject.variantNames(): List<String> =
        this["variants"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
}
