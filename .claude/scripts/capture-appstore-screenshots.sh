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
# model-viewer/multi-model is still not settable from THIS side on iOS (#2785 —
# no launch argument). iOS instead carries its framing in the scenes themselves,
# via `.framingMargin(_:)` / `.cameraOrbit(azimuth:elevation:)` (#2896). The
# shared cross-store decision remains the SET and ORDER only.
DEMOS=(model-viewer dynamic-sky multi-model)
#
# The iOS half of this set was deferred once (#2896) because these three scenes
# rendered too weak to ship: dim subjects, a far default camera, and no sky at
# all in `dynamic-sky`. The measured cause was NOT the capture side — every
# bundled preset is a Radiance `.hdr`, which `EnvironmentResource(named:)`
# cannot load, so every scene silently ran with no IBL and no skybox
# (`SceneEnvironment.load()` now decodes those through ImageIO). The framing
# half was fixed scene-side with `.framingMargin(_:)` / `.cameraOrbit(_:)`.
# #2785 (a `-camera_distance` launch argument, Android parity) is still open and
# would let this script frame per slot from the outside; it is not required for
# this set.
# Per-demo render settle time (model load + camera orbit), seconds.
WAIT_SECONDS="${WAIT_SECONDS:-28}"

# Quiet window between booting a freshly-erased simulator and the first
# capture, seconds. Cheap head start, NOT a guarantee — see below.
BOOT_QUIET_SECONDS="${BOOT_QUIET_SECONDS:-25}"

# System-banner guard (#2896).
#
# `simctl` exposes NO notification-suppression API: `simctl ui` covers only
# appearance / contrast / content size and `simctl privacy` covers per-app TCC
# services — neither touches banners (checked against Xcode 26.3). And waiting
# does not fix it either: with BOOT_QUIET_SECONDS=25 the "Ready for Apple
# Intelligence" card still landed squarely in `iphone-6.9/02-dynamic-sky.png`,
# because a freshly-erased device posts it roughly a minute into the session —
# i.e. during a capture, not before the first one. That is the same class of
# leak that reached the live listing in #917.
#
# So detect it instead of hoping. Every demo now runs under `-qa_mode 1`, which
# freezes the orbit sweep, and a frozen scene is pixel-identical across
# re-shoots (measured: 0 differing pixels between two independent runs). The
# top band of the frame therefore only changes if something transient is drawn
# over it. Shoot, wait, shoot again, and compare a hash of that band: equal
# means nothing was overlaid, different means a banner was up in one of the two
# — discard the pair and retry. Exhausting the attempts FAILS the run rather
# than committing a contaminated frame.
#
# The band is the top BANNER_BAND_PCT % of the frame starting BANNER_BAND_TOP_PCT
# % down, which clears the (overridden, static) status bar and covers where iOS
# draws a notification card. No demo animates there — the models sit mid-frame.
BANNER_RECHECK_SECONDS="${BANNER_RECHECK_SECONDS:-8}"
BANNER_MAX_ATTEMPTS="${BANNER_MAX_ATTEMPTS:-4}"
BANNER_BAND_TOP_PCT="${BANNER_BAND_TOP_PCT:-6}"
BANNER_BAND_PCT="${BANNER_BAND_PCT:-16}"

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

# Hash of the notification-banner band of a screenshot. `sips` re-encodes the
# crop deterministically (verified: three crops of one file hash identically),
# so an unchanged band hashes the same across shots.
banner_band_hash() {
  local png="$1" w h y0 bh crop
  w="$(sips -g pixelWidth "$png" | awk '/pixelWidth/{print $2}')"
  h="$(sips -g pixelHeight "$png" | awk '/pixelHeight/{print $2}')"
  y0=$(( h * BANNER_BAND_TOP_PCT / 100 ))
  bh=$(( h * BANNER_BAND_PCT / 100 ))
  crop="$(mktemp -d)"
  sips -s format png -c "$bh" "$w" --cropOffset "$y0" 0 "$png" --out "$crop/band.png" >/dev/null
  shasum -a 256 "$crop/band.png" | awk '{print $1}'
  rm -rf "$crop"
}

# capture_settled <udid> <destination.png>
# Screenshots into <destination.png>, then proves nothing transient was drawn
# over the banner band by re-shooting and comparing. See the BANNER_* block.
capture_settled() {
  local udid="$1" dest="$2" attempt=1 probedir probe first second
  probedir="$(mktemp -d)"
  probe="$probedir/probe.png"
  while [ "$attempt" -le "$BANNER_MAX_ATTEMPTS" ]; do
    xcrun simctl io "$udid" screenshot "$dest" >/dev/null
    sleep "$BANNER_RECHECK_SECONDS"
    xcrun simctl io "$udid" screenshot "$probe" >/dev/null
    first="$(banner_band_hash "$dest")"
    second="$(banner_band_hash "$probe")"
    if [ "$first" = "$second" ]; then
      rm -rf "$probedir"
      return 0
    fi
    log "    banner band changed between shots — a system banner was up; retrying ($attempt/$BANNER_MAX_ATTEMPTS)"
    attempt=$((attempt + 1))
  done
  rm -rf "$probedir"
  echo "Banner band never settled for $dest after $BANNER_MAX_ATTEMPTS attempts." >&2
  echo "Refusing to keep a possibly-contaminated capture — re-run, or raise BANNER_MAX_ATTEMPTS." >&2
  return 1
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
  # Sit out the first-boot system-banner window before the first shot — see
  # BOOT_QUIET_SECONDS above.
  log "  quiet window: ${BOOT_QUIET_SECONDS}s (letting first-boot banners fire and expire)…"
  sleep "$BOOT_QUIET_SECONDS"
  local i=1
  for demo in "${DEMOS[@]}"; do
    local file
    file="$(printf '%02d-%s.png' "$i" "$demo")"
    xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
    sleep 2
    log "  launching -demo $demo (settle ${WAIT_SECONDS}s)…"
    # `-qa_mode 1` freezes each demo's orbit auto-rotation on its authored
    # hero pose. Without it the shot lands on whatever azimuth the sweep had
    # reached after WAIT_SECONDS — so the same demo produced a different pose
    # AND a different slice of the HDRI backdrop on every run, which is not a
    # capture you can review or reproduce (#2896).
    xcrun simctl launch "$udid" "$BUNDLE_ID" -demo "$demo" -qa_mode 1 >/dev/null
    sleep "$WAIT_SECONDS"
    capture_settled "$udid" "$dir/$file"
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
