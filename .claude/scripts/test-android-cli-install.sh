#!/usr/bin/env bash
# Hermetic self-test for android_cli_install_and_launch (#2990).
#
# WHY THIS EXISTS
#   The helper used to `return $?` from `android run`. On a real emulator that
#   command printed two success-shaped lines, failed to install anything, and
#   left the device holding a build eight hours old — so a QA run measured the
#   PREVIOUS binary and reported a verified fix that never reached the device.
#   Exit codes are not evidence; the device's `lastUpdateTime` is.
#
#   This suite pins the property that matters: the helper CANNOT return 0 unless
#   the stamp moved. It runs entirely against stub `adb` / `android` binaries —
#   no emulator, no lease, no network — because the failure it guards is a
#   LOGIC failure, and a test that needed a device would never run.
#
#   The last block is a mutation test: restore the old `return $?` shape and the
#   suite must go RED.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SCRIPT_DIR/lib/android-cli.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

mkdir -p "$TMP/bin"
: > "$TMP/app.apk"

# The stub device keeps its "installed build" timestamp in a file. `adb install`
# moves it; `android run` moves it only when STUB_CLI_INSTALLS=1. That is the
# whole world this test needs.
cat > "$TMP/bin/adb" <<'STUB'
#!/usr/bin/env bash
# Match on the WHOLE argument list, not the first recognised word. A per-arg
# loop matched `shell` before `dumpsys` in `adb -s X shell dumpsys package P`
# and silently returned no stamp — a stub bug that reads exactly like the
# helper being broken. Order-independent matching removes that whole class.
all=" $* "
case "$all" in
  *" install "*)
    [ "${STUB_ADB_INSTALLS:-1}" = "1" ] && date +%s%N > "$STUB_STATE"
    exit "${STUB_ADB_RC:-0}" ;;
  *" dumpsys "*)
    [ -s "$STUB_STATE" ] && echo "    lastUpdateTime=$(cat "$STUB_STATE")"
    exit 0 ;;
esac
exit 0
STUB

cat > "$TMP/bin/android" <<'STUB'
#!/usr/bin/env bash
# Mimics the measured misbehaviour: success-shaped output, no install.
echo "App loaded: com.example"
echo "Debuggable: true"
if [ "${STUB_CLI_INSTALLS:-0}" = "1" ]; then
  date +%s%N > "$STUB_STATE"
else
  echo "No matching components found for type ACTIVITY with name com.example/.MainActivity"
fi
exit 0
STUB
chmod +x "$TMP/bin/adb" "$TMP/bin/android"

export PATH="$TMP/bin:$PATH"
export STUB_STATE="$TMP/installed_stamp"

run_helper() {
  ( set +e
    # shellcheck disable=SC1090
    source "$LIB" >/dev/null 2>&1
    android_cli_install_and_launch "$TMP/app.apk" "com.example/.MainActivity" "emulator-5554" \
      >"$TMP/out" 2>"$TMP/err"
    echo $? )
}

echo "android_cli_install_and_launch (#2990)"

# 1 ── the exact measured defect: CLI prints success, installs nothing, adb saves it
printf 'seed\n' > "$STUB_STATE"
STUB_CLI_INSTALLS=0 STUB_ADB_INSTALLS=1
export STUB_CLI_INSTALLS STUB_ADB_INSTALLS
rc="$(run_helper)"
if [ "$rc" = "0" ] && grep -q "falling back to adb" "$TMP/err"; then
  ok "CLI installs nothing → falls back to adb, and says so"
else
  bad "CLI-installs-nothing case: rc=$rc, stderr did not mention the fallback"
  sed 's/^/      /' "$TMP/err" | head -3
fi

# 2 ── nothing installs at all: MUST refuse, loudly. This is the false-green case.
printf 'seed\n' > "$STUB_STATE"
STUB_CLI_INSTALLS=0 STUB_ADB_INSTALLS=0
export STUB_CLI_INSTALLS STUB_ADB_INSTALLS
rc="$(run_helper)"
if [ "$rc" != "0" ] && grep -q "INSTALL NOT PROVEN" "$TMP/err"; then
  ok "no install anywhere → non-zero AND names the danger (stale binary)"
else
  bad "silent non-install returned rc=$rc — this is the #2990 false green"
fi

# 3 ── the happy CLI path still short-circuits (no needless adb install)
printf 'seed\n' > "$STUB_STATE"
STUB_CLI_INSTALLS=1 STUB_ADB_INSTALLS=1
export STUB_CLI_INSTALLS STUB_ADB_INSTALLS
rc="$(run_helper)"
if [ "$rc" = "0" ] && ! grep -q "falling back" "$TMP/err"; then
  ok "CLI installs correctly → returns 0 without falling back"
else
  bad "working CLI path did not short-circuit (rc=$rc)"
fi

# 4 ── a missing APK is refused before any device is touched
rc="$( ( set +e; source "$LIB" >/dev/null 2>&1
         android_cli_install_and_launch "$TMP/nope.apk" "com.example/.MainActivity" "emulator-5554" \
           >/dev/null 2>"$TMP/err"; echo $? ) )"
if [ "$rc" != "0" ] && grep -q "APK not found" "$TMP/err"; then
  ok "missing APK refused up front"
else
  bad "missing APK was not refused (rc=$rc)"
fi

# 5 ── MUTATION: the old shape (trust the CLI's exit code) must make this RED
mut="$TMP/mutated-lib.sh"
sed 's/if \[\[ -n "\$after" \&\& "\$after" != "\$before" \]\]; then/if true; then/' "$LIB" > "$mut"
if ! grep -q "if true; then" "$mut"; then
  bad "mutation could not be applied — the guard's shape changed, retarget it"
else
  printf 'seed\n' > "$STUB_STATE"
  STUB_CLI_INSTALLS=0 STUB_ADB_INSTALLS=0
  export STUB_CLI_INSTALLS STUB_ADB_INSTALLS
  rc="$( ( set +e; source "$mut" >/dev/null 2>&1
           android_cli_install_and_launch "$TMP/app.apk" "com.example/.MainActivity" "emulator-5554" \
             >/dev/null 2>/dev/null; echo $? ) )"
  if [ "$rc" = "0" ]; then
    ok "mutation: trusting the CLI's exit code reintroduces the false green (so the guard is load-bearing)"
  else
    bad "mutation SURVIVED — the stamp check is not what makes this pass"
  fi
fi

echo
echo "android-cli-install: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
