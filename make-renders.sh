#!/usr/bin/env bash
# Render every view of the app and put the pictures where the model reads them.
#
#   ./make-renders.sh            every view
#   ./make-renders.sh map        one view, while iterating on it
#
# The renderer draws the real composables off-screen with sample data (no
# device, no emulator) on the JVM desktop target, so a picture comes from the
# same code the app runs and cannot quietly stop matching it. Each view is drawn
# as a phone and as a window wide enough to show what the layout does with room,
# in light and dark.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
gallery="$here/composeApp/build/gallery"
model="$here/docs/model/img"

if [ $# -gt 0 ]; then
  echo "Rendering $1…"
  "$here/gradlew" :composeApp:renderGallery -q --console=plain --no-configuration-cache "-Ponly=$1"
else
  echo "Rendering every view…"
  "$here/gradlew" :composeApp:renderGallery -q --console=plain --no-configuration-cache
fi

mkdir -p "$model/card"
count=0
for source in "$gallery"/*-phone-*.png "$gallery"/*-wide-*.png "$gallery"/*-card-*.png; do
  [ -e "$source" ] || continue
  name="$(basename "$source")"
  case "$name" in
    *-card-*.png) cp "$source" "$model/card/$name" ;;
    *) cp "$source" "$model/$name"; count=$((count + 1)) ;;
  esac
done

echo "$count renders in docs/model/img, cards in docs/model/img/card"
