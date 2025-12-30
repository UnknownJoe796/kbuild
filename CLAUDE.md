# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KBuild is a reactive build **library** for Kotlin. Builds are expressed as reactive data flows using the [Reactive](https://github.com/lightningkite/reactive) library—dependencies track themselves automatically through access patterns, similar to Solid.js.

Target use cases:
- Kotlin Multiplatform libraries (common + JVM + JS + Native)
- Kotlin JVM server binaries
- Continuous builds with file watching
- Integration testing (server + tests together)

## Build Commands

```bash
./gradlew build           # Build the project
./gradlew test            # Run all tests
./gradlew test --tests "com.ivieleague.kbuild.SelfBuildTest"  # Single test class
./gradlew test --tests "*.SelfBuildTest.build"                # Single test method
./gradlew clean build     # Clean build
```

## Architecture

### Core Reactive Model

The build system uses reactive primitives from `com.lightningkite:reactive`:

- **`Reactive<T>`** — Observable value with automatic dependency tracking
- **`ReactiveContext`** — Tracks accessed reactives, reruns when dependencies change (used via context receivers)
- **`ReactiveState<T>`** — Represents loading/success/error (maps to build status)
- **`Signal<T>`** — Mutable reactive value (used for file system changes)

Build steps use context receivers to work within reactive contexts:

```kotlin
// File watching produces Reactive<Set<File>>
val sources = DirectoryWatch(File("src"), "**/*.kt")

// Compilation functions use context(ReactiveContext)
context(ReactiveContext)
fun buildMyProject(): File {
    return kotlinJvmCompile(
        name = "my-app",
        sourceRoots = sources,
        classpathJars = dependencies,
        outputFolder = File("build/classes")
    )
}
```

### Package Structure

- **`common/`** — Core abstractions: `Producer`, `Module`, `Library`, `Version`
  - `Producer<T>` = `() -> Set<T>` — Lazy collection evaluation
  - `Configurer<T>` = `T.() -> Unit` — Lambda configuration pattern

- **`kotlin/`** — Kotlin compilation via embedded compiler
  - `kotlinJvmCompile()` — Incremental JVM compilation using K2 with context receivers
  - `kotlinJsCompile()` — Two-phase K2 JS compilation (Sources → KLIB → JS) with incremental support
  - `KotlinJvmCompile`, `KotlinJsCompile` — Legacy class-based API (deprecated)
  - `KotlinWithJavaCompile` — Mixed Kotlin/Java compilation

- **`java/`** — Java compilation via javac

- **`jvm/`** — JAR building (`JarBuild`), JVM execution, manifest handling

- **`maven/`** — Dependency resolution and publishing
  - `MavenAether` — Singleton wrapping Eclipse Aether, caches to `~/.maven-cache`
  - `PomBuild` — POM generation with scope-aware dependency resolution
  - `MavenDeploy` — Publishing to Maven repositories
  - `GpgSigner` — Artifact signing

- **`native/`** — Kotlin/Native compilation
  - `KonanCompiler` — Downloads and manages Kotlin/Native compiler
  - `KotlinNativeCompile` — Native compilation for all targets
  - `CInterop` — C library bindings

- **`kmp/`** — Kotlin Multiplatform coordination
  - `KmpProject` — Multi-target project builder
  - `KmpTarget` — Platform target definitions
  - `SourceSet`, `SourceSetHierarchy` — Standard KMP source set structure
  - `KmpDependency` — Multiplatform dependency resolution
  - `KmpPublish` — Multi-artifact publishing

- **`intellij/`** — IntelliJ `.idea/` folder generation
  - `IntelliJProjectBuild` — Project-level files
  - `IntelliJModuleBuild` — Module .iml files
  - `IntelliJKmpBuild` — KMP-specific support

- **`junit/`** — JUnit 5 programmatic test execution
  - `JUnitRun` — Execute JUnit tests, capture results

- **`watch/`** — File system watching
  - `DirectoryWatch` — Reactive file watching with glob patterns

- **`server/`** — Server process management
  - `ServerProcess` — Manage JVM server lifecycle

- **`browser/`** — Browser test execution
  - `BrowserTestRunner` — Run JS tests in browser environment

- **`nodejs/`** — Node.js integration
  - `NodeJsTestRunner` — Run JS tests in Node.js

- **`npm/`** — npm integration
  - `NpmDependency`, package.json generation

- **`vite/`** — Vite dev server integration

- **`ios/`** — iOS build support
  - `XCFramework`, `SwiftPackage`, `IosPodspec`

- **`android/`** — Android build support
  - `AndroidSdk`, `ApkBuilder`, `AndroidProject`

- **`keychain/`** — Secure credential storage for publishing

- **`cli/`** — Command-line interface (`KBuildCli`)

### Key Files

- `SelfBuildTest.kt` — KBuild building itself, demonstrates full API usage
- `KotlinJvmCompile.kt` — Core JVM compilation, wraps `IncrementalJvmCompilerRunner`
- `KotlinJsCompile.kt` — K2 JS compilation with two-phase approach and incremental support
- `MavenAether.kt` — Dependency resolution, isolates Aether complexity
- `KmpProject.kt` — Multi-target build coordination

## Key Implementation Details

### K2 Kotlin/JS Compilation

The K2 compiler requires a two-phase approach for JS output:
1. **Phase 1**: Sources → KLIB (intermediate representation)
2. **Phase 2**: KLIB → JS (linking)

This is handled automatically by `kotlinJsCompile()` when `outputMode = JsOutputMode.JS`.

### Incremental Compilation

Both JVM and JS compilation support incremental builds:

```kotlin
// JVM incremental (uses IncrementalJvmCompilerRunner)
kotlinJvmCompile(
    name = "my-app",
    sourceRoots = sources,
    cache = File("build/cache"),  // Enable incremental
    outputFolder = File("build/classes")
)

// JS incremental (uses makeJsIncrementally)
kotlinJsCompileBlocking(
    name = "my-js-app",
    sourceRoots = sources,
    cache = File("build/js-cache"),  // Enable incremental
    outputDir = File("build/js")
)
```

### Context Receivers vs Legacy Classes

The codebase uses context receivers for reactive compilation:

```kotlin
// Preferred: Context receiver functions
context(ReactiveContext)
fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    outputFolder: File
): File

// Legacy: Class-based API (deprecated, for backwards compatibility)
@Deprecated("Use kotlinJvmCompile function with ReactiveContext instead")
class KotlinJvmCompile(...) : () -> File
```

## Design Principles

1. **Library, not framework** — Import and call functions, no special runtime
2. **Explicit over implicit** — Every build step is Ctrl+Clickable
3. **Reactive by default** — Continuous builds are the architecture
4. **Vendor libraries directly** — Thin wrappers over Kotlin compiler, Maven, etc.
5. **No plugins** — Just libraries you import and use

## Code Style

- Follow existing patterns in the codebase
- Prefer context receiver functions over class-based wrappers
- Use `Reactive<Set<File>>` for reactive file collections
- Prefer functions with parameters over DSL/lambda configuration
- Keep wrappers thin—expose vendor APIs where reasonable
- Names should be clear enough that docs aren't needed

## Testing

Tests use actual Kotlin compilation. The test suite covers:
- JVM compilation (`KotlinJvmCompile`)
- JS compilation (`KotlinJsCompile`) with ES modules, CommonJS, and KLIB output
- Native compilation (`KotlinNativeCompile`)
- Browser and Node.js test runners
- Maven dependency resolution
- Incremental compilation scenarios

Run tests with `./gradlew test`. Tests create temporary projects in `build/run/`.
