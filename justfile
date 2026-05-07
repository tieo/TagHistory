# Recipes mirror what CI runs so local and CI stay aligned.
# Requires KVM on Linux (/dev/kvm) for the emulator-based recipes.

default:
    @just --list

# Build the debug APK using the same Docker image CI uses.
apk:
    docker compose build build
    docker compose run --rm build ./gradlew :androidApp:assembleDebug

# Run the full Maestro suite against a containerized emulator.
# Override device or android version with env vars, e.g.
#   EMULATOR_DEVICE='Pixel 6' EMULATOR_VERSION=13.0 just e2e
e2e: apk
    docker compose build maestro
    docker compose run --rm maestro

# Run a single flow file. Pass an absolute path or a path relative to repo root.
#   just e2e-one .maestro/34_history_bottom_sheet_ui.yaml
e2e-one file: apk
    docker compose build maestro
    docker compose run --rm maestro /tests/{{ file_name(file) }}

# Drop into a shell of the build container.
shell-build:
    docker compose run --rm build bash

# Drop into a shell of the maestro/emulator container without auto-running tests.
shell-test:
    docker compose run --rm --entrypoint bash maestro

# Tear down volumes (gradle/cargo caches, maestro tests dir).
clean-volumes:
    docker compose down -v

# Local Gradle build outside Docker (fast iteration).
local-apk:
    JAVA_HOME=$HOME/.gradle/jdks/temurin-21 ./gradlew :androidApp:assembleDebug

# Local Maestro run on already-running emulator-5554.
local-e2e:
    maestro --device emulator-5554 test .maestro/

# Local Maestro for one flow on emulator-5554.
local-e2e-one file:
    maestro --device emulator-5554 test {{ file }}
