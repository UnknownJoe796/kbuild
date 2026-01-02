# Reactive Build Demo

This sample demonstrates KBuild's reactive build capability - automatically recompiling and retesting when source files change.

## How It Works

KBuild uses the [Reactive](https://github.com/lightningkite/reactive) library for dependency tracking:

1. **DirectoryWatch** monitors a directory for file changes (uses Java's WatchService)
2. **ReactiveContext** tracks which reactive values are accessed during a calculation
3. When any dependency changes, the calculation automatically re-runs

```kotlin
val sourceWatch = DirectoryWatch(srcDir, "**/*.kt")

scope.reactiveScope {
    // Access the reactive value - registers dependency
    val sources = sourceWatch()

    // This block re-executes when sources change
    build()
}
```

## Running the Demo

### Prerequisites

Build and install KBuild to local Maven:

```bash
cd /path/to/kbuild
./gradlew publishToMavenLocal
```

### Single Build

```bash
kbuild -b Build Build.build
```

### Watch Mode (Reactive)

```bash
kbuild -b Build Build.watch
```

Then edit `src/Main.kt` or `src/MainTest.kt` - the build will automatically re-run.

### Clean

```bash
kbuild -b Build Build.clean
```

## Key Concepts

### DirectoryWatch

Monitors a directory for file changes using glob patterns:

```kotlin
// Watch all Kotlin files recursively
val watch = DirectoryWatch(srcDir, "**/*.kt")

// Watch only top-level files
val watch = DirectoryWatch(srcDir, "*.kt")

// Custom debounce (batch rapid changes)
val watch = DirectoryWatch(srcDir, "**/*.kt", debounceMs = 500)
```

### Reactive Compilation

The reactive version of `kotlinJvmCompile` takes `Reactive<Set<File>>` parameters:

```kotlin
context(ctx: ReactiveContext)
fun kotlinJvmCompile(
    name: String,
    sourceRoots: Reactive<Set<File>>,  // Reactive!
    classpathJars: Reactive<Set<File>>, // Reactive!
    outputFolder: File
): File
```

When source files change, the DirectoryWatch value updates, which triggers recompilation.

### Why This Matters

Unlike traditional build systems that require explicit task dependencies:

- **Gradle**: `compileKotlin.dependsOn(generateSources)`
- **KBuild**: Dependencies are implicit from data flow

This means:
- No forgotten dependencies
- No stale builds
- Continuous builds work out of the box
