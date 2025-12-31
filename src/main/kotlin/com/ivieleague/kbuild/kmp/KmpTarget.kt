package com.ivieleague.kbuild.kmp

import com.ivieleague.kbuild.native.KonanTarget
import com.ivieleague.kbuild.native.TargetFamily

/**
 * Represents a Kotlin Multiplatform compilation target.
 *
 * Each target corresponds to a specific platform that Kotlin can compile to.
 * Targets are organized hierarchically for source set sharing.
 */
sealed class KmpTarget(
    val name: String,
    val sourceSetName: String = "${name}Main"
) {
    /** JVM target using JDK */
    data object Jvm : KmpTarget("jvm")

    /** JavaScript target */
    sealed class Js(name: String) : KmpTarget(name) {
        data object Browser : Js("jsBrowser")
        data object Node : Js("jsNode")

        companion object : Js("js")
    }

    /** WebAssembly target */
    sealed class Wasm(name: String) : KmpTarget(name) {
        data object Js : Wasm("wasmJs")
        data object Wasi : Wasm("wasmWasi")

        companion object : Wasm("wasm")
    }

    /** Native targets */
    sealed class Native(
        name: String,
        val konanTarget: KonanTarget
    ) : KmpTarget(name) {

        // macOS
        data object MacosX64 : Native("macosX64", KonanTarget.MACOS_X64)
        data object MacosArm64 : Native("macosArm64", KonanTarget.MACOS_ARM64)

        // iOS
        data object IosArm64 : Native("iosArm64", KonanTarget.IOS_ARM64)
        data object IosSimulatorArm64 : Native("iosSimulatorArm64", KonanTarget.IOS_SIMULATOR_ARM64)
        data object IosX64 : Native("iosX64", KonanTarget.IOS_X64)

        // watchOS
        data object WatchosArm64 : Native("watchosArm64", KonanTarget.WATCHOS_ARM64)
        data object WatchosSimulatorArm64 : Native("watchosSimulatorArm64", KonanTarget.WATCHOS_SIMULATOR_ARM64)

        // tvOS
        data object TvosArm64 : Native("tvosArm64", KonanTarget.TVOS_ARM64)
        data object TvosSimulatorArm64 : Native("tvosSimulatorArm64", KonanTarget.TVOS_SIMULATOR_ARM64)

        // Linux
        data object LinuxX64 : Native("linuxX64", KonanTarget.LINUX_X64)
        data object LinuxArm64 : Native("linuxArm64", KonanTarget.LINUX_ARM64)

        // Windows
        data object MingwX64 : Native("mingwX64", KonanTarget.MINGW_X64)

        // Android Native
        data object AndroidNativeArm64 : Native("androidNativeArm64", KonanTarget.ANDROID_ARM64)
        data object AndroidNativeArm32 : Native("androidNativeArm32", KonanTarget.ANDROID_ARM32)
        data object AndroidNativeX64 : Native("androidNativeX64", KonanTarget.ANDROID_X64)
        data object AndroidNativeX86 : Native("androidNativeX86", KonanTarget.ANDROID_X86)

        fun isIosTarget(): Boolean = this in listOf(IosArm64, IosSimulatorArm64, IosX64)
        fun isMacosTarget(): Boolean = this in listOf(MacosX64, MacosArm64)
        fun isAppleTarget(): Boolean = konanTarget.family in listOf(
            TargetFamily.OSX, TargetFamily.IOS, TargetFamily.WATCHOS, TargetFamily.TVOS
        )

        companion object {
            val all: List<Native> by lazy {
                listOf(
                    MacosX64, MacosArm64,
                    IosArm64, IosSimulatorArm64, IosX64,
                    WatchosArm64, WatchosSimulatorArm64,
                    TvosArm64, TvosSimulatorArm64,
                    LinuxX64, LinuxArm64,
                    MingwX64,
                    AndroidNativeArm64, AndroidNativeArm32, AndroidNativeX64, AndroidNativeX86
                )
            }

            fun fromKonanTarget(target: KonanTarget): Native? =
                all.find { it.konanTarget == target }

            /** Get the native target for the current host */
            fun host(): Native = fromKonanTarget(KonanTarget.host())
                ?: throw IllegalStateException("No KmpTarget.Native for host: ${KonanTarget.host()}")
        }
    }

    companion object {
        val all: List<KmpTarget> by lazy { listOf(Jvm, Js, Wasm.Js, Wasm.Wasi) + Native.all }
    }
}

/**
 * Target groups for hierarchical source sets.
 *
 * These represent intermediate source sets that compile to multiple targets.
 * For example, `apple` sources compile to all Apple platforms (iOS, macOS, watchOS, tvOS).
 */
enum class KmpTargetGroup(
    val sourceSetName: String,
    val targets: Set<KmpTarget>
) {
    /** Common code shared by all targets */
    COMMON("common", KmpTarget.all.toSet()),

    /** All native targets */
    NATIVE("native", KmpTarget.Native.all.toSet()),

    /** All Apple targets (iOS, macOS, watchOS, tvOS) */
    APPLE("apple", setOf(
        KmpTarget.Native.MacosX64, KmpTarget.Native.MacosArm64,
        KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64, KmpTarget.Native.IosX64,
        KmpTarget.Native.WatchosArm64, KmpTarget.Native.WatchosSimulatorArm64,
        KmpTarget.Native.TvosArm64, KmpTarget.Native.TvosSimulatorArm64
    )),

    /** macOS targets */
    MACOS("macos", setOf(KmpTarget.Native.MacosX64, KmpTarget.Native.MacosArm64)),

    /** iOS targets (device and simulator) */
    IOS("ios", setOf(
        KmpTarget.Native.IosArm64,
        KmpTarget.Native.IosSimulatorArm64,
        KmpTarget.Native.IosX64
    )),

    /** watchOS targets */
    WATCHOS("watchos", setOf(
        KmpTarget.Native.WatchosArm64,
        KmpTarget.Native.WatchosSimulatorArm64
    )),

    /** tvOS targets */
    TVOS("tvos", setOf(
        KmpTarget.Native.TvosArm64,
        KmpTarget.Native.TvosSimulatorArm64
    )),

    /** Linux targets */
    LINUX("linux", setOf(KmpTarget.Native.LinuxX64, KmpTarget.Native.LinuxArm64)),

    /** Windows targets (MinGW) */
    MINGW("mingw", setOf(KmpTarget.Native.MingwX64)),

    /** POSIX-compatible targets (all Unix-like) */
    POSIX("posix", setOf(
        KmpTarget.Native.MacosX64, KmpTarget.Native.MacosArm64,
        KmpTarget.Native.IosArm64, KmpTarget.Native.IosSimulatorArm64, KmpTarget.Native.IosX64,
        KmpTarget.Native.WatchosArm64, KmpTarget.Native.WatchosSimulatorArm64,
        KmpTarget.Native.TvosArm64, KmpTarget.Native.TvosSimulatorArm64,
        KmpTarget.Native.LinuxX64, KmpTarget.Native.LinuxArm64,
        KmpTarget.Native.AndroidNativeArm64, KmpTarget.Native.AndroidNativeArm32,
        KmpTarget.Native.AndroidNativeX64, KmpTarget.Native.AndroidNativeX86
    )),

    /** Android Native targets */
    ANDROID_NATIVE("androidNative", setOf(
        KmpTarget.Native.AndroidNativeArm64,
        KmpTarget.Native.AndroidNativeArm32,
        KmpTarget.Native.AndroidNativeX64,
        KmpTarget.Native.AndroidNativeX86
    ));

    fun contains(target: KmpTarget): Boolean = target in targets
}
