#!/usr/bin/env bash
# Build the wasmJs browser dist and serve it on http://127.0.0.1:8765.
# Use this for poking at the web app while iterating; the Gradle
# `wasmJsBrowserDevelopmentRun` task can also serve with HMR, but
# this is faster when you just want a fresh static dist.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/nix/store/c3pl7bqrx3d2rc3dh98z6yaj0mv1p52g-openjdk-21.0.10+7/lib/openjdk}"

PORT="${PORT:-8765}"
TARGET="${1:-development}"    # development | production
TASK="wasmJsBrowserDevelopmentExecutableDistribution"
DIST_DIR="composeApp/build/dist/wasmJs/developmentExecutable"
if [ "$TARGET" = "production" ]; then
    # Production has no *ExecutableDistribution task — the webpack
    # variant emits the bundle under build/kotlin-webpack/wasmJs/productionExecutable.
    TASK="wasmJsBrowserProductionWebpack"
    DIST_DIR="composeApp/build/kotlin-webpack/wasmJs/productionExecutable"
fi

echo "==> Building $TASK"
./gradlew ":composeApp:$TASK"

if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: dist dir $DIST_DIR was not produced" >&2
    exit 1
fi

echo "==> Serving $DIST_DIR on http://127.0.0.1:$PORT"
cd "$DIST_DIR"
exec python3 -m http.server "$PORT"
