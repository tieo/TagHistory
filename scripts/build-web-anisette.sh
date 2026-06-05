#!/usr/bin/env bash
# Vendor lbr77/anisette-js + extract Apple's libCoreADI / libstoreservicescore
# into the wasm dist so the web build can generate anisette headers
# on-device — same identity bytes as the Android ottjni bridge,
# nothing leaves the browser.
#
# Run once per checkout. Output lands under
# composeApp/src/wasmJsMain/resources/anisette/ — webpack will copy
# the directory into every wasm dist build after that.
#
# Prerequisites:
#   * git
#   * Rust nightly + rustup-installed wasm32 targets
#   * Emscripten SDK (`emcc` on PATH)
#   * Bun (`bun` on PATH)
#
# All four are available via `nix-shell -p git rustup emscripten bun`.

set -euo pipefail
cd "$(dirname "$0")/.."

DEST="composeApp/src/wasmJsMain/resources/anisette"
mkdir -p "$DEST"

# 1. anisette-js — Unicorn-based browser anisette generator.
if [ ! -d build/web-anisette/anisette-js ]; then
    mkdir -p build/web-anisette
    git clone --depth 1 https://github.com/lbr77/anisette-js build/web-anisette/anisette-js
fi
if [ ! -d build/web-anisette/unicorn ]; then
    git clone https://github.com/lbr77/unicorn build/web-anisette/unicorn
    (cd build/web-anisette/unicorn && git checkout tci-emscripten)
fi

(
    cd build/web-anisette/anisette-js
    # Patch the build script to point at our unicorn checkout instead
    # of its sibling-dir default.
    UNICORN_DIR="$(pwd)/../unicorn" bash script/build-glue.sh
)

cp build/web-anisette/anisette-js/dist/anisette.js "$DEST/"
cp build/web-anisette/anisette-js/dist/anisette_rs.wasm "$DEST/" 2>/dev/null \
    || cp build/web-anisette/anisette-js/dist/anisette_rs.node.wasm "$DEST/anisette_rs.wasm"

# 2. Apple libCoreADI + libstoreservicescore — reuse whatever the
#    androidApp build already extracted from the Apple Music APK. The
#    arm64-v8a slice is the one anisette-js's emulator expects.
APPLE_LIB_SRC="androidApp/build/intermediates/assets/debug/mergeDebugAssets/apple-libs/arm64-v8a"
if [ ! -f "$APPLE_LIB_SRC/libCoreADI.so" ]; then
    echo "Apple libs not extracted yet — running ./gradlew :androidApp:extractAppleLibs first."
    ./gradlew :androidApp:extractAppleLibs
fi
cp "$APPLE_LIB_SRC/libCoreADI.so" "$DEST/"
cp "$APPLE_LIB_SRC/libstoreservicescore.so" "$DEST/"

echo "Vendored anisette dist into $DEST"
ls -la "$DEST"
