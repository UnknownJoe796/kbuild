#!/bin/bash
#
# KBuild Fast Runner (Daemon Mode)
#
# Usage:
#   ./run-kbuild-fast.sh KBuildBuild.compile    # Run compile target via daemon
#   ./run-kbuild-fast.sh --stop           # Stop the daemon
#   ./run-kbuild-fast.sh --status         # Check daemon status
#
# This script uses a background daemon to avoid JVM startup overhead.
# First invocation starts the daemon (~1s), subsequent calls are instant.
# Classpath comes from bootstrap/classpath.txt (no Gradle required).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE=".kbuild-daemon.pid"
LOG_FILE=".kbuild-daemon.log"

BOOTSTRAP_CACHE="$HOME/.kbuild/bootstrap-deps"
MANIFEST="$SCRIPT_DIR/bootstrap/classpath.txt"

# Build DEPS_CP from manifest (deps must already be cached by bootstrap.sh)
build_deps_cp() {
    local cp=""
    while IFS=' ' read -r coord repo; do
        [[ -z "$coord" || "$coord" == \#* ]] && continue
        IFS=':' read -r group artifact version classifier <<< "$coord"
        local group_path="${group//.//}"
        local filename
        if [ -n "$classifier" ]; then
            filename="${artifact}-${version}-${classifier}.jar"
        else
            filename="${artifact}-${version}.jar"
        fi
        local dest="$BOOTSTRAP_CACHE/$filename"
        if [ -n "$cp" ]; then cp="$cp:$dest"; else cp="$dest"; fi
    done < "$MANIFEST"
    echo "$cp"
}

# Ensure kbuild jar exists; compile via bootstrap if needed
ensure_built() {
    BOOTSTRAP_JAR="$SCRIPT_DIR/build/bootstrap/kbuild.jar"
    if [ ! -f "$BOOTSTRAP_JAR" ]; then
        echo "Building KBuild from source..."
        source "$SCRIPT_DIR/bootstrap/bootstrap.sh"
    fi
    KBUILD_CLASSPATH="$(build_deps_cp):$BOOTSTRAP_JAR"
}

# Check if daemon is running
is_daemon_running() {
    if [ ! -f "$PID_FILE" ]; then
        return 1
    fi

    local info=$(cat "$PID_FILE")
    local port=$(echo "$info" | cut -d: -f1)

    if command -v nc &> /dev/null; then
        echo '{"id":"ping","command":"ping"}' | nc -w 1 localhost "$port" 2>/dev/null | grep -q '"status":"ok"'
        return $?
    else
        lsof -i ":$port" &>/dev/null
        return $?
    fi
}

# Get daemon port
get_daemon_port() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE" | cut -d: -f1
    fi
}

# Start daemon in background
start_daemon() {
    ensure_built

    echo "Starting KBuild daemon..."
    nohup java -cp "$KBUILD_CLASSPATH" com.ivieleague.kbuild.cli.KBuildCliKt --daemon > "$LOG_FILE" 2>&1 &

    for i in {1..20}; do
        sleep 0.5
        if is_daemon_running; then
            local port=$(get_daemon_port)
            echo "Daemon started on port $port"
            return 0
        fi
    done

    echo "Error: Daemon failed to start. Check $LOG_FILE for details."
    return 1
}

# Stop daemon
stop_daemon() {
    if [ ! -f "$PID_FILE" ]; then
        echo "Daemon is not running"
        return 0
    fi

    local info=$(cat "$PID_FILE")
    local port=$(echo "$info" | cut -d: -f1)
    local pid=$(echo "$info" | cut -d: -f2)

    echo "Stopping daemon (PID: $pid)..."

    if command -v nc &> /dev/null; then
        echo '{"id":"stop","command":"stop"}' | nc -w 1 localhost "$port" 2>/dev/null || true
    fi

    sleep 0.5

    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
    fi

    rm -f "$PID_FILE"
    echo "Daemon stopped"
}

# Send command to daemon
send_command() {
    local expression="$1"
    local port=$(get_daemon_port)

    if [ -z "$port" ]; then
        echo "Error: Could not get daemon port"
        return 1
    fi

    local request='{"id":"run1","command":"run","expression":"'"$expression"'"}'
    local response

    if command -v nc &> /dev/null; then
        response=$(echo "$request" | nc localhost "$port" 2>/dev/null)
    else
        exec 3<>/dev/tcp/localhost/$port
        echo "$request" >&3
        response=$(cat <&3)
        exec 3>&-
    fi

    local status=$(echo "$response" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    local value=$(echo "$response" | grep -o '"value":"[^"]*"' | cut -d'"' -f4)
    local error=$(echo "$response" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
    local duration=$(echo "$response" | grep -o '"durationMs":[0-9]*' | cut -d: -f2)

    if [ "$status" = "ok" ]; then
        if [ -n "$value" ]; then
            echo "$value"
        fi
        if [ -n "$duration" ]; then
            echo "[Completed in ${duration}ms]"
        fi
        return 0
    else
        echo "Error: $error"
        return 1
    fi
}

# Main logic
case "${1:-}" in
    --stop)
        stop_daemon
        ;;
    --status)
        if is_daemon_running; then
            echo "Daemon is running on port $(get_daemon_port)"
        else
            echo "Daemon is not running"
        fi
        ;;
    --start)
        if is_daemon_running; then
            echo "Daemon already running on port $(get_daemon_port)"
        else
            start_daemon
        fi
        ;;
    --help|-h)
        echo "KBuild Fast Runner (Daemon Mode)"
        echo ""
        echo "Usage:"
        echo "  ./run-kbuild-fast.sh <expression>  Run a build target"
        echo "  ./run-kbuild-fast.sh --start       Start the daemon"
        echo "  ./run-kbuild-fast.sh --stop        Stop the daemon"
        echo "  ./run-kbuild-fast.sh --status      Check daemon status"
        echo ""
        echo "Examples:"
        echo "  ./run-kbuild-fast.sh KBuildBuild.compile"
        echo "  ./run-kbuild-fast.sh KBuildBuild.test"
        ;;
    "")
        echo "Error: No expression provided"
        echo "Usage: ./run-kbuild-fast.sh <expression>"
        echo "Example: ./run-kbuild-fast.sh KBuildBuild.compile"
        exit 1
        ;;
    *)
        if ! is_daemon_running; then
            start_daemon
        fi
        send_command "$1"
        ;;
esac
