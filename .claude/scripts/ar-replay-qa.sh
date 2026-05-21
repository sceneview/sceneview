#!/usr/bin/env bash
# ar-replay-qa.sh — autonomous AR-demo replay QA, headless, no physical device.
#
# Slice 4 of the device-QA harness umbrella (#1560, this slice #1565).
#
# What it does:
#   1. Builds + installs the android-demo *debug* APK (the debug sourceSet
#      ships the bundled ARCore recording the harness replays).
#   2. Runs the `ARReplayHarnessTest` instrumentation test, which deep-links
#      every Augmented Reality demo, drives each through a recorded ARCore
#      session, and asserts no crash. The one demo that consumes
#      `playbackDataset` (`ar-record-playback`) additionally proves the
#      recorded dataset advanced frames.
#
#      Honesty gate (#1645): ARCore dataset playback needs camera-stream
#      support the x86 software-GPU CI emulator does not provide. When
#      `ar-record-playback` advances 0 frames the harness reports it as
#      `skipped` (with a reason) — NOT a misleading `pass` — and this script
#      exits 3 so the device-QA orchestrator records the AR leg as `skipped`.
#   3. Pulls the machine-readable verdict `ar-qa-summary.json` the test wrote
#      to `/sdcard/Download/SceneView/` and echoes its path.
#
# This is the entrypoint the slice-5 orchestrator runner (`device-qa.sh`,
# #1566) calls for the Android-AR leg. The JSON shape mirrors the device-QA
# harness convention: `{ harness, passed, total, demos: [{ id, verdict,
# replayedFrames }] }` — see ARReplayHarnessTest.writeSummary().
#
# Requirements:
#   - An ARCore-friendly emulator (or device). `setup-ar-emulator.sh`
#     provisions one with Google Play Services for AR.
#   - A connected, booted device — this script does not boot one.
#
# Usage:
#   bash .claude/scripts/ar-replay-qa.sh [--no-install] [--out <dir>]
#
# Options:
#   --no-install   Skip the build+install step (APK already on device).
#   --out <dir>    Directory to copy ar-qa-summary.json into. Default: a temp
#                  dir; the resolved path is printed on the last line.
#   -h | --help    Show this help.
#
# Exit status:
#   0  every AR demo survived recorded-session replay AND the recorded ARCore
#      dataset genuinely replayed frames (verdict `replayed`)
#   1  one or more demos crashed, or the harness could not run
#   2  no device / emulator connected
#   3  SKIPPED — no demo crashed, but `ar-record-playback` advanced 0 frames:
#      ARCore dataset playback is unsupported on this emulator. An environment
#      limitation reported honestly — the AR leg is NOT a pass (#1645).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# This script lives in `.claude/scripts/`, so the repo root is two levels up.
# Resolving it from BASH_SOURCE (not the caller's CWD) keeps every path below
# — `./gradlew`, the demo module — correct no matter where this script is
# invoked from (e.g. device-qa.sh runs it with a non-repo-root CWD, #1585).
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"

INSTALL=1
OUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) INSTALL=0; shift ;;
    --out) OUT_DIR="${2:?--out needs a directory}"; shift 2 ;;
    -h|--help)
      sed -n '2,49p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "[ar-replay-qa] unknown option: $1" >&2; exit 1 ;;
  esac
done

# ── Device check ───────────────────────────────────────────────────────────
SERIAL="$(android_cli_pick_serial || true)"
if [[ -z "$SERIAL" ]]; then
  echo "[ar-replay-qa] no single device/emulator connected." >&2
  echo "[ar-replay-qa] boot one first: bash .claude/scripts/setup-ar-emulator.sh" >&2
  exit 2
fi
echo "[ar-replay-qa] target device: $SERIAL"

# ── Build + install ────────────────────────────────────────────────────────
# The replay harness needs the *debug* APK: the bundled ARCore recording
# (bundled-pixel9-sample.mp4) ships only in the debug sourceSet so the release
# APK stays lean (#934).
if [[ "$INSTALL" -eq 1 ]]; then
  echo "[ar-replay-qa] building + installing android-demo debug APK + test APK…"
  ./gradlew --console=plain \
    :samples:android-demo:installDebug \
    :samples:android-demo:installDebugAndroidTest
else
  echo "[ar-replay-qa] --no-install: skipping build+install."
fi

# ── Clean logcat so a crash this run is not masked by an older one ──────────
adb -s "$SERIAL" logcat -c || true

# ── Run the replay harness ─────────────────────────────────────────────────
echo "[ar-replay-qa] running ARReplayHarnessTest (headless AR replay over all AR demos)…"
HARNESS_STATUS=0
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.sceneview.demo.ar.ARReplayHarnessTest \
  io.github.sceneview.demo.test/androidx.test.runner.AndroidJUnitRunner \
  || HARNESS_STATUS=$?

# ── Pull the machine-readable verdict ──────────────────────────────────────
DEVICE_SUMMARY="/sdcard/Download/SceneView/ar-qa-summary.json"
if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$(mktemp -d)"
fi
mkdir -p "$OUT_DIR"
LOCAL_SUMMARY="$OUT_DIR/ar-qa-summary.json"

SUMMARY_PULLED=0
if adb -s "$SERIAL" pull "$DEVICE_SUMMARY" "$LOCAL_SUMMARY" >/dev/null 2>&1; then
  SUMMARY_PULLED=1
  echo "[ar-replay-qa] verdict summary:"
  cat "$LOCAL_SUMMARY"
  echo
  echo "[ar-replay-qa] summary written to: $LOCAL_SUMMARY"
else
  echo "[ar-replay-qa] WARNING: no ar-qa-summary.json on device — the harness" >&2
  echo "[ar-replay-qa] was likely assumeTrue-skipped (bundled recording absent," >&2
  echo "[ar-replay-qa] or Google Play Services for AR missing). See test output." >&2
fi

# ── Verdict ────────────────────────────────────────────────────────────────
# A non-zero instrumentation status means a demo crashed (the harness asserts
# no crash) -> hard FAIL. Note the harness also ends the test with assumeTrue
# when the replay demo advanced 0 frames; an assumeTrue-skip is NOT an
# instrumentation failure, so HARNESS_STATUS stays 0 in that case and we fall
# through to the summary-based skip detection below.
if [[ "$HARNESS_STATUS" -ne 0 ]]; then
  echo "[ar-replay-qa] FAIL — one or more AR demos crashed during replay." >&2
  exit 1
fi

# Honesty gate (#1645): inspect the machine-readable summary. If the replay
# demo (`ar-record-playback`) advanced 0 frames it is graded `skipped` — the
# CI emulator cannot do ARCore dataset playback. Surface that as exit 3 so the
# AR leg is recorded `skipped`, never a misleading `pass`. The `passed` count
# in the summary already excludes `skipped` demos (see writeSummary()).
SKIPPED_COUNT=0
if [[ "$SUMMARY_PULLED" -eq 1 ]]; then
  if command -v python3 >/dev/null 2>&1; then
    SKIPPED_COUNT="$(python3 -c '
import json, sys
try:
    with open(sys.argv[1]) as f:
        data = json.load(f)
except Exception:
    print(0); sys.exit(0)
print(int(data.get("skipped", 0)))
' "$LOCAL_SUMMARY" 2>/dev/null || echo 0)"
  else
    # Fallback for hosts without python3: the summary's top-level "skipped"
    # key is the count we need. The first `"skipped":` line is the header
    # field (per-demo objects carry `"verdict": "skipped"`, not `"skipped":`).
    SKIPPED_COUNT="$(grep -m1 -oE '"skipped"[[:space:]]*:[[:space:]]*[0-9]+' "$LOCAL_SUMMARY" 2>/dev/null \
      | grep -oE '[0-9]+$' || echo 0)"
  fi
fi
if [[ "$SKIPPED_COUNT" =~ ^[0-9]+$ ]] && [[ "$SKIPPED_COUNT" -gt 0 ]]; then
  echo "[ar-replay-qa] SKIPPED — no AR demo crashed, but $SKIPPED_COUNT demo(s)" >&2
  echo "[ar-replay-qa] (incl. ar-record-playback) replayed 0 frames: ARCore dataset" >&2
  echo "[ar-replay-qa] playback is not supported on this emulator. The recorded" >&2
  echo "[ar-replay-qa] session was NOT exercised — this is not a pass (#1645)." >&2
  exit 3
fi

echo "[ar-replay-qa] PASS — every AR demo survived recorded-session replay" \
  "and the recorded ARCore dataset replayed frames."
