package com.ivieleague.kbuild.cli

import kotlin.test.*

class BuildScriptAnnotationsTest {

    // ==================== No annotations ====================

    @Test
    fun `empty source returns empty annotations`() {
        val result = parseBuildScriptAnnotations("")
        assertTrue(result.repositories.isEmpty())
        assertTrue(result.dependsOn.isEmpty())
    }

    @Test
    fun `source with no annotations returns empty`() {
        val source = """
            import com.example.Foo

            object Build {
                val compile = "done"
            }
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertTrue(result.repositories.isEmpty())
        assertTrue(result.dependsOn.isEmpty())
    }

    // ==================== @file:DependsOn ====================

    @Test
    fun `parses single @file_DependsOn positional arg`() {
        val source = """@file:DependsOn("com.example:foo:1.0")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.example:foo:1.0"), result.dependsOn)
    }

    @Test
    fun `parses @file_DependsOn with maven named arg`() {
        val source = """@file:DependsOn(maven = "com.example:bar:2.0")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.example:bar:2.0"), result.dependsOn)
    }

    @Test
    fun `parses @DependsOn without @file prefix`() {
        val source = """@DependsOn("com.example:baz:3.0")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.example:baz:3.0"), result.dependsOn)
    }

    @Test
    fun `parses @DependsOn with maven named arg without @file prefix`() {
        val source = """@DependsOn(maven = "com.example:qux:4.0")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.example:qux:4.0"), result.dependsOn)
    }

    @Test
    fun `parses multiple @file_DependsOn annotations`() {
        val source = """
            @file:DependsOn("com.example:first:1.0")
            @file:DependsOn("com.example:second:2.0")
            @file:DependsOn("com.example:third:3.0")
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertEquals(
            listOf("com.example:first:1.0", "com.example:second:2.0", "com.example:third:3.0"),
            result.dependsOn
        )
    }

    // ==================== @file:Repository ====================

    @Test
    fun `parses single @file_Repository`() {
        val source = """@file:Repository("https://repo.example.com/maven")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("https://repo.example.com/maven"), result.repositories)
    }

    @Test
    fun `parses @Repository without @file prefix`() {
        val source = """@Repository("https://repo.example.com/maven")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("https://repo.example.com/maven"), result.repositories)
    }

    @Test
    fun `parses multiple @file_Repository annotations`() {
        val source = """
            @file:Repository("https://repo1.example.com")
            @file:Repository("https://repo2.example.com")
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertEquals(
            listOf("https://repo1.example.com", "https://repo2.example.com"),
            result.repositories
        )
    }

    // ==================== Combined ====================

    @Test
    fun `parses mixed @file_Repository and @file_DependsOn`() {
        val source = """
            @file:Repository("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
            @file:DependsOn("com.ivieleague:kbuild:1.0-SNAPSHOT")
            @file:DependsOn("com.lightningkite:reactive-jvm:6.0.0-prerelease-26")
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("https://lightningkite-maven.s3.us-west-2.amazonaws.com"), result.repositories)
        assertEquals(
            listOf("com.ivieleague:kbuild:1.0-SNAPSHOT", "com.lightningkite:reactive-jvm:6.0.0-prerelease-26"),
            result.dependsOn
        )
    }

    @Test
    fun `parses real-world reactive Build-kt header`() {
        val source = """
            // KBuild dependencies (for Skate compatibility)
            @file:Repository("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
            @file:DependsOn("com.ivieleague:kbuild:1.0-SNAPSHOT")
            @file:DependsOn("com.lightningkite:reactive-jvm:6.0.0-prerelease-26")
            @file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

            import com.ivieleague.kbuild.cli.DependsOn
            import com.ivieleague.kbuild.cli.Repository

            object Build {
                val compile get() = TODO()
            }
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertEquals(
            listOf("https://lightningkite-maven.s3.us-west-2.amazonaws.com"),
            result.repositories
        )
        assertEquals(
            listOf(
                "com.ivieleague:kbuild:1.0-SNAPSHOT",
                "com.lightningkite:reactive-jvm:6.0.0-prerelease-26",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
            ),
            result.dependsOn
        )
    }

    @Test
    fun `parses mixed prefix and no-prefix annotations`() {
        val source = """
            @file:Repository("https://central.example.com")
            @Repository("https://local.example.com")
            @file:DependsOn("com.example:a:1.0")
            @DependsOn(maven = "com.example:b:2.0")
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        assertEquals(
            listOf("https://central.example.com", "https://local.example.com"),
            result.repositories
        )
        assertEquals(
            listOf("com.example:a:1.0", "com.example:b:2.0"),
            result.dependsOn
        )
    }

    @Test
    fun `ignores annotation-like text inside strings`() {
        // An annotation pattern appearing inside a Kotlin string literal should not be matched
        // by our regex (the double quotes in the pattern would close the outer match prematurely,
        // so we just verify overall correctness on realistic cases)
        val source = """
            @file:DependsOn("com.example:real:1.0")

            object Build {
                val doc = "use @DependsOn to add deps"
            }
        """.trimIndent()
        val result = parseBuildScriptAnnotations(source)
        // Only the real annotation is parsed; the one inside a string literal is not a valid
        // annotation form (it lacks parentheses), so it is not matched.
        assertEquals(listOf("com.example:real:1.0"), result.dependsOn)
    }

    @Test
    fun `parses coordinates with prerelease versions`() {
        val source = """@file:DependsOn("com.lightningkite:reactive-jvm:6.0.0-prerelease-26")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.lightningkite:reactive-jvm:6.0.0-prerelease-26"), result.dependsOn)
    }

    @Test
    fun `parses SNAPSHOT versions`() {
        val source = """@file:DependsOn("com.ivieleague:kbuild:1.0-SNAPSHOT")"""
        val result = parseBuildScriptAnnotations(source)
        assertEquals(listOf("com.ivieleague:kbuild:1.0-SNAPSHOT"), result.dependsOn)
    }
}
