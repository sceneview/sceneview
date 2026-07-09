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
#   --out <dir>    Directory to copy ar-qa-summary.json into. Also receives
#                  ar-logcat.txt (streamed for the whole run) and
#                  ar-instrumentation.txt (teed `am instrument` output) so a
#                  crash is diagnosable from the artifact bundle (#2620).
#                  Default: a temp dir; the resolved path is printed last.
#   -h | --help    Show this help.
#
# Exit status:
#   0  every AR demo survived recorded-session replay AND the recorded ARCore
#      dataset genuinely replayed frames (verdict `replayed`); OR the harness
#      assumeTrue-skipped because the bundled recording is absent (sparse
#      checkout / release build) — a no-op that stays green (#1565)
#   1  one or more demos crashed, OR the instrumentation host process died
#      mid-sweep (a demo took down the shared MainActivity process, or lmkd
#      OOM-killed it). The recovered partial summary names the culprit and the
#      captured logcat holds the crash signature (#2620)
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
      sed -n '2,57p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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

# ── Resolve the output dir up-front ────────────────────────────────────────
# Needed BEFORE the run so the streamed logcat and the teed instrumentation
# output land in the artifact dir device-qa.sh uploads (device-qa-artifacts/).
if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$(mktemp -d)"
fi
mkdir -p "$OUT_DIR"
LOCAL_SUMMARY="$OUT_DIR/ar-qa-summary.json"
LOGCAT_FILE="$OUT_DIR/ar-logcat.txt"
INSTR_FILE="$OUT_DIR/ar-instrumentation.txt"

# ── Clean logcat so a crash this run is not masked by an older one ──────────
adb -s "$SERIAL" logcat -c || true

# ── Stream logcat for the whole run ────────────────────────────────────────
# The AR demos are deep-linked into MainActivity's process, which is ALSO the
# instrumentation host (the demo manifest declares no `android:process`). A
# native crash (Filament/GL on the software-GPU swiftshader CI emulator) or an
# lmkd OOM-kill of that RAM-heavy process tears the instrumentation down
# mid-sweep: `am instrument` then exits non-zero with NO JUnit output and the
# harness never finishes writing its summary (#2620). A streamed logcat is the
# only place the real crash signature (tombstone / SIGSEGV / lowmemorykiller)
# survives — a post-hoc `logcat -d` can miss it once the ring buffer rolls over
# on a chatty multi-minute Filament run, so stream live into the artifact dir.
LOGCAT_PID=""
stop_logcat() { [[ -n "$LOGCAT_PID" ]] && kill "$LOGCAT_PID" >/dev/null 2>&1 || true; }
trap stop_logcat EXIT
adb -s "$SERIAL" logcat -v threadtime > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

# ── Run the replay harness ─────────────────────────────────────────────────
echo "[ar-replay-qa] running ARReplayHarnessTest (headless AR replay over all AR demos)…"
HARNESS_STATUS=0
# `tee` the instrumentation stream to a file for the artifact bundle. errexit is
# toggled off around the pipe so PIPESTATUS[0] captures `am instrument`'s own
# status (not tee's) — a crashed instrumentation host exits non-zero, whereas a
# plain JUnit assertion failure would still exit 0 and write its summary.
set +e
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.sceneview.demo.ar.ARReplayHarnessTest \
  io.github.sceneview.demo.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$INSTR_FILE"
HARNESS_STATUS=${PIPESTATUS[0]}
set -e

# Stop the logcat stream now the run is over so the file is complete before we
# inspect it below.
stop_logcat
trap - EXIT

# ── Pull the machine-readable verdict ──────────────────────────────────────
DEVICE_SUMMARY="/sdcard/Download/SceneView/ar-qa-summary.json"
SUMMARY_PULLED=0
if adb -s "$SERIAL" pull "$DEVICE_SUMMARY" "$LOCAL_SUMMARY" >/dev/null 2>&1; then
  SUMMARY_PULLED=1
  echo "[ar-replay-qa] verdict summary:"
  cat "$LOCAL_SUMMARY"
  echo
  echo "[ar-replay-qa] summary written to: $LOCAL_SUMMARY"
fi

# ── Verdict ────────────────────────────────────────────────────────────────
# A non-zero instrumentation status is NOT a normal JUnit assertion failure
# (that prints `FAILURES!!!` + a stack trace and still writes the summary). It
# means the instrumentation host process DIED — a demo crashed the shared
# MainActivity process, or lmkd OOM-killed it — before the sweep finished. The
# harness now writes its summary incrementally with an `inProgress` marker, so a
# recovered partial summary names the exact culprit; the streamed logcat holds
# the native crash tombstone the instrumentation stdout does not (#2620).
if [[ "$HARNESS_STATUS" -ne 0 ]]; then
  echo "[ar-replay-qa] FAIL — AR replay harness exited non-zero ($HARNESS_STATUS)." >&2
  if [[ "$SUMMARY_PULLED" -eq 1 ]]; then
    CRASHER=""
    if command -v python3 >/dev/null 2>&1; then
      CRASHER="$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
v = d.get("inProgress")
if v:
    print(v)
' "$LOCAL_SUMMARY" 2>/dev/null || true)"
    fi
    if [[ -n "$CRASHER" ]]; then
      echo "[ar-replay-qa] the app process died while replaying demo '$CRASHER'" >&2
      echo "[ar-replay-qa] — it runs in MainActivity's process, which is also the" >&2
      echo "[ar-replay-qa] instrumentation host, so a native crash / OOM-kill took" >&2
      echo "[ar-replay-qa] both down and no per-demo verdict is possible for it." >&2
    else
      echo "[ar-replay-qa] a partial summary was recovered but names no in-flight" >&2
      echo "[ar-replay-qa] demo — see the summary + logcat for the crash point." >&2
    fi
  else
    echo "[ar-replay-qa] no summary on device: the harness died before the demo" >&2
    echo "[ar-replay-qa] sweep began (a setUp / asset-deploy crash), or it" >&2
    echo "[ar-replay-qa] assumeTrue-skipped because the bundled recording is absent." >&2
  fi
  echo "[ar-replay-qa] captured logcat:        $LOGCAT_FILE" >&2
  echo "[ar-replay-qa] instrumentation output: $INSTR_FILE" >&2
  # Echo the most telling native-crash lines inline so the CI job log is
  # self-diagnosing without downloading the artifact.
  echo "[ar-replay-qa] --- logcat crash signature (grep) -------------------------" >&2
  grep -aiE 'FATAL|signal [0-9]|SIGSEGV|SIGABRT|tombstone|lowmemorykiller|lmkd|Process io.github.sceneview.demo.*(died|killed)|libfilament|JNI DETECTED' \
    "$LOGCAT_FILE" 2>/dev/null | tail -40 >&2 || true
  echo "[ar-replay-qa] -----------------------------------------------------------" >&2
  exit 1
fi

# HARNESS_STATUS==0 but no summary → the test assumeTrue-skipped before writing
# one: the bundled recording is absent (a release build, or a sparse checkout
# that excluded the MP4). That is intentionally green — a sparse checkout must
# not fail the leg (see ARReplayHarnessTest KDoc, #1565). Nothing was replayed,
# so do NOT fall through to the PASS line (which would falsely claim frames
# replayed); exit 0 with an honest note.
if [[ "$SUMMARY_PULLED" -eq 0 ]]; then
  echo "[ar-replay-qa] no ar-qa-summary.json and instrumentation exited cleanly —" >&2
  echo "[ar-replay-qa] the harness assumeTrue-skipped before the sweep (bundled" >&2
  echo "[ar-replay-qa] recording absent: release build or sparse checkout). Nothing" >&2
  echo "[ar-replay-qa] to replay; leaving the leg green (#1565)." >&2
  exit 0
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
