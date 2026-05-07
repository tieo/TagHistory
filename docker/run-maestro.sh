#!/usr/bin/env bash
set -euo pipefail

# Entrypoint for the Maestro test container. Boots the emulator (already
# configured by the budtmo base image), waits for it, installs the APK,
# then runs the test files passed as arguments. With no arguments runs
# every flow under .maestro/ except the seed helper and the live login.

APK_PATH="${APK_PATH:-/apk/androidApp-debug.apk}"
TESTS_ROOT="${TESTS_ROOT:-/tests}"

/start.sh &

echo "Waiting for ADB to be available..."
for _ in $(seq 1 60); do
    if adb devices | grep -q "emulator-5554.*device$"; then
        break
    fi
    sleep 2
done

adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
echo "Emulator ready."

if [ -f "$APK_PATH" ]; then
    adb install -r "$APK_PATH"
else
    echo "::error::APK not found at $APK_PATH"
    exit 2
fi

if [ "$#" -eq 0 ]; then
    mapfile -t FILES < <(ls "$TESTS_ROOT"/*.yaml \
        | grep -v '00_seed_test_data\.yaml$' \
        | grep -v '02_login_happy_path\.yaml$' \
        | sort)
else
    FILES=("$@")
fi

echo "Running ${#FILES[@]} maestro flow(s)"
FAIL=0
for f in "${FILES[@]}"; do
    echo "::group::maestro $f"
    if ! maestro test "$f"; then
        echo "::error file=$f::flow failed"
        FAIL=1
    fi
    echo "::endgroup::"
done
exit $FAIL
