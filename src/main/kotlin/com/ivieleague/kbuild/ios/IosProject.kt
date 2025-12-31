package com.ivieleague.kbuild.ios

import com.ivieleague.kbuild.kmp.KmpProjectConfig
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.kmp.kmpBuildFrameworkBlocking
import java.io.File

/**
 * Orchestrates iOS project building from a Kotlin Multiplatform project.
 *
 * This class provides a complete iOS integration workflow:
 * 1. Build Kotlin/Native frameworks for iOS targets
 * 2. Generate XCFramework for distribution
 * 3. Generate Swift Package Manager integration (recommended)
 * 4. Generate CocoaPods integration files (legacy, deprecated)
 * 5. Scaffold an iOS Xcode project (optional)
 *
 * Example usage:
 * ```
 * val kmpConfig = kmpConfig("MyApp", projectRoot) {
 *     jvm()
 *     iosArm64()
 *     iosSimulatorArm64()
 * }
 *
 * val iosProject = IosProject(kmpConfig)
 *
 * // Build frameworks and generate SPM package
 * iosProject.build()
 *
 * // Create a complete iOS project structure
 * iosProject.scaffold()
 * ```
 */
class IosProject(
    val kmpConfig: KmpProjectConfig,
    val iosDeploymentTarget: String = "14.0",
    val frameworkName: String = kmpConfig.name,
    val iosProjectDir: File = kmpConfig.projectRoot.resolve("ios")
) {
    val buildDir: File = kmpConfig.buildDir
    val frameworksDir: File = buildDir.resolve("frameworks")
    val xcframeworksDir: File = buildDir.resolve("xcframeworks")
    val cocoapodsDir: File = buildDir.resolve("cocoapods")

    /**
     * iOS targets enabled in the KMP project.
     */
    val iosTargets: Set<KmpTarget.Native> = kmpConfig.targets
        .filterIsInstance<KmpTarget.Native>()
        .filter { it.isIosTarget() }
        .toSet()

    /**
     * Build configuration for iOS targets.
     */
    data class BuildConfig(
        val staticFramework: Boolean = false,
        val debug: Boolean = true,
        val optimizations: Boolean = false
    )

    /**
     * iOS integration mode.
     */
    enum class IntegrationMode {
        /** Swift Package Manager (recommended) */
        SPM,
        /** CocoaPods (legacy, deprecated) */
        COCOAPODS
    }

    /**
     * Build the iOS frameworks for all enabled targets.
     *
     * @param config Build configuration
     * @return Map of target to framework directory
     */
    fun buildFrameworks(config: BuildConfig = BuildConfig()): Map<KmpTarget.Native, File> {
        require(iosTargets.isNotEmpty()) {
            "No iOS targets found in project. Enable at least one iOS target " +
                "(iosArm64, iosSimulatorArm64, iosX64)."
        }

        val results = mutableMapOf<KmpTarget.Native, File>()

        for (target in iosTargets) {
            println("Building framework for ${target.name}...")
            val framework = kmpBuildFrameworkBlocking(kmpConfig, target, static = config.staticFramework)
            results[target] = framework
            println("  Created: $framework")
        }

        return results
    }

    /**
     * Build an XCFramework from the iOS targets.
     *
     * @param config Build configuration
     * @return The XCFramework directory
     */
    fun buildXCFramework(config: BuildConfig = BuildConfig()): File {
        require(XCFramework.isAvailable()) {
            "xcodebuild is not available. XCFramework creation requires Xcode on macOS."
        }

        val frameworks = buildFrameworks(config)

        val platformFrameworks = mutableMapOf<XCFramework.Platform, File>()

        // Map targets to platforms
        for ((target, framework) in frameworks) {
            val platform = when (target) {
                KmpTarget.Native.IosArm64 -> XCFramework.Platform.IOS_DEVICE
                KmpTarget.Native.IosSimulatorArm64,
                KmpTarget.Native.IosX64 -> XCFramework.Platform.IOS_SIMULATOR
                else -> continue
            }

            // If we already have a simulator framework and this is also a simulator,
            // we'd need to lipo them together. For now, prefer arm64 simulator.
            if (platform == XCFramework.Platform.IOS_SIMULATOR &&
                platformFrameworks.containsKey(platform)) {
                // Keep arm64 simulator if we already have it
                if (target != KmpTarget.Native.IosSimulatorArm64) continue
            }

            platformFrameworks[platform] = framework
        }

        val xcframework = XCFramework(frameworkName, xcframeworksDir)
        return xcframework.build(platformFrameworks)
    }

    // ============== Swift Package Manager (Recommended) ==============

    /**
     * Generate a Swift Package for local development.
     *
     * The generated Package.swift points to the local XCFramework, making it easy
     * to integrate with Xcode during development.
     *
     * @param macosVersion Optional minimum macOS version (if macOS is supported)
     * @return The generated Package.swift file
     */
    fun generateSwiftPackage(macosVersion: String? = null): File {
        val swiftPackage = SwiftPackage.forKotlinFramework(
            name = frameworkName,
            xcframeworkPath = "$frameworkName.xcframework",
            iosVersion = iosDeploymentTarget,
            macosVersion = macosVersion
        )
        return swiftPackage.writeTo(xcframeworksDir)
    }

    /**
     * Prepare the framework for remote distribution via Swift Package Manager.
     *
     * Creates:
     * - {name}-{version}.xcframework.zip - The zipped XCFramework for download
     * - Package.swift - Points to the remote URL
     *
     * @param version Version string for the release
     * @param baseUrl Base URL where the zip will be hosted (e.g., "https://github.com/user/repo/releases/download/v1.0.0")
     * @return Pair of (Package.swift file, checksum)
     */
    fun prepareForDistribution(
        version: String,
        baseUrl: String
    ): Pair<File, String> {
        val xcframework = xcframeworksDir.resolve("$frameworkName.xcframework")
        require(xcframework.exists()) {
            "XCFramework not found at $xcframework. Run buildXCFramework() first."
        }

        val distribution = SwiftPackageDistribution(
            name = frameworkName,
            version = version,
            xcframework = xcframework,
            outputDir = buildDir.resolve("spm-distribution"),
            baseUrl = baseUrl,
            iosVersion = iosDeploymentTarget
        )

        val (swiftPackage, checksum) = distribution.prepareRemote()
        val packageFile = swiftPackage.writeTo(distribution.outputDir)

        println("Prepared for distribution:")
        println("  XCFramework zip: ${distribution.outputDir.resolve("$frameworkName-$version.xcframework.zip")}")
        println("  Package.swift: $packageFile")
        println("  Checksum: $checksum")

        return Pair(packageFile, checksum)
    }

    // ============== CocoaPods (Legacy/Deprecated) ==============

    /**
     * Generate the CocoaPods podspec file.
     *
     * @param version Version string for the podspec
     * @return The generated podspec file
     * @deprecated CocoaPods is deprecated. Use [generateSwiftPackage] instead.
     */
    @Deprecated("CocoaPods is deprecated. Use generateSwiftPackage() for Swift Package Manager integration.")
    fun generatePodspec(version: String = "1.0.0"): File {
        cocoapodsDir.mkdirs()

        // Create a dummy framework directory if it doesn't exist
        // This is needed for pod install to work before the first build
        val dummyFramework = cocoapodsDir.resolve("framework/$frameworkName.framework")
        if (!dummyFramework.exists()) {
            dummyFramework.mkdirs()
            dummyFramework.resolve("Info.plist").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleIdentifier</key>
                    <string>com.example.$frameworkName</string>
                    <key>CFBundleName</key>
                    <string>$frameworkName</string>
                    <key>CFBundleVersion</key>
                    <string>$version</string>
                </dict>
                </plist>
            """.trimIndent())
        }

        val podspec = IosPodspec(
            name = frameworkName,
            version = version,
            iosDeploymentTarget = iosDeploymentTarget,
            frameworkPath = "build/cocoapods/framework/$frameworkName.framework",
            buildScriptEnabled = true,
            buildCommand = generateBuildScript()
        )

        return podspec.writeTo(kmpConfig.projectRoot)
    }

    private fun generateBuildScript(): String = """
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

echo "Building $frameworkName for ${'$'}TARGET"

# Build using kbuild
# TODO: Replace with actual kbuild command when CLI is implemented
# ./kbuild buildFramework --target ${'$'}TARGET --output build/cocoapods/framework
""".trimIndent()

    /**
     * Generate the iOS project Podfile.
     *
     * @param appName Name of the iOS app target
     * @return The generated Podfile
     * @deprecated CocoaPods is deprecated. Use Swift Package Manager instead.
     */
    @Deprecated("CocoaPods is deprecated. Use Swift Package Manager instead.")
    fun generatePodfile(appName: String = "App"): File {
        iosProjectDir.mkdirs()

        val podfile = IosPodfile(
            platformVersion = iosDeploymentTarget,
            projectName = appName,
            localPods = listOf(
                IosPodfile.LocalPodReference(frameworkName, "..")
            )
        )

        return podfile.writeTo(iosProjectDir)
    }

    /**
     * Scaffold a complete iOS project structure.
     *
     * Creates:
     * - ios/App/ - Xcode project directory
     * - ios/App/AppDelegate.swift
     * - ios/App/ViewController.swift
     * - ios/App/Info.plist
     * - build/xcframeworks/Package.swift (for SPM integration)
     *
     * For SPM integration (default), add the package to Xcode:
     * File > Add Package Dependencies > Add Local > select build/xcframeworks
     *
     * @param appName Name of the iOS app
     * @param bundleId Bundle identifier (e.g., "com.example.myapp")
     * @param integrationMode How to integrate with Xcode (SPM recommended)
     */
    fun scaffold(
        appName: String = "App",
        bundleId: String = "com.example.${kmpConfig.name.lowercase()}",
        integrationMode: IntegrationMode = IntegrationMode.SPM
    ) {
        require(iosTargets.isNotEmpty()) {
            "No iOS targets found in project. Enable at least one iOS target first."
        }

        println("Scaffolding iOS project...")

        // Create directory structure
        val appDir = iosProjectDir.resolve(appName)
        appDir.mkdirs()

        // Generate AppDelegate.swift
        appDir.resolve("AppDelegate.swift").writeText("""
            import UIKit
            import $frameworkName

            @main
            class AppDelegate: UIResponder, UIApplicationDelegate {
                var window: UIWindow?

                func application(
                    _ application: UIApplication,
                    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
                ) -> Bool {
                    window = UIWindow(frame: UIScreen.main.bounds)
                    window?.rootViewController = ViewController()
                    window?.makeKeyAndVisible()
                    return true
                }
            }
        """.trimIndent())

        // Generate ViewController.swift
        appDir.resolve("ViewController.swift").writeText("""
            import UIKit
            import $frameworkName

            class ViewController: UIViewController {
                override func viewDidLoad() {
                    super.viewDidLoad()
                    view.backgroundColor = .white

                    // Initialize your Kotlin code here
                    // Example: ${frameworkName}Kt.initialize()

                    let label = UILabel()
                    label.text = "Hello from Kotlin!"
                    label.textAlignment = .center
                    label.translatesAutoresizingMaskIntoConstraints = false
                    view.addSubview(label)

                    NSLayoutConstraint.activate([
                        label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                        label.centerYAnchor.constraint(equalTo: view.centerYAnchor)
                    ])
                }
            }
        """.trimIndent())

        // Generate Info.plist
        appDir.resolve("Info.plist").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>$bundleId</string>
                <key>CFBundleName</key>
                <string>$appName</string>
                <key>CFBundleDisplayName</key>
                <string>$appName</string>
                <key>CFBundleVersion</key>
                <string>1</string>
                <key>CFBundleShortVersionString</key>
                <string>1.0</string>
                <key>CFBundlePackageType</key>
                <string>APPL</string>
                <key>CFBundleExecutable</key>
                <string>${'$'}(EXECUTABLE_NAME)</string>
                <key>UILaunchStoryboardName</key>
                <string>LaunchScreen</string>
                <key>UISupportedInterfaceOrientations</key>
                <array>
                    <string>UIInterfaceOrientationPortrait</string>
                    <string>UIInterfaceOrientationLandscapeLeft</string>
                    <string>UIInterfaceOrientationLandscapeRight</string>
                </array>
                <key>UIRequiredDeviceCapabilities</key>
                <array>
                    <string>arm64</string>
                </array>
            </dict>
            </plist>
        """.trimIndent())

        // Generate LaunchScreen.storyboard
        appDir.resolve("LaunchScreen.storyboard").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <document type="com.apple.InterfaceBuilder3.CocoaTouch.Storyboard.XIB" version="3.0" toolsVersion="21701">
                <scenes>
                    <scene sceneID="EHf-IW-A2E">
                        <viewController id="01J-lp-oVM" sceneMemberID="viewController">
                            <view key="view" contentMode="scaleToFill" id="Ze5-6b-2t3">
                                <rect key="frame" x="0.0" y="0.0" width="393" height="852"/>
                                <autoresizingMask key="autoresizingMask" widthSizable="YES" heightSizable="YES"/>
                                <color key="backgroundColor" systemColor="systemBackgroundColor"/>
                            </view>
                        </viewController>
                        <placeholder placeholderIdentifier="IBFirstResponder" id="iYj-Kq-Ea1" userLabel="First Responder" sceneMemberID="firstResponder"/>
                    </scene>
                </scenes>
            </document>
        """.trimIndent())

        // Generate asset catalog with placeholder app icon
        val assetsDir = appDir.resolve("Assets.xcassets")
        IosAssetCatalog.generate(assetsDir, iconColor = "#007AFF")
        IosAssetCatalog.generateAccentColor(assetsDir, "#007AFF")
        println("  Generated asset catalog: $assetsDir")

        // Generate integration files based on mode
        when (integrationMode) {
            IntegrationMode.SPM -> {
                // Create XCFramework directory and Package.swift
                xcframeworksDir.mkdirs()
                generateSwiftPackage()

                println("iOS project scaffolded at: $iosProjectDir")
                println()
                println("Next steps:")
                println("  1. Build the XCFramework: iosProject.buildXCFramework()")
                println("  2. Open your iOS project in Xcode")
                println("  3. File > Add Package Dependencies > Add Local")
                println("  4. Select: ${xcframeworksDir.absolutePath}")
                println("  5. Add '$frameworkName' to your target")
            }
            IntegrationMode.COCOAPODS -> {
                @Suppress("DEPRECATION")
                generatePodfile(appName)
                @Suppress("DEPRECATION")
                generatePodspec()

                println("iOS project scaffolded at: $iosProjectDir")
                println()
                println("Next steps (CocoaPods - deprecated):")
                println("  1. cd ${iosProjectDir.name}")
                println("  2. pod install")
                println("  3. open $appName.xcworkspace")
            }
        }
    }

    // ============== Xcode Project Generation ==============

    /**
     * Generate an Xcode project for the iOS app.
     *
     * Creates a .xcodeproj file that can be opened in Xcode and built with xcodebuild.
     * The project will be configured to link against the Kotlin XCFramework via SPM.
     *
     * @param appName Name of the iOS app
     * @param bundleId Bundle identifier
     * @return The generated .xcodeproj directory
     */
    fun generateXcodeProject(
        appName: String = "App",
        bundleId: String = "com.example.${kmpConfig.name.lowercase()}",
        autoSign: Boolean = true
    ): File {
        val appDir = iosProjectDir.resolve(appName)
        require(appDir.exists()) {
            "App directory not found at $appDir. Run scaffold() first."
        }

        var project = XcodeProject.forApp(
            name = appName,
            bundleId = bundleId,
            appDir = appDir,
            deploymentTarget = iosDeploymentTarget
        )

        // Add asset catalog if it exists
        val assetsDir = appDir.resolve("Assets.xcassets")
        if (assetsDir.exists()) {
            project = project.addAssetCatalog("$appName/Assets.xcassets")
        }

        // Auto-configure code signing if requested and available
        if (autoSign && IosCodeSigning.isAvailable()) {
            try {
                project = project.autoConfigureCodeSigning()
                println("  Code signing configured: ${project.getDevelopmentTeam()}")
            } catch (e: Exception) {
                println("  Warning: Could not configure code signing: ${e.message}")
            }
        }

        return project.writeTo(iosProjectDir)
    }

    // ============== Swift/Xcode Compilation ==============

    /**
     * Build the iOS app using xcodebuild.
     *
     * Requires:
     * - macOS with Xcode installed
     * - scaffold() and generateXcodeProject() to have been called
     * - XCFramework to be built (buildXCFramework())
     *
     * @param appName Name of the iOS app (scheme name)
     * @param configuration Debug or Release
     * @param simulator Simulator configuration for build destination
     * @return Build result with success status and output paths
     */
    fun buildApp(
        appName: String = "App",
        configuration: String = "Debug",
        simulator: SwiftCompiler.SimulatorDestination = SwiftCompiler.SimulatorDestination()
    ): SwiftCompiler.BuildResult {
        require(SwiftCompiler.isMacOS()) {
            "Building iOS apps requires macOS"
        }
        require(SwiftCompiler.hasXcodebuild()) {
            "xcodebuild not found. Install Xcode from the App Store."
        }

        val xcodeproj = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            throw IllegalStateException(
                "Xcode project not found in $iosProjectDir. " +
                "Run scaffold() and generateXcodeProject() first."
            )
        }

        println("Building iOS app: $appName")
        println("  Project: $xcodeproj")
        println("  Configuration: $configuration")
        println("  Destination: ${simulator.toDestinationString()}")

        return SwiftCompiler.buildWithXcode(
            projectDir = iosProjectDir,
            scheme = appName,
            configuration = configuration,
            destination = simulator,
            derivedDataPath = buildDir.resolve("DerivedData")
        )
    }

    /**
     * Build the iOS app for a physical device.
     *
     * @param appName Name of the iOS app
     * @param configuration Debug or Release (Release recommended for device)
     * @return Build result
     */
    fun buildAppForDevice(
        appName: String = "App",
        configuration: String = "Release"
    ): SwiftCompiler.BuildResult {
        require(SwiftCompiler.isMacOS()) {
            "Building iOS apps requires macOS"
        }
        require(SwiftCompiler.hasXcodebuild()) {
            "xcodebuild not found. Install Xcode from the App Store."
        }

        val xcodeproj = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            throw IllegalStateException(
                "Xcode project not found in $iosProjectDir. " +
                "Run scaffold() and generateXcodeProject() first."
            )
        }

        println("Building iOS app for device: $appName")
        println("  Project: $xcodeproj")
        println("  Configuration: $configuration")

        return SwiftCompiler.buildForDevice(
            projectDir = iosProjectDir,
            scheme = appName,
            configuration = configuration,
            derivedDataPath = buildDir.resolve("DerivedData")
        )
    }

    /**
     * Run the iOS app in the simulator.
     *
     * @param appName Name of the iOS app
     * @param simulatorId Optional specific simulator UDID (uses default if not specified)
     * @return Result of the run operation
     */
    fun runInSimulator(
        appName: String = "App",
        simulatorId: String? = null
    ): SwiftCompiler.BuildResult {
        require(SwiftCompiler.isMacOS()) {
            "iOS Simulator requires macOS"
        }

        // Find the built app
        val derivedData = buildDir.resolve("DerivedData")
        val appPath = findBuiltApp(derivedData, appName)
            ?: throw IllegalStateException(
                "Built app not found. Run buildApp() first."
            )

        println("Running app in simulator: $appPath")

        return SwiftCompiler.runInSimulator(appPath, simulatorId)
    }

    /**
     * Build the XCFramework's Swift Package for testing.
     *
     * This builds the Swift package that wraps the Kotlin XCFramework.
     * Useful for validating that the SPM integration works correctly.
     *
     * @param configuration debug or release
     * @return Build result
     */
    fun buildSwiftPackage(configuration: String = "debug"): SwiftCompiler.BuildResult {
        require(SwiftCompiler.hasSwift()) {
            "swift not found. Install Xcode or Swift toolchain."
        }

        val packageSwift = xcframeworksDir.resolve("Package.swift")
        if (!packageSwift.exists()) {
            throw IllegalStateException(
                "Package.swift not found at $packageSwift. " +
                "Run buildXCFramework() and generateSwiftPackage() first."
            )
        }

        println("Building Swift package: $frameworkName")
        println("  Package: $packageSwift")
        println("  Configuration: $configuration")

        return SwiftCompiler.buildSwiftPackage(
            packageDir = xcframeworksDir,
            configuration = configuration
        )
    }

    /**
     * Clean all build artifacts.
     */
    fun clean() {
        println("Cleaning iOS build artifacts...")

        if (buildDir.exists()) {
            buildDir.deleteRecursively()
            println("  Removed: $buildDir")
        }

        // Clean Xcode derived data if it exists
        val appName = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }
            ?.firstOrNull()
            ?.nameWithoutExtension

        if (appName != null && SwiftCompiler.hasXcodebuild()) {
            try {
                SwiftCompiler.cleanXcode(iosProjectDir, appName)
                println("  Cleaned Xcode project: $appName")
            } catch (e: Exception) {
                // Ignore errors during clean
            }
        }

        println("Clean complete.")
    }

    // ============== Production Build (IPA for Device) ==============

    /**
     * Result of an IPA build operation.
     */
    data class IpaBuildResult(
        val success: Boolean,
        val ipa: File?,
        val archive: File?,
        val teamId: String?,
        val exportMethod: IosCodeSigning.ExportMethod,
        val errorMessage: String? = null
    ) {
        fun printSummary() {
            if (success) {
                println("IPA Build Successful!")
                println("  Archive: $archive")
                println("  IPA: $ipa")
                println("  Team ID: $teamId")
                println("  Export Method: ${exportMethod.value}")
            } else {
                println("IPA Build Failed!")
                println("  Error: $errorMessage")
            }
        }
    }

    /**
     * Build an IPA for distribution or device installation.
     *
     * This performs the full archive → export workflow:
     * 1. Build the app for device (arm64)
     * 2. Archive the app with xcodebuild
     * 3. Export the archive to IPA
     *
     * Requires:
     * - macOS with Xcode
     * - Valid code signing identity (development or distribution)
     * - scaffold() and generateXcodeProject() to have been called
     *
     * @param appName Name of the iOS app (scheme name)
     * @param configuration Build configuration (Release recommended)
     * @param exportMethod How to export (DEVELOPMENT for device testing)
     * @param teamId Optional team ID (auto-detected if not provided)
     * @return IPA build result with paths to archive and IPA
     */
    fun buildIpa(
        appName: String = "App",
        configuration: String = "Release",
        exportMethod: IosCodeSigning.ExportMethod = IosCodeSigning.ExportMethod.DEVELOPMENT,
        teamId: String? = null
    ): IpaBuildResult {
        require(SwiftCompiler.isMacOS()) {
            "Building iOS IPA requires macOS"
        }
        require(SwiftCompiler.hasXcodebuild()) {
            "xcodebuild not found. Install Xcode from the App Store."
        }

        val xcodeproj = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            return IpaBuildResult(
                success = false,
                ipa = null,
                archive = null,
                teamId = null,
                exportMethod = exportMethod,
                errorMessage = "Xcode project not found in $iosProjectDir. Run scaffold() and generateXcodeProject() first."
            )
        }

        // Auto-detect team if not provided
        val resolvedTeamId = teamId ?: run {
            val team = IosCodeSigning.autoSelectTeam()
            if (team == null) {
                return IpaBuildResult(
                    success = false,
                    ipa = null,
                    archive = null,
                    teamId = null,
                    exportMethod = exportMethod,
                    errorMessage = "No code signing identity found. Please install a development certificate from Apple Developer."
                )
            }
            println("Using development team: ${team.name} (${team.id})")
            team.id
        }

        println("Building IPA: $appName")
        println("  Project: $xcodeproj")
        println("  Configuration: $configuration")
        println("  Export Method: ${exportMethod.value}")
        println("  Team ID: $resolvedTeamId")
        println()

        // Step 1: Archive the app
        val archivePath = buildDir.resolve("archives/$appName.xcarchive")
        println("Step 1/2: Archiving app...")

        val archiveResult = SwiftCompiler.archiveApp(
            projectDir = iosProjectDir,
            scheme = appName,
            archivePath = archivePath,
            configuration = configuration
        )

        if (!archiveResult.success) {
            return IpaBuildResult(
                success = false,
                ipa = null,
                archive = null,
                teamId = resolvedTeamId,
                exportMethod = exportMethod,
                errorMessage = "Archive failed: ${archiveResult.output}"
            )
        }
        println("  Archive: $archivePath")

        // Step 2: Export IPA
        println("Step 2/2: Exporting IPA...")

        // Generate export options plist
        val bundleId = readBundleIdFromProject(xcodeproj)
        val exportOptionsPlist = IosCodeSigning.generateExportOptionsPlist(
            outputDir = buildDir.resolve("export"),
            teamId = resolvedTeamId,
            method = exportMethod,
            bundleId = bundleId
        )

        val exportPath = buildDir.resolve("outputs/ipa")
        val exportResult = SwiftCompiler.exportIpa(
            archivePath = archivePath,
            exportPath = exportPath,
            exportOptionsPlist = exportOptionsPlist
        )

        if (!exportResult.success) {
            return IpaBuildResult(
                success = false,
                ipa = null,
                archive = archivePath,
                teamId = resolvedTeamId,
                exportMethod = exportMethod,
                errorMessage = "Export failed: ${exportResult.output}"
            )
        }

        // Find the generated IPA
        val ipa = exportPath.listFiles { f -> f.name.endsWith(".ipa") }?.firstOrNull()
        if (ipa == null) {
            return IpaBuildResult(
                success = false,
                ipa = null,
                archive = archivePath,
                teamId = resolvedTeamId,
                exportMethod = exportMethod,
                errorMessage = "IPA file not found after export"
            )
        }

        println()
        println("BUILD SUCCESSFUL")
        println("IPA: $ipa")

        return IpaBuildResult(
            success = true,
            ipa = ipa,
            archive = archivePath,
            teamId = resolvedTeamId,
            exportMethod = exportMethod
        )
    }

    /**
     * Install an app on a connected physical device.
     *
     * Uses devicectl (Xcode 15+) or ios-deploy as fallback.
     *
     * @param ipa Optional IPA file (uses latest build if not specified)
     * @param deviceId Optional specific device UDID (uses first device if not specified)
     * @return true if installation succeeded
     */
    fun installOnDevice(
        ipa: File? = null,
        deviceId: String? = null
    ): Boolean {
        require(SwiftCompiler.isMacOS()) {
            "Installing on iOS device requires macOS"
        }

        val ipaFile = ipa ?: run {
            val exportPath = buildDir.resolve("outputs/ipa")
            exportPath.listFiles { f -> f.name.endsWith(".ipa") }?.firstOrNull()
                ?: throw IllegalStateException(
                    "No IPA found. Run buildIpa() first or specify an IPA file."
                )
        }

        println("Installing on device: ${ipaFile.name}")

        return SwiftCompiler.installOnDevice(ipaFile, deviceId)
    }

    /**
     * Run the app on a connected physical device.
     *
     * Installs the app if needed, then launches it.
     *
     * @param appName Name of the app
     * @param bundleId Optional bundle ID (auto-detected if not specified)
     * @param deviceId Optional specific device UDID
     * @return true if launch succeeded
     */
    fun runOnDevice(
        appName: String = "App",
        bundleId: String? = null,
        deviceId: String? = null
    ): Boolean {
        require(SwiftCompiler.isMacOS()) {
            "Running on iOS device requires macOS"
        }

        val resolvedBundleId = bundleId ?: run {
            val xcodeproj = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
            if (xcodeproj != null) {
                readBundleIdFromProject(xcodeproj) ?: "com.example.${kmpConfig.name.lowercase()}"
            } else {
                "com.example.${kmpConfig.name.lowercase()}"
            }
        }

        println("Launching on device: $resolvedBundleId")

        return SwiftCompiler.launchOnDevice(resolvedBundleId, deviceId)
    }

    /**
     * List connected physical iOS devices.
     *
     * @return List of connected devices
     */
    fun listDevices(): List<SwiftCompiler.IosDevice> {
        return SwiftCompiler.listConnectedDevices()
    }

    /**
     * List all iOS devices (simulators and physical).
     *
     * @return List of all devices
     */
    fun listAllDevices(): List<SwiftCompiler.IosDevice> {
        return SwiftCompiler.listAllDevices()
    }

    /**
     * Complete build-and-run workflow.
     *
     * For simulator:
     * 1. Build XCFramework
     * 2. Generate Xcode project
     * 3. Build for simulator
     * 4. Run in simulator
     *
     * For device:
     * 1. Build XCFramework
     * 2. Generate Xcode project
     * 3. Build IPA
     * 4. Install on device
     * 5. Launch app
     *
     * @param appName Name of the iOS app
     * @param bundleId Bundle identifier
     * @param simulator If true, build for simulator; if false, build for device
     * @return true if the entire workflow succeeded
     */
    fun buildAndRun(
        appName: String = "App",
        bundleId: String = "com.example.${kmpConfig.name.lowercase()}",
        simulator: Boolean = true
    ): Boolean {
        println("Building and running iOS app: $appName")
        println("  Target: ${if (simulator) "Simulator" else "Physical Device"}")
        println()

        // Build XCFramework
        try {
            buildXCFramework()
        } catch (e: Exception) {
            println("Failed to build XCFramework: ${e.message}")
            return false
        }

        // Generate Xcode project if needed
        val xcodeproj = iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            val appDir = iosProjectDir.resolve(appName)
            if (!appDir.exists()) {
                scaffold(appName, bundleId)
            }
            generateXcodeProject(appName, bundleId)
        }

        return if (simulator) {
            // Build and run in simulator
            val buildResult = buildApp(appName)
            if (!buildResult.success) {
                println("Build failed: ${buildResult.output}")
                return false
            }
            val runResult = runInSimulator(appName)
            runResult.success
        } else {
            // Build IPA and run on device
            val ipaResult = buildIpa(appName)
            if (!ipaResult.success) {
                println("IPA build failed: ${ipaResult.errorMessage}")
                return false
            }

            if (!installOnDevice(ipaResult.ipa)) {
                println("Installation failed")
                return false
            }

            runOnDevice(appName, bundleId)
        }
    }

    private fun readBundleIdFromProject(xcodeproj: File): String? {
        // Try to read bundle ID from the project.pbxproj
        val pbxproj = xcodeproj.resolve("project.pbxproj")
        if (!pbxproj.exists()) return null

        val content = pbxproj.readText()
        val bundleIdMatch = Regex("""PRODUCT_BUNDLE_IDENTIFIER\s*=\s*"?([^";]+)"?;""")
            .find(content)
        return bundleIdMatch?.groupValues?.get(1)
    }

    // ============== TestFlight / App Store Upload ==============

    /**
     * Result of an App Store upload operation.
     */
    data class TestFlightUploadResult(
        val success: Boolean,
        val ipa: File?,
        val requestId: String?,
        val message: String
    ) {
        fun printSummary() {
            if (success) {
                println("TestFlight Upload Successful!")
                println("  IPA: $ipa")
                if (requestId != null) {
                    println("  Request ID: $requestId")
                }
                println()
                println("Your build is now processing. You can track its status in App Store Connect.")
                println("Once processing is complete, it will be available in TestFlight.")
            } else {
                println("TestFlight Upload Failed!")
                println("  Error: $message")
            }
        }
    }

    /**
     * Build and upload to TestFlight in one step.
     *
     * This method:
     * 1. Builds an IPA with app-store export method
     * 2. Validates the IPA against App Store requirements
     * 3. Uploads to App Store Connect
     *
     * The build will appear in TestFlight once Apple's processing is complete
     * (usually 10-30 minutes).
     *
     * @param appName Name of the iOS app (scheme name)
     * @param auth App Store Connect authentication
     * @param teamId Optional team ID (auto-detected if not provided)
     * @return Upload result
     */
    fun uploadToTestFlight(
        appName: String = "App",
        auth: SwiftCompiler.AppStoreConnectAuth,
        teamId: String? = null
    ): TestFlightUploadResult {
        require(SwiftCompiler.isMacOS()) {
            "TestFlight upload requires macOS"
        }
        require(SwiftCompiler.hasAltool()) {
            "xcrun altool not found. Install Xcode."
        }

        println("Building for TestFlight...")
        println()

        // Build IPA with app-store export method
        val ipaResult = buildIpa(
            appName = appName,
            configuration = "Release",
            exportMethod = IosCodeSigning.ExportMethod.APP_STORE,
            teamId = teamId
        )

        if (!ipaResult.success) {
            return TestFlightUploadResult(
                success = false,
                ipa = null,
                requestId = null,
                message = "Build failed: ${ipaResult.errorMessage}"
            )
        }

        val ipa = ipaResult.ipa
            ?: return TestFlightUploadResult(
                success = false,
                ipa = null,
                requestId = null,
                message = "IPA file not found after build"
            )

        println()
        println("Uploading to TestFlight...")

        // Upload to App Store Connect
        val uploadResult = SwiftCompiler.uploadToAppStoreConnect(
            ipaPath = ipa,
            auth = auth,
            validate = true
        )

        return if (uploadResult.success) {
            TestFlightUploadResult(
                success = true,
                ipa = ipa,
                requestId = uploadResult.requestId,
                message = "Upload successful"
            )
        } else {
            TestFlightUploadResult(
                success = false,
                ipa = ipa,
                requestId = null,
                message = uploadResult.errorOutput.ifEmpty { uploadResult.output }
            )
        }
    }

    /**
     * Upload an existing IPA to TestFlight.
     *
     * Use this if you've already built an IPA with the app-store export method
     * and want to upload it.
     *
     * @param ipa Path to the IPA file
     * @param auth App Store Connect authentication
     * @param validate Whether to validate before uploading (recommended)
     * @return Upload result
     */
    fun uploadIpaToTestFlight(
        ipa: File,
        auth: SwiftCompiler.AppStoreConnectAuth,
        validate: Boolean = true
    ): TestFlightUploadResult {
        require(SwiftCompiler.isMacOS()) {
            "TestFlight upload requires macOS"
        }
        require(ipa.exists()) {
            "IPA not found at $ipa"
        }

        println("Uploading ${ipa.name} to TestFlight...")

        val uploadResult = SwiftCompiler.uploadToAppStoreConnect(
            ipaPath = ipa,
            auth = auth,
            validate = validate
        )

        return if (uploadResult.success) {
            TestFlightUploadResult(
                success = true,
                ipa = ipa,
                requestId = uploadResult.requestId,
                message = "Upload successful"
            )
        } else {
            TestFlightUploadResult(
                success = false,
                ipa = ipa,
                requestId = null,
                message = uploadResult.errorOutput.ifEmpty { uploadResult.output }
            )
        }
    }

    /**
     * Validate an IPA against App Store requirements without uploading.
     *
     * @param ipa Path to the IPA file
     * @param auth App Store Connect authentication
     * @return Validation result
     */
    fun validateForAppStore(
        ipa: File,
        auth: SwiftCompiler.AppStoreConnectAuth
    ): Boolean {
        require(SwiftCompiler.isMacOS()) {
            "App Store validation requires macOS"
        }
        require(ipa.exists()) {
            "IPA not found at $ipa"
        }

        println("Validating ${ipa.name}...")

        val result = SwiftCompiler.validateForAppStore(ipa, auth)

        if (result.success) {
            println("Validation passed!")
        } else {
            println("Validation failed:")
            println(result.errorOutput.ifEmpty { result.output })
        }

        return result.success
    }

    /**
     * List available iOS simulators.
     */
    fun listSimulators(): List<SwiftCompiler.Simulator> {
        return SwiftCompiler.listSimulators()
    }

    private fun findBuiltApp(derivedDataPath: File, appName: String): File? {
        val productsDir = derivedDataPath.resolve("Build/Products")
        if (!productsDir.exists()) return null

        // Look in all configuration directories
        val configDirs = productsDir.listFiles { f -> f.isDirectory } ?: return null

        for (configDir in configDirs) {
            val app = configDir.resolve("$appName.app")
            if (app.exists()) return app
        }

        return null
    }

    /**
     * Build everything needed for iOS distribution.
     *
     * @param config Build configuration
     * @param integrationMode How to integrate with Xcode (SPM recommended)
     * @return Map containing paths to built artifacts
     */
    fun build(
        config: BuildConfig = BuildConfig(),
        integrationMode: IntegrationMode = IntegrationMode.SPM
    ): BuildResult {
        println("Building iOS project: ${kmpConfig.name}")
        println("iOS Deployment Target: $iosDeploymentTarget")
        println("Integration: $integrationMode")
        println("Targets: ${iosTargets.joinToString { it.name }}")
        println()

        // Build frameworks
        val frameworks = buildFrameworks(config)

        // Build XCFramework if xcodebuild is available
        val xcframework = if (XCFramework.isAvailable()) {
            try {
                buildXCFramework(config)
            } catch (e: Exception) {
                println("Warning: Could not create XCFramework: ${e.message}")
                null
            }
        } else {
            println("Note: xcodebuild not available, skipping XCFramework creation")
            null
        }

        // Generate integration files
        val packageSwift: File?
        val podspec: File?

        when (integrationMode) {
            IntegrationMode.SPM -> {
                packageSwift = if (xcframework != null) {
                    generateSwiftPackage()
                } else null
                podspec = null
            }
            IntegrationMode.COCOAPODS -> {
                packageSwift = null
                @Suppress("DEPRECATION")
                podspec = generatePodspec()
            }
        }

        return BuildResult(
            frameworks = frameworks,
            xcframework = xcframework,
            packageSwift = packageSwift,
            podspec = podspec,
            integrationMode = integrationMode
        )
    }

    /**
     * Result of an iOS build.
     */
    data class BuildResult(
        val frameworks: Map<KmpTarget.Native, File>,
        val xcframework: File?,
        val packageSwift: File?,
        val podspec: File?,
        val integrationMode: IntegrationMode
    ) {
        fun printSummary() {
            println("iOS Build Complete!")
            println()
            println("Frameworks:")
            frameworks.forEach { (target, path) ->
                println("  ${target.name}: $path")
            }
            if (xcframework != null) {
                println()
                println("XCFramework: $xcframework")
            }
            println()
            when (integrationMode) {
                IntegrationMode.SPM -> {
                    if (packageSwift != null) {
                        println("Swift Package: $packageSwift")
                        println()
                        println("To use in Xcode:")
                        println("  File > Add Package Dependencies > Add Local")
                        println("  Select the directory containing Package.swift")
                    }
                }
                IntegrationMode.COCOAPODS -> {
                    if (podspec != null) {
                        println("Podspec: $podspec")
                        println()
                        println("To use with CocoaPods (deprecated):")
                        println("  pod install")
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Quick helper to check if this is a Mac (required for iOS development).
         */
        fun isMacOS(): Boolean {
            return System.getProperty("os.name").lowercase().contains("mac")
        }

        /**
         * Check if Xcode command line tools are available.
         */
        fun hasXcodeTools(): Boolean {
            return try {
                val process = ProcessBuilder("xcode-select", "-p")
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * DSL for creating an iOS project from a KMP project.
 */
fun KmpProjectConfig.iosProject(
    iosDeploymentTarget: String = "14.0",
    frameworkName: String = this.name,
    iosProjectDir: File = this.projectRoot.resolve("ios")
): IosProject = IosProject(
    kmpConfig = this,
    iosDeploymentTarget = iosDeploymentTarget,
    frameworkName = frameworkName,
    iosProjectDir = iosProjectDir
)

/**
 * Check if a native target is an iOS target.
 */
fun KmpTarget.Native.isIosTarget(): Boolean = when (this.name) {
    "iosArm64", "iosSimulatorArm64", "iosX64" -> true
    else -> false
}

/**
 * Check if a native target is a macOS target.
 */
fun KmpTarget.Native.isMacosTarget(): Boolean = when (this.name) {
    "macosArm64", "macosX64" -> true
    else -> false
}
