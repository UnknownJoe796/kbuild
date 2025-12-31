package com.ivieleague.kbuild.ios

import com.ivieleague.kbuild.kmp.KmpProjectConfig
import com.ivieleague.kbuild.kmp.kmpBuildFrameworkBlocking
import com.ivieleague.kbuild.kmp.KmpTarget
import java.io.File

/**
 * Builds an XCFramework from multiple architecture-specific frameworks.
 *
 * XCFramework is Apple's format for distributing frameworks that support multiple
 * platforms and architectures. It bundles together:
 * - iOS device (arm64)
 * - iOS simulator (arm64 + x86_64)
 * - macOS (arm64 + x86_64)
 * - etc.
 *
 * This is the recommended format for distributing binary frameworks for iOS.
 *
 * Example usage:
 * ```
 * val xcframework = XCFramework(
 *     name = "MyFramework",
 *     outputDir = File("build/xcframeworks")
 * )
 * xcframework.build(
 *     frameworks = mapOf(
 *         XCFramework.Platform.IOS_DEVICE to deviceFramework,
 *         XCFramework.Platform.IOS_SIMULATOR to simulatorFramework
 *     )
 * )
 * ```
 */
class XCFramework(
    val name: String,
    val outputDir: File
) {
    /**
     * Target platforms for XCFramework.
     */
    enum class Platform(val description: String) {
        IOS_DEVICE("iOS Device (arm64)"),
        IOS_SIMULATOR("iOS Simulator (arm64, x86_64)"),
        MACOS("macOS (arm64, x86_64)"),
        WATCHOS_DEVICE("watchOS Device"),
        WATCHOS_SIMULATOR("watchOS Simulator"),
        TVOS_DEVICE("tvOS Device"),
        TVOS_SIMULATOR("tvOS Simulator")
    }

    /**
     * The output XCFramework path.
     */
    val outputPath: File = outputDir.resolve("$name.xcframework")

    /**
     * Build an XCFramework from the provided frameworks.
     *
     * @param frameworks Map of platform to framework directory
     * @return The created XCFramework directory
     */
    fun build(frameworks: Map<Platform, File>): File {
        require(frameworks.isNotEmpty()) { "At least one framework is required" }

        // Validate all frameworks exist
        for ((platform, framework) in frameworks) {
            require(framework.exists()) {
                "Framework for $platform does not exist: $framework"
            }
            require(framework.isDirectory && framework.name.endsWith(".framework")) {
                "Invalid framework path for $platform: $framework (expected .framework directory)"
            }
        }

        outputDir.mkdirs()

        // Remove existing XCFramework if present
        if (outputPath.exists()) {
            outputPath.deleteRecursively()
        }

        // Build xcodebuild command
        val args = mutableListOf("xcodebuild", "-create-xcframework")

        for ((_, framework) in frameworks) {
            args.add("-framework")
            args.add(framework.absolutePath)
        }

        args.add("-output")
        args.add(outputPath.absolutePath)

        println("Creating XCFramework: $name")
        println("  Input frameworks: ${frameworks.size}")
        frameworks.forEach { (platform, path) ->
            println("    - $platform: $path")
        }

        val process = ProcessBuilder(args)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("xcodebuild -create-xcframework failed with exit code: $exitCode")
        }

        println("Created XCFramework: $outputPath")
        return outputPath
    }

    /**
     * Build XCFramework for iOS (device + simulator).
     *
     * @param deviceFramework Framework built for iOS device (arm64)
     * @param simulatorFramework Framework built for iOS simulator
     */
    fun buildForIos(
        deviceFramework: File,
        simulatorFramework: File
    ): File = build(
        mapOf(
            Platform.IOS_DEVICE to deviceFramework,
            Platform.IOS_SIMULATOR to simulatorFramework
        )
    )

    /**
     * Build XCFramework for iOS and macOS.
     */
    fun buildForApple(
        iosDeviceFramework: File,
        iosSimulatorFramework: File,
        macosFramework: File
    ): File = build(
        mapOf(
            Platform.IOS_DEVICE to iosDeviceFramework,
            Platform.IOS_SIMULATOR to iosSimulatorFramework,
            Platform.MACOS to macosFramework
        )
    )

    companion object {
        /**
         * Check if xcodebuild is available on this system.
         */
        fun isAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("which", "xcodebuild")
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Create an XCFramework from a KmpProjectConfig.
         *
         * This builds all necessary iOS targets and combines them into an XCFramework.
         *
         * @param project The KMP project
         * @param outputDir Output directory for the XCFramework
         * @param includeSimulator Whether to include simulator builds
         * @param includeMacos Whether to include macOS builds
         */
        fun fromKmpProjectConfig(
            project: KmpProjectConfig,
            outputDir: File = project.buildDir.resolve("xcframeworks"),
            includeSimulator: Boolean = true,
            includeMacos: Boolean = false
        ): File {
            require(isAvailable()) {
                "xcodebuild is not available. XCFramework creation requires Xcode on macOS."
            }

            val frameworks = mutableMapOf<Platform, File>()

            // Build iOS device framework (arm64)
            if (KmpTarget.Native.IosArm64 in project.targets) {
                val deviceFramework = kmpBuildFrameworkBlocking(project, KmpTarget.Native.IosArm64)
                frameworks[Platform.IOS_DEVICE] = deviceFramework
            }

            // Build iOS simulator framework
            // For universal simulator support, we'd need to build both arm64 and x86_64
            // and lipo them together. For now, just use the host architecture.
            if (includeSimulator) {
                val simulatorTarget = if (KmpTarget.Native.IosSimulatorArm64 in project.targets) {
                    KmpTarget.Native.IosSimulatorArm64
                } else if (KmpTarget.Native.IosX64 in project.targets) {
                    KmpTarget.Native.IosX64
                } else {
                    null
                }

                if (simulatorTarget != null) {
                    val simulatorFramework = kmpBuildFrameworkBlocking(project, simulatorTarget)
                    frameworks[Platform.IOS_SIMULATOR] = simulatorFramework
                }
            }

            // Build macOS framework
            if (includeMacos) {
                val macosTarget = if (KmpTarget.Native.MacosArm64 in project.targets) {
                    KmpTarget.Native.MacosArm64
                } else if (KmpTarget.Native.MacosX64 in project.targets) {
                    KmpTarget.Native.MacosX64
                } else {
                    null
                }

                if (macosTarget != null) {
                    val macosFramework = kmpBuildFrameworkBlocking(project, macosTarget)
                    frameworks[Platform.MACOS] = macosFramework
                }
            }

            require(frameworks.isNotEmpty()) {
                "No iOS/macOS targets found in project. Enable at least one Apple target."
            }

            val xcframework = XCFramework(project.name, outputDir)
            return xcframework.build(frameworks)
        }
    }
}

/**
 * Creates a "fat" or "universal" framework by combining multiple architectures.
 *
 * This is used to combine arm64 and x86_64 simulator frameworks into one.
 * Note: This approach is deprecated in favor of XCFramework, but still useful
 * for some CocoaPods setups.
 */
class UniversalFramework(
    val name: String,
    val outputDir: File
) {
    val outputPath: File = outputDir.resolve("$name.framework")

    /**
     * Combine multiple frameworks with different architectures into one.
     *
     * @param frameworks List of framework directories to combine
     * @return The universal framework directory
     */
    fun build(vararg frameworks: File): File {
        require(frameworks.isNotEmpty()) { "At least one framework is required" }
        require(frameworks.all { it.exists() && it.isDirectory }) {
            "All frameworks must exist and be directories"
        }

        outputDir.mkdirs()

        // Remove existing framework if present
        if (outputPath.exists()) {
            outputPath.deleteRecursively()
        }

        // Copy the first framework as base
        val baseFramework = frameworks.first()
        baseFramework.copyRecursively(outputPath)

        if (frameworks.size == 1) {
            return outputPath
        }

        // Use lipo to combine binaries
        val binaryName = name
        val outputBinary = outputPath.resolve(binaryName)
        val inputBinaries = frameworks.map { it.resolve(binaryName).absolutePath }

        val args = mutableListOf("lipo", "-create")
        args.addAll(inputBinaries)
        args.add("-output")
        args.add(outputBinary.absolutePath)

        println("Creating universal framework: $name")
        println("  Combining ${frameworks.size} architectures")

        val process = ProcessBuilder(args)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("lipo failed with exit code: $exitCode")
        }

        println("Created universal framework: $outputPath")
        return outputPath
    }

    companion object {
        /**
         * Check if lipo is available (should be on any macOS system).
         */
        fun isAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("which", "lipo")
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
