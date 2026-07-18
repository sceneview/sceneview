#!/usr/bin/env bash
# capture-appstore-screenshots.sh — reproducible iOS App Store screenshot capture.
#
# Builds the iOS demo, then drives it on the two App Store Connect device
# classes that require screenshots, capturing four visually-strong 3D demos
# per class. Output lands in `samples/ios-demo/appstore-screenshots/`.
#
# Why this script exists: issue #917 — the live App Store screenshots were
# stale Android-device captures, several of them blank white AR scenes. This
# produces a fresh, correctly-sized set of *real iOS-simulator* captures of
# rendered 3D content.
#
# Reproducibility trick: the app accepts a `-demo <id>` launch argument
# (see `SceneViewDemoApp.swift`) that routes straight to a demo on first
# frame. That avoids the SpringBoard "Open in …?" confirmation dialog that
# `simctl openurl sceneview://demo/<id>` raises — `openurl` is still the
# user-facing deep link; `-demo` is the headless screenshot path.
#
# Usage:
#   bash .claude/scripts/capture-appstore-screenshots.sh
#
# This script only CAPTURES. Uploading the result is a separate, explicit
# step (#2612 P2 Phase B) — no fastlane involved:
#
#   .claude/scripts/store-sync/asc_listing.py --dry-run            # what differs
#   .claude/scripts/store-sync/asc_listing.py --apply-screenshots  # push them
#
# or from CI: the `sync-screenshots` job in app-store.yml, which is
# dispatch-gated (`sync_screenshots=true`) because screenshots persist across
# App Store versions and so are not a per-release concern.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IOS_DEMO="$REPO_ROOT/samples/ios-demo"
OUT="$IOS_DEMO/appstore-screenshots"
DERIVED="${DERIVED_DATA:-/tmp/sceneview-appstore-dd}"
BUNDLE_ID="io.github.sceneview.demo"

# App Store Connect required device classes.
#   iPhone 6.9" → 1320×2868   (iPhone 16/17 Pro Max share this display class)
#   iPad 13"    → 2064×2752   (iPad Pro 13-inch M4/M5 share this display class)
IPHONE_NAME="${IPHONE_SIM:-iPhone 16 Pro Max}"
IPAD_NAME="${IPAD_SIM:-iPad Pro 13-inch (M4)}"

# Four demos that render rich, full 3D content — never empty/loading AR scenes.
DEMOS=(model-viewer dynamic-sky multi-model lighting)
# Per-demo render settle time (model load + camera orbit), seconds.
WAIT_SECONDS="${WAIT_SECONDS:-24}"

log() { printf '\033[1;36m[appstore-shots]\033[0m %s\n' "$*"; }

# Resolve a booted simulator udid by device name; fall back to the first
# available device whose name contains the requested substring.
resolve_sim() {
  local name="$1"
  xcrun simctl list devices available -j \
    | python3 -c "
import json,sys
name=sys.argv[1]
data=json.load(sys.stdin)
for runtime,devs in data['devices'].items():
    for d in devs:
        if d['name']==name:
            print(d['udid']); sys.exit(0)
for runtime,devs in data['devices'].items():
    for d in devs:
        if name.split()[0] in d['name'] and ('Pro Max' in d['name'] if 'iPhone' in name else '13' in d['name']):
            print(d['udid']); sys.exit(0)
sys.exit(1)
" "$name"
}

build_app() {
  log "Building SceneViewDemo (Debug, iphonesimulator)…"
  xcodebuild build \
    -project "$IOS_DEMO/SceneViewDemo.xcodeproj" \
    -scheme SceneViewDemo \
    -configuration Debug \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$DERIVED" \
    CODE_SIGNING_ALLOWED=NO >/dev/null
  APP_PATH="$DERIVED/Build/Products/Debug-iphonesimulator/SceneView.app"
  [ -d "$APP_PATH" ] || { echo "build produced no .app" >&2; exit 1; }
}

# capture <udid> <output-subdir>
capture_class() {
  local udid="$1" subdir="$2"
  local dir="$OUT/$subdir"
  mkdir -p "$dir"
  log "Preparing simulator $udid → $subdir"
  xcrun simctl shutdown "$udid" 2>/dev/null || true
  xcrun simctl erase "$udid"
  xcrun simctl boot "$udid"
  sleep 6
  xcrun simctl install "$udid" "$APP_PATH"
  local i=1
  for demo in "${DEMOS[@]}"; do
    local file
    file="$(printf '%02d-%s.png' "$i" "$demo")"
    xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
    sleep 2
    log "  launching -demo $demo (settle ${WAIT_SECONDS}s)…"
    xcrun simctl launch "$udid" "$BUNDLE_ID" -demo "$demo" >/dev/null
    sleep "$WAIT_SECONDS"
    xcrun simctl io "$udid" screenshot "$dir/$file" >/dev/null
    log "  wrote $subdir/$file ($(file -b "$dir/$file" | grep -oE '[0-9]+ x [0-9]+'))"
    i=$((i + 1))
  done
  xcrun simctl shutdown "$udid" 2>/dev/null || true
}

main() {
  command -v xcrun >/dev/null || { echo "xcrun not found — Xcode required" >&2; exit 1; }
  build_app

  local iphone_udid ipad_udid
  iphone_udid="$(resolve_sim "$IPHONE_NAME")" || {
    echo "No iPhone 6.9\" simulator found (looked for '$IPHONE_NAME')." >&2; exit 1; }
  ipad_udid="$(resolve_sim "$IPAD_NAME")" || {
    echo "No iPad 13\" simulator found (looked for '$IPAD_NAME')." >&2; exit 1; }

  capture_class "$iphone_udid" "iphone-6.9"
  capture_class "$ipad_udid" "ipad-13"

  log "Done. Screenshots in $OUT"
  log "Verify dimensions: iPhone 6.9\" = 1320×2868, iPad 13\" = 2064×2752"
}

main "$@"
