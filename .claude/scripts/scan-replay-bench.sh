#!/usr/bin/env bash
# Compare two debug APKs over private ARCore walks; no build and no upload.
# Usage: bash .claude/scripts/scan-replay-bench.sh --apk-a main.apk --label-a main \
#          --apk-b v2.apk --label-b v2 [--corpus DIR] [--walks all|A,B|id,id] \
#          [--serial 09291JEC223501] [--out DIR] [--measure-only]
# --measure-only: no screen recording or UI polls during the live scan; ScanBench cadence only.
# Requires Python 3.9+, adb, ffmpeg and ffprobe. No drawtext or browser required.
# Outputs default to ~/sceneview-bench/YYYY-MM-DD-HHMM; repository paths are refused.
# Use --self-test [--out DIR] for synthetic parser, lifecycle, video and sheet tests.
# Default walks: the five references A–E, in that order. --walks all selects the corpus.
# Install A once, run every walk, then install B once and repeat. Walks stay on the device.
# Real runs need an unlocked, ARCore-ready device; camera permission is granted after install.
# Replay starts scanning automatically on tracking: qa_state record* would inject fake data.
# Exit: 0 for complete runs, 1 for failed/skipped/incomplete runs or warnings,
# 2 for setup errors, 130 for interruption. Lock each install and each run separately;
# wait up to 10 min at 15 s intervals, then skip. Never steal a lock.
# Both APKs should include the debug ScanBench hook for comparable spatial metrics.
# Videos, frames and logs are private. Nothing is attached, served, or published.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 -B "$SCRIPT_DIR/scan_replay_bench.py" "$@"
