# Hot Reload Demo

This sample demonstrates KBuild's hot reload capability for server development - one of the core use cases KBuild was designed for.

## The Problem with Traditional Build Tools

With Gradle or Maven:
1. Edit code
2. Wait for build to finish
3. Manually restart server
4. Test your changes
5. Repeat

This friction slows down development, especially for server-side code where the feedback loop matters.

## KBuild's Solution

With KBuild's reactive file watching:
1. Edit code
2. Save
3. Server automatically restarts with new code
4. Test your changes

No manual steps. No waiting. Just save and see.

## Running the Demo

### Prerequisites

Build and install KBuild to local Maven:

```bash
cd /path/to/kbuild
./gradlew publishToMavenLocal
```

### Development Mode (Hot Reload)

```bash
kbuild -b Build Build.dev
```

This will:
1. Compile the server
2. Start it on http://localhost:8080
3. Watch `src/` for changes
4. When you save, automatically recompile and restart

### Try It

1. Start the dev server: `kbuild -b Build Build.dev`
2. Open http://localhost:8080 in your browser
3. Edit `src/Server.kt` - change the message
4. Save the file
5. Refresh your browser - see the new message!

### Single Run

```bash
kbuild -b Build Build.run
```

### Clean

```bash
kbuild -b Build Build.clean
```

## How It Works

```kotlin
// Create reactive file watcher
val sourceWatch = DirectoryWatch(srcDir, "**/*.kt")

// Set up reactive scope
scope.reactiveScope {
    // Access the reactive value - registers dependency
    val sources = sourceWatch()

    // This block re-executes when sources change
    compile()
    restartServer()
}
```

Key components:
- **DirectoryWatch** - Monitors directories for file changes using Java's WatchService
- **reactiveScope** - Creates a reactive context that tracks dependencies
- **invoke()** - Accessing a Reactive value registers it as a dependency

## Architecture

```
         DirectoryWatch
              |
              v
    +---------+----------+
    |   ReactiveScope    |
    |                    |
    |  sources = watch() | <-- registers dependency
    |  compile()         |
    |  restartServer()   |
    +---------+----------+
              |
              v (file changes)
              |
    Re-executes the scope
```

## Extending This Pattern

### With JUnit Tests

```kotlin
scope.reactiveScope {
    val sources = sourceWatch()

    compile()
    runTests()  // Also re-run tests on change
    restartServer()
}
```

### With Multiple Servers

```kotlin
scope.reactiveScope {
    val sources = sourceWatch()

    compile()

    // Restart both API and web servers
    restartServer(apiServer, port = 8080)
    restartServer(webServer, port = 3000)
}
```

### With Browser Refresh

```kotlin
scope.reactiveScope {
    val sources = sourceWatch()

    compile()
    restartServer()
    notifyBrowser()  // Trigger browser refresh via WebSocket
}
```

## Comparison with Alternatives

| Feature | KBuild | Spring DevTools | Gradle Continuous |
|---------|--------|-----------------|-------------------|
| Hot reload | Full restart | Class reload | Full restart |
| Setup | Just code | Add dependency | Add plugin |
| Customization | Full control | Limited | Task-based |
| Reactive | Yes | No | No |
| Debug output | Your code | Magic | Gradle logs |
