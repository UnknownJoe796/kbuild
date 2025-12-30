# Kotlin Multiplatform with KBuild

This guide covers building Kotlin Multiplatform projects with KBuild.

## Table of Contents

- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Targets](#targets)
- [Source Sets](#source-sets)
- [Dependencies](#dependencies)
- [Building](#building)
- [Publishing](#publishing)
- [IDE Support](#ide-support)
- [Examples](#examples)

## Quick Start

```kotlin
import com.ivieleague.kbuild.kmp.*
import java.io.File

val project = kmpProject("my-library", File(".")) {
    // Enable targets
    jvm()
    js()
    nativeHost()  // Current platform
}

// Build everything
project.buildAll()
```

## Project Structure

KBuild uses the standard KMP source set layout:

```
my-project/
├── src/
│   ├── commonMain/kotlin/       # Shared production code
│   ├── commonTest/kotlin/       # Shared tests
│   ├── jvmMain/kotlin/          # JVM-specific code
│   ├── jvmTest/kotlin/          # JVM tests
│   ├── jsMain/kotlin/           # JavaScript code
│   ├── jsTest/kotlin/           # JS tests
│   ├── nativeMain/kotlin/       # All native targets
│   ├── appleMain/kotlin/        # Apple platforms (iOS, macOS, etc.)
│   ├── iosMain/kotlin/          # iOS only
│   ├── macosMain/kotlin/        # macOS only
│   ├── linuxMain/kotlin/        # Linux
│   └── mingwMain/kotlin/        # Windows (MinGW)
├── build/
│   ├── classes/kotlin/jvm/      # JVM output
│   ├── libs/js/                 # JS output
│   └── libs/native/             # Native outputs
└── Build.kt
```

## Targets

### Available Targets

```kotlin
val project = kmpProject("my-lib", projectRoot) {
    // JVM
    jvm()

    // JavaScript
    js()

    // WebAssembly
    wasmJs()

    // Native - individual targets
    macosX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Native - convenience methods
    macos()        // macosX64 + macosArm64
    ios()          // iosArm64 + iosSimulatorArm64
    linux()        // linuxX64 + linuxArm64
    nativeHost()   // Current platform

    // Specific native target
    native(KmpTarget.Native.TvosArm64)
}
```

### Target Enum

All targets are defined in `KmpTarget`:

```kotlin
sealed class KmpTarget {
    object Jvm : KmpTarget

    sealed class Js : KmpTarget {
        object Browser : Js
        object Node : Js
    }

    sealed class Wasm : KmpTarget {
        object Js : Wasm
        object Wasi : Wasm
    }

    sealed class Native : KmpTarget {
        // macOS
        object MacosX64 : Native
        object MacosArm64 : Native

        // iOS
        object IosArm64 : Native
        object IosSimulatorArm64 : Native
        object IosX64 : Native

        // watchOS
        object WatchosArm64 : Native
        object WatchosSimulatorArm64 : Native

        // tvOS
        object TvosArm64 : Native
        object TvosSimulatorArm64 : Native

        // Linux
        object LinuxX64 : Native
        object LinuxArm64 : Native

        // Windows
        object MingwX64 : Native

        // Android Native
        object AndroidNativeArm64 : Native
        object AndroidNativeArm32 : Native
        object AndroidNativeX64 : Native
        object AndroidNativeX86 : Native

        companion object {
            fun host(): Native  // Returns target for current platform
        }
    }
}
```

### Target Groups

Target groups help organize related targets:

```kotlin
enum class KmpTargetGroup {
    COMMON,        // All targets
    NATIVE,        // All native targets
    APPLE,         // iOS, macOS, watchOS, tvOS
    MACOS,         // macOS x64 + arm64
    IOS,           // iOS device + simulators
    WATCHOS,       // watchOS device + simulator
    TVOS,          // tvOS device + simulator
    LINUX,         // Linux x64 + arm64
    MINGW,         // Windows
    POSIX,         // All Unix-like (Apple + Linux)
    ANDROID_NATIVE // Android NDK targets
}

// Check if target is in group
if (KmpTargetGroup.APPLE.contains(myTarget)) {
    // Apple-specific logic
}
```

## Source Sets

### Source Set Hierarchy

KBuild implements the standard KMP hierarchy:

```
commonMain
├── jvmMain
├── jsMain
├── wasmJsMain
└── nativeMain
    ├── appleMain
    │   ├── iosMain
    │   │   ├── iosArm64Main
    │   │   ├── iosSimulatorArm64Main
    │   │   └── iosX64Main
    │   ├── macosMain
    │   │   ├── macosX64Main
    │   │   └── macosArm64Main
    │   ├── watchosMain
    │   │   ├── watchosArm64Main
    │   │   └── watchosSimulatorArm64Main
    │   └── tvosMain
    │       ├── tvosArm64Main
    │       └── tvosSimulatorArm64Main
    ├── linuxMain
    │   ├── linuxX64Main
    │   └── linuxArm64Main
    └── mingwMain
        └── mingwX64Main
```

### Accessing Source Sets

```kotlin
val project = kmpProject("my-lib", projectRoot) { /* ... */ }

// Access source sets
val common = project.sourceSets.commonMain
val jvm = project.sourceSets.jvmMain
val native = project.sourceSets.nativeMain
val apple = project.sourceSets.appleMain
val ios = project.sourceSets.iosMain
val macos = project.sourceSets.macosMain

// Get all sources for a target (includes inherited)
val jvmSources = project.getSourcesForTarget(KmpTarget.Jvm)
// Returns: commonMain/kotlin + jvmMain/kotlin

val iosSources = project.getSourcesForTarget(KmpTarget.Native.IosArm64)
// Returns: commonMain + nativeMain + appleMain + iosMain + iosArm64Main
```

### Custom Source Sets

```kotlin
val mySourceSet = sourceSet("myCustomMain", projectRoot) {
    srcDir("src/myCustomMain/kotlin")
    resourceDir("src/myCustomMain/resources")
    dependsOn(project.sourceSets.commonMain)
    dependencies(myDependencies)
}
```

## Dependencies

### Common Dependencies

Dependencies that work across all platforms:

```kotlin
val project = kmpProject("my-lib", projectRoot) {
    jvm()
    js()
    native(KmpTarget.Native.host())

    // Add KMP dependency (auto-resolves correct artifact per target)
    commonDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    commonDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
```

### Platform-Specific Dependencies

```kotlin
import org.apache.maven.model.Dependency

val project = kmpProject("my-lib", projectRoot) {
    jvm()
    js()

    // JVM-only dependency
    dependency(KmpTarget.Jvm, Dependency("com.example:jvm-lib:1.0.0"))

    // JS-only dependency
    dependency(KmpTarget.Js, Dependency("com.example:js-lib:1.0.0"))
}
```

### KMP Dependency Resolution

```kotlin
// Create a KMP dependency
val coroutines = KmpDependency(
    groupId = "org.jetbrains.kotlinx",
    artifactId = "kotlinx-coroutines-core",
    version = "1.7.3"
)

// Resolve for specific target
val jvmLibs = coroutines.resolveForTarget(KmpTarget.Jvm)
// Resolves: kotlinx-coroutines-core-jvm.jar

val nativeLibs = coroutines.resolveForTarget(KmpTarget.Native.MacosArm64)
// Resolves: kotlinx-coroutines-core-macosarm64.klib
```

### Standard Library

```kotlin
// Kotlin stdlib helpers
val jvmStdlib = KotlinStdlib.jvm()
val jsStdlib = KotlinStdlib.js()
// Native stdlib is bundled with compiler

// Test library
val testLib = KotlinTest.common
val junitTest = KotlinTest.jvmJunit5()
```

## Building

### Build All Targets

```kotlin
val results = project.buildAll()
// Returns: Map<KmpTarget, File>

results.forEach { (target, output) ->
    println("${target.name}: $output")
}
```

### Build Specific Targets

```kotlin
// JVM
val jvmOutput: File? = project.buildJvm()
// Output: build/classes/kotlin/jvm/main/

// JavaScript
val jsOutput: File? = project.buildJs()
// Output: build/libs/js/my-lib.klib or .js

// Native
val nativeOutput: File = project.buildNative(KmpTarget.Native.MacosArm64)
// Output: build/libs/macosArm64/my-lib.klib

// All native targets
val allNative = project.buildAllNative()
// Returns: Map<KmpTarget.Native, File>
```

### Build Apple Frameworks

```kotlin
// Dynamic framework
val framework = project.buildFramework(KmpTarget.Native.IosArm64)
// Output: build/frameworks/iosArm64/my-lib.framework

// Static framework
val staticFramework = project.buildFramework(
    target = KmpTarget.Native.IosArm64,
    static = true
)
```

### Access Compilers Directly

```kotlin
// JVM compiler
val jvmCompile = project.jvmCompile
jvmCompile?.invoke()

// JS compiler
val jsCompile = project.jsCompile
jsCompile?.invoke()

// Native compiler for specific target
val nativeCompile = project.getNativeCompile(KmpTarget.Native.LinuxX64)
nativeCompile()

// Framework compiler
val frameworkCompile = project.getFrameworkCompile(KmpTarget.Native.IosArm64)
frameworkCompile()
```

## Publishing

### Publish to Maven

```kotlin
import com.ivieleague.kbuild.common.ProjectIdentifier
import com.ivieleague.kbuild.common.Version

val publish = project.publish(
    projectIdentifier = ProjectIdentifier(
        group = "com.example",
        name = "my-library",
        version = Version(1, 0, 0)
    )
)

// Publish to local Maven repo
publish.publishAll(MavenAether.local)

// Publish to remote repository
val sonatype = RemoteRepository.Builder(
    "sonatype",
    "default",
    "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
).setAuthentication(
    DefaultAuthenticationBuilder()
        .addUsername(System.getenv("SONATYPE_USER"))
        .addPassword(System.getenv("SONATYPE_PASSWORD"))
        .build()
).build()

publish.publishAll(sonatype)
```

### Published Artifacts

For a project named `my-library`, KBuild publishes:

| Artifact | Description |
|----------|-------------|
| `my-library` | Root POM + Gradle Module Metadata |
| `my-library-jvm` | JVM JAR |
| `my-library-js` | JavaScript .klib |
| `my-library-macosarm64` | macOS ARM64 .klib |
| `my-library-iosarm64` | iOS ARM64 .klib |
| ... | Other enabled targets |

### Gradle Module Metadata

KBuild generates `module.json` for proper Gradle variant resolution:

```json
{
  "formatVersion": "1.1",
  "component": {
    "group": "com.example",
    "module": "my-library",
    "version": "1.0.0"
  },
  "variants": [
    {
      "name": "jvmApiElements",
      "attributes": {
        "org.jetbrains.kotlin.platform.type": "jvm"
      },
      "available-at": {
        "group": "com.example",
        "module": "my-library-jvm",
        "version": "1.0.0"
      }
    },
    // ... other variants
  ]
}
```

### GPG Signing

```kotlin
import com.ivieleague.kbuild.maven.GpgSigner

val signer = GpgSigner(
    keyId = System.getenv("GPG_KEY_ID"),
    passphrase = System.getenv("GPG_PASSPHRASE")
)

// Publish with signing
val artifacts = publish.artifacts()
val signedArtifacts = artifacts.signWith(signer)
MavenAether.deploy(repository, signedArtifacts)
```

## IDE Support

### Generate IntelliJ Project

```kotlin
val intellij = project.intellij()
intellij()  // Generates .idea/ and .iml files
```

This creates:
- `.idea/modules.xml` - Module registration
- `.idea/kotlinc.xml` - Kotlin compiler settings
- `.idea/misc.xml` - Project settings
- `my-lib-commonMain.iml` - Module for commonMain
- `my-lib-jvmMain.iml` - Module for jvmMain
- etc.

## Examples

### Library with JVM, JS, and Native

```kotlin
// Build.kt
import com.ivieleague.kbuild.kmp.*
import com.ivieleague.kbuild.common.*
import java.io.File

object MyLibrary {
    val root = File(".")

    val project = kmpProject("my-kmp-lib", root) {
        jvm()
        js()
        macos()
        ios()
        linux()

        commonDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    }

    val publish = project.publish(
        ProjectIdentifier("com.example", "my-kmp-lib", Version(1, 0, 0))
    )

    fun build() = project.buildAll()
    fun publishLocal() = publish.publishAll(MavenAether.local)
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "build" -> MyLibrary.build()
        "publish" -> MyLibrary.publishLocal()
        else -> println("Usage: build | publish")
    }
}
```

### iOS Framework

```kotlin
import com.ivieleague.kbuild.kmp.*
import java.io.File

val project = kmpProject("MyiOSLib", File(".")) {
    iosArm64()
    iosSimulatorArm64()
}

// Build frameworks for both architectures
val deviceFramework = project.buildFramework(KmpTarget.Native.IosArm64)
val simFramework = project.buildFramework(KmpTarget.Native.IosSimulatorArm64)

println("Device framework: $deviceFramework")
println("Simulator framework: $simFramework")
```

### Expect/Actual Pattern

```kotlin
// src/commonMain/kotlin/Platform.kt
expect class Platform() {
    val name: String
}

fun greet(): String = "Hello from ${Platform().name}"

// src/jvmMain/kotlin/Platform.kt
actual class Platform actual constructor() {
    actual val name: String = "JVM"
}

// src/jsMain/kotlin/Platform.kt
actual class Platform actual constructor() {
    actual val name: String = "JavaScript"
}

// src/nativeMain/kotlin/Platform.kt
actual class Platform actual constructor() {
    actual val name: String = "Native"
}
```

**Note:** Expect/actual requires proper KMP compiler configuration. When using `KmpProject`, sources are compiled together per target, which handles expect/actual resolution automatically.
