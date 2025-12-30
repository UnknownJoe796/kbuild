package com.ivieleague.kbuild.native

import java.io.File

/**
 * Configuration for CInterop - generating Kotlin bindings from C headers.
 *
 * CInterop allows you to use C libraries from Kotlin/Native code by generating
 * Kotlin declarations that map to C functions, types, and structures.
 *
 * @param name Name of the interop library
 * @param defFile Path to the .def file describing the library
 * @param target Target platform
 * @param outputDir Directory for the generated .klib
 * @param packageName Optional package name for generated Kotlin declarations
 * @param headers Additional header files to include
 * @param headerDirs Directories to search for headers
 * @param compilerOpts Additional C compiler options
 * @param linkerOpts Additional linker options
 */
class CInterop(
    val name: String,
    val defFile: File,
    val target: KonanTarget = KonanTarget.host(),
    val outputDir: File,
    val packageName: String? = null,
    val headers: List<File> = emptyList(),
    val headerDirs: List<File> = emptyList(),
    val compilerOpts: List<String> = emptyList(),
    val linkerOpts: List<String> = emptyList()
) : () -> File {

    private val compiler = KonanCompiler.default()

    /**
     * The output .klib file.
     */
    val outputFile: File = outputDir.resolve("$name.klib")

    override fun invoke(): File {
        outputDir.mkdirs()

        // Build cinterop arguments
        val args = mutableListOf<String>()

        // Package name
        packageName?.let {
            args.add("-pkg")
            args.add(it)
        }

        // Additional headers
        headers.forEach { header ->
            args.add("-header")
            args.add(header.absolutePath)
        }

        // Header directories
        headerDirs.forEach { dir ->
            args.add("-headerFilterAdditionalSearchPrefix")
            args.add(dir.absolutePath)
        }

        // Compiler options
        compilerOpts.forEach { opt ->
            args.add("-compilerOpts")
            args.add(opt)
        }

        // Linker options
        linkerOpts.forEach { opt ->
            args.add("-linkerOpts")
            args.add(opt)
        }

        println("Running cinterop for $name...")

        return compiler.cinterop(
            defFile = defFile,
            output = outputFile,
            target = target,
            additionalArgs = args
        )
    }
}

/**
 * Builder for creating .def files programmatically.
 *
 * Example:
 * ```
 * val defFile = DefFileBuilder("mylib")
 *     .headers("mylib.h")
 *     .headerFilter("mylib_headers")
 *     .compilerOpts("-I/usr/local/include")
 *     .linkerOpts("-L/usr/local/lib", "-lmylib")
 *     .build(outputDir)
 * ```
 */
class DefFileBuilder(private val name: String) {
    private val headers = mutableListOf<String>()
    private val headerFilter = mutableListOf<String>()
    private val compilerOpts = mutableListOf<String>()
    private val linkerOpts = mutableListOf<String>()
    private var packageName: String? = null
    private var staticLibraries: String? = null
    private var libraryPaths: String? = null
    private var excludedFunctions: String? = null
    private var strictEnums: String? = null

    fun headers(vararg h: String) = apply { headers.addAll(h) }
    fun headerFilter(vararg patterns: String) = apply { headerFilter.addAll(patterns) }
    fun compilerOpts(vararg opts: String) = apply { compilerOpts.addAll(opts) }
    fun linkerOpts(vararg opts: String) = apply { linkerOpts.addAll(opts) }
    fun packageName(name: String) = apply { packageName = name }
    fun staticLibraries(libs: String) = apply { staticLibraries = libs }
    fun libraryPaths(paths: String) = apply { libraryPaths = paths }
    fun excludedFunctions(funcs: String) = apply { excludedFunctions = funcs }
    fun strictEnums(enums: String) = apply { strictEnums = enums }

    /**
     * Build the .def file content.
     */
    fun buildContent(): String {
        return buildString {
            if (headers.isNotEmpty()) {
                appendLine("headers = ${headers.joinToString(" ")}")
            }
            if (headerFilter.isNotEmpty()) {
                appendLine("headerFilter = ${headerFilter.joinToString(" ")}")
            }
            if (compilerOpts.isNotEmpty()) {
                appendLine("compilerOpts = ${compilerOpts.joinToString(" ")}")
            }
            if (linkerOpts.isNotEmpty()) {
                appendLine("linkerOpts = ${linkerOpts.joinToString(" ")}")
            }
            packageName?.let { appendLine("package = $it") }
            staticLibraries?.let { appendLine("staticLibraries = $it") }
            libraryPaths?.let { appendLine("libraryPaths = $it") }
            excludedFunctions?.let { appendLine("excludedFunctions = $it") }
            strictEnums?.let { appendLine("strictEnums = $it") }
        }
    }

    /**
     * Write the .def file to a directory.
     */
    fun build(outputDir: File): File {
        outputDir.mkdirs()
        val defFile = outputDir.resolve("$name.def")
        defFile.writeText(buildContent())
        return defFile
    }
}

/**
 * DSL for creating a CInterop configuration.
 */
fun cinterop(
    name: String,
    defFile: File,
    target: KonanTarget = KonanTarget.host(),
    outputDir: File,
    packageName: String? = null
): CInterop = CInterop(
    name = name,
    defFile = defFile,
    target = target,
    outputDir = outputDir,
    packageName = packageName
)

/**
 * DSL for creating a .def file.
 */
fun defFile(name: String, block: DefFileBuilder.() -> Unit): DefFileBuilder {
    return DefFileBuilder(name).apply(block)
}

/**
 * Common system library .def file templates.
 */
object SystemLibraries {

    /**
     * Create a .def file for POSIX C library functions.
     */
    fun posix(outputDir: File): File {
        return DefFileBuilder("posix")
            .headers("stdio.h", "stdlib.h", "string.h", "unistd.h", "fcntl.h", "errno.h")
            .packageName("platform.posix")
            .build(outputDir)
    }

    /**
     * Create a .def file for Apple Foundation framework.
     */
    fun foundation(outputDir: File): File {
        return DefFileBuilder("Foundation")
            .headers("Foundation/Foundation.h")
            .compilerOpts("-framework", "Foundation")
            .linkerOpts("-framework", "Foundation")
            .packageName("platform.Foundation")
            .build(outputDir)
    }

    /**
     * Create a .def file for Apple UIKit framework.
     */
    fun uiKit(outputDir: File): File {
        return DefFileBuilder("UIKit")
            .headers("UIKit/UIKit.h")
            .compilerOpts("-framework", "UIKit")
            .linkerOpts("-framework", "UIKit")
            .packageName("platform.UIKit")
            .build(outputDir)
    }

    /**
     * Create a .def file for Apple AppKit framework (macOS).
     */
    fun appKit(outputDir: File): File {
        return DefFileBuilder("AppKit")
            .headers("AppKit/AppKit.h")
            .compilerOpts("-framework", "AppKit")
            .linkerOpts("-framework", "AppKit")
            .packageName("platform.AppKit")
            .build(outputDir)
    }
}
