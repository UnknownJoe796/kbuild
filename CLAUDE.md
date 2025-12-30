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
- **`ReactiveContext`** — Tracks accessed reactives, reruns when dependencies change
- **`ReactiveState<T>`** — Represents loading/success/error (maps to build status)
- **`Signal<T>`** — Mutable reactive value (used for file system changes)

Build steps become reactive contexts that automatically rebuild when inputs change:

```kotlin
val sources = watchDirectory(File("src"), "**/*.kt")  // Reactive<Set<File>>
val compiled = reactive {
    compile(sources())  // Subscribes automatically, rebuilds on file change
}
```

### Package Structure

- **`common/`** — Core abstractions: `Producer`, `Module`, `Library`, `Version`
  - `Producer<T>` = `() -> Set<T>` — Being migrated to `Reactive<Set<T>>`
  - `Configurer<T>` = `T.() -> Unit` — Lambda configuration pattern

- **`kotlin/`** — Kotlin compilation via embedded compiler
  - `KotlinJvmCompile` — Incremental JVM compilation using K2
  - `KotlinWithJavaCompile` — Mixed Kotlin/Java compilation

- **`java/`** — Java compilation

- **`jvm/`** — JAR building (`JarBuild`), JVM execution, manifest handling

- **`maven/`** — Dependency resolution and publishing
  - `MavenAether` — Singleton wrapping Eclipse Aether, caches to `~/.maven-cache`
  - `PomBuild` — POM generation with scope-aware dependency resolution
  - `MavenDeploy` — Publishing to Maven repositories

- **`intellij/`** — IntelliJ `.idea/` folder generation

- **`junit/`** — JUnit 5 programmatic test execution

- **`keychain/`** — Secure credential storage for publishing

- **`templates/`** — Pre-built project templates (e.g., `KotlinJvmLibraryTemplate`)

### Key Files

- `SelfBuildTest.kt` — KBuild building itself, demonstrates full API usage
- `KotlinJvmCompile.kt` — Core compilation, wraps `IncrementalJvmCompilerRunner`
- `MavenAether.kt` — Dependency resolution, isolates Aether complexity
- `Producer.kt` — Core `Producer<T>` type and composition operators

## Development Roadmap

### Phase 1: Reactive Foundation
1. Add `com.lightningkite:reactive` as dependency
2. Create `DirectoryWatch: Reactive<Set<File>>` using file watcher
3. Adapt `KotlinJvmCompile` to work within reactive contexts
4. Prove continuous build works for single JVM module

### Phase 2: Full JVM Stack
5. Reactive test execution (JUnit reruns on code change)
6. Server process management (start/stop/restart on rebuild)
7. Integration test coordination (wait for server, run tests)

### Phase 3: Kotlin/JS
8. `KotlinJsCompile` using K2 compiler with JS backend
9. Node modules / npm dependency handling

### Phase 4: Kotlin/Native
10. Konan compiler download and management
11. `KotlinNativeCompile` for various targets

### Phase 5: KMP Coordination
12. Standard source set structure (`commonMain`, `jvmMain`, `jsMain`, etc.)
13. expect/actual resolution across source sets
14. Platform-specific dependency handling

## Design Principles

1. **Library, not framework** — Import and call functions, no special runtime
2. **Explicit over implicit** — Every build step is Ctrl+Clickable
3. **Reactive by default** — Continuous builds are the architecture
4. **Vendor libraries directly** — Thin wrappers over Kotlin compiler, Maven, etc.
5. **No plugins** — Just libraries you import and use

## Code Style

- Follow existing patterns in the codebase
- Use `Producer<T>` for lazy collections (migrating to `Reactive<Set<T>>`)
- Prefer functions with parameters over DSL/lambda configuration
- Keep wrappers thin—expose vendor APIs where reasonable
- Names should be clear enough that docs aren't needed
