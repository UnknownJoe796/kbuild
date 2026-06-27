package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.kmp.KmpDependency
import com.ivieleague.kbuild.kmp.KmpTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-network tests (like [PomBuildTest]) for variant-aware Gradle Module Metadata resolution.
 * They resolve against Maven Central, caching to `~/.maven-cache`.
 */
class GradleModuleMetadataTest {
    private val coroutinesGroup = "org.jetbrains.kotlinx"
    private val coroutinesArtifact = "kotlinx-coroutines-core"
    private val coroutinesVersion = "1.10.2"

    @Test
    fun fetchAndSelectVariants() {
        val metadata = MavenAether.fetchModuleMetadata(coroutinesGroup, coroutinesArtifact, coroutinesVersion)
        assertNotNull(metadata, "Root .module must be published for kotlinx-coroutines-core")
        assertEquals("1.1", metadata.formatVersion)

        // Root KMP variants are pure redirects: jvm + iosArm64 must carry available-at to per-target modules.
        val jvmRoot = MavenAether.selectVariant(metadata, KmpTarget.Jvm)
        assertNotNull(jvmRoot, "jvm variant must be selectable")
        assertEquals(
            "kotlinx-coroutines-core-jvm",
            jvmRoot.availableAt?.module,
            "jvm root variant must redirect to the -jvm per-target module"
        )

        val iosRoot = MavenAether.selectVariant(metadata, KmpTarget.Native.IosArm64)
        assertNotNull(iosRoot, "iosArm64 variant must be selectable")
        assertEquals(
            "kotlinx-coroutines-core-iosarm64",
            iosRoot.availableAt?.module,
            "iosArm64 root variant must redirect to the -iosarm64 per-target module"
        )
        // Native selection must discriminate by konan target, not just platform type == native.
        assertEquals(
            "native",
            iosRoot.attribute("org.jetbrains.kotlin.platform.type")
        )
        assertEquals(
            "ios_arm64",
            iosRoot.attribute("org.jetbrains.kotlin.native.target")
        )
    }

    @Test
    fun resolveJvmFollowsRedirectAndPullsTransitives() {
        val libs = MavenAether.resolveKmpForTarget(
            coroutinesGroup, coroutinesArtifact, coroutinesVersion, KmpTarget.Jvm
        )
        assertNotNull(libs, "JVM resolution via .module must succeed")
        val names = libs.map { it.default.name }
        assertTrue(
            names.any { it == "kotlinx-coroutines-core-jvm-1.10.2.jar" },
            "Must resolve the -jvm jar via available-at; got $names"
        )
        assertTrue(
            names.any { it.startsWith("kotlin-stdlib") && it.endsWith(".jar") },
            "Must pull transitive kotlin-stdlib; got $names"
        )
    }

    @Test
    fun resolveJsProducesKlib() {
        val libs = MavenAether.resolveKmpForTarget(
            coroutinesGroup, coroutinesArtifact, coroutinesVersion, KmpTarget.Js
        )
        assertNotNull(libs, "JS resolution via .module must succeed")
        val names = libs.map { it.default.name }
        assertTrue(
            names.any { it == "kotlinx-coroutines-core-js-1.10.2.klib" },
            "Must resolve the -js klib via available-at; got $names"
        )
    }

    @Test
    fun resolveNativeFollowsRedirectToKlib() {
        val libs = MavenAether.resolveKmpForTarget(
            coroutinesGroup, coroutinesArtifact, coroutinesVersion, KmpTarget.Native.IosArm64
        )
        assertNotNull(libs, "Native resolution via .module must succeed")
        val names = libs.map { it.default.name }
        assertTrue(
            names.any { it == "kotlinx-coroutines-core-iosarm64-1.10.2.klib" },
            "Must follow available-at to the -iosarm64 klib; got $names"
        )
    }

    @Test
    fun nonKmpLibraryReturnsNull() {
        // slf4j-api is a plain Maven JVM library with no .module -> signals POM/convention fallback.
        val result = MavenAether.resolveKmpForTarget("org.slf4j", "slf4j-api", "2.0.9", KmpTarget.Jvm)
        assertNull(result, "A library without Gradle Module Metadata must return null (POM fallback)")
        assertNull(MavenAether.fetchModuleMetadata("org.slf4j", "slf4j-api", "2.0.9"))
    }

    @Test
    fun kmpDependencyResolveForTargetUsesModuleMetadata() {
        val libs = runBlocking {
            KmpDependency(coroutinesGroup, coroutinesArtifact, coroutinesVersion).resolveForTarget(KmpTarget.Jvm)
        }
        assertTrue(libs.isNotEmpty(), "resolveForTarget must return libraries")
        assertTrue(
            libs.any { it.default.name == "kotlinx-coroutines-core-jvm-1.10.2.jar" },
            "resolveForTarget(Jvm) must contain the -jvm jar; got ${libs.map { it.default.name }}"
        )
    }
}
