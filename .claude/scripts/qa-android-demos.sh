#!/usr/bin/env bash
# qa-android-demos.sh — Android demo device-QA, now a thin Maestro wrapper.
#
# As of slice #1562 (umbrella #1560) the slow tap-and-wait / screenshot-by-
# screenshot logic that used to live here has been replaced by Maestro YAML
# flows under `.maestro/android/`. Maestro drives every one of the 42 demos
# like a real user (deep-link launch, camera-orbit drag, viewport tap, ONE
# screenshot per demo, navigate back) and asserts each demo's Activity stays
# alive. See `.maestro/README.md`.
#
# This script just orchestrates the pieces around `maestro test`:
#   1. (optional) build + install the demo APK
#   2. (best-effort) screen-record the run via host-side `adb emu screenrecord`
#      — parity with the iOS / web QA legs; skipped silently on a physical
#      device or when the emulator console is unavailable (#1671)
#   3. run the Maestro catalog (or a single category subflow)
#
# Usage:
#   bash .claude/scripts/qa-android-demos.sh [--install] [--flow <name>]
#
# Options:
#   --install        Build :samples:android-demo:assembleDebug and install it.
#   --flow <name>    Run a single category flow instead of the full catalog,
#                    e.g. `--flow lighting` → .maestro/android/lighting.yaml.
#                    Defaults to `catalog` (all 42 demos).
#   -h | --help      Show this help.
#
# Pool-aware (#1654): with the RAM-budgeted adaptive emulator pool, several
# emulators may be running. Set ANDROID_SERIAL to pin the QA run to a specific
# leased emulator — device-qa.sh does this for its android leg. When unset, the
# first running emulator is used and ANDROID_SERIAL is exported to pin it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# This script lives in `.claude/scripts/`, so the repo root is two levels up.
# Resolving it from BASH_SOURCE (not the caller's CWD) keeps every path below
# — `.maestro/...`, `./gradlew`, the APK — correct no matter where this script
# is invoked from (e.g. device-qa.sh runs it with a non-repo-root CWD, #1585).
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/maestro.sh
source "$SCRIPT_DIR/lib/maestro.sh"
# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"
# RAM-budgeted adaptive emulator pool helpers (#1647 → #1654) — used to detect a
# leasable running emulator and to target the leased serial via ANDROID_SERIAL.
# shellcheck source=lib/emulator-select.sh
source "$SCRIPT_DIR/lib/emulator-select.sh"
# API-key resolution (#2343) — resolve + EXPORT SKETCHFAB_API_KEY / ARCORE_API_KEY
# (env, else repo-root local.properties) so the assembleDebug build below injects
# them via samples/android-demo/build.gradle, instead of silently building with
# empty keys (Explore disabled, AR Cloud → ERROR_NOT_AUTHORIZED). Presence only —
# never logs a key value.
# shellcheck source=lib/qa-keys.sh
source "$SCRIPT_DIR/lib/qa-keys.sh"

PACKAGE="io.github.sceneview.demo"
ACTIVITY=".MainActivity"
APK="samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk"

INSTALL=false
FLOW="catalog"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) INSTALL=true; shift ;;
    --flow)    FLOW="${2:?--flow needs a name}"; shift 2 ;;
    -h|--help)
      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "[qa] unknown argument: $1" >&2; exit 2 ;;
  esac
done

FLOW_FILE=".maestro/android/${FLOW}.yaml"
if [[ ! -f "$FLOW_FILE" ]]; then
  echo "[qa] no such flow: $FLOW_FILE" >&2
  echo "[qa] available: $(cd .maestro/android && ls *.yaml | sed 's/\.yaml//' | tr '\n' ' ')" >&2
  exit 2
fi

# --- Emulator check --------------------------------------------------------
# Adaptive emulator pool (#1647 → #1654). This script never boots its own
# emulator — leasing/booting a pool member (RAM-gated, multi-port) is delegated
# to setup-ar-emulator.sh / the device-qa.sh orchestrator.
#   - If ANDROID_SERIAL is set (device-qa.sh leased a pool emulator for us),
#     target THAT serial — every adb / android-CLI / Maestro call below honours
#     ANDROID_SERIAL, so the QA run stays pinned to the leased emulator even
#     when several emulators are up in the pool.
#   - Otherwise pick a single running emulator (standalone invocation).
#   - Either way, never drive an emulator this session is not entitled to
#     (#2862): honour pool reservations and the pool AVD identity.
#     Local pool only: on CI the emulator is the runner's own `test` AVD (see
#     the same guard in device-qa.sh), so the check is skipped there.
if [[ -z "${EMU_REQUIRE_AVD:-}" && "${GITHUB_ACTIONS:-}" != "true" ]]; then
  EMU_REQUIRE_AVD="$EMU_POOL_AVD"
fi
export EMU_REQUIRE_AVD
# Adopt the token published by a `setup-ar-emulator.sh` run that just
# provisioned an emulator for this session (no-op when we already have one, or
# when nothing was published) — otherwise our own provisioning would look like
# a peer's reservation to us.
[[ -n "${EMU_LEASE_SESSION:-}" ]] || emu_lease_session_inherit >/dev/null 2>&1 || true

# Release any lease THIS run takes below, on exit, however it ends (#2862
# follow-up). Selecting a free serial is NOT enough: without holding it, a
# second standalone run would pick the same un-leased emulator and both would drive it
# — the exact concurrent-input collision #2862 set out to kill. The trap is a
# no-op when device-qa.sh owns the lease under its own pid (it exported
# ANDROID_SERIAL and we take the branch just below, acquiring nothing), and it
# KEEPS a sticky session reservation (emu_lease_release_all skips sticky) — it
# only drops the plain pid lease a standalone run takes on an un-leased pool
# emulator.
#
# ⚠️ Installing an EXIT trap here has a cost, measured on this host (bash
# 3.2.57): once a trap is set, a script that aborts inside a `||`-guarded list
# dies with status 0 (the `||` already reset `$?`) — the false-green documented
# in lib/maestro.sh. This script had NO EXIT trap before, and it runs Maestro as
# `maestro_run … || MAESTRO_RC=$?` below. Preserving `$?` in the trap does not
# fix it (it preserves the 0), so the defence is the positive `[qa] PASS`
# marker this script prints on the genuine success path — device-qa.sh's
# run_android now requires that marker, exactly as run_ios does. Do not grade
# this script on its exit code alone.
trap 'emu_lease_release_all || true' EXIT

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! emu_serial_alive "$ANDROID_SERIAL" adb; then
    echo "[qa] ERROR: ANDROID_SERIAL=$ANDROID_SERIAL is not a running emulator." >&2
    exit 1
  fi
  # A pre-set ANDROID_SERIAL used to be trusted outright as "my caller holds the
  # lease" — and setup-ar-emulator.sh's own printed next-steps tell a human to
  # export exactly that, so the hold-the-lease fix never ran on the documented
  # happy path (#2921). Verify instead of assuming. emu_lease_ensure is a strict
  # no-op when the lease is already ours by pid or session token (device-qa.sh's
  # child, or our own sticky reservation); it only acts when the emulator is
  # unleased (take it) or held by a live peer (refuse). Calling emu_lease_acquire
  # here instead would be a REGRESSION: it rewrites the owner to our pid and our
  # EXIT trap would then release a lease our parent still depends on.
  if ! emu_lease_ensure "$ANDROID_SERIAL" adb; then
    echo "[qa] ERROR: ANDROID_SERIAL=$ANDROID_SERIAL is reserved by another session." >&2
    echo "[qa]        Inherit it:  export EMU_LEASE_SESSION=<its token>" >&2
    echo "[qa]        Or provision your own:  bash .claude/scripts/setup-ar-emulator.sh" >&2
    exit 1
  fi
  echo "[qa] targeting leased pool emulator: $ANDROID_SERIAL"
elif reuse_serial="$(emu_lease_free_serial adb)"; then
  # Standalone invocation. Take a LEASABLE emulator, not merely a running one
  # (#2862): a peer session's reservation — or a stray non-pool AVD sitting on a
  # pool port — must never be driven here.
  # HOLD it for the whole run: emu_lease_free_serial only reports that the serial
  # is takeable right now; a racing peer can lease it in the same instant. Acquire
  # it (pins an un-leased emulator under our pid, or adopts our session's sticky
  # reservation), and refuse to drive it if we lost that race — piloting an
  # emulator we could not reserve is precisely the collision this fixes.
  if ! emu_lease_acquire "$reuse_serial" adb; then
    echo "[qa] ERROR: lost the race to lease $reuse_serial — a peer session took it first." >&2
    echo "[qa]        Provision your own:  bash .claude/scripts/setup-ar-emulator.sh" >&2
    exit 1
  fi
  echo "[qa] using running emulator: $reuse_serial (leased)"
  # Pin downstream adb / android-CLI / Maestro to this serial so a peer pool
  # emulator booting mid-run can never steal the QA target.
  export ANDROID_SERIAL="$reuse_serial"
elif busy_serial="$(emu_running_serial adb)"; then
  echo "[qa] ERROR: $busy_serial is running but reserved by another session," >&2
  echo "[qa]        or is not the pool AVD ($EMU_POOL_AVD). Refusing to drive it." >&2
  echo "[qa]        Provision your own:  bash .claude/scripts/setup-ar-emulator.sh" >&2
  echo "[qa]        Inherit that one:    export EMU_LEASE_SESSION=<its token>" >&2
  exit 1
elif ! adb get-state >/dev/null 2>&1; then
  echo "[qa] ERROR: no Android device. Boot one first (RAM-budgeted pool):" >&2
  echo "[qa]   bash .claude/scripts/setup-ar-emulator.sh" >&2
  exit 1
fi

# --- API keys (#2343) ------------------------------------------------------
# Resolve + EXPORT the demo store secrets BEFORE the build so build.gradle wires
# them into the debug APK (Explore/Sketchfab + AR Cloud). Without this the build
# is silently keyless and those paths are untestable. Presence (not the value)
# is logged. When run standalone (no parent device-qa.sh), print the loud banner
# here too — a parent orchestrator sets QA_KEYS_RESOLVED_BY_PARENT=1 and prints
# its own banner once, so this child stays quiet to avoid a double banner.
qa_keys_resolve_all
echo "[qa] API keys — Sketchfab present: ${QA_SKETCHFAB_KEY_PRESENT}, ARCore present: ${QA_ARCORE_KEY_PRESENT}"
if [[ "${QA_KEYS_RESOLVED_BY_PARENT:-}" != "1" ]]; then
  qa_keys_banner_if_absent
fi

# --- Optional build + install ---------------------------------------------
if $INSTALL; then
  # #2343: if a key is present, delete any pre-existing (possibly KEYLESS) APK so
  # the `[[ ! -f "$APK" ]]` guard below can never short-circuit a keyed build. An
  # env-sourced buildConfigField is not a tracked Gradle input, so a stale keyless
  # APK at this path would otherwise be installed verbatim while record_key_subleg
  # reports the sub-leg `passed` off resolution-time presence — the exact false
  # green #2343 kills. (DO NOT remove this — see PR #2347 review.)
  qa_keys_force_fresh_build_if_present "$APK"
  if [[ ! -f "$APK" ]]; then
    echo "[qa] building demo APK (this is a cold build — streams progress)..."
    # `--console=plain` (NOT -q): a quiet build emits zero output, so a slow
    # cold build on a 2-core CI runner looked like a 40-min silent hang.
    # `timeout` bounds it so a genuinely stuck build/daemon fails fast with a
    # clear diagnostic instead of eating the whole CI job budget (#1560).
    timeout "${ANDROID_BUILD_TIMEOUT:-1800}" \
      ./gradlew :samples:android-demo:assembleDebug --console=plain || {
        echo "[qa] ERROR: APK build failed or timed out (>${ANDROID_BUILD_TIMEOUT:-1800}s)" >&2
        exit 1
      }
  fi
  echo "[qa] installing $APK"
  android_cli_ensure || true
  if android_cli_locate; then
    android_cli_install_and_launch "$APK" "${PACKAGE}/${ACTIVITY}" >/dev/null || {
      echo "[qa] android run failed, falling back to adb install" >&2
      adb install -r "$APK"
    }
  else
    adb install -r "$APK"
  fi
fi

# --- Launchability gate (#2725) ---------------------------------------------
# A stale/partial prior install can leave the package listed by `pm list
# packages` while its launcher activity is NOT resolvable. `adb install -r`
# over that residue "succeeds", and Maestro then fails every flow with
# "Unable to launch app" — proven 2026-07-14: the whole catalog leg died in
# 34 s and was graded a genuine FAIL. Probe the actual launch component; if
# unresolvable, do ONE clean uninstall + reinstall (needs the APK), re-probe,
# and otherwise fail fast with a clear diagnostic instead of letting 49 demos
# fail one by one.
qa_launchable() {
  adb shell cmd package resolve-activity --brief "${PACKAGE}/${ACTIVITY}" 2>/dev/null \
    | tr -d '\r' | grep -q "^${PACKAGE}/"
}
if ! qa_launchable; then
  echo "[qa] WARNING: ${PACKAGE}/${ACTIVITY} is not resolvable — stale/partial install residue (#2725)." >&2
  if [[ -f "$APK" ]]; then
    echo "[qa] remediation: clean uninstall + reinstall from $APK"
    adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
    adb install -r -g "$APK" || {
      echo "[qa] ERROR: clean reinstall failed." >&2
      exit 1
    }
    if ! qa_launchable; then
      echo "[qa] ERROR: ${PACKAGE}/${ACTIVITY} still not resolvable after a clean reinstall — aborting before the flow burns the whole catalog." >&2
      exit 1
    fi
    echo "[qa] remediation OK — launch activity resolvable."
  else
    echo "[qa] ERROR: no APK at $APK to reinstall from — rerun with --install (or build the demo APK first)." >&2
    exit 1
  fi
fi

# --- Crash gate: clear logcat so the post-run FATAL/ANR sweep is scoped -----
adb logcat -c 2>/dev/null || true

# --- Optional screen recording (best-effort) -------------------------------
# Record the whole QA run for visual review — parity with the iOS leg
# (`simctl io recordVideo`) and the web leg (Playwright `page.screencast`).
# Uses the HOST-SIDE `adb emu screenrecord` console path (#1671): unlike the
# guest-side `adb shell screenrecord` it captures the emulator's presented
# framebuffer directly, so it is immune to the Emulator 36.x gfxstream
# regression that records `-gpu host` Filament content as near-empty. Strictly
# best-effort: a recording failure never fails the QA run (e.g. a physical
# device, where the emulator console is unavailable). The .webm lands under
# tools/qa-screenshots/android/ (gitignored).
QA_VIDEO=""
QA_VIDEO_DIR="${ANDROID_QA_VIDEO_DIR:-tools/qa-screenshots/android}"
mkdir -p "$QA_VIDEO_DIR"
# `--time-limit` is a hard encoder cap. Maestro's catalog run is long, so cap
# generously (overridable via ANDROID_QA_REC_LIMIT for short single flows).
if REC_TMP="$(android_cli_screenrecord_start "${ANDROID_SERIAL:-}" "${ANDROID_QA_REC_LIMIT:-1800}")"; then
  QA_VIDEO="$QA_VIDEO_DIR/android-qa-${FLOW}.webm"
  echo "[qa] recording the QA run -> $QA_VIDEO"
else
  echo "[qa] screen recording unavailable (host-side 'adb emu screenrecord' needs an emulator) — continuing without it"
fi

# --- Run the Maestro flow --------------------------------------------------
echo "[qa] running Maestro flow: $FLOW_FILE"
MAESTRO_RC=0
maestro_run "$FLOW_FILE" || MAESTRO_RC=$?

# One-shot retry on an emulator-transport drop ("device offline" — the CI
# emulator going unstable mid-flow, #1643). A transport drop is intermittent;
# one retry rescues a flaky run without masking a genuine demo failure (a real
# crash fails again the same way). Only retried for offline/transport errors.
if [[ "$MAESTRO_RC" -ne 0 ]]; then
  if adb get-state >/dev/null 2>&1; then
    echo "[qa] device still online — Maestro failure is genuine, not retrying." >&2
  else
    echo "[qa] device offline after flow — emulator transport dropped; one retry..." >&2
    adb wait-for-device 2>/dev/null || true
    MAESTRO_RC=0
    maestro_run "$FLOW_FILE" || MAESTRO_RC=$?
  fi
fi

# --- Stop the screen recording --------------------------------------------
# Stop the host-side emulator recording and move the finished .webm into the
# QA video dir. Best-effort throughout — a recording failure never affects the
# QA verdict.
if [[ -n "$QA_VIDEO" ]]; then
  android_cli_screenrecord_stop "${ANDROID_SERIAL:-}" || true
  if [[ -n "${REC_TMP:-}" && -s "$REC_TMP" ]]; then
    mv "$REC_TMP" "$QA_VIDEO" 2>/dev/null \
      && echo "[qa] recording saved: $QA_VIDEO" \
      || echo "[qa] recording could not be moved from $REC_TMP" >&2
  fi
fi

# --- FATAL / ANR logcat sweep ---------------------------------------------
# Maestro's per-demo "Navigate back" assertion already fails on a hard crash,
# but a backgrounded native crash or an ANR can leave the process technically
# alive — so we also grep logcat. This complements (does not replace) the
# orchestrator runner's sweep (umbrella slice #1566).
CRASHES="$(adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|ANR in ${PACKAGE}" || true)"
if [[ -n "$CRASHES" ]]; then
  echo "[qa] CRASH/ANR detected in logcat:" >&2
  echo "$CRASHES" | tail -20 >&2
  MAESTRO_RC=1
fi

if [[ "$MAESTRO_RC" -eq 0 ]]; then
  echo "[qa] PASS — $FLOW flow completed, no crash/ANR detected."
else
  echo "[qa] FAIL — see Maestro output above (rc=$MAESTRO_RC)." >&2
fi
exit "$MAESTRO_RC"
