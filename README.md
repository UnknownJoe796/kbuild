# KBuild

A reactive build library for Kotlin. Not a build system—a library.

## Why?

Gradle plugins are black boxes. When something breaks, you're reading plugin source code to debug it. KBuild makes the build process explicit Kotlin code you can Ctrl+Click through.

**KBuild is for teams who:**
- Build Kotlin JVM servers with hot reload
- Want continuous builds that actually work
- Are tired of Gradle's complexity tax
- Need fine-grained control over the build process

## Core Concept

Builds are reactive data flows. Dependencies track themselves:

```kotlin
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.lightningkite.reactive.context.ReactiveContext

// File watching returns Reactive<Set<File>>
val sources = DirectoryWatch(File("src/main/kotlin"), "**/*.kt")

// Compilation uses context parameters for reactive integration
context(ctx: ReactiveContext)
fun build(): File = kotlinJvmCompile(
    name = "my-app",
    sourceRoots = sources,
    classpathJars = dependencies,
    outputFolder = File("build/classes")
)
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

## Building KBuild

KBuild builds itself. The **canonical** build is `Build.kt`, driven via `./run-kbuild.sh`,
which compiles kbuild from source with no Gradle (it downloads kotlinc and the dependency
jars listed in `bootstrap/classpath.txt`; see `bootstrap/bootstrap.sh`):

```bash
./run-kbuild.sh Build.compile   # Compile main sources
./run-kbuild.sh Build.test      # Compile + run the full test suite (via kbuild's junitRun)
./run-kbuild.sh Build.jar       # Build the jar
./run-kbuild.sh Build.ide       # Generate kbuild's own IntelliJ project (.idea/ + kbuild.iml)
./run-kbuild.sh Build.clean
```

### Gradle: escape hatch

Gradle is kept functional but **secondary** — a fallback if the self-host is ever broken,
and the source of truth for regenerating the bootstrap dependency manifest:

```bash
./gradlew build    # Build via Gradle (escape hatch)
./gradlew test     # Run tests via Gradle (escape hatch)
```

When dependencies in `build.gradle.kts` change, regenerate `bootstrap/classpath.txt`:
run `./gradlew printClasspath` and map each resolved jar back to a
`group:artifact:version[:classifier] repo-url` line (format documented in the header of
`bootstrap/classpath.txt`).

## Quick Examples

### JVM Project

```kotlin
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.maven.*
import java.io.File

// Blocking compilation (for scripts or non-reactive usage)
val outputDir = kotlinJvmCompileBlocking(
    name = "my-app",
    sourceRoots = setOf(File("src/main/kotlin")),
    classpathJars = runBlocking { MavenAether.libraries("org.jetbrains.kotlin:kotlin-stdlib:2.1.20") }
        .mapNotNull { it.default }.toSet(),
    cache = File("build/cache"),  // Enable incremental compilation
    outputFolder = File("build/classes")
)
```

### Kotlin/JS Project

```kotlin
import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.maven.*

val jsStdlib = runBlocking {
    MavenAether.libraries(listOf(KlibDependency(Kotlin.standardLibraryJsId).aether()))
}.mapNotNull { it.default }.toSet()

// Compile to JavaScript (ES modules)
val jsOutput = kotlinJsCompileBlocking(
    name = "my-js-app",
    sourceRoots = setOf(File("src/main/kotlin")),
    libraries = jsStdlib,
    outputMode = JsOutputMode.JS,
    moduleKind = JsModuleKind.ES,
    sourceMap = true,
    cache = File("build/js-cache"),  // Enable incremental compilation
    outputDir = File("build/js")
)

// Or compile to KLIB (for libraries)
val klibOutput = kotlinJsCompileBlocking(
    name = "my-js-lib",
    sourceRoots = setOf(File("src/main/kotlin")),
    libraries = jsStdlib,
    outputMode = JsOutputMode.KLIB,
    outputDir = File("build/klib")
)
```

### Kotlin/Native Project

```kotlin
import com.ivieleague.kbuild.native.*

val nativeOutput = kotlinNativeCompileBlocking(
    name = "my-native-app",
    sourceRoots = setOf(File("src/main/kotlin")),
    target = KonanTarget.host(),
    outputKind = NativeOutputKind.EXECUTABLE,
    outputDir = File("build/native")
)
```

### Reactive Builds with File Watching

```kotlin
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.lightningkite.reactive.context.ReactiveContext
import com.lightningkite.reactive.context.reactiveScope

// Watch source files
val sources = DirectoryWatch(File("src/main/kotlin"), "**/*.kt")

// Build reactively - recompiles when files change
reactiveScope {
    val output = kotlinJvmCompile(
        name = "my-app",
        sourceRoots = sources,
        classpathJars = dependencies,
        outputFolder = File("build/classes")
    )
    println("Built to: $output")
}
```

## API Reference

### Compilation

| Function | Description |
|----------|-------------|
| `kotlinJvmCompile()` | Compile Kotlin to JVM bytecode (context parameter) |
| `kotlinJvmCompileBlocking()` | Blocking JVM compilation |
| `kotlinJsCompile()` | Compile Kotlin to JavaScript (context parameter) |
| `kotlinJsCompileBlocking()` | Blocking JS compilation |
| `kotlinNativeCompileBlocking()` | Compile Kotlin to native binaries |
| `javaCompileBlocking()` | Compile Java sources |
| `kotlinWithJavaCompileBlocking()` | Mixed Kotlin/Java compilation |

### Dependencies

| Class/Function | Description |
|----------------|-------------|
| `MavenAether` | Maven dependency resolution |
| `Dependency()` | Create Maven dependency |
| `KlibDependency()` | Create .klib dependency |

### Testing

| Function/Class | Description |
|----------------|-------------|
| `junitRun()` | Run JUnit tests reactively (context parameter) |
| `junitRunBlocking()` | Blocking JUnit test execution |
| `NodeJsTestRunner` | Run JS tests in Node.js |
| `BrowserTestRunner` | Run JS tests in browser |
| `KotlinNativeTestRunner` | Run native tests |

### Server Management

| Class | Description |
|-------|-------------|
| `ServerProcess` | Manage JVM server process |

### File Watching

| Class | Description |
|-------|-------------|
| `DirectoryWatch` | Watch directory for changes, returns `Reactive<Set<File>>` |

### Publishing

| Class | Description |
|-------|-------------|
| `MavenDeploy` | Deploy artifacts to Maven repo |
| `GpgSigner` | Sign artifacts with GPG |
| `PomBuild` | Generate POM files |

### IDE Integration

| Class | Description |
|-------|-------------|
| `IntelliJProjectBuild` | Generate IntelliJ project files |
| `IntelliJModuleBuild` | Generate module .iml files |

## Supported Targets

### JVM
- JVM 17+ (uses K2 compiler)

### JavaScript
- ES Modules, CommonJS, UMD, AMD
- Source maps
- Node.js and Browser
- Incremental compilation via klib cache

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
my-project/
├── src/
│   ├── main/kotlin/          # Main sources
│   └── test/kotlin/          # Test sources
├── build/
│   ├── classes/              # Compiled classes
│   ├── libs/                 # JAR outputs
│   └── cache/                # Incremental compilation cache
└── Build.kt                  # Build definition
```

## CLI

KBuild includes an interactive CLI for running build targets. Build classes are loaded and executed via reflection, with support for reactive (watch mode) execution.

### Basic Usage

```bash
# Run a build target once
kbuild Build.compile

# Run with watch mode (re-runs when dependencies change)
kbuild Build.compile --watch
kbuild Build.compile -w

# List available targets
kbuild --list
kbuild -l

# Show help
kbuild --help
```

### Expression Syntax

The CLI uses dot notation to reference build targets:

```bash
# Property or no-arg method
kbuild Build.compile

# Explicit method call
kbuild Build.compile()

# Method with arguments
kbuild Build.test(".*Foo")

# Chained access
kbuild Build.project.buildJvm

# Method with multiple args
kbuild Build.config("name", 42, true)
```

### Command-Line Options

| Option | Description |
|--------|-------------|
| `--watch`, `-w` | Watch mode - re-run when reactive dependencies change |
| `--list`, `-l` | List available build targets |
| `--project`, `-p <path>` | Project root directory (default: `.`) |
| `--build`, `-b <class>` | Build class name (default: `Build`) |
| `--verbose`, `-v` | Verbose output with stack traces |
| `--repl` | Start interactive REPL mode |
| `--daemon` | Start background daemon for fast repeated builds |
| `--help`, `-h` | Show help |
| `--version` | Show version |

### Interactive REPL

Start an interactive session for exploring and running targets:

```bash
kbuild --repl
```

REPL commands:

```
kbuild> ls                    # List targets in current context
kbuild> Build.compile         # Run a target
kbuild> watch compile         # Run target in watch mode
kbuild> cd project            # Navigate into nested object
kbuild> cd ..                 # Go back to parent
kbuild> stop                  # Stop current watch
kbuild> help                  # Show help
kbuild> exit                  # Exit REPL
```

Features:
- Tab completion for targets and commands
- Command history (saved to `~/.kbuild_history`)
- Navigate into nested build objects with `cd`
- Watch mode with `Ctrl+C` to stop

Example session:

```
$ kbuild --repl
KBuild REPL - Type 'help' for commands
Current context: Build

Build> ls
Properties:
  ⟳ sources: Set<File>
    version: String

Functions:
  ⟳ compile(): File
  ⟳ test(pattern: String): Set<TestResult>
    clean(): Unit

Build> compile
[14:32:01] Running: compile
[14:32:03] ✓ Success (2341ms): /build/classes

Build> watch test
Watching 'test'. Press Ctrl+C or type 'stop' to stop.
[14:32:15] Running: test
[14:32:18] ✓ Success (2891ms): Set(42 items)
[14:33:01] ⟳ Rerunning: Dependency changed
[14:33:03] ✓ Success (1523ms): Set(42 items)
^C
Build> exit
Goodbye!
```

### Background Daemon

For fast repeated builds, run a background daemon that keeps the build loaded:

```bash
# Start daemon
kbuild --daemon &

# Subsequent commands connect to daemon (fast startup)
kbuild Build.compile      # Uses daemon
kbuild Build.test         # Uses daemon

# Stop daemon
kbuild --stop-daemon
```

The daemon:
- Keeps build class loaded in memory
- Accepts commands via TCP socket (JSON protocol)
- Writes PID to `.kbuild-daemon.pid`
- Auto-cleans stale PID files

### Target Markers

When listing targets, markers indicate special properties:

```
⟳ compile: File       # Reactive - supports watch mode
  version: String     # Regular - runs once
```

### Creating a Build Class

```kotlin
// Build.kt
object Build {
    val sources = DirectoryWatch(File("src"), "**/*.kt")

    // Regular function - runs once
    fun clean() {
        File("build").deleteRecursively()
    }

    // Reactive function - supports watch mode
    context(ctx: ReactiveContext)
    fun compile(): File = kotlinJvmCompile(
        name = "my-app",
        sourceRoots = sources,  // Reactive dependency
        outputFolder = File("build/classes")
    )

    // Function with parameters
    context(ctx: ReactiveContext)
    fun test(pattern: String = ".*"): Set<TestResult> = junitRun(
        testClasses = compile,
        filter = pattern
    )
}
```

Run it:

```bash
kbuild Build.compile           # Build once
kbuild Build.compile -w        # Build and watch
kbuild Build.test              # Run all tests
kbuild 'Build.test(".*Foo")'   # Run tests matching pattern
```

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
| Incremental builds | Complex up-to-date checks | Reactive file watching + compiler IC |
| Plugin behavior | Opaque, global state | Functions you call |
| IDE support | Requires Gradle plugin | Standard Kotlin |
| Configuration | DSL compiles to model | Plain Kotlin objects |
| Debugging | Read plugin source | Ctrl+Click your code |

### Performance: KBuild vs Gradle

Benchmark compiling KBuild itself (91 Kotlin source files) on Apple M1 Max, 32GB RAM:

| Build Scenario | Gradle (no daemon) | Gradle (warm daemon) | KBuild | KBuild (daemon) |
|----------------|-------------------|---------------------|--------|-----------------|
| Cold build | 20.8s | 15.0s | 14.9s | 15.7s* |
| Incremental (1 file) | — | 15.0s | 2.3s | **0.6s** |
| No-op (nothing changed) | — | 15.0s | 0.7s | **0.06s** |

\* First run includes daemon startup time (~2s overhead).

| Memory | Gradle | KBuild | KBuild (daemon) |
|--------|--------|--------|-----------------|
| Per-build peak | ~100 MB (wrapper)† | 400-900 MB | N/A |
| Persistent process | ~2-3 GB | 0 (exits) | ~850 MB |

† Gradle wrapper only; actual compilation runs in the daemon (~2-3 GB).

**Key findings:**
- **Cold builds**: All roughly similar (15-21s range)
- **Incremental builds**: KBuild daemon is **25x faster** than Gradle (0.6s vs 15s)
- **No-op detection**: KBuild daemon is **250x faster** (60ms vs 15s)
- **Memory**: KBuild daemon uses ~850 MB vs Gradle's ~2-3 GB

**Benchmark limitations (be skeptical):**
- Single project, single machine, limited runs—not statistically rigorous
- KBuild compiling itself may have optimizations Gradle doesn't (e.g., pre-resolved dependencies)
- Gradle's 15s "warm daemon" time includes configuration phase; real-world projects vary widely
- Memory measurements use different methods (RSS snapshots vs /usr/bin/time)
- Your mileage will vary based on project size, dependency count, and machine specs
- KBuild's incremental compilation reuses the same Kotlin compiler as Gradle

**Where KBuild adds value:**
- **True incremental**: Detects "nothing changed" in 60ms with daemon
- **Transparency**: Build logic is debuggable Kotlin code, not plugin internals
- **Control**: Direct access to compiler APIs—tune exactly what you need
- **Memory efficiency**: Daemon uses ~850 MB vs Gradle's ~2-3 GB

## Current Status

Core functionality:

- ✅ JVM compilation with incremental support (K2 compiler)
- ✅ JS compilation (K2 two-phase: Sources → KLIB → JS) with incremental support
- ✅ Native compilation for all targets via Konan
- ✅ File watching and reactive builds
- ✅ Maven dependency resolution
- ✅ JUnit 5 test execution
- ✅ IntelliJ project generation
- ✅ Interactive CLI with REPL and daemon modes
- 🚧 Kotlin Multiplatform coordination (in development)

## License

MIT License - see [LICENSE.txt](LICENSE.txt)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
