# Kotlin/JS + Vite Demo

Demonstrates Kotlin/JS compilation with incremental builds and Vite hot module replacement.

## Quick Start

```bash
# From kbuild project root, build and install kbuild
./gradlew installDist

# Navigate to this sample
cd samples/kotlin-js-vite

# Install npm dependencies (one-time)
../../build/install/kbuild/bin/kbuild -b Build Build.setup

# Start development mode
../../build/install/kbuild/bin/kbuild -b Build Build.dev
```

## Commands

| Command | Description |
|---------|-------------|
| `Build.setup` | Install npm dependencies (run once) |
| `Build.dev` | Development mode with file watching and Vite |
| `Build.compile` | Compile Kotlin to JavaScript |
| `Build.build` | Production build |
| `Build.clean` | Clean build outputs |

## How It Works

1. **Kotlin Compilation**: KBuild compiles `src/*.kt` to ES modules in `build/js/`
2. **Incremental Builds**: Only recompiles when source files change
3. **Vite Dev Server**: Serves the app with hot module replacement
4. **File Watching**: KBuild watches for Kotlin changes and triggers recompilation

## Project Structure

```
kotlin-js-vite/
├── Build.kt          # Build configuration
├── src/
│   └── Main.kt       # Kotlin/JS source code
├── index.html        # HTML entry point
├── vite.config.js    # Vite configuration
├── package.json      # npm dependencies
└── build/
    ├── js/           # Compiled JavaScript output
    ├── cache/        # Incremental compilation cache
    └── dist/         # Production build output
```
