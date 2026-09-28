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
# For each theme (light, dark) and each id it cold-starts the app, waits for the
# viewport's own "Scene ready" accessibility node (the same signal the Maestro
# flows gate on, #3444) and writes <out-dir>/<theme>/<id>.png. A demo that never
# reports ready is still captured — the screen is the evidence — but is listed
# under `not-ready` in <out-dir>/summary.md, next to any FATAL/ANR logcat line.
#
# Exit status: non-zero only when nothing could be captured at all (install
# failed, emulator gone). A single slow demo is a report line, not a red run.
set -uo pipefail

APK="${1:?usage: ci-preview-android.sh <apk> <out-dir> [demo-ids]}"
OUT="${2:?usage: ci-preview-android.sh <apk> <out-dir> [demo-ids]}"
IDS_RAW="${3:-home,model-viewer}"
PKG="io.github.sceneview.demo"
ACTIVITY="$PKG/.MainActivity"
READY_TIMEOUT="${PREVIEW_READY_TIMEOUT:-45}"

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
adb install -r -g "$APK" || { echo "::error::adb install failed"; exit 1; }

scene_ready() {
  # uiautomator sees Compose semantics the way Maestro does; "Scene ready" is the
  # viewport's contentDescription once a frame has reached the surface (#3444).
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q 'Scene ready'
}

captured=0
not_ready=()
for theme in light dark; do
  if [ "$theme" = dark ]; then adb shell cmd uimode night yes >/dev/null; else adb shell cmd uimode night no >/dev/null; fi
  mkdir -p "$OUT/$theme"
  for id in "${IDS[@]}"; do
    adb shell am force-stop "$PKG"
    adb logcat -c 2>/dev/null || true
    if [ "$id" = home ]; then
      adb shell am start -W -n "$ACTIVITY" >/dev/null
      # The home screen has no "Scene ready" contract: give it a fixed settle.
      sleep 12
    else
      adb shell am start -W -n "$ACTIVITY" --es demo "$id" --ez qa_mode true --ez qa_backdrop true >/dev/null
      ready=0
      deadline=$((SECONDS + READY_TIMEOUT))
      while [ "$SECONDS" -lt "$deadline" ]; do
        if scene_ready; then ready=1; break; fi
        sleep 2
      done
      [ "$ready" -eq 1 ] || not_ready+=("$theme/$id")
      # Let the loading cover finish its cross-fade.
      sleep 3
    fi
    if adb exec-out screencap -p > "$OUT/$theme/$id.png" && [ -s "$OUT/$theme/$id.png" ]; then
      captured=$((captured + 1))
      echo "[preview] captured $theme/$id"
    else
      echo "::warning::screencap failed for $theme/$id"
      rm -f "$OUT/$theme/$id.png"
    fi
    crash="$(adb logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in '"$PKG" | head -3 || true)"
    if [ -n "$crash" ]; then
      echo "::warning::crash/ANR logged during $theme/$id"
      printf -- '- crash during `%s/%s`:\n```\n%s\n```\n' "$theme" "$id" "$crash" >> "$SUMMARY"
    fi
  done
done
adb shell cmd uimode night no >/dev/null 2>&1 || true

{
  echo "- captured: $captured screenshot(s) — ids: ${IDS[*]}, themes: light dark"
  if [ "${#not_ready[@]}" -gt 0 ]; then
    echo "- not-ready after ${READY_TIMEOUT}s (captured anyway): ${not_ready[*]}"
  fi
} >> "$SUMMARY"
cat "$SUMMARY"

[ "$captured" -gt 0 ] || { echo "::error::no screenshot captured"; exit 1; }
