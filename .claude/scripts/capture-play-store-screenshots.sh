#!/usr/bin/env bash
#
# capture-play-store-screenshots.sh — automated Play Store screenshot capture.
#
# Reproduces the flow Thomas used in session agitated-meitner-a66271 to refresh
# the v4.0.9 phone screenshots (commit 47f60f97). Tracked in #919: that flow
# previously only lived in the chat history.
#
# Usage:
#   bash .claude/scripts/capture-play-store-screenshots.sh \
#     [--form-factor phone|tablet7|tablet10]   # default phone \
#     [--demos model-viewer,dynamic-sky,multi-model]   # default = set v2 \
#     [--out samples/android-demo/distribution/play-store/en-GB/graphics] \
#     [--status-bar-px N | auto] \
#     [--variance-threshold N] \
#     [--settle SECONDS]   # per-demo wait; default 15 (phone) / 50 (tablets) \
#     [--no-build]
#
# Requirements:
#   - A booted AVD (or physical device) matching the requested form factor.
#     Phone = Pixel-class; tablets = the `Tablet7_QA` / `Tablet10_QA` AVDs
#     (see the "Tablet capture" note below).
#   - `adb` on $PATH (Android SDK platform-tools).
#   - Google's `android` CLI from developer.android.com/tools/agents/android-cli
#     (auto-installed by the helper). It avoids `adb shell screencap`'s LF/CRLF
#     corruption that the previous version had to patch in Python.
#   - Python 3 with Pillow installed (`pip3 install pillow`).
#
# Output:
#   `<out>/<prefix>-screenshot-{1..N}.png`, where `<prefix>` is the form factor
#   (`phone`, `tablet7`, `tablet10`) — the exact filenames `play_listing.py`
#   maps onto the Play `imageType`s. Plus a mosaic thumbnail at
#   `$TMPDIR/sceneview-store-capture/mosaic-<prefix>.png` for visual confirmation,
#   kept well under the 1800 px session-image limit. The mosaic lands OUTSIDE
#   `<out>` on purpose: that directory mirrors the Play listing byte-for-byte and
#   `play_listing.py`'s tests reject any file there that no imageType claims.
#
# Why crop the status bar: it shows battery / wifi / clock, which change every
# screenshot session and inflate the diff. Cropping gives a clean device-frame
# preview that survives Play Store's auto-resize. The phone default (96 px) is
# the Pixel_7a AVD's 480 dpi bar; tablets run at a different density, so they
# default to `--status-bar-px auto` (read live from `dumpsys window`).
#
# Tablet capture (#2796): the two tablet classes MUST be captured on genuinely
# different devices. The 12 PNGs this script replaced were byte-identical
# across the 7"/10" slots — the 10" image had simply been re-uploaded into the
# 7" slot. Phone output is normalised to a fixed 9:19.2 height; tablets keep
# their NATIVE post-crop height instead, because padding a landscape tablet
# frame to a portrait phone ratio would letterbox it with black bars — the
# exact defect #917 filed against the iOS listing.

set -euo pipefail

# Pull in helpers for Google's Android CLI (with adb fallback for older hosts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"
android_cli_ensure || true

# ── Defaults ─────────────────────────────────────────────────────────────────
# The COMMON showcase set — the same THREE demos, same order, as iOS's
# `capture-appstore-screenshots.sh`, so both stores show identical screens.
# Each slot must resolve to a DISTINCT on-screen demo on both stores — the
# #2773 defect was two slots collapsing onto the same screen. That does NOT
# require a standalone demo, only a distinct one, and both cases below were
# re-verified in source (#2854):
#   - dynamic-sky  → Android `lighting-lab`, Sky tab (ALIAS_INITIAL_TAB has no
#                    entry, so it lands on tab 0 = Sky, its default); iOS
#                    `DynamicSkyScene`. A distinct sky/sun theme.
#   - multi-model  → Android `model-viewer`, Multi-Model tab (ALIAS_INITIAL_TAB
#                    = 1, applied by MainActivity.resolveInitialTab on the
#                    `--es demo` ingress — NOT the Single Model tab that would
#                    duplicate slot 1); iOS `MultiModelScene`.
#
# Order and membership are the Fable design verdict on the CAPTURED mosaic, not
# a-priori guesses (#2854). Each frame was judged as a store listing a developer
# scrolls past in two seconds; only frames that survive that bar ship:
#   1 model-viewer   the core load-any-GLB promise; flagship hero model, framed
#                    full-frame at 4.5 m on phone / 4.0 m on the tablet classes
#                    (see camera_distance_for — the distance is form-factor
#                    specific since #3106).
#   2 dynamic-sky    the strongest frame — a lit drone against a vivid procedural
#                    sky; a sky/sun/environment theme no other slot carries and
#                    the shot most likely to sell the SDK. Deterministic noon
#                    default — no HDRI backdrop at all (see the `materials` note
#                    at the bottom of this list: it never picked one at random).
#   3 multi-model    the only non-helmet, non-sky frame — a rich photoreal-foliage
#                    fidelity shot. Since #2913 the scene frames ITSELF from the
#                    live viewport aspect, so it deliberately takes no
#                    `camera_distance` entry below: a fixed metre value would
#                    override the per-viewport framing with a number tuned on one
#                    screen shape.
#
# Three strong frames, deliberately — Fable's verdict was that fewer strong shots
# beat more mixed ones. Dropped from earlier sets, and why (so nobody re-adds them
# by guesswork):
#   fog              even pulled all the way in to 1.6 m the fogged helmet stayed a
#                    low-contrast grey subject (centre-variance ~3.6k, under the 4k
#                    ship bar) — a weak store frame, not a fog showcase (#2854).
#   double-pendulum  ignores camera_distance (own auto-fit); its auto-fit frame is
#                    a tiny linkage in a ~95%-black rectangle — un-reframable (#2854).
#   materials        was not reproducible: it opened on a STREAMED slug, so the
#                    subject depended on the API key / network / cache (a streamed
#                    insect on one device, the bundled helmet on another), and it
#                    drew the `studio_2k` skybox — a living-room interior, despite
#                    its "neutral / studio / product" catalog tags — behind an
#                    orbiting camera, so the backdrop changed with capture timing.
#                    The subject also stayed small at every distance. The DEMO side
#                    is fixed (#2874: bundled default subject, one fixed studio
#                    HDRI, subject-independent framing) and the id is eligible
#                    again — but do NOT add it back here without capturing it and
#                    LOOKING at the frame against the other slots first.
#   geometry         the clipping is FIXED as of #2873 (2x2 cluster, a camera
#                    distance derived from the frustum relation, camera_distance
#                    wired in), but the id stays out for two capture-side reasons:
#                    the cluster leaves the frame CENTRE empty, so the centre-patch
#                    variance guard below reads it as blank (measured 0.1 against
#                    the 100 floor), and the free-running Y-spin turns the flat
#                    plane edge-on at unpredictable instants. Re-add only after
#                    judging a fresh mosaic — a green capture is not the bar.
#   animation        a static screenshot of a skeletal-animation demo is just a
#                    posed model — a visual duplicate of slot 1 on both stores.
#
# NOTE: the SET and ORDER are shared with iOS; the per-slot `camera_distance`
# framing below is Android-only (iOS has no equivalent extra, #2785), so on iOS
# multi-model renders at its scene default.
DEMOS_DEFAULT="model-viewer,dynamic-sky,multi-model"
# TABLET RUNS SHOOT ALL THREE AGAIN — `multi-model` came back when #2913 landed,
# per the instruction this block carried while it was dropped (#2915).
# What had been measured on both tablet AVDs against build 4.25.0: a tablet
# portrait frame is ~0.64 w/h against the phone's ~0.47, and at that aspect the
# capture landed on a wooden post against the backdrop wall — no foliage. That
# was real, but BOTH stated causes were wrong, and it is worth recording which:
#   1. The framing was broken at EVERY aspect, not just on tablets. The section
#      aimed a fixed camera at the formation centre it authored, while the
#      library's `autoCenterContent` pass had already translated that formation
#      onto the world origin — so the lens sat ~0.6 m from the centroid, inside
#      the subject. A narrow phone frame cropped that into something that reads as
#      texture; a wider tablet frame exposed it. The section now derives its
#      distance from the live viewport aspect and both classes frame the formation
#      full height.
#   2. `camera_distance` was inert here not because it "moves the camera ALONG an
#      angle it cannot change", but because the section read no `DemoSettings` at
#      all — the 2.5 / 3.5 / 4.5 m probe compared three frames that each discarded
#      the extra. It honours the override now.
# Also worth knowing before reading any capture of this demo as evidence: without
# a Sketchfab key it renders bundled fallbacks (a lantern, a lantern, a shiba, a
# soldier) instead of the streamed oaks — a different scene that renders perfectly
# and passes every guard. The wooden post was that lantern's post.
# The variance guard PASSES a bad frame here (2227 on 10", 2827 on 7"), so a green
# capture is never the evidence: judge the mosaic by eye after every run.
DEMOS_DEFAULT_TABLET="model-viewer,dynamic-sky,multi-model"
# Canonical Play Store listing directory — the same `graphics/` subdir the
# `play-store.yml` listing-sync job uploads to the store (#1710).
OUT_DIR_DEFAULT="samples/android-demo/distribution/play-store/en-GB/graphics"
PKG="io.github.sceneview.demo"
APK_PATH="samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk"
# Form factor → output filename prefix + framing policy. `phone` is the historical
# behaviour; the tablet classes were added in #2796.
FORM_FACTOR_DEFAULT="phone"
# Model-heavy demos (model-viewer, and multi-model which loads four GLBs) load
# their assets asynchronously — `rememberModelInstance` returns null until the
# load lands, so the viewport is a flat dark surface for the first several
# seconds. 8s was too short on an emulator and the variance guard (correctly)
# rejected the blank frame; 15s clears the async load. Tune with `--settle`.
SETTLE_SECONDS_DEFAULT=15
# Default 100 keeps small-footprint hero shots (model fills only the centre
# 1/9 of the frame, variance ~60-80) from being false-rejected. The
# `--variance-threshold N` flag exists as an escape hatch for known-noisy
# captures (e.g. raise to 300 when QA finds a Material 3 splash sneaking
# through at variance ~110-150). #975.
VARIANCE_THRESHOLD_DEFAULT=100

DEMOS=""
OUT_DIR=""
STATUS_BAR_PX=""
VARIANCE_THRESHOLD=""
SETTLE_SECONDS=""
FORM_FACTOR=""
SKIP_BUILD=0
require_value() {
  # Guard against `--flag` with no following value under `set -u`.
  [[ $# -ge 2 ]] || { echo "[capture] missing value for $1" >&2; exit 2; }
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --demos) require_value "$@"; DEMOS="$2"; shift 2 ;;
    --form-factor) require_value "$@"; FORM_FACTOR="$2"; shift 2 ;;
    --out)   require_value "$@"; OUT_DIR="$2"; shift 2 ;;
    --status-bar-px) require_value "$@"; STATUS_BAR_PX="$2"; shift 2 ;;
    --variance-threshold) require_value "$@"; VARIANCE_THRESHOLD="$2"; shift 2 ;;
    --settle) require_value "$@"; SETTLE_SECONDS="$2"; shift 2 ;;
    --no-build) SKIP_BUILD=1; shift ;;
    -h|--help)
      sed -n '2,48p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done
# NB: DEMOS stays unresolved here on purpose — its default is form-factor
# specific (see DEMOS_DEFAULT_TABLET; the two sets are identical again since
# #2913, but the split stays so a class can diverge without a code change) and
# FORM_FACTOR is only validated in the case block below. Resolved after `esac`.
OUT_DIR="${OUT_DIR:-$OUT_DIR_DEFAULT}"
VARIANCE_THRESHOLD="${VARIANCE_THRESHOLD:-$VARIANCE_THRESHOLD_DEFAULT}"
FORM_FACTOR="${FORM_FACTOR:-$FORM_FACTOR_DEFAULT}"

# ── Form-factor policy ───────────────────────────────────────────────────────
# PREFIX  — output filename stem; must stay in lockstep with the names
#           `store-sync/play_listing.py` maps onto the Play `imageType`s.
# TARGET_HEIGHT — 0 means "keep the native post-crop height" (tablets). Only the
#           phone class is normalised, to the 9:19.2 the Play listing expects.
# STATUS_BAR_PX default — 96 px is the Pixel_7a's 480 dpi bar; the tablet AVDs
#           run at 320 dpi with a different bar height, so they resolve it live.
case "$FORM_FACTOR" in
  phone)
    PREFIX="phone"
    DEMOS_DEFAULT_FF="$DEMOS_DEFAULT"
    # Pixel_7a AVD natural resolution = 1080×2400. Crop 96 px → 1080×2304 = 9:19.2.
    TARGET_HEIGHT=2304
    STATUS_BAR_PX_DEFAULT=96
    STATUS_BAR_PX_FALLBACK=96
    ;;
  tablet7|tablet10)
    PREFIX="$FORM_FACTOR"
    DEMOS_DEFAULT_FF="$DEMOS_DEFAULT_TABLET"
    TARGET_HEIGHT=0
    STATUS_BAR_PX_DEFAULT="auto"
    # MUST be numeric and MUST NOT be the "auto" default: when live detection
    # fails, the resolver falls back to this, and falling back to the literal
    # string "auto" reaches Python as int("auto") and aborts the run.
    # 48 px is the measured bar height at the tablet AVDs' 320 dpi.
    STATUS_BAR_PX_FALLBACK=48
    # A tablet framebuffer is ~4 Mpx and the demo GLBs load markedly slower there
    # than on the phone rig — measured live, the hero model was still loading at
    # 25 s and the variance guard (correctly) rejected the black frame. Overridable
    # with `--settle`.
    SETTLE_SECONDS_DEFAULT=50
    ;;
  *)
    echo "[capture] --form-factor must be phone|tablet7|tablet10 (got '$FORM_FACTOR')" >&2
    exit 2 ;;
esac
STATUS_BAR_PX="${STATUS_BAR_PX:-$STATUS_BAR_PX_DEFAULT}"
SETTLE_SECONDS="${SETTLE_SECONDS:-$SETTLE_SECONDS_DEFAULT}"
# Form-factor default (see DEMOS_DEFAULT_TABLET); an explicit --demos still wins.
DEMOS="${DEMOS:-$DEMOS_DEFAULT_FF}"

# ── 1. Recover an offline AVD if needed ──────────────────────────────────────
if ! adb devices | grep -qE "^emulator-|^[0-9A-F]{8}.*device$"; then
  echo "[capture] No device responding; cycling adb server" >&2
  adb kill-server || true
  adb start-server
  sleep 2
  if ! adb devices | grep -qE "device$"; then
    echo "[capture] No device after restart. Boot an AVD or plug a phone." >&2
    exit 1
  fi
fi

# Reject multiple devices unless ANDROID_SERIAL pins one — Play screenshots must
# come from one stable surface. Without this, the script ran against a
# non-deterministic adb default device → non-reproducible output (#975).
ALL_DEVICES_OUT=$(adb devices)
DEVICE_LINES=$(echo "$ALL_DEVICES_OUT" | grep -cE "device$" || true)
if [[ "$DEVICE_LINES" -ne 1 ]] && [[ -z "${ANDROID_SERIAL:-}" ]]; then
  echo "[capture] $DEVICE_LINES devices in 'device' state visible to adb." >&2
  echo "[capture] Set ANDROID_SERIAL=<serial> to pick one explicitly:" >&2
  echo "$ALL_DEVICES_OUT" >&2
  exit 1
fi
# If ANDROID_SERIAL is set, verify it matches a device-state entry — otherwise
# `adb install` / `screencap` later fail with cryptic errors after the gradle
# build has already burned ~90 s.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! echo "$ALL_DEVICES_OUT" | awk '$2 == "device" {print $1}' | grep -qFx "$ANDROID_SERIAL"; then
    echo "[capture] ANDROID_SERIAL='$ANDROID_SERIAL' is not in 'device' state. adb sees:" >&2
    echo "$ALL_DEVICES_OUT" >&2
    exit 1
  fi
fi
# Diagnostic when one device is targeted but others are visible (offline /
# unauthorized) — the user may not realise the second slot exists.
if [[ "$DEVICE_LINES" -lt $(echo "$ALL_DEVICES_OUT" | grep -cE "^[A-Za-z0-9._-]+\s" || true) ]]; then
  echo "[capture] Note: extra devices visible (offline/unauthorized). adb sees:" >&2
  echo "$ALL_DEVICES_OUT" >&2
fi

# Resolve --status-bar-px auto. Reads `dumpsys window` and parses the
# `InsetsSource ... type=statusBars frame=[0,0][W,H]` line — H is the statusbar
# height in pixels (173 on a Pixel_7a AVD @ 480dpi, NOT 96 — confirmed live).
# The header-line + h=NNN approach the 2-agent review of #975 first tried did
# not work because (a) `Window{...}` precedes `StatusBar`, not the other way,
# and (b) h=NNN lives in a different InsetsSource block on Android 13+. The
# `InsetsSource ... type=statusBars frame=` form is stable across Android 11-15.
if [[ "$STATUS_BAR_PX" = "auto" ]]; then
  DETECTED=$(adb shell 'dumpsys window' 2>/dev/null \
    | grep -E 'type=statusBars.*frame=\[' \
    | head -1 \
    | sed -E 's/.*frame=\[[0-9]+,[0-9]+\]\[[0-9]+,([0-9]+)\].*/\1/' \
    || true)
  if [[ -n "${DETECTED:-}" ]] && [[ "$DETECTED" =~ ^[0-9]+$ ]] && [[ "$DETECTED" -gt 0 ]]; then
    STATUS_BAR_PX="$DETECTED"
    echo "[capture] --status-bar-px auto → $STATUS_BAR_PX" >&2
  else
    STATUS_BAR_PX="$STATUS_BAR_PX_FALLBACK"
    echo "[capture] --status-bar-px auto: detection failed, using fallback $STATUS_BAR_PX" >&2
  fi
fi

# ── 1b. Streamed-asset key guard (#2913) ─────────────────────────────────────
# `multi-model` renders whatever the Sketchfab resolver hands back. WITH a key it
# streams the `park` category — the photoreal oaks the slot exists for. WITHOUT
# one the resolver falls back to per-slug BUNDLED models (a lantern, a lantern, a
# shiba, a soldier), so the same demo id captures an entirely different scene and
# nothing downstream can tell: the frame renders fully, the foreground guard
# passes, and centre-variance is high either way. The "wooden support post" in
# #2913's tablet frames is the bundled lantern's post, while the committed
# phone-screenshot-3.png it was compared against is a streamed oak — two different
# scenes, on top of the (real, separate) framing defect that issue fixed.
#
# Same sources gradle reads (samples/android-demo/build.gradle): the
# SKETCHFAB_API_KEY env var, else `sketchfab.api.key` in local.properties. WARN
# only — a keyless capture of the other slots is perfectly valid, and this script
# must stay usable without secrets (#2343).
if echo ",$DEMOS," | grep -q ",multi-model,"; then
  if [[ -z "${SKETCHFAB_API_KEY:-}" ]] &&
     ! grep -qE '^[[:space:]]*sketchfab\.api\.key[[:space:]]*=[[:space:]]*[^[:space:]]' \
       local.properties 2>/dev/null; then
    echo "[capture] WARNING: no Sketchfab API key (SKETCHFAB_API_KEY / local.properties)." >&2
    echo "[capture] 'multi-model' will render its BUNDLED fallbacks, not the streamed park" >&2
    echo "[capture] scene — a different picture that no guard here can detect. Do not ship" >&2
    echo "[capture] the result as a store slot without judging the mosaic (#2913)." >&2
  fi
fi

# ── 2. Build a fresh debug APK ───────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "[capture] Building :samples:android-demo:assembleDebug" >&2
  ./gradlew :samples:android-demo:assembleDebug --stacktrace --no-daemon
fi
[[ -f "$APK_PATH" ]] || { echo "[capture] APK missing at $APK_PATH" >&2; exit 1; }

# ── 3. Install ───────────────────────────────────────────────────────────────
# Install through `android_cli_install_and_launch`, which proves the install
# landed against the device's `lastUpdateTime` instead of trusting an exit code
# (#2990) and falls back to `adb install -r` itself. Force-stop right after so
# the `--es demo` deep-link launch on each iteration starts cold.
# ⚠️ Never trust `android run`'s exit code here. On this host it printed
# `No matching components found for type ACTIVITY with name …/.MainActivity`
# and STILL exited 0, so the `||` fallback below never fired and the capture
# silently shot a 16-hour-old build (device 4.23.0 vs freshly-built 4.24.0).
# Every screenshot looked plausible, so nothing downstream caught it — the
# stale-install class of #2305/#2411. The install is therefore VERIFIED against
# the device rather than assumed: if the package's lastUpdateTime did not move,
# fall back to `adb install -r`, which reports Success/Failure honestly.
installed_stamp() {
  adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell dumpsys package "$PKG" 2>/dev/null \
    | awk -F= '/lastUpdateTime=/ {print $2; exit}'
}
STAMP_BEFORE="$(installed_stamp)"

if android_cli_locate && [[ "$DEVICE_LINES" -eq 1 ]]; then
  echo "[capture] android_cli_install_and_launch $APK_PATH (install+launch, adb fallback)" >&2
  android_cli_install_and_launch "$APK_PATH" "$PKG/.MainActivity" >/dev/null || true
else
  echo "[capture] adb install -r $APK_PATH" >&2
  adb install -r "$APK_PATH" >/dev/null
fi

# Verify the install actually LANDED against the device, not the exit code
# (#2796 hit the same trap on tablet AVDs — `pm path` empty, `pm clear` printing
# "Failed" while still exiting 0). The lastUpdateTime stamp is the stronger check:
# it also catches the stale-build no-op where an OLD build stays installed (so
# `pm path` is non-empty) but the fresh install silently did nothing.
if [[ -n "$STAMP_BEFORE" && "$(installed_stamp)" == "$STAMP_BEFORE" ]]; then
  echo "[capture] install did NOT land (lastUpdateTime unchanged) — adb install -r" >&2
  adb install -r "$APK_PATH" >/dev/null
fi
[[ -n "$(installed_stamp)" ]] || { echo "[capture] $PKG is not installed after install step" >&2; exit 1; }
echo "[capture] on-device build: $(adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell dumpsys package "$PKG" | awk -F= '/versionName=/ {print $2; exit}')" >&2

# ── 3b. Force DARK mode (#2773) ──────────────────────────────────────────────
# Uniform look with the iOS capture: render the 3D content on a dark surface
# both stores. `cmd uimode night yes` flips the system dark theme; the demo
# app follows DayNight. Non-fatal on API levels/emulators that reject it.
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell "cmd uimode night yes" >/dev/null 2>&1 || true

# ── 3c. Portrait for the tablet classes (#2796) ──────────────────────────────
# The demos frame their scene for a portrait viewport. Captured in a tablet's
# natural LANDSCAPE orientation the subject collapses to ~5% of the frame width
# — model-viewer, lighting and double-pendulum all came out as a speck floating
# in black, and the double-pendulum frame was uniform enough (variance 0.1) that
# the guard rejected it outright. Rotating to portrait restores the phone-like
# framing the scenes are authored for.
#
# The rotation constant is DERIVED, never hardcoded: a 10" tablet's natural
# orientation is landscape (portrait = user_rotation 1) while a 7" tablet's is
# portrait (user_rotation 0). Reading `wm size` and rotating only when the frame
# is wider than it is tall works for either, and for a phone too.
#
# Must run BEFORE the capture loop: a rotation recreates the activity, and the
# re-created instance restores its own state instead of honouring the `--es demo`
# extra (observed live — the same state-restoration bug the per-demo `pm clear`
# guards against).
if [[ "$FORM_FACTOR" != "phone" ]]; then
  # Drop any leftover display override first. A stale `wm size` override (seen
  # live as `Override size: 1080x2424` on a 2560x1600 tablet) silently shrinks
  # every capture to a phone-shaped viewport, which would defeat the entire
  # point of a tablet class. `reset` is a no-op when none is set.
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  # Read the PHYSICAL line specifically: with an override present `wm size`
  # prints two lines, and the override is the one we just cleared.
  WM_SIZE=$(adb shell wm size 2>/dev/null | tr -d '\r' | grep -i "physical" | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' | head -1 || true)
  if [[ -n "${WM_SIZE:-}" ]]; then
    SCREEN_W=${WM_SIZE% *}
    SCREEN_H=${WM_SIZE#* }
    if [[ "$SCREEN_W" -gt "$SCREEN_H" ]]; then
      echo "[capture] $FORM_FACTOR is landscape-native (${SCREEN_W}x${SCREEN_H}) → rotating to portrait" >&2
      adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
      adb shell settings put system user_rotation 1 >/dev/null 2>&1 || true
    else
      echo "[capture] $FORM_FACTOR is already portrait (${SCREEN_W}x${SCREEN_H})" >&2
      adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
      adb shell settings put system user_rotation 0 >/dev/null 2>&1 || true
    fi
    sleep 3  # let the window manager settle before the first launch
  else
    echo "[capture] could not read 'wm size' — leaving orientation untouched" >&2
  fi
fi

# ── 3d. One-shot deterministic reset + warm-up (#2796) ───────────────────────
# Why `pm clear` at all: once the app has saved state it restores the
# LAST-VIEWED demo and silently ignores the `--es demo` extra. Observed live —
# `--es demo model-viewer` re-opened "Picking & Collision", so a whole capture
# run can be of the wrong screens while every automated check passes.
#
# Why exactly ONCE, here, and not per demo: clearing app data also drops the
# asset cache, and the first launch afterwards spends the settle window loading
# the model instead of rendering it (captured as a black viewport). Doing it
# once and then warming the cache up costs one extra launch for the whole run.
echo "[capture] resetting app state (pm clear) + warming the asset cache" >&2
adb shell pm clear "$PKG" >/dev/null
adb shell am start -n "$PKG/.MainActivity" --es demo "${DEMOS%%,*}" >/dev/null
sleep "$SETTLE_SECONDS"
adb shell am force-stop "$PKG"

# ── 4. Capture loop ──────────────────────────────────────────────────────────
# Per-demo store framing, in metres, via the `camera_distance` intent extra
# (#2652). A demo's DEFAULT framing is tuned for interactive use — you orbit and
# pinch — not for a still that must read at Play Store thumbnail size. Left
# alone, `model-viewer` renders the helmet small on black: measured centre-patch
# variance 98.3, i.e. the frame is ~98% empty, which is both a weak screenshot
# and close enough to the blank-capture guard (threshold 100) that the run
# passes or fails on where the auto-orbit happens to be. At 4.5 m the whole
# helmet fills the frame (variance ~1979) and the result is deterministic.
#
# Values were chosen by LOOKING at each capture, never by maximising variance:
# 2.0 m and 3.0 m score far higher (2042 / 2747) because the camera is *inside*
# the helmet, which is a high-variance, unusable frame. Variance detects blank,
# it does not detect good — see #2796 and the `--variance-threshold` note above.
#
# Echoes empty for demos that frame well by default. bash 3.2 on macOS has no
# associative arrays, hence the case statement (see project memory on 3.2).
#
# The value is FORM-FACTOR specific (#3106). 4.5 m was judged on the phone's
# ~0.47 w/h portrait frame; re-judged on the tablet classes it left the helmet
# at roughly a third of the frame height with ~60% empty black around it — a
# weak store frame, and the exact "assume the phone number transfers" trap this
# function now avoids. Probed live on Tablet7_QA against the captured frame:
#   4.5 m  subject too small, most of the frame is black — rejected
#   3.0 m  fills the frame but the chin piece is CROPPED at the bottom edge
#   3.5 m  fills the frame on a 3/4 pose, but the hero orbit is free-running and
#          the head-on pose CLIPS against the left edge — rejected on the pose
#          lottery, not on the one frame that happened to be captured
#   4.0 m  fills the frame and survives every orbit instant (probed at three
#          points of the same orbit, widest pose still has margin) — SHIPPED
# One tablet value covers both classes because both AVDs rotate to the SAME
# portrait aspect: Tablet7_QA is 1200x1920 natively, Tablet10_QA is 2560x1600
# natively and §3c rotates it to 1600x2560 before the capture loop — 0.625
# w/h either way. Re-probe if an AVD with a different ratio is ever added.
camera_distance_for() {
  case "$1" in
    model-viewer)
      case "$FORM_FACTOR" in
        tablet7|tablet10) echo "4.0" ;;
        *)                echo "4.5" ;;
      esac
      ;;
    *)               echo "" ;;
  esac
}
# Framing notes (#2854) — the `camera_distance` extra is honored only by demos
# that actually read `DemoSettings.cameraDistance`: everything built on
# `rememberHeroOrbitCameraManipulator` (DemoHelpers.kt), plus the Multi-Model
# section since #2913. It is a silent no-op anywhere else, so never add one for a
# demo that computes its own framing (e.g. double-pendulum has its own auto-fit
# and never reads the extra).
#   model-viewer → framed above (hero-orbit), per form factor since #3106.
#   multi-model  → deliberately UNFRAMED, and the 6.0 m that used to sit here is
#     gone (#2913). That value was written when the section built a stock
#     `rememberCameraManipulator`, which reads no DemoSettings at all — the extra
#     never reached the scene, which is why probing 2.5 / 3.5 / 4.5 m produced
#     three identical frames and looked like "distance cannot change the angle".
#     The section now computes its distance from its own formation size and the
#     LIVE viewport aspect, so it frames itself correctly on phone and tablet;
#     passing a fixed metre value here would override that per-viewport framing
#     with a single number tuned on one screen shape.
#   dynamic-sky  → hero-orbit and honors the extra, but Fable's verdict was ACCEPT
#     at its default noon framing, so it is left unframed (echo empty).

mkdir -p "$OUT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

IFS=',' read -ra DEMO_ARR <<< "$DEMOS"
INDEX=1
for DEMO in "${DEMO_ARR[@]}"; do
  DEMO="${DEMO// /}"  # trim whitespace
  CAM_DISTANCE="$(camera_distance_for "$DEMO")"
  echo "[capture] [$INDEX] $DEMO${CAM_DISTANCE:+ (camera_distance=$CAM_DISTANCE)}" >&2

  # `am force-stop` + `--es demo <id>` stay on adb: `android run` in v0.7 has no
  # equivalent for either (no `--force-stop` flag, no intent-extras forwarding).
  # Re-evaluate when CLI v0.8+ ships those flags. Same allow-listed ingress
  # channel as the QA flow + #958.
  #
  # A force-stop per demo, NOT a `pm clear` per demo (#2796): clearing app data
  # every iteration also drops the asset cache, and the model then fails to load
  # inside the settle window — model-viewer captured a black viewport with the
  # "Surprise me" button and nothing else. The one-shot `pm clear` in §3d gives
  # the determinism; a force-stop is enough to relaunch cold from there.
  adb shell am force-stop "$PKG"
  if [[ -n "$CAM_DISTANCE" ]]; then
    adb shell am start -n "$PKG/.MainActivity" --es demo "$DEMO" \
      --ef camera_distance "$CAM_DISTANCE" >/dev/null
  else
    adb shell am start -n "$PKG/.MainActivity" --es demo "$DEMO" >/dev/null
  fi
  sleep "$SETTLE_SECONDS"

  # Foreground guard (#2796). The variance check below only rejects a UNIFORM
  # frame, so it happily accepted an Android LAUNCHER screenshot (variance 679)
  # when the app had died mid-series — a home screen, complete with Play Store
  # icons, came within one commit of the live listing. Assert the app actually
  # owns the screen before capturing anything.
  # `topResumedActivity=` is the authoritative "owns the screen" line; fall back
  # to `ResumedActivity:` only when it is absent, rather than grepping both at
  # once — on a multi-display dump the first match of a combined pattern can
  # belong to another display and fail the guard for the wrong reason.
  FOREGROUND=$(adb shell dumpsys activity activities 2>/dev/null \
    | grep -E "topResumedActivity=" | head -1 || true)
  if [[ -z "${FOREGROUND:-}" ]]; then
    FOREGROUND=$(adb shell dumpsys activity activities 2>/dev/null \
      | grep -E "ResumedActivity[:=]" | head -1 || true)
  fi
  if ! echo "$FOREGROUND" | grep -qF "$PKG"; then
    echo "[capture] $DEMO: '$PKG' is NOT in the foreground after ${SETTLE_SECONDS}s." >&2
    echo "[capture] Resumed activity was: ${FOREGROUND:-<none>}" >&2
    echo "[capture] Refusing to capture — the app likely crashed or never started." >&2
    exit 1
  fi

  RAW="$TMP_DIR/raw-$INDEX.png"
  # `android screen capture` writes the PNG directly without going through an
  # adb shell pipe, so no LF/CRLF correction is needed. The helper falls back to
  # `adb -s $serial exec-out screencap -p` if the android CLI is unavailable —
  # both paths produce clean PNG bytes.
  android_cli_screenshot "$RAW"

  OUT="$OUT_DIR/$PREFIX-screenshot-$INDEX.png"
  python3 - "$RAW" "$OUT" "$STATUS_BAR_PX" "$TARGET_HEIGHT" "$VARIANCE_THRESHOLD" <<'PY'
import sys
from PIL import Image
import math

raw, out, status_px, target_h, var_thresh = sys.argv[1:6]
status_px = int(status_px)
target_h = int(target_h)
var_thresh = float(var_thresh)

img = Image.open(raw)
w, h = img.size
# Crop the status bar; preserve full width.
crop = img.crop((0, status_px, w, h))
# target_h == 0 → keep the native post-crop height (tablet classes). Padding a
# landscape tablet frame up to a portrait phone height would letterbox it with
# black bars, which is the #917 defect, so only the phone class is normalised.
cw, ch = crop.size
if target_h and ch != target_h:
    if ch > target_h:
        crop = crop.crop((0, 0, cw, target_h))
    else:
        # Fill with black at the bottom to avoid scaling artefacts.
        bg = Image.new(crop.mode, (cw, target_h), (0, 0, 0, 255) if crop.mode == "RGBA" else (0, 0, 0))
        bg.paste(crop, (0, 0))
        crop = bg

# Variance sanity check on a 3×3 grid of 32×32 centre patches. Sample against the
# ACTUAL post-normalisation size — with target_h == 0 the old `target_h`-derived
# rows would all collapse to y=0 and read the top edge instead of the centre.
cw, ch = crop.size
pixels = crop.convert("RGB").load()
samples = []
for cy in (ch // 4, ch // 2, 3 * ch // 4):
    for cx in (cw // 4, cw // 2, 3 * cw // 4):
        for dy in range(-16, 16, 8):
            for dx in range(-16, 16, 8):
                r, g, b = pixels[cx + dx, cy + dy]
                samples.append((r + g + b) / 3)
mean = sum(samples) / len(samples)
variance = sum((s - mean) ** 2 for s in samples) / len(samples)
if variance < var_thresh:
    sys.exit(f"[variance] {variance:.1f} < {var_thresh} — capture looks blank/uniform")

crop.save(out, optimize=True)
print(f"[capture]   variance={variance:.1f}  → {out}")
PY

  INDEX=$((INDEX + 1))
done
TOTAL=$((INDEX - 1))

# ── 4b. Prune stale trailing slots ──────────────────────────────────────────
# $OUT_DIR is a byte-for-byte mirror of the Play listing and `play_listing.py`
# selects by GLOB, not by count — so a slot this run did not write is still
# uploaded at the next tag. When the set shrinks (5 → 3 on phone in #2855,
# 5 → 2 on tablets in #2913) the leftovers are frames from an older app build
# that nobody looks at again: the mosaic below iterates 1..TOTAL and cannot
# render them. Drop them here, AFTER the capture loop completed, so an aborted
# run (set -e, foreground guard, variance guard) can never empty the mirror.
STALE=$((TOTAL + 1))
while [[ -f "$OUT_DIR/$PREFIX-screenshot-$STALE.png" ]]; do
  echo "[capture] pruning stale slot $PREFIX-screenshot-$STALE.png (set is now $TOTAL)" >&2
  rm -f "$OUT_DIR/$PREFIX-screenshot-$STALE.png"
  STALE=$((STALE + 1))
done

# ── 5. Mosaic thumbnail (visual sanity, well under the 1800 px session limit) ─
# Written OUTSIDE $OUT_DIR on purpose: that directory is a byte-for-byte mirror
# of the Play listing, and `play_listing.py`'s test suite fails on any file there
# that no `imageType` pattern claims (it caught this exact mosaic). Keep review
# artefacts out of the mirror.
MOSAIC_DIR="${TMPDIR:-/tmp}/sceneview-store-capture"
mkdir -p "$MOSAIC_DIR"
python3 - "$OUT_DIR" "$TOTAL" "$PREFIX" "$MOSAIC_DIR" <<'PY'
import sys, os
from PIL import Image
out_dir, total, prefix, mosaic_dir = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
images = []
for i in range(1, total + 1):
    p = os.path.join(out_dir, f"{prefix}-screenshot-{i}.png")
    if os.path.exists(p):
        images.append(Image.open(p))
if not images:
    sys.exit(0)
# Preserve each capture's aspect ratio — the old fixed 360×768 cell squashed a
# landscape tablet frame into a portrait box, making the mosaic useless as a
# sanity check for exactly the class this script now also captures.
# Budget a total width of 1600 px so the mosaic stays under the 1800 px
# session-image limit whatever the capture count.
MOSAIC_MAX_W = 1600
cell_w = max(1, MOSAIC_MAX_W // len(images))
src_w, src_h = images[0].size
cell_h = max(1, round(cell_w * src_h / src_w))
mosaic = Image.new("RGB", (cell_w * len(images), cell_h), (12, 14, 20))
for i, img in enumerate(images):
    # Fit within the cell (a shot at a different size still lands centred).
    scale = min(cell_w / img.width, cell_h / img.height)
    t = img.resize((max(1, round(img.width * scale)), max(1, round(img.height * scale))), Image.LANCZOS)
    mosaic.paste(t, (i * cell_w + (cell_w - t.width) // 2, (cell_h - t.height) // 2))
mosaic_path = os.path.join(mosaic_dir, f"mosaic-{prefix}.png")
mosaic.save(mosaic_path, optimize=True)
print(f"[capture]   mosaic → {mosaic_path}")
PY

echo "[capture] DONE — $TOTAL $PREFIX screenshots in $OUT_DIR/" >&2
echo "[capture] Inspect $MOSAIC_DIR/mosaic-$PREFIX.png before pushing to the Play Store." >&2
