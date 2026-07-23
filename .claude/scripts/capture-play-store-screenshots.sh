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
#     [--demos model-viewer,ar-pose,reflection-probes,environment] \
#     [--out samples/android-demo/distribution/play-store/en-GB/graphics] \
#     [--status-bar-px N | auto] \
#     [--variance-threshold N] \
#     [--settle SECONDS]   # per-demo wait before capture (default 15) \
#     [--no-build]
#
# Requirements:
#   - A booted Pixel-class AVD (or physical phone) with ARCore-ish capabilities.
#   - `adb` on $PATH (Android SDK platform-tools).
#   - Google's `android` CLI from developer.android.com/tools/agents/android-cli
#     (auto-installed by the helper). It avoids `adb shell screencap`'s LF/CRLF
#     corruption that the previous version had to patch in Python.
#   - Python 3 with Pillow installed (`pip3 install pillow`).
#
# Output:
#   `<out>/phone-screenshot-{1..N}.png` — 1080×2304 PNGs, Play Store 9:19.2,
#   status bar trimmed. Plus a mosaic thumbnail at
#   `<out>/.mosaic.png` for visual confirmation, kept well under the 1800 px
#   session-image limit.
#
# Why crop 96 px off the top: the Android status bar at 480 dpi on the
# stock Pixel_7a AVD is 96 px tall. Cropping it gives a clean device-frame
# preview that survives Play Store's auto-resize without showing battery /
# wifi / clock — those change every screenshot session and inflate the diff.

set -euo pipefail

# Pull in helpers for Google's Android CLI (with adb fallback for older hosts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"
android_cli_ensure || true

# ── Defaults ─────────────────────────────────────────────────────────────────
# The COMMON showcase set — the same FOUR demos, same order, as iOS's
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
#                    full-frame at 4.5 m (see camera_distance_for).
#   2 dynamic-sky    the strongest frame — a lit drone against a vivid procedural
#                    sky; a sky/sun/environment theme no other slot carries and
#                    the shot most likely to sell the SDK. Deterministic noon
#                    default (no random HDRI, unlike the dropped `materials`).
#   3 multi-model    the only non-helmet, non-sky frame — a rich photoreal-foliage
#                    fidelity shot; pulled BACK to 6.0 m for the fullest scene the
#                    fixed default camera angle allows (distance can't change the
#                    angle, so this is as composed as the diorama gets).
#
# Three strong frames, deliberately — Fable's verdict was that fewer strong shots
# beat more mixed ones. Dropped from earlier sets, and why (so nobody re-adds them
# by guesswork):
#   fog              even pulled all the way in to 1.6 m the fogged helmet stayed a
#                    low-contrast grey subject (centre-variance ~3.6k, under the 4k
#                    ship bar) — a weak store frame, not a fog showcase (#2854).
#   double-pendulum  ignores camera_distance (own auto-fit); its auto-fit frame is
#                    a tiny linkage in a ~95%-black rectangle — un-reframable (#2854).
#   materials        picked a different HDRI each launch (not reproducible) and the
#                    subject stayed small at every distance (#2874).
#   geometry         primitives are laid out wider than a phone-portrait frame;
#                    every distance clipped one at an edge (#2873). Demo-side fix.
#   animation        a static screenshot of a skeletal-animation demo is just a
#                    posed model — a visual duplicate of slot 1 on both stores.
#
# NOTE: the SET and ORDER are shared with iOS; the per-slot `camera_distance`
# framing below is Android-only (iOS has no equivalent extra, #2785), so on iOS
# multi-model renders at its scene default.
DEMOS_DEFAULT="model-viewer,dynamic-sky,multi-model"
# Canonical Play Store listing directory — the same `graphics/` subdir the
# `play-store.yml` listing-sync job uploads to the store (#1710).
OUT_DIR_DEFAULT="samples/android-demo/distribution/play-store/en-GB/graphics"
PKG="io.github.sceneview.demo"
APK_PATH="samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk"
STATUS_BAR_PX_DEFAULT=96
# Pixel_7a AVD natural resolution = 1080×2400. Crop 96 px → 1080×2304 = 9:19.2.
TARGET_HEIGHT=2304
# Model-heavy demos (model-viewer, and multi-model which loads four GLBs) fetch
# their assets asynchronously — `rememberModelInstance` returns null until the
# load lands, so the viewport is a flat dark surface for the first several
# seconds. 8s was too short on an emulator and the variance guard (correctly)
# rejected the blank frame; 15s clears the async load. multi-model's four-asset
# park is the slowest — verify all four props are present, not a partial load.
# Tune with `--settle` on a slower/faster host.
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
SKIP_BUILD=0
require_value() {
  # Guard against `--flag` with no following value under `set -u`.
  [[ $# -ge 2 ]] || { echo "[capture] missing value for $1" >&2; exit 2; }
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --demos) require_value "$@"; DEMOS="$2"; shift 2 ;;
    --out)   require_value "$@"; OUT_DIR="$2"; shift 2 ;;
    --status-bar-px) require_value "$@"; STATUS_BAR_PX="$2"; shift 2 ;;
    --variance-threshold) require_value "$@"; VARIANCE_THRESHOLD="$2"; shift 2 ;;
    --settle) require_value "$@"; SETTLE_SECONDS="$2"; shift 2 ;;
    --no-build) SKIP_BUILD=1; shift ;;
    -h|--help)
      sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done
DEMOS="${DEMOS:-$DEMOS_DEFAULT}"
OUT_DIR="${OUT_DIR:-$OUT_DIR_DEFAULT}"
STATUS_BAR_PX="${STATUS_BAR_PX:-$STATUS_BAR_PX_DEFAULT}"
VARIANCE_THRESHOLD="${VARIANCE_THRESHOLD:-$VARIANCE_THRESHOLD_DEFAULT}"
SETTLE_SECONDS="${SETTLE_SECONDS:-$SETTLE_SECONDS_DEFAULT}"

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
  if ! echo "$ALL_DEVICES_OUT" | awk '$2 == "device" {print $1}' | grep -qx "$ANDROID_SERIAL"; then
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
    STATUS_BAR_PX="$STATUS_BAR_PX_DEFAULT"
    echo "[capture] --status-bar-px auto: detection failed, using default $STATUS_BAR_PX" >&2
  fi
fi

# ── 2. Build a fresh debug APK ───────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "[capture] Building :samples:android-demo:assembleDebug" >&2
  ./gradlew :samples:android-demo:assembleDebug --stacktrace --no-daemon
fi
[[ -f "$APK_PATH" ]] || { echo "[capture] APK missing at $APK_PATH" >&2; exit 1; }

# ── 3. Install ───────────────────────────────────────────────────────────────
# Use `android run` (atomic install+launch) when available; force-stop right
# after so the `--es demo` deep-link launch on each iteration starts cold.
# Falls back to `adb install` when the android CLI is missing or on multi-device
# hosts (the `run` subcommand has no `--device` flag in v0.7).
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
  echo "[capture] android run --apks=$APK_PATH (install+launch)" >&2
  android_cli_install_and_launch "$APK_PATH" "$PKG/.MainActivity" >/dev/null || true
else
  echo "[capture] adb install -r $APK_PATH" >&2
  adb install -r "$APK_PATH" >/dev/null
fi

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
camera_distance_for() {
  case "$1" in
    model-viewer)    echo "4.5" ;;
    multi-model)     echo "6.0" ;;
    *)               echo "" ;;
  esac
}
# Framing notes (#2854) — the `camera_distance` extra is honored ONLY by demos
# built on `rememberHeroOrbitCameraManipulator` (DemoHelpers.kt); it is a silent
# no-op on any other demo, so never add one for a non-hero-orbit demo (e.g.
# double-pendulum computes its own auto-fit and never reads the extra).
#   model-viewer / multi-model  → framed above (both hero-orbit). multi-model read
#     as one cropped tree at its default, so it is pulled BACK to 6.0 m for the
#     fullest scene its fixed camera angle allows (distance cannot change angle).
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
  adb shell am force-stop "$PKG"
  if [[ -n "$CAM_DISTANCE" ]]; then
    adb shell am start -n "$PKG/.MainActivity" --es demo "$DEMO" \
      --ef camera_distance "$CAM_DISTANCE" >/dev/null
  else
    adb shell am start -n "$PKG/.MainActivity" --es demo "$DEMO" >/dev/null
  fi
  sleep "$SETTLE_SECONDS"

  RAW="$TMP_DIR/raw-$INDEX.png"
  # `android screen capture` writes the PNG directly without going through an
  # adb shell pipe, so no LF/CRLF correction is needed. The helper falls back to
  # `adb -s $serial exec-out screencap -p` if the android CLI is unavailable —
  # both paths produce clean PNG bytes.
  android_cli_screenshot "$RAW"

  OUT="$OUT_DIR/phone-screenshot-$INDEX.png"
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
# Pad / crop to the Play Store 9:19.2 target height if the source isn't 2400.
cw, ch = crop.size
if ch != target_h:
    if ch > target_h:
        crop = crop.crop((0, 0, cw, target_h))
    else:
        # Fill with black at the bottom to avoid scaling artefacts.
        bg = Image.new(crop.mode, (cw, target_h), (0, 0, 0, 255) if crop.mode == "RGBA" else (0, 0, 0))
        bg.paste(crop, (0, 0))
        crop = bg

# Variance sanity check on a 3×3 grid of 32×32 centre patches.
pixels = crop.convert("RGB").load()
samples = []
for cy in (target_h // 4, target_h // 2, 3 * target_h // 4):
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

# ── 5. Mosaic thumbnail (visual sanity, well under the 1800 px session limit) ─
python3 - "$OUT_DIR" "$TOTAL" <<'PY'
import sys, os
from PIL import Image
out_dir, total = sys.argv[1], int(sys.argv[2])
images = []
for i in range(1, total + 1):
    p = os.path.join(out_dir, f"phone-screenshot-{i}.png")
    if os.path.exists(p):
        images.append(Image.open(p))
if not images:
    sys.exit(0)
# 4-wide row of 360×768 thumbnails → 1440×768 total.
thumb_w, thumb_h = 360, 768
mosaic = Image.new("RGB", (thumb_w * len(images), thumb_h), (12, 14, 20))
for i, img in enumerate(images):
    t = img.resize((thumb_w, thumb_h), Image.LANCZOS)
    mosaic.paste(t, (i * thumb_w, 0))
mosaic_path = os.path.join(out_dir, ".mosaic.png")
mosaic.save(mosaic_path, optimize=True)
print(f"[capture]   mosaic → {mosaic_path}")
PY

echo "[capture] DONE — $TOTAL screenshots in $OUT_DIR/" >&2
echo "[capture] Inspect $OUT_DIR/.mosaic.png before pushing to the Play Store." >&2
