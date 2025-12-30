package com.ivieleague.kbuild.cli

import java.io.File
import kotlin.test.*

class KBuildCliTest {

    // ==================== Mode Parsing ====================

    @Test
    fun `parseArgs with no args defaults to HELP mode`() {
        val options = KBuildCli.parseArgs(arrayOf())
        assertEquals(KBuildCli.Mode.HELP, options.mode)
    }

    @Test
    fun `parseArgs with --help returns HELP mode`() {
        val options = KBuildCli.parseArgs(arrayOf("--help"))
        assertEquals(KBuildCli.Mode.HELP, options.mode)
    }

    @Test
    fun `parseArgs with -h returns HELP mode`() {
        val options = KBuildCli.parseArgs(arrayOf("-h"))
        assertEquals(KBuildCli.Mode.HELP, options.mode)
    }

    @Test
    fun `parseArgs with --version returns VERSION mode`() {
        val options = KBuildCli.parseArgs(arrayOf("--version"))
        assertEquals(KBuildCli.Mode.VERSION, options.mode)
    }

    @Test
    fun `parseArgs with --repl returns REPL mode`() {
        val options = KBuildCli.parseArgs(arrayOf("--repl"))
        assertEquals(KBuildCli.Mode.REPL, options.mode)
    }

    @Test
    fun `parseArgs with --daemon returns DAEMON mode`() {
        val options = KBuildCli.parseArgs(arrayOf("--daemon"))
        assertEquals(KBuildCli.Mode.DAEMON, options.mode)
    }

    @Test
    fun `parseArgs with --list returns LIST mode`() {
        val options = KBuildCli.parseArgs(arrayOf("--list"))
        assertEquals(KBuildCli.Mode.LIST, options.mode)
    }

    @Test
    fun `parseArgs with -l returns LIST mode`() {
        val options = KBuildCli.parseArgs(arrayOf("-l"))
        assertEquals(KBuildCli.Mode.LIST, options.mode)
    }

    @Test
    fun `parseArgs with expression returns RUN mode`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertEquals(KBuildCli.Mode.RUN, options.mode)
        assertEquals("Build.compile", options.expression)
    }

    // ==================== Expression Parsing ====================

    @Test
    fun `parseArgs captures simple expression`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertEquals("Build.compile", options.expression)
    }

    @Test
    fun `parseArgs captures expression with method call`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.test()"))
        assertEquals("Build.test()", options.expression)
    }

    @Test
    fun `parseArgs captures chained expression`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.project.sources"))
        assertEquals("Build.project.sources", options.expression)
    }

    @Test
    fun `parseArgs expression is null when no expression given`() {
        val options = KBuildCli.parseArgs(arrayOf("--list"))
        assertNull(options.expression)
    }

    // ==================== Watch Flag ====================

    @Test
    fun `parseArgs with --watch sets watch flag`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "--watch"))
        assertTrue(options.watch)
    }

    @Test
    fun `parseArgs with -w sets watch flag`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "-w"))
        assertTrue(options.watch)
    }

    @Test
    fun `parseArgs watch flag defaults to false`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertFalse(options.watch)
    }

    @Test
    fun `parseArgs watch flag before expression`() {
        val options = KBuildCli.parseArgs(arrayOf("--watch", "Build.compile"))
        assertTrue(options.watch)
        assertEquals("Build.compile", options.expression)
    }

    // ==================== Verbose Flag ====================

    @Test
    fun `parseArgs with --verbose sets verbose flag`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "--verbose"))
        assertTrue(options.verbose)
    }

    @Test
    fun `parseArgs with -v sets verbose flag`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "-v"))
        assertTrue(options.verbose)
    }

    @Test
    fun `parseArgs verbose defaults to false`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertFalse(options.verbose)
    }

    // ==================== Project Path ====================

    @Test
    fun `parseArgs with --project sets project path`() {
        val options = KBuildCli.parseArgs(arrayOf("--project", "/custom/path", "Build.compile"))
        assertEquals(File("/custom/path"), options.projectPath)
    }

    @Test
    fun `parseArgs with -p sets project path`() {
        val options = KBuildCli.parseArgs(arrayOf("-p", "/custom/path", "Build.compile"))
        assertEquals(File("/custom/path"), options.projectPath)
    }

    @Test
    fun `parseArgs project path defaults to current directory`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertEquals(File("."), options.projectPath)
    }

    // ==================== Build Class ====================

    @Test
    fun `parseArgs with --build sets build class`() {
        val options = KBuildCli.parseArgs(arrayOf("--build", "MyBuild", "compile"))
        assertEquals("MyBuild", options.buildClass)
    }

    @Test
    fun `parseArgs with -b sets build class`() {
        val options = KBuildCli.parseArgs(arrayOf("-b", "CustomBuild", "compile"))
        assertEquals("CustomBuild", options.buildClass)
    }

    @Test
    fun `parseArgs build class defaults to Build`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertEquals("Build", options.buildClass)
    }

    // ==================== Extra Args ====================

    @Test
    fun `parseArgs captures unknown flags as extra args`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "--unknown-flag"))
        assertTrue("--unknown-flag" in options.extraArgs)
    }

    @Test
    fun `parseArgs captures extra positional args`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "extra1", "extra2"))
        assertTrue("extra1" in options.extraArgs)
        assertTrue("extra2" in options.extraArgs)
    }

    // ==================== Combined Flags ====================

    @Test
    fun `parseArgs combines multiple flags`() {
        val options = KBuildCli.parseArgs(arrayOf(
            "-v", "-w", "-p", "/my/project", "-b", "MyBuild", "MyBuild.compile"
        ))

        assertEquals(KBuildCli.Mode.RUN, options.mode)
        assertEquals("MyBuild.compile", options.expression)
        assertTrue(options.verbose)
        assertTrue(options.watch)
        assertEquals(File("/my/project"), options.projectPath)
        assertEquals("MyBuild", options.buildClass)
    }

    @Test
    fun `parseArgs flags after expression`() {
        val options = KBuildCli.parseArgs(arrayOf(
            "Build.compile", "--watch", "--verbose"
        ))

        assertEquals("Build.compile", options.expression)
        assertTrue(options.watch)
        assertTrue(options.verbose)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `parseArgs mode flag overrides previous mode`() {
        // Later flags take precedence
        val options = KBuildCli.parseArgs(arrayOf("--help", "--version"))
        assertEquals(KBuildCli.Mode.VERSION, options.mode)
    }

    @Test
    fun `parseArgs expression mode overrides help`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile"))
        assertEquals(KBuildCli.Mode.RUN, options.mode)
    }

    @Test
    fun `parseArgs handles project path at end`() {
        val options = KBuildCli.parseArgs(arrayOf("Build.compile", "-p", "/path"))
        assertEquals(File("/path"), options.projectPath)
    }

    @Test
    fun `parseArgs handles missing project path value gracefully`() {
        // When -p is at end with no value, should default
        val options = KBuildCli.parseArgs(arrayOf("-p"))
        assertEquals(File("."), options.projectPath)
    }

    @Test
    fun `parseArgs handles missing build class value gracefully`() {
        // When -b is at end with no value, should default
        val options = KBuildCli.parseArgs(arrayOf("-b"))
        assertEquals("Build", options.buildClass)
    }

    // ==================== CliOptions Data Class ====================

    @Test
    fun `CliOptions has correct default values`() {
        val options = KBuildCli.CliOptions(
            mode = KBuildCli.Mode.RUN,
            expression = "test"
        )

        assertEquals(File("."), options.projectPath)
        assertEquals("Build", options.buildClass)
        assertFalse(options.verbose)
        assertFalse(options.watch)
        assertTrue(options.extraArgs.isEmpty())
    }

    // ==================== Mode Enum ====================

    @Test
    fun `Mode enum has all expected values`() {
        val modes = KBuildCli.Mode.values()
        assertTrue(KBuildCli.Mode.RUN in modes)
        assertTrue(KBuildCli.Mode.REPL in modes)
        assertTrue(KBuildCli.Mode.DAEMON in modes)
        assertTrue(KBuildCli.Mode.LIST in modes)
        assertTrue(KBuildCli.Mode.HELP in modes)
        assertTrue(KBuildCli.Mode.VERSION in modes)
    }
}
