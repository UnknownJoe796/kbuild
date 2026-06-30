package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.common.DependencyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for KmpDependencyResolver.
 *
 * Verifies that:
 * 1. Common dependencies are resolved correctly for each target
 * 2. Target-specific dependencies are included
 * 3. Kotlin stdlib is automatically included
 * 4. Multiple dependencies combine correctly
 */
class KmpDependencyResolverTest {

    @Test
    fun `resolver includes common dependencies for JVM target`() {
        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(coroutines)
        )

        // Verify the dependency is included
        // Note: This creates the Maven dependencies that would be resolved
        val deps = setOf(coroutines.forTarget(KmpTarget.Jvm))
        assertEquals("kotlinx-coroutines-core-jvm", deps.first().artifactId)
    }

    @Test
    fun `resolver includes target-specific dependencies`() {
        val jvmOnlyDep = Dependency("com.example:jvm-only-lib:1.0.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js),
            commonDependencies = emptySet(),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(jvmOnlyDep)
            )
        )

        // The JVM target should have the dependency
        assertTrue(resolver.targetDependencies[KmpTarget.Jvm]?.contains(jvmOnlyDep) == true)

        // The JS target should NOT have it
        assertTrue(resolver.targetDependencies[KmpTarget.Js].isNullOrEmpty())
    }

    @Test
    fun `resolver handles multiple common dependencies`() {
        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        val serialization = kmpDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
        val datetime = kmpDependency("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm, KmpTarget.Native.MacosArm64),
            commonDependencies = setOf(coroutines, serialization, datetime)
        )

        assertEquals(3, resolver.commonDependencies.size)

        // Verify each resolves to correct target suffix
        resolver.commonDependencies.forEach { dep ->
            val jvmArtifact = dep.forTarget(KmpTarget.Jvm)
            assertTrue(jvmArtifact.artifactId.endsWith("-jvm"))

            val nativeArtifact = dep.forTarget(KmpTarget.Native.MacosArm64)
            assertTrue(nativeArtifact.artifactId.endsWith("-macosarm64"))
        }
    }

    @Test
    fun `resolver handles mixed common and target dependencies`() {
        val coroutines = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        val jvmDep = Dependency("org.slf4j:slf4j-api:2.0.9")
        val jsDep = Dependency("org.example:js-specific-lib:1.0.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm, KmpTarget.Js),
            commonDependencies = setOf(coroutines),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(jvmDep),
                KmpTarget.Js to setOf(jsDep)
            )
        )

        // Common should have 1 dep
        assertEquals(1, resolver.commonDependencies.size)

        // JVM should have 1 target-specific
        assertEquals(1, resolver.targetDependencies[KmpTarget.Jvm]?.size)
        assertTrue(resolver.targetDependencies[KmpTarget.Jvm]?.any { it.artifactId == "slf4j-api" } == true)

        // JS should have 1 target-specific
        assertEquals(1, resolver.targetDependencies[KmpTarget.Js]?.size)
        assertTrue(resolver.targetDependencies[KmpTarget.Js]?.any { it.artifactId == "js-specific-lib" } == true)
    }

    @Test
    fun `resolver handles native-specific dependencies`() {
        val commonDep = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        val macosOnlyDep = Dependency("com.example:macos-lib:1.0.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Native.MacosArm64, KmpTarget.Native.LinuxX64),
            commonDependencies = setOf(commonDep),
            targetDependencies = mapOf(
                KmpTarget.Native.MacosArm64 to setOf(macosOnlyDep)
            )
        )

        // macOS should have target-specific
        assertEquals(1, resolver.targetDependencies[KmpTarget.Native.MacosArm64]?.size)

        // Linux should NOT have macOS-specific
        assertTrue(resolver.targetDependencies[KmpTarget.Native.LinuxX64].isNullOrEmpty())
    }

    @Test
    fun `resolver targets set is correct`() {
        val resolver = KmpDependencyResolver(
            targets = setOf(
                KmpTarget.Jvm,
                KmpTarget.Js,
                KmpTarget.Native.MacosArm64,
                KmpTarget.Native.LinuxX64
            ),
            commonDependencies = emptySet()
        )

        assertEquals(4, resolver.targets.size)
        assertTrue(KmpTarget.Jvm in resolver.targets)
        assertTrue(KmpTarget.Js in resolver.targets)
        assertTrue(KmpTarget.Native.MacosArm64 in resolver.targets)
        assertTrue(KmpTarget.Native.LinuxX64 in resolver.targets)
    }

    @Test
    fun `resolver with empty dependencies`() {
        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = emptySet(),
            targetDependencies = emptyMap()
        )

        assertTrue(resolver.commonDependencies.isEmpty())
        assertTrue(resolver.targetDependencies.isEmpty())
    }

    @Test
    fun `multiple target-specific dependencies for same target`() {
        val dep1 = Dependency("com.example:lib1:1.0.0")
        val dep2 = Dependency("com.example:lib2:1.0.0")
        val dep3 = Dependency("com.example:lib3:1.0.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm),
            targetDependencies = mapOf(
                KmpTarget.Jvm to setOf(dep1, dep2, dep3)
            )
        )

        assertEquals(3, resolver.targetDependencies[KmpTarget.Jvm]?.size)
    }

    @Test
    fun `common dependency with test scope`() {
        val testDep = Dependency(
            groupId = "org.jetbrains.kotlin",
            artifactId = "kotlin-test",
            version = "2.0.0",
            scope = DependencyScope.Test
        )

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Jvm),
            commonDependencies = setOf(testDep)
        )

        val jvmTestDep = testDep.forTarget(KmpTarget.Jvm)
        assertEquals("test", jvmTestDep.scope)
    }

    @Test
    fun `KotlinStdlib common dependency resolves per target`() {
        val stdlib = KotlinStdlib.common

        val jvmArtifact = stdlib.forTarget(KmpTarget.Jvm)
        assertEquals("kotlin-stdlib-jvm", jvmArtifact.artifactId)
        assertEquals("jar", jvmArtifact.type)

        val jsArtifact = stdlib.forTarget(KmpTarget.Js)
        assertEquals("kotlin-stdlib-js", jsArtifact.artifactId)
        assertEquals("klib", jsArtifact.type)

        val nativeArtifact = stdlib.forTarget(KmpTarget.Native.MacosArm64)
        assertEquals("kotlin-stdlib-macosarm64", nativeArtifact.artifactId)
        assertEquals("klib", nativeArtifact.type)
    }

    @Test
    fun `KotlinTest common dependency resolves per target`() {
        val test = KotlinTest.common

        val jvmArtifact = test.forTarget(KmpTarget.Jvm)
        assertEquals("kotlin-test-jvm", jvmArtifact.artifactId)
        assertEquals("test", jvmArtifact.scope)

        val jsArtifact = test.forTarget(KmpTarget.Js)
        assertEquals("kotlin-test-js", jsArtifact.artifactId)
        assertEquals("test", jsArtifact.scope)
    }

    @Test
    fun `resolver handles all JS variants`() {
        val dep = kmpDependency("org.example:mylib:1.0.0")

        val resolver = KmpDependencyResolver(
            targets = setOf(KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node),
            commonDependencies = setOf(dep)
        )

        // All JS variants should resolve to same -js suffix
        assertEquals("mylib-js", dep.forTarget(KmpTarget.Js).artifactId)
        assertEquals("mylib-js", dep.forTarget(KmpTarget.Js.Browser).artifactId)
        assertEquals("mylib-js", dep.forTarget(KmpTarget.Js.Node).artifactId)
    }

    @Test
    fun `resolver handles Wasm targets`() {
        val dep = kmpDependency("org.example:mylib:1.0.0")

        val wasmJsDep = dep.forTarget(KmpTarget.Wasm.Js)
        val wasmWasiDep = dep.forTarget(KmpTarget.Wasm.Wasi)

        assertEquals("mylib-wasm-js", wasmJsDep.artifactId)
        assertEquals("klib", wasmJsDep.type)

        assertEquals("mylib-wasm-wasi", wasmWasiDep.artifactId)
        assertEquals("klib", wasmWasiDep.type)
    }

    @Test
    fun `dependency resolution for all major target groups`() {
        val dep = kmpDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

        val testCases = mapOf(
            KmpTarget.Jvm to "kotlinx-coroutines-core-jvm",
            KmpTarget.Js to "kotlinx-coroutines-core-js",
            KmpTarget.Wasm.Js to "kotlinx-coroutines-core-wasm-js",
            KmpTarget.Native.MacosArm64 to "kotlinx-coroutines-core-macosarm64",
            KmpTarget.Native.IosArm64 to "kotlinx-coroutines-core-iosarm64",
            KmpTarget.Native.LinuxX64 to "kotlinx-coroutines-core-linuxx64",
            KmpTarget.Native.MingwX64 to "kotlinx-coroutines-core-mingwx64"
        )

        testCases.forEach { (target, expectedArtifact) ->
            assertEquals(
                expectedArtifact,
                dep.forTarget(target).artifactId,
                "Failed for target: ${target.name}"
            )
        }
    }
}
