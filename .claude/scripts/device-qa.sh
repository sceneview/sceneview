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
#                    (#1651). Default: `android,ar,web-perf,sketchfab,arcore-cloud`.
#                    `android,ar` run on the chronically flaky SwiftShader
#                    emulator (#1643) and are `continue-on-error: true` in
#                    device-qa.yml, so the release gate must not be hard-blocked
#                    by them. `web-perf` is the Lighthouse perf sub-leg of `web`
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
#   The runner calls disk-gated-spawn-check.sh before starting and cleans the
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

# Release every emulator lease this orchestrator owns when it exits.
trap 'emu_lease_release_all' EXIT

# --- Flags -----------------------------------------------------------------
PLATFORM="all"
FAST=false
CI_MODE=false
OUT_DIR="$REPO_ROOT"
# Advisory legs (#1651): a failure here is a release-gate WARN, not a block.
# `android,ar` ride the flaky SwiftShader emulator and are continue-on-error
# in device-qa.yml; `web` is reliable and stays BLOCKING.
# `web-perf` (#1898) is the Lighthouse perf sub-leg — advisory at first, until
# its budgets are proven stable against real baseline data. Promote it out of
# this CSV to make a budget breach a hard release block.
# `sketchfab` + `arcore-cloud` (#2343) are the key-gated sub-legs: when their
# API key is absent the path is reported `skipped` (advisory) — a missing LOCAL
# key must surface as a WARN, never hard-block a dev's run.
ADVISORY="android,ar,web-perf,sketchfab,arcore-cloud"
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
if ! bash "$SCRIPT_DIR/disk-gated-spawn-check.sh" --quiet >/dev/null 2>&1; then
  warn "free disk is below the safe threshold — run cleanup before a full pass:"
  warn "  bash .claude/scripts/gradle-cache-cleanup.sh"
  warn "  bash .claude/scripts/worktree-auto-prune.sh --yes --keep \"\$(git rev-parse --show-toplevel)\""
  # Hard-stop in CI; on a dev box a single light leg (web) may still be fine,
  # so warn and let the caller's --platform choice proceed.
  if $CI_MODE; then
    echo "[device-qa] CI mode + low disk — aborting before any platform ran." >&2
    exit 2
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
RESULT_STATUSES=()   # passed | failed | skipped
RESULT_REASONS=()
RESULT_SUMMARIES=()  # path to a platform JSON summary, or "" if none
RESULT_DURATIONS=()

record() {
  RESULT_PLATFORMS+=("$1")
  RESULT_STATUSES+=("$2")
  RESULT_REASONS+=("$3")
  RESULT_SUMMARIES+=("$4")
  RESULT_DURATIONS+=("$5")
  log "$1 -> $2 ${3:+($3)}"
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
  if serial="$(emu_lease_free_serial adb)" && emu_lease_acquire "$serial"; then
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
  # Re-lease under this orchestrator's pid (setup-ar-emulator.sh dropped its own
  # lease in its EXIT trap). Best-effort: even if a peer grabbed the lease, the
  # emulator is up and we still target it via ANDROID_SERIAL.
  emu_lease_acquire "$serial" || true
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

  # Maestro has no flat summary JSON; the wrapper's exit code is the verdict.
  if [[ $rc -eq 0 ]]; then
    record android passed "flow=$flow" "" "$(( $(date +%s) - started ))"
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

  if [[ $rc -eq 0 ]]; then
    record ios passed "flow=$flow" "" "$(( $(date +%s) - started ))"
  elif [[ $rc -eq 1 && ! $CI_MODE ]] && grep -q 'no available simulator' "$ARTIFACTS/ios-output.txt" 2>/dev/null; then
    record ios skipped "no iOS simulator available" "" "$(( $(date +%s) - started ))"
  else
    record ios failed "ios-device-qa.sh rc=$rc (flow=$flow)" "" "$(( $(date +%s) - started ))"
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
  ( cd "$webdir" && npx playwright test "${spec_args[@]}" ) \
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
    0) record ar passed "ar-replay-qa.sh" "$kept" "$(( $(date +%s) - started ))" ;;
    2) record ar skipped "no device for ar-replay-qa.sh" "$kept" "$(( $(date +%s) - started ))" ;;
    # rc=3 (#1645): no demo crashed, but `ar-record-playback` replayed 0 frames
    # — ARCore dataset playback is unsupported on this emulator. The recorded
    # session was not exercised, so the AR leg is `skipped`, never a pass.
    3) record ar skipped "ARCore dataset playback unsupported on emulator (ar-record-playback replayed 0 frames)" "$kept" "$(( $(date +%s) - started ))" ;;
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

for leg in "${LEGS[@]}"; do
  case "$leg" in
    web)     run_web ;;
    android)
      run_android
      # Key-gated sub-leg (#2343): whether the Explore/Sketchfab path was
      # actually exercised. `skipped` (advisory) when SKETCHFAB_API_KEY is
      # absent — never counted as part of the android pass.
      record_key_subleg sketchfab android "${QA_SKETCHFAB_KEY_PRESENT:-false}" "Sketchfab Explore path"
      ;;
    ar)
      run_ar
      # Key-gated sub-leg (#2343): whether the AR Cloud demos (Cloud Anchors /
      # Geospatial / Streetscape) were actually exercised. `skipped` (advisory)
      # when ARCORE_API_KEY is absent — never counted as part of the ar pass.
      record_key_subleg arcore-cloud ar "${QA_ARCORE_KEY_PRESENT:-false}" "ARCore Cloud path"
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
    failed)
      FAILED=$((FAILED + 1))
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
  if is_advisory "${RESULT_PLATFORMS[$i]}"; then
    export "DQ_ADVISORY_$i=true"
  else
    export "DQ_ADVISORY_$i=false"
  fi
done

python3 - "$REPORT" "$RUN_STARTED" "$PLATFORM" "$FAST" "$CI_MODE" "$OVERALL" \
          "$PASSED" "$FAILED" "$SKIPPED" <<'PYEOF'
import json, sys, os

(report_path, started, platform, fast, ci, overall,
 passed, failed, skipped) = sys.argv[1:10]

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

blocking_failed = [p["platform"] for p in platforms
                   if p["status"] == "failed" and not p["advisory"]]
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
      failed|skipped) tag="[advisory — WARN, not a release blocker]" ;;
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
echo "  passed=$PASSED  failed=$FAILED  skipped=$SKIPPED  ->  $OVERALL"
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
