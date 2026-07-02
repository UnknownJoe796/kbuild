package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.native.KonanTarget
import com.ivieleague.kbuild.native.TargetFamily
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end tests for cinterop wired into the KMP pipeline: a [NativeCInterop] declaration must
 * produce a per-target klib that lands on the native library path automatically, so ordinary
 * `nativeMain` Kotlin can call the C / Objective-C bindings and the whole thing compiles, links, and
 * runs.
 *
 * These exercise the real Kotlin/Native `cinterop` + `konanc` toolchain against the host target, so
 * they are slow (like the other native tests) and only run where that toolchain applies.
 */
class CInteropKmpTest {

    private fun writeMain(root: File, code: String) {
        val src = root.resolve("src/nativeMain/kotlin").also { it.mkdirs() }
        src.resolve("Main.kt").writeText(code)
    }

    private fun runExe(exe: File): String {
        val process = ProcessBuilder(exe.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue(exit == 0, "Executable should run successfully, got exit code $exit; output:\n$output")
        return output
    }

    @Test
    fun `C interop klib is generated and callable from nativeMain`() {
        // Skip on Windows: the def-body C snippet is toolchain-portable everywhere else we run.
        assumeTrue(KonanTarget.host().family != TargetFamily.MINGW, "cinterop C test skipped on MinGW host")

        val root = File("build/run/CInteropKmpTest-c").apply { deleteRecursively(); mkdirs() }

        // A def with an inline C body (text after `---`): cinterop compiles it into the klib's stubs,
        // so no external library needs to exist on disk — the binding is fully self-contained.
        val defFile = root.resolve("def/ckbuild.def").also { it.parentFile.mkdirs() }
        defFile.writeText(
            """
            package = ckbuild
            ---
            int kbuild_add(int a, int b) { return a + b; }
            """.trimIndent()
        )

        writeMain(
            root,
            """
            @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
            import ckbuild.kbuild_add

            fun main() {
                println("SUM=" + kbuild_add(2, 3))
            }
            """.trimIndent()
        )

        val config = KmpProjectConfig(
            name = "cinterop-c",
            projectRoot = root,
            targets = setOf(KmpTarget.Native.host()),
            cinterops = listOf(NativeCInterop(name = "ckbuild", defFile = defFile))
        )

        val exe = runBlocking { kmpCompileNativeExecutable(config, KmpTarget.Native.host()) }
        assertTrue(exe.exists(), "Executable should exist: $exe")

        // Prove the generated klib is where the pipeline placed it (so the caching path is exercised too).
        val klib = config.buildDir.resolve("cinterop/${KmpTarget.Native.host().name}/ckbuild.klib")
        assertTrue(klib.exists(), "cinterop klib should have been generated at $klib")

        val output = runExe(exe)
        assertTrue(output.contains("SUM=5"), "C binding should return 5; output was:\n$output")
    }

    @Test
    fun `Objective-C interop klib is generated and callable from nativeMain`() {
        // Objective-C interop requires an Apple target compiled on an Apple host.
        assumeTrue(KonanTarget.host().family == TargetFamily.OSX, "ObjC cinterop test requires a macOS host")

        val root = File("build/run/CInteropKmpTest-objc").apply { deleteRecursively(); mkdirs() }

        // `language = Objective-C` plus an inline ObjC body that touches the ObjC runtime (NSNumber).
        // linkerOpts pulls in Foundation so the .kexe links.
        val defFile = root.resolve("def/objckbuild.def").also { it.parentFile.mkdirs() }
        defFile.writeText(
            """
            language = Objective-C
            package = objckbuild
            linkerOpts = -framework Foundation
            ---
            #import <Foundation/Foundation.h>
            int kbuild_objc_answer(void) {
                NSNumber* n = [NSNumber numberWithInt: 42];
                return [n intValue];
            }
            """.trimIndent()
        )

        writeMain(
            root,
            """
            @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
            import objckbuild.kbuild_objc_answer

            fun main() {
                println("ANSWER=" + kbuild_objc_answer())
            }
            """.trimIndent()
        )

        val config = KmpProjectConfig(
            name = "cinterop-objc",
            projectRoot = root,
            targets = setOf(KmpTarget.Native.host()),
            cinterops = listOf(
                NativeCInterop(
                    name = "objckbuild",
                    defFile = defFile,
                    appliesTo = { it.isAppleTarget() }
                )
            )
        )

        val exe = runBlocking { kmpCompileNativeExecutable(config, KmpTarget.Native.host()) }
        assertTrue(exe.exists(), "Executable should exist: $exe")

        val output = runExe(exe)
        assertTrue(output.contains("ANSWER=42"), "ObjC binding should return 42; output was:\n$output")
    }
}
