#!/usr/bin/env bash
# test-as-count.sh — self-test for lib/as-count.sh.
#
# The helper exists because a `grep -c` that matches nothing prints `0` and exits
# 1, so `$(grep -c … || echo 0)` produces the two-line value `0\n0`. In
# `quality-gate.sh` that value took out two checks in two different ways: one
# comparison died and silently took the FAIL branch (a false red on a clean
# diff), and one `$((TOTAL + N))` died under `set -e` and skipped the rest of the
# block — printing NO check line at all, which is a green gate that verified
# nothing.
#
# So both consumption shapes are pinned here, not just the helper's return value:
# the comparison shape AND the arithmetic shape have to survive every input a
# failing grep can produce. Pure string work — no repo, no network, no Gradle.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/as-count.sh
source "$REPO_ROOT/.claude/scripts/lib/as-count.sh"

FAILURES=0

expect() { # $1 = label, $2 = expected, $3 = actual
    if [ "$2" = "$3" ]; then
        echo "[PASS] $1"
    else
        echo "[FAIL] $1 — expected '$2', got '$3'"
        FAILURES=$((FAILURES + 1))
    fi
}

# ── as_count: every shape a failing or succeeding grep can hand over ─────────
expect "the real defect: '0\\n0' collapses to 0" "0" "$(as_count "$(printf '0\n0')")"
expect "a plain count passes through" "7" "$(as_count "7")"
expect "empty input is 0" "0" "$(as_count "")"
expect "no argument at all is 0" "0" "$(as_count)"
expect "a trailing newline is stripped" "3" "$(as_count "$(printf '3\n')")"
expect "only the FIRST line counts" "12" "$(as_count "$(printf '12\n99')")"
expect "grep -c per-file output keeps the number" "4" "$(as_count "file.kt:4")"
expect "non-numeric noise is 0, never a crash" "0" "$(as_count "no such file")"

# ── The comparison shape: `[ -eq 0 ]` must not die and must not take FAIL ────
verdict() { # mimics `[ "$N" -eq 0 ] && echo PASS || echo FAIL`
    local n
    n="$(as_count "${1:-}")"
    [ "$n" -eq 0 ] && echo "PASS" || echo "FAIL"
}
expect "clean diff verdict is PASS, not the false red" "PASS" "$(verdict "$(printf '0\n0')")"
expect "a real hit still reports FAIL" "FAIL" "$(verdict "2")"

# ── The arithmetic shape: `$((TOTAL + N))` must not abort the block ──────────
# This is the one that printed no check line at all. The subshell runs under
# `set -e`, exactly like quality-gate.sh, so an arithmetic error would abort it
# and lose the echo.
total_of() {
    (
        set -e
        local total=0 v
        for v in "$@"; do
            total=$((total + $(as_count "$v")))
        done
        echo "$total"
    )
}
expect "summing over '0\\n0' does not abort the block" "0" "$(total_of "$(printf '0\n0')" "$(printf '0\n0')")"
expect "summing mixes clean and real counts" "5" "$(total_of "$(printf '0\n0')" "5")"
expect "summing survives non-numeric noise" "9" "$(total_of "no such file" "9")"

# ── as_count_or_unknown: 0 must read as '?', never as 'no offending lines' ───
expect "a failed checker that itemised nothing reads '?'" "?" "$(as_count_or_unknown "$(printf '0\n0')")"
expect "a failed checker with items reads the count" "6" "$(as_count_or_unknown "6")"
expect "no argument reads '?'" "?" "$(as_count_or_unknown)"

# ── Static guard: no positional dereferenced without a default ───────────────
# The behavioural "no argument at all" case above only FAILS on bash 5. On the
# bash 3.2 that ships with macOS, `${1%%…}` with no positional silently yields
# the empty string instead of erroring under `set -u`, so this whole file passed
# locally while the runner went red. That is the local-vs-runner divergence the
# helper is supposed to eliminate, so it gets a check that does not depend on
# which bash is running: every `${N…}` in the library must open with a default.
#
# Full-line comments are dropped first — the library's own header quotes the
# broken form on purpose, and a guard that flags the documentation of a bug it
# fixed is a guard nobody keeps. Anything outside a full-line comment counts.
LIB="$REPO_ROOT/.claude/scripts/lib/as-count.sh"
UNSAFE=""
while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    case "$ref" in
        '${'[0-9]'-'* | '${'[0-9]':-'*) ;;
        *) UNSAFE="$UNSAFE $ref" ;;
    esac
done <<EOF
$(grep -v '^[[:space:]]*#' "$LIB" | grep -o '\${[0-9][^}]*' || true)
EOF
expect "no positional is dereferenced without a default" "" "$UNSAFE"

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "as-count.sh: all scenarios hold"
    exit 0
fi
echo "as-count.sh: $FAILURES scenario(s) failed"
exit 1
