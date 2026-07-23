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
#     [--demos model-viewer,lighting,materials,geometry,double-pendulum] \
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
# The COMMON showcase set (#2773) — the same five demos, same order, as iOS's
# `capture-appstore-screenshots.sh`, so both stores show identical screens.
# The previous default (ar-pose,reflection-probes,environment) had rotted:
# post-consolidation `reflection-probes` AND `environment` both alias
# `lighting-lab` (DeepLinkRouter.kt), so screenshots 3 & 4 captured the SAME
# demo, and `ar-pose` is a placeholder on the iOS simulator. Every id below is
# a standalone demo on BOTH platforms.
DEMOS_DEFAULT="model-viewer,lighting,materials,geometry,double-pendulum"
# Canonical Play Store listing directory — the same `graphics/` subdir the
# `play-store.yml` listing-sync job uploads to the store (#1710).
OUT_DIR_DEFAULT="samples/android-demo/distribution/play-store/en-GB/graphics"
PKG="io.github.sceneview.demo"
APK_PATH="samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk"
# Form factor → output filename prefix + framing policy. `phone` is the historical
# behaviour; the tablet classes were added in #2796.
FORM_FACTOR_DEFAULT="phone"
# Model-heavy demos (model-viewer, lighting, materials) load their GLB
# asynchronously — `rememberModelInstance` returns null until the load lands,
# so the viewport is a flat dark surface for the first several seconds. 8s was
# too short on an emulator and the variance guard (correctly) rejected the blank
# frame; 15s clears the async load. Tune with `--settle` on a slower/faster host.
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
DEMOS="${DEMOS:-$DEMOS_DEFAULT}"
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
    # Pixel_7a AVD natural resolution = 1080×2400. Crop 96 px → 1080×2304 = 9:19.2.
    TARGET_HEIGHT=2304
    STATUS_BAR_PX_DEFAULT=96
    STATUS_BAR_PX_FALLBACK=96
    ;;
  tablet7|tablet10)
    PREFIX="$FORM_FACTOR"
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
    STATUS_BAR_PX="$STATUS_BAR_PX_FALLBACK"
    echo "[capture] --status-bar-px auto: detection failed, using fallback $STATUS_BAR_PX" >&2
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
if android_cli_locate && [[ "$DEVICE_LINES" -eq 1 ]]; then
  echo "[capture] android run --apks=$APK_PATH (install+launch)" >&2
  android_cli_install_and_launch "$APK_PATH" "$PKG/.MainActivity" >/dev/null || {
    echo "[capture] android run failed, falling back to adb install" >&2
    adb install -r "$APK_PATH" >/dev/null
  }
else
  echo "[capture] adb install -r $APK_PATH" >&2
  adb install -r "$APK_PATH" >/dev/null
fi

# Verify the install actually landed (#2796). `android run` can NO-OP the
# install and still exit 0 — the `|| fallback` above never fires, and the run
# then dies on the first `am start` with no output at all, because `set -e`
# kills it silently. Observed live on a freshly booted tablet AVD: `pm path`
# empty, `pm clear` printing "Failed" (while still exiting 0, so it cannot be
# relied on either). This is the same silent-no-op trap documented for asset QA.
if ! adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | grep -q "^package:"; then
  echo "[capture] install did NOT land ('pm path $PKG' is empty) — retrying with adb install" >&2
  adb install -r "$APK_PATH" >/dev/null
  if ! adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | grep -q "^package:"; then
    echo "[capture] '$PKG' is still not installed after 'adb install -r $APK_PATH'." >&2
    exit 1
  fi
fi

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
mkdir -p "$OUT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

IFS=',' read -ra DEMO_ARR <<< "$DEMOS"
INDEX=1
for DEMO in "${DEMO_ARR[@]}"; do
  DEMO="${DEMO// /}"  # trim whitespace
  echo "[capture] [$INDEX] $DEMO" >&2

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
  adb shell am start -n "$PKG/.MainActivity" --es demo "$DEMO" >/dev/null
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
  if ! echo "$FOREGROUND" | grep -q "$PKG"; then
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
