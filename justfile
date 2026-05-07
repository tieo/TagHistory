# TagHistory build and test recipes.
# Run `just` (no args) to list everything grouped by area.
#
# KVM is required for any recipe that boots an emulator
# (Linux: ensure /dev/kvm exists and your user is in the `kvm` group).
#
# Override emulator config per call:
#   EMULATOR_DEVICE='Pixel 6' EMULATOR_VERSION=13.0 just docker-e2e

set positional-arguments

# Show the full recipe list grouped.
default:
    @just --list --unsorted

# ─── Docker (mirrors CI) ──────────────────────────────────────────────

[group('docker')]
[doc('Build debug APK in the build container.')]
docker-apk:
    docker compose build build
    docker compose run --rm build ./gradlew :androidApp:assembleDebug

[group('docker')]
[doc('Run all Maestro flows in a containerized emulator.')]
docker-e2e: docker-apk
    docker compose build maestro
    docker compose run --rm maestro

[group('docker')]
[doc('Run a single Maestro flow file. Usage: just docker-e2e-one .maestro/34_history_bottom_sheet_ui.yaml')]
docker-e2e-one file: docker-apk
    docker compose build maestro
    docker compose run --rm maestro /tests/{{ file_name(file) }}

[group('docker')]
[doc('Run unit tests in the build container.')]
docker-test:
    docker compose run --rm build ./gradlew test

# ─── Local host (fast iteration) ──────────────────────────────────────

[group('local')]
[doc('Build debug APK on the host (no Docker).')]
local-apk:
    JAVA_HOME=$HOME/.gradle/jdks/temurin-21 ./gradlew :androidApp:assembleDebug

[group('local')]
[doc('Install the latest debug APK on emulator-5554.')]
local-install: local-apk
    adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

[group('local')]
[doc('Run the full Maestro suite against running emulator-5554.')]
local-e2e: local-install
    maestro --device emulator-5554 test .maestro/

[group('local')]
[doc('Run one Maestro flow against emulator-5554.')]
local-e2e-one file: local-install
    maestro --device emulator-5554 test {{ file }}

[group('local')]
[doc('Run all unit tests on the host.')]
local-test:
    JAVA_HOME=$HOME/.gradle/jdks/temurin-21 ./gradlew test

# ─── CI mirror ────────────────────────────────────────────────────────

[group('ci')]
[doc('Replays what the CI build job runs.')]
ci-build: docker-apk

[group('ci')]
[doc('Replays one CI matrix shard. Usage: just ci-shard 3')]
ci-shard shard='0': docker-apk
    #!/usr/bin/env bash
    set -euo pipefail
    docker compose build maestro
    ls .maestro/*.yaml \
      | grep -v '00_seed_test_data\.yaml$' \
      | grep -v '02_login_happy_path\.yaml$' \
      | sort \
      | shuf --random-source=<(yes "shard{{ shard }}" | head -c 4096) \
      > /tmp/shard_files.txt
    mapfile -t TESTS < /tmp/shard_files.txt
    MAPPED=()
    for f in "${TESTS[@]}"; do
        MAPPED+=("/tests/$(basename "$f")")
    done
    docker compose run --rm maestro "${MAPPED[@]}"

# ─── Developer helpers ────────────────────────────────────────────────

[group('dev')]
[doc('Bash shell in the build container.')]
shell-build:
    docker compose run --rm build bash

[group('dev')]
[doc('Bash shell in the Maestro/emulator container (no auto-test).')]
shell-test:
    docker compose run --rm --entrypoint bash maestro

[group('dev')]
[doc('Drop docker volumes (gradle, cargo, maestro output caches).')]
clean-volumes:
    docker compose down -v

[group('dev')]
[doc('Tail logcat from the running emulator-5554.')]
logcat:
    adb -s emulator-5554 logcat

[group('dev')]
[doc('Install lefthook git hooks (pre-commit + pre-push).')]
lefthook-install:
    lefthook install

[group('dev')]
[doc('Run all lefthook hooks against staged files (mirrors what runs on commit).')]
lefthook-run:
    lefthook run pre-commit
