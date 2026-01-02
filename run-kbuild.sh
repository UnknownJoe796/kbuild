#!/bin/bash
#
# KBuild Launcher Script
#
# Usage:
#   ./run-kbuild.sh Build.compile          # Compile the project
#   ./run-kbuild.sh Build.test             # Run tests
#   ./run-kbuild.sh Build.jar              # Create JAR
#   ./run-kbuild.sh --list                 # List available targets
#   ./run-kbuild.sh --help                 # Show help
#
# First run will bootstrap by building the kbuild distribution with Gradle.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KBUILD_DIST="$SCRIPT_DIR/build/install/kbuild"

# Check if kbuild distribution exists
if [ ! -d "$KBUILD_DIST/bin" ]; then
    echo "KBuild distribution not found. Building with Gradle..."
    (cd "$SCRIPT_DIR" && ./gradlew installDist --quiet)
fi

# Set up classpath for kbuild
export KBUILD_HOME="$KBUILD_DIST"

# Run kbuild with the provided arguments
exec "$KBUILD_DIST/bin/kbuild" "$@"
