package com.ivieleague.kbuild.kmp

import java.io.File

/**
 * A C / Objective-C interop declaration for native targets, mirroring Gradle's `cinterops.creating`.
 *
 * For each enabled native target, kbuild runs the Kotlin/Native `cinterop` tool against [defFile] and
 * puts the resulting `<name>.klib` on that target's library path — for the main compile, the test
 * compile, and the executable/framework link. The def file's `language`, `package`, `headers`,
 * `compilerOpts`, `linkerOpts`, and body (the text after `---`) are honored as-is; set
 * [packageName]/[compilerOpts] here only to override or add to what the def declares.
 *
 * Objective-C interop (`language = Objective-C` in the def) requires an Apple target compiled on an
 * Apple host. Restrict such interops with [appliesTo].
 *
 * @param name klib name; also the generated Kotlin interop module name
 * @param defFile the `.def` file describing the C/ObjC library
 * @param packageName overrides the def's `package` (`-pkg`) when set
 * @param compilerOpts extra options forwarded to the cinterop tool's C compiler
 * @param appliesTo restricts which native targets this interop is generated for (default: all)
 */
data class NativeCInterop(
    val name: String,
    val defFile: File,
    val packageName: String? = null,
    val compilerOpts: List<String> = emptyList(),
    val appliesTo: (KmpTarget.Native) -> Boolean = { true }
)
