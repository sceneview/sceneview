#!/bin/bash
# poster.sh <outDir> — the hero posters, from the real scene at its opening pose.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; out="$1"; tmp="$(mktemp -d)"
cd "$here"
WAIT=3500 node shots.mjs 2560 1600 "$tmp" "wide=shift=1.375&zoom=1.3&t=0&tier=3" >/dev/null
WAIT=3500 node shots.mjs 1920 1920 "$tmp" "sq=shift=0&zoom=1.55&t=0&tier=3" >/dev/null
for w in 1280 1920 2560; do cwebp -quiet -q 80 -resize $w 0 "$tmp/wide.png" -o "$out/hero-circuit-$w.webp"; done
for w in 640 960; do cwebp -quiet -q 80 -resize $w 0 "$tmp/sq.png" -o "$out/hero-circuit-sq-$w.webp"; done
rm -rf "$tmp"
ls -la "$out"/hero-circuit-*
