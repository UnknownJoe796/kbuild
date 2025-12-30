package com.example

fun main() {
    println("Hello from Kotlin/Native!")
    println("Platform: ${Platform.osFamily}")
    println("Architecture: ${Platform.cpuArchitecture}")
}

// Platform detection for Kotlin/Native
object Platform {
    val osFamily: String
        get() = when {
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX -> "macOS"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.LINUX -> "Linux"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.WINDOWS -> "Windows"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.IOS -> "iOS"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.ANDROID -> "Android"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.TVOS -> "tvOS"
            kotlin.native.Platform.osFamily == kotlin.native.OsFamily.WATCHOS -> "watchOS"
            else -> "Unknown"
        }

    val cpuArchitecture: String
        get() = when {
            kotlin.native.Platform.cpuArchitecture == kotlin.native.CpuArchitecture.ARM64 -> "arm64"
            kotlin.native.Platform.cpuArchitecture == kotlin.native.CpuArchitecture.X64 -> "x64"
            kotlin.native.Platform.cpuArchitecture == kotlin.native.CpuArchitecture.X86 -> "x86"
            kotlin.native.Platform.cpuArchitecture == kotlin.native.CpuArchitecture.ARM32 -> "arm32"
            else -> "Unknown"
        }
}
