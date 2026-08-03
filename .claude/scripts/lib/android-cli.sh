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

# Tested with android CLI v1.0 (stable, Google I/O May 2026 — Journeys runnable
# from the CLI/CI, `android studio *` subcommands) and v0.7.15411012 (April 2026
# first release). This helper is written to work with BOTH: v1.0 is preferred,
# v0.7 remains supported (some hosts still carry 0.7). Per-arch install URLs are
# computed in android_cli_ensure (no single const).
ANDROID_CLI_VERSION_HINT="1.0+ (0.7 compatible)"

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
  # PREFERRED install path (android CLI v1.0, I/O May 2026): Homebrew. Only
  # fires when the formula is actually available on this host — otherwise we
  # fall straight through to the custom dl.google.com install below, so a host
  # without the formula (or without brew) is unaffected.
  # TODO: confirm the published formula name once v1.0 lands in homebrew-core.
  # Likely `android-cli` (Google's agent CLI — NOT `android-commandlinetools`,
  # which is the unrelated Android SDK command-line tools cask). Add a `apt`
  # branch here too if Google ships a Debian package (blog mentioned brew/apt).
  local brew_formula="android-cli"
  if command -v brew >/dev/null 2>&1 \
     && brew info "$brew_formula" >/dev/null 2>&1; then
    echo "[android-cli] installing $brew_formula via Homebrew (preferred)" >&2
    if brew list "$brew_formula" >/dev/null 2>&1 || brew install "$brew_formula" >&2; then
      if android_cli_locate; then
        # Silence the first-run ToS blurb (see custom-install note below).
        "$ANDROID_CLI_BIN" --version </dev/null >/dev/null 2>&1 || true
        return 0
      fi
    fi
    echo "[android-cli] brew install did not yield a usable binary — falling back to direct download" >&2
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

# android_cli_install_stamp <serial> <package>
# Prints the package's `lastUpdateTime` as recorded by the DEVICE, or nothing
# when the package is absent. This is the evidence `android_cli_install_and_launch`
# uses to prove an install actually landed.
#
# Known limit, deliberately accepted: this stamp is SECOND-granular, so two
# installs of the same package completing inside one wall-clock second compare
# equal. Real install latency is well above a second, so it is practically
# unreachable — and the failure it would cause is a refusal to launch, never a
# false success. The asymmetry is the point: this check may cry wolf, it may
# not stay silent.
android_cli_install_stamp() {
  local serial="$1" pkg="$2"
  # ALWAYS succeeds: "no stamp" is a legitimate answer (package absent, device
  # gone), and callers already treat an empty result as "not proven". Without
  # the `|| true` this pipeline fails under the lib's own `set -o pipefail`
  # plus a caller's inherited `set -e`, and the whole helper is aborted with
  # adb's raw exit code — measured: rc=3 and an EMPTY stderr, so the caller is
  # told nothing at all. A reader would then debug the wrong layer.
  adb -s "$serial" shell dumpsys package "$pkg" 2>/dev/null \
    | awk -F'=' '/lastUpdateTime=/{print $2; exit}' | tr -d '\r' || true
}

# android_cli_install_and_launch <apk> <package/activity> [serial]
# Installs the APK and starts the activity, and PROVES the install landed.
#
# ⛔ WHY THIS VERIFIES INSTEAD OF TRUSTING AN EXIT CODE (#2990)
#   `android run` printed two success-shaped lines ("App loaded: …",
#   "Debuggable: true") and then "No matching components found for type ACTIVITY
#   with name pkg/.MainActivity" — for an activity the platform resolves fine
#   (`cmd package resolve-activity --brief` returns exactly that string). The APK
#   was NOT installed: `lastUpdateTime` still pointed at a build eight hours old.
#   A QA run then measured the PREVIOUS binary while printing plausible output,
#   and only a visibly-unchanged pill gave it away. Had the fix been a colour or a
#   label, the run would have "verified" a fix that never reached the device.
#
#   So the contract here is not "the command exited 0" but "the device's
#   lastUpdateTime moved". If the CLI path leaves it untouched we fall back to
#   adb — the path the report proved works — and if THAT leaves it untouched we
#   fail loudly. The helper can no longer report success without evidence.
#
#   Measured on `android` CLI 1.0.15498356 while fixing this: `--no-metrics` is
#   accepted in the GLOBAL position (`android --no-metrics run …`, which is what
#   this helper uses) and rejected in the sub-command position
#   (`android run --no-metrics …` ⇒ "Unknown option" + usage dump). The report's
#   note about that flag is real but does not apply to this call site — it is NOT
#   the cause of the silent non-install, which remains unexplained upstream.
android_cli_install_and_launch() {
  local apk="$1"
  local activity="$2"
  local serial="${3:-${ANDROID_SERIAL:-}}"
  local pkg="${activity%%/*}"

  if [[ ! -f "$apk" ]]; then
    echo "[android-cli] APK not found: $apk" >&2
    return 1
  fi
  if [[ -z "$serial" ]]; then
    serial="$(android_cli_pick_serial)" || return 1
  fi

  local before after
  before="$(android_cli_install_stamp "$serial" "$pkg")"

  if android_cli_locate; then
    "$ANDROID_CLI_BIN" "${ANDROID_CLI_GLOBAL_FLAGS[@]}" run \
      --apks="$apk" --activity="$activity" --device="$serial" || true
    after="$(android_cli_install_stamp "$serial" "$pkg")"
    if [[ -n "$after" && "$after" != "$before" ]]; then
      return 0
    fi
    echo "[android-cli] 'android run' did not change lastUpdateTime for $pkg" >&2
    echo "[android-cli]   before='$before' after='$after' — falling back to adb (#2990)" >&2
  fi

  adb -s "$serial" install -r "$apk" || return 1
  after="$(android_cli_install_stamp "$serial" "$pkg")"
  if [[ -z "$after" || "$after" == "$before" ]]; then
    echo "[android-cli] ⛔ INSTALL NOT PROVEN for $pkg on $serial." >&2
    echo "[android-cli]   lastUpdateTime did not move (before='$before' after='$after')." >&2
    echo "[android-cli]   The device still holds the PREVIOUS build — any QA run from" >&2
    echo "[android-cli]   here measures the wrong binary. Refusing to launch. (#2990)" >&2
    return 1
  fi

  # Distinct exit codes, because the caller's message depends on WHICH half
  # failed. Returning `am start`'s status made a proven install with a bad
  # activity report "install could not be proven" — a true failure described by
  # a false cause, which sends the reader after the wrong bug.
  #   1 = the install could not be proven (device may hold the previous build)
  #   2 = the install landed, but the activity did not start
  # `|| true`: under a caller's inherited `set -e` this plain statement would
  # abort the function with force-stop's status, never reaching the launch check
  # and never returning the documented 1-vs-2 code. force-stop returning
  # non-zero is not a reason to skip the launch, and its status carries no
  # information the caller can act on.
  adb -s "$serial" shell am force-stop "$pkg" || true
  if ! adb -s "$serial" shell am start -n "$activity"; then
    echo "[android-cli] install PROVEN for $pkg, but 'am start -n $activity' failed." >&2
    echo "[android-cli]   The binary on the device IS the one you built; the" >&2
    echo "[android-cli]   activity name is the suspect. (#2990)" >&2
    return 2
  fi
}
