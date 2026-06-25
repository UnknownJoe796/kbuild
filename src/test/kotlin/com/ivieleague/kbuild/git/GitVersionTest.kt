package com.ivieleague.kbuild.git

import com.ivieleague.kbuild.common.Version
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitVersionTest {

    @Test
    fun `parseTagVersion handles simple version`() {
        assertEquals(Version(1, 2, 3), parseTagVersion("1.2.3"))
    }

    @Test
    fun `parseTagVersion handles v prefix`() {
        assertEquals(Version(1, 2, 3), parseTagVersion("v1.2.3"))
    }

    @Test
    fun `parseTagVersion handles variant`() {
        assertEquals(Version(1, 2, 3, "rc1"), parseTagVersion("1.2.3-rc1"))
    }

    @Test
    fun `parseTagVersion handles partial version`() {
        assertEquals(Version(1, 0, 0), parseTagVersion("1"))
        assertEquals(Version(1, 2, 0), parseTagVersion("1.2"))
    }

    @Test
    fun `computeVersion on main branch clean at tag`() {
        val version = computeVersion(
            branch = "main",
            tagVersion = Version(1, 2, 3),
            isClean = true
        )
        assertEquals(Version(1, 2, 3), version)
    }

    @Test
    fun `computeVersion on main branch dirty`() {
        val version = computeVersion(
            branch = "main",
            tagVersion = Version(1, 2, 3),
            isClean = false
        )
        assertEquals(Version(1, 2, 4, "local"), version)
    }

    @Test
    fun `computeVersion on feature branch clean`() {
        val version = computeVersion(
            branch = "feature-new-thing",
            tagVersion = Version(1, 2, 3),
            isClean = true
        )
        // Non-standard branch, so patch bumped and branch name in variant
        assertEquals(Version(1, 2, 4, "featurenewthing"), version)
    }

    @Test
    fun `computeVersion on feature branch dirty`() {
        val version = computeVersion(
            branch = "feature-new-thing",
            tagVersion = Version(1, 2, 3),
            isClean = false
        )
        assertEquals(Version(1, 2, 4, "featurenewthing-local"), version)
    }

    @Test
    fun `computeVersion on version branch for future release`() {
        val version = computeVersion(
            branch = "version-2.0",
            tagVersion = Version(1, 2, 3),
            isClean = true
        )
        assertEquals(Version(2, 0, 0, "prerelease"), version)
    }

    @Test
    fun `computeVersion on version branch dirty`() {
        val version = computeVersion(
            branch = "version-2.0",
            tagVersion = Version(1, 2, 3),
            isClean = false
        )
        assertEquals(Version(2, 0, 0, "prerelease-local"), version)
    }

    @Test
    fun `computeVersion on dev branch clean at tag`() {
        val version = computeVersion(
            branch = "dev",
            tagVersion = Version(1, 2, 3),
            isClean = true
        )
        assertEquals(Version(1, 2, 3), version)
    }

    @Test
    fun `computeVersion when tag has variant and dirty`() {
        val version = computeVersion(
            branch = "main",
            tagVersion = Version(1, 2, 3, "rc1"),
            isClean = false
        )
        // Patch bumped because tag has variant, rc1 carried forward, local added
        assertEquals(Version(1, 2, 4, "rc1-local"), version)
    }

    @Test
    fun `computeVersion when tag has numeric variant and dirty`() {
        val version = computeVersion(
            branch = "main",
            tagVersion = Version(1, 2, 3, "5"),
            isClean = false
        )
        // Numeric variant should be incremented
        assertEquals(Version(1, 2, 4, "6-local"), version)
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `gitVersion returns nogit for non-repo directory`() {
        val version = gitVersion(tempDir)
        assertEquals(Version(0, 0, 1, "nogit"), version)
    }

    @Test
    fun `gitVersion works on real git repo`() {
        // Test on kbuild's own directory
        val kbuildDir = File(".").absoluteFile
        if (kbuildDir.resolve(".git").exists()) {
            val version = gitVersion(kbuildDir)
            assertNotNull(version)
            assertTrue(version.major >= 0)
            assertTrue(version.minor >= 0)
            assertTrue(version.patch >= 0)
            println("KBuild git version: $version")
        }
    }
}
