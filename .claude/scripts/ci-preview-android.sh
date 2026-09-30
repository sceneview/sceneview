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
# For each id it cold-starts the app in light mode, flips it to dark in place,
# lets each settle for PREVIEW_SETTLE_SECONDS (default 20; preview.yml passes 60, what a first
# Filament frame takes on swangle) and writes <out-dir>/<theme>/<id>.png plus a
# filtered logcat/<theme>-<id>.txt. FATAL/ANR lines and an app that was gone at
# capture time land in <out-dir>/summary.md.
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
# The debug APK is its own app next to the Play Store one (`applicationIdSuffix
# ".qa"`); the class keeps the demo namespace, hence the fully qualified name.
PKG="io.github.sceneview.demo.qa"
ACTIVITY="$PKG/io.github.sceneview.demo.MainActivity"
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

ss_pid() { timeout 10 adb shell pidof system_server 2>/dev/null | tr -d '\r\n'; }
app_pid() { timeout 10 adb shell pidof "$PKG" 2>/dev/null | tr -d '\r\n'; }

# Android on this emulator can soft-reboot (system_server restarts, adb stays
# up) under a live Filament viewport: two runs out of two lost the 4th capture
# that way. Wait for the framework to come back instead of capturing the
# launcher, and keep the crash buffer so the cause is in the artifact.
wait_framework() {
  local i
  for i in $(seq 1 90); do
    [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = 1 ] && [ -n "$(ss_pid)" ] && { sleep 20; return 0; }
    sleep 2
  done
  return 1
}

# Grab one theme of an already-running demo. Returns 0 on a valid frame, 2 when
# the framework restarted under it (retryable), 1 otherwise.
shoot() {
  local theme="$1" id="$2" before="$3" dest="$OUT/$1/$2.png"
  mkdir -p "$OUT/$theme" "$OUT/logcat"
  if ! alive; then echo "::error::emulator lost at $theme/$id"; return 1; fi
  if [ "$(ss_pid)" != "$before" ]; then
    echo "::warning::Android restarted its framework during $theme/$id"
    adbt logcat -b crash -d > "$OUT/logcat/$theme-$id-crash.txt" 2>/dev/null || true
    return 2
  fi
  if ! capture "$dest"; then echo "::warning::capture failed for $theme/$id"; rm -f "$dest"; return 1; fi
  adbt logcat -d 2>/dev/null | grep -iE 'filament|egl|gles|sceneview|AndroidRuntime|OpenGL|vulkan|ActivityManager|lmkd|lowmemorykiller|DEBUG|libc' | tail -300 > "$OUT/logcat/$theme-$id.txt" || true
  # The screenshot alone cannot tell "demo on screen" from "app gone, launcher
  # showing": say it in words.
  if [ -z "$(app_pid)" ]; then
    echo "::warning::the app was not running after $theme/$id"
    echo "- app not running after \`$theme/$id\` — the capture shows whatever replaced it; see logcat/$theme-$id.txt" >> "$SUMMARY"
  fi
  local crash
  crash="$(adbt logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in '"$PKG" | head -3 || true)"
  if [ -n "$crash" ]; then
    echo "::warning::crash/ANR logged during $theme/$id"
    printf -- '- crash during `%s/%s`:\n```\n%s\n```\n' "$theme" "$id" "$crash" >> "$SUMMARY"
  fi
  echo "[preview] captured $theme/$id"
  return 0
}

# One cold start per demo: light first, then the theme is flipped under the
# running app with `cmd uimode` (a configuration change, not a relaunch). This
# halves the Filament cold starts compared with a relaunch per theme.
captured=0
lost=""
restarts=0
for id in "${IDS[@]}"; do
  done_id=""
  for attempt in 1 2; do
    if ! alive; then lost="$id"; break 2; fi
    adbt shell cmd uimode night no >/dev/null
    adbt shell am force-stop "$PKG"
    adbt logcat -c 2>/dev/null || true
    before="$(ss_pid)"
    if [ "$id" = home ]; then
      adbt shell am start -n "$ACTIVITY" >/dev/null
    else
      adbt shell am start -n "$ACTIVITY" --es demo "$id" --ez qa_mode true --ez qa_backdrop true >/dev/null
    fi
    sleep "$SETTLE"
    shoot light "$id" "$before"; rc=$?
    if [ "$rc" = 0 ]; then
      adbt shell cmd uimode night yes >/dev/null
      sleep "$SETTLE"
      shoot dark "$id" "$before"; rc=$?
    fi
    if [ "$rc" = 0 ]; then captured=$((captured + 2)); done_id=1; break; fi
    if [ "$rc" = 2 ]; then
      restarts=$((restarts + 1))
      wait_framework || { lost="$id (framework never came back)"; break 2; }
      echo "[preview] retrying $id (attempt $((attempt + 1)))"
      continue
    fi
    lost="$id"; break 2
  done
  [ -n "$done_id" ] || [ -n "$lost" ] || lost="$id (framework restarted twice)"
  [ -z "$lost" ] || break
done
alive && adbt shell cmd uimode night no >/dev/null 2>&1 || true

{
  echo "- captured: $captured screenshot(s) — ids: ${IDS[*]}, themes: light dark, settle ${SETTLE}s"
  [ "$restarts" = 0 ] || echo "- Android framework restarted $restarts time(s); the affected demo was retried (crash buffer in logcat/)"
  [ -z "$lost" ] || echo "- incomplete at \`$lost\` — later captures missing"
} >> "$SUMMARY"
cat "$SUMMARY"

[ "$captured" -gt 0 ] || { echo "::error::no screenshot captured"; exit 1; }
[ -z "$lost" ] || exit 1
