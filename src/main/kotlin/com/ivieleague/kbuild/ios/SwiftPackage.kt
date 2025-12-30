package com.ivieleague.kbuild.ios

import java.io.File
import java.security.MessageDigest

/**
 * Generates a Swift Package Manager Package.swift file for distributing Kotlin/Native frameworks.
 *
 * Swift Package Manager (SPM) is Apple's official dependency manager and the recommended
 * replacement for CocoaPods. It supports binary targets for pre-compiled frameworks like
 * those produced by Kotlin/Native.
 *
 * For Kotlin frameworks, SPM uses binary targets that point to XCFrameworks:
 * - Local: `.binaryTarget(name:path:)` for local development
 * - Remote: `.binaryTarget(name:url:checksum:)` for distribution via URL
 *
 * Example usage:
 * ```
 * val package = SwiftPackage(
 *     name = "MyFramework",
 *     platforms = listOf(SwiftPackage.Platform.iOS("14.0")),
 *     products = listOf(SwiftPackage.Product.library("MyFramework", listOf("MyFramework"))),
 *     targets = listOf(SwiftPackage.Target.binaryTarget("MyFramework", "MyFramework.xcframework"))
 * )
 * package.writeTo(projectRoot)
 * ```
 */
class SwiftPackage(
    val name: String,
    val platforms: List<Platform> = listOf(Platform.iOS("14.0")),
    val products: List<Product> = emptyList(),
    val targets: List<Target> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    val swiftToolsVersion: String = "5.9"
) {
    /**
     * Supported platforms for Swift packages.
     */
    sealed class Platform(val declaration: String) {
        class iOS(version: String) : Platform(".iOS(.v${version.replace(".", "_").replace("_0", "")})") {
            constructor(majorVersion: Int) : this("$majorVersion.0")
        }
        class macOS(version: String) : Platform(".macOS(.v${version.replace(".", "_").replace("_0", "")})") {
            constructor(majorVersion: Int) : this("$majorVersion.0")
        }
        class watchOS(version: String) : Platform(".watchOS(.v${version.replace(".", "_").replace("_0", "")})")
        class tvOS(version: String) : Platform(".tvOS(.v${version.replace(".", "_").replace("_0", "")})")
        class visionOS(version: String) : Platform(".visionOS(.v${version.replace(".", "_").replace("_0", "")})")

        companion object {
            /**
             * Parse a platform version string like "iOS 14.0" or "macOS 12.0"
             */
            fun parse(spec: String): Platform {
                val parts = spec.trim().split(" ", limit = 2)
                val platform = parts[0].lowercase()
                val version = parts.getOrElse(1) { "14.0" }

                return when (platform) {
                    "ios" -> iOS(version)
                    "macos" -> macOS(version)
                    "watchos" -> watchOS(version)
                    "tvos" -> tvOS(version)
                    "visionos" -> visionOS(version)
                    else -> throw IllegalArgumentException("Unknown platform: $platform")
                }
            }
        }
    }

    /**
     * Product definitions for the package.
     */
    sealed class Product(val declaration: String) {
        /**
         * A library product that can be imported by other packages.
         */
        class Library(
            val name: String,
            val targets: List<String>,
            val type: LibraryType = LibraryType.AUTOMATIC
        ) : Product(buildString {
            append(".library(")
            append("name: \"$name\"")
            if (type != LibraryType.AUTOMATIC) {
                append(", type: .${type.name.lowercase()}")
            }
            append(", targets: [${targets.joinToString { "\"$it\"" }}]")
            append(")")
        })

        enum class LibraryType { AUTOMATIC, STATIC, DYNAMIC }

        companion object {
            fun library(name: String, targets: List<String>) = Library(name, targets)
            fun staticLibrary(name: String, targets: List<String>) = Library(name, targets, LibraryType.STATIC)
            fun dynamicLibrary(name: String, targets: List<String>) = Library(name, targets, LibraryType.DYNAMIC)
        }
    }

    /**
     * Target definitions for the package.
     */
    sealed class Target(val declaration: String) {
        /**
         * A binary target pointing to a local XCFramework.
         */
        class BinaryTarget(
            val name: String,
            val path: String
        ) : Target(".binaryTarget(name: \"$name\", path: \"$path\")")

        /**
         * A binary target pointing to a remote XCFramework.
         */
        class RemoteBinaryTarget(
            val name: String,
            val url: String,
            val checksum: String
        ) : Target(".binaryTarget(name: \"$name\", url: \"$url\", checksum: \"$checksum\")")

        /**
         * A regular Swift target with source code.
         */
        class SourceTarget(
            val name: String,
            val dependencies: List<String> = emptyList(),
            val path: String? = null
        ) : Target(buildString {
            append(".target(name: \"$name\"")
            if (dependencies.isNotEmpty()) {
                append(", dependencies: [${dependencies.joinToString { "\"$it\"" }}]")
            }
            if (path != null) {
                append(", path: \"$path\"")
            }
            append(")")
        })

        /**
         * A test target.
         */
        class TestTarget(
            val name: String,
            val dependencies: List<String> = emptyList()
        ) : Target(".testTarget(name: \"$name\", dependencies: [${dependencies.joinToString { "\"$it\"" }}])")

        companion object {
            fun binaryTarget(name: String, path: String) = BinaryTarget(name, path)
            fun remoteBinaryTarget(name: String, url: String, checksum: String) = RemoteBinaryTarget(name, url, checksum)
            fun target(name: String, dependencies: List<String> = emptyList()) = SourceTarget(name, dependencies)
            fun testTarget(name: String, dependencies: List<String> = emptyList()) = TestTarget(name, dependencies)
        }
    }

    /**
     * Package dependencies.
     */
    sealed class Dependency(val declaration: String) {
        class GitHub(
            val owner: String,
            val repo: String,
            val version: VersionRequirement
        ) : Dependency(".package(url: \"https://github.com/$owner/$repo.git\", ${version.declaration})")

        class URL(
            val url: String,
            val version: VersionRequirement
        ) : Dependency(".package(url: \"$url\", ${version.declaration})")

        class Local(
            val path: String
        ) : Dependency(".package(path: \"$path\")")

        sealed class VersionRequirement(val declaration: String) {
            class From(version: String) : VersionRequirement("from: \"$version\"")
            class Exact(version: String) : VersionRequirement("exact: \"$version\"")
            class Branch(branch: String) : VersionRequirement("branch: \"$branch\"")
            class Revision(revision: String) : VersionRequirement("revision: \"$revision\"")
            class Range(from: String, to: String) : VersionRequirement("\"$from\"..<\"$to\"")
            class UpToNextMajor(version: String) : VersionRequirement(".upToNextMajor(from: \"$version\")")
            class UpToNextMinor(version: String) : VersionRequirement(".upToNextMinor(from: \"$version\")")
        }

        companion object {
            fun github(owner: String, repo: String, from: String) =
                GitHub(owner, repo, VersionRequirement.From(from))

            fun url(url: String, from: String) =
                URL(url, VersionRequirement.From(from))

            fun local(path: String) = Local(path)
        }
    }

    /**
     * Generate the Package.swift content.
     */
    fun generate(): String = buildString {
        appendLine("// swift-tools-version: $swiftToolsVersion")
        appendLine("// The swift-tools-version declares the minimum version of Swift required to build this package.")
        appendLine()
        appendLine("import PackageDescription")
        appendLine()
        appendLine("let package = Package(")
        appendLine("    name: \"$name\",")

        // Platforms
        if (platforms.isNotEmpty()) {
            appendLine("    platforms: [")
            platforms.forEachIndexed { index, platform ->
                val comma = if (index < platforms.size - 1) "," else ""
                appendLine("        ${platform.declaration}$comma")
            }
            appendLine("    ],")
        }

        // Products
        if (products.isNotEmpty()) {
            appendLine("    products: [")
            products.forEachIndexed { index, product ->
                val comma = if (index < products.size - 1) "," else ""
                appendLine("        ${product.declaration}$comma")
            }
            appendLine("    ],")
        }

        // Dependencies
        if (dependencies.isNotEmpty()) {
            appendLine("    dependencies: [")
            dependencies.forEachIndexed { index, dep ->
                val comma = if (index < dependencies.size - 1) "," else ""
                appendLine("        ${dep.declaration}$comma")
            }
            appendLine("    ],")
        }

        // Targets
        if (targets.isNotEmpty()) {
            appendLine("    targets: [")
            targets.forEachIndexed { index, target ->
                val comma = if (index < targets.size - 1) "," else ""
                appendLine("        ${target.declaration}$comma")
            }
            appendLine("    ]")
        }

        appendLine(")")
    }

    /**
     * Write the Package.swift to a directory.
     */
    fun writeTo(directory: File): File {
        directory.mkdirs()
        val packageFile = directory.resolve("Package.swift")
        packageFile.writeText(generate())
        return packageFile
    }

    companion object {
        /**
         * Create a simple package for a Kotlin/Native XCFramework.
         *
         * @param name The framework/package name
         * @param xcframeworkPath Path to the XCFramework relative to Package.swift
         * @param iosVersion Minimum iOS version
         * @param macosVersion Optional minimum macOS version
         */
        fun forKotlinFramework(
            name: String,
            xcframeworkPath: String = "$name.xcframework",
            iosVersion: String = "14.0",
            macosVersion: String? = null
        ): SwiftPackage {
            val platforms = mutableListOf<Platform>(Platform.iOS(iosVersion))
            if (macosVersion != null) {
                platforms.add(Platform.macOS(macosVersion))
            }

            return SwiftPackage(
                name = name,
                platforms = platforms,
                products = listOf(Product.library(name, listOf(name))),
                targets = listOf(Target.binaryTarget(name, xcframeworkPath))
            )
        }

        /**
         * Create a package for distributing a Kotlin framework via URL.
         *
         * @param name The framework name
         * @param url URL to the .xcframework.zip file
         * @param checksum SHA256 checksum of the zip file
         * @param iosVersion Minimum iOS version
         */
        fun forRemoteKotlinFramework(
            name: String,
            url: String,
            checksum: String,
            iosVersion: String = "14.0"
        ): SwiftPackage {
            return SwiftPackage(
                name = name,
                platforms = listOf(Platform.iOS(iosVersion)),
                products = listOf(Product.library(name, listOf(name))),
                targets = listOf(Target.remoteBinaryTarget(name, url, checksum))
            )
        }

        /**
         * Calculate SHA256 checksum for a file (used for remote binary targets).
         */
        fun calculateChecksum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Create a zip archive of an XCFramework for distribution.
         *
         * @param xcframework The XCFramework directory
         * @param outputZip The output zip file path
         * @return Pair of (zip file, checksum)
         */
        fun zipXCFramework(xcframework: File, outputZip: File): Pair<File, String> {
            require(xcframework.exists() && xcframework.isDirectory) {
                "XCFramework does not exist or is not a directory: $xcframework"
            }
            require(xcframework.name.endsWith(".xcframework")) {
                "Expected .xcframework directory, got: ${xcframework.name}"
            }

            outputZip.parentFile?.mkdirs()

            // Use system zip command for proper structure
            val process = ProcessBuilder(
                "zip", "-r", "-y", outputZip.absolutePath, xcframework.name
            )
                .directory(xcframework.parentFile)
                .inheritIO()
                .start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("Failed to create zip archive, exit code: $exitCode")
            }

            val checksum = calculateChecksum(outputZip)
            return Pair(outputZip, checksum)
        }
    }
}

/**
 * Swift Package distribution helper.
 *
 * Helps prepare a Kotlin framework for distribution via Swift Package Manager,
 * including creating the XCFramework zip and generating the Package.swift.
 */
class SwiftPackageDistribution(
    val name: String,
    val version: String,
    val xcframework: File,
    val outputDir: File,
    val baseUrl: String? = null, // For remote distribution
    val iosVersion: String = "14.0",
    val macosVersion: String? = null
) {
    /**
     * Prepare for local distribution (direct file path).
     */
    fun prepareLocal(): SwiftPackage {
        val relativePath = xcframework.name
        return SwiftPackage.forKotlinFramework(
            name = name,
            xcframeworkPath = relativePath,
            iosVersion = iosVersion,
            macosVersion = macosVersion
        )
    }

    /**
     * Prepare for remote distribution (URL-based).
     *
     * @return Pair of (Package, checksum) - the checksum can be used to verify the download
     */
    fun prepareRemote(): Pair<SwiftPackage, String> {
        requireNotNull(baseUrl) { "baseUrl is required for remote distribution" }

        // Create zip archive
        val zipFileName = "$name-$version.xcframework.zip"
        val zipFile = outputDir.resolve(zipFileName)
        val (_, checksum) = SwiftPackage.zipXCFramework(xcframework, zipFile)

        val downloadUrl = "$baseUrl/$zipFileName"

        val package_ = SwiftPackage.forRemoteKotlinFramework(
            name = name,
            url = downloadUrl,
            checksum = checksum,
            iosVersion = iosVersion
        )

        return Pair(package_, checksum)
    }

    /**
     * Write the Package.swift for local development.
     */
    fun writeLocalPackage(): File {
        val package_ = prepareLocal()
        return package_.writeTo(outputDir)
    }

    /**
     * Prepare everything for remote distribution.
     *
     * Creates:
     * - {name}-{version}.xcframework.zip
     * - Package.swift (pointing to the remote URL)
     *
     * @return The Package.swift file
     */
    fun writeRemotePackage(): File {
        val (package_, _) = prepareRemote()
        return package_.writeTo(outputDir)
    }
}
