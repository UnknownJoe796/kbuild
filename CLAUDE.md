# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KBuild is a reactive build **library** for Kotlin. Builds are expressed as reactive data flows using the [Reactive](https://github.com/lightningkite/reactive) library—dependencies track themselves automatically through access patterns, similar to Solid.js.

Target use cases:
- Kotlin JVM server binaries with hot reload
- Continuous builds with file watching
- Integration testing (server + tests together)
- Fine-grained control over the build process

## Build Commands

```bash
./gradlew build           # Build the project
./gradlew test            # Run all tests
./gradlew test --tests "com.ivieleague.kbuild.cli.KBuildCliTest"  # Single test class
./gradlew clean build     # Clean build
```

## Architecture

### Core Reactive Model

The build system uses reactive primitives from `com.lightningkite:reactive`:

- **`Reactive<T>`** — Observable value with automatic dependency tracking
- **`ReactiveContext`** — Tracks accessed reactives, reruns when dependencies change (used via context parameters)
- **`ReactiveState<T>`** — Represents loading/success/error (maps to build status)
- **`Signal<T>`** — Mutable reactive value (used for file system changes)

Build steps use context parameters to work within reactive contexts:

```kotlin
// File watching produces Reactive<Set<File>>
val sources = DirectoryWatch(File("src"), "**/*.kt")

// Compilation functions use context(ctx: ReactiveContext)
context(ctx: ReactiveContext)
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

- **`common/`** — Core abstractions: `Module`, `Library`, `Version`, `TestResult`
  - `Configurer<T>` = `T.() -> Unit` — Lambda configuration pattern

- **`kotlin/`** — Kotlin compilation via embedded compiler
  - `kotlinJvmCompile()` — Incremental JVM compilation using K2 with context parameters
  - `kotlinJsCompile()` — Two-phase K2 JS compilation (Sources → KLIB → JS) with incremental support
  - `kotlinWithJavaCompile()` — Mixed Kotlin/Java compilation
  - `kspJvmProcess()` / `kspJsProcess()` / `kspNativeProcess()` — KSP2 symbol processing for all platforms

- **`java/`** — Java compilation via javac
  - `javaCompileBlocking()` — Standard Java compilation

- **`jvm/`** — JAR building, JVM execution, manifest handling
  - `jarBuild()` — Create JAR files
  - `JvmExecute` — Run JVM applications
  - `JVM` — Classloader utilities

- **`maven/`** — Dependency resolution and publishing
  - `MavenAether` — Singleton wrapping Eclipse Aether, caches to `~/.maven-cache`
  - `PomBuild` — POM generation with scope-aware dependency resolution
  - `MavenDeploy` — Publishing to Maven repositories
  - `GpgSigner` — Artifact signing

- **`native/`** — Kotlin/Native compilation
  - `KonanCompiler` — Downloads and manages Kotlin/Native compiler
  - `KotlinNativeCompile` — Native compilation for all targets
  - `KotlinNativeTestRunner` — Run native tests
  - `CInterop` — C library bindings

- **`intellij/`** — IntelliJ `.idea/` folder generation
  - `IntelliJProjectBuild` — Project-level files
  - `IntelliJModuleBuild` — Module .iml files

- **`junit/`** — JUnit 5 programmatic test execution
  - `junitRun()` — Execute JUnit tests reactively
  - `junitRunBlocking()` — Blocking test execution

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

- **`keychain/`** — Secure credential storage for publishing

- **`cli/`** — Command-line interface
  - `KBuildCli` — Main entry point
  - `BuildRepl` — Interactive REPL mode
  - `BuildDaemon` — Background daemon mode
  - `ExpressionParser/Evaluator` — Dot-notation expression handling

### Key Files

- `KotlinJvmCompile.kt` — Core JVM compilation, wraps `IncrementalJvmCompilerRunner`
- `KotlinJsCompile.kt` — K2 JS compilation with two-phase approach and incremental support
- `KspProcess.kt` — KSP2 symbol processing for JVM, JS, and Native targets
- `MavenAether.kt` — Dependency resolution, isolates Aether complexity
- `DirectoryWatch.kt` — Reactive file watching implementation
- `KBuildCli.kt` — CLI entry point

## Key Implementation Details

### Context Parameters (Kotlin 2.2.0+)

The codebase uses context parameters for reactive compilation:

```kotlin
// Context parameter functions
context(ctx: ReactiveContext)
fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    outputFolder: File
): File
```

Compile with `-Xcontext-parameters` flag.

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

### KSP2 Symbol Processing

KSP2 runs as a **standalone tool** (not a compiler plugin like KSP1). The workflow is:
1. Run KSP to generate code
2. Compile the generated + original sources together

```kotlin
// JVM KSP processing
val generatedSources = kspJvmProcessBlocking(
    name = "my-app",
    sourceRoots = setOf(srcDir),
    classpathJars = dependencies,
    processorClasspath = setOf(roomProcessorJar, moshiProcessorJar),
    processorOptions = mapOf("room.schemaLocation" to "build/schemas"),
    kotlinOutputDir = File("build/ksp/kotlin"),
    javaOutputDir = File("build/ksp/java"),
    resourceOutputDir = File("build/ksp/resources"),
    classOutputDir = File("build/ksp/classes"),
    cacheDir = File("build/ksp/cache")
)

// Then compile with generated sources included
kotlinJvmCompile(
    name = "my-app",
    sourceRoots = constant(setOf(srcDir) + generatedSources),
    classpathJars = dependencies,
    outputFolder = File("build/classes")
)
```

Processors are discovered via `ServiceLoader` from the processor classpath JARs. The functions support:
- **JVM**: `kspJvmProcess()` / `kspJvmProcessBlocking()`
- **JS**: `kspJsProcess()` / `kspJsProcessBlocking()`
- **Native**: `kspNativeProcess()` / `kspNativeProcessBlocking()`

## Design Principles

1. **Library, not framework** — Import and call functions, no special runtime
2. **Explicit over implicit** — Every build step is Ctrl+Clickable
3. **Reactive by default** — Continuous builds are the architecture
4. **Vendor libraries directly** — Thin wrappers over Kotlin compiler, Maven, etc.
5. **No plugins** — Just libraries you import and use

## Code Style

- Follow existing patterns in the codebase
- Prefer context parameter functions over class-based wrappers
- Use `Reactive<Set<File>>` for reactive file collections
- Prefer functions with parameters over DSL/lambda configuration
- Keep wrappers thin—expose vendor APIs where reasonable
- Names should be clear enough that docs aren't needed

## Testing

Tests use actual Kotlin compilation. The test suite covers:
- Maven dependency resolution (`PomBuildTest`)
- File watching (`DirectoryWatchTest`)
- Native compilation (`KotlinNativeCompileTest`, `KotlinNativeTestRunnerTest`)
- KSP symbol processing (`KspProcessTest`)
- CLI functionality (`KBuildCliTest`, `ExpressionParserTest`, `ExpressionEvaluatorTest`)

Run tests with `./gradlew test`. Tests create temporary projects in `build/run/`.
