package com.ivieleague.kbuild.android

import java.io.File

/**
 * Manages Android SDK detection and tool location.
 *
 * The Android SDK contains essential tools for building APKs:
 * - aapt2: Resource compilation and linking
 * - d8: DEX compilation (Java bytecode to Dalvik bytecode)
 * - zipalign: APK alignment for optimal loading
 * - apksigner: APK signing
 * - adb: Android Debug Bridge for device communication
 *
 * SDK is detected from:
 * 1. ANDROID_HOME environment variable (preferred)
 * 2. ANDROID_SDK_ROOT environment variable
 * 3. Common installation paths
 */
class AndroidSdk private constructor(
    val sdkRoot: File
) {
    /**
     * Available build-tools versions, sorted descending (newest first).
     */
    val buildToolsVersions: List<String> by lazy {
        val buildToolsDir = sdkRoot.resolve("build-tools")
        if (!buildToolsDir.exists()) return@lazy emptyList()

        buildToolsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.filter { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
            ?.sortedWith(compareByDescending { version ->
                version.split(".").map { it.toIntOrNull() ?: 0 }
                    .let { parts -> parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 } }
            })
            ?: emptyList()
    }

    /**
     * The newest installed build-tools version.
     */
    val latestBuildToolsVersion: String?
        get() = buildToolsVersions.firstOrNull()

    /**
     * Available platform versions (API levels), sorted descending.
     */
    val platformVersions: List<Int> by lazy {
        val platformsDir = sdkRoot.resolve("platforms")
        if (!platformsDir.exists()) return@lazy emptyList()

        platformsDir.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
            ?.mapNotNull { it.name.removePrefix("android-").toIntOrNull() }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * The highest installed API level.
     */
    val latestPlatformVersion: Int?
        get() = platformVersions.firstOrNull()

    /**
     * Get the build-tools directory for a specific version.
     */
    fun buildToolsDir(version: String = latestBuildToolsVersion ?: error("No build-tools installed")): File {
        val dir = sdkRoot.resolve("build-tools/$version")
        require(dir.exists()) { "Build-tools $version not found at $dir" }
        return dir
    }

    /**
     * Get the android.jar for a specific API level.
     */
    fun androidJar(apiLevel: Int = latestPlatformVersion ?: error("No platforms installed")): File {
        val jar = sdkRoot.resolve("platforms/android-$apiLevel/android.jar")
        require(jar.exists()) { "android.jar for API $apiLevel not found at $jar" }
        return jar
    }

    /**
     * Get the platform directory for a specific API level.
     */
    fun platformDir(apiLevel: Int): File {
        val dir = sdkRoot.resolve("platforms/android-$apiLevel")
        require(dir.exists()) { "Platform android-$apiLevel not found at $dir" }
        return dir
    }

    // ============== Build Tools ==============

    /**
     * Get aapt2 executable.
     * aapt2 compiles and links Android resources.
     */
    fun aapt2(buildToolsVersion: String = latestBuildToolsVersion ?: error("No build-tools")): File {
        return findExecutable(buildToolsDir(buildToolsVersion), "aapt2")
    }

    /**
     * Get d8 executable.
     * d8 compiles Java bytecode (.class) to Dalvik bytecode (.dex).
     */
    fun d8(buildToolsVersion: String = latestBuildToolsVersion ?: error("No build-tools")): File {
        // d8 is a script/batch file, not a direct executable
        val buildTools = buildToolsDir(buildToolsVersion)
        return when {
            isWindows -> buildTools.resolve("d8.bat").also { require(it.exists()) { "d8.bat not found in $buildTools" } }
            else -> findExecutable(buildTools, "d8")
        }
    }

    /**
     * Get zipalign executable.
     * zipalign optimizes APK file alignment.
     */
    fun zipalign(buildToolsVersion: String = latestBuildToolsVersion ?: error("No build-tools")): File {
        return findExecutable(buildToolsDir(buildToolsVersion), "zipalign")
    }

    /**
     * Get apksigner executable.
     * apksigner signs APK files.
     */
    fun apksigner(buildToolsVersion: String = latestBuildToolsVersion ?: error("No build-tools")): File {
        val buildTools = buildToolsDir(buildToolsVersion)
        return when {
            isWindows -> buildTools.resolve("apksigner.bat").also { require(it.exists()) { "apksigner.bat not found in $buildTools" } }
            else -> findExecutable(buildTools, "apksigner")
        }
    }

    // ============== Platform Tools ==============

    private val platformToolsDir: File
        get() = sdkRoot.resolve("platform-tools")

    /**
     * Get adb executable.
     * adb communicates with Android devices/emulators.
     */
    fun adb(): File {
        return findExecutable(platformToolsDir, "adb")
    }

    /**
     * Check if adb is available.
     */
    fun hasAdb(): Boolean {
        return try {
            adb().exists()
        } catch (e: Exception) {
            false
        }
    }

    // ============== Emulator ==============

    private val emulatorDir: File
        get() = sdkRoot.resolve("emulator")

    /**
     * Get emulator executable.
     */
    fun emulator(): File {
        return findExecutable(emulatorDir, "emulator")
    }

    /**
     * Check if emulator is available.
     */
    fun hasEmulator(): Boolean {
        return try {
            emulator().exists()
        } catch (e: Exception) {
            false
        }
    }

    // ============== Helpers ==============

    private fun findExecutable(dir: File, name: String): File {
        val exeName = if (isWindows) "$name.exe" else name
        val exe = dir.resolve(exeName)
        require(exe.exists()) { "$exeName not found in $dir" }
        return exe
    }

    /**
     * Validate that all required tools are available.
     */
    fun validate(minApiLevel: Int = 21, requireAdb: Boolean = true): ValidationResult {
        val errors = mutableListOf<String>()

        // Check build-tools
        if (buildToolsVersions.isEmpty()) {
            errors.add("No build-tools installed. Install via Android SDK Manager.")
        } else {
            try { aapt2() } catch (e: Exception) { errors.add("aapt2: ${e.message}") }
            try { d8() } catch (e: Exception) { errors.add("d8: ${e.message}") }
            try { zipalign() } catch (e: Exception) { errors.add("zipalign: ${e.message}") }
            try { apksigner() } catch (e: Exception) { errors.add("apksigner: ${e.message}") }
        }

        // Check platforms
        if (platformVersions.isEmpty()) {
            errors.add("No Android platforms installed. Install via Android SDK Manager.")
        } else if (!platformVersions.any { it >= minApiLevel }) {
            errors.add("No platform >= API $minApiLevel found. Install android-$minApiLevel or higher.")
        }

        // Check platform-tools
        if (requireAdb && !hasAdb()) {
            errors.add("adb not found. Install platform-tools via Android SDK Manager.")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            buildToolsVersion = latestBuildToolsVersion,
            platformVersion = latestPlatformVersion
        )
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>,
        val buildToolsVersion: String?,
        val platformVersion: Int?
    ) {
        fun printSummary() {
            if (valid) {
                println("Android SDK: Valid")
                println("  Build-tools: $buildToolsVersion")
                println("  Platform: android-$platformVersion")
            } else {
                println("Android SDK: Invalid")
                errors.forEach { println("  ERROR: $it") }
            }
        }
    }

    override fun toString(): String {
        return "AndroidSdk(root=$sdkRoot, buildTools=$latestBuildToolsVersion, platform=$latestPlatformVersion)"
    }

    companion object {
        private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        private val isMac = System.getProperty("os.name").lowercase().contains("mac")
        private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        /**
         * Common SDK installation paths.
         */
        private val commonPaths: List<String> by lazy {
            buildList {
                // Environment variables (highest priority)
                System.getenv("ANDROID_HOME")?.let { add(it) }
                System.getenv("ANDROID_SDK_ROOT")?.let { add(it) }

                // User home paths
                val userHome = System.getProperty("user.home")

                when {
                    isMac -> {
                        add("$userHome/Library/Android/sdk")
                        add("/usr/local/share/android-sdk")
                    }
                    isLinux -> {
                        add("$userHome/Android/Sdk")
                        add("$userHome/android-sdk")
                        add("/opt/android-sdk")
                        add("/usr/local/android-sdk")
                    }
                    isWindows -> {
                        add("$userHome/AppData/Local/Android/Sdk")
                        add("C:/Android/sdk")
                        add("C:/Users/$userHome/AppData/Local/Android/Sdk")
                    }
                }
            }
        }

        /**
         * Find the Android SDK installation.
         *
         * @return AndroidSdk instance or null if not found
         */
        fun find(): AndroidSdk? {
            for (path in commonPaths) {
                val dir = File(path)
                if (isValidSdk(dir)) {
                    return AndroidSdk(dir)
                }
            }
            return null
        }

        /**
         * Get the Android SDK, throwing if not found.
         *
         * @throws IllegalStateException if SDK is not found
         */
        fun get(): AndroidSdk {
            return find() ?: throw IllegalStateException(
                "Android SDK not found. Set ANDROID_HOME environment variable or install Android Studio.\n" +
                "Searched paths: ${commonPaths.joinToString()}"
            )
        }

        /**
         * Create AndroidSdk from a specific path.
         *
         * @param sdkRoot Path to the SDK root
         * @throws IllegalArgumentException if path is not a valid SDK
         */
        fun fromPath(sdkRoot: File): AndroidSdk {
            require(isValidSdk(sdkRoot)) {
                "Invalid Android SDK at $sdkRoot. Missing build-tools or platforms directory."
            }
            return AndroidSdk(sdkRoot)
        }

        /**
         * Check if a directory is a valid Android SDK.
         */
        fun isValidSdk(dir: File): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false

            // Must have at least build-tools or platforms directory
            val hasBuildTools = dir.resolve("build-tools").exists()
            val hasPlatforms = dir.resolve("platforms").exists()

            return hasBuildTools || hasPlatforms
        }

        /**
         * Check if Android SDK is available on this system.
         */
        fun isAvailable(): Boolean = find() != null
    }
}
