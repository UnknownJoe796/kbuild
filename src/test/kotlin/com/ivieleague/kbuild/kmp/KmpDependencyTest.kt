package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.common.DependencyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for KmpDependency artifact resolution.
 *
 * Verifies that:
 * 1. Artifact IDs are correctly suffixed for each target
 * 2. Artifact types are correct (jar for JVM, klib for others)
 * 3. KmpDependency parsing works correctly
 * 4. Scope is preserved when converting to Maven Dependency
 */
class KmpDependencyTest {

    @Test
    fun `artifactIdForTarget returns correct suffix for JVM`() {
        val dep = Dependency(
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-coroutines-core",
            version = "1.7.3"
        )

        assertEquals(
            "kotlinx-coroutines-core-jvm",
            dep.artifactIdForTarget(KmpTarget.Jvm)
        )
    }

    @Test
    fun `artifactIdForTarget returns correct suffix for JS`() {
        val dep = Dependency(
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-coroutines-core",
            version = "1.7.3"
        )

        assertEquals(
            "kotlinx-coroutines-core-js",
            dep.artifactIdForTarget(KmpTarget.Js)
        )
        assertEquals(
            "kotlinx-coroutines-core-js",
            dep.artifactIdForTarget(KmpTarget.Js.Browser)
        )
        assertEquals(
            "kotlinx-coroutines-core-js",
            dep.artifactIdForTarget(KmpTarget.Js.Node)
        )
    }

    @Test
    fun `artifactIdForTarget returns correct suffix for Wasm`() {
        val dep = Dependency(
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-coroutines-core",
            version = "1.7.3"
        )

        assertEquals(
            "kotlinx-coroutines-core-wasm-js",
            dep.artifactIdForTarget(KmpTarget.Wasm.Js)
        )
        assertEquals(
            "kotlinx-coroutines-core-wasm-wasi",
            dep.artifactIdForTarget(KmpTarget.Wasm.Wasi)
        )
    }

    @Test
    fun `artifactIdForTarget returns correct suffix for Native targets`() {
        val dep = Dependency(
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-coroutines-core",
            version = "1.7.3"
        )

        // macOS
        assertEquals(
            "kotlinx-coroutines-core-macosx64",
            dep.artifactIdForTarget(KmpTarget.Native.MacosX64)
        )
        assertEquals(
            "kotlinx-coroutines-core-macosarm64",
            dep.artifactIdForTarget(KmpTarget.Native.MacosArm64)
        )

        // iOS
        assertEquals(
            "kotlinx-coroutines-core-iosarm64",
            dep.artifactIdForTarget(KmpTarget.Native.IosArm64)
        )
        assertEquals(
            "kotlinx-coroutines-core-iossimulatorarm64",
            dep.artifactIdForTarget(KmpTarget.Native.IosSimulatorArm64)
        )
        assertEquals(
            "kotlinx-coroutines-core-iosx64",
            dep.artifactIdForTarget(KmpTarget.Native.IosX64)
        )

        // Linux
        assertEquals(
            "kotlinx-coroutines-core-linuxx64",
            dep.artifactIdForTarget(KmpTarget.Native.LinuxX64)
        )
        assertEquals(
            "kotlinx-coroutines-core-linuxarm64",
            dep.artifactIdForTarget(KmpTarget.Native.LinuxArm64)
        )

        // Windows
        assertEquals(
            "kotlinx-coroutines-core-mingwx64",
            dep.artifactIdForTarget(KmpTarget.Native.MingwX64)
        )
    }

    @Test
    fun `typeForTarget returns jar for JVM`() {
        val dep = Dependency(
            groupId = "org.example",
            artifactId = "mylib",
            version = "1.0.0"
        )

        assertEquals("jar", dep.typeForTarget(KmpTarget.Jvm))
    }

    @Test
    fun `typeForTarget returns klib for non-JVM targets`() {
        val dep = Dependency(
            groupId = "org.example",
            artifactId = "mylib",
            version = "1.0.0"
        )

        assertEquals("klib", dep.typeForTarget(KmpTarget.Js))
        assertEquals("klib", dep.typeForTarget(KmpTarget.Js.Browser))
        assertEquals("klib", dep.typeForTarget(KmpTarget.Wasm.Js))
        assertEquals("klib", dep.typeForTarget(KmpTarget.Native.MacosArm64))
        assertEquals("klib", dep.typeForTarget(KmpTarget.Native.LinuxX64))
        assertEquals("klib", dep.typeForTarget(KmpTarget.Native.IosArm64))
    }

    @Test
    fun `forTarget creates correct Maven Dependency`() {
        val kmpDep = Dependency(
            groupId = "org.jetbrains.kotlinx",
            artifactId = "kotlinx-coroutines-core",
            version = "1.7.3",
            scope = DependencyScope.Compile
        )

        val jvmDep = kmpDep.forTarget(KmpTarget.Jvm)
        assertEquals("org.jetbrains.kotlinx", jvmDep.groupId)
        assertEquals("kotlinx-coroutines-core-jvm", jvmDep.artifactId)
        assertEquals("1.7.3", jvmDep.version)
        assertEquals("jar", jvmDep.type)

        val nativeDep = kmpDep.forTarget(KmpTarget.Native.MacosArm64)
        assertEquals("org.jetbrains.kotlinx", nativeDep.groupId)
        assertEquals("kotlinx-coroutines-core-macosarm64", nativeDep.artifactId)
        assertEquals("1.7.3", nativeDep.version)
        assertEquals("klib", nativeDep.type)
    }

    @Test
    fun `parse creates KmpDependency from string`() {
        val dep = Dependency.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        assertEquals("org.jetbrains.kotlinx", dep.groupId)
        assertEquals("kotlinx-coroutines-core", dep.artifactId)
        assertEquals("1.7.3", dep.version)
        assertEquals(DependencyScope.Compile, dep.scope)
    }

    @Test
    fun `parse with custom scope`() {
        val dep = Dependency.parse(
            "org.jetbrains.kotlin:kotlin-test:2.0.0",
            DependencyScope.Test
        )

        assertEquals("org.jetbrains.kotlin", dep.groupId)
        assertEquals("kotlin-test", dep.artifactId)
        assertEquals("2.0.0", dep.version)
        assertEquals(DependencyScope.Test, dep.scope)
    }

    @Test
    fun `kmpDependency DSL function works`() {
        val dep = kmpDependency("com.squareup.okio:okio:3.5.0")

        assertEquals("com.squareup.okio", dep.groupId)
        assertEquals("okio", dep.artifactId)
        assertEquals("3.5.0", dep.version)
    }

    @Test
    fun `parse throws on invalid format`() {
        try {
            Dependency.parse("invalid")
            assertTrue(false, "Should have thrown")
        } catch (e: Exception) {
            // Expected: IndexOutOfBoundsException or similar for malformed coordinates
        }

        try {
            Dependency.parse("group:artifact")
            assertTrue(false, "Should have thrown")
        } catch (e: Exception) {
            // Expected: IndexOutOfBoundsException or similar for missing version
        }
    }

    @Test
    fun `all native targets have unique artifact suffixes`() {
        val dep = Dependency(
            groupId = "org.example",
            artifactId = "mylib",
            version = "1.0.0"
        )

        val artifactIds = KmpTarget.Native.all.map { dep.artifactIdForTarget(it) }

        // Verify all are unique
        assertEquals(
            artifactIds.size,
            artifactIds.toSet().size,
            "All native targets should have unique artifact IDs"
        )

        // Verify they all start with the base artifact ID
        artifactIds.forEach { id ->
            assertTrue(id.startsWith("mylib-"), "Artifact ID should start with 'mylib-': $id")
        }
    }

    @Test
    fun `KotlinStdlib forTarget returns correct dependencies`() {
        val jvmStdlib = KotlinStdlib.forTarget(KmpTarget.Jvm)
        assertTrue(jvmStdlib != null, "JVM should have stdlib")
        assertTrue(jvmStdlib!!.artifactId.contains("kotlin-stdlib"))

        val jsStdlib = KotlinStdlib.forTarget(KmpTarget.Js)
        assertTrue(jsStdlib != null, "JS should have stdlib")
        assertTrue(jsStdlib!!.artifactId.contains("kotlin-stdlib"))

        // Native stdlib is handled by compiler, so returns null
        val nativeStdlib = KotlinStdlib.forTarget(KmpTarget.Native.MacosArm64)
        assertTrue(nativeStdlib == null, "Native should not have explicit stdlib (compiler handles it)")
    }

    @Test
    fun `KotlinTest common dependency has correct properties`() {
        val testDep = KotlinTest.common

        assertEquals("org.jetbrains.kotlin", testDep.groupId)
        assertEquals("kotlin-test", testDep.artifactId)
        assertEquals(DependencyScope.Test, testDep.scope)
    }

    @Test
    fun `KmpDependency data class equality`() {
        val dep1 = Dependency("com.example", "mylib", "1.0.0")
        val dep2 = Dependency("com.example", "mylib", "1.0.0")
        val dep3 = Dependency("com.example", "mylib", "2.0.0")

        assertEquals(dep1, dep2)
        assertTrue(dep1 != dep3)
        assertEquals(dep1.hashCode(), dep2.hashCode())
    }

    @Test
    fun `dependency scope is preserved in forTarget`() {
        val testDep = Dependency(
            groupId = "org.jetbrains.kotlin",
            artifactId = "kotlin-test",
            version = "2.0.0",
            scope = DependencyScope.Test
        )

        val mavenDep = testDep.forTarget(KmpTarget.Jvm)
        assertEquals("test", mavenDep.scope)
    }

    @Test
    fun `multiple dependencies for same target`() {
        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        val serialization = kmpDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

        val jvmCoroutines = coroutines.forTarget(KmpTarget.Jvm)
        val jvmSerialization = serialization.forTarget(KmpTarget.Jvm)

        assertEquals("kotlinx-coroutines-core-jvm", jvmCoroutines.artifactId)
        assertEquals("kotlinx-serialization-json-jvm", jvmSerialization.artifactId)

        // Both should be jars
        assertEquals("jar", jvmCoroutines.type)
        assertEquals("jar", jvmSerialization.type)
    }

    @Test
    fun `watchOS and tvOS artifact IDs are correct`() {
        val dep = kmpDependency("org.example:mylib:1.0.0")

        assertEquals(
            "mylib-watchosarm64",
            dep.artifactIdForTarget(KmpTarget.Native.WatchosArm64)
        )
        assertEquals(
            "mylib-watchossimulatorarm64",
            dep.artifactIdForTarget(KmpTarget.Native.WatchosSimulatorArm64)
        )
        assertEquals(
            "mylib-tvosarm64",
            dep.artifactIdForTarget(KmpTarget.Native.TvosArm64)
        )
        assertEquals(
            "mylib-tvossimulatorarm64",
            dep.artifactIdForTarget(KmpTarget.Native.TvosSimulatorArm64)
        )
    }

    @Test
    fun `Android Native artifact IDs are correct`() {
        val dep = kmpDependency("org.example:mylib:1.0.0")

        assertEquals(
            "mylib-androidnativearm64",
            dep.artifactIdForTarget(KmpTarget.Native.AndroidNativeArm64)
        )
        assertEquals(
            "mylib-androidnativearm32",
            dep.artifactIdForTarget(KmpTarget.Native.AndroidNativeArm32)
        )
        assertEquals(
            "mylib-androidnativex64",
            dep.artifactIdForTarget(KmpTarget.Native.AndroidNativeX64)
        )
        assertEquals(
            "mylib-androidnativex86",
            dep.artifactIdForTarget(KmpTarget.Native.AndroidNativeX86)
        )
    }
}
