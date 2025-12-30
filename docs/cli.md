# KBuild CLI Reference

The KBuild CLI provides an interactive way to run build targets defined in Kotlin build classes.

## Installation

The CLI is part of the KBuild library. To use it, ensure your build class is on the classpath and run the main class:

```bash
java -cp "your-build.jar:kbuild.jar" com.ivieleague.kbuild.cli.KBuildCliKt [options]
```

Or create a shell script alias:

```bash
#!/bin/bash
java -cp "$PROJECT/build/classes:$KBUILD_HOME/kbuild.jar" \
  com.ivieleague.kbuild.cli.KBuildCliKt "$@"
```

## Command Modes

### Run Mode (default)

Execute a build target expression:

```bash
kbuild Build.compile
kbuild Build.test(".*Integration")
kbuild Build.project.buildJvm
```

### List Mode

Show available targets in the build class:

```bash
kbuild --list
kbuild -l
```

Output:

```
Available targets in Build:

Properties:
  ⟳ sources: Set<File>
    version: String

Functions:
  ⟳ compile(): File
  ⟳ test(pattern: String): Set<TestResult>
    clean(): Unit

Legend: ⟳ = reactive (supports watch mode)
```

### REPL Mode

Interactive shell for exploring and running builds:

```bash
kbuild --repl
```

### Daemon Mode

Background process for fast repeated builds:

```bash
kbuild --daemon
```

## Expression Syntax

### Identifiers

Simple property or no-argument method access:

```
Build.compile       # Access 'compile' on Build
project.sources     # Chained access
```

### Method Calls

Explicit method invocation with optional arguments:

```
Build.compile()              # No arguments
Build.test("pattern")        # String argument
Build.config(42, true)       # Multiple arguments
Build.run(null)              # Null argument
```

### Supported Argument Types

| Type | Example |
|------|---------|
| String | `"hello"`, `'world'` |
| Long | `42`, `-1` |
| Double | `3.14`, `-0.5` |
| Boolean | `true`, `false` |
| Null | `null` |

### String Escaping

Strings support standard escape sequences:

```
"hello\nworld"     # Newline
"tab\there"        # Tab
"quote\"here"      # Escaped quote
"back\\slash"      # Backslash
```

## Command-Line Options

| Option | Short | Description |
|--------|-------|-------------|
| `--help` | `-h` | Show help message |
| `--version` | | Show version |
| `--list` | `-l` | List available targets |
| `--watch` | `-w` | Watch mode (re-run on changes) |
| `--verbose` | `-v` | Verbose output with stack traces |
| `--project` | `-p` | Project root directory |
| `--build` | `-b` | Build class name (default: `Build`) |
| `--repl` | | Start interactive REPL |
| `--daemon` | | Start background daemon |

### Examples

```bash
# Basic execution
kbuild Build.compile

# Watch mode
kbuild Build.compile --watch

# Custom build class
kbuild -b MyBuild compile

# Custom project directory
kbuild -p /path/to/project Build.compile

# Verbose with watch
kbuild -v -w Build.test
```

## Interactive REPL

### Starting the REPL

```bash
kbuild --repl
```

### REPL Commands

| Command | Description |
|---------|-------------|
| `ls` | List targets in current context |
| `cd <name>` | Navigate into nested object |
| `cd ..` | Navigate back to parent |
| `watch <expr>` | Run expression in watch mode |
| `stop` | Stop current watch |
| `run <expr>` | Run expression once (same as just typing it) |
| `help` | Show help |
| `exit`, `quit` | Exit REPL |

### Navigation

The REPL maintains a current context. Navigate with `cd`:

```
Build> ls
Properties:
    project: Project

Build> cd project
Context: Project

Project> ls
Properties:
    sources: Set<File>
Functions:
  ⟳ compile(): File

Project> compile
[14:30:00] ✓ Success (1234ms): /build/classes

Project> cd ..
Context: Build
```

### Watch Mode

Start a reactive watch that re-runs when dependencies change:

```
Build> watch compile
Watching 'compile'. Press Ctrl+C or type 'stop' to stop.
[14:30:00] Running: compile
[14:30:02] ✓ Success (2341ms): /build/classes
[14:31:15] ⟳ Rerunning: Dependency changed
[14:31:16] ✓ Success (823ms): /build/classes
^C
Build> stop
Watch stopped
```

### Tab Completion

The REPL supports tab completion for:
- Built-in commands (`ls`, `cd`, `watch`, etc.)
- Available targets in the current context

### Command History

History is saved to `~/.kbuild_history` and persists between sessions. Use arrow keys to navigate history.

## Background Daemon

### Starting the Daemon

```bash
kbuild --daemon
# Output: KBuild daemon started on port 54321
# Output: PID: 12345
```

The daemon runs in the foreground. Use `&` for background:

```bash
kbuild --daemon &
```

### Daemon Protocol

The daemon communicates via TCP using line-delimited JSON:

**Request:**
```json
{
  "id": "req-1",
  "command": "run",
  "expression": "Build.compile"
}
```

**Response:**
```json
{
  "id": "req-1",
  "status": "ok",
  "value": "/build/classes",
  "durationMs": 1234
}
```

### Supported Commands

| Command | Description |
|---------|-------------|
| `ping` | Health check, returns "pong" |
| `run` | Execute expression once |
| `watch` | Execute in watch mode |
| `list` | List available targets |
| `cancel` | Cancel a running job |
| `stop` | Shutdown the daemon |

### Response Statuses

| Status | Description |
|--------|-------------|
| `ok` | Success |
| `error` | Error occurred (check `error` field) |
| `loading` | Reactive loading in progress |
| `watching` | Watch started successfully |

### PID File

The daemon writes its port and PID to `.kbuild-daemon.pid`:

```
54321:12345
```

This file is automatically cleaned up on shutdown or when a stale daemon is detected.

### Connecting to Daemon

When you run a kbuild command, it first checks for a running daemon:

```bash
# First command starts daemon or runs directly
kbuild Build.compile

# If daemon is running, subsequent commands are fast
kbuild Build.test  # Connects to existing daemon
```

## Reactive Functions

### Detecting Reactive Functions

Functions with `context(ReactiveContext)` are detected as reactive:

```kotlin
// Reactive - supports watch mode
context(ReactiveContext)
fun compile(): File = ...

// Non-reactive - runs once only
fun clean(): Unit = ...
```

The CLI marks reactive targets with `⟳` in listings.

### Watch Mode Behavior

**Reactive functions:**
- Run in a reactive context
- Automatically re-run when dependencies change
- Support `ReactiveLoading` for async operations

**Non-reactive functions:**
- Run once
- Stay alive in watch mode but don't re-run
- Cancel with Ctrl+C

## Error Handling

### Parse Errors

Invalid expressions produce clear error messages:

```
Error parsing expression: Invalid identifier: 123invalid
Error parsing expression: Unclosed parenthesis in: test(
```

### Evaluation Errors

Missing targets or type mismatches are reported:

```
Error evaluating expression: No member 'nonexistent' found on Build
Error evaluating expression: No matching overload for test(3 args) on Build
```

### Execution Errors

Runtime errors include duration and optional stack trace:

```
[14:30:00] ✗ Error: FileNotFoundException: src/missing.kt
```

Use `--verbose` for full stack traces.

## Build Class Requirements

### Basic Structure

```kotlin
object Build {
    // Properties are accessible
    val version = "1.0.0"

    // Public functions are callable
    fun compile(): File = ...

    // Private members are not exposed
    private fun helper() = ...
}
```

### Using Kotlin Objects

Objects are preferred for build definitions:

```kotlin
object Build {
    // Singleton - INSTANCE field detected automatically
}
```

### Using Classes

Classes work too - a no-arg constructor is called:

```kotlin
class Build {
    // Constructed once when loaded
    val sources = File("src")
}
```

### Nested Objects

Use nested objects for organization:

```kotlin
object Build {
    object Jvm {
        fun compile(): File = ...
    }
    object Js {
        fun compile(): File = ...
    }
}
```

Access via chained expressions:

```bash
kbuild Build.Jvm.compile
kbuild Build.Js.compile
```

Or navigate in REPL:

```
Build> cd Jvm
Jvm> compile
```

## Troubleshooting

### Build Class Not Found

```
Error: Could not load build class 'Build'
```

Ensure:
1. The class is on the classpath
2. The class name matches (case-sensitive)
3. Try fully qualified name: `-b com.example.Build`

### Method Not Found

```
No function 'compile' found on Build
```

Check:
1. Method is public
2. Spelling matches exactly
3. Use `--list` to see available targets

### Argument Type Mismatch

```
argument type mismatch
```

The CLI parses integers as `Long`. If your function takes `Int`, you may need to adjust the function signature or use Long parameters.

### Watch Mode Not Re-running

- Ensure the function has `context(ReactiveContext)`
- Check that reactive dependencies are actually changing
- Verify file watchers are on the correct directories

### Daemon Connection Failed

```
Connection refused
```

The daemon may have crashed. Delete `.kbuild-daemon.pid` and restart:

```bash
rm .kbuild-daemon.pid
kbuild --daemon &
```
