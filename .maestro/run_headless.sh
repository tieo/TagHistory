#!/usr/bin/env bash
# Run the full Maestro test suite against a headless Android emulator.
#
# Usage:
#   .maestro/run_headless.sh [AVD_NAME]
#
# Optional env vars for authenticated tests:
#   APPLE_EMAIL, APPLE_PASSWORD, APPLE_2FA_CODE
#
# Prerequisites:
#   - Android SDK with emulator + avdmanager on PATH
#   - maestro CLI on PATH  (curl -Ls "https://get.maestro.mobile.dev" | bash)
#   - A debug APK already built:
#       ./gradlew :androidApp:assembleDebug
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APK="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
AVD="${1:-TagHistory_test}"
RESULTS_DIR="$PROJECT_ROOT/build/maestro-results"

# ── helpers ───────────────────────────────────────────────────────────────────
die() { echo "ERROR: $*" >&2; exit 1; }
require() { command -v "$1" &>/dev/null || die "$1 not found on PATH"; }

require emulator
require adb
require maestro

# ── build if needed ───────────────────────────────────────────────────────────
if [[ ! -f "$APK" ]]; then
  echo "==> Building debug APK…"
  (cd "$PROJECT_ROOT" && ./gradlew :androidApp:assembleDebug)
fi

# ── create AVD if missing ──────────────────────────────────────────────────────
if ! avdmanager list avd | grep -q "Name: $AVD"; then
  echo "==> Creating AVD '$AVD' (Pixel 6, API 34)…"
  echo "no" | avdmanager create avd \
    --name "$AVD" \
    --package "system-images;android-34;google_apis;x86_64" \
    --device "pixel_6"
fi

# ── boot emulator headlessly ──────────────────────────────────────────────────
echo "==> Starting emulator (headless)…"
emulator \
  -avd "$AVD" \
  -no-window \
  -no-audio \
  -no-snapshot \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  &
EMULATOR_PID=$!
trap "kill $EMULATOR_PID 2>/dev/null || true" EXIT

# Wait for boot
echo "==> Waiting for device to boot…"
adb wait-for-device
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do
  sleep 2
done
echo "==> Device ready."

# Disable animations for reliable UI tests
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

# ── install APK ──────────────────────────────────────────────────────────────
echo "==> Installing APK…"
adb install -r "$APK"

# ── run tests ────────────────────────────────────────────────────────────────
mkdir -p "$RESULTS_DIR"

ENV_FLAGS=""
[[ -n "${APPLE_EMAIL:-}" ]]    && ENV_FLAGS+=" --env APPLE_EMAIL=$APPLE_EMAIL"
[[ -n "${APPLE_PASSWORD:-}" ]] && ENV_FLAGS+=" --env APPLE_PASSWORD=$APPLE_PASSWORD"
[[ -n "${APPLE_2FA_CODE:-}" ]] && ENV_FLAGS+=" --env APPLE_2FA_CODE=$APPLE_2FA_CODE"

echo "==> Running Maestro flows…"
# shellcheck disable=SC2086
maestro test \
  --format junit \
  --output "$RESULTS_DIR/results.xml" \
  $ENV_FLAGS \
  "$SCRIPT_DIR"

echo "==> Done. Results: $RESULTS_DIR/results.xml"
