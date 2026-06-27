package com.ivieleague.kbuild.maven

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.kmp.KmpDependency
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.kmp.resolveVersionConflicts
import com.ivieleague.kbuild.kotlin.Kotlin
import kotlinx.coroutines.runBlocking
import java.io.File
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
    fun versionConflictResolutionKeepsHighestAndPinsKotlin() = runBlocking {
        val kotlinVersion = Kotlin.version.toString()
        val libs = setOf(
            // Numeric comparison must beat lexical: 1.10.0 > 1.9.0 > 1.2.0.
            Library("org.example:foo:1.2.0", File("foo-1.2.0.jar")),
            Library("org.example:foo:1.10.0", File("foo-1.10.0.jar")),
            Library("org.example:foo:1.9.0", File("foo-1.9.0.jar")),
            // Two stdlib versions, the pinned one present -> pin selects it, no re-resolve.
            Library("org.jetbrains.kotlin:kotlin-stdlib:2.1.0", File("kotlin-stdlib-2.1.0.jar")),
            Library("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion", File("kotlin-stdlib-$kotlinVersion.jar")),
        )
        var reResolveCalls = 0
        val resolved = libs.resolveVersionConflicts(kotlinVersion) { g, a, v, _ ->
            reResolveCalls++
            Library("$g:$a:$v", File("$a-$v.jar"))
        }
        assertEquals(
            setOf("org.example:foo:1.10.0"),
            resolved.filter { it.name.startsWith("org.example") }.map { it.name }.toSet(),
            "Highest version must win for ordinary modules"
        )
        assertEquals(
            setOf("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"),
            resolved.filter { it.name.startsWith("org.jetbrains.kotlin") }.map { it.name }.toSet(),
            "Kotlin first-party must be pinned to Kotlin.version"
        )
        assertEquals(0, reResolveCalls, "A pinned copy already on the classpath needs no re-resolution")
    }

    @Test
    fun kotlinFirstPartyPinReResolvesWhenOnlyOtherVersionPresent() = runBlocking {
        val kotlinVersion = Kotlin.version.toString()
        // A transitive pulled a kotlin-* module at an older version with no Kotlin.version copy:
        // pinning must re-resolve it to Kotlin.version, preserving klib packaging.
        val libs = setOf(
            Library("org.jetbrains.kotlin:kotlin-stdlib-common:2.1.0", File("kotlin-stdlib-common-2.1.0.klib"))
        )
        var requested: String? = null
        val resolved = libs.resolveVersionConflicts(kotlinVersion) { g, a, v, sample ->
            requested = "$g:$a:$v"
            assertTrue(sample.default.name.endsWith(".klib"), "Sample packaging must drive re-resolution extension")
            Library("$g:$a:$v", File("$a-$v.klib"))
        }
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion", requested)
        assertEquals(
            setOf("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion"),
            resolved.map { it.name }.toSet()
        )
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
