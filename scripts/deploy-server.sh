#!/usr/bin/env bash
# Builds the sync server, its native anisette bridge and the web app, pushes
# them to nasx's /var/lib/taghistory-deploy and restarts the service. The
# service itself is declared in the dotfiles flake (systemd.services.taghistory).
#
# TAGHISTORY_HOST picks the address (default nasx); from outside the LAN set
# it to nasx's VPN address. The host key is always checked as nasx's.
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${TAGHISTORY_HOST:-nasx}"
SSH=(ssh -o HostKeyAlias=nasx "root@$HOST")
APPLE_LIBS=androidApp/src/main/assets/apple-libs/x86_64
[ -f "$APPLE_LIBS/libCoreADI.so" ] || { echo "missing $APPLE_LIBS (Apple's ADI libraries are not in git)" >&2; exit 1; }

echo "==> Building server and web app"
./gradlew --max-workers=2 :server:installDist :composeApp:wasmJsBrowserDistribution
echo "==> Building libottjni.so"
(cd rust && cargo build --release -p ottjni)

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -r server/build/install/taghistory-server "$STAGE/server"
cp rust/target/release/libottjni.so "$STAGE/"
mkdir -p "$STAGE/apple-libs"
cp -r "$APPLE_LIBS" "$STAGE/apple-libs/"
cp -r composeApp/build/dist/wasmJs/productionExecutable "$STAGE/web"

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
