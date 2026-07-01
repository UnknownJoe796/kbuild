package com.ivieleague.kbuild.android

import com.ivieleague.kbuild.kmp.KmpProjectConfig
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileNonIncremental
import com.lightningkite.reactive.core.Constant
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orchestrates Android APK building from a Kotlin Multiplatform project.
 *
 * This class provides a complete Android build workflow:
 * 1. Compile Kotlin code to JVM bytecode (.class files)
 * 2. Convert bytecode to Dalvik DEX format
 * 3. Compile and link Android resources
 * 4. Package into an APK
 * 5. Align and sign the APK
 *
 * Example usage:
 * ```kotlin
 * val kmpConfig = kmpConfig("MyApp", projectRoot) {
 *     jvm()  // Use JVM target for Android
 * }
 *
 * val android = kmpConfig.androidProject(
 *     packageName = "com.example.myapp",
 *     minSdk = 24,
 *     targetSdk = 34
 * )
 *
 * // Scaffold project structure
 * android.scaffold(appName = "My App")
 *
 * // Build APK
 * val result = android.build()
 * println("APK: ${result.apk}")
 *
 * // Install and run on device
 * android.install()
 * android.run()
 * ```
 */
class AndroidProject(
    val kmpConfig: KmpProjectConfig,
    val packageName: String,
    val minSdk: Int = 24,
    val targetSdk: Int = 34,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val androidDir: File = kmpConfig.projectRoot.resolve("android")
) {
    val buildDir: File = kmpConfig.buildDir
    val outputsDir: File = buildDir.resolve("outputs/apk")

    private val sdk: AndroidSdk by lazy { AndroidSdk.get() }
    private val apkBuilder: ApkBuilder by lazy { ApkBuilder(sdk, targetSdk = targetSdk) }

    /**
     * Resource directories for the Android app.
     */
    val resourcesDir: File = androidDir.resolve("res")

    /**
     * Assets directory.
     */
    val assetsDir: File = androidDir.resolve("assets")

    /**
     * Native libraries directory.
     */
    val nativeLibsDir: File = androidDir.resolve("jniLibs")

    /**
     * AndroidManifest.xml file.
     */
    val manifestFile: File = androidDir.resolve("AndroidManifest.xml")

    // ============== Configuration ==============

    data class BuildConfig(
        val debug: Boolean = true,
        val minify: Boolean = false,
        val verbose: Boolean = false
    )

    // ============== Scaffolding ==============

    /**
     * Scaffold Android project structure.
     *
     * Creates:
     * - AndroidManifest.xml
     * - res/values/strings.xml
     * - res/layout/activity_main.xml (if mainActivity specified)
     * - Kotlin Activity class (if mainActivity specified)
     *
     * @param appName Display name of the app
     * @param mainActivity Fully qualified name of main activity (e.g., ".MainActivity")
     * @return ScaffoldResult with paths to created files
     */
    fun scaffold(
        appName: String = kmpConfig.name,
        mainActivity: String = ".MainActivity"
    ): ScaffoldResult {
        require(KmpTarget.Jvm in kmpConfig.targets) {
            "Android requires JVM target. Add jvm() to your KmpProject."
        }

        androidDir.mkdirs()
        resourcesDir.mkdirs()

        // Generate AndroidManifest.xml
        val manifest = AndroidManifest(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk
        ).apply {
            applicationLabel = appName
            applicationTheme = "@style/Theme.App"

            addActivity(
                name = mainActivity,
                exported = true,
                theme = "@style/Theme.App"
            ) {
                intentFilter {
                    action(AndroidManifest.Intent.ACTION_MAIN)
                    category(AndroidManifest.Intent.CATEGORY_LAUNCHER)
                }
            }
        }
        manifest.writeTo(manifestFile)

        // Generate res/values/strings.xml
        val valuesDir = resourcesDir.resolve("values")
        valuesDir.mkdirs()
        valuesDir.resolve("strings.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">$appName</string>
            </resources>
        """.trimIndent())

        // Generate res/values/styles.xml
        valuesDir.resolve("styles.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="android:Theme.Material.Light.DarkActionBar">
                    <item name="android:colorPrimary">#6200EE</item>
                    <item name="android:colorPrimaryDark">#3700B3</item>
                    <item name="android:colorAccent">#03DAC5</item>
                </style>
            </resources>
        """.trimIndent())

        // Generate res/layout/activity_main.xml
        val layoutDir = resourcesDir.resolve("layout")
        layoutDir.mkdirs()
        layoutDir.resolve("activity_main.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="16dp">

                <TextView
                    android:id="@+id/textView"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name"
                    android:textSize="24sp" />

                <Button
                    android:id="@+id/button"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:text="Click Me" />

            </LinearLayout>
        """.trimIndent())

        // Generate MainActivity.kt
        val srcDir = kmpConfig.projectRoot.resolve("src/jvmMain/kotlin")
        srcDir.mkdirs()

        // Parse activity name: ".MainActivity" or ".ui.HomeActivity" or "com.other.Activity"
        val activityPackage: String
        val activityClassName: String
        if (mainActivity.startsWith(".")) {
            // Relative to package: ".MainActivity" or ".ui.HomeActivity"
            val relativePath = mainActivity.substring(1) // Remove leading dot
            if (relativePath.contains(".")) {
                // Has subpackage: ".ui.HomeActivity" -> package = packageName.ui, class = HomeActivity
                activityPackage = packageName + "." + relativePath.substringBeforeLast(".")
                activityClassName = relativePath.substringAfterLast(".")
            } else {
                // Simple: ".MainActivity" -> package = packageName, class = MainActivity
                activityPackage = packageName
                activityClassName = relativePath
            }
        } else {
            // Fully qualified: "com.other.Activity"
            activityPackage = mainActivity.substringBeforeLast(".")
            activityClassName = mainActivity.substringAfterLast(".")
        }

        val activityPackageDir = srcDir.resolve(activityPackage.replace(".", "/"))
        activityPackageDir.mkdirs()

        val activityFile = activityPackageDir.resolve("$activityClassName.kt")
        // Only generate activity if it doesn't already exist
        if (!activityFile.exists()) {
            activityFile.writeText("""
            package $activityPackage

            import android.app.Activity
            import android.os.Bundle
            import android.widget.Button
            import android.widget.TextView

            /**
             * Main activity for the Android app.
             *
             * Generated by kbuild scaffold.
             */
            class $activityClassName : Activity() {
                private var clickCount = 0

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    val textView = findViewById<TextView>(R.id.textView)
                    val button = findViewById<Button>(R.id.button)

                    button.setOnClickListener {
                        clickCount++
                        textView.text = "Clicked ${'$'}clickCount times!"
                    }
                }
            }
        """.trimIndent())
        }

        // Generate R.java placeholder (will be generated by aapt2)
        val rPackageDir = srcDir.resolve(packageName.replace(".", "/"))
        rPackageDir.mkdirs()

        println("Android project scaffolded at: $androidDir")
        println()
        println("Next steps:")
        println("  1. Build: android.build()")
        println("  2. Install: android.install()")
        println("  3. Run: android.run()")

        return ScaffoldResult(
            androidDir = androidDir,
            manifestFile = manifestFile,
            resourcesDir = resourcesDir,
            sourceDir = srcDir
        )
    }

    data class ScaffoldResult(
        val androidDir: File,
        val manifestFile: File,
        val resourcesDir: File,
        val sourceDir: File
    )

    // ============== Compilation ==============

    /**
     * Compile Kotlin sources for Android.
     *
     * This uses the standard JVM compiler but includes android.jar in the classpath
     * so that Android framework classes (Activity, Bundle, etc.) are available.
     *
     * @param rJavaDir Directory containing generated R.java files (from aapt2 link)
     * @return The classes output directory
     */
    private suspend fun compileKotlinForAndroid(rJavaDir: File? = null): File {
        val androidJar = sdk.androidJar(targetSdk)

        // Get classpath from KmpProjectConfig dependencies + android.jar
        val baseClasspath = kmpConfig.dependencies.resolveJvmClasspath()
        val classpath = baseClasspath + setOf(androidJar)

        // Include R.java directory in source roots if it exists
        val sourceDirs = kmpConfig.getSourcesForTarget(KmpTarget.Jvm).toMutableSet()
        if (rJavaDir != null && rJavaDir.exists()) {
            sourceDirs.add(rJavaDir)
        }

        // Use non-incremental compilation for Android to properly handle R.java
        // The incremental compiler doesn't handle Java source files well
        return kotlinJvmCompileNonIncremental(
            name = kmpConfig.name,
            sourceRoots = Constant(sourceDirs),
            classpathJars = classpath,
            outputFolder = buildDir.resolve("classes/kotlin/android/main")
        )
    }

    // ============== Building ==============

    /**
     * Build the APK.
     *
     * Build pipeline:
     * 1. Compile Kotlin to .class files (with android.jar in classpath)
     * 2. Compile resources (aapt2 compile)
     * 3. Link resources + manifest → base APK (aapt2 link)
     * 4. Convert .class to DEX (d8)
     * 5. Add DEX to APK
     * 6. Add assets and native libs (if any)
     * 7. Align APK (zipalign)
     * 8. Sign APK (apksigner)
     *
     * @param config Build configuration
     * @return BuildResult with paths to outputs
     */
    suspend fun build(config: BuildConfig = BuildConfig()): BuildResult {
        require(AndroidSdk.isAvailable()) {
            "Android SDK not found. Set ANDROID_HOME or install Android Studio."
        }

        val validation = sdk.validate(minSdk)
        if (!validation.valid) {
            throw IllegalStateException(
                "Android SDK validation failed:\n${validation.errors.joinToString("\n") { "  - $it" }}"
            )
        }

        println("Building Android APK: ${kmpConfig.name}")
        println("  Package: $packageName")
        println("  Version: $versionName ($versionCode)")
        println("  Min SDK: $minSdk")
        println("  Target SDK: $targetSdk")
        println("  Build-tools: ${sdk.latestBuildToolsVersion}")
        println()

        val builder = ApkBuilder(sdk, targetSdk = targetSdk, verbose = config.verbose)
        val intermediateDir = buildDir.resolve("intermediates/android")
        intermediateDir.mkdirs()

        // Step 1: Compile resources
        println("Step 1/7: Compiling resources...")
        val flatFilesDir = intermediateDir.resolve("flat")
        val flatFiles = if (resourcesDir.exists()) {
            builder.compileResources(resourcesDir, flatFilesDir)
        } else {
            emptyList()
        }
        println("  Compiled ${flatFiles.size} resource files")

        // Step 2: Link resources and generate R.java
        println("Step 2/7: Linking resources...")
        val baseApk = intermediateDir.resolve("base.apk")
        val rJavaDir = intermediateDir.resolve("r")

        builder.linkResources(
            flatFiles = flatFiles,
            manifest = manifestFile,
            outputApk = baseApk,
            minSdk = minSdk,
            generateR = true,
            rJavaOutputDir = rJavaDir
        )
        println("  Base APK: $baseApk")
        println("  R.java: $rJavaDir")

        // Step 3: Compile Kotlin to .class files (with android.jar + R.java in classpath)
        println("Step 3/7: Compiling Kotlin...")
        val classesDir = compileKotlinForAndroid(rJavaDir)
        println("  Classes: $classesDir")

        // Step 4: Compile DEX
        println("Step 4/7: Compiling DEX...")
        val dexDir = intermediateDir.resolve("dex")
        val dexFile = builder.compileDex(
            classesDir = classesDir,
            outputDir = dexDir,
            minSdk = minSdk
        )
        println("  DEX: $dexFile")

        // Step 5: Add DEX to APK
        println("Step 5/7: Adding DEX to APK...")
        builder.addDexToApk(baseApk, dexFile)

        // Add assets if present
        if (assetsDir.exists()) {
            builder.addAssets(baseApk, assetsDir)
        }

        // Add native libs if present
        if (nativeLibsDir.exists()) {
            builder.addNativeLibs(baseApk, nativeLibsDir)
        }

        // Step 6: Align APK
        println("Step 6/7: Aligning APK...")
        outputsDir.mkdirs()
        val alignedApk = outputsDir.resolve("${kmpConfig.name}-aligned.apk")
        builder.align(baseApk, alignedApk)
        println("  Aligned: $alignedApk")

        // Step 7: Sign APK
        println("Step 7/7: Signing APK...")
        val keystore = ApkBuilder.debugKeystore(kmpConfig.projectRoot)
        val signedApk = outputsDir.resolve(
            if (config.debug) "${kmpConfig.name}-debug.apk"
            else "${kmpConfig.name}-release.apk"
        )
        builder.sign(alignedApk, keystore, signedApk)
        println("  Signed: $signedApk")

        println()
        println("BUILD SUCCESSFUL")
        println("APK: $signedApk")

        return BuildResult(
            success = true,
            apk = signedApk,
            alignedApk = alignedApk,
            classesDir = classesDir,
            dexFile = dexFile,
            buildType = if (config.debug) "debug" else "release"
        )
    }

    data class BuildResult(
        val success: Boolean,
        val apk: File,
        val alignedApk: File,
        val classesDir: File,
        val dexFile: File,
        val buildType: String
    )

    // ============== Device Operations ==============

    /**
     * Install the APK on a connected device or emulator.
     *
     * @param apk APK file to install (default: last built APK)
     * @return True if installation succeeded
     */
    fun install(apk: File? = null): Boolean {
        val targetApk = apk ?: findLatestApk()
            ?: throw IllegalStateException("No APK found. Run build() first.")

        require(sdk.hasAdb()) { "adb not found in Android SDK" }

        println("Installing $targetApk...")

        val result = runAdb("install", "-r", targetApk.absolutePath)

        if (!result.success) {
            println("Install failed: ${result.output}")
            return false
        }

        println("Installed successfully")
        return true
    }

    /**
     * Uninstall the app from a connected device.
     */
    fun uninstall(): Boolean {
        require(sdk.hasAdb()) { "adb not found" }

        println("Uninstalling $packageName...")
        val result = runAdb("uninstall", packageName)

        return result.success
    }

    /**
     * Launch the app on a connected device.
     *
     * @param activity Activity to launch (default: main launcher activity)
     */
    fun run(activity: String = ".MainActivity"): Boolean {
        require(sdk.hasAdb()) { "adb not found" }

        val fullActivity = if (activity.startsWith(".")) "$packageName$activity" else activity

        println("Starting $fullActivity...")

        val result = runAdb(
            "shell", "am", "start",
            "-n", "$packageName/$fullActivity"
        )

        if (!result.success) {
            println("Launch failed: ${result.output}")
            return false
        }

        println("App started")
        return true
    }

    /**
     * Stop the app on a connected device.
     */
    fun stop(): Boolean {
        require(sdk.hasAdb()) { "adb not found" }

        val result = runAdb("shell", "am", "force-stop", packageName)
        return result.success
    }

    /**
     * Clear app data on device.
     */
    fun clearData(): Boolean {
        require(sdk.hasAdb()) { "adb not found" }

        val result = runAdb("shell", "pm", "clear", packageName)
        return result.success
    }

    /**
     * View logcat output for this app.
     *
     * @param lines Number of lines to show
     * @return Log output
     */
    fun logcat(lines: Int = 100): String {
        require(sdk.hasAdb()) { "adb not found" }

        // Get the app's PID first
        val pidResult = runAdb("shell", "pidof", "-s", packageName)
        val pid = pidResult.output.trim()

        return if (pid.isNotEmpty()) {
            val result = runAdb("logcat", "-d", "-t", lines.toString(), "--pid=$pid")
            result.output
        } else {
            // If no PID, just grep for the package name
            val result = runAdb("logcat", "-d", "-t", lines.toString())
            result.output.lines()
                .filter { it.contains(packageName) || it.contains(kmpConfig.name) }
                .joinToString("\n")
        }
    }

    /**
     * List connected devices.
     */
    fun listDevices(): List<Device> {
        require(sdk.hasAdb()) { "adb not found" }

        val result = runAdb("devices", "-l")
        if (!result.success) return emptyList()

        return result.output.lines()
            .drop(1) // Skip header
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    Device(
                        serial = parts[0],
                        state = parts[1],
                        model = parts.find { it.startsWith("model:") }?.substringAfter("model:"),
                        device = parts.find { it.startsWith("device:") }?.substringAfter("device:")
                    )
                } else null
            }
    }

    data class Device(
        val serial: String,
        val state: String,
        val model: String?,
        val device: String?
    )

    // ============== Emulator Management ==============

    /**
     * List available Android emulators (AVDs).
     */
    fun listEmulators(): List<String> {
        if (!sdk.hasEmulator()) return emptyList()

        val emulator = sdk.emulator()
        val result = runCommand(emulator.absolutePath, "-list-avds")

        return if (result.success) {
            result.output.lines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    /**
     * Start an emulator.
     *
     * @param avdName Name of the AVD to start
     * @param waitForBoot Whether to wait for the emulator to fully boot
     */
    fun startEmulator(avdName: String, waitForBoot: Boolean = true): Boolean {
        require(sdk.hasEmulator()) { "Android emulator not found" }

        val emulator = sdk.emulator()

        println("Starting emulator: $avdName...")

        // Start emulator in background
        ProcessBuilder(emulator.absolutePath, "-avd", avdName, "-no-snapshot-load")
            .redirectErrorStream(true)
            .start()

        if (waitForBoot) {
            println("Waiting for emulator to boot...")
            repeat(60) { // Wait up to 2 minutes
                Thread.sleep(2000)
                val result = runAdb("shell", "getprop", "sys.boot_completed")
                if (result.output.trim() == "1") {
                    println("Emulator ready")
                    return true
                }
            }
            println("Emulator boot timeout")
            return false
        }

        return true
    }

    // ============== Clean ==============

    /**
     * Clean build artifacts.
     */
    fun clean() {
        println("Cleaning Android build artifacts...")

        buildDir.resolve("intermediates/android").deleteRecursively()
        outputsDir.deleteRecursively()

        println("Clean complete")
    }

    // ============== Helpers ==============

    private fun findLatestApk(): File? {
        if (!outputsDir.exists()) return null

        return outputsDir.listFiles { f -> f.extension == "apk" && !f.name.contains("aligned") }
            ?.maxByOrNull { it.lastModified() }
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int
    )

    private fun runAdb(vararg args: String): CommandResult {
        val adb = sdk.adb()
        return runCommand(adb.absolutePath, *args)
    }

    private fun runCommand(vararg command: String, timeoutSeconds: Long = 60): CommandResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CommandResult(false, "Command timed out", -1)
        }

        return CommandResult(
            success = process.exitValue() == 0,
            output = output,
            exitCode = process.exitValue()
        )
    }

    companion object {
        /**
         * Check if Android SDK is available.
         */
        fun isAvailable(): Boolean = AndroidSdk.isAvailable()
    }
}

/**
 * DSL for creating an Android project from a KMP project configuration.
 */
fun KmpProjectConfig.androidProject(
    packageName: String,
    minSdk: Int = 24,
    targetSdk: Int = 34,
    versionCode: Int = 1,
    versionName: String = "1.0",
    androidDir: File = this.projectRoot.resolve("android")
): AndroidProject = AndroidProject(
    kmpConfig = this,
    packageName = packageName,
    minSdk = minSdk,
    targetSdk = targetSdk,
    versionCode = versionCode,
    versionName = versionName,
    androidDir = androidDir
)
