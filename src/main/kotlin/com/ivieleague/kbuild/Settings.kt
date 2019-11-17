package com.ivieleague.kbuild

object Settings {
    enum class OutputLevel {
        Debug,
        Normal,
        Minimal
    }

    var outputLevel = OutputLevel.Normal
}