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
#      Sharded (#2643): the sweep is split into `AR_SHARD_COUNT` (default 6)
#      separate `am instrument` runs — `-e ar_shard_index <i> -e ar_shard_count
#      <n>` — with an `am force-stop` between them. A single process replaying
#      all ~32 AR demos back-to-back sustains ~4 min of Filament/GL work on the
#      software-GPU CI emulator, enough CPU/RAM pressure to sever the `am
#      instrument -w` adb connection mid-sweep (rc=255, no verdict recovered).
#      Bounding each process to a few demos keeps every shard short enough to
#      finish; a shard that is still severed costs only its own demos — the
#      others produce real per-demo verdicts. Each shard's summary is pulled and
#      the per-shard verdicts are merged; any demo a shard PLANNED but never
#      reached (severed early) is recorded `skipped` with an environmental
#      reason — never lost silently, never a fake pass.
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
# Exit status (computed by merging every shard's verdict):
#   0  every AR demo survived recorded-session replay AND the recorded ARCore
#      dataset genuinely replayed frames (verdict `replayed`); OR every shard
#      assumeTrue-skipped because the bundled recording is absent (sparse
#      checkout / release build) — a no-op that stays green (#1565)
#   1  one or more demos crashed (verdict `crashed` — a real product regression),
#      OR the harness died before writing ANY verdict for ANY shard (a setUp /
#      asset-deploy crash). The merged summary names the crashed demo(s) and the
#      captured logcat holds the crash signature (#2620)
#   2  no device / emulator connected
#   3  SKIPPED — no demo crashed, but one or more demos were not validated:
#      `ar-record-playback` advanced 0 frames (ARCore dataset playback is
#      unsupported on this emulator — #1645), and/or a shard was severed under
#      emulator memory/CPU pressure so its demos were recorded `skipped`
#      (environmental — #2643). Reported honestly per-demo — never a fake pass.

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
  # Free the Gradle daemon's heap (~1-2 GB) before the RAM-heavy emulator sweep.
  # On the CI runner the emulator + SwiftShader + adb share the same ~16 GB as
  # this daemon; host memory pressure is one trigger for the severed
  # `am instrument` connection this script now shards around (#2643). No further
  # Gradle call follows in this script, so stopping the daemon is free here.
  ./gradlew --stop >/dev/null 2>&1 || true
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

# ── Run the replay harness, sharded ────────────────────────────────────────
# See the header block: the sweep runs in SHARD_COUNT separate `am instrument`
# invocations with an `am force-stop` between them, so no single process replays
# every AR demo and a severed shard costs only its own demos (#2643).
SHARD_COUNT="${AR_SHARD_COUNT:-6}"
case "$SHARD_COUNT" in ''|*[!0-9]*) SHARD_COUNT=6 ;; esac
[[ "$SHARD_COUNT" -lt 1 ]] && SHARD_COUNT=1

DEVICE_SUMMARY="/sdcard/Download/SceneView/ar-qa-summary.json"
PKG="io.github.sceneview.demo"
ANY_SHARD_NONZERO=0
SHARDS_PULLED=0
MISSING_SHARDS=""

echo "[ar-replay-qa] running ARReplayHarnessTest in $SHARD_COUNT shard(s)…"
shard=0
while [[ "$shard" -lt "$SHARD_COUNT" ]]; do
  echo "" | tee -a "$INSTR_FILE" >/dev/null
  echo "[ar-replay-qa] ── shard $shard of $SHARD_COUNT ──" | tee -a "$INSTR_FILE"
  # Fresh process for this shard — nothing lingers from the previous one.
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  # Drop the previous shard's on-device summary so a failed pull cannot recover
  # a stale file and mis-attribute it to this shard.
  adb -s "$SERIAL" shell rm -f "$DEVICE_SUMMARY" >/dev/null 2>&1 || true

  # `tee -a` the instrumentation stream into the artifact bundle. errexit is
  # toggled off around the pipe so PIPESTATUS[0] captures `am instrument`'s own
  # status (not tee's) — a severed/crashed host exits non-zero, a plain JUnit
  # assertion failure still exits 0 and writes its summary.
  set +e
  adb -s "$SERIAL" shell am instrument -w \
    -e class io.github.sceneview.demo.ar.ARReplayHarnessTest \
    -e ar_shard_index "$shard" \
    -e ar_shard_count "$SHARD_COUNT" \
    io.github.sceneview.demo.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee -a "$INSTR_FILE"
  shard_status=${PIPESTATUS[0]}
  set -e
  [[ "$shard_status" -ne 0 ]] && ANY_SHARD_NONZERO=1

  # Calm the emulator and guarantee the summary is flushed before pulling: if
  # `am instrument` was severed the on-device test may still be running, so
  # force-stop it — that stops the Filament churn and makes `adb pull` reliable
  # (the mid-thrash pull is exactly why #2620's summary was never recovered).
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true

  shard_json="$OUT_DIR/ar-shard-$shard.json"
  for attempt in 1 2 3; do
    if adb -s "$SERIAL" pull "$DEVICE_SUMMARY" "$shard_json" >/dev/null 2>&1; then
      SHARDS_PULLED=$((SHARDS_PULLED + 1))
      echo "[ar-replay-qa] shard $shard verdict pulled (am instrument rc=$shard_status)"
      break
    fi
    if [[ "$attempt" -eq 3 ]]; then
      echo "[ar-replay-qa] shard $shard: no summary pulled (am instrument rc=$shard_status)" >&2
      MISSING_SHARDS="${MISSING_SHARDS:+$MISSING_SHARDS,}$shard"
    fi
    sleep 1
  done
  shard=$((shard + 1))
done

# Stop the logcat stream now every shard is done so the file is complete.
stop_logcat
trap - EXIT

# ── No shard produced a summary ────────────────────────────────────────────
# Either every shard assumeTrue-skipped (bundled recording absent — a sparse
# checkout / release build, intentionally green #1565), or the harness died
# before writing any verdict at all (a setUp / asset-deploy crash).
if [[ "$SHARDS_PULLED" -eq 0 ]]; then
  if [[ "$ANY_SHARD_NONZERO" -eq 0 ]]; then
    echo "[ar-replay-qa] no ar-qa-summary.json from any shard and every shard exited" >&2
    echo "[ar-replay-qa] cleanly — the harness assumeTrue-skipped before the sweep" >&2
    echo "[ar-replay-qa] (bundled recording absent). Nothing to replay; green (#1565)." >&2
    exit 0
  fi
  echo "[ar-replay-qa] FAIL — no verdict from any shard and a shard exited non-zero." >&2
  echo "[ar-replay-qa] The harness died before writing any summary (setUp / asset" >&2
  echo "[ar-replay-qa] deploy crash), so no demo can be named. See logcat." >&2
  echo "[ar-replay-qa] captured logcat: $LOGCAT_FILE" >&2
  echo "[ar-replay-qa] --- logcat crash signature (grep) -------------------------" >&2
  grep -aiE 'FATAL|signal [0-9]|SIGSEGV|SIGABRT|tombstone|lowmemorykiller|lmkd|Process io.github.sceneview.demo.*(died|killed)|libfilament|JNI DETECTED' \
    "$LOGCAT_FILE" 2>/dev/null | tail -40 >&2 || true
  echo "[ar-replay-qa] -----------------------------------------------------------" >&2
  exit 1
fi

# ── Merge the per-shard verdicts ───────────────────────────────────────────
# Combine every shard's `demos[]`. Any demo a shard PLANNED but never reached
# (its process was severed early) is recorded `skipped` with an environmental
# reason — accounted for, never lost silently, never folded into `passed`.
if ! command -v python3 >/dev/null 2>&1; then
  echo "[ar-replay-qa] python3 unavailable — cannot merge shard verdicts." >&2
  exit 1
fi

MERGE_VERDICT="$(
  OUT_DIR="$OUT_DIR" LOCAL_SUMMARY="$LOCAL_SUMMARY" \
  SHARD_COUNT="$SHARD_COUNT" MISSING_SHARDS="$MISSING_SHARDS" python3 <<'PY'
import glob, json, os

out_dir = os.environ["OUT_DIR"]
local_summary = os.environ["LOCAL_SUMMARY"]
shard_count = int(os.environ.get("SHARD_COUNT", "0") or 0)
missing_shards = [int(s) for s in os.environ.get("MISSING_SHARDS", "").split(",") if s]
ENV_REASON = ("instrumentation host severed under emulator memory/CPU pressure — "
              "demo not exercised this run (environmental, not a product crash) (#2643)")
IN_FLIGHT_REASON = ("was in-flight when its shard's instrumentation was severed — "
                    "prime suspect for the kill, see ar-logcat.txt (#2620/#2643)")

by_id = {}       # demoId -> demo verdict object
planned = set()  # every demo any shard set out to replay
in_flight = []   # inProgress demos from severed shards (final summary non-null)
recording = None

for path in sorted(glob.glob(os.path.join(out_dir, "ar-shard-*.json"))):
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        continue
    recording = recording or data.get("recording")
    for pid in data.get("plannedDemos", []):
        planned.add(pid)
    for d in data.get("demos", []):
        did = d.get("id")
        if did:
            by_id[did] = d
    # A pulled summary whose `inProgress` is non-null is a severed shard's last
    # write: that demo was actively replaying when the process died (#2620).
    prog = data.get("inProgress")
    if prog:
        in_flight.append(prog)

# A planned demo with no verdict → its shard was severed before reaching it.
# The one that was in-flight at severance keeps the sharper suspect reason.
for pid in planned:
    if pid not in by_id:
        reason = IN_FLIGHT_REASON if pid in in_flight else ENV_REASON
        by_id[pid] = {"id": pid, "verdict": "skipped", "replayedFrames": 0,
                      "reason": reason}

demos = [by_id[k] for k in sorted(by_id)]
passed = sum(1 for d in demos if d.get("verdict") in ("replayed", "alive"))
skipped = sum(1 for d in demos if d.get("verdict") == "skipped")
failed = sum(1 for d in demos if d.get("verdict") == "crashed")
env_skips = sum(1 for d in demos
                if d.get("verdict") == "skipped"
                and d.get("reason") in (ENV_REASON, IN_FLIGHT_REASON))

merged = {
    "harness": "ar-replay",
    "recording": recording,
    "passed": passed,
    "skipped": skipped,
    "failed": failed,
    "total": len(demos),
    "environmentalSkips": env_skips,
    "shardCount": shard_count,
    "missingShards": missing_shards,
    "inProgress": in_flight or None,
    "demos": demos,
}
with open(local_summary, "w") as f:
    json.dump(merged, f, indent=2)

# A shard that produced NO summary leaves its demos entirely unaccounted —
# neither planned nor verdict'd. That absence must never grade as a pass
# (#2643 review ERROR 1: the #2620 original sin recurring at shard level).
if missing_shards:
    print("shardloss")
elif failed > 0:
    print("fail")
elif len(demos) == 0:
    print("empty")
elif skipped > 0:
    print("skip")
else:
    print("pass")
PY
)"

if [[ -f "$LOCAL_SUMMARY" ]]; then
  echo "[ar-replay-qa] merged verdict summary:"
  cat "$LOCAL_SUMMARY"
  echo
  echo "[ar-replay-qa] summary written to: $LOCAL_SUMMARY"
fi

# ── Final verdict ──────────────────────────────────────────────────────────
case "$MERGE_VERDICT" in
  pass)
    echo "[ar-replay-qa] PASS — every AR demo survived recorded-session replay" \
      "and the recorded ARCore dataset replayed frames."
    exit 0
    ;;
  skip)
    echo "[ar-replay-qa] SKIPPED — no AR demo crashed, but one or more demos were" >&2
    echo "[ar-replay-qa] not validated: ar-record-playback replayed 0 frames (ARCore" >&2
    echo "[ar-replay-qa] dataset playback unsupported on this emulator, #1645) and/or a" >&2
    echo "[ar-replay-qa] shard was severed under emulator pressure (#2643). Per-demo" >&2
    echo "[ar-replay-qa] reasons: $LOCAL_SUMMARY" >&2
    exit 3
    ;;
  empty)
    echo "[ar-replay-qa] no demos accounted for after merge — treating as skipped." >&2
    exit 3
    ;;
  shardloss)
    echo "[ar-replay-qa] FAIL — shard(s) [$MISSING_SHARDS] of $SHARD_COUNT produced no" >&2
    echo "[ar-replay-qa] summary at all: their demos are UNACCOUNTED for this run (not" >&2
    echo "[ar-replay-qa] planned, not verdict'd). Never grading that as a pass (#2643)." >&2
    echo "[ar-replay-qa] Diagnose with the per-shard instrumentation output + logcat:" >&2
    echo "[ar-replay-qa]   instrumentation: $INSTR_FILE" >&2
    echo "[ar-replay-qa]   logcat:          $LOGCAT_FILE" >&2
    echo "[ar-replay-qa] --- logcat crash signature (grep) -------------------------" >&2
    grep -aiE 'FATAL|signal [0-9]|SIGSEGV|SIGABRT|tombstone|lowmemorykiller|lmkd|Process io.github.sceneview.demo.*(died|killed)|libfilament|JNI DETECTED' \
      "$LOGCAT_FILE" 2>/dev/null | tail -40 >&2 || true
    echo "[ar-replay-qa] -----------------------------------------------------------" >&2
    exit 1
    ;;
  *)
    echo "[ar-replay-qa] FAIL — one or more AR demos crashed during recorded-session replay." >&2
    python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
c = [x["id"] for x in d.get("demos", []) if x.get("verdict") == "crashed"]
if c:
    print("[ar-replay-qa] crashed demo(s): " + ", ".join(c))
' "$LOCAL_SUMMARY" >&2 2>/dev/null || true
    echo "[ar-replay-qa] captured logcat:        $LOGCAT_FILE" >&2
    echo "[ar-replay-qa] instrumentation output: $INSTR_FILE" >&2
    echo "[ar-replay-qa] --- logcat crash signature (grep) -------------------------" >&2
    grep -aiE 'FATAL|signal [0-9]|SIGSEGV|SIGABRT|tombstone|lowmemorykiller|lmkd|Process io.github.sceneview.demo.*(died|killed)|libfilament|JNI DETECTED' \
      "$LOGCAT_FILE" 2>/dev/null | tail -40 >&2 || true
    echo "[ar-replay-qa] -----------------------------------------------------------" >&2
    exit 1
    ;;
esac
