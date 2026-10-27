#!/usr/bin/env bash
# Builds the sync server, its native anisette bridge with Apple's macOS CoreADI
# and the web app, pushes them to nasx's /var/lib/taghistory-deploy and
# restarts the service. The service itself is declared in the dotfiles flake
# (systemd.services.taghistory).
#
# TAGHISTORY_HOST picks the address (default nasx); from outside the LAN set
# it to nasx's VPN address. The host key is always checked as nasx's.
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${TAGHISTORY_HOST:-nasx}"
SSH=(ssh -o HostKeyAlias=nasx "root@$HOST")
# Apple's macOS CoreADI is not in git; the app build extracts it from the
# iTunes for Mac installer.
COREADI=androidApp/src/main/assets/coreadi/CoreADI.x86_64

echo "==> Building server and web app"
./gradlew --max-workers=2 :androidApp:extractCoreAdi :server:installDist :webApp:wasmJsBrowserDistribution
echo "==> Building libottjni.so"
# Unicorn is C built by cmake, and bindgen reads its headers through libclang
# against glibc's; Nix supplies all of it. On nasx the library finds glibc and
# libgcc by soname in the JVM's own, and it needs no glibc newer than 2.34.
(
    cd rust
    LIBCLANG_PATH=$(nix build --no-link --print-out-paths nixpkgs#llvmPackages.libclang.lib)/lib \
    BINDGEN_EXTRA_CLANG_ARGS="-isystem $(nix build --no-link --print-out-paths nixpkgs#glibc.dev)/include" \
        nix shell nixpkgs#rustup nixpkgs#cmake nixpkgs#gcc --command cargo build --release -p ottjni
)

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -r server/build/install/taghistory-server "$STAGE/server"
cp rust/target/release/libottjni.so "$STAGE/"
mkdir -p "$STAGE/apple-libs/x86_64"
cp "$COREADI" "$STAGE/apple-libs/x86_64/"
cp -r webApp/build/dist/wasmJs/productionExecutable "$STAGE/web"

echo "==> Uploading to $HOST"
rsync -a --delete --chown=root:root -e "ssh -o HostKeyAlias=nasx" "$STAGE/" "root@$HOST:/var/lib/taghistory-deploy/"
"${SSH[@]}" "chmod -R a+rX /var/lib/taghistory-deploy && systemctl restart taghistory"

echo "==> Waiting for the server"
for _ in $(seq 60); do
    if "${SSH[@]}" "curl -sf http://127.0.0.1:8095/api/status" 2>/dev/null; then
        echo
        exit 0
    fi
    sleep 2
done
"${SSH[@]}" "journalctl -u taghistory -n 40 --no-pager"
exit 1
