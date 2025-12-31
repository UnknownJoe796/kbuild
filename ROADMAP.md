# KBuild Development Roadmap

## Overview

KBuild is a reactive build library for Kotlin, providing:

- Reactive compilation with context parameters
- Incremental builds for JVM and JS
- Native compilation for all Kotlin/Native targets
- Maven dependency resolution and publishing
- Interactive CLI with REPL and daemon modes

## Completed Phases

### Phase 1: Reactive Foundation ✅

- Added `com.lightningkite:reactive-jvm` dependency
- Created `DirectoryWatch` class implementing `Reactive<Set<File>>` via `BaseReactiveValue`
- Uses `java.nio.file.WatchService` with recursive directory registration
- Supports glob patterns for filtering (e.g., `**/*.kt`)
- Debouncing with configurable delay (default 100ms)

### Phase 2: JVM Compilation ✅

- `kotlinJvmCompile()` - Context parameter function for reactive JVM compilation
- `kotlinJvmCompileBlocking()` - Blocking compilation
- Incremental compilation via `IncrementalJvmCompilerRunner`
- JUnit 5 test execution via `junitRun()` and `junitRunBlocking()`
- Server process management via `ServerProcess`
- Mixed Kotlin/Java compilation via `kotlinWithJavaCompile()`

### Phase 3: Kotlin/JS ✅

- K2 two-phase compilation: Sources → KLIB → JS
- `kotlinJsCompile()` - Context parameter function for reactive JS compilation
- `kotlinJsCompileBlocking()` - Blocking compilation
- Incremental compilation via `makeJsIncrementally()`
- Multiple module kinds: ES, CommonJS, UMD, AMD, plain
- Source maps support
- `BrowserTestRunner` for browser-based testing
- `NodeJsTestRunner` for Node.js testing
- npm integration (`NpmDependency`, package.json generation)

### Phase 4: Kotlin/Native ✅

- `KonanCompiler` - Downloads and manages Kotlin/Native compiler
- `KotlinNativeCompile` - Native compilation for all targets
- Multiple output kinds: Executable, Static library, Dynamic library, KLIB, Framework
- `CInterop` for C library bindings
- `KotlinNativeTestRunner` for running native tests

### Phase 5: Tooling ✅

- Maven dependency resolution via `MavenAether`
- Maven publishing via `MavenDeploy`
- `GpgSigner` for signing artifacts
- `PomBuild` for POM generation
- `IntelliJProjectBuild`, `IntelliJModuleBuild` for IDE support
- `KBuildCli` for command-line interface
- Interactive REPL mode
- Background daemon mode for fast repeated builds

---

## In Development

### Phase 6: KMP Coordination 🚧

- Standard source set layout (`commonMain`, `commonTest`, `jvmMain`, etc.)
- `SourceSet` model with dependencies, source directories, and targets
- `SourceSetHierarchy` with full KMP hierarchy
- `KmpProject` for coordinated multi-target builds
- `KmpTarget` sealed class for all platform targets
- `KmpDependency` for multiplatform dependency resolution

---

## Architecture Notes

### Context Parameters (Kotlin 2.2.0+)

Build functions use Kotlin context parameters for reactive integration:

```kotlin
context(ctx: ReactiveContext)
fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,
    classpathJars: Reactive<Set<File>>,
    outputFolder: File
): File
```

This allows functions to automatically subscribe to reactive inputs and re-execute when they change.

Compile with `-Xcontext-parameters` flag.

### K2 JS Compilation

The K2 compiler requires two-phase compilation for JavaScript:
1. Sources → KLIB (incremental, cached)
2. KLIB → JS (linking)

Incremental compilation is supported for the KLIB phase using `makeJsIncrementally()`.

---

## Future Work

### Real-World Validation
- [ ] Test with full KiteUI project
- [ ] Test with Lightning Server project
- [ ] Benchmark against Gradle for real projects

### Bootstrap
- [ ] Self-hosting: Build KBuild using KBuild
- [ ] Create `kbuild.kt` script that builds the project

### Optimizations
- [ ] Parallel target compilation
- [ ] Remote build cache
- [ ] Memory optimization for multiple compiler instances

---

## Open Questions

1. **Bootstrap**: When to switch from Gradle to self-hosting?

2. **Parallel compilation**: Should multiple targets compile in parallel?
   - Reactive model supports this naturally
   - Need to manage memory (multiple compiler instances)

3. **Remote build cache**: Worth implementing?
   - Could wrap `Reactive<T>` with cache check
   - Lower priority than core functionality

4. **Gradle interop**: Should KBuild consume Gradle projects?
   - Focus on replacement, not integration
