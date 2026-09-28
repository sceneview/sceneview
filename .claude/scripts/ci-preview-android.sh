#!/usr/bin/env bash
# Capture the Android demo on an already-booted emulator, light + dark.
#
# Called by .github/workflows/preview.yml inside ReactiveCircus/android-emulator-
# runner, which runs each `script:` line through `sh -c` — that is why the whole
# capture lives here, in a real bash file, instead of inline in the workflow.
#
# Usage: ci-preview-android.sh <apk> <out-dir> [demo-ids]
#   demo-ids  comma- or space-separated ids from DemoRegistry's ALL_DEMOS, plus
#             the pseudo-id `home` (plain launcher start). Default: home,model-viewer
#
# For each theme (light, dark) and each id it cold-starts the app, lets it settle
# for PREVIEW_SETTLE_SECONDS (default 20) and writes <out-dir>/<theme>/<id>.png.
# FATAL/ANR logcat lines land in <out-dir>/summary.md.
#
# Deliberately NO `uiautomator dump` polling for the "Scene ready" node: the
# first CI run lost the emulator (`device 'emulator-5554' not found`) two
# minutes into polling a Filament viewport with it. A fixed settle is dumber and
# has not killed a device; the screenshot is the evidence either way. Every adb
# call is `timeout`-bounded, and a device that disappears ends the loop with a
# report line instead of hanging the job until `timeout-minutes`.
#
# Exit status: non-zero when nothing was captured or the emulator went away
# mid-run (the preview is then incomplete). A demo that crashes on its own is a
# report line and a screenshot of whatever is on screen, not a red run.
set -uo pipefail

APK="${1:?usage: ci-preview-android.sh <apk> <out-dir> [demo-ids]}"
OUT="${2:?usage: ci-preview-android.sh <apk> <out-dir> [demo-ids]}"
IDS_RAW="${3:-home,model-viewer}"
PKG="io.github.sceneview.demo"
ACTIVITY="$PKG/.MainActivity"
SETTLE="${PREVIEW_SETTLE_SECONDS:-20}"

# Every adb call goes through this: 60 s is far above any healthy call here.
adbt() { timeout 60 adb "$@"; }
alive() { [ "$(timeout 10 adb get-state 2>/dev/null)" = device ]; }

# Host-side capture first. A guest-side `screencap` over a Filament viewport on
# SwiftShader took the whole emulator down (run 36405180532, same death as
# #3554: the guest read-back is the last line before the process vanishes).
# `adb emu screenrecord screenshot` reads the frame from the host renderer and
# writes the PNG straight onto the runner. `screencap` stays as the fallback.
capture() {
  local dest="$1" tmp f
  tmp="$(mktemp -d)"
  if timeout 60 adb emu screenrecord screenshot "$tmp" >/dev/null 2>&1; then
    f="$(find "$tmp" -name '*.png' -size +0 -print -quit)"
    if [ -n "$f" ]; then mv "$f" "$dest"; rm -rf "$tmp"; return 0; fi
  fi
  rm -rf "$tmp"
  echo "::warning::host-side capture failed for $dest, falling back to screencap"
  adbt exec-out screencap -p > "$dest" && [ -s "$dest" ]
}

mkdir -p "$OUT"
SUMMARY="$OUT/summary.md"
: > "$SUMMARY"

# Demo ids are passed to `am start --es demo`, and MainActivity validates them
# against the closed registry (DeepLinkRouter.validate). Still, keep the shell
# side strict: only [a-z0-9-], so an input can never smuggle an extra argument.
IDS=()
for id in $(printf '%s' "$IDS_RAW" | tr ',' ' '); do
  if [[ "$id" =~ ^[a-z0-9-]+$ ]]; then IDS+=("$id"); else echo "::warning::skipping invalid demo id '$id'"; fi
done
[ "${#IDS[@]}" -gt 0 ] || { echo "::error::no valid demo id in '$IDS_RAW'"; exit 1; }

echo "[preview] installing $APK"
timeout 300 adb install -r -g "$APK" || { echo "::error::adb install failed"; exit 1; }

captured=0
lost=""
for theme in light dark; do
  [ -z "$lost" ] || break
  if [ "$theme" = dark ]; then adbt shell cmd uimode night yes >/dev/null; else adbt shell cmd uimode night no >/dev/null; fi
  mkdir -p "$OUT/$theme"
  for id in "${IDS[@]}"; do
    if ! alive; then lost="$theme/$id"; echo "::error::emulator lost before $theme/$id"; break; fi
    adbt shell am force-stop "$PKG"
    adbt logcat -c 2>/dev/null || true
    if [ "$id" = home ]; then
      adbt shell am start -n "$ACTIVITY" >/dev/null
    else
      adbt shell am start -n "$ACTIVITY" --es demo "$id" --ez qa_mode true --ez qa_backdrop true >/dev/null
    fi
    sleep "$SETTLE"
    # Tells "died while rendering" apart from "died while being captured".
    if ! alive; then lost="$theme/$id (during render, before capture)"; echo "::error::emulator lost while rendering $theme/$id"; break; fi
    if capture "$OUT/$theme/$id.png"; then
      captured=$((captured + 1))
      echo "[preview] captured $theme/$id"
    else
      echo "::warning::screencap failed for $theme/$id"
      rm -f "$OUT/$theme/$id.png"
    fi
    mkdir -p "$OUT/logcat"
    adbt logcat -d 2>/dev/null | grep -iE 'filament|egl|gles|sceneview|AndroidRuntime|OpenGL|vulkan' | tail -300 > "$OUT/logcat/$theme-$id.txt" || true
    crash="$(adbt logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in '"$PKG" | head -3 || true)"
    if [ -n "$crash" ]; then
      echo "::warning::crash/ANR logged during $theme/$id"
      printf -- '- crash during `%s/%s`:\n```\n%s\n```\n' "$theme" "$id" "$crash" >> "$SUMMARY"
    fi
  done
done
alive && adbt shell cmd uimode night no >/dev/null 2>&1 || true

{
  echo "- captured: $captured screenshot(s) — ids: ${IDS[*]}, themes: light dark, settle ${SETTLE}s"
  [ -z "$lost" ] || echo "- emulator lost before \`$lost\` — later captures missing"
} >> "$SUMMARY"
cat "$SUMMARY"

[ "$captured" -gt 0 ] || { echo "::error::no screenshot captured"; exit 1; }
[ -z "$lost" ] || exit 1
