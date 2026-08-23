#!/usr/bin/env bash
# device-qa.sh — autonomous cross-platform device-QA orchestrator runner.
#
# Slice 5 of the device-QA harness umbrella (#1560, this slice #1566).
#
# WHAT THIS IS
# ------------
# A SINGLE unattended entrypoint that ties the four platform harnesses
# (slices #1562-#1565) into one pass and aggregates their machine-readable
# verdicts into ONE report:
#
#   android  -> qa-android-demos.sh    (Maestro, .maestro/android/)
#   ios      -> ios-device-qa.sh       (Maestro, .maestro/ios/)
#   web      -> Playwright suite       (samples/web-demo/tests/ -> web-qa-summary.json)
#   ar       -> ar-replay-qa.sh        (ARReplayHarnessTest  -> ar-qa-summary.json)
#
# This script does NOT re-implement any harness — it boots the emulator /
# simulator each leg needs, builds + installs the demo app, delegates to the
# platform script, and collects the per-platform result. The aggregated
# verdict is written to `device-qa-report.json` and printed as a human
# summary. Exit status is non-zero only when a REQUIRED (non-advisory) leg did
# not pass — this is the gate the release checkpoint hangs on (#1566 "done
# means"). A non-passing ADVISORY leg (#1651/#1670) is a WARN, exit 0.
#
# Usage:
#   bash .claude/scripts/device-qa.sh [--platform=android|ios|web|ar|all]
#                                     [--fast] [--ci] [--out <dir>]
#
# Flags:
#   --platform=<p>   Which platform(s) to run. `all` (default) runs every
#                    platform feasible on this host; an unfeasible one is
#                    reported `skipped` (or, under --ci, `failed`).
#   --fast           Run a per-category subset rather than the full demo
#                    catalog: each platform runs one representative category
#                    flow (Android/iOS: `3d-basics`; web: a single spec; AR:
#                    the replay harness is already the full minimal set).
#   --ci             CI mode: a `skipped` REQUIRED platform (missing emulator /
#                    simulator / toolchain) is treated as a FAILURE, not a
#                    soft skip. Outside --ci a skip is non-fatal — a dev box
#                    rarely has every runtime, and a partial pass is useful.
#                    A skipped ADVISORY leg is a WARN even under --ci (#1670):
#                    an honest #1645 skip on `ar` is expected on a CI emulator
#                    and must never hard-block the release gate.
#   --out <dir>      Directory for the aggregated report + per-platform
#                    artifacts. Default: repo-root (`device-qa-report.json`).
#   --advisory=<csv> Comma-separated platforms whose result is ADVISORY for the
#                    release gate — a failure on an advisory leg surfaces as a
#                    WARN (not a hard block) in release-checklist.sh section 14
#                    (#1651). Default: `android,ar,ios,web-perf,sketchfab,arcore-cloud`.
#                    `android,ar` run on the chronically flaky SwiftShader
#                    emulator (#1643) and are `continue-on-error: true` in
#                    device-qa.yml, so the release gate must not be hard-blocked
#                    by them. `ios` (#2803) is the nightly-only Maestro-on-
#                    simulator leg on a costly macOS runner — advisory until the
#                    simulator boot/timing is proven reliably green. `web-perf`
#                    is the Lighthouse perf sub-leg of `web`
#                    (#1879/#1898) — advisory until its budgets are proven against
#                    real baseline data. `sketchfab` + `arcore-cloud` (#2343) are
#                    the key-gated sub-legs — `skipped` (advisory) when their API
#                    key is absent so a keyless run is never a silent green. The
#                    `web` leg itself is intentionally NOT advisory: it is
#                    reliable and BLOCKING.
#                    Pass `--advisory=` (empty) to make every leg blocking.
#   -h | --help      Show this help.
#
# API keys (#2343):
#   Full local QA needs the demo store secrets so the Explore/Sketchfab path and
#   the AR Cloud demos are actually exercised — otherwise the harness builds a
#   keyless debug APK (Explore disabled, AR Cloud → ERROR_NOT_AUTHORIZED) and the
#   key-gated `sketchfab` / `arcore-cloud` legs report `skipped` (advisory), with
#   a loud banner. Provide them via the repo-root local.properties (gitignored)
#   or the environment — NEVER commit a key value:
#     • local.properties:  sketchfab.api.key=<token>   /   ARCORE_API_KEY=<key>
#     • or env vars:        SKETCHFAB_API_KEY=<token>   /   ARCORE_API_KEY=<key>
#
# Exit status:
#   0  every REQUIRED leg passed. Includes the WARN case where only advisory
#      legs are non-passing (failed or skipped), and — outside --ci — the case
#      where a required leg was skipped (soft partial pass).
#   1  a REQUIRED (non-advisory) leg failed, or — under --ci — a required leg
#      was skipped. A non-passing advisory leg alone never produces exit 1.
#   2  bad invocation / disk gate tripped before any platform ran
#
# Disk hygiene:
#   The runner checks free disk (inline df gate below) before starting and cleans the
#   previous platform's heavy build output before the next leg, so a full
#   `--platform=all` pass on a constrained host never craters free disk.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# .claude/scripts/ -> repo root is two levels up.
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# RAM-budgeted adaptive emulator pool helpers (#1647 → #1654). The android and
# ar legs lease an emulator from the pool: whichever leg runs first leases a
# free running one (or boots a new pool member, RAM-gated, inside
# setup-ar-emulator.sh) and the next leg leases its own — as many emulators as
# live host RAM safely allows, floor 1.
# shellcheck source=lib/emulator-select.sh
source "$SCRIPT_DIR/lib/emulator-select.sh"
# API-key resolution (#2343) — resolve + EXPORT SKETCHFAB_API_KEY / ARCORE_API_KEY
# so the android leg's assembleDebug build is built WITH the keys, and so a
# keyless run reports the key-gated legs as honestly `skipped` (never green).
# shellcheck source=lib/qa-keys.sh
source "$SCRIPT_DIR/lib/qa-keys.sh"

# The demo debug APK both Android-emulator legs install. Kept as the single
# canonical path qa-android-demos.sh expects and the CI build-android-apk job
# restores into. #2343: when a key is present this APK is deleted before each
# leg's build/install (qa_keys_force_fresh_build_if_present) so a stale KEYLESS
# build can never be reused — see PR #2347 review.
DEMO_DEBUG_APK="samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk"

# Pool session identity (#2862). The pool reserves emulators per SESSION, not
# per pid, so a provisioned emulator stops looking free the moment the script
# that provisioned it exits. Resolution order:
#   1. EMU_LEASE_SESSION already exported  -> the caller owns the reservation;
#      we borrow it and must NOT release it on exit.
#   2. A token published by a `setup-ar-emulator.sh` run in the last few minutes
#      -> inherit it once (the documented provision-then-QA two-step).
#   3. Otherwise mint our own -> nothing else will ever claim it, so we release
#      it on exit rather than idling the pool until the sticky TTL.
# The token is EXPORTED so the setup-ar-emulator.sh subprocess below runs under
# the same identity and hands its emulator straight back to us.
EMU_LEASE_SESSION_MINTED=false
if [[ -z "${EMU_LEASE_SESSION:-}" ]]; then
  if ! emu_lease_session_inherit >/dev/null 2>&1; then
    EMU_LEASE_SESSION="$(emu_lease_session_new)"
    EMU_LEASE_SESSION_MINTED=true
  fi
fi
export EMU_LEASE_SESSION
# Never lease a device that is not the pool AVD (#2862) — a stray emulator on a
# pool port would otherwise be driven as if it were the ARCore-ready Pixel_7a.
#
# LOCAL POOL ONLY. On CI the emulator comes from
# ReactiveCircus/android-emulator-runner, whose AVD is named `test` and is not
# a pool member; requiring the pool AVD there would refuse the only emulator on
# the runner and redden the android/ar legs. A runner is single-session anyway,
# which is the collision this guard exists to prevent. An explicit
# EMU_REQUIRE_AVD from the caller always wins.
if [[ -z "${EMU_REQUIRE_AVD:-}" && "${GITHUB_ACTIONS:-}" != "true" ]]; then
  EMU_REQUIRE_AVD="$EMU_POOL_AVD"
fi
export EMU_REQUIRE_AVD

# Release every emulator lease this orchestrator owns when it exits — plus, when
# we minted our own session token, the sticky reservations carrying it.
qa_release_leases() {
  emu_lease_release_all
  if [[ "$EMU_LEASE_SESSION_MINTED" == "true" ]]; then
    emu_lease_release_session
  fi
}
trap 'qa_release_leases' EXIT

# --- Flags -----------------------------------------------------------------
PLATFORM="all"
FAST=false
CI_MODE=false
OUT_DIR="$REPO_ROOT"
# Advisory legs (#1651): a failure here is a release-gate WARN, not a block.
# `android,ar` ride the flaky SwiftShader emulator and are continue-on-error
# in device-qa.yml; `web` is reliable and stays BLOCKING.
# `ios` (#2803) is advisory for the same reason android/ar are: the iOS leg
# runs Maestro on a CI simulator (flaky boot/timing) on a costly macOS runner
# (nightly-only in device-qa.yml, `continue-on-error: true`) — a red iOS leg
# surfaces as a release WARN, never a hard block, until the simulator leg is
# proven reliably green. Promote it out of this CSV to make it blocking.
# `web-perf` (#1898) is the Lighthouse perf sub-leg — advisory at first, until
# its budgets are proven stable against real baseline data. Promote it out of
# this CSV to make a budget breach a hard release block.
# `sketchfab` + `arcore-cloud` (#2343) are the key-gated sub-legs: when their
# API key is absent the path is reported `skipped` (advisory) — a missing LOCAL
# key must surface as a WARN, never hard-block a dev's run.
ADVISORY="android,ar,ios,web-perf,sketchfab,arcore-cloud"
ADVISORY_SET=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform=*) PLATFORM="${1#--platform=}"; shift ;;
    --platform)   PLATFORM="${2:?--platform needs a value}"; shift 2 ;;
    --fast)       FAST=true; shift ;;
    --ci)         CI_MODE=true; shift ;;
    --out=*)      OUT_DIR="${1#--out=}"; shift ;;
    --out)        OUT_DIR="${2:?--out needs a directory}"; shift 2 ;;
    --advisory=*) ADVISORY="${1#--advisory=}"; ADVISORY_SET=true; shift ;;
    --advisory)   ADVISORY="${2-}"; ADVISORY_SET=true; shift 2 ;;
    -h|--help)
      sed -n '2,84p' "$SCRIPT_DIR/device-qa.sh" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "[device-qa] unknown argument: $1" >&2; exit 2 ;;
  esac
done
: "$ADVISORY_SET"  # silence unused-var warnings; reserved for future strictness

# Is platform $1 in the advisory CSV?
is_advisory() {
  case ",$ADVISORY," in
    *",$1,"*) return 0 ;;
    *)        return 1 ;;
  esac
}

case "$PLATFORM" in
  android|ios|web|ar|all) ;;
  *) echo "[device-qa] invalid --platform: $PLATFORM (android|ios|web|ar|all)" >&2; exit 2 ;;
esac

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
REPORT="$OUT_DIR/device-qa-report.json"
# Per-platform artifact directory so two legs never clobber each other.
ARTIFACTS="$OUT_DIR/device-qa-artifacts"
rm -rf "$ARTIFACTS"
mkdir -p "$ARTIFACTS"

# --- Logging ---------------------------------------------------------------
log()  { echo "[device-qa] $*"; }
warn() { echo "[device-qa] WARNING: $*" >&2; }

# --- Disk gate -------------------------------------------------------------
# A full cross-platform pass spins up an emulator + simulator + browser +
# multiple Gradle/xcodebuild builds. Refuse to start on a near-full disk.
#
# The threshold scales with the platform selection. The default 15 GB fits a
# full cross-platform pass (emulator AVD + Gradle + xcodebuild). Single-leg CI
# jobs need far less, and GitHub ubuntu runners float between ~14-21 GB free
# depending on the image of the day, so a blanket 15 GB gate aborts CI at
# random (run 29033630233 killed the BLOCKING web leg before any test ran):
#   • web            ~5 GB — npm + Playwright browsers, no emulator/build.
#   • android | ar   ~8 GB — reuse the prebuilt demo APK, boot one emulator,
#                    assembleDebug to inject the keys (#2343), sideload the
#                    ~300 MB ARCore APK. Well under a full pass — a 15 GB gate
#                    false-aborted the advisory `ar` leg (#2640) exactly as it
#                    once did the web leg.
#   • ios            ~10 GB — a single `xcodebuild` sim build (SPM RealityKit +
#                    the demo app) into a mktemp DerivedData plus a booted
#                    simulator; heavier than android (which reuses a prebuilt
#                    APK), lighter than a full pass. On a disk-tight self-hosted
#                    Mac this trips → the advisory ios leg skips honestly (WARN),
#                    never cratering the host (#2803).
DISK_MIN_GB=15
case "$PLATFORM" in
  web)        DISK_MIN_GB=5 ;;
  android|ar) DISK_MIN_GB=8 ;;
  ios)        DISK_MIN_GB=10 ;;
esac
DISK_GATE_SKIP=false   # set below when an advisory-only --ci run trips the gate
# Inline check: the former disk-gated-spawn-check.sh helper left with the
# harness (#3244), which made this gate fail on every runner regardless of
# free space and turned the blocking web leg red on every push.
if [ "$(df -k / | awk 'NR==2 { print int($4 / 1024 / 1024) }')" -lt "$DISK_MIN_GB" ]; then
  warn "free disk is below the safe threshold — run cleanup before a full pass:"
  warn "  bash .claude/scripts/gradle-cache-cleanup.sh"
  warn "  bash .claude/scripts/worktree-auto-prune.sh --yes --keep \"\$(git rev-parse --show-toplevel)\""
  # A BLOCKING leg (web, or an `all` pass that includes it) must hard-stop in CI
  # so a genuine shortage is never hidden. But a single ADVISORY leg (ar/android)
  # is already non-blocking (#1651/#1670) and an infra-level disk shortage is not
  # a product crash — hard-failing it with exit 2 turned the job red and wrote no
  # report (#2640). Flag it here and, once the report machinery is up, record an
  # honest `skipped` (WARN, exit 0 — the #1645 path): the job stays green with a
  # truthful report and, because no demo ever launches, no real crash is masked.
  # On a dev box (not --ci) a single light leg may still be fine: warn + proceed.
  if $CI_MODE; then
    if [[ "$PLATFORM" != "all" ]] && is_advisory "$PLATFORM"; then
      DISK_GATE_SKIP=true
    else
      echo "[device-qa] CI mode + low disk — aborting before any platform ran." >&2
      exit 2
    fi
  fi
fi

disk_free_gb() {
  df -k / | awk 'NR==2 { printf "%.1f", $4 / 1024 / 1024 }'
}

# Best-effort reclaim of a platform's heavy build output before the next leg.
clean_build_output() {
  local what="$1"
  case "$what" in
    android|ar)
      rm -rf samples/android-demo/build 2>/dev/null || true ;;
    web)
      rm -rf samples/web-demo/test-results \
             samples/web-demo/playwright-report 2>/dev/null || true ;;
    ios)
      # iOS derived data lives in a mktemp dir inside ios-device-qa.sh and is
      # cleaned there; nothing repo-local to reclaim.
      : ;;
  esac
}

# --- Per-platform result accumulator ---------------------------------------
# Parallel arrays — bash 3.2 (macOS default) has no associative-array export.
RESULT_PLATFORMS=()
# `timeout` (#3141) is a FAILURE with a named cause: the leg's clock ran out
# mid-flow, no demo failed. It carries exactly the weight of `failed`
# everywhere it is graded — the only thing it adds is resolution, so an
# expired budget and a real crash stop reading as the same line.
RESULT_STATUSES=()   # passed | failed | timeout | skipped
RESULT_REASONS=()
RESULT_SUMMARIES=()  # path to a platform JSON summary, or "" if none
RESULT_DURATIONS=()
RESULT_LOGS=()       # path to a captured device/simulator log, or "" if none

record() {
  RESULT_PLATFORMS+=("$1")
  RESULT_STATUSES+=("$2")
  RESULT_REASONS+=("$3")
  RESULT_SUMMARIES+=("$4")
  RESULT_DURATIONS+=("$5")
  RESULT_LOGS+=("${6:-}")
  log "$1 -> $2 ${3:+($3)}"
}

# timed_out_flow <leg-output-file> — echo `flow=<name>` for the flow whose
# per-flow budget expired, as printed by lib/maestro.sh's TIMEOUT marker and
# re-emitted by the qa wrapper (#3141). Echoes `an unnamed flow` when the
# marker is absent (e.g. the wrapper itself was killed), never an empty string:
# a verdict that reads "budget expired in " has lost the fact it exists for.
timed_out_flow() {
  local out="$1" name=""
  if [[ -f "$out" ]]; then
    name="$(sed -n 's/^\[[a-z-]*\] TIMEOUT — flow=\([^ ]*\).*/\1/p' "$out" | tail -n 1)"
  fi
  if [[ -n "$name" ]]; then
    echo "flow=$name"
  else
    echo "an unnamed flow"
  fi
}

# Echo the status last recorded for platform $1 (or empty if it never ran).
last_status_of() {
  local want="$1" i found=""
  for i in "${!RESULT_PLATFORMS[@]}"; do
    [[ "${RESULT_PLATFORMS[$i]}" == "$want" ]] && found="${RESULT_STATUSES[$i]}"
  done
  echo "$found"
}

# record_key_subleg — emit a key-gated sub-leg (#2343) that fits the existing
# report shape so a keyless run is reported HONESTLY and never as a silent green.
#   $1 sub-leg name      (sketchfab | arcore-cloud) — both in the ADVISORY CSV
#   $2 parent leg name   (android | ar) — whose flow would exercise the path
#   $3 key-present flag  (true | false)
#   $4 path label        (e.g. "Sketchfab Explore path")
# Rules:
#   • key absent              → skipped, reason "key missing — <path> NOT tested"
#   • key present, parent pass → passed,  reason "<path> exercised by <parent>"
#   • key present, parent !pass→ skipped, reason "<parent> leg did not pass …"
# The sub-leg never reports `failed`: a real crash is owned by the parent leg;
# this leg only attests whether the key-gated path was actually exercised.
record_key_subleg() {
  local name="$1" parent="$2" present="$3" path="$4"
  local pstatus; pstatus="$(last_status_of "$parent")"
  if [[ "$present" != "true" ]]; then
    record "$name" skipped "key missing — ${path} NOT tested" "" 0
  elif [[ "$pstatus" == "passed" ]]; then
    record "$name" passed "key present — ${path} exercised by the ${parent} flow" "" 0
  else
    record "$name" skipped "${parent} leg did not pass (${pstatus:-not run}) — ${path} not exercised" "" 0
  fi
}

# device_has_connectivity — probe the leased emulator's radio state (#2959).
# setup-ar-emulator.sh's ensure_airplane_mode_disabled already repairs this
# right after boot; this is the backstop for an emulator that was leased
# already-running (never went through that repair) or where the repair
# itself failed. Echoes "true"/"false"; empty ANDROID_SERIAL or an adb error
# is treated as "unknown connectivity" — NOT as present, so a broken probe
# fails closed into an honest skip rather than a silent false-pass.
device_has_connectivity() {
  local serial="${1:-${ANDROID_SERIAL:-}}"
  [[ -n "$serial" ]] || { echo false; return; }
  local mode
  mode="$(adb -s "$serial" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r\n')"
  [[ "$mode" == "0" ]] && echo true || echo false
}

# record_streamed_subleg — like record_key_subleg, but for a sub-leg whose
# path is BOTH key-gated AND network-gated (#2959): a present key with the
# emulator stuck in airplane mode silently resolves every streamed slug to
# its bundled fallback (measured closing #2942 — see
# ensure_airplane_mode_disabled in setup-ar-emulator.sh). Reports which of
# the two gates was missing so a skip is actionable, not just advisory noise.
record_streamed_subleg() {
  local name="$1" parent="$2" key_present="$3" path="$4" serial="${5:-}"
  if [[ "$key_present" != "true" ]]; then
    record_key_subleg "$name" "$parent" "$key_present" "$path"
    return
  fi
  local net; net="$(device_has_connectivity "$serial")"
  if [[ "$net" != "true" ]]; then
    record "$name" skipped "key present but no connectivity on the emulator (airplane mode, or unresolved) — ${path} would silently resolve to its bundled fallback (#2959), NOT tested" "" 0
    return
  fi
  record_key_subleg "$name" "$parent" "$key_present" "$path"
}

# --- Pool emulator acquisition ---------------------------------------------
# acquire_pool_emulator — lease an emulator from the RAM-budgeted adaptive pool
# (#1654) for the android / ar legs. Strategy:
#   1. Lease a free already-running emulator if one exists.
#   2. Else delegate to setup-ar-emulator.sh, which leases or boots a new pool
#      member (RAM-gated, multi-port) and publishes its serial; re-lease it
#      here so the lease outlives that subprocess.
# Echoes the leased serial on stdout; returns 1 if no emulator could be obtained.
acquire_pool_emulator() {
  emu_pool_reclaim_stale adb
  local serial
  # Step 1: lease a free running emulator outright.
  if serial="$(emu_lease_free_serial adb)" && emu_lease_acquire "$serial" adb; then
    echo "$serial"
    return 0
  fi
  # Step 2: setup-ar-emulator.sh leases or boots a pool member, RAM-gated. It
  # prints `EMU_SERIAL=<serial>` as its last stdout line — capture that to learn
  # exactly which pool emulator it obtained (no race on a shared file). Its log
  # lines are surfaced to our stderr so the run stays visible.
  local setup_out setup_rc=0
  setup_out="$(bash "$SCRIPT_DIR/setup-ar-emulator.sh" 2>&1)" || setup_rc=$?
  printf '%s\n' "$setup_out" | grep -v '^EMU_SERIAL=' >&2 || true
  if [[ "$setup_rc" -ne 0 ]]; then
    return 1
  fi
  serial="$(printf '%s\n' "$setup_out" | sed -n 's/^EMU_SERIAL=//p' | tail -n1)"
  # Fallbacks: the published-serial file, then the first running emulator.
  if [[ -z "${serial:-}" ]] && [[ -f "$EMU_LEASE_DIR/last-booted.serial" ]]; then
    serial="$(cat "$EMU_LEASE_DIR/last-booted.serial" 2>/dev/null || true)"
  fi
  if [[ -z "${serial:-}" ]] || ! emu_serial_alive "$serial" adb; then
    serial="$(emu_running_serial adb || true)"
  fi
  [[ -n "${serial:-}" ]] || return 1
  # Adopt the lease under this orchestrator's pid. setup-ar-emulator.sh ran with
  # our exported EMU_LEASE_SESSION, so the sticky reservation it left behind is
  # ours to take (#2862 — before that, it dropped the lease on exit and the live
  # emulator looked free to every peer). Best-effort: even if a peer grabbed it,
  # the emulator is up and we still target it via ANDROID_SERIAL.
  emu_lease_acquire "$serial" adb || true
  echo "$serial"
  return 0
}

# --- Android leg -----------------------------------------------------------
run_android() {
  local started; started=$(date +%s)
  local serial=""
  log "=== Android leg ==="

  if ! command -v adb >/dev/null 2>&1; then
    record android skipped "adb not on PATH (no Android SDK)" "" 0
    return 0
  fi

  # Lease an emulator from the RAM-budgeted adaptive pool (#1654): a free
  # running one, or a freshly-booted pool member if live RAM has room, or wait
  # for a lease to free. All RAM-gating happens inside the pool helpers.
  if ! serial="$(acquire_pool_emulator)"; then
    record android skipped "could not lease/boot a pool emulator (RAM too tight or pool full)" "" "$(( $(date +%s) - started ))"
    return 0
  fi
  log "Android leg using pool emulator: $serial"
  # Pin every downstream adb / android-CLI / Maestro call to the leased serial.
  export ANDROID_SERIAL="$serial"

  if ! adb -s "$serial" get-state >/dev/null 2>&1; then
    record android skipped "leased emulator $serial not responding" "" "$(( $(date +%s) - started ))"
    return 0
  fi

  local flow="catalog"
  $FAST && flow="3d-basics"

  # #2343: when a key is present, drop any stale (possibly KEYLESS) demo APK —
  # e.g. the CI build-android-apk artifact, restored into this exact path — so
  # the keyed build below is never short-circuited and `record_key_subleg
  # sketchfab` stays truthful. qa-android-demos.sh re-checks this too, but doing
  # it here covers the install path explicitly (idempotent no-op if already
  # gone). See PR #2347 review.
  qa_keys_force_fresh_build_if_present "$DEMO_DEBUG_APK"

  local rc=0
  # Stream live via `tee` — a plain `> file` redirect kept the whole Android
  # leg silent in CI until the wrapper returned, so a slow APK build (or a
  # genuine hang) showed 40+ min of nothing before the job timed out and was
  # cancelled. `pipefail` (set above) makes `|| rc=$?` capture the wrapper's
  # exit code, not tee's. ANDROID_SERIAL (exported above) targets the leased
  # emulator throughout the wrapper.
  bash "$SCRIPT_DIR/qa-android-demos.sh" --install --flow "$flow" 2>&1 \
    | tee "$ARTIFACTS/android-output.txt" || rc=$?

  # Surface the host-side `adb emu screenrecord` capture (#1671) alongside the
  # iOS/web recordings so the autonomous QA runner finds all three in one place.
  local android_rec="$REPO_ROOT/tools/qa-screenshots/android/android-qa-${flow}.webm"
  if [[ -s "$android_rec" ]]; then
    cp "$android_rec" "$ARTIFACTS/" 2>/dev/null \
      && log "android recording: $ARTIFACTS/$(basename "$android_rec")"
  fi

  # Maestro has no flat summary JSON, so the wrapper's exit code carries the
  # verdict — but rc=0 alone is NOT proof of a pass, exactly as on the iOS leg
  # above. Measured on this host (bash 3.2.57): when a call that aborts sits in
  # a `||`-guarded list AND an EXIT trap is installed, the script dies with
  # exit 0 because the `||` already reset `$?` — and qa-android-demos.sh now
  # installs an EXIT trap (it releases its pool lease, #2862) and runs Maestro
  # as `maestro_run … || MAESTRO_RC=$?`. Preserving `$?` in the trap does NOT
  # help (it preserves the 0). Require the positive `[qa] PASS` marker, which
  # only the genuine success path prints.
  if [[ $rc -eq 0 ]] && grep -q '^\[qa\] PASS — ' "$ARTIFACTS/android-output.txt" 2>/dev/null; then
    record android passed "flow=$flow" "" "$(( $(date +%s) - started ))"
  elif [[ $rc -eq 0 ]]; then
    record android failed "qa-android-demos.sh exited 0 without its PASS marker — harness aborted mid-run (flow=$flow)" "" "$(( $(date +%s) - started ))"
  elif [[ $rc -eq 124 ]]; then
    record android timeout "per-flow budget expired in $(timed_out_flow "$ARTIFACTS/android-output.txt") — no demo failed (leg flow=$flow)" "" "$(( $(date +%s) - started ))"
  else
    record android failed "qa-android-demos.sh rc=$rc (flow=$flow)" "" "$(( $(date +%s) - started ))"
  fi
}

# --- iOS leg ---------------------------------------------------------------
run_ios() {
  local started; started=$(date +%s)
  log "=== iOS leg ==="

  if [[ "$(uname -s)" != "Darwin" ]]; then
    record ios skipped "iOS simulator only runs on macOS" "" 0
    return 0
  fi
  if ! command -v xcrun >/dev/null 2>&1; then
    record ios skipped "xcrun not found (Xcode command-line tools missing)" "" 0
    return 0
  fi

  local flow="catalog"
  $FAST && flow="3d-basics"

  local rc=0
  bash "$SCRIPT_DIR/ios-device-qa.sh" --install --flow "$flow" \
    > "$ARTIFACTS/ios-output.txt" 2>&1 || rc=$?
  cat "$ARTIFACTS/ios-output.txt"

  # Surface the `simctl io recordVideo` capture alongside the android/web
  # recordings so the autonomous QA runner finds all three in one place.
  local ios_rec="$REPO_ROOT/tools/qa-screenshots/ios/ios-qa-${flow}.mov"
  if [[ -s "$ios_rec" ]]; then
    cp "$ios_rec" "$ARTIFACTS/" 2>/dev/null \
      && log "ios recording: $ARTIFACTS/$(basename "$ios_rec")"
  fi

  # Surface the simulator os_log captured by ios-device-qa.sh's crash-gate
  # tail, and attach its artifact path to the iOS verdict — a red run's exact
  # log is inspectable straight from device-qa-report.json.
  local ios_log="$REPO_ROOT/tools/qa-screenshots/ios/ios-sim-${flow}.log"
  local ios_log_artifact=""
  if [[ -s "$ios_log" ]]; then
    if cp "$ios_log" "$ARTIFACTS/" 2>/dev/null; then
      ios_log_artifact="$ARTIFACTS/$(basename "$ios_log")"
      log "ios simulator log: $ios_log_artifact"
    fi
  fi

  # rc=0 alone is NOT proof of a pass: on macOS bash 3.2, an abort (e.g. a
  # `set -u` expansion error) inside a `||`-guarded call exits the child with
  # status 0 when its EXIT trap is set — measured on the 2026-07-21 nightly,
  # where the ios leg graded PASSED with zero Maestro steps run. Require the
  # positive `[ios-qa] PASS` marker, which only the genuine success path prints.
  if [[ $rc -eq 0 ]] && grep -q '^\[ios-qa\] PASS — ' "$ARTIFACTS/ios-output.txt" 2>/dev/null; then
    record ios passed "flow=$flow" "" "$(( $(date +%s) - started ))" "$ios_log_artifact"
  elif [[ $rc -eq 0 ]]; then
    record ios failed "ios-device-qa.sh exited 0 without its PASS marker — harness aborted mid-run (flow=$flow)" "" "$(( $(date +%s) - started ))" "$ios_log_artifact"
  elif [[ $rc -eq 124 ]]; then
    record ios timeout "per-flow budget expired in $(timed_out_flow "$ARTIFACTS/ios-output.txt") — no demo failed (leg flow=$flow)" "" "$(( $(date +%s) - started ))" "$ios_log_artifact"
  elif [[ $rc -eq 1 && ! $CI_MODE ]] && grep -q 'no available simulator' "$ARTIFACTS/ios-output.txt" 2>/dev/null; then
    record ios skipped "no iOS simulator available" "" "$(( $(date +%s) - started ))" "$ios_log_artifact"
  else
    record ios failed "ios-device-qa.sh rc=$rc (flow=$flow)" "" "$(( $(date +%s) - started ))" "$ios_log_artifact"
  fi
}

# --- Web leg ---------------------------------------------------------------
run_web() {
  local started; started=$(date +%s)
  log "=== Web leg ==="

  if ! command -v node >/dev/null 2>&1 || ! command -v npx >/dev/null 2>&1; then
    record web skipped "node/npx not on PATH" "" 0
    return 0
  fi

  local webdir="samples/web-demo"
  local summary="$webdir/test-results/web-qa-summary.json"
  rm -f "$summary"

  # Playwright + the chromium browser binary are installed on demand. The
  # webServer block in playwright.config.ts auto-starts http-server.
  # `iwer` (Immersive Web Emulation Runtime) is a best-effort WebXR shim
  # injected by `tests/webxr.spec.ts` via `page.addInitScript()` — same
  # caveat as the Android record/replay harness: it validates wire-level XR
  # API access, not real spatial tracking. The webxr spec now DRIVES a full
  # immersive-ar / immersive-vr session programmatically (request, XR frame
  # loop, pose/controller nudge, end) against IWER's programmable XRDevice —
  # no recorded fixture or real headset needed (#1674/#1748 item 4).
  log "ensuring Playwright + chromium are installed (samples/web-demo)"
  (
    cd "$webdir"
    [[ -f package.json ]] || npm init -y >/dev/null 2>&1 || true
    npm install --no-audit --no-fund --save-dev @playwright/test http-server iwer >/dev/null 2>&1
    npx playwright install chromium --with-deps >/dev/null 2>&1 \
      || npx playwright install chromium >/dev/null 2>&1
  ) || {
    record web skipped "could not install Playwright/chromium" "" "$(( $(date +%s) - started ))"
    return 0
  }

  # --fast: run only the lighter render smoke spec, not the full catalog.
  # NOTE: deliberately NOT passing --reporter — a CLI --reporter REPLACES the
  # whole config reporter list, which would drop the custom qa-summary-reporter
  # that emits web-qa-summary.json (the machine-readable verdict this runner
  # embeds into device-qa-report.json). Let playwright.config.ts drive.
  local spec_args=()
  $FAST && spec_args=(tests/render.spec.ts)

  local rc=0
  # ${arr[@]+…} idiom: bash 3.2 (macOS default) + `set -u` errors on expanding
  # an EMPTY array — which made the BLOCKING web leg false-FAIL in 25 s on any
  # Mac host (unbound variable, rc=1, zero tests run).
  ( cd "$webdir" && npx playwright test ${spec_args[@]+"${spec_args[@]}"} ) \
    > "$ARTIFACTS/web-output.txt" 2>&1 || rc=$?
  cat "$ARTIFACTS/web-output.txt"

  local kept=""
  if [[ -f "$summary" ]]; then
    kept="$ARTIFACTS/web-qa-summary.json"
    cp "$summary" "$kept"
  fi

  # Per-test page.screencast recordings (Playwright >= 1.59, issue #1748 item
  # 3). The `screencast` fixture in tests/helpers.ts writes one .webm per test
  # under `test-results/screencasts/`. Mirror them into $ARTIFACTS/ so the
  # autonomous QA runner surfaces them alongside the Maestro Android / iOS
  # videos — same convention as `record web` keeps `web-qa-summary.json`.
  local screencasts="$webdir/test-results/screencasts"
  if [[ -d "$screencasts" ]] && compgen -G "$screencasts/*.webm" >/dev/null; then
    mkdir -p "$ARTIFACTS/web-screencasts"
    cp "$screencasts"/*.webm "$ARTIFACTS/web-screencasts/" 2>/dev/null || true
    log "web screencasts: $(ls "$ARTIFACTS/web-screencasts" | wc -l | tr -d ' ') .webm under $ARTIFACTS/web-screencasts/"
  fi

  if [[ $rc -eq 0 ]]; then
    record web passed "${FAST:+fast }playwright" "$kept" "$(( $(date +%s) - started ))"
  else
    record web failed "playwright rc=$rc" "$kept" "$(( $(date +%s) - started ))"
  fi

  # ADVISORY perf-QA sub-leg (item 5 of #1748, scaffolded in #1879, thresholds
  # tuned + recorded in #1898). Runs Lighthouse against the web-demo and writes
  # web-perf-summary.json with an ENFORCED per-metric budget. The result is now
  # `record()`-ed as its own `web-perf` leg so a budget breach surfaces in
  # device-qa-report.json -> releaseGate.advisoryFailed (web-perf is in the
  # default ADVISORY CSV — a breach is a release WARN, not a hard block). The
  # call is still continue-on-error: web-perf-qa.sh exits 0 even on a `failed`
  # verdict (the breach is carried in the JSON), and a missing tool soft-skips.
  if [[ -x "$SCRIPT_DIR/web-perf-qa.sh" ]]; then
    local perf_started; perf_started=$(date +%s)
    log "running advisory web-perf-qa (Lighthouse, #1879/#1898)"
    bash "$SCRIPT_DIR/web-perf-qa.sh" --out "$webdir/test-results" \
      >> "$ARTIFACTS/web-output.txt" 2>&1 \
      || warn "web-perf-qa.sh exited non-zero — advisory, continuing"
    local perf_summary="$webdir/test-results/web-perf-summary.json"
    local perf_kept="" perf_status="skipped" perf_reason="web-perf-qa.sh produced no summary"
    if [[ -f "$perf_summary" ]]; then
      perf_kept="$ARTIFACTS/web-perf-summary.json"
      cp "$perf_summary" "$perf_kept"
      log "advisory perf summary: $perf_kept"
      # Read status + a human reason straight from the perf summary. python3 is
      # already a hard dependency of web-perf-qa.sh, so it is safe to use here.
      perf_status="$(python3 -c "import json;d=json.load(open('$perf_summary'));print(d.get('status','skipped'))" 2>/dev/null || echo skipped)"
      perf_reason="$(python3 -c "
import json
d=json.load(open('$perf_summary'))
br=d.get('breaches') or []
print('; '.join(br) if br else (d.get('reason') or 'all metrics within budget'))
" 2>/dev/null || echo 'could not read web-perf-summary.json')"
    fi
    record web-perf "$perf_status" "$perf_reason" "$perf_kept" "$(( $(date +%s) - perf_started ))"
  fi
}

# --- AR leg ----------------------------------------------------------------
run_ar() {
  local started; started=$(date +%s)
  local serial=""
  log "=== AR leg ==="

  if ! command -v adb >/dev/null 2>&1; then
    record ar skipped "adb not on PATH (no Android SDK)" "" 0
    return 0
  fi

  # The AR replay harness needs an ARCore-capable emulator. The android leg
  # usually ran first in this same process and still holds a pool lease — reuse
  # that emulator directly (no extra lease, no boot). Otherwise lease one from
  # the RAM-budgeted pool (#1654): a free running emulator, or a fresh pool
  # member if RAM has room. setup-ar-emulator.sh also sideloads ARCore.
  if [[ -n "${ANDROID_SERIAL:-}" ]] && emu_serial_alive "$ANDROID_SERIAL" adb; then
    serial="$ANDROID_SERIAL"
    log "AR leg reusing the Android leg's pool emulator: $serial"
  elif ! serial="$(acquire_pool_emulator)"; then
    record ar skipped "could not lease/boot an ARCore pool emulator (RAM too tight or pool full)" "" "$(( $(date +%s) - started ))"
    return 0
  fi
  log "AR leg using pool emulator: $serial"
  # Pin every downstream adb / android-CLI call to the leased serial.
  export ANDROID_SERIAL="$serial"

  if ! adb -s "$serial" get-state >/dev/null 2>&1; then
    record ar skipped "leased emulator $serial not responding" "" "$(( $(date +%s) - started ))"
    return 0
  fi

  # #2343: ar-replay-qa.sh installs via Gradle `installDebug`, which trusts
  # UP-TO-DATE. The keys reach the build through System.getenv() — NOT a tracked
  # Gradle input — so a keyless APK assembled earlier (the android leg, a manual
  # build, or the CI build-android-apk artifact restored into this path) would be
  # reused even with ARCORE_API_KEY now in the env, leaving AR Cloud at
  # ERROR_NOT_AUTHORIZED while `record_key_subleg arcore-cloud` reports `passed`.
  # Delete the APK so Gradle re-assembles and re-reads the env. (DO NOT remove —
  # see PR #2347 review.)
  qa_keys_force_fresh_build_if_present "$DEMO_DEBUG_APK"

  local rc=0
  bash "$SCRIPT_DIR/ar-replay-qa.sh" --out "$ARTIFACTS" \
    > "$ARTIFACTS/ar-output.txt" 2>&1 || rc=$?
  cat "$ARTIFACTS/ar-output.txt"

  local summary="$ARTIFACTS/ar-qa-summary.json"
  local kept=""
  [[ -f "$summary" ]] && kept="$summary"

  case $rc in
    # rc=0 alone is NOT proof of a pass, for the same measured reason the
    # android and iOS legs above require a positive marker: on bash 3.2.57 a
    # script that aborts inside a `||`-guarded list with an EXIT trap installed
    # exits 0 — and ar-replay-qa.sh installs one to release its pool lease.
    # Grading `ar` on rc alone graded an ABSENCE; require a marker only a real
    # terminal path prints, and keep the two green paths distinguishable so the
    # report never implies demos ran when none did (#2921).
    0)
      if grep -q '^\[ar-replay-qa\] PASS — ' "$ARTIFACTS/ar-output.txt" 2>/dev/null; then
        record ar passed "ar-replay-qa.sh" "$kept" "$(( $(date +%s) - started ))"
      elif grep -q '^\[ar-replay-qa\] GREEN-NO-OP — ' "$ARTIFACTS/ar-output.txt" 2>/dev/null; then
        record ar passed "nothing to replay — bundled recording absent (#1565); no AR demo was exercised" "$kept" "$(( $(date +%s) - started ))"
      else
        record ar failed "ar-replay-qa.sh exited 0 without a positive marker — harness aborted mid-run" "$kept" "$(( $(date +%s) - started ))"
      fi
      ;;
    2) record ar skipped "no device for ar-replay-qa.sh" "$kept" "$(( $(date +%s) - started ))" ;;
    # rc=3: no demo crashed, but one or more demos were not validated —
    # `ar-record-playback` replayed 0 frames (ARCore dataset playback unsupported
    # on this emulator, #1645) and/or a shard was severed under emulator pressure
    # so its demos are recorded `skipped` (environmental, #2643). Per-demo reasons
    # are in ar-qa-summary.json. The AR leg is `skipped`, never a pass.
    3) record ar skipped "AR demos not fully validated — see ar-qa-summary.json (playback 0 frames #1645 / shard severed #2643)" "$kept" "$(( $(date +%s) - started ))" ;;
    *) record ar failed "ar-replay-qa.sh rc=$rc" "$kept" "$(( $(date +%s) - started ))" ;;
  esac
}

# --- Dispatch --------------------------------------------------------------
RUN_STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "starting device-QA — platform=$PLATFORM fast=$FAST ci=$CI_MODE"
log "free disk at start: $(disk_free_gb) GB"

# Resolve + EXPORT the demo API keys ONCE (#2343) so the android leg's
# assembleDebug build is built WITH them (Explore/Sketchfab + AR Cloud), and so
# the key-gated sub-legs below report honestly. Presence only — never the value.
# QA_KEYS_RESOLVED_BY_PARENT tells the child qa-android-demos.sh not to re-print
# the banner this orchestrator is about to print.
qa_keys_resolve_all
export QA_KEYS_RESOLVED_BY_PARENT=1
log "API keys — Sketchfab present: ${QA_SKETCHFAB_KEY_PRESENT}, ARCore present: ${QA_ARCORE_KEY_PRESENT}"
qa_keys_banner_if_absent

# Order: cheapest/least-stateful first (web), then the Android-emulator legs
# back-to-back (they can share one booted emulator), then iOS last.
LEGS=()
case "$PLATFORM" in
  all) LEGS=(web android ar ios) ;;
  *)   LEGS=("$PLATFORM") ;;
esac

# The --ci disk gate tripped for an advisory-only selection (see the disk gate
# near the top): record every selected leg as an honest `skipped` and run
# nothing. The aggregation below turns an all-advisory `skipped` set into
# WARN / exit 0 (#1645/#1670) with a truthful report — no demo ran, so no crash
# is masked, and the job stays green instead of a misleading exit-2 failure.
if $DISK_GATE_SKIP; then
  for leg in "${LEGS[@]}"; do
    record "$leg" skipped "disk gate: free disk below ${DISK_MIN_GB} GB on the CI runner — advisory leg not run (#1645)" "" 0
  done
  LEGS=()
fi

# ${LEGS[@]+…}: macOS ships bash 3.2, where expanding an EMPTY array under
# `set -u` is an "unbound variable" error (bash ≥4.4 allows it) — the
# self-hosted-Mac ios leg crashed here whenever the disk gate emptied LEGS,
# turning the honest-skip design above into a bogus exit 1.
for leg in ${LEGS[@]+"${LEGS[@]}"}; do
  case "$leg" in
    web)     run_web ;;
    android)
      run_android
      # Key- AND connectivity-gated sub-leg (#2343, #2959): whether the
      # Explore/Sketchfab path was actually exercised. `skipped` (advisory)
      # when SKETCHFAB_API_KEY is absent OR the emulator has no connectivity
      # (airplane mode silently swaps every streamed slug to its bundled
      # fallback — never counted as part of the android pass either way.
      record_streamed_subleg sketchfab android "${QA_SKETCHFAB_KEY_PRESENT:-false}" "Sketchfab Explore path"
      ;;
    ar)
      run_ar
      # Key- AND connectivity-gated sub-leg (#2343, #2959): whether the AR
      # Cloud demos (Cloud Anchors / Geospatial / Streetscape) were actually
      # exercised. `skipped` (advisory) when ARCORE_API_KEY is absent or the
      # emulator has no connectivity — never counted as part of the ar pass.
      record_streamed_subleg arcore-cloud ar "${QA_ARCORE_KEY_PRESENT:-false}" "ARCore Cloud path"
      ;;
    ios)     run_ios ;;
  esac
  # Reclaim build output before the next leg so a full pass stays disk-safe.
  # AR runs right after Android and reuses the same APK build, so don't wipe
  # the Android build dir between those two adjacent legs.
  if [[ "$leg" != "android" ]]; then
    clean_build_output "$leg"
  fi
  log "free disk after $leg leg: $(disk_free_gb) GB"
done

# --- Aggregate the report --------------------------------------------------
# A leg's weight in the verdict depends on whether it is ADVISORY (#1651/#1652).
# An advisory leg (default: android, ar) that `failed` or `skipped` is only a
# WARN — never a hard block. A REQUIRED leg (e.g. web) that did not `pass`
# blocks the release gate. This split is what keeps an honest #1645 `skipped`
# on the advisory `ar` leg from false-failing the gate (#1670).
PASSED=0; FAILED=0; SKIPPED=0
TIMED_OUT=0          # subset of FAILED whose cause was the clock (#3141)
REQUIRED_FAILED=0    # failed legs that are NOT advisory  -> hard block
REQUIRED_SKIPPED=0   # skipped legs that are NOT advisory -> block under --ci
ADVISORY_FAILED=0    # failed/skipped advisory legs       -> WARN, never a block
ADVISORY_NONPASS=0   # advisory legs that did not pass    -> WARN
for i in "${!RESULT_STATUSES[@]}"; do
  s="${RESULT_STATUSES[$i]}"
  advisory=false
  is_advisory "${RESULT_PLATFORMS[$i]}" && advisory=true
  case "$s" in
    passed)  PASSED=$((PASSED + 1)) ;;
    # `timeout` carries the SAME weight as `failed` (#3141). It is graded here
    # explicitly, and not left to fall through: this `case` has no default arm,
    # so an unhandled status increments no counter at all and the run grades
    # `passed` with a red leg in it — measured, and the exact false-green this
    # repo pays the most for. Any status added later needs an arm here too.
    failed|timeout)
      FAILED=$((FAILED + 1))
      if [[ "$s" == "timeout" ]]; then
        TIMED_OUT=$((TIMED_OUT + 1))
      fi
      if $advisory; then
        ADVISORY_FAILED=$((ADVISORY_FAILED + 1))
        ADVISORY_NONPASS=$((ADVISORY_NONPASS + 1))
      else
        REQUIRED_FAILED=$((REQUIRED_FAILED + 1))
      fi
      ;;
    skipped)
      SKIPPED=$((SKIPPED + 1))
      if $advisory; then
        ADVISORY_NONPASS=$((ADVISORY_NONPASS + 1))
      else
        REQUIRED_SKIPPED=$((REQUIRED_SKIPPED + 1))
      fi
      ;;
  esac
done

# Overall verdict.
#   failed  -> exit 1: a REQUIRED leg failed, OR (under --ci) a REQUIRED leg
#              was skipped. A real crash on ANY leg also counts as failed
#              only when that leg is required; an advisory crash is a WARN.
#   warn    -> exit 0: every required leg passed, but an advisory leg did not
#              pass (failed or skipped) — surfaced loudly, never blocking.
#   passed  -> exit 0: every selected leg passed.
# An all-skipped run made entirely of advisory legs lands in `warn` (exit 0),
# never `failed` — the #1670 fix.
OVERALL="passed"
if [[ $REQUIRED_FAILED -gt 0 ]]; then
  OVERALL="failed"
elif $CI_MODE && [[ $REQUIRED_SKIPPED -gt 0 ]]; then
  # A required leg could not run in CI — that is a gate failure.
  OVERALL="failed"
elif [[ $ADVISORY_NONPASS -gt 0 ]]; then
  # Only advisory legs are non-passing -> WARN, exit 0 (#1670).
  OVERALL="warn"
elif [[ $REQUIRED_SKIPPED -gt 0 ]]; then
  # Outside --ci, a skipped required leg is a soft (partial) pass.
  OVERALL="passed"
fi

# Emit device-qa-report.json. Built with python3 so per-platform summary
# files are embedded verbatim (web-qa-summary.json, ar-qa-summary.json).
# The per-platform records are exported as DQ_* environment variables — far
# more robust across shells than argv quoting for arbitrary reason strings.
export DQ_N="${#RESULT_PLATFORMS[@]}"
export DQ_ADVISORY="$ADVISORY"
for i in "${!RESULT_PLATFORMS[@]}"; do
  export "DQ_PLATFORM_$i=${RESULT_PLATFORMS[$i]}"
  export "DQ_STATUS_$i=${RESULT_STATUSES[$i]}"
  export "DQ_REASON_$i=${RESULT_REASONS[$i]}"
  export "DQ_SUMMARY_$i=${RESULT_SUMMARIES[$i]}"
  export "DQ_DURATION_$i=${RESULT_DURATIONS[$i]}"
  export "DQ_LOG_$i=${RESULT_LOGS[$i]}"
  if is_advisory "${RESULT_PLATFORMS[$i]}"; then
    export "DQ_ADVISORY_$i=true"
  else
    export "DQ_ADVISORY_$i=false"
  fi
done

python3 - "$REPORT" "$RUN_STARTED" "$PLATFORM" "$FAST" "$CI_MODE" "$OVERALL" \
          "$PASSED" "$FAILED" "$SKIPPED" "$TIMED_OUT" <<'PYEOF'
import json, sys, os

(report_path, started, platform, fast, ci, overall,
 passed, failed, skipped, timed_out) = sys.argv[1:11]

n = int(os.environ["DQ_N"])
advisory_csv = os.environ.get("DQ_ADVISORY", "")
advisory_set = {p for p in advisory_csv.split(",") if p}

platforms = []
for i in range(n):
    summary_path = os.environ.get(f"DQ_SUMMARY_{i}", "")
    embedded = None
    if summary_path and os.path.isfile(summary_path):
        try:
            with open(summary_path) as fh:
                embedded = json.load(fh)
        except Exception:
            embedded = None
    platforms.append({
        "platform": os.environ[f"DQ_PLATFORM_{i}"],
        "status":   os.environ[f"DQ_STATUS_{i}"],
        # advisory=true → a failure on this leg is a release-gate WARN, not a
        # hard block (#1651). Mirrors `continue-on-error` in device-qa.yml.
        "advisory": os.environ.get(f"DQ_ADVISORY_{i}", "false") == "true",
        "reason":   os.environ.get(f"DQ_REASON_{i}", ""),
        "durationSec": int(os.environ.get(f"DQ_DURATION_{i}", "0") or 0),
        "summary":  embedded,
        # Path to the captured device/simulator log for this leg (currently
        # the iOS crash-gate os_log tail), or null if the leg keeps none.
        "log":      os.environ.get(f"DQ_LOG_{i}", "") or None,
    })

# --- Release-gate verdict (#1651 / #1670) ----------------------------------
# The aggregated `status` above answers "did every leg pass". The release gate
# needs a finer signal: a non-passing ADVISORY leg (android/ar) is only a WARN,
# while a non-passing BLOCKING leg (web) hard-blocks. Pre-compute that split so
# release-checklist.sh section 14 reads an explicit field instead of
# re-deriving the policy.
#
# A `skipped` leg counts as "did not pass" too — an honest #1645 skip on the
# advisory `ar` leg must surface as `warn`, never `blocked` (#1670). A skipped
# REQUIRED leg blocks only under --ci (where every required leg is expected to
# run); outside --ci a skipped required leg is a soft partial pass.
def _nonpass(p):
    return p["status"] != "passed"

# `timeout` blocks exactly like `failed` (#3141) — the distinction is in the
# WORD and the reason string, never in the weight. Anything else would turn a
# finer report into a laxer gate.
FAILED_STATUSES = ("failed", "timeout")

blocking_failed = [p["platform"] for p in platforms
                   if p["status"] in FAILED_STATUSES and not p["advisory"]]
blocking_skipped = [p["platform"] for p in platforms
                    if p["status"] == "skipped" and not p["advisory"]]
advisory_failed = [p["platform"] for p in platforms
                   if _nonpass(p) and p["advisory"]]
ci_mode = ci == "true"
if blocking_failed or (ci_mode and blocking_skipped):
    gate = "blocked"
elif advisory_failed:
    gate = "warn"
else:
    gate = "clear"

report = {
    "harness": "device-qa",
    "schemaVersion": 2,
    "startedAt": started,
    "platformSelection": platform,
    "fast": fast == "true",
    "ci": ci == "true",
    "status": overall,
    "advisoryPlatforms": sorted(advisory_set),
    # releaseGate: "clear" (tag freely) | "warn" (advisory leg red — human
    # should see it before tagging) | "blocked" (a blocking leg is red).
    "releaseGate": {
        "verdict": gate,
        "blockingFailed": blocking_failed,
        # Advisory legs that did not pass (failed OR skipped) — surfaced as a
        # WARN, never a block (#1670). Kept under the legacy key for the
        # release-checklist consumer; an honest skip belongs here just like a
        # crash, because neither blocks the gate.
        "advisoryFailed": advisory_failed,
    },
    "totals": {
        "passed": int(passed),
        "failed": int(failed),
        "skipped": int(skipped),
        # A SUBSET of `failed`, not a fourth bucket: legs whose red came from
        # an expired per-flow budget rather than a demo (#3141). Existing
        # consumers that read `failed` keep the number they always read.
        "timedOut": int(timed_out),
    },
    "platforms": platforms,
}
with open(report_path, "w") as fh:
    json.dump(report, fh, indent=2)
    fh.write("\n")
PYEOF

# --- Human-readable summary ------------------------------------------------
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  SceneView device-QA — aggregated report"
echo "═══════════════════════════════════════════════════════"
echo "  selection : $PLATFORM   fast=$FAST   ci=$CI_MODE"
echo "  report    : $REPORT"
echo "───────────────────────────────────────────────────────"
for i in "${!RESULT_PLATFORMS[@]}"; do
  tag=""
  if is_advisory "${RESULT_PLATFORMS[$i]}"; then
    tag="[advisory]"
    # A non-passing advisory leg (failed OR skipped) is a release-gate WARN,
    # not a block — flag it so it is never silent (#1651 / #1670).
    case "${RESULT_STATUSES[$i]}" in
      failed|timeout|skipped) tag="[advisory — WARN, not a release blocker]" ;;
    esac
  fi
  printf "  %-9s %-8s %4ss  %s %s\n" \
    "${RESULT_PLATFORMS[$i]}" \
    "${RESULT_STATUSES[$i]}" \
    "${RESULT_DURATIONS[$i]}" \
    "$tag" \
    "${RESULT_REASONS[$i]}"
done
echo "───────────────────────────────────────────────────────"
echo "  passed=$PASSED  failed=$FAILED (of which timeout=$TIMED_OUT)  skipped=$SKIPPED  ->  $OVERALL"
echo "  advisory legs: ${ADVISORY:-(none)}  (a red advisory leg is a release WARN, not a block — #1651)"
echo "═══════════════════════════════════════════════════════"

if [[ "$OVERALL" == "passed" ]]; then
  [[ $SKIPPED -gt 0 ]] && warn "$SKIPPED platform(s) skipped — pass is partial (not a --ci pass)."
  log "device-QA PASSED."
  exit 0
elif [[ "$OVERALL" == "warn" ]]; then
  # Every REQUIRED leg passed; only advisory legs are non-passing (failed or
  # an honest #1645 skip). This is a WARN, not a release blocker (#1670) —
  # exit 0 so the release gate is not falsely hard-blocked.
  warn "device-QA WARN — $ADVISORY_NONPASS advisory leg(s) did not pass."
  warn "advisory legs are non-blocking (#1651/#1670); a human should still review the report."
  log "device-QA passed-with-warnings (advisory legs only) — release checkpoint may tag."
  exit 0
else
  echo "[device-qa] device-QA FAILED — release checkpoint must NOT tag." >&2
  exit 1
fi
