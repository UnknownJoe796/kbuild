# Hello Android

A sample Android application demonstrating Kotlin to APK compilation without Gradle.

## Features

- Kotlin/JVM compiled to DEX bytecode
- Android resources (layouts, strings, styles)
- APK signing with debug keystore
- Device installation and running via ADB

## Prerequisites

1. **Android SDK** - Set `ANDROID_HOME` environment variable
   - Requires: build-tools, platform-tools, platforms/android-34

2. **Device or Emulator** - For installation and running
   - Connect a device via USB (enable USB debugging)
   - Or start an Android emulator

## Quick Start

### 1. Scaffold the project

```bash
./Build.kt scaffold
```

This creates:
- `android/` - Android project directory
  - `AndroidManifest.xml` - App manifest
  - `res/` - Resources (layouts, strings, styles)
- `src/jvmMain/kotlin/` - Kotlin source code
  - `MainActivity.kt` - Main activity

### 2. Build the APK

```bash
./Build.kt build
```

This runs the build pipeline:
1. Compile Kotlin to .class files
2. Compile resources with aapt2
3. Link resources into base APK
4. Convert .class to DEX with d8
5. Add DEX to APK
6. Align with zipalign
7. Sign with debug keystore

Output: `build/outputs/apk/hello-android-debug.apk`

### 3. Install on device

```bash
./Build.kt install
```

### 4. Run the app

```bash
./Build.kt run
```

Or do all at once:

```bash
./Build.kt build-run
```

## Project Structure

```
hello-android/
├── Build.kt                    # Build script
├── README.md
├── src/
│   └── jvmMain/
│       └── kotlin/
│           └── com/example/helloandroid/
│               └── MainActivity.kt
├── android/
│   ├── AndroidManifest.xml
│   └── res/
│       ├── layout/
│       │   └── activity_main.xml
│       └── values/
│           ├── strings.xml
│           └── styles.xml
└── build/
    ├── classes/                # Compiled .class files
    ├── intermediates/android/  # Build intermediates
    │   ├── flat/               # Compiled resources
    │   ├── dex/                # DEX files
    │   └── base.apk            # Before signing
    └── outputs/apk/
        ├── hello-android-aligned.apk
        └── hello-android-debug.apk
```

## Commands

| Command | Description |
|---------|-------------|
| `scaffold` | Create project structure |
| `build` | Build the APK |
| `install` | Install APK on device |
| `run` | Launch the app |
| `build-run` | Build, install, and run |
| `devices` | List connected devices |
| `emulators` | List available emulators |
| `logcat [n]` | View last n log lines (default: 100) |
| `stop` | Stop the running app |
| `clear` | Clear app data |
| `clean` | Remove build artifacts |

## How It Works

### Build Pipeline

```
Kotlin source (.kt)
        ↓
   KotlinJvmCompile
        ↓
JVM bytecode (.class)
        ↓
      d8 (DEX compiler)
        ↓
Dalvik bytecode (.dex)
        ↓
   +   Resources (aapt2)
        ↓
      APK (unsigned)
        ↓
    zipalign
        ↓
    apksigner
        ↓
   APK (signed, ready to install)
```

### No Gradle Required

This sample builds directly using Android SDK tools:
- **aapt2** - Android Asset Packaging Tool (resources)
- **d8** - DEX compiler (bytecode conversion)
- **zipalign** - APK alignment
- **apksigner** - APK signing
- **adb** - Android Debug Bridge (device communication)

## API

```kotlin
// Create Android project
val android = kmpProject.androidProject(
    packageName = "com.example.myapp",
    minSdk = 24,
    targetSdk = 34
)

// Scaffold project structure
android.scaffold(appName = "My App")

// Build APK
val result = android.build()
println("APK: ${result.apk}")

// Device operations
android.install()
android.run()
android.stop()
android.clearData()
android.logcat(100)
```

## Limitations

- Debug builds only (release signing requires additional keystore setup)
- No ProGuard/R8 minification
- No multidex (single DEX file)
- No build variants or product flavors
- Basic resource support (no data binding, view binding)

These can be added as needed for more complex projects.
