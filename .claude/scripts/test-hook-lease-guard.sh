#!/usr/bin/env bash
# test-hook-lease-guard.sh — hermetic self-test for hook-dispatch.sh's emulator
# lease guard (pre-bash, blocking) and the parallel-worktree claim reminder.
#
# Why this exists: on 2026-07-27 a session drove an emulator held by a sibling
# session's sticky lease (`install -r`, `pm clear`, `am force-stop`), killing its
# QA run. The scripts honour leases (#2862, #2916); raw adb typed by a session
# bypassed all of it. hook-dispatch.sh now refuses that command — and this test
# is what keeps the refusal honest, because a guard that silently stops firing is
# worse than no guard at all.
#
# Hermetic: a scratch EMU_LEASE_DIR with hand-written lease files, hook JSON piped
# on stdin. No emulator, no adb, no network, no repo mutation.
#
# Usage:
#   bash .claude/scripts/test-hook-lease-guard.sh
#   HOOK=/path/to/candidate/hook-dispatch.sh bash .claude/scripts/test-hook-lease-guard.sh

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="${HOOK:-$SCRIPT_DIR/hook-dispatch.sh}"

[ -f "$HOOK" ] || { echo "FATAL: hook not found: $HOOK" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "SKIP: jq not available — the hook no-ops without it."; exit 0; }

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
LEASE_DIR="$SCRATCH/leases"
mkdir -p "$LEASE_DIR"

PASS=0
FAIL=0

# Run the hook with a Bash command payload; echo its exit code.
run_hook() {
  printf '%s' "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":$(printf '%s' "$1" | jq -Rs .)}}" \
    | EMU_LEASE_DIR="$LEASE_DIR" bash "$HOOK" pre-bash >/dev/null 2>&1
  echo $?
}

expect() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    printf '  \033[0;32m✓\033[0m %s (exit %s)\n' "$label" "$got"
    PASS=$((PASS + 1))
  else
    printf '  \033[0;31m✗\033[0m %s — expected exit %s, got %s\n' "$label" "$want" "$got"
    FAIL=$((FAIL + 1))
  fi
}

write_lease() { # serial mode session since owner_pid
  printf '%s\nmode=%s\nsession=%s\navd=Pixel_7a\nsince=%s\nlabel=test\n' \
    "$5" "$2" "$3" "$4" > "$LEASE_DIR/$1.lease"
}

NOW="$(date +%s)"
FOREIGN_SESSION="sv-${NOW}-999999"

echo "== emulator lease guard =="

# A live sticky lease owned by someone else must block a mutating command.
write_lease "emulator-5554" "sticky" "$FOREIGN_SESSION" "$NOW" "999999"
expect "foreign live sticky lease + pm clear -> BLOCKED" 2 \
  "$(run_hook 'adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"
expect "foreign live sticky lease + install -r -> BLOCKED" 2 \
  "$(run_hook 'adb -s emulator-5554 install -r app.apk')"
expect "foreign live sticky lease + am force-stop -> BLOCKED" 2 \
  "$(run_hook 'adb -s emulator-5554 shell am force-stop io.github.sceneview.demo')"

# Escape hatches must mirror lib/emulator-select.sh semantics exactly.
expect "owner re-declares its session token -> allowed" 0 \
  "$(run_hook "EMU_LEASE_SESSION=$FOREIGN_SESSION adb -s emulator-5554 shell pm clear io.github.sceneview.demo")"
expect "deliberate takeover -> allowed" 0 \
  "$(run_hook 'EMU_LEASE_TAKEOVER=1 adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"

# Read-only inspection is never the incident, and must stay frictionless.
expect "read-only dumpsys -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5554 shell dumpsys package io.github.sceneview.demo')"
expect "read-only devices listing -> allowed" 0 \
  "$(run_hook 'adb devices -l')"

# A command aimed at a different serial must not be caught by this lease.
expect "mutating command on another serial -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5556 shell pm clear io.github.sceneview.demo')"

# An expired sticky lease is not a live reservation.
write_lease "emulator-5554" "sticky" "$FOREIGN_SESSION" "$((NOW - 20000))" "999999"
expect "expired sticky lease (> TTL) -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"

# A pid-mode lease whose owner is dead is stale, not a reservation.
write_lease "emulator-5554" "pid" "$FOREIGN_SESSION" "$NOW" "999999"
expect "pid lease with dead owner -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"

# No leases at all: the guard must be invisible.
rm -f "$LEASE_DIR"/*.lease
expect "no lease files -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"

# The hook must never block on its own missing state.
LEASE_DIR_BACKUP="$LEASE_DIR"
LEASE_DIR="$SCRATCH/does-not-exist"
expect "lease dir absent -> allowed" 0 \
  "$(run_hook 'adb -s emulator-5554 shell pm clear io.github.sceneview.demo')"
LEASE_DIR="$LEASE_DIR_BACKUP"

echo "== a command that MENTIONS a device command must not be blocked =="
# Regression: the first version of this guard matched substrings and blocked a
# `gh issue comment` whose body merely quoted the incident command. A mention is
# not an invocation — lib/hook-adb-target.py requires COMMAND position.
write_lease "emulator-5554" "sticky" "$FOREIGN_SESSION" "$NOW" "999999"
expect "gh issue comment quoting the command -> allowed" 0 \
  "$(run_hook 'gh issue comment 2924 --body "the incident command was: adb -s emulator-5554 shell pm clear io.github.sceneview.demo"')"
expect "git commit message quoting the command -> allowed" 0 \
  "$(run_hook 'git commit -m "guard: refuse adb -s emulator-5554 shell pm clear when foreign-leased"')"
expect "echo of a device command -> allowed" 0 \
  "$(run_hook 'echo "adb -s emulator-5554 install -r app.apk"')"
expect "grep for the pattern in sources -> allowed" 0 \
  "$(run_hook 'grep -rn "adb .* pm clear" .claude/scripts/')"

echo "== invocation shapes the guard must still catch =="
expect "leading env assignment + real invocation -> BLOCKED" 2 \
  "$(run_hook 'ANDROID_SERIAL=emulator-5554 adb shell pm clear io.github.sceneview.demo')"
expect "invocation after a separator -> BLOCKED" 2 \
  "$(run_hook 'cd /tmp && adb -s emulator-5554 install -r app.apk')"
expect "absolute path to the driver -> BLOCKED" 2 \
  "$(run_hook '/usr/local/bin/adb -s emulator-5554 shell am force-stop io.github.sceneview.demo')"

echo "== unrelated commands stay untouched =="
write_lease "emulator-5554" "sticky" "$FOREIGN_SESSION" "$NOW" "999999"
expect "git status -> allowed" 0 "$(run_hook 'git status --porcelain')"
expect "gradle build -> allowed" 0 "$(run_hook './gradlew :sceneview:compileReleaseKotlin')"
expect "qa script (adb lives INSIDE it, invisible here) -> allowed" 0 \
  "$(run_hook 'bash .claude/scripts/qa-android-demos.sh --fast')"
expect "git worktree add -> allowed (reminder only)" 0 \
  "$(run_hook 'git worktree add .claude/worktrees/foo -b claude/foo')"

echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[0;32m✓ hook lease guard: %d/%d assertions passed\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
fi
printf '\033[0;31m✗ hook lease guard: %d failure(s) out of %d\033[0m\n' "$FAIL" "$((PASS + FAIL))"
exit 1
