package com.ivieleague.kbuild.native

import com.ivieleague.kbuild.common.Producer
import com.ivieleague.kbuild.kotlin.Kotlin
import java.io.File

/**
 * Compiles Kotlin source files to native binaries using the Kotlin/Native compiler.
 *
 * Features:
 * - Cross-compilation support for multiple targets
 * - Multiple output kinds (executable, static/dynamic library, framework)
 * - Library linking (.klib files)
 * - Debug and release builds
 *
 * The compiler distribution is automatically downloaded and cached in ~/.konan
 * (same location as Gradle) to avoid duplicate downloads.
 *
 * @param name Module name
 * @param sourceRoots Producer of source root directories
 * @param libraries Producer of .klib library files
 * @param target Target platform (defaults to host)
 * @param outputKind Type of output to produce
 * @param outputDir Output directory
 * @param optimizations Enable release optimizations
 * @param debug Include debug information
 * @param additionalArgs Additional compiler arguments
 */
class KotlinNativeCompile(
    val name: String,
    val sourceRoots: Producer<File>,
    val libraries: Producer<File> = { emptySet() },
    val target: KonanTarget = KonanTarget.host(),
    val outputKind: NativeOutputKind = NativeOutputKind.EXECUTABLE,
    val outputDir: File,
    val optimizations: Boolean = false,
    val debug: Boolean = true,
    val additionalArgs: List<String> = emptyList()
) : () -> File {

    private val compiler = KonanCompiler.default()

    /**
     * The output file name based on output kind and target.
     */
    val outputFile: File
        get() {
            val baseName = name
            val extension = when (outputKind) {
                NativeOutputKind.EXECUTABLE -> when (target.family) {
                    TargetFamily.MINGW -> ".exe"
                    else -> ".kexe"
                }
                NativeOutputKind.STATIC -> ".a"
                NativeOutputKind.DYNAMIC -> when (target.family) {
                    TargetFamily.OSX, TargetFamily.IOS, TargetFamily.WATCHOS, TargetFamily.TVOS -> ".dylib"
                    TargetFamily.MINGW -> ".dll"
                    else -> ".so"
                }
                NativeOutputKind.LIBRARY -> ".klib"
                NativeOutputKind.FRAMEWORK, NativeOutputKind.STATIC_FRAMEWORK -> ".framework"
            }
            return outputDir.resolve("$baseName$extension")
        }

    override fun invoke(): File {
        outputDir.mkdirs()

        // Collect source files
        val sources = sourceRoots().toList()
        if (sources.isEmpty()) {
            throw IllegalStateException("No source directories provided")
        }

        val allSourceFiles = sources.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.toList()
        }

        if (allSourceFiles.isEmpty()) {
            throw IllegalStateException("No Kotlin source files found in: $sources")
        }

        // Collect libraries
        val libs = libraries().toList()

        println("Compiling ${allSourceFiles.size} Kotlin files to ${target.targetName}...")

        return compiler.compile(
            sources = sources,
            output = outputFile,
            target = target,
            outputKind = outputKind,
            libraries = libs,
            optimizations = optimizations,
            debug = debug,
            additionalArgs = additionalArgs
        )
    }

    /**
     * Ensure the Kotlin/Native compiler is installed.
     */
    fun ensureCompilerInstalled(): File {
        return compiler.ensureInstalled()
    }
}

/**
 * Creates a Kotlin/Native compiler for producing an executable.
 */
fun kotlinNativeExecutable(
    name: String,
    sourceRoots: Producer<File>,
    libraries: Producer<File> = { emptySet() },
    target: KonanTarget = KonanTarget.host(),
    outputDir: File,
    optimizations: Boolean = false,
    debug: Boolean = true
): KotlinNativeCompile = KotlinNativeCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.EXECUTABLE,
    outputDir = outputDir,
    optimizations = optimizations,
    debug = debug
)

/**
 * Creates a Kotlin/Native compiler for producing a .klib library.
 */
fun kotlinNativeLibrary(
    name: String,
    sourceRoots: Producer<File>,
    libraries: Producer<File> = { emptySet() },
    target: KonanTarget = KonanTarget.host(),
    outputDir: File
): KotlinNativeCompile = KotlinNativeCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.LIBRARY,
    outputDir = outputDir,
    optimizations = false,
    debug = false
)

/**
 * Creates a Kotlin/Native compiler for producing an Apple framework.
 */
fun kotlinNativeFramework(
    name: String,
    sourceRoots: Producer<File>,
    libraries: Producer<File> = { emptySet() },
    target: KonanTarget,
    outputDir: File,
    static: Boolean = false
): KotlinNativeCompile {
    require(target.family in listOf(TargetFamily.OSX, TargetFamily.IOS, TargetFamily.WATCHOS, TargetFamily.TVOS)) {
        "Frameworks are only supported on Apple platforms, got: $target"
    }
    return KotlinNativeCompile(
        name = name,
        sourceRoots = sourceRoots,
        libraries = libraries,
        target = target,
        outputKind = if (static) NativeOutputKind.STATIC_FRAMEWORK else NativeOutputKind.FRAMEWORK,
        outputDir = outputDir,
        optimizations = false,
        debug = true
    )
}

/**
 * Creates a Kotlin/Native compiler for producing a static library.
 */
fun kotlinNativeStaticLibrary(
    name: String,
    sourceRoots: Producer<File>,
    libraries: Producer<File> = { emptySet() },
    target: KonanTarget = KonanTarget.host(),
    outputDir: File
): KotlinNativeCompile = KotlinNativeCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.STATIC,
    outputDir = outputDir,
    optimizations = false,
    debug = true
)

/**
 * Creates a Kotlin/Native compiler for producing a dynamic/shared library.
 */
fun kotlinNativeDynamicLibrary(
    name: String,
    sourceRoots: Producer<File>,
    libraries: Producer<File> = { emptySet() },
    target: KonanTarget = KonanTarget.host(),
    outputDir: File
): KotlinNativeCompile = KotlinNativeCompile(
    name = name,
    sourceRoots = sourceRoots,
    libraries = libraries,
    target = target,
    outputKind = NativeOutputKind.DYNAMIC,
    outputDir = outputDir,
    optimizations = false,
    debug = true
)
