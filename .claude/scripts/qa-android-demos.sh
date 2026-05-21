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
#   2. run the Maestro catalog (or a single category subflow)
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
      sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! emu_serial_alive "$ANDROID_SERIAL" adb; then
    echo "[qa] ERROR: ANDROID_SERIAL=$ANDROID_SERIAL is not a running emulator." >&2
    exit 1
  fi
  echo "[qa] targeting leased pool emulator: $ANDROID_SERIAL"
elif reuse_serial="$(emu_running_serial adb)"; then
  echo "[qa] using running emulator: $reuse_serial"
  # Pin downstream adb / android-CLI / Maestro to this serial so a peer pool
  # emulator booting mid-run can never steal the QA target.
  export ANDROID_SERIAL="$reuse_serial"
elif ! adb get-state >/dev/null 2>&1; then
  echo "[qa] ERROR: no Android device. Boot one first (RAM-budgeted pool):" >&2
  echo "[qa]   bash .claude/scripts/setup-ar-emulator.sh" >&2
  exit 1
fi

# --- Optional build + install ---------------------------------------------
if $INSTALL; then
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

# --- Crash gate: clear logcat so the post-run FATAL/ANR sweep is scoped -----
adb logcat -c 2>/dev/null || true

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
