#!/usr/bin/env kotlin

/**
 * Build script for the hello-ios sample project.
 *
 * This demonstrates using IosProject to build an iOS app
 * without Xcode or CocoaPods configuration.
 *
 * Usage:
 *   ./Build.kt scaffold    - Set up the iOS project structure
 *   ./Build.kt build       - Build the XCFramework
 *   ./Build.kt build-app   - Build the iOS app (for simulator)
 *   ./Build.kt build-ipa   - Build IPA for device
 *   ./Build.kt simulators  - List available simulators
 *   ./Build.kt devices     - List connected devices
 *   ./Build.kt run         - Run in simulator
 *   ./Build.kt run-device  - Run on physical device
 *   ./Build.kt clean       - Clean build artifacts
 *
 * Requirements:
 *   - macOS with Xcode installed
 *   - Apple Developer certificate (for device deployment)
 */

@file:DependsOn("com.ivieleague:kbuild:1.0.0")

import com.ivieleague.kbuild.ios.*
import com.ivieleague.kbuild.kmp.kmpProject
import java.io.File

val projectRoot = File(".")

// Create KMP project with iOS targets
val kmpProject = kmpProject("hello-ios", projectRoot) {
    iosArm64()           // Physical devices
    iosSimulatorArm64()  // Apple Silicon simulators
    // iosX64()          // Intel simulators (uncomment if needed)
}

// Create iOS project
val ios = kmpProject.iosProject(
    iosDeploymentTarget = "15.0",
    frameworkName = "HelloIos"
)

// Handle command line arguments
when (args.firstOrNull()) {
    "scaffold" -> {
        println("Setting up iOS project...")
        ios.scaffold(
            appName = "HelloIosApp",
            bundleId = "com.example.helloios",
            integrationMode = IosProject.IntegrationMode.SPM
        )
        println()
        println("Done! Next steps:")
        println("  1. ./Build.kt build       (build XCFramework)")
        println("  2. ./Build.kt build-app   (build for simulator)")
        println("  3. ./Build.kt run         (run in simulator)")
    }

    "build" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: iOS builds require macOS!")
            System.exit(1)
        }

        println("Building iOS XCFramework...")
        val result = ios.build()
        result.printSummary()
    }

    "build-app" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: iOS builds require macOS!")
            System.exit(1)
        }

        // Ensure XCFramework exists
        val xcframework = ios.xcframeworksDir.resolve("HelloIos.xcframework")
        if (!xcframework.exists()) {
            println("Building XCFramework first...")
            ios.buildXCFramework()
        }

        // Generate Xcode project if needed
        val xcodeproj = ios.iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            println("Generating Xcode project...")
            ios.generateXcodeProject(
                appName = "HelloIosApp",
                bundleId = "com.example.helloios"
            )
        }

        println("Building iOS app for simulator...")
        val result = ios.buildApp(appName = "HelloIosApp")
        if (result.success) {
            println("Build succeeded!")
            println("App: ${result.app}")
        } else {
            println("Build failed:")
            println(result.output)
            System.exit(1)
        }
    }

    "build-ipa" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: iOS builds require macOS!")
            System.exit(1)
        }

        // Ensure project is set up
        val xcodeproj = ios.iosProjectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
        if (xcodeproj == null || !xcodeproj.exists()) {
            println("Please run './Build.kt scaffold' and './Build.kt build' first")
            System.exit(1)
        }

        println("Building IPA for device...")
        val result = ios.buildIpa(
            appName = "HelloIosApp",
            configuration = "Release"
        )
        result.printSummary()

        if (!result.success) {
            System.exit(1)
        }
    }

    "simulators" -> {
        println("Available iOS simulators:")
        val simulators = ios.listSimulators()
        if (simulators.isEmpty()) {
            println("  (none)")
        } else {
            simulators.filter { it.isAvailable }.forEach { sim ->
                val running = if (sim.state == "Booted") " [running]" else ""
                println("  ${sim.name} (${sim.udid})$running")
            }
        }
    }

    "devices" -> {
        println("Connected iOS devices:")
        val devices = ios.listDevices()
        if (devices.isEmpty()) {
            println("  (none)")
            println()
            println("To connect a device:")
            println("  1. Connect your iPhone/iPad via USB")
            println("  2. Trust the computer on your device")
            println("  3. Ensure you have a development certificate")
        } else {
            devices.forEach { device ->
                println("  ${device.name} (${device.udid}) - ${device.state}")
            }
        }
    }

    "run" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: iOS Simulator requires macOS!")
            System.exit(1)
        }

        println("Running in simulator...")
        val result = ios.runInSimulator(appName = "HelloIosApp")
        if (!result.success) {
            println("Failed to run app:")
            println(result.output)
            System.exit(1)
        }
    }

    "run-device" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: iOS device deployment requires macOS!")
            System.exit(1)
        }

        val devices = ios.listDevices()
        if (devices.isEmpty()) {
            println("No iOS devices connected!")
            println("Connect a device and try again.")
            System.exit(1)
        }

        // Check if IPA exists
        val ipaDir = ios.buildDir.resolve("outputs/ipa")
        val ipa = ipaDir.listFiles { f -> f.name.endsWith(".ipa") }?.firstOrNull()
        if (ipa == null) {
            println("No IPA found. Building IPA first...")
            val buildResult = ios.buildIpa(appName = "HelloIosApp")
            if (!buildResult.success) {
                println("Failed to build IPA: ${buildResult.errorMessage}")
                System.exit(1)
            }
        }

        println("Installing on device...")
        if (!ios.installOnDevice()) {
            println("Failed to install app")
            System.exit(1)
        }

        println("Launching app...")
        ios.runOnDevice(appName = "HelloIosApp", bundleId = "com.example.helloios")
    }

    "build-and-run" -> {
        println("Building and running...")
        val success = ios.buildAndRun(
            appName = "HelloIosApp",
            bundleId = "com.example.helloios",
            simulator = true
        )
        if (!success) {
            System.exit(1)
        }
    }

    "clean" -> {
        ios.clean()
    }

    "upload-testflight" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: TestFlight upload requires macOS!")
            System.exit(1)
        }

        // Get credentials from environment or command line
        val appleId = System.getenv("APPLE_ID")
        val appPassword = System.getenv("APP_SPECIFIC_PASSWORD")
        val apiKeyId = System.getenv("APP_STORE_CONNECT_KEY_ID")
        val apiIssuerId = System.getenv("APP_STORE_CONNECT_ISSUER_ID")
        val apiKeyPath = System.getenv("APP_STORE_CONNECT_KEY_PATH")

        val auth = when {
            apiKeyId != null && apiIssuerId != null && apiKeyPath != null -> {
                println("Using App Store Connect API Key authentication")
                SwiftCompiler.AppStoreConnectAuth.ApiKey(
                    keyId = apiKeyId,
                    issuerId = apiIssuerId,
                    privateKeyPath = File(apiKeyPath)
                )
            }
            appleId != null && appPassword != null -> {
                println("Using Apple ID authentication")
                SwiftCompiler.AppStoreConnectAuth.AppleId(
                    appleId = appleId,
                    appSpecificPassword = appPassword
                )
            }
            else -> {
                println("""
                    ERROR: No authentication credentials found!

                    Set one of these environment variable combinations:

                    Option 1 - API Key (Recommended for CI/CD):
                      APP_STORE_CONNECT_KEY_ID     - Your API Key ID
                      APP_STORE_CONNECT_ISSUER_ID  - Your Issuer ID
                      APP_STORE_CONNECT_KEY_PATH   - Path to AuthKey_XXX.p8 file

                    Option 2 - Apple ID:
                      APPLE_ID                     - Your Apple ID email
                      APP_SPECIFIC_PASSWORD        - App-specific password from appleid.apple.com

                    See: https://developer.apple.com/documentation/appstoreconnectapi
                """.trimIndent())
                System.exit(1)
                return@when null!! // unreachable
            }
        }

        println("Uploading to TestFlight...")
        val result = ios.uploadToTestFlight(
            appName = "HelloIosApp",
            auth = auth
        )
        result.printSummary()

        if (!result.success) {
            System.exit(1)
        }
    }

    "validate" -> {
        if (!IosProject.isMacOS()) {
            println("ERROR: Validation requires macOS!")
            System.exit(1)
        }

        // Find existing IPA
        val ipaDir = ios.buildDir.resolve("outputs/ipa")
        val ipa = ipaDir.listFiles { f -> f.name.endsWith(".ipa") }?.firstOrNull()
        if (ipa == null) {
            println("No IPA found. Build one first with: ./Build.kt build-ipa")
            System.exit(1)
        }

        // Get credentials
        val appleId = System.getenv("APPLE_ID")
        val appPassword = System.getenv("APP_SPECIFIC_PASSWORD")
        if (appleId == null || appPassword == null) {
            println("Set APPLE_ID and APP_SPECIFIC_PASSWORD environment variables")
            System.exit(1)
        }

        val auth = SwiftCompiler.AppStoreConnectAuth.AppleId(appleId, appPassword)
        val valid = ios.validateForAppStore(ipa, auth)

        if (!valid) {
            System.exit(1)
        }
    }

    else -> {
        println("""
            Hello iOS Sample
            ================

            Usage: ./Build.kt <command>

            Build Commands:
              scaffold     - Set up the iOS project structure
              build        - Build the XCFramework
              build-app    - Build the iOS app (for simulator)
              build-ipa    - Build IPA for device deployment
              clean        - Clean build artifacts

            Run Commands:
              simulators   - List available simulators
              devices      - List connected physical devices
              run          - Run in simulator
              run-device   - Install and run on physical device
              build-and-run- Build and run in one step

            TestFlight Commands:
              upload-testflight - Build and upload to TestFlight
              validate          - Validate IPA for App Store

            Quick Start (Simulator):
              1. ./Build.kt scaffold
              2. ./Build.kt build
              3. ./Build.kt build-app
              4. ./Build.kt run

            Quick Start (Device):
              1. ./Build.kt scaffold
              2. ./Build.kt build
              3. ./Build.kt build-ipa
              4. ./Build.kt run-device

            Quick Start (TestFlight):
              1. ./Build.kt scaffold
              2. ./Build.kt build
              3. export APPLE_ID=your@email.com
              4. export APP_SPECIFIC_PASSWORD=xxxx-xxxx-xxxx-xxxx
              5. ./Build.kt upload-testflight

            Requirements:
              - macOS with Xcode installed
              - Apple Developer account (for device/TestFlight)
              - App Store Connect API key or app-specific password (for TestFlight)
        """.trimIndent())
    }
}
