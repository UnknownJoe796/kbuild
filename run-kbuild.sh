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
# Fast path: if the current commit carries an exact git tag and the
#   prebuilt jar is available on LK S3, it is downloaded and used directly.
# Fallback: compile kbuild from source via bootstrap/bootstrap.sh (no Gradle).
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LK_S3="https://lightningkite-maven.s3.us-west-2.amazonaws.com"
KBUILD_GROUP_PATH="com/ivieleague/kbuild"
BOOTSTRAP_CACHE="$HOME/.kbuild/bootstrap-deps"
JAR_CACHE="$HOME/.kbuild/release-jars"

# --- try S3 fast path if an exact git tag exists ---

GIT_TAG=$(git -C "$SCRIPT_DIR" describe --exact-match HEAD 2>/dev/null || true)

KBUILD_JAR=""
KBUILD_DEPS=""

if [ -n "$GIT_TAG" ]; then
    REMOTE_JAR_URL="$LK_S3/$KBUILD_GROUP_PATH/$GIT_TAG/kbuild-$GIT_TAG.jar"
    CACHED_JAR="$JAR_CACHE/kbuild-$GIT_TAG.jar"
    mkdir -p "$JAR_CACHE"
    if [ ! -f "$CACHED_JAR" ]; then
        echo "[kbuild] Trying S3 prebuilt jar for tag $GIT_TAG..."
        if curl -fsSL --head "$REMOTE_JAR_URL" >/dev/null 2>&1; then
            curl -fsSL "$REMOTE_JAR_URL" -o "$CACHED_JAR"
            echo "[kbuild] Downloaded prebuilt jar."
        else
            echo "[kbuild] Prebuilt jar not found on S3 (will bootstrap from source)."
        fi
    fi
    if [ -f "$CACHED_JAR" ]; then
        KBUILD_JAR="$CACHED_JAR"
    fi
fi

# --- build DEPS_CP from bootstrap/classpath.txt ---

MANIFEST="$SCRIPT_DIR/bootstrap/classpath.txt"
mkdir -p "$BOOTSTRAP_CACHE"

while IFS=' ' read -r coord repo; do
    [[ -z "$coord" || "$coord" == \#* ]] && continue
    IFS=':' read -r group artifact version classifier <<< "$coord"
    group_path="${group//.//}"
    if [ -n "$classifier" ]; then
        filename="${artifact}-${version}-${classifier}.jar"
    else
        filename="${artifact}-${version}.jar"
    fi
    dest="$BOOTSTRAP_CACHE/$filename"
    if [ -n "$KBUILD_DEPS" ]; then
        KBUILD_DEPS="$KBUILD_DEPS:$dest"
    else
        KBUILD_DEPS="$dest"
    fi
done < "$MANIFEST"

# --- bootstrap from source if no prebuilt jar ---

if [ -z "$KBUILD_JAR" ]; then
    source "$SCRIPT_DIR/bootstrap/bootstrap.sh"
    # bootstrap.sh exports KBUILD_JAR and KBUILD_DEPS
fi

exec java -cp "$KBUILD_DEPS:$KBUILD_JAR" com.ivieleague.kbuild.cli.KBuildCliKt "$@"
