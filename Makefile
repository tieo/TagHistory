.PHONY: build apk e2e e2e-one shell-build shell-test clean-volumes

# Build the debug APK in the same Docker image CI uses.
apk:
	docker compose build build
	docker compose run --rm build ./gradlew :androidApp:assembleDebug

# Run the full Maestro E2E suite in a containerized emulator.
# Requires KVM (Linux host with /dev/kvm). Sample:
#   make e2e
#   make e2e EMULATOR_DEVICE='Pixel 6' EMULATOR_VERSION=13.0
e2e: apk
	docker compose build maestro
	docker compose run --rm maestro

# Run a single flow:
#   make e2e-one F=.maestro/34_history_bottom_sheet_ui.yaml
e2e-one: apk
	docker compose build maestro
	docker compose run --rm maestro /tests/$$(basename $(F))

# Drop into a build-tools shell.
shell-build:
	docker compose run --rm build bash

# Drop into a maestro/emulator shell (no auto-run).
shell-test:
	docker compose run --rm --entrypoint bash maestro

clean-volumes:
	docker compose down -v
