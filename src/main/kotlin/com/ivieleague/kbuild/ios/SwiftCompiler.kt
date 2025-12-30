package com.ivieleague.kbuild.ios

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Interface for invoking Swift and Xcode build tools.
 *
 * Supports:
 * - `xcodebuild` for building iOS apps with Xcode projects
 * - `swift build` for building Swift packages
 */
object SwiftCompiler {

    /**
     * Check if running on macOS (required for Swift/Xcode builds).
     */
    fun isMacOS(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Check if xcodebuild is available.
     */
    fun hasXcodebuild(): Boolean {
        if (!isMacOS()) return false
        return try {
            val process = ProcessBuilder("which", "xcodebuild")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if swift is available.
     */
    fun hasSwift(): Boolean {
        return try {
            val process = ProcessBuilder("which", "swift")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Result of a build operation.
     */
    data class BuildResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val errorOutput: String,
        val outputDir: File?
    )

    /**
     * iOS Simulator destination for xcodebuild.
     */
    data class SimulatorDestination(
        val name: String = "iPhone 15",
        val os: String = "latest"
    ) {
        fun toDestinationString(): String = "platform=iOS Simulator,name=$name,OS=$os"
    }

    /**
     * Build an iOS app using xcodebuild.
     *
     * @param projectDir Directory containing the .xcodeproj
     * @param scheme The scheme to build (usually the app name)
     * @param configuration Debug or Release
     * @param destination Simulator or device destination
     * @param derivedDataPath Optional custom derived data path
     */
    fun buildWithXcode(
        projectDir: File,
        scheme: String,
        configuration: String = "Debug",
        destination: SimulatorDestination = SimulatorDestination(),
        derivedDataPath: File? = null
    ): BuildResult {
        require(isMacOS()) { "xcodebuild is only available on macOS" }
        require(hasXcodebuild()) { "xcodebuild not found. Install Xcode from the App Store." }

        val xcodeproj = projectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
            ?: throw IllegalArgumentException("No .xcodeproj found in $projectDir")

        val args = mutableListOf(
            "xcodebuild",
            "-project", xcodeproj.absolutePath,
            "-scheme", scheme,
            "-configuration", configuration,
            "-destination", destination.toDestinationString()
        )

        val effectiveDerivedData = derivedDataPath ?: projectDir.resolve("build/DerivedData")
        args.addAll(listOf("-derivedDataPath", effectiveDerivedData.absolutePath))

        args.add("build")

        val process = ProcessBuilder(args)
            .directory(projectDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val appPath = if (exitCode == 0) {
            findBuiltApp(effectiveDerivedData, scheme, configuration)
        } else null

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = appPath
        )
    }

    /**
     * Build an iOS app for a specific device architecture.
     */
    fun buildForDevice(
        projectDir: File,
        scheme: String,
        configuration: String = "Release",
        derivedDataPath: File? = null
    ): BuildResult {
        require(isMacOS()) { "xcodebuild is only available on macOS" }
        require(hasXcodebuild()) { "xcodebuild not found. Install Xcode from the App Store." }

        val xcodeproj = projectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
            ?: throw IllegalArgumentException("No .xcodeproj found in $projectDir")

        val effectiveDerivedData = derivedDataPath ?: projectDir.resolve("build/DerivedData")

        val args = listOf(
            "xcodebuild",
            "-project", xcodeproj.absolutePath,
            "-scheme", scheme,
            "-configuration", configuration,
            "-destination", "generic/platform=iOS",
            "-derivedDataPath", effectiveDerivedData.absolutePath,
            "build"
        )

        val process = ProcessBuilder(args)
            .directory(projectDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val appPath = if (exitCode == 0) {
            findBuiltApp(effectiveDerivedData, scheme, configuration)
        } else null

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = appPath
        )
    }

    /**
     * Build a Swift package using swift build.
     *
     * @param packageDir Directory containing Package.swift
     * @param configuration debug or release
     * @param additionalArgs Additional arguments to pass to swift build
     */
    fun buildSwiftPackage(
        packageDir: File,
        configuration: String = "debug",
        additionalArgs: List<String> = emptyList()
    ): BuildResult {
        require(hasSwift()) { "swift not found. Install Xcode or Swift toolchain." }

        val packageSwift = packageDir.resolve("Package.swift")
        require(packageSwift.exists()) { "Package.swift not found in $packageDir" }

        val args = mutableListOf("swift", "build", "-c", configuration)
        args.addAll(additionalArgs)

        val process = ProcessBuilder(args)
            .directory(packageDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val buildDir = packageDir.resolve(".build/$configuration")

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = if (exitCode == 0 && buildDir.exists()) buildDir else null
        )
    }

    /**
     * Run swift test on a package.
     */
    fun testSwiftPackage(
        packageDir: File,
        filter: String? = null
    ): BuildResult {
        require(hasSwift()) { "swift not found. Install Xcode or Swift toolchain." }

        val args = mutableListOf("swift", "test")
        if (filter != null) {
            args.addAll(listOf("--filter", filter))
        }

        val process = ProcessBuilder(args)
            .directory(packageDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = null
        )
    }

    /**
     * Clean a Swift package.
     */
    fun cleanSwiftPackage(packageDir: File): BuildResult {
        require(hasSwift()) { "swift not found" }

        val process = ProcessBuilder("swift", "package", "clean")
            .directory(packageDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = null
        )
    }

    /**
     * Clean xcodebuild derived data.
     */
    fun cleanXcode(
        projectDir: File,
        scheme: String,
        derivedDataPath: File? = null
    ): BuildResult {
        require(isMacOS()) { "xcodebuild is only available on macOS" }
        require(hasXcodebuild()) { "xcodebuild not found" }

        val xcodeproj = projectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
            ?: throw IllegalArgumentException("No .xcodeproj found in $projectDir")

        val args = mutableListOf(
            "xcodebuild",
            "-project", xcodeproj.absolutePath,
            "-scheme", scheme,
            "clean"
        )

        if (derivedDataPath != null) {
            args.addAll(listOf("-derivedDataPath", derivedDataPath.absolutePath))
        }

        val process = ProcessBuilder(args)
            .directory(projectDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = null
        )
    }

    /**
     * Run the app in the iOS Simulator.
     */
    fun runInSimulator(
        appPath: File,
        simulatorId: String? = null
    ): BuildResult {
        require(isMacOS()) { "iOS Simulator is only available on macOS" }
        require(appPath.exists()) { "App not found at $appPath" }

        // Boot the simulator if needed
        val simId = simulatorId ?: getDefaultSimulatorId()
            ?: throw IllegalStateException("No iOS Simulator available")

        // Boot simulator
        ProcessBuilder("xcrun", "simctl", "boot", simId)
            .inheritIO()
            .start()
            .waitFor()

        // Install the app
        val installProcess = ProcessBuilder("xcrun", "simctl", "install", simId, appPath.absolutePath)
            .start()

        val installOutput = installProcess.inputStream.bufferedReader().readText()
        val installError = installProcess.errorStream.bufferedReader().readText()

        if (installProcess.waitFor() != 0) {
            return BuildResult(
                success = false,
                exitCode = installProcess.exitValue(),
                output = installOutput,
                errorOutput = installError,
                outputDir = null
            )
        }

        // Get bundle ID from app
        val bundleId = getBundleIdFromApp(appPath)
            ?: return BuildResult(
                success = false,
                exitCode = 1,
                output = "",
                errorOutput = "Could not determine bundle ID from app",
                outputDir = null
            )

        // Launch the app
        val launchProcess = ProcessBuilder("xcrun", "simctl", "launch", simId, bundleId)
            .start()

        val launchOutput = launchProcess.inputStream.bufferedReader().readText()
        val launchError = launchProcess.errorStream.bufferedReader().readText()
        val exitCode = launchProcess.waitFor()

        return BuildResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = launchOutput,
            errorOutput = launchError,
            outputDir = appPath
        )
    }

    /**
     * List available iOS simulators.
     */
    fun listSimulators(): List<Simulator> {
        if (!isMacOS()) return emptyList()

        val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "-j")
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Simple parsing - in production you'd use a JSON parser
        val simulators = mutableListOf<Simulator>()
        val regex = """"name"\s*:\s*"([^"]+)".*?"udid"\s*:\s*"([^"]+)".*?"state"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in regex.findAll(output)) {
            simulators.add(Simulator(
                name = match.groupValues[1],
                udid = match.groupValues[2],
                state = match.groupValues[3]
            ))
        }

        return simulators.filter { it.name.contains("iPhone") || it.name.contains("iPad") }
    }

    data class Simulator(
        val name: String,
        val udid: String,
        val state: String
    ) {
        val isBooted: Boolean get() = state == "Booted"
    }

    // ============== Device Types ==============

    /**
     * Represents an iOS device (physical or simulator).
     */
    data class IosDevice(
        val udid: String,
        val name: String,
        val type: DeviceType,
        val state: String = "unknown"
    ) {
        val isConnected: Boolean get() = state == "connected" || state == "Booted"
    }

    enum class DeviceType {
        SIMULATOR,
        PHYSICAL_DEVICE
    }

    // ============== Archive & IPA Export ==============

    /**
     * Archive an iOS app for distribution.
     *
     * Creates an .xcarchive that can be exported to an IPA.
     *
     * @param projectDir Directory containing the .xcodeproj
     * @param scheme The scheme to archive
     * @param archivePath Where to save the .xcarchive
     * @param configuration Usually "Release" for distribution
     * @return Build result with the archive path
     */
    fun archiveApp(
        projectDir: File,
        scheme: String,
        archivePath: File,
        configuration: String = "Release"
    ): BuildResult {
        require(isMacOS()) { "xcodebuild is only available on macOS" }
        require(hasXcodebuild()) { "xcodebuild not found. Install Xcode from the App Store." }

        val xcodeproj = projectDir.listFiles { f -> f.name.endsWith(".xcodeproj") }?.firstOrNull()
            ?: throw IllegalArgumentException("No .xcodeproj found in $projectDir")

        archivePath.parentFile?.mkdirs()

        val args = listOf(
            "xcodebuild",
            "-project", xcodeproj.absolutePath,
            "-scheme", scheme,
            "-configuration", configuration,
            "-destination", "generic/platform=iOS",
            "-archivePath", archivePath.absolutePath,
            "archive"
        )

        val process = ProcessBuilder(args)
            .directory(projectDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return BuildResult(
            success = exitCode == 0 && archivePath.exists(),
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = if (archivePath.exists()) archivePath else null
        )
    }

    /**
     * Export an IPA from an archive.
     *
     * @param archivePath The .xcarchive directory
     * @param exportPath Directory to export the IPA to
     * @param exportOptionsPlist Path to ExportOptions.plist
     * @return The exported IPA file, or null if export failed
     */
    fun exportIpa(
        archivePath: File,
        exportPath: File,
        exportOptionsPlist: File
    ): BuildResult {
        require(isMacOS()) { "xcodebuild is only available on macOS" }
        require(hasXcodebuild()) { "xcodebuild not found" }
        require(archivePath.exists()) { "Archive not found at $archivePath" }
        require(exportOptionsPlist.exists()) { "ExportOptions.plist not found at $exportOptionsPlist" }

        exportPath.mkdirs()

        val args = listOf(
            "xcodebuild",
            "-exportArchive",
            "-archivePath", archivePath.absolutePath,
            "-exportPath", exportPath.absolutePath,
            "-exportOptionsPlist", exportOptionsPlist.absolutePath
        )

        val process = ProcessBuilder(args)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        // Find the exported IPA
        val ipaFile = exportPath.listFiles { f -> f.extension == "ipa" }?.firstOrNull()

        return BuildResult(
            success = exitCode == 0 && ipaFile != null,
            exitCode = exitCode,
            output = output,
            errorOutput = errorOutput,
            outputDir = ipaFile
        )
    }

    // ============== Physical Device Operations ==============

    /**
     * Check if devicectl is available (Xcode 15+, macOS 14+).
     */
    fun hasDeviceCtl(): Boolean {
        if (!isMacOS()) return false
        return try {
            val process = ProcessBuilder("xcrun", "devicectl", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List connected physical iOS devices.
     *
     * Uses devicectl (Xcode 15+) or falls back to instruments/idevice_id.
     *
     * @return List of connected devices
     */
    fun listConnectedDevices(): List<IosDevice> {
        if (!isMacOS()) return emptyList()

        return if (hasDeviceCtl()) {
            listDevicesWithDeviceCtl()
        } else {
            listDevicesWithInstruments()
        }
    }

    private fun listDevicesWithDeviceCtl(): List<IosDevice> {
        val process = ProcessBuilder("xcrun", "devicectl", "list", "devices", "--json-output", "-")
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        if (process.exitValue() != 0) return emptyList()

        // Parse JSON output - look for device entries
        val devices = mutableListOf<IosDevice>()

        // Simple regex parsing for device info
        // Format: "identifier" : "UDID", "name" : "iPhone Name"
        val devicePattern = """"identifier"\s*:\s*"([^"]+)".*?"name"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in devicePattern.findAll(output)) {
            val udid = match.groupValues[1]
            val name = match.groupValues[2]

            // Skip simulators (they have different identifier format)
            if (udid.length >= 20 && !udid.contains("-")) {
                devices.add(IosDevice(
                    udid = udid,
                    name = name,
                    type = DeviceType.PHYSICAL_DEVICE,
                    state = "connected"
                ))
            }
        }

        return devices
    }

    private fun listDevicesWithInstruments(): List<IosDevice> {
        // Fallback: use instruments -s devices
        val process = ProcessBuilder("instruments", "-s", "devices")
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val devices = mutableListOf<IosDevice>()

        // Parse format: "iPhone Name (iOS Version) [UDID]"
        val pattern = """^(.+?)\s+\([\d.]+\)\s+\[([A-Za-z0-9-]+)\]""".toRegex(RegexOption.MULTILINE)

        for (match in pattern.findAll(output)) {
            val name = match.groupValues[1].trim()
            val udid = match.groupValues[2]

            // Physical devices have UDIDs without dashes (40 chars)
            if (!udid.contains("-")) {
                devices.add(IosDevice(
                    udid = udid,
                    name = name,
                    type = DeviceType.PHYSICAL_DEVICE,
                    state = "connected"
                ))
            }
        }

        return devices
    }

    /**
     * Install an app on a physical iOS device.
     *
     * @param appPath Path to the .app bundle or .ipa file
     * @param deviceId Optional device UDID (uses first connected device if not specified)
     * @return True if installation succeeded
     */
    fun installOnDevice(
        appPath: File,
        deviceId: String? = null
    ): Boolean {
        require(isMacOS()) { "iOS device installation requires macOS" }
        require(appPath.exists()) { "App not found at $appPath" }

        val targetDevice = deviceId ?: listConnectedDevices().firstOrNull()?.udid
            ?: throw IllegalStateException("No iOS device connected")

        return if (hasDeviceCtl()) {
            installWithDeviceCtl(appPath, targetDevice)
        } else {
            installWithIdeviceinstaller(appPath, targetDevice)
        }
    }

    private fun installWithDeviceCtl(appPath: File, deviceId: String): Boolean {
        val args = if (appPath.extension == "ipa") {
            listOf("xcrun", "devicectl", "device", "install", "app", "--device", deviceId, appPath.absolutePath)
        } else {
            listOf("xcrun", "devicectl", "device", "install", "app", "--device", deviceId, appPath.absolutePath)
        }

        val process = ProcessBuilder(args)
            .inheritIO()
            .start()

        return process.waitFor() == 0
    }

    private fun installWithIdeviceinstaller(appPath: File, deviceId: String): Boolean {
        // Try ios-deploy first (commonly installed via npm)
        val iosDeployAvailable = try {
            ProcessBuilder("which", "ios-deploy").start().waitFor() == 0
        } catch (e: Exception) { false }

        return if (iosDeployAvailable) {
            val process = ProcessBuilder(
                "ios-deploy",
                "--id", deviceId,
                "--bundle", appPath.absolutePath,
                "--no-wifi"
            ).inheritIO().start()
            process.waitFor() == 0
        } else {
            // Try ideviceinstaller (libimobiledevice)
            val process = ProcessBuilder(
                "ideviceinstaller",
                "-u", deviceId,
                "-i", appPath.absolutePath
            ).inheritIO().start()
            process.waitFor() == 0
        }
    }

    /**
     * Launch an app on a physical iOS device.
     *
     * @param bundleId The app's bundle identifier
     * @param deviceId Optional device UDID
     * @return True if launch succeeded
     */
    fun launchOnDevice(
        bundleId: String,
        deviceId: String? = null
    ): Boolean {
        require(isMacOS()) { "iOS device operations require macOS" }

        val targetDevice = deviceId ?: listConnectedDevices().firstOrNull()?.udid
            ?: throw IllegalStateException("No iOS device connected")

        return if (hasDeviceCtl()) {
            launchWithDeviceCtl(bundleId, targetDevice)
        } else {
            launchWithIosDebug(bundleId, targetDevice)
        }
    }

    private fun launchWithDeviceCtl(bundleId: String, deviceId: String): Boolean {
        val process = ProcessBuilder(
            "xcrun", "devicectl", "device", "process", "launch",
            "--device", deviceId,
            bundleId
        ).inheritIO().start()

        return process.waitFor() == 0
    }

    private fun launchWithIosDebug(bundleId: String, deviceId: String): Boolean {
        // Fallback using ios-deploy
        val iosDeployAvailable = try {
            ProcessBuilder("which", "ios-deploy").start().waitFor() == 0
        } catch (e: Exception) { false }

        if (!iosDeployAvailable) {
            println("Warning: Neither devicectl nor ios-deploy available for launching")
            return false
        }

        // ios-deploy can launch by bundle ID with -L flag
        val process = ProcessBuilder(
            "ios-deploy",
            "--id", deviceId,
            "--bundle_id", bundleId,
            "--justlaunch"
        ).inheritIO().start()

        return process.waitFor() == 0
    }

    /**
     * Get all devices (simulators + physical).
     */
    fun listAllDevices(): List<IosDevice> {
        val simulators = listSimulators().map {
            IosDevice(
                udid = it.udid,
                name = it.name,
                type = DeviceType.SIMULATOR,
                state = it.state
            )
        }

        val physicalDevices = listConnectedDevices()

        return physicalDevices + simulators
    }

    // ============== App Store Connect / TestFlight Upload ==============

    /**
     * Result of an App Store Connect upload operation.
     */
    data class UploadResult(
        val success: Boolean,
        val output: String,
        val errorOutput: String,
        val requestId: String? = null
    )

    /**
     * Authentication method for App Store Connect.
     */
    sealed class AppStoreConnectAuth {
        /**
         * Authenticate using Apple ID and app-specific password.
         *
         * To create an app-specific password:
         * 1. Go to appleid.apple.com
         * 2. Sign in and go to Security → App-Specific Passwords
         * 3. Generate a password for "kbuild" or similar
         *
         * @param appleId Your Apple ID email
         * @param appSpecificPassword The generated app-specific password
         */
        data class AppleId(
            val appleId: String,
            val appSpecificPassword: String
        ) : AppStoreConnectAuth()

        /**
         * Authenticate using App Store Connect API Key.
         *
         * This is the recommended method for CI/CD.
         *
         * To create an API key:
         * 1. Go to App Store Connect → Users and Access → Keys
         * 2. Click "+" to create a new key with "App Manager" role
         * 3. Download the .p8 file (only available once!)
         *
         * @param keyId The Key ID from App Store Connect
         * @param issuerId The Issuer ID from App Store Connect
         * @param privateKeyPath Path to the .p8 private key file
         */
        data class ApiKey(
            val keyId: String,
            val issuerId: String,
            val privateKeyPath: File
        ) : AppStoreConnectAuth()
    }

    /**
     * Check if altool is available for App Store uploads.
     */
    fun hasAltool(): Boolean {
        if (!isMacOS()) return false
        return try {
            val process = ProcessBuilder("xcrun", "altool", "--help")
                .redirectErrorStream(true)
                .start()
            process.waitFor(10, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate an IPA before uploading to App Store Connect.
     *
     * This checks that the IPA meets App Store requirements without
     * actually uploading it.
     *
     * @param ipaPath Path to the IPA file
     * @param auth Authentication credentials
     * @return Validation result
     */
    fun validateForAppStore(
        ipaPath: File,
        auth: AppStoreConnectAuth
    ): UploadResult {
        require(isMacOS()) { "App Store validation requires macOS" }
        require(hasAltool()) { "xcrun altool not found. Install Xcode." }
        require(ipaPath.exists()) { "IPA not found at $ipaPath" }

        val args = mutableListOf(
            "xcrun", "altool",
            "--validate-app",
            "--type", "ios",
            "--file", ipaPath.absolutePath
        )

        addAuthArgs(args, auth)

        val process = ProcessBuilder(args)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return UploadResult(
            success = exitCode == 0,
            output = output,
            errorOutput = errorOutput
        )
    }

    /**
     * Upload an IPA to App Store Connect for TestFlight or App Store distribution.
     *
     * The IPA must be signed with an App Store distribution certificate
     * and built with the "app-store" export method.
     *
     * @param ipaPath Path to the IPA file
     * @param auth Authentication credentials (Apple ID or API Key)
     * @param validate Whether to validate before uploading (recommended)
     * @return Upload result
     */
    fun uploadToAppStoreConnect(
        ipaPath: File,
        auth: AppStoreConnectAuth,
        validate: Boolean = true
    ): UploadResult {
        require(isMacOS()) { "App Store upload requires macOS" }
        require(hasAltool()) { "xcrun altool not found. Install Xcode." }
        require(ipaPath.exists()) { "IPA not found at $ipaPath" }

        // Validate first if requested
        if (validate) {
            println("Validating IPA...")
            val validationResult = validateForAppStore(ipaPath, auth)
            if (!validationResult.success) {
                return UploadResult(
                    success = false,
                    output = "Validation failed:\n${validationResult.output}",
                    errorOutput = validationResult.errorOutput
                )
            }
            println("Validation passed.")
        }

        println("Uploading to App Store Connect...")
        val args = mutableListOf(
            "xcrun", "altool",
            "--upload-app",
            "--type", "ios",
            "--file", ipaPath.absolutePath
        )

        addAuthArgs(args, auth)

        val process = ProcessBuilder(args)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        // Try to extract request ID from output
        val requestId = Regex("RequestUUID\\s*=\\s*([a-f0-9-]+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)

        return UploadResult(
            success = exitCode == 0,
            output = output,
            errorOutput = errorOutput,
            requestId = requestId
        )
    }

    /**
     * Check the status of a previous upload using its request ID.
     *
     * @param requestId The request ID from a previous upload
     * @param auth Authentication credentials
     * @return Status information
     */
    fun checkUploadStatus(
        requestId: String,
        auth: AppStoreConnectAuth
    ): UploadResult {
        require(isMacOS()) { "App Store operations require macOS" }
        require(hasAltool()) { "xcrun altool not found" }

        val args = mutableListOf(
            "xcrun", "altool",
            "--notarization-info", requestId
        )

        addAuthArgs(args, auth)

        val process = ProcessBuilder(args)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return UploadResult(
            success = exitCode == 0,
            output = output,
            errorOutput = errorOutput,
            requestId = requestId
        )
    }

    private fun addAuthArgs(args: MutableList<String>, auth: AppStoreConnectAuth) {
        when (auth) {
            is AppStoreConnectAuth.AppleId -> {
                args.addAll(listOf(
                    "--username", auth.appleId,
                    "--password", auth.appSpecificPassword
                ))
            }
            is AppStoreConnectAuth.ApiKey -> {
                args.addAll(listOf(
                    "--apiKey", auth.keyId,
                    "--apiIssuer", auth.issuerId
                ))
                // The API key file must be in a specific location or set via env
                // ~/.private_keys/AuthKey_<keyId>.p8 or ~/.appstoreconnect/private_keys/
                // We'll ensure it's there
                ensureApiKeyInPlace(auth)
            }
        }
    }

    private fun ensureApiKeyInPlace(auth: AppStoreConnectAuth.ApiKey) {
        // altool looks for API keys in specific locations:
        // 1. ./private_keys/AuthKey_<keyId>.p8
        // 2. ~/.private_keys/AuthKey_<keyId>.p8
        // 3. ~/.appstoreconnect/private_keys/AuthKey_<keyId>.p8

        val privateKeysDir = File(System.getProperty("user.home"), ".private_keys")
        privateKeysDir.mkdirs()

        val expectedKeyFile = privateKeysDir.resolve("AuthKey_${auth.keyId}.p8")

        if (!expectedKeyFile.exists() && auth.privateKeyPath.exists()) {
            // Copy the key to the expected location
            auth.privateKeyPath.copyTo(expectedKeyFile, overwrite = true)
            // Ensure proper permissions
            try {
                ProcessBuilder("chmod", "600", expectedKeyFile.absolutePath).start().waitFor()
            } catch (e: Exception) {
                // Ignore permission errors on non-Unix systems
            }
        }
    }

    private fun getDefaultSimulatorId(): String? {
        val simulators = listSimulators()
        return simulators.find { it.isBooted }?.udid
            ?: simulators.find { it.name.contains("iPhone") }?.udid
    }

    private fun findBuiltApp(derivedDataPath: File, scheme: String, configuration: String): File? {
        // Xcode puts built apps in DerivedData/Build/Products/Configuration-iphonesimulator/
        val productsDir = derivedDataPath.resolve("Build/Products")
        if (!productsDir.exists()) return null

        val configDirs = productsDir.listFiles { f ->
            f.isDirectory && f.name.startsWith(configuration)
        } ?: return null

        for (configDir in configDirs) {
            val app = configDir.resolve("$scheme.app")
            if (app.exists()) return app
        }

        return null
    }

    private fun getBundleIdFromApp(appPath: File): String? {
        val infoPlist = appPath.resolve("Info.plist")
        if (!infoPlist.exists()) return null

        // Use PlistBuddy to extract bundle ID
        val process = ProcessBuilder(
            "/usr/libexec/PlistBuddy",
            "-c", "Print :CFBundleIdentifier",
            infoPlist.absolutePath
        ).start()

        return if (process.waitFor() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else null
    }
}

/**
 * Extension to add SPM local package dependency to Xcode project.
 *
 * This is useful for linking Kotlin XCFrameworks distributed via SPM.
 */
fun XcodeProject.addLocalPackage(packagePath: String, productName: String): XcodeProject {
    // Note: Full SPM integration requires modifying the project.pbxproj
    // to add XCLocalSwiftPackageReference and XCSwiftPackageProductDependency
    // This is a placeholder for future implementation
    return this
}
