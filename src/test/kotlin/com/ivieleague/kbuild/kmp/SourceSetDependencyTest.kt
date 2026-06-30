package com.ivieleague.kbuild.kmp

import org.apache.maven.model.Dependency
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for source set dependency chaining and inheritance.
 *
 * Verifies that:
 * 1. Dependencies are properly inherited through the source set hierarchy
 * 2. Source directories are inherited correctly
 * 3. Transitive dependencies are resolved
 * 4. Source set builder DSL works correctly
 */
class SourceSetDependencyTest {

    @Test
    fun `source set inherits dependencies from parent`() {
        val root = File("build/run/SourceSetInheritTest")
        root.deleteRecursively()

        // Create a simple hierarchy: parent -> child
        val parentDep = Dependency().apply {
            groupId = "com.example"
            artifactId = "parent-lib"
            version = "1.0.0"
        }

        val parent = SourceSet("parentMain", sourceDirectories = setOf(root.resolve("src/parentMain/kotlin")), dependencies = setOf(parentDep))

        val childDep = Dependency().apply {
            groupId = "com.example"
            artifactId = "child-lib"
            version = "1.0.0"
        }

        val child = SourceSet("childMain", sourceDirectories = setOf(root.resolve("src/childMain/kotlin")), dependsOn = setOf(parent), dependencies = setOf(childDep))

        // Child should have both its own and parent's dependencies
        assertEquals(setOf(childDep), child.dependencies, "Child should have its own dependency")
        assertEquals(setOf(parentDep, childDep), child.allDependencies, "allDependencies should include parent's")
    }

    @Test
    fun `source set inherits from multiple parents`() {
        val root = File("build/run/SourceSetMultiParentTest")
        root.deleteRecursively()

        val dep1 = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib1"
            version = "1.0.0"
        }
        val dep2 = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib2"
            version = "1.0.0"
        }
        val dep3 = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib3"
            version = "1.0.0"
        }

        val parent1 = SourceSet("parent1Main", dependencies = setOf(dep1))

        val parent2 = SourceSet("parent2Main", dependencies = setOf(dep2))

        val child = SourceSet("childMain", dependsOn = setOf(parent1, parent2), dependencies = setOf(dep3))

        assertEquals(
            setOf(dep1, dep2, dep3),
            child.allDependencies,
            "Child should inherit from both parents"
        )
    }

    @Test
    fun `transitive dependency inheritance works correctly`() {
        val root = File("build/run/SourceSetTransitiveTest")
        root.deleteRecursively()

        val grandparentDep = Dependency().apply {
            groupId = "com.example"
            artifactId = "grandparent-lib"
            version = "1.0.0"
        }
        val parentDep = Dependency().apply {
            groupId = "com.example"
            artifactId = "parent-lib"
            version = "1.0.0"
        }
        val childDep = Dependency().apply {
            groupId = "com.example"
            artifactId = "child-lib"
            version = "1.0.0"
        }

        val grandparent = SourceSet("grandparentMain", dependencies = setOf(grandparentDep))

        val parent = SourceSet("parentMain", dependsOn = setOf(grandparent), dependencies = setOf(parentDep))

        val child = SourceSet("childMain", dependsOn = setOf(parent), dependencies = setOf(childDep))

        // Verify transitive dependency inheritance
        assertEquals(setOf(grandparent), parent.dependsOn)
        assertEquals(setOf(grandparent), parent.allDependsOn)

        assertEquals(setOf(parent), child.dependsOn)
        assertEquals(setOf(parent, grandparent), child.allDependsOn)

        assertEquals(
            setOf(grandparentDep, parentDep, childDep),
            child.allDependencies,
            "Child should have grandparent's, parent's, and its own dependencies"
        )
    }

    @Test
    fun `source directories are inherited correctly`() {
        val root = File("build/run/SourceSetDirInheritTest")
        root.deleteRecursively()

        val common = SourceSet("commonMain", sourceDirectories = setOf(root.resolve("src/commonMain/kotlin")))

        val native = SourceSet("nativeMain", sourceDirectories = setOf(root.resolve("src/nativeMain/kotlin")), dependsOn = setOf(common))

        val apple = SourceSet("appleMain", sourceDirectories = setOf(root.resolve("src/appleMain/kotlin")), dependsOn = setOf(native))

        val ios = SourceSet("iosMain", sourceDirectories = setOf(root.resolve("src/iosMain/kotlin")), dependsOn = setOf(apple))

        // Check own directories
        assertEquals(
            setOf(root.resolve("src/iosMain/kotlin")),
            ios.sourceDirectories
        )

        // Check all inherited directories
        assertEquals(
            setOf(
                root.resolve("src/commonMain/kotlin"),
                root.resolve("src/nativeMain/kotlin"),
                root.resolve("src/appleMain/kotlin"),
                root.resolve("src/iosMain/kotlin")
            ),
            ios.allSourceDirectories
        )
    }

    @Test
    fun `KMP hierarchy commonMain to iosArm64Main chains correctly`() {
        val root = File("build/run/KmpHierarchyChainTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Native.IosArm64),
            useStandardLayout = true
        )

        // Verify the chain: iosArm64Main -> iosMain -> appleMain -> nativeMain -> commonMain
        val iosArm64 = hierarchy.iosArm64Main

        // Direct parent
        assertTrue(hierarchy.iosMain in iosArm64.dependsOn)

        // All transitive parents
        val allParents = iosArm64.allDependsOn
        assertTrue(hierarchy.iosMain in allParents, "Should have iosMain")
        assertTrue(hierarchy.appleMain in allParents, "Should have appleMain")
        assertTrue(hierarchy.nativeMain in allParents, "Should have nativeMain")
        assertTrue(hierarchy.commonMain in allParents, "Should have commonMain")

        // Verify source directories chain correctly
        val expectedDirs = setOf(
            root.resolve("src/commonMain/kotlin"),
            root.resolve("src/nativeMain/kotlin"),
            root.resolve("src/appleMain/kotlin"),
            root.resolve("src/iosMain/kotlin"),
            root.resolve("src/iosArm64Main/kotlin")
        )
        assertEquals(expectedDirs, iosArm64.allSourceDirectories)
    }

    @Test
    fun `KMP hierarchy jvmMain chains to commonMain`() {
        val root = File("build/run/KmpJvmChainTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Jvm),
            useStandardLayout = true
        )

        val jvmMain = hierarchy.jvmMain

        // Direct parent is commonMain
        assertEquals(setOf(hierarchy.commonMain), jvmMain.dependsOn)
        assertEquals(setOf(hierarchy.commonMain), jvmMain.allDependsOn)

        // Source directories
        assertEquals(
            setOf(
                root.resolve("src/commonMain/kotlin"),
                root.resolve("src/jvmMain/kotlin")
            ),
            jvmMain.allSourceDirectories
        )
    }

    @Test
    fun `KMP hierarchy linuxX64Main chains correctly`() {
        val root = File("build/run/KmpLinuxChainTest")
        root.deleteRecursively()

        val hierarchy = SourceSetHierarchy(
            projectRoot = root,
            enabledTargets = setOf(KmpTarget.Native.LinuxX64),
            useStandardLayout = true
        )

        // linuxX64Main -> linuxMain -> nativeMain -> commonMain
        val linuxX64 = hierarchy.linuxX64Main

        val allParents = linuxX64.allDependsOn
        assertTrue(hierarchy.linuxMain in allParents)
        assertTrue(hierarchy.nativeMain in allParents)
        assertTrue(hierarchy.commonMain in allParents)

        // Should NOT have appleMain (Linux is not Apple)
        assertTrue(hierarchy.appleMain !in allParents)
    }

    @Test
    fun `source set with dependencies from builder`() {
        val root = File("build/run/SourceSetBuilderDepsTest")
        root.deleteRecursively()

        val coroutinesDep = Dependency().apply {
            groupId = "org.jetbrains.kotlinx"
            artifactId = "kotlinx-coroutines-core"
            version = "1.7.3"
        }
        val serializationDep = Dependency().apply {
            groupId = "org.jetbrains.kotlinx"
            artifactId = "kotlinx-serialization-json"
            version = "1.6.0"
        }

        val common = SourceSet(
            "commonMain",
            sourceDirectories = setOf(root.resolve("src/commonMain/kotlin")),
            resourceDirectories = setOf(root.resolve("src/commonMain/resources")),
            dependencies = setOf(coroutinesDep, serializationDep)
        )

        assertEquals(2, common.dependencies.size)
        assertTrue(coroutinesDep in common.dependencies)
        assertTrue(serializationDep in common.dependencies)
    }

    @Test
    fun `source set isMain and isTest detection`() {
        val root = File("build/run/SourceSetMainTestDetection")
        root.deleteRecursively()

        val main = SourceSet("myMain")
        val test = SourceSet("myTest")
        val neither = SourceSet("my")

        assertTrue(main.isMain)
        assertTrue(!main.isTest)

        assertTrue(!test.isMain)
        assertTrue(test.isTest)

        assertTrue(!neither.isMain)
        assertTrue(!neither.isTest)
    }

    @Test
    fun `source set baseName extraction`() {
        val root = File("build/run/SourceSetBaseName")

        assertEquals("common", SourceSet("commonMain").baseName)
        assertEquals("common", SourceSet("commonTest").baseName)
        assertEquals("jvm", SourceSet("jvmMain").baseName)
        assertEquals("iosArm64", SourceSet("iosArm64Main").baseName)
    }

    @Test
    fun `resource directories are inherited correctly`() {
        val root = File("build/run/SourceSetResourceInheritTest")
        root.deleteRecursively()

        val common = SourceSet("commonMain", resourceDirectories = setOf(root.resolve("src/commonMain/resources")))

        val jvm = SourceSet("jvmMain", resourceDirectories = setOf(root.resolve("src/jvmMain/resources")), dependsOn = setOf(common))

        assertEquals(
            setOf(
                root.resolve("src/commonMain/resources"),
                root.resolve("src/jvmMain/resources")
            ),
            jvm.allResourceDirectories
        )
    }

    @Test
    fun `diamond dependency inheritance works correctly`() {
        // Tests the diamond problem: A -> B, A -> C, B -> D, C -> D
        val root = File("build/run/SourceSetDiamondTest")
        root.deleteRecursively()

        val depD = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib-d"
            version = "1.0.0"
        }
        val depB = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib-b"
            version = "1.0.0"
        }
        val depC = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib-c"
            version = "1.0.0"
        }
        val depA = Dependency().apply {
            groupId = "com.example"
            artifactId = "lib-a"
            version = "1.0.0"
        }

        val d = SourceSet("dMain", dependencies = setOf(depD))

        val b = SourceSet("bMain", dependsOn = setOf(d), dependencies = setOf(depB))

        val c = SourceSet("cMain", dependsOn = setOf(d), dependencies = setOf(depC))

        val a = SourceSet("aMain", dependsOn = setOf(b, c), dependencies = setOf(depA))

        // A should have all dependencies, but D only once
        assertEquals(
            setOf(depA, depB, depC, depD),
            a.allDependencies
        )

        // D should appear only once in allDependsOn
        val allDeps = a.allDependsOn
        assertEquals(3, allDeps.size, "Should have B, C, D (no duplicates)")
        assertTrue(b in allDeps)
        assertTrue(c in allDeps)
        assertTrue(d in allDeps)
    }

    @Test
    fun `source set equality is based on name`() {
        val root = File("build/run/SourceSetEquality")

        val ss1 = SourceSet("commonMain", sourceDirectories = setOf(root.resolve("src/a/kotlin")))
        val ss2 = SourceSet("commonMain", sourceDirectories = setOf(root.resolve("src/b/kotlin")))
        val ss3 = SourceSet("jvmMain")

        assertEquals(ss1, ss2, "Source sets with same name should be equal")
        assertTrue(ss1 != ss3, "Source sets with different names should not be equal")
        assertEquals(ss1.hashCode(), ss2.hashCode(), "Hash codes should match for same name")
    }

    @Test
    fun `deeply nested hierarchy inheritance`() {
        val root = File("build/run/DeepNestingTest")
        root.deleteRecursively()

        // Create a deep chain: common -> native -> apple -> ios -> iosArm64 -> custom
        val deps = (1..6).map { i ->
            Dependency().apply {
                groupId = "com.example"
                artifactId = "lib$i"
                version = "1.0.0"
            }
        }

        val common = SourceSet("commonMain", dependencies = setOf(deps[0]))
        val native = SourceSet("nativeMain", dependsOn = setOf(common), dependencies = setOf(deps[1]))
        val apple = SourceSet("appleMain", dependsOn = setOf(native), dependencies = setOf(deps[2]))
        val ios = SourceSet("iosMain", dependsOn = setOf(apple), dependencies = setOf(deps[3]))
        val iosArm64 = SourceSet("iosArm64Main", dependsOn = setOf(ios), dependencies = setOf(deps[4]))
        val custom = SourceSet("customMain", dependsOn = setOf(iosArm64), dependencies = setOf(deps[5]))

        assertEquals(6, custom.allDependencies.size, "Should have all 6 dependencies")
        assertEquals(5, custom.allDependsOn.size, "Should have 5 transitive source set dependencies")

        // Verify order doesn't matter - all are included
        deps.forEach { dep ->
            assertTrue(dep in custom.allDependencies, "Should include $dep")
        }
    }
}
