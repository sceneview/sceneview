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
#   5. capture the simulator's unified log for the whole run and sweep it for
#      crash markers (Maestro 1.39 cannot read the device log from a flow —
#      same gap the Android wrapper covers with adb). The captured log is kept
#      as a run artifact next to the .mov, so a crash line is inspectable
#      after the fact (inspired by XcodeBuildMCP's automatic per-app os_log
#      capture on launch_app_sim).
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
#                        whatever iOS device this host actually has — the newest
#                        runtime's iPhone, resolved by `lib/ios-simulator.sh`,
#                        the same resolver `.github/workflows/ios.yml` uses.
#                        Pass a name only to pin a specific model on purpose.
#   --sketchfab-key <k>  Explicit Sketchfab API key for a KEYED build (#2356),
#                        overriding the env / local.properties resolution. With
#                        a key present (here, the SKETCHFAB_API_KEY env var, or
#                        repo-root local.properties `sketchfab.api.key`) the
#                        `--install` build bakes it into Info.plist so the
#                        Explore/Sketchfab + streamed-USDZ demos are exercised;
#                        without one the build is keyless and that path is
#                        loudly reported NOT-tested. Key value is never logged.
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
# shellcheck source=lib/qa-keys.sh
# Shared key resolver (#2343, reused for iOS #2356): resolves SKETCHFAB_API_KEY
# from the env, else repo-root local.properties `sketchfab.api.key`, and reports
# PRESENCE (never the value). The iOS build wires it via a `xcodebuild`
# user-defined build setting → Info.plist `SketchfabAPIKey = $(SKETCHFAB_API_KEY)`
# → SketchfabConfig.apiKey, mirroring the Android assembleDebug buildConfigField.
source "$SCRIPT_DIR/lib/qa-keys.sh"
# shellcheck source=lib/ios-simulator.sh
# Waits for CoreSimulator, then resolves a simulator by UDID at run time rather
# than pinning a model name (#3174).
source "$SCRIPT_DIR/lib/ios-simulator.sh"

BUNDLE_ID="io.github.sceneview.demo"
# The demo app's CFBundleExecutable — what `log stream` sees as the process
# name. NOT "SceneViewDemo" (that's the scheme/project name): the built bundle
# is SceneView.app and the unified log tags its lines `SceneView[pid]`.
PROCESS_NAME="SceneView"
DEMO_DIR="samples/ios-demo"
XCODE_PROJECT="SceneViewDemo.xcodeproj"
XCODE_SCHEME="SceneViewDemo"

INSTALL=false
FLOW="catalog"
# Empty means "resolve one at run time" (see lib/ios-simulator.sh), which also
# waits out the CoreSimulator cold window that #3174 turned out to be. Passing
# --simulator <name> opts back into a fixed model, and back into rotting the day
# Xcode replaces the device set.
SIMULATOR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install)   INSTALL=true; shift ;;
    --flow)      FLOW="${2:?--flow needs a name}"; shift 2 ;;
    --simulator) SIMULATOR="${2:?--simulator needs a name}"; shift 2 ;;
    --sketchfab-key)
      # Explicit key override for a keyed build (#2356). Takes precedence over
      # the env / local.properties resolution below. The value is consumed, not
      # echoed.
      export SKETCHFAB_API_KEY="${2:?--sketchfab-key needs a value}"; shift 2 ;;
    -h|--help)
      sed -n '2,46p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
  if [[ -z "$SIMULATOR" ]]; then
    # No `--simulator` given: take whatever iOS device this host actually has.
    echo "[ios-qa] no booted simulator — resolving one..."
    if ! TARGET_UDID="$(ios_simulator_udid)"; then
      exit 1
    fi
  else
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
  fi
  xcrun simctl boot "$TARGET_UDID"
  BOOTED_UDID="$TARGET_UDID"
fi
echo "[ios-qa] using simulator $BOOTED_UDID"

# --- Resolve the Sketchfab key (#2356) -------------------------------------
# Resolve + export SKETCHFAB_API_KEY (env → repo-root local.properties
# `sketchfab.api.key`) so the build below bakes it into Info.plist. Presence
# only is ever logged — never the value (qa-keys.sh security invariant). When
# the key is absent the build stays keyless and the Explore/Sketchfab path is
# reported as NOT tested via the loud banner, mirroring Android #2343.
qa_keys_resolve_sketchfab || true
echo "[ios-qa] Sketchfab API key present: ${QA_SKETCHFAB_KEY_PRESENT}"
# The Info.plist substitutes SketchfabAPIKey = $(SKETCHFAB_API_KEY). When the
# key is present we pass it as a `xcodebuild` user-defined build setting (which
# OVERRIDES the Config.xcconfig `#include?` of Secrets.xcconfig — so the harness
# never has to write a key file to disk); when absent we pass nothing and the
# placeholder resolves empty (SketchfabConfig handles empty/unsubstituted).
# Built as a positional list. NOTE: assigned element-by-element (not `arr=()`
# then `"${arr[@]}"`) because macOS's default bash 3.2 treats expanding an
# EMPTY array under `set -u` as an unbound-variable error.
SKETCHFAB_BUILD_SETTING=""
if [[ "${QA_SKETCHFAB_KEY_PRESENT}" == "true" ]]; then
  SKETCHFAB_BUILD_SETTING="SKETCHFAB_API_KEY=${SKETCHFAB_API_KEY}"
fi

# --- Optional build + install ---------------------------------------------
if $INSTALL; then
  echo "[ios-qa] building $XCODE_SCHEME for the simulator (this is heavy)..."
  DERIVED_DATA="$(mktemp -d)"
  set -o pipefail
  # NB: the key build setting (if any) is the LAST argument — `xcodebuild`
  # parses `NAME=value` overrides positionally after the options. It is passed
  # unquoted-as-one-arg only when non-empty so a keyless build sends no stray
  # empty argument. It is never echoed; `set -x` is never enabled here.
  xcodebuild build \
    -project "$DEMO_DIR/$XCODE_PROJECT" \
    -scheme "$XCODE_SCHEME" \
    -destination "id=$BOOTED_UDID" \
    -derivedDataPath "$DERIVED_DATA" \
    ${SKETCHFAB_BUILD_SETTING:+"$SKETCHFAB_BUILD_SETTING"} \
    | { command -v xcpretty >/dev/null 2>&1 && xcpretty || cat; }

  APP_PATH="$(find "$DERIVED_DATA/Build/Products" -name 'SceneView.app' -maxdepth 3 -print -quit)"
  if [[ -z "$APP_PATH" ]]; then
    echo "[ios-qa] ERROR: build succeeded but SceneView.app was not found." >&2
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
#
# The log is a KEPT run artifact (not a mktemp) so the exact os_log of a red
# run is inspectable after the fact and device-qa.sh can attach its path to
# the per-leg verdict in device-qa-report.json.
#
# Predicate — verified empirically against the installed demo app (iPhone 17,
# iOS 26.3 sim), three ORed legs because no single one sees every failure:
#   process == "SceneView"      the app's own runtime output (~22k lines over
#                               a flow run) — where NSException / Fatal error
#                               markers land, since the crashing process logs
#                               those itself;
#   subsystem == "$BUNDLE_ID"   the app's structured Logger() calls (only
#                               SketchfabConfig today, but future-proof);
#   eventMessage CONTAINS ...   the SYSTEM side. A signal death (SIGSEGV etc.)
#                               kills the app before it can log anything — the
#                               only record is runningboardd/SpringBoard's
#                               "[app<bundle-id>:pid] Process exited: ...
#                               code:SIGSEGV(11)", which names the bundle id
#                               in the message but matches neither filter
#                               above (verified with a synthetic kill -SEGV).
SIM_LOG="${IOS_QA_LOG_FILE:-$QA_VIDEO_DIR/ios-sim-${FLOW}.log}"
: > "$SIM_LOG"
xcrun simctl spawn "$BOOTED_UDID" log stream \
  --predicate "process == \"$PROCESS_NAME\" OR subsystem == \"$BUNDLE_ID\" OR eventMessage CONTAINS \"$BUNDLE_ID\"" \
  --style compact \
  > "$SIM_LOG" 2>/dev/null &
LOG_PID=$!
# Make sure the log tail — and the screen recording — die with the script.
# The recording is stopped with SIGINT (not the default SIGKILL) so the .mov
# is finalised rather than left truncated.
trap 'kill -INT "${REC_PID:-}" 2>/dev/null || true; kill "$LOG_PID" 2>/dev/null || true' EXIT

# --- Run the Maestro flow --------------------------------------------------
# `--udid` pins Maestro to the iOS simulator explicitly. Without it, Maestro
# picks a default device — and on a host where the Android QA emulator pool
# has a Pixel_7a running (the common case, #1654), it targets THAT and the
# whole iOS leg silently runs against the wrong platform.
echo "[ios-qa] running Maestro flow: $FLOW_FILE"
MAESTRO_RC=0
maestro_run "$FLOW_FILE" --udid "$BOOTED_UDID" || MAESTRO_RC=$?

# Stop the screen recording cleanly — SIGINT, never SIGKILL, or the .mov is
# left truncated/unplayable. `wait` lets recordVideo finalise the container.
if [[ -n "$REC_PID" ]]; then
  kill -INT "$REC_PID" 2>/dev/null || true
  wait "$REC_PID" 2>/dev/null || true
  if [[ -s "$QA_VIDEO" ]]; then
    echo "[ios-qa] recording saved: $QA_VIDEO"
  fi
fi

# Give the log stream a moment to flush, then stop it. The captured log is
# kept — device-qa.sh picks this exact line up to attach the path to the iOS
# verdict in device-qa-report.json.
sleep 1
kill "$LOG_PID" 2>/dev/null || true
echo "[ios-qa] simulator log: $SIM_LOG"

# --- Crash log sweep -------------------------------------------------------
# In-process markers (Fatal error / NSException / "Terminating app due to")
# plus the runningboardd signal-exit record (`code:SIGSEGV(11)` etc.) that is
# the ONLY trace of a signal death. Deliberately NOT matched: bare
# "Terminating app" (Foundation logs "[Start] Terminating app <bundle-id>" on
# every CLEAN terminate between demos) and SIGKILL (how launch/terminate stops
# the previous instance) — both would false-positive a healthy run.
CRASHES="$(grep -E "Fatal error|NSException|EXC_BAD_ACCESS|did crash|Terminating app due to|code:SIG(SEGV|ABRT|BUS|ILL|TRAP|FPE)" "$SIM_LOG" 2>/dev/null || true)"
if [[ -n "$CRASHES" ]]; then
  echo "[ios-qa] CRASH detected in the simulator log:" >&2
  echo "$CRASHES" | tail -20 >&2
  MAESTRO_RC=1
fi

# --- Keyless-QA honesty banner (#2356) -------------------------------------
# A keyless run can NEVER be read as "complete": the Explore tab's Sketchfab
# carousels + search and the streamed-USDZ demos (MultiModelDemo, OrbitalARDemo,
# ModelViewerDemo) were not exercised — the build shipped an empty
# SketchfabAPIKey. Suppress the qa-keys.sh ARCore half (the iOS demo has no
# ARCore-Cloud path) and print only the Sketchfab notice. Advisory — it does
# not flip the QA verdict, mirroring the Android `sketchfab` skipped sub-leg.
if [[ "${QA_SKETCHFAB_KEY_PRESENT:-false}" != "true" ]]; then
  QA_ARCORE_KEY_PRESENT=true qa_keys_banner_if_absent
  echo "[ios-qa] NOTE: Sketchfab key absent — Explore/Sketchfab + streamed-USDZ demos NOT tested (keyless build)." >&2
fi

if [[ "$MAESTRO_RC" -eq 0 ]]; then
  echo "[ios-qa] PASS — $FLOW flow completed, no crash detected."
  if [[ "${QA_SKETCHFAB_KEY_PRESENT:-false}" == "true" ]]; then
    echo "[ios-qa] (Sketchfab key WAS present — Explore/Sketchfab path was exercised.)"
  fi
elif [[ "$MAESTRO_RC" -eq 124 ]]; then
  # A budget that expired is NOT a demo crash — grading both as `FAIL` is what
  # made the 4.30.0 checkpoint's `ios failed` unreadable (#3141). The `flow=`
  # token is what device-qa.sh reads to name the flow in its verdict.
  echo "[ios-qa] TIMEOUT — flow=${MAESTRO_TIMEOUT_FLOW:-unknown} exceeded its $(maestro_flow_budget)s budget; no demo failed, the clock did." >&2
else
  echo "[ios-qa] FAIL — see Maestro output above (rc=$MAESTRO_RC)." >&2
fi
exit "$MAESTRO_RC"
