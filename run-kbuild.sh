#!/bin/bash
#
# KBuild CLI Runner
#
# Usage:
#   ./run-kbuild.sh Build.compile           # Run compile target
#   ./run-kbuild.sh Build.test              # Run tests
#   ./run-kbuild.sh --list                  # List available targets
#   ./run-kbuild.sh --repl                  # Interactive REPL
#
# This script bootstraps KBuild by first building it with Gradle,
# then running the CLI with the built classes.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Build KBuild first if classes don't exist
if [ ! -d "build/classes/kotlin/main" ]; then
    echo "Building KBuild with Gradle..."
    ./gradlew classes --quiet
fi

# Get classpath from Gradle
echo "Getting classpath..."
CLASSPATH=$(./gradlew -q printClasspath)

if [ -z "$CLASSPATH" ]; then
    echo "Error: Failed to get classpath from Gradle"
    exit 1
fi

# Run the CLI
exec java -cp "$CLASSPATH" com.ivieleague.kbuild.cli.KBuildCliKt "$@"
