package com.example

actual class Platform actual constructor() {
    actual val name: String = buildString {
        append("Native/")
        append(
            when (kotlin.native.Platform.osFamily) {
                kotlin.native.OsFamily.MACOSX -> "macOS"
                kotlin.native.OsFamily.LINUX -> "Linux"
                kotlin.native.OsFamily.WINDOWS -> "Windows"
                kotlin.native.OsFamily.IOS -> "iOS"
                kotlin.native.OsFamily.ANDROID -> "Android"
                kotlin.native.OsFamily.TVOS -> "tvOS"
                kotlin.native.OsFamily.WATCHOS -> "watchOS"
                else -> "Unknown"
            }
        )
        append(" ")
        append(
            when (kotlin.native.Platform.cpuArchitecture) {
                kotlin.native.CpuArchitecture.ARM64 -> "arm64"
                kotlin.native.CpuArchitecture.X64 -> "x64"
                kotlin.native.CpuArchitecture.X86 -> "x86"
                kotlin.native.CpuArchitecture.ARM32 -> "arm32"
                else -> "Unknown"
            }
        )
    }
}

fun main() {
    println(Greeting().greet())
}
