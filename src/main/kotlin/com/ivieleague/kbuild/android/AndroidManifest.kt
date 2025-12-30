package com.ivieleague.kbuild.android

import java.io.File

/**
 * Generates AndroidManifest.xml for Android applications.
 *
 * The manifest declares:
 * - Package name and version
 * - Min/target SDK versions
 * - Application components (activities, services, receivers, providers)
 * - Required permissions
 * - Hardware/software features
 *
 * Example:
 * ```kotlin
 * val manifest = AndroidManifest(
 *     packageName = "com.example.myapp",
 *     versionCode = 1,
 *     versionName = "1.0.0",
 *     minSdk = 24,
 *     targetSdk = 34
 * )
 *
 * manifest.addActivity(
 *     name = ".MainActivity",
 *     exported = true,
 *     launchMode = LaunchMode.SINGLE_TOP
 * ) {
 *     intentFilter {
 *         action(Intent.ACTION_MAIN)
 *         category(Intent.CATEGORY_LAUNCHER)
 *     }
 * }
 *
 * manifest.writeTo(projectDir.resolve("AndroidManifest.xml"))
 * ```
 */
class AndroidManifest(
    val packageName: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 21,
    val targetSdk: Int = 34,
    val compileSdk: Int = targetSdk
) {
    private val permissions = mutableListOf<String>()
    private val features = mutableListOf<Feature>()
    private val activities = mutableListOf<Activity>()
    private val services = mutableListOf<Service>()
    private val receivers = mutableListOf<Receiver>()
    private val providers = mutableListOf<Provider>()
    private val metaData = mutableListOf<MetaData>()

    var applicationLabel: String = packageName.substringAfterLast(".")
    var applicationIcon: String? = null
    var applicationRoundIcon: String? = null
    var applicationTheme: String? = null
    var applicationName: String? = null
    var debuggable: Boolean? = null
    var allowBackup: Boolean = true
    var supportsRtl: Boolean = true
    var extractNativeLibs: Boolean? = null

    // ============== Permissions ==============

    /**
     * Add a permission requirement.
     */
    fun addPermission(permission: String) {
        if (permission !in permissions) {
            permissions.add(permission)
        }
    }

    /**
     * Add multiple permissions.
     */
    fun addPermissions(vararg perms: String) {
        perms.forEach { addPermission(it) }
    }

    /**
     * Common permission constants.
     */
    object Permission {
        const val INTERNET = "android.permission.INTERNET"
        const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
        const val ACCESS_WIFI_STATE = "android.permission.ACCESS_WIFI_STATE"
        const val CAMERA = "android.permission.CAMERA"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
        const val VIBRATE = "android.permission.VIBRATE"
        const val WAKE_LOCK = "android.permission.WAKE_LOCK"
        const val RECEIVE_BOOT_COMPLETED = "android.permission.RECEIVE_BOOT_COMPLETED"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }

    // ============== Features ==============

    data class Feature(
        val name: String,
        val required: Boolean = true,
        val version: Int? = null
    )

    fun addFeature(name: String, required: Boolean = true, version: Int? = null) {
        features.add(Feature(name, required, version))
    }

    // ============== Intent ==============

    object Intent {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_DIAL = "android.intent.action.DIAL"

        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
    }

    // ============== Intent Filters ==============

    class IntentFilter {
        val actions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val data = mutableListOf<DataSpec>()

        data class DataSpec(
            val scheme: String? = null,
            val host: String? = null,
            val port: String? = null,
            val path: String? = null,
            val pathPrefix: String? = null,
            val pathPattern: String? = null,
            val mimeType: String? = null
        )

        fun action(action: String) {
            actions.add(action)
        }

        fun category(category: String) {
            categories.add(category)
        }

        fun data(
            scheme: String? = null,
            host: String? = null,
            port: String? = null,
            path: String? = null,
            pathPrefix: String? = null,
            pathPattern: String? = null,
            mimeType: String? = null
        ) {
            data.add(DataSpec(scheme, host, port, path, pathPrefix, pathPattern, mimeType))
        }

        internal fun toXml(indent: String): String = buildString {
            appendLine("$indent<intent-filter>")
            actions.forEach { appendLine("$indent    <action android:name=\"$it\" />") }
            categories.forEach { appendLine("$indent    <category android:name=\"$it\" />") }
            data.forEach { d ->
                append("$indent    <data")
                d.scheme?.let { append(" android:scheme=\"$it\"") }
                d.host?.let { append(" android:host=\"$it\"") }
                d.port?.let { append(" android:port=\"$it\"") }
                d.path?.let { append(" android:path=\"$it\"") }
                d.pathPrefix?.let { append(" android:pathPrefix=\"$it\"") }
                d.pathPattern?.let { append(" android:pathPattern=\"$it\"") }
                d.mimeType?.let { append(" android:mimeType=\"$it\"") }
                appendLine(" />")
            }
            appendLine("$indent</intent-filter>")
        }
    }

    // ============== Components ==============

    enum class LaunchMode(val value: String) {
        STANDARD("standard"),
        SINGLE_TOP("singleTop"),
        SINGLE_TASK("singleTask"),
        SINGLE_INSTANCE("singleInstance")
    }

    enum class ScreenOrientation(val value: String) {
        UNSPECIFIED("unspecified"),
        BEHIND("behind"),
        LANDSCAPE("landscape"),
        PORTRAIT("portrait"),
        REVERSE_LANDSCAPE("reverseLandscape"),
        REVERSE_PORTRAIT("reversePortrait"),
        SENSOR_LANDSCAPE("sensorLandscape"),
        SENSOR_PORTRAIT("sensorPortrait"),
        USER_LANDSCAPE("userLandscape"),
        USER_PORTRAIT("userPortrait"),
        SENSOR("sensor"),
        FULL_SENSOR("fullSensor"),
        NO_SENSOR("nosensor"),
        USER("user"),
        FULL_USER("fullUser"),
        LOCKED("locked")
    }

    enum class ConfigChanges(val value: String) {
        DENSITY("density"),
        FONT_SCALE("fontScale"),
        KEYBOARD("keyboard"),
        KEYBOARD_HIDDEN("keyboardHidden"),
        LAYOUT_DIRECTION("layoutDirection"),
        LOCALE("locale"),
        MCC("mcc"),
        MNC("mnc"),
        NAVIGATION("navigation"),
        ORIENTATION("orientation"),
        SCREEN_LAYOUT("screenLayout"),
        SCREEN_SIZE("screenSize"),
        SMALLEST_SCREEN_SIZE("smallestScreenSize"),
        TOUCHSCREEN("touchscreen"),
        UI_MODE("uiMode")
    }

    class Activity(
        val name: String,
        val exported: Boolean = false,
        val enabled: Boolean = true,
        val label: String? = null,
        val theme: String? = null,
        val launchMode: LaunchMode? = null,
        val screenOrientation: ScreenOrientation? = null,
        val configChanges: List<ConfigChanges> = emptyList(),
        val taskAffinity: String? = null,
        val windowSoftInputMode: String? = null,
        val parentActivityName: String? = null
    ) {
        val intentFilters = mutableListOf<IntentFilter>()
        val metaData = mutableListOf<MetaData>()

        fun intentFilter(block: IntentFilter.() -> Unit) {
            intentFilters.add(IntentFilter().apply(block))
        }

        fun metaData(name: String, value: String? = null, resource: String? = null) {
            metaData.add(MetaData(name, value, resource))
        }
    }

    class Service(
        val name: String,
        val exported: Boolean = false,
        val enabled: Boolean = true,
        val permission: String? = null,
        val foregroundServiceType: String? = null
    ) {
        val intentFilters = mutableListOf<IntentFilter>()
        val metaData = mutableListOf<MetaData>()

        fun intentFilter(block: IntentFilter.() -> Unit) {
            intentFilters.add(IntentFilter().apply(block))
        }
    }

    class Receiver(
        val name: String,
        val exported: Boolean = false,
        val enabled: Boolean = true,
        val permission: String? = null
    ) {
        val intentFilters = mutableListOf<IntentFilter>()
        val metaData = mutableListOf<MetaData>()

        fun intentFilter(block: IntentFilter.() -> Unit) {
            intentFilters.add(IntentFilter().apply(block))
        }
    }

    class Provider(
        val name: String,
        val authorities: String,
        val exported: Boolean = false,
        val enabled: Boolean = true,
        val grantUriPermissions: Boolean = false,
        val permission: String? = null,
        val readPermission: String? = null,
        val writePermission: String? = null
    ) {
        val metaData = mutableListOf<MetaData>()
    }

    data class MetaData(
        val name: String,
        val value: String? = null,
        val resource: String? = null
    )

    // ============== Add Components ==============

    fun addActivity(
        name: String,
        exported: Boolean = false,
        enabled: Boolean = true,
        label: String? = null,
        theme: String? = null,
        launchMode: LaunchMode? = null,
        screenOrientation: ScreenOrientation? = null,
        configChanges: List<ConfigChanges> = emptyList(),
        taskAffinity: String? = null,
        windowSoftInputMode: String? = null,
        parentActivityName: String? = null,
        block: Activity.() -> Unit = {}
    ) {
        activities.add(Activity(
            name = name,
            exported = exported,
            enabled = enabled,
            label = label,
            theme = theme,
            launchMode = launchMode,
            screenOrientation = screenOrientation,
            configChanges = configChanges,
            taskAffinity = taskAffinity,
            windowSoftInputMode = windowSoftInputMode,
            parentActivityName = parentActivityName
        ).apply(block))
    }

    fun addService(
        name: String,
        exported: Boolean = false,
        enabled: Boolean = true,
        permission: String? = null,
        foregroundServiceType: String? = null,
        block: Service.() -> Unit = {}
    ) {
        services.add(Service(name, exported, enabled, permission, foregroundServiceType).apply(block))
    }

    fun addReceiver(
        name: String,
        exported: Boolean = false,
        enabled: Boolean = true,
        permission: String? = null,
        block: Receiver.() -> Unit = {}
    ) {
        receivers.add(Receiver(name, exported, enabled, permission).apply(block))
    }

    fun addProvider(
        name: String,
        authorities: String,
        exported: Boolean = false,
        enabled: Boolean = true,
        grantUriPermissions: Boolean = false,
        permission: String? = null,
        readPermission: String? = null,
        writePermission: String? = null,
        block: Provider.() -> Unit = {}
    ) {
        providers.add(Provider(name, authorities, exported, enabled, grantUriPermissions, permission, readPermission, writePermission).apply(block))
    }

    fun addMetaData(name: String, value: String? = null, resource: String? = null) {
        metaData.add(MetaData(name, value, resource))
    }

    // ============== Generate XML ==============

    /**
     * Generate the AndroidManifest.xml content.
     */
    fun generate(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"""")
        appendLine("""    xmlns:tools="http://schemas.android.com/tools"""")
        appendLine("""    package="$packageName"""")
        appendLine("""    android:versionCode="$versionCode"""")
        appendLine("""    android:versionName="$versionName">""")
        appendLine()

        // Uses-sdk
        appendLine("""    <uses-sdk""")
        appendLine("""        android:minSdkVersion="$minSdk"""")
        appendLine("""        android:targetSdkVersion="$targetSdk" />""")
        appendLine()

        // Permissions
        permissions.forEach { perm ->
            appendLine("""    <uses-permission android:name="$perm" />""")
        }
        if (permissions.isNotEmpty()) appendLine()

        // Features
        features.forEach { feature ->
            append("""    <uses-feature android:name="${feature.name}"""")
            append(""" android:required="${feature.required}"""")
            feature.version?.let { append(""" android:glEsVersion="$it"""") }
            appendLine(" />")
        }
        if (features.isNotEmpty()) appendLine()

        // Application
        append("    <application")
        appendLine()
        appendLine("""        android:label="$applicationLabel"""")
        applicationIcon?.let { appendLine("""        android:icon="$it"""") }
        applicationRoundIcon?.let { appendLine("""        android:roundIcon="$it"""") }
        applicationTheme?.let { appendLine("""        android:theme="$it"""") }
        applicationName?.let { appendLine("""        android:name="$it"""") }
        appendLine("""        android:allowBackup="$allowBackup"""")
        appendLine("""        android:supportsRtl="$supportsRtl"""")
        debuggable?.let { appendLine("""        android:debuggable="$it"""") }
        extractNativeLibs?.let { appendLine("""        android:extractNativeLibs="$it"""") }
        appendLine("        >")

        // Application meta-data
        metaData.forEach { md ->
            append("        <meta-data android:name=\"${md.name}\"")
            md.value?.let { append(" android:value=\"$it\"") }
            md.resource?.let { append(" android:resource=\"$it\"") }
            appendLine(" />")
        }

        // Activities
        activities.forEach { activity ->
            generateActivity(activity, "        ")
        }

        // Services
        services.forEach { service ->
            generateService(service, "        ")
        }

        // Receivers
        receivers.forEach { receiver ->
            generateReceiver(receiver, "        ")
        }

        // Providers
        providers.forEach { provider ->
            generateProvider(provider, "        ")
        }

        appendLine("    </application>")
        appendLine()
        appendLine("</manifest>")
    }

    private fun StringBuilder.generateActivity(activity: Activity, indent: String) {
        append("${indent}<activity")
        appendLine()
        appendLine("$indent    android:name=\"${activity.name}\"")
        appendLine("$indent    android:exported=\"${activity.exported}\"")
        if (!activity.enabled) appendLine("$indent    android:enabled=\"false\"")
        activity.label?.let { appendLine("$indent    android:label=\"$it\"") }
        activity.theme?.let { appendLine("$indent    android:theme=\"$it\"") }
        activity.launchMode?.let { appendLine("$indent    android:launchMode=\"${it.value}\"") }
        activity.screenOrientation?.let { appendLine("$indent    android:screenOrientation=\"${it.value}\"") }
        if (activity.configChanges.isNotEmpty()) {
            appendLine("$indent    android:configChanges=\"${activity.configChanges.joinToString("|") { it.value }}\"")
        }
        activity.taskAffinity?.let { appendLine("$indent    android:taskAffinity=\"$it\"") }
        activity.windowSoftInputMode?.let { appendLine("$indent    android:windowSoftInputMode=\"$it\"") }
        activity.parentActivityName?.let { appendLine("$indent    android:parentActivityName=\"$it\"") }

        if (activity.intentFilters.isEmpty() && activity.metaData.isEmpty()) {
            appendLine("$indent    />")
        } else {
            appendLine("$indent    >")
            activity.metaData.forEach { md ->
                append("$indent    <meta-data android:name=\"${md.name}\"")
                md.value?.let { append(" android:value=\"$it\"") }
                md.resource?.let { append(" android:resource=\"$it\"") }
                appendLine(" />")
            }
            activity.intentFilters.forEach { filter ->
                append(filter.toXml("$indent    "))
            }
            appendLine("$indent</activity>")
        }
    }

    private fun StringBuilder.generateService(service: Service, indent: String) {
        append("${indent}<service")
        appendLine()
        appendLine("$indent    android:name=\"${service.name}\"")
        appendLine("$indent    android:exported=\"${service.exported}\"")
        if (!service.enabled) appendLine("$indent    android:enabled=\"false\"")
        service.permission?.let { appendLine("$indent    android:permission=\"$it\"") }
        service.foregroundServiceType?.let { appendLine("$indent    android:foregroundServiceType=\"$it\"") }

        if (service.intentFilters.isEmpty() && service.metaData.isEmpty()) {
            appendLine("$indent    />")
        } else {
            appendLine("$indent    >")
            service.metaData.forEach { md ->
                append("$indent    <meta-data android:name=\"${md.name}\"")
                md.value?.let { append(" android:value=\"$it\"") }
                md.resource?.let { append(" android:resource=\"$it\"") }
                appendLine(" />")
            }
            service.intentFilters.forEach { filter ->
                append(filter.toXml("$indent    "))
            }
            appendLine("$indent</service>")
        }
    }

    private fun StringBuilder.generateReceiver(receiver: Receiver, indent: String) {
        append("${indent}<receiver")
        appendLine()
        appendLine("$indent    android:name=\"${receiver.name}\"")
        appendLine("$indent    android:exported=\"${receiver.exported}\"")
        if (!receiver.enabled) appendLine("$indent    android:enabled=\"false\"")
        receiver.permission?.let { appendLine("$indent    android:permission=\"$it\"") }

        if (receiver.intentFilters.isEmpty() && receiver.metaData.isEmpty()) {
            appendLine("$indent    />")
        } else {
            appendLine("$indent    >")
            receiver.metaData.forEach { md ->
                append("$indent    <meta-data android:name=\"${md.name}\"")
                md.value?.let { append(" android:value=\"$it\"") }
                md.resource?.let { append(" android:resource=\"$it\"") }
                appendLine(" />")
            }
            receiver.intentFilters.forEach { filter ->
                append(filter.toXml("$indent    "))
            }
            appendLine("$indent</receiver>")
        }
    }

    private fun StringBuilder.generateProvider(provider: Provider, indent: String) {
        append("${indent}<provider")
        appendLine()
        appendLine("$indent    android:name=\"${provider.name}\"")
        appendLine("$indent    android:authorities=\"${provider.authorities}\"")
        appendLine("$indent    android:exported=\"${provider.exported}\"")
        if (!provider.enabled) appendLine("$indent    android:enabled=\"false\"")
        if (provider.grantUriPermissions) appendLine("$indent    android:grantUriPermissions=\"true\"")
        provider.permission?.let { appendLine("$indent    android:permission=\"$it\"") }
        provider.readPermission?.let { appendLine("$indent    android:readPermission=\"$it\"") }
        provider.writePermission?.let { appendLine("$indent    android:writePermission=\"$it\"") }

        if (provider.metaData.isEmpty()) {
            appendLine("$indent    />")
        } else {
            appendLine("$indent    >")
            provider.metaData.forEach { md ->
                append("$indent    <meta-data android:name=\"${md.name}\"")
                md.value?.let { append(" android:value=\"$it\"") }
                md.resource?.let { append(" android:resource=\"$it\"") }
                appendLine(" />")
            }
            appendLine("$indent</provider>")
        }
    }

    /**
     * Write the manifest to a file.
     */
    fun writeTo(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(generate())
        return file
    }

    /**
     * Write the manifest to a directory.
     */
    fun writeToDir(dir: File): File {
        return writeTo(dir.resolve("AndroidManifest.xml"))
    }

    companion object {
        /**
         * Create a basic manifest for a simple app.
         */
        fun forSimpleApp(
            packageName: String,
            mainActivity: String = ".MainActivity",
            appName: String = packageName.substringAfterLast("."),
            minSdk: Int = 24,
            targetSdk: Int = 34
        ): AndroidManifest {
            return AndroidManifest(
                packageName = packageName,
                minSdk = minSdk,
                targetSdk = targetSdk
            ).apply {
                applicationLabel = appName

                addActivity(
                    name = mainActivity,
                    exported = true
                ) {
                    intentFilter {
                        action(Intent.ACTION_MAIN)
                        category(Intent.CATEGORY_LAUNCHER)
                    }
                }
            }
        }
    }
}
