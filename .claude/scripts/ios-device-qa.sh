#!/usr/bin/env bash
# ios-device-qa.sh — iOS demo device-QA, a thin Maestro wrapper.
#
# The iOS leg of the autonomous device-QA harness (umbrella #1560, slice
# #1563). Maestro YAML flows under `.maestro/ios/` drive every iOS demo
# reachable via the public `sceneview://demo/<id>` deep link in
# `samples/ios-demo` like a real user (custom-scheme deep-link launch,
# camera-orbit drag, viewport tap, ONE screenshot per demo) and assert each
# demo's screen stays alive. See `.maestro/README.md`.
#
# This script is the iOS analogue of `qa-android-demos.sh`: it orchestrates the
# pieces around `maestro test`:
#   1. boot an iOS simulator (if none is booted)
#   2. (optional) build + install the SceneViewDemo app on it
#   3. (best-effort) screen-record the run for visual review — parity with the
#      Android leg; skipped silently if the host lacks hardware Metal
#   4. run the Maestro catalog (or a single category subflow)
#   5. sweep the simulator log for crash markers (Maestro 1.39 cannot read the
#      device log from a flow — same gap the Android wrapper covers with adb).
#
# Usage:
#   bash .claude/scripts/ios-device-qa.sh [--install] [--flow <name>] \
#       [--simulator <name>]
#
# Options:
#   --install            Build the SceneViewDemo app for the simulator and
#                        install it before the flow run.
#   --flow <name>        Run a single category flow instead of the full
#                        catalog, e.g. `--flow lighting` ->
#                        .maestro/ios/lighting.yaml. Defaults to `catalog`.
#   --simulator <name>   Simulator device name to boot / target. Defaults to
#                        "iPhone 16 Pro" (the name used by `.github/workflows/
#                        ios.yml`).
#   -h | --help          Show this help.
#
# Note — a full `xcodebuild` simulator build is heavy (Swift Package resolve +
# RealityKit compile). On a disk-constrained host, run without `--install`
# against an already-installed build, or let the orchestrator runner (umbrella
# slice #1566) do the build once and reuse the simulator.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# This script lives in `.claude/scripts/`, so the repo root is two levels up.
# Resolving it from BASH_SOURCE (not the caller's CWD) keeps every path below
# — `.maestro/...`, the Xcode project — correct no matter where this script is
# invoked from (e.g. device-qa.sh runs it with a non-repo-root CWD, #1585).
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/maestro.sh
source "$SCRIPT_DIR/lib/maestro.sh"

BUNDLE_ID="io.github.sceneview.demo"
DEMO_DIR="samples/ios-demo"
XCODE_PROJECT="SceneViewDemo.xcodeproj"
XCODE_SCHEME="SceneViewDemo"

INSTALL=false
FLOW="catalog"
SIMULATOR="iPhone 16 Pro"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install)   INSTALL=true; shift ;;
    --flow)      FLOW="${2:?--flow needs a name}"; shift 2 ;;
    --simulator) SIMULATOR="${2:?--simulator needs a name}"; shift 2 ;;
    -h|--help)
      sed -n '2,33p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "[ios-qa] unknown argument: $1" >&2; exit 2 ;;
  esac
done

FLOW_FILE=".maestro/ios/${FLOW}.yaml"
if [[ ! -f "$FLOW_FILE" ]]; then
  echo "[ios-qa] no such flow: $FLOW_FILE" >&2
  echo "[ios-qa] available: $(cd .maestro/ios && ls *.yaml | sed 's/\.yaml//' | tr '\n' ' ')" >&2
  exit 2
fi

# --- Host check ------------------------------------------------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "[ios-qa] ERROR: the iOS simulator only runs on macOS." >&2
  exit 1
fi
if ! command -v xcrun >/dev/null 2>&1; then
  echo "[ios-qa] ERROR: xcrun not found — install Xcode + command-line tools." >&2
  exit 1
fi

# --- Boot a simulator ------------------------------------------------------
# Reuse an already-booted simulator if there is one; otherwise boot the named
# device. `simctl boot` is idempotent enough but we guard it anyway.
BOOTED_UDID="$(xcrun simctl list devices booted -j \
  | python3 -c 'import json,sys; ds=json.load(sys.stdin)["devices"]; \
print(next((d["udid"] for v in ds.values() for d in v if d.get("state")=="Booted"), ""))' \
  2>/dev/null || true)"

if [[ -z "$BOOTED_UDID" ]]; then
  echo "[ios-qa] no booted simulator — booting \"$SIMULATOR\"..."
  TARGET_UDID="$(xcrun simctl list devices available -j \
    | python3 -c "import json,sys; ds=json.load(sys.stdin)['devices']; \
print(next((d['udid'] for v in ds.values() for d in v if d.get('name')=='$SIMULATOR'), ''))" \
    2>/dev/null || true)"
  if [[ -z "$TARGET_UDID" ]]; then
    echo "[ios-qa] ERROR: no available simulator named \"$SIMULATOR\"." >&2
    echo "[ios-qa] list devices with: xcrun simctl list devices available" >&2
    exit 1
  fi
  xcrun simctl boot "$TARGET_UDID"
  BOOTED_UDID="$TARGET_UDID"
fi
echo "[ios-qa] using simulator $BOOTED_UDID"

# --- Optional build + install ---------------------------------------------
if $INSTALL; then
  echo "[ios-qa] building $XCODE_SCHEME for the simulator (this is heavy)..."
  DERIVED_DATA="$(mktemp -d)"
  set -o pipefail
  xcodebuild build \
    -project "$DEMO_DIR/$XCODE_PROJECT" \
    -scheme "$XCODE_SCHEME" \
    -destination "id=$BOOTED_UDID" \
    -derivedDataPath "$DERIVED_DATA" \
    | { command -v xcpretty >/dev/null 2>&1 && xcpretty || cat; }

  APP_PATH="$(find "$DERIVED_DATA/Build/Products" -name 'SceneViewDemo.app' -maxdepth 3 -print -quit)"
  if [[ -z "$APP_PATH" ]]; then
    echo "[ios-qa] ERROR: build succeeded but SceneViewDemo.app was not found." >&2
    rm -rf "$DERIVED_DATA"
    exit 1
  fi
  echo "[ios-qa] installing $APP_PATH"
  xcrun simctl install "$BOOTED_UDID" "$APP_PATH"
  rm -rf "$DERIVED_DATA"
fi

# --- Optional screen recording (best-effort) -------------------------------
# Record the whole QA run for visual review — parity with the Android leg.
# `simctl io recordVideo` needs hardware Metal, which CI VMs may lack, so this
# is strictly best-effort: a recording failure never fails the QA run. h264
# (not the hevc default) sidesteps the recordVideo frame-glitch artefacts. The
# .mov lands under tools/qa-screenshots/ios/ (gitignored).
REC_PID=""
QA_VIDEO_DIR="${IOS_QA_VIDEO_DIR:-tools/qa-screenshots/ios}"
QA_VIDEO="$QA_VIDEO_DIR/ios-qa-${FLOW}.mov"
mkdir -p "$QA_VIDEO_DIR"
xcrun simctl io "$BOOTED_UDID" recordVideo --codec h264 --force "$QA_VIDEO" \
  >/dev/null 2>&1 &
REC_PID=$!
sleep 1
if kill -0 "$REC_PID" 2>/dev/null; then
  echo "[ios-qa] recording the QA run -> $QA_VIDEO"
else
  REC_PID=""
  echo "[ios-qa] screen recording unavailable (recordVideo needs hardware Metal) — continuing without it"
fi

# --- Crash gate: spawn a log tail so the post-run sweep is scoped ----------
# Maestro's per-demo `assertVisible` already fails on a hard crash, but a
# background NSException or a watchdog kill can be missed — so we also grep the
# simulator log. This complements (does not replace) the orchestrator runner's
# sweep (umbrella slice #1566).
LOG_FILE="$(mktemp)"
xcrun simctl spawn "$BOOTED_UDID" log stream \
  --predicate "process == \"SceneViewDemo\"" --style compact \
  > "$LOG_FILE" 2>/dev/null &
LOG_PID=$!
# Make sure the log tail — and the screen recording — die with the script.
# The recording is stopped with SIGINT (not the default SIGKILL) so the .mov
# is finalised rather than left truncated.
trap 'kill -INT "${REC_PID:-}" 2>/dev/null || true; kill "$LOG_PID" 2>/dev/null || true; rm -f "$LOG_FILE"' EXIT

# --- Run the Maestro flow --------------------------------------------------
echo "[ios-qa] running Maestro flow: $FLOW_FILE"
MAESTRO_RC=0
maestro_run "$FLOW_FILE" || MAESTRO_RC=$?

# Stop the screen recording cleanly — SIGINT, never SIGKILL, or the .mov is
# left truncated/unplayable. `wait` lets recordVideo finalise the container.
if [[ -n "$REC_PID" ]]; then
  kill -INT "$REC_PID" 2>/dev/null || true
  wait "$REC_PID" 2>/dev/null || true
  if [[ -s "$QA_VIDEO" ]]; then
    echo "[ios-qa] recording saved: $QA_VIDEO"
  fi
fi

# Give the log stream a moment to flush, then stop it.
sleep 1
kill "$LOG_PID" 2>/dev/null || true

# --- Crash log sweep -------------------------------------------------------
CRASHES="$(grep -E "Fatal error|NSException|EXC_BAD_ACCESS|did crash|Terminating app" "$LOG_FILE" 2>/dev/null || true)"
if [[ -n "$CRASHES" ]]; then
  echo "[ios-qa] CRASH detected in the simulator log:" >&2
  echo "$CRASHES" | tail -20 >&2
  MAESTRO_RC=1
fi

if [[ "$MAESTRO_RC" -eq 0 ]]; then
  echo "[ios-qa] PASS — $FLOW flow completed, no crash detected."
else
  echo "[ios-qa] FAIL — see Maestro output above (rc=$MAESTRO_RC)." >&2
fi
exit "$MAESTRO_RC"
