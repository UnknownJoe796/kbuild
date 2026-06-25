#!/bin/bash
#
# KBuild from-source bootstrap.
#
# Compiles kbuild from source without Gradle by:
#   1. Downloading kotlinc 2.3.20 if not cached.
#   2. Downloading all runtime dependency jars listed in bootstrap/classpath.txt.
#   3. Compiling all src/main/kotlin sources into build/bootstrap/kbuild.jar.
#
# After sourcing this script (or running it), the caller receives:
#   KBUILD_JAR  — path to the compiled kbuild jar
#   KBUILD_DEPS — colon-separated classpath of runtime dependency jars
#
# Usage (from a caller script):
#   source "$(dirname "${BASH_SOURCE[0]}")/bootstrap/bootstrap.sh"
#   exec java -cp "$KBUILD_DEPS:$KBUILD_JAR" com.ivieleague.kbuild.cli.KBuildCliKt "$@"

set -e

BOOTSTRAP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$BOOTSTRAP_DIR/.." && pwd)"

KOTLIN_VERSION="2.3.20"
KOTLIN_ZIP_SHA256="222ba516cdc4052ce0be9d2ec6adf3c5c64fc53156d7dd91d1f5317809431b98"
KOTLIN_INSTALL_DIR="$HOME/.kbuild/kotlin-$KOTLIN_VERSION"
KOTLIN_ZIP_URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"

DEPS_CACHE_DIR="$HOME/.kbuild/bootstrap-deps"
BOOTSTRAP_OUT="$PROJECT_DIR/build/bootstrap"
SOURCES_LIST="$BOOTSTRAP_OUT/sources.txt"
KBUILD_JAR="$BOOTSTRAP_OUT/kbuild.jar"
MANIFEST="$BOOTSTRAP_DIR/classpath.txt"

# --- prerequisite checks ---

_require() {
    command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required but not found. $2" >&2; exit 1; }
}
_require java "Install Java 17+ and ensure it is on PATH."
_require curl "Install curl."
_require unzip "Install unzip."

JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F'.' '{if ($1=="1") print $2; else print $1}')
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "ERROR: Java 17+ required (found: '$JAVA_MAJOR')." >&2
    exit 1
fi

# --- kotlinc ---

if [ ! -x "$KOTLIN_INSTALL_DIR/bin/kotlinc" ]; then
    echo "[bootstrap] Downloading Kotlin compiler $KOTLIN_VERSION..."
    mkdir -p "$HOME/.kbuild"
    TMP_ZIP="$(mktemp /tmp/kotlin-compiler-XXXXXX.zip)"
    curl -fsSL "$KOTLIN_ZIP_URL" -o "$TMP_ZIP"

    # Verify SHA256
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_HASH=$(sha256sum "$TMP_ZIP" | awk '{print $1}')
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL_HASH=$(shasum -a 256 "$TMP_ZIP" | awk '{print $1}')
    else
        echo "ERROR: neither sha256sum nor shasum found; cannot verify download." >&2
        rm -f "$TMP_ZIP"
        exit 1
    fi

    if [ "$ACTUAL_HASH" != "$KOTLIN_ZIP_SHA256" ]; then
        echo "ERROR: SHA256 mismatch for kotlin-compiler-$KOTLIN_VERSION.zip" >&2
        echo "  expected: $KOTLIN_ZIP_SHA256" >&2
        echo "  actual:   $ACTUAL_HASH" >&2
        rm -f "$TMP_ZIP"
        exit 1
    fi

    echo "[bootstrap] SHA256 verified. Extracting..."
    TMP_DIR="$(mktemp -d /tmp/kotlin-extract-XXXXXX)"
    unzip -q "$TMP_ZIP" -d "$TMP_DIR"
    mv "$TMP_DIR/kotlinc" "$KOTLIN_INSTALL_DIR"
    rm -rf "$TMP_ZIP" "$TMP_DIR"
    echo "[bootstrap] kotlinc installed to $KOTLIN_INSTALL_DIR"
fi

KOTLINC="$KOTLIN_INSTALL_DIR/bin/kotlinc"
# The serialization compiler plugin ships inside the kotlinc distribution.
SERIAL_PLUGIN="$KOTLIN_INSTALL_DIR/lib/kotlinx-serialization-compiler-plugin.jar"

# --- download dependencies ---

mkdir -p "$DEPS_CACHE_DIR" "$BOOTSTRAP_OUT"

KBUILD_DEPS=""

while IFS=' ' read -r coord repo; do
    # Skip blank lines and comments
    [[ -z "$coord" || "$coord" == \#* ]] && continue

    # Parse coord: group:artifact:version[:classifier]
    IFS=':' read -r group artifact version classifier <<< "$coord"

    group_path="${group//.//}"
    if [ -n "$classifier" ]; then
        filename="${artifact}-${version}-${classifier}.jar"
    else
        filename="${artifact}-${version}.jar"
    fi
    url="${repo}/${group_path}/${artifact}/${version}/${filename}"
    dest="$DEPS_CACHE_DIR/$filename"

    if [ ! -f "$dest" ]; then
        echo "[bootstrap] Downloading $filename..."
        curl -fsSL "$url" -o "$dest" || { echo "ERROR: failed to download $url" >&2; exit 1; }
    fi

    if [ -n "$KBUILD_DEPS" ]; then
        KBUILD_DEPS="$KBUILD_DEPS:$dest"
    else
        KBUILD_DEPS="$dest"
    fi
done < "$MANIFEST"

export KBUILD_DEPS

# --- compile ---

if [ ! -f "$KBUILD_JAR" ]; then
    echo "[bootstrap] Collecting Kotlin sources..."
    find "$PROJECT_DIR/src/main/kotlin" -name "*.kt" > "$SOURCES_LIST"
    SOURCE_COUNT=$(wc -l < "$SOURCES_LIST" | tr -d ' ')
    echo "[bootstrap] Compiling $SOURCE_COUNT source files..."
    "$KOTLINC" \
        @"$SOURCES_LIST" \
        -classpath "$KBUILD_DEPS" \
        -Xcontext-parameters \
        -opt-in kotlin.reflect.ExperimentalContextParametersApi \
        -Xplugin="$SERIAL_PLUGIN" \
        -d "$KBUILD_JAR" \
        2>&1
    echo "[bootstrap] Compiled: $KBUILD_JAR"
fi

export KBUILD_JAR
