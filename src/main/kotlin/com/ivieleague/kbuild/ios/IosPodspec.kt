package com.ivieleague.kbuild.ios

import java.io.File

/**
 * Generates a CocoaPods .podspec file for integrating Kotlin/Native frameworks with Xcode.
 *
 * CocoaPods is the most common dependency manager for iOS projects. The podspec file
 * describes how to integrate the Kotlin framework, including:
 * - Framework location and name
 * - iOS deployment target
 * - Build script phases for compiling the framework
 *
 * Example usage:
 * ```
 * val podspec = IosPodspec(
 *     name = "MyFramework",
 *     version = "1.0.0",
 *     frameworkPath = "build/frameworks/MyFramework.framework",
 *     iosDeploymentTarget = "14.0"
 * )
 * podspec.writeTo(projectRoot)
 * ```
 */
class IosPodspec(
    val name: String,
    val version: String = "1.0.0",
    val summary: String = "Kotlin Multiplatform shared module",
    val homepage: String = "https://github.com/example/$name",
    val authors: String = "",
    val license: String = "MIT",
    val iosDeploymentTarget: String = "14.0",
    val frameworkPath: String = "build/cocoapods/framework/$name.framework",
    val libraries: List<String> = listOf("c++"),
    val vendoredFrameworks: List<String> = emptyList(),
    val dependencies: List<PodDependency> = emptyList(),
    val buildScriptEnabled: Boolean = true,
    val buildCommand: String? = null // Custom build command, or null for default
) {
    /**
     * A CocoaPods dependency.
     */
    data class PodDependency(
        val name: String,
        val version: String? = null
    )

    /**
     * Generate the podspec content.
     */
    fun generate(): String = buildString {
        appendLine("Pod::Spec.new do |spec|")
        appendLine("    spec.name                     = '$name'")
        appendLine("    spec.version                  = '$version'")
        appendLine("    spec.homepage                 = '$homepage'")
        appendLine("    spec.source                   = { :git => \"Not Published\", :tag => \"Cocoapods/#{spec.name}/#{spec.version}\" }")
        appendLine("    spec.authors                  = '$authors'")
        appendLine("    spec.license                  = '$license'")
        appendLine("    spec.summary                  = '$summary'")
        appendLine("    spec.vendored_frameworks      = '$frameworkPath'")

        if (vendoredFrameworks.isNotEmpty()) {
            appendLine("    spec.vendored_frameworks      = [${vendoredFrameworks.joinToString(", ") { "'$it'" }}]")
        }

        if (libraries.isNotEmpty()) {
            appendLine("    spec.libraries                = ${libraries.joinToString(", ") { "'$it'" }}")
        }

        appendLine("    spec.ios.deployment_target    = '$iosDeploymentTarget'")
        appendLine()

        // Framework existence check
        appendLine("    if !Dir.exist?('$frameworkPath') || Dir.empty?('$frameworkPath')")
        appendLine("        raise \"")
        appendLine()
        appendLine("        Kotlin framework '$name' doesn't exist yet, so a proper Xcode project can't be generated.")
        appendLine("        Run the build command to generate the framework first.")
        appendLine("        \"")
        appendLine("    end")
        appendLine()

        // Pod target xcconfig
        appendLine("    spec.pod_target_xcconfig = {")
        appendLine("        'KOTLIN_PROJECT_PATH' => ':$name',")
        appendLine("        'PRODUCT_MODULE_NAME' => '$name',")
        appendLine("    }")
        appendLine()

        // Dependencies
        for (dep in dependencies) {
            if (dep.version != null) {
                appendLine("    spec.dependency '${dep.name}', '${dep.version}'")
            } else {
                appendLine("    spec.dependency '${dep.name}'")
            }
        }

        // Build script phase
        if (buildScriptEnabled) {
            val script = buildCommand ?: defaultBuildScript()
            appendLine()
            appendLine("    spec.script_phases = [")
            appendLine("        {")
            appendLine("            :name => 'Build $name',")
            appendLine("            :execution_position => :before_compile,")
            appendLine("            :shell_path => '/bin/sh',")
            appendLine("            :script => <<-SCRIPT")
            appendLine(script.prependIndent("                "))
            appendLine("            SCRIPT")
            appendLine("        }")
            appendLine("    ]")
        }

        appendLine()
        appendLine("end")
    }

    private fun defaultBuildScript(): String = """
if [ "YES" = "${'$'}OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "Skipping Kotlin build due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED"
  exit 0
fi
set -ev
REPO_ROOT="${'$'}PODS_TARGET_SRCROOT"
# Build the framework for the current platform and architecture
# This will be customized based on the build system
echo "Building $name for ${'$'}PLATFORM_NAME (${'$'}ARCHS)"
""".trimIndent()

    /**
     * Write the podspec to a file.
     *
     * @param projectRoot The project root directory
     * @return The written podspec file
     */
    fun writeTo(projectRoot: File): File {
        val podspecFile = projectRoot.resolve("$name.podspec")
        podspecFile.writeText(generate())
        return podspecFile
    }

    companion object {
        /**
         * Create a podspec for a KMP project that uses kbuild for compilation.
         *
         * @param name The framework name
         * @param version The version string
         * @param iosDeploymentTarget Minimum iOS version
         * @param kbuildScript Path to the kbuild script or command
         */
        fun forKbuild(
            name: String,
            version: String = "1.0.0",
            iosDeploymentTarget: String = "14.0",
            kbuildScript: String = "./kbuild"
        ) = IosPodspec(
            name = name,
            version = version,
            iosDeploymentTarget = iosDeploymentTarget,
            buildCommand = """
if [ "YES" = "${'$'}OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "Skipping Kotlin build due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED"
  exit 0
fi
set -ev
REPO_ROOT="${'$'}PODS_TARGET_SRCROOT"
cd "${'$'}REPO_ROOT"

# Determine the target based on platform and architecture
case "${'$'}PLATFORM_NAME" in
  iphoneos)
    TARGET="iosArm64"
    ;;
  iphonesimulator)
    if [[ "${'$'}ARCHS" == *"arm64"* ]]; then
      TARGET="iosSimulatorArm64"
    else
      TARGET="iosX64"
    fi
    ;;
  *)
    echo "Unknown platform: ${'$'}PLATFORM_NAME"
    exit 1
    ;;
esac

echo "Building $name for ${'$'}TARGET"
$kbuildScript buildFramework --target ${'$'}TARGET
""".trimIndent()
        )
    }
}

/**
 * Generates a Podfile for an iOS project that uses a Kotlin framework.
 */
class IosPodfile(
    val platformVersion: String = "14.0",
    val projectName: String,
    val targetName: String = projectName,
    val pods: List<PodReference> = emptyList(),
    val localPods: List<LocalPodReference> = emptyList()
) {
    /**
     * Reference to a remote pod.
     */
    data class PodReference(
        val name: String,
        val version: String? = null
    )

    /**
     * Reference to a local pod (typically the Kotlin framework).
     */
    data class LocalPodReference(
        val name: String,
        val path: String
    )

    fun generate(): String = buildString {
        appendLine("platform :ios, '$platformVersion'")
        appendLine()
        appendLine("target '$targetName' do")
        appendLine("  use_frameworks!")
        appendLine()

        // Local pods (Kotlin framework)
        for (pod in localPods) {
            appendLine("  pod '${pod.name}', :path => '${pod.path}'")
        }

        // Remote pods
        for (pod in pods) {
            if (pod.version != null) {
                appendLine("  pod '${pod.name}', '${pod.version}'")
            } else {
                appendLine("  pod '${pod.name}'")
            }
        }

        appendLine("end")
    }

    fun writeTo(directory: File): File {
        val podfile = directory.resolve("Podfile")
        podfile.writeText(generate())
        return podfile
    }

    companion object {
        /**
         * Create a simple Podfile for a project with a Kotlin framework.
         */
        fun forKotlinFramework(
            projectName: String,
            frameworkName: String,
            frameworkPath: String = "..",
            platformVersion: String = "14.0"
        ) = IosPodfile(
            platformVersion = platformVersion,
            projectName = projectName,
            localPods = listOf(LocalPodReference(frameworkName, frameworkPath))
        )
    }
}
