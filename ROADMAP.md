# KBuild Development Roadmap

## Phase 1: Reactive Foundation ✅ COMPLETE

**Goal:** Prove the reactive build concept works with a single Kotlin JVM module.

### 1.1 Add Reactive Dependency ✅
- [x] Add `com.lightningkite:reactive-jvm:5.1.3-24` to `build.gradle.kts`
- [x] Using published version from Lightning Kite S3 Maven repo

### 1.2 File Watching ✅
- [x] Created `DirectoryWatch` class implementing `Reactive<Set<File>>` via `BaseReactiveValue`
- [x] Uses `java.nio.file.WatchService` with recursive directory registration
- [x] Supports glob patterns for filtering (e.g., `**/*.kt`)
- [x] Debouncing with configurable delay (default 100ms)
- [x] Detects file modifications via lastModified timestamps
- [x] Test: `DirectoryWatchTest` verifies file change detection

### 1.3 Reactive Compilation ✅
- [x] Created `ReactiveKotlinCompile` that wraps `KotlinJvmCompile`
- [x] Returns `Reactive<File>` (output directory) via `BaseReactive`
- [x] States: notReady (compiling), Success (compiled), exception (failed)
- [x] Subscribes to source and classpath changes, auto-recompiles
- [x] Test: `ReactiveKotlinCompileTest` verifies reactive behavior

### 1.4 End-to-End JVM Build ✅
- [x] Created `samples/hello-reactive/` example project
- [x] `Build.kt` wires: DirectoryWatch → ReactiveKotlinCompile
- [x] `HelloReactiveTest` integration tests verify end-to-end flow
- [ ] Benchmark: TODO - compare rebuild time with Gradle

---

## Phase 2: Full JVM Development Stack ✅ COMPLETE

**Goal:** Support the full development loop for Kotlin JVM projects.

### 2.1 Reactive Testing ✅
- [x] Created `ReactiveJUnitRun` that wraps `JUnitRun` with reactive capabilities
- [x] Rerun tests when test sources OR main sources change
- [x] Filter test runs (single test class/method) via `filter` parameter
- [x] Stream test results as they complete via `onTestComplete` callback
- [x] Test: `ReactiveJUnitRunTest` verifies reactive behavior and filtering

### 2.2 Server Process Management ✅
- [x] Created `ServerProcess` class that manages a JVM process
- [x] Start/stop/restart based on classpath changes
- [x] Health check support (wait for server ready before proceeding)
- [x] Graceful shutdown on rebuild (SIGTERM, wait, SIGKILL)
- [x] Port management (detect port conflicts via `isPortAvailable`)
- [x] Created `ReactiveServerProcess` for auto-restart on code changes
- [x] ServerState sealed class: Stopped, Starting, Running, Restarting, Failed

### 2.3 Integration Test Coordination ✅
- [x] Created `ReactiveIntegrationTest` for coordinating tests with servers
- [x] Tests wait for server ready before running
- [x] Automatic test rerun when server restarts
- [x] Support multiple servers (e.g., API + worker) via `servers` map
- [x] Test: `ReactiveIntegrationTestTest` verifies coordination logic

---

## Phase 3: Kotlin/JS ✅ COMPLETE

**Goal:** Compile Kotlin to JavaScript, integrate with npm ecosystem.

### 3.1 Kotlin/JS Compiler ✅
- [x] Created `KotlinJsCompile` using K2 compiler with IR backend
- [x] Support IR backend (legacy is deprecated)
- [x] Output: `.mjs` (ES modules) or `.js` (CommonJS) files
- [x] Source maps for debugging via `sourceMap` parameter
- [x] Created `ReactiveKotlinJsCompile` for file-watching compilation
- [x] Added `KlibDependency` helper for resolving .klib artifacts
- [x] Support for multiple module kinds: ES, CommonJS, UMD, AMD, plain
- [x] Test: `KotlinJsCompileTest` verifies compilation

### 3.2 npm Integration ✅
- [x] Created `NpmDependency` for declaring npm dependencies
- [x] Created `PackageJson` class for generating package.json
- [x] Created `NpmProject` for managing npm projects (install, scripts, npx)
- [x] DSL for declaring dependencies: `npmDependencies { dependency("lodash", "^4.17.21") }`

### 3.3 Node.js Testing ✅
- [x] Created `NodeJsTestRunner` for running tests in Node.js
- [x] Parses test output for pass/fail results
- [x] Sample project: `samples/hello-js/`

---

## Phase 4: Kotlin/Native ✅ COMPLETE

**Goal:** Compile Kotlin to native binaries.

### 4.1 Konan Compiler Management ✅
- [x] Download Kotlin/Native compiler distribution per platform
- [x] Cache in `~/.konan` (same as Gradle to avoid duplicate downloads)
- [x] Version management (matches Kotlin version)
- [x] Created `KonanCompiler` class with automatic download and extraction

### 4.2 Native Compilation ✅
- [x] Created `KotlinNativeCompile` for native compilation
- [x] Created `ReactiveKotlinNativeCompile` for reactive builds
- [x] Support all common targets via `KonanTarget` enum:
  - macOS (x64, arm64)
  - iOS (arm64, simulator_arm64, x64)
  - watchOS, tvOS
  - Linux (x64, arm64)
  - Windows (mingw_x64)
  - Android Native (arm64, arm32, x64, x86)
- [x] Multiple output kinds via `NativeOutputKind`:
  - Executable
  - Static library (.a)
  - Dynamic library (.dylib, .so, .dll)
  - Kotlin library (.klib)
  - Framework (for Apple platforms)
  - Static framework

### 4.3 CInterop ✅
- [x] Created `CInterop` class for generating Kotlin bindings from C headers
- [x] Created `DefFileBuilder` for programmatic .def file creation
- [x] Created `SystemLibraries` object with templates for POSIX, Foundation, UIKit, AppKit
- [x] Support for headers, header filters, compiler opts, linker opts
- [x] Sample project: `samples/hello-native/`

---

## Phase 5: Kotlin Multiplatform Coordination ✅ COMPLETE

**Goal:** Build KMP projects with standard source set structure.

### 5.1 Source Set Structure ✅
- [x] Support standard layout: `commonMain`, `commonTest`, `jvmMain`, `jsMain`, `nativeMain`, etc.
- [x] Created `SourceSet` model with dependencies, source directories, and targets
- [x] Created `SourceSetHierarchy` with full KMP hierarchy
- [x] Hierarchical source sets with proper inheritance:
  - `commonMain` → `nativeMain` → `appleMain` → `iosMain` → `iosArm64Main`
  - `commonMain` → `nativeMain` → `linuxMain` → `linuxX64Main`
  - etc.

### 5.2 Target Support ✅
- [x] Created `KmpTarget` sealed class hierarchy for all KMP targets
- [x] Created `KmpTargetGroup` enum for hierarchical target groups:
  - COMMON, NATIVE, APPLE, MACOS, IOS, WATCHOS, TVOS, LINUX, MINGW, POSIX, ANDROID_NATIVE
- [x] Support for JVM, JS, Wasm, and all Native targets

### 5.3 Dependency Handling ✅
- [x] Created `KmpDependency` for multiplatform dependencies
- [x] Automatic artifact resolution for each target (-jvm, -js, -linuxx64, etc.)
- [x] Created `KmpDependencyResolver` for resolving dependencies per target
- [x] `KotlinStdlib` and `KotlinTest` helpers for standard library dependencies

### 5.4 KmpProject ✅
- [x] Created `KmpProject` class for coordinated multi-target builds
- [x] DSL builder: `kmpProject("name", root) { jvm(); js(); nativeHost() }`
- [x] Methods: `buildJvm()`, `buildJs()`, `buildNative(target)`, `buildAll()`
- [x] Framework support for Apple platforms
- [x] Sample project: `samples/hello-kmp/`

---

## Phase 6: Publishing & Tooling ✅ COMPLETE

**Goal:** Publish libraries and integrate with IDEs.

### 6.1 Maven Publishing ✅
- [x] Created `KmpPublish` class for publishing KMP artifacts
- [x] Publishes platform-specific artifacts: `-jvm.jar`, `-js.klib`, `-{target}.klib`
- [x] Gradle Module Metadata (module.json) for proper variant resolution
- [x] Created `GpgSigner` for signing artifacts with GPG
- [x] Support for passphrase from environment or properties file

### 6.2 IntelliJ Integration ✅
- [x] Created `IntelliJKmpBuild` for KMP projects
- [x] Generates `.iml` files for each source set with proper hierarchy
- [x] Generates `kotlinc.xml` with multiplatform settings
- [x] Generates library files for all dependencies
- [x] Extension: `project.intellij()`

### 6.3 CLI ✅
- [x] Created `KBuildCli` with command-line interface
- [x] Commands: `build`, `build:jvm`, `build:js`, `build:native`, `test`, `watch`, `publish`, `intellij`, `clean`, `help`
- [x] Options: `--project`, `--build-file`, `--verbose`, `--release`, `--target`
- [x] Main entry point in `com.ivieleague.kbuild.cli`

---

## Success Criteria

Phase 1 complete when: ✅
- [x] Single Kotlin JVM project builds reactively
- [x] File change triggers rebuild within 100ms detection + compile time
- [ ] Works for at least one real Lightning Kite project (TODO: test with real project)

Phase 2 complete when: ✅
- [x] ReactiveServerProcess supports hot reload
- [x] Integration tests run automatically when code changes
- [ ] TODO: Test with real `lightning-server` project

Phase 3 complete when: ✅
- [x] Kotlin/JS compilation works with K2 IR backend
- [x] npm project management (package.json, install, scripts)
- [x] Node.js test runner for running JS tests
- [ ] TODO: Test with real KiteUI project

Phase 4 complete when: ✅
- [x] Kotlin/Native compilation works for host platform
- [x] Cross-compilation targets supported
- [x] CInterop for C library bindings
- [ ] TODO: Test with real KiteUI project for iOS compilation

Phase 5 complete when: ✅
- [x] KMP source set hierarchy implemented
- [x] Multi-target compilation (JVM, JS, Native)
- [x] Dependency resolution per target
- [ ] TODO: Test with full KiteUI project
- [ ] TODO: Benchmark against Gradle

Phase 6 complete when: ✅
- [x] KMP Maven publishing with Gradle Module Metadata
- [x] GPG signing support
- [x] IntelliJ project generation for KMP
- [x] CLI wrapper for common tasks

---

## Open Questions

1. **Bootstrap problem**: KBuild uses Gradle to build itself. When do we switch to self-hosting?
   - Suggestion: After Phase 1 is solid, create `kbuild.kt` that builds KBuild using KBuild

2. **Parallel compilation**: Should multiple targets compile in parallel?
   - Reactive model supports this naturally (each target is independent reactive)
   - Need to manage memory (multiple compiler instances)

3. **Remote build cache**: Worth implementing?
   - Could be a separate library that wraps `Reactive<T>` with cache check
   - Lower priority than core functionality

4. **Gradle interop**: Should KBuild be able to consume Gradle projects?
   - Probably not initially—focus on replacement, not integration
