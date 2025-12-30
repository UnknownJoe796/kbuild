# KBuild

A reactive build library for Kotlin. Not a build system—a library.

## Why?

Gradle plugins are black boxes. When `kotlin("multiplatform")` breaks, you're reading plugin source code to debug it. KBuild makes the build process explicit Kotlin code you can Ctrl+Click through.

**KBuild is for teams who:**
- Build Kotlin Multiplatform libraries and apps
- Run Kotlin JVM servers with hot reload
- Want continuous builds that actually work
- Are tired of Gradle's complexity tax

## Core Concept

Builds are reactive data flows. Dependencies track themselves:

```kotlin
val sources = DirectoryWatch(File("src/main/kotlin"), "**/*.kt")
val compiled = ReactiveKotlinCompile(
    name = "my-app",
    sources = sources,
    classpath = dependencies.asReactive(),
    outputFolder = File("build/classes")
)

compiled.addListener {
    when {
        compiled.state.success -> println("Built: ${compiled.state.getOrNull()}")
        compiled.state.exception != null -> println("Failed: ${compiled.state.exception}")
        else -> println("Building...")
    }
}
```

No explicit task dependencies. No `dependsOn`. The graph builds itself from data access patterns.

## Installation

Not yet published to Maven Central. Clone and build locally:

```bash
git clone https://github.com/user/kbuild
cd kbuild
./gradlew publishToMavenLocal
```

Then in your build script:
```kotlin
@file:DependsOn("com.ivieleague:kbuild:0.0.1")
```

## Quick Examples

### JVM Project

```kotlin
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.maven.*
import java.io.File

val compile = KotlinJvmCompile(
    name = "my-app",
    sourceRoots = { setOf(File("src/main/kotlin")) },
    classpathJars = {
        MavenAether.libraries("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
            .map { it.default }.toSet()
    },
    cache = File("build/cache"),
    outputFolder = File("build/classes")
)

compile() // Returns output directory
```

### Kotlin/JS Project

```kotlin
import com.ivieleague.kbuild.kotlin.*

val compile = KotlinJsCompile(
    name = "my-js-app",
    sourceRoots = { setOf(File("src/main/kotlin")) },
    libraries = { jsStdlib },
    outputMode = JsOutputMode.JS,
    moduleKind = JsModuleKind.ES,
    sourceMap = true,
    outputDir = File("build/js")
)
```

### Kotlin/Native Project

```kotlin
import com.ivieleague.kbuild.native.*

val compile = kotlinNativeExecutable(
    name = "my-native-app",
    sourceRoots = { setOf(File("src/main/kotlin")) },
    target = KonanTarget.host(),
    outputDir = File("build/native")
)
```

### Kotlin Multiplatform Project

```kotlin
import com.ivieleague.kbuild.kmp.*

val project = kmpProject("my-kmp-lib", File(".")) {
    jvm()
    js()
    nativeHost()
    ios()
    macos()
}

// Build all targets
project.buildAll()

// Or specific targets
project.buildJvm()
project.buildJs()
project.buildNative(KmpTarget.Native.IosArm64)
project.buildFramework(KmpTarget.Native.IosArm64)
```

### Server with Hot Reload

```kotlin
import com.ivieleague.kbuild.server.*
import com.ivieleague.kbuild.kotlin.*

val compile = ReactiveKotlinCompile(...)

val server = ReactiveServerProcess(
    name = "my-server",
    classpath = compile,
    mainClass = "com.example.MainKt",
    port = 8080
)

// Server automatically restarts when code changes
server.addListener {
    when (val state = server.serverState) {
        is ServerState.Running -> println("Server running on :${state.port}")
        is ServerState.Failed -> println("Server failed: ${state.exception}")
        else -> {}
    }
}
```

## API Reference

### Compilation

| Class | Description |
|-------|-------------|
| `KotlinJvmCompile` | Compile Kotlin to JVM bytecode |
| `KotlinJsCompile` | Compile Kotlin to JavaScript |
| `KotlinNativeCompile` | Compile Kotlin to native binaries |
| `ReactiveKotlinCompile` | Reactive JVM compilation with file watching |
| `ReactiveKotlinJsCompile` | Reactive JS compilation |
| `ReactiveKotlinNativeCompile` | Reactive native compilation |

### Multiplatform

| Class | Description |
|-------|-------------|
| `KmpProject` | Coordinates multi-target builds |
| `KmpTarget` | Target platforms (JVM, JS, Native variants) |
| `SourceSet` | Source set with dependencies and inheritance |
| `SourceSetHierarchy` | Standard KMP source set structure |
| `KmpDependency` | Multiplatform dependency resolution |

### Dependencies

| Class | Description |
|-------|-------------|
| `MavenAether` | Maven dependency resolution |
| `Dependency()` | Create Maven dependency |
| `KlibDependency()` | Create .klib dependency |
| `KmpDependency` | Multiplatform dependency |

### Testing

| Class | Description |
|-------|-------------|
| `JUnitRun` | Run JUnit tests |
| `ReactiveJUnitRun` | Reactive test execution |
| `ReactiveIntegrationTest` | Coordinate tests with servers |
| `NodeJsTestRunner` | Run JS tests in Node.js |

### Server Management

| Class | Description |
|-------|-------------|
| `ServerProcess` | Manage JVM server process |
| `ReactiveServerProcess` | Auto-restart on code changes |
| `ServerState` | Server state (Running, Stopped, Failed) |

### Publishing

| Class | Description |
|-------|-------------|
| `KmpPublish` | Publish KMP artifacts to Maven |
| `MavenDeploy` | Deploy artifacts to Maven repo |
| `GpgSigner` | Sign artifacts with GPG |
| `PomBuild` | Generate POM files |

### IDE Integration

| Class | Description |
|-------|-------------|
| `IntelliJProjectBuild` | Generate IntelliJ project files |
| `IntelliJModuleBuild` | Generate module .iml files |
| `IntelliJKmpBuild` | IntelliJ support for KMP projects |

### File Watching

| Class | Description |
|-------|-------------|
| `DirectoryWatch` | Watch directory for changes |

## Supported Targets

### JVM
- JVM 17+ (uses K2 compiler)

### JavaScript
- ES Modules, CommonJS, UMD, AMD
- Source maps
- Node.js and Browser

### Native
| Platform | Targets |
|----------|---------|
| macOS | x64, arm64 |
| iOS | arm64, simulator-arm64, x64 |
| watchOS | arm64, simulator-arm64 |
| tvOS | arm64, simulator-arm64 |
| Linux | x64, arm64 |
| Windows | x64 (MinGW) |
| Android | arm64, arm32, x64, x86 |

## Project Structure

```
my-kmp-project/
├── src/
│   ├── commonMain/kotlin/     # Shared code
│   ├── commonTest/kotlin/     # Shared tests
│   ├── jvmMain/kotlin/        # JVM-specific
│   ├── jsMain/kotlin/         # JS-specific
│   ├── nativeMain/kotlin/     # All native
│   ├── appleMain/kotlin/      # Apple platforms
│   ├── iosMain/kotlin/        # iOS
│   └── ...
├── build/
│   ├── classes/
│   ├── libs/
│   └── cache/
└── Build.kt
```

## CLI

```bash
kbuild <command> [options]

Commands:
  build              Build all targets
  build:jvm          Build JVM only
  build:js           Build JS only
  build:native       Build native for host
  test               Run tests
  watch              Watch and rebuild
  publish            Publish to Maven
  intellij           Generate IDE files
  clean              Clean build outputs

Options:
  --project, -p      Project root path
  --verbose, -v      Verbose output
  --release          Optimized build
```

## Examples

See the `samples/` directory:
- `samples/hello-reactive/` - Reactive JVM build
- `samples/hello-js/` - Kotlin/JS project
- `samples/hello-native/` - Kotlin/Native project
- `samples/hello-kmp/` - Kotlin Multiplatform

## Design Principles

1. **Library, not framework** — Import and call functions. No daemon, no magic.

2. **Explicit over implicit** — Every build step is visible Kotlin code.

3. **Reactive by default** — Continuous builds are the architecture, not a feature.

4. **Thin wrappers** — Direct access to Kotlin compiler, Maven resolver. Debug the real APIs.

5. **No plugins** — Want something? Import a library and call it.

## Comparison with Gradle

| Aspect | Gradle | KBuild |
|--------|--------|--------|
| Task dependencies | Explicit `dependsOn` | Implicit from data flow |
| Incremental builds | Complex up-to-date checks | Reactive file watching |
| Plugin behavior | Opaque, global state | Functions you call |
| IDE support | Requires Gradle plugin | Standard Kotlin |
| Configuration | DSL compiles to model | Plain Kotlin objects |
| Debugging | Read plugin source | Ctrl+Click your code |

## Current Status

All phases complete:

- ✅ Reactive Foundation (file watching, reactive compilation)
- ✅ JVM Development (testing, server management, integration tests)
- ✅ Kotlin/JS (K2 IR backend, npm integration, Node.js testing)
- ✅ Kotlin/Native (cross-compilation, CInterop, frameworks)
- ✅ Multiplatform (source sets, dependency resolution, coordinated builds)
- ✅ Publishing & Tooling (Maven, GPG signing, IntelliJ, CLI)

## License

MIT License - see [LICENSE.txt](LICENSE.txt)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
