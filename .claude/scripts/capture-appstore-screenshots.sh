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
# or from CI: the `app-store-screenshots.yml` workflow (dispatch-only,
# `confirm=true`). It is a workflow of its OWN — not a job in app-store.yml,
# where a dispatch would also archive and upload a TestFlight build — and it
# is not wired to tags, because screenshots persist across App Store versions
# and so are not a per-release concern.
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

# The COMMON showcase set — the same THREE demos captured on Android's
# `capture-play-store-screenshots.sh`, in the same order, so the two stores
# show identical screens. Each id resolves to a DISTINCT on-screen demo on both
# stores (the #2773 requirement): on iOS every id below is a standalone
# generated scene (GeneratedScenes.swift — `dynamic-sky` → `DynamicSkyScene`,
# `multi-model` → `MultiModelScene`); on Android those two resolve through
# DeepLinkRouter to a distinct umbrella TAB (`multi-model` → the Multi-Model tab
# via ALIAS_INITIAL_TAB, not the Single Model tab that would duplicate slot 1).
# Both sides were re-verified in source (#2854). All three render rich 3D
# content, never an empty AR scene.
#
# ⚠️ This array and `DEMOS_DEFAULT` in the Android script are the SAME decision
# stored twice — a known drift hazard. Change them in one commit, or the two
# stores silently diverge again (#2773). Full order rationale (the Fable design
# verdict on the captured mosaic) lives in the Android script's header; the short
# version: model-viewer hero, then the sky-drone shot, then the multi-model
# foliage-fidelity shot. Two demos were dropped: double-pendulum (auto-fit frame
# is a tiny linkage in a near-black rectangle, un-reframable) and fog (even pulled
# fully in, the fogged helmet stayed a weak low-contrast subject) (#2854).
#
# NOTE: the per-slot `camera_distance` framing the Android script applies to
# model-viewer/multi-model has NO iOS equivalent (#2785) — iOS renders each scene
# at its default framing. The shared decision is the SET and ORDER only.
DEMOS=(model-viewer dynamic-sky multi-model)
#
# ⚠️ DEFERRED (#2896) — the committed screenshots under `appstore-screenshots/`
# are intentionally NOT regenerated from this set yet; they still show the older
# set. On iOS (RealityKit) these three scenes render too weak for the App Store —
# dim lighting, a far default camera, and `dynamic-sky` shows no sky at all — and
# iOS has no `camera_distance` framing lever to fix it from the capture side
# (#2785). That needs iOS SCENE-side work (brighter lighting, closer framing, a
# working procedural sky) tracked in #2896; re-run this script to refresh the App
# Store set only once those land. The Android half of #2854 shipped without waiting.
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
  # Uniform look with the Android capture (#2773): force DARK appearance so
  # the 3D content renders on a dark surface both stores, and clean the status
  # bar (fixed 9:41, full wifi/cellular/battery) so no live clock/signal noise
  # inflates the diff — the iOS equivalent of Android's status-bar crop.
  xcrun simctl ui "$udid" appearance dark 2>/dev/null || true
  xcrun simctl status_bar "$udid" override \
    --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
    --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 \
    2>/dev/null || true
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
