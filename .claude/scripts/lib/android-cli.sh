#!/usr/bin/env bash
# android-cli.sh — shared helpers for Google's Android CLI (developer.android.com/tools/agents/android-cli).
#
# The `android` CLI is Google's AI-agent-focused front-end for adb/uiautomator/emulator/sdkmanager.
# It produces JSON layout dumps with precomputed tap coordinates (no XML parsing), and PNG screenshots
# without the LF/CRLF corruption that `adb shell screencap` suffers from on some shells.
#
# Usage from another script:
#     source "$(dirname "$0")/lib/android-cli.sh"
#     android_cli_ensure                              # bootstraps install to ~/.local/bin if missing
#     android_cli_screenshot /tmp/out.png             # device-aware screen capture
#     rec=$(android_cli_screenrecord_start "$serial") # host-side emulator recording
#     android_cli_screenrecord_stop "$serial"         # … stop it, WebM left at $rec
#     android_cli_layout /tmp/ui.json [serial]        # JSON layout dump
#     android_cli_resolve_tap /tmp/ui.json "Demo Name"  # echoes "x,y" for a text node
#
# Each helper falls back to plain `adb` on multi-device hosts or when the `android` CLI binary is
# missing AND we're in CI (where we don't want to fetch binaries on the fly).

set -o pipefail

# Tested with android CLI 0.7.15411012 (April 2026 first release).
# Per-arch install URLs are computed in android_cli_ensure (no single const).
ANDROID_CLI_VERSION_HINT="0.7+"

# Always-on global flags. `--no-metrics` keeps CI logs clean and disables
# telemetry; we never want the binary to phone home from the SceneView repo.
ANDROID_CLI_GLOBAL_FLAGS=(--no-metrics)

# Reset state so successive `source`s of this lib in long-lived shells don't
# inherit a stale binary path.
ANDROID_CLI_BIN=""

# Resolve the `android` binary path. Returns 0 on success, sets ANDROID_CLI_BIN.
android_cli_locate() {
  ANDROID_CLI_BIN=""
  if command -v android >/dev/null 2>&1; then
    ANDROID_CLI_BIN="$(command -v android)"
    return 0
  fi
  if [[ -x "$HOME/.local/bin/android" ]]; then
    ANDROID_CLI_BIN="$HOME/.local/bin/android"
    return 0
  fi
  return 1
}

# Ensure the `android` CLI is installed. Auto-installs to ~/.local/bin without touching the shell rc.
# Honors $CI: in CI we never auto-download; the workflow is expected to install it as a prior step.
android_cli_ensure() {
  if android_cli_locate; then
    return 0
  fi
  if [[ -n "${CI:-}" ]]; then
    echo "[android-cli] not installed and CI=1 — install it via your workflow before calling this helper." >&2
    return 1
  fi
  local os arch url
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os $arch" in
    "Darwin arm64")  url="https://dl.google.com/android/cli/latest/darwin_arm64/android" ;;
    "Darwin x86_64") url="https://dl.google.com/android/cli/latest/darwin_x86_64/android" ;;
    "Linux x86_64")  url="https://dl.google.com/android/cli/latest/linux_x86_64/android" ;;
    *) echo "[android-cli] unsupported host $os $arch" >&2; return 1 ;;
  esac
  echo "[android-cli] fetching $url" >&2
  mkdir -p "$HOME/.local/bin"
  curl -fsSL "$url" -o "$HOME/.local/bin/android" || {
    echo "[android-cli] download failed" >&2
    return 1
  }
  chmod +x "$HOME/.local/bin/android"
  ANDROID_CLI_BIN="$HOME/.local/bin/android"
  # Silence the first-run terms-of-service blurb so callers can parse stdout
  # cleanly. The binary prints the ToS once to stderr on its first invocation —
  # `--version </dev/null` accepts implicitly and is non-interactive.
  "$ANDROID_CLI_BIN" --version </dev/null >/dev/null 2>&1 || true
}

# Count devices in "device" state (filters out offline/unauthorized).
android_cli_device_count() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {n++} END {print n+0}'
}

# Pick the single device serial, or honor $ANDROID_SERIAL if set. Echoes serial on stdout.
android_cli_pick_serial() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    echo "$ANDROID_SERIAL"
    return 0
  fi
  local n
  n="$(android_cli_device_count)"
  if [[ "$n" -eq 1 ]]; then
    adb devices | awk '$2=="device" {print $1; exit}'
    return 0
  fi
  return 1
}

# android_cli_screenshot <output.png> [serial]
# `android screen capture` in v0.7 has no --device flag, so fall back to
# `adb -s $serial exec-out screencap -p` when multi-device. Note: `exec-out` (not
# `shell`) is required — only the `shell` form suffers LF/CRLF translation.
android_cli_screenshot() {
  local out="$1"
  local serial="${2:-${ANDROID_SERIAL:-}}"
  local n; n="$(android_cli_device_count)"
  # Delete any stale file first so a failed capture cannot be confused with a
  # previous successful one (single-device path can't pass --device, so the
  # caller has no way to tell the file is from this invocation otherwise).
  rm -f "$out" 2>/dev/null || true
  if android_cli_locate && [[ "$n" -le 1 ]] && [[ -z "$serial" ]]; then
    "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" screen capture --output "$out"
    return $?
  fi
  # Multi-device or missing CLI: use adb. Pinned serial avoids ambiguity.
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || {
      echo "[android-cli] multi-device and ANDROID_SERIAL unset" >&2
      return 1
    }
  fi
  adb -s "$serial" exec-out screencap -p > "$out"
}

# android_cli_screenshot_annotated <output.png> [serial]
# Draws labeled bounding boxes around UI elements — pairs with `android screen resolve`.
android_cli_screenshot_annotated() {
  local out="$1"
  local serial="${2:-${ANDROID_SERIAL:-}}"
  local n; n="$(android_cli_device_count)"
  rm -f "$out" 2>/dev/null || true
  if ! android_cli_locate; then
    echo "[android-cli] --annotate requires the android CLI; install via android_cli_ensure" >&2
    return 1
  fi
  if [[ "$n" -gt 1 ]] || [[ -n "$serial" ]]; then
    echo "[android-cli] --annotate is single-device only in v0.7 (no --device flag)" >&2
    return 1
  fi
  "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" screen capture --annotate --output "$out"
}

# android_cli_screenrecord_start <serial> [time-limit-seconds]
# Start a HOST-SIDE emulator screen recording via the emulator console
# (`adb emu screenrecord`). Output goes to a WebM whose path is echoed on
# stdout so the caller can pick it up after android_cli_screenrecord_stop.
#
# Why raw `adb`, not the `android` CLI: the `android` CLI v0.7 has NO
# screenrecord subcommand, so `adb emu screenrecord` is the sanctioned
# exception to the "android CLI not raw adb" rule. Crucially it is *host-side*:
# it captures the emulator's presented framebuffer directly (the same engine as
# the Extended Controls recorder), so unlike the guest-side `adb shell
# screenrecord` it is immune to the Emulator 36.x gfxstream/SurfaceFlinger
# regression that records `-gpu host` Filament/OpenGL content as near-empty
# (~0.5 fps). See issue #1671.
#
# Returns 0 and echoes the WebM path on success, non-zero on failure.
android_cli_screenrecord_start() {
  local serial="${1:-${ANDROID_SERIAL:-}}"
  local time_limit="${2:-180}"
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || {
      echo "[android-cli] screenrecord: multi-device and ANDROID_SERIAL unset" >&2
      return 1
    }
  fi
  # The emulator console recorder writes WebM. Use a serial-scoped temp path so
  # parallel pool emulators (#1654) never trample each other's recording.
  local out="${TMPDIR:-/tmp}/sceneview-rec-${serial}.webm"
  rm -f "$out" 2>/dev/null || true
  # `adb emu screenrecord` — HOST-SIDE emulator-console command (see note
  # above). `--time-limit` is a hard cap so a crashed `stop` cannot leave the
  # encoder running forever.
  if ! adb -s "$serial" emu screenrecord start --time-limit "$time_limit" "$out" >/dev/null 2>&1; then
    echo "[android-cli] screenrecord: 'adb emu screenrecord' failed on $serial" >&2
    echo "[android-cli] screenrecord: needs a running emulator (not a physical device)" >&2
    return 1
  fi
  echo "$out"
}

# android_cli_screenrecord_stop <serial>
# Stop the host-side emulator recording started by android_cli_screenrecord_start.
android_cli_screenrecord_stop() {
  local serial="${1:-${ANDROID_SERIAL:-}}"
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || return 1
  fi
  # Host-side emulator-console stop — see android_cli_screenrecord_start note.
  adb -s "$serial" emu screenrecord stop >/dev/null 2>&1
}

# android_cli_layout <output.json> [serial]
# Returns a JSON tree with precomputed `center` coordinates per node — no XML parsing needed.
# `android layout` DOES support --device, so multi-device works.
android_cli_layout() {
  local out="$1"
  local serial="${2:-${ANDROID_SERIAL:-}}"
  if android_cli_locate; then
    if [[ -n "$serial" ]]; then
      "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" layout --device="$serial" --output "$out" --pretty
    else
      "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" layout --output "$out" --pretty
    fi
    return $?
  fi
  # Fallback: raw uiautomator dump (returns XML, callers must parse).
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || return 1
  fi
  # PID-suffixed path so parallel script runs don't trample each other.
  local remote="/sdcard/ui-$$.xml"
  adb -s "$serial" shell uiautomator dump "$remote" >/dev/null
  adb -s "$serial" pull "$remote" "$out" >/dev/null
  adb -s "$serial" shell rm -f "$remote" >/dev/null 2>&1 || true
}

# android_cli_resolve_tap <layout.json> <text-or-pattern>
# Echoes "x,y" of the first node whose text exactly matches. JSON-only (layout output).
# Returns 1 if not found.
android_cli_resolve_tap() {
  local layout="$1"
  local target="$2"
  command -v python3 >/dev/null 2>&1 || {
    echo "[android-cli] python3 required for android_cli_resolve_tap" >&2
    return 2
  }
  python3 - "$layout" "$target" <<'PY'
import json, sys, re
layout_path, target = sys.argv[1], sys.argv[2]
try:
    with open(layout_path) as f:
        data = json.load(f)
except (OSError, json.JSONDecodeError):
    sys.exit(1)
for node in data:
    if node.get("text") == target:
        center = node.get("center", "")
        m = re.match(r"\[(-?\d+),(-?\d+)\]", center)
        if m:
            print(f"{m.group(1)},{m.group(2)}")
            sys.exit(0)
sys.exit(1)
PY
}

# android_cli_locate_aapt2
# Resolves the `aapt2` binary and echoes its path on stdout. Returns 0 on
# success, 1 if it genuinely cannot be found.
#
# `aapt2` is NOT on PATH on most CI runners — it ships *inside* the Android SDK
# at `<sdk>/build-tools/<version>/aapt2`, not in `<sdk>/platform-tools` (which
# IS the dir that gets added to PATH). So:
#   1. Try a bare `aapt2` on PATH first (covers dev machines that symlink it).
#   2. Otherwise walk `${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/*` and
#      pick the newest version directory that contains an executable `aapt2`.
android_cli_locate_aapt2() {
  if command -v aapt2 >/dev/null 2>&1; then
    command -v aapt2
    return 0
  fi
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
    local bt
    # `sort -V` orders build-tools dirs by semantic version; `tail` → newest.
    # The loop walks newest→oldest so a partially-installed newest dir without
    # an `aapt2` falls through to the next-newest instead of failing outright.
    while IFS= read -r bt; do
      [[ -z "$bt" ]] && continue
      if [[ -x "$bt/aapt2" ]]; then
        echo "$bt/aapt2"
        return 0
      fi
    done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null \
             | sort -Vr)
  fi
  return 1
}

# android_cli_describe <apk-or-aab>
# Echoes the artifact's manifest metadata (package / versionName / versionCode …)
# on stdout so callers can grep it.
#
# Return codes are meaningful to callers (validate-release-artifact.sh):
#   0  → artifact introspected OK, metadata on stdout
#   1  → the artifact itself is unreadable / corrupt / unsupported
#   2  → the TOOLING (`aapt2`) is unavailable — distinct from a bad artifact so
#        a validation guard can warn+skip instead of hard-failing a release.
#
# NOTE on tooling: the `android` CLI's `describe` subcommand (v0.7) analyzes a
# *project directory*, not a built artifact — it has no artifact-introspection
# mode. The correct tool for reading a built `.apk` / `.aab` manifest is
# `aapt2`, which ships with the Android SDK build-tools the runner already has
# (resolved via `android_cli_locate_aapt2` — it is NOT on PATH).
# This helper therefore uses `aapt2`:
#   - `.apk` → `aapt2 dump badging` reads it directly.
#   - `.aab` → the protobuf `base/manifest/AndroidManifest.xml` is unzipped and
#              decoded with `aapt2 dump xmltree`.
android_cli_describe() {
  local artifact="$1"
  if [[ ! -f "$artifact" ]]; then
    echo "[android-cli] describe: artifact not found: $artifact" >&2
    return 1
  fi
  local aapt2
  if ! aapt2="$(android_cli_locate_aapt2)"; then
    echo "[android-cli] describe: aapt2 not found — not on PATH and not under" \
         "\${ANDROID_SDK_ROOT:-\$ANDROID_HOME}/build-tools/*" >&2
    return 2
  fi
  case "$artifact" in
    *.aab)
      # An .aab is a zip; its manifest is protobuf-encoded — `aapt2 dump badging`
      # cannot read a bundle, so extract + xmltree the base module manifest.
      local tmp; tmp="$(mktemp -d)"
      if command -v unzip >/dev/null 2>&1 \
         && unzip -o -q "$artifact" "base/manifest/AndroidManifest.xml" -d "$tmp" 2>/dev/null \
         && [[ -f "$tmp/base/manifest/AndroidManifest.xml" ]]; then
        "$aapt2" dump xmltree --file "base/manifest/AndroidManifest.xml" "$tmp/base"
        local rc=$?
        rm -rf "$tmp"
        return $rc
      fi
      rm -rf "$tmp"
      echo "[android-cli] describe: could not extract base manifest from $artifact" >&2
      return 1
      ;;
    *)
      "$aapt2" dump badging "$artifact"
      ;;
  esac
}

# android_cli_install_and_launch <apk> <package/activity> [serial]
# Wraps `android run --apks ... --activity ... [--device ...]` which combines install+start.
# `activity` should be in `pkg/.Class` form (e.g. `io.github.sceneview.demo/.MainActivity`).
android_cli_install_and_launch() {
  local apk="$1"
  local activity="$2"
  local serial="${3:-${ANDROID_SERIAL:-}}"
  if android_cli_locate; then
    if [[ -n "$serial" ]]; then
      "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" run --apks="$apk" --activity="$activity" --device="$serial"
    else
      "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" run --apks="$apk" --activity="$activity"
    fi
    return $?
  fi
  # Fallback: separate adb steps.
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || return 1
  fi
  adb -s "$serial" install -r "$apk"
  local pkg="${activity%%/*}"
  adb -s "$serial" shell am force-stop "$pkg"
  adb -s "$serial" shell am start -n "$activity"
}
