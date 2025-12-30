package com.example.helloios

import platform.UIKit.UIDevice

/**
 * iOS implementation of Platform.
 *
 * Uses UIDevice to get device information.
 */
actual class Platform actual constructor() {
    actual val name: String = UIDevice.currentDevice.let { device ->
        "${device.systemName()} ${device.systemVersion()}"
    }
}
