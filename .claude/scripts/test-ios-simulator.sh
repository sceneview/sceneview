#!/usr/bin/env bash
# Self-test for lib/ios-simulator.sh — the iOS Simulator destination resolver.
#
# Hermetic: `xcrun` is overridden by a shell function per case, so this runs
# anywhere (Linux CI included) and never touches CoreSimulator.
#
# WHAT IT PINS, and why each half is load-bearing (#3174):
#
#   THE WAIT. The measured defect is a CoreSimulator cold-start race, not a
#   missing device: on run 31807717171 attempt 1 the SAME job failed on
#   `iPhone 16 Pro` at 14:07:45 with no concrete simulator listed, then resolved
#   `iPhone 16 Pro (OS:26.2)` at 14:08:01 and went green. A resolver that asked
#   once and gave up would fire inside that window and kill the WHOLE job, where
#   today only one step dies. `cold start` and `transient simctl failure` below
#   are that regression, written down.
#
#   THE REFUSAL TO DEGRADE. After the timeout the resolver must return non-zero
#   and print nothing usable. A resolver that shrugged and emitted an empty
#   `-destination` would turn a broken runner into a quiet pass — the false-green
#   class already paid for in #1515 and #2878.
#
# Both are mutation-tested at the end: this file must go RED when either half is
# removed from the library, or it is decoration.

# The case bodies below are invoked indirectly, through run_case "$@" — a
# file-level directive, so it must precede the first command.
# shellcheck disable=SC2329
# shellcheck source-path=SCRIPTDIR

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$REPO_ROOT/.claude/scripts/lib/ios-simulator.sh"

if [ ! -f "$LIB" ]; then
    printf '❌ missing %s\n' "$LIB" >&2
    exit 1
fi

FAILURES=0

# Two devices under the newest runtime and an older iPhone above it, so a
# resolver that takes the FIRST match, or that ignores the runtime ordering,
# picks the wrong one and this file notices.
WARM_LISTING='== Devices ==
-- iOS 18.0 --
    iPhone 15 (11111111-1111-1111-1111-111111111111) (Shutdown)
-- iOS 26.2 --
    iPhone 16 Pro (DB7A4F45-473E-4AFF-B207-43FBCE9682DE) (Shutdown)
    iPad Pro 11-inch (22222222-2222-2222-2222-222222222222) (Shutdown)
-- tvOS 18.0 --
    Apple TV (33333333-3333-3333-3333-333333333333) (Shutdown)'

# What the cold window actually looks like: the runtime header is absent, so
# there is no iOS device to find yet. Non-iOS platforms are present, which is
# why "the listing is non-empty" is not a readiness signal.
COLD_LISTING='== Devices ==
-- tvOS 18.0 --
    Apple TV (33333333-3333-3333-3333-333333333333) (Shutdown)'

WANT_UDID='DB7A4F45-473E-4AFF-B207-43FBCE9682DE'

# Runs one case in a subshell (each installs its own `xcrun` stub) and reports.
# The subshell cannot bump a counter in this shell, so it signals by exit status
# and the caller accumulates — an earlier version of this harness counted inside
# the subshell and always printed "0 failed".
run_case() { # name expected_rc expected_stdout body...
    local name="$1" want_rc="$2" want_out="$3"; shift 3
    local out rc
    out="$("$@" 2>/dev/null)"; rc=$?
    if [ "$rc" = "$want_rc" ] && [ "$out" = "$want_out" ]; then
        printf '  ✅ %s\n' "$name"
        return 0
    fi
    printf '  ❌ %s\n     want rc=%s out=%q\n     got  rc=%s out=%q\n' \
        "$name" "$want_rc" "$want_out" "$rc" "$out"
    FAILURES=$((FAILURES + 1))
    return 1
}

# --- case bodies (each runs in its own subshell via run_case) ----------------

case_nominal() (
    xcrun() { printf '%s\n' "$WARM_LISTING"; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    ios_simulator_udid
)

case_cold_start() (
    local_count="$1"
    printf '0\n' > "$local_count"
    xcrun() {
        local n; n="$(cat "$local_count")"
        printf '%s\n' "$((n + 1))" > "$local_count"
        if [ "$n" -lt 2 ]; then printf '%s\n' "$COLD_LISTING"
        else printf '%s\n' "$WARM_LISTING"; fi
    }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    IOS_SIM_WAIT_SECONDS=30 IOS_SIM_POLL_SECONDS=0
    ios_simulator_udid
)

case_transient_simctl_failure() (
    local_count="$1"
    printf '0\n' > "$local_count"
    xcrun() {
        local n; n="$(cat "$local_count")"
        printf '%s\n' "$((n + 1))" > "$local_count"
        if [ "$n" -lt 1 ]; then
            printf 'xcrun: error: unable to find utility simctl\n' >&2
            return 72
        fi
        printf '%s\n' "$WARM_LISTING"
    }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    IOS_SIM_WAIT_SECONDS=30 IOS_SIM_POLL_SECONDS=0
    ios_simulator_udid
)

case_never_ready() (
    xcrun() { printf '%s\n' "$COLD_LISTING"; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    IOS_SIM_WAIT_SECONDS=1 IOS_SIM_POLL_SECONDS=1
    ios_simulator_udid
)

case_simctl_always_fails() (
    xcrun() { printf 'xcrun: error: no Xcode selected\n' >&2; return 72; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    IOS_SIM_WAIT_SECONDS=1 IOS_SIM_POLL_SECONDS=1
    ios_simulator_udid
)

case_destination_ok() (
    xcrun() { printf '%s\n' "$WARM_LISTING"; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    ios_simulator_destination
)

case_destination_fails() (
    xcrun() { printf '%s\n' "$COLD_LISTING"; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    IOS_SIM_WAIT_SECONDS=1 IOS_SIM_POLL_SECONDS=1
    ios_simulator_destination
)

# The workflow steps that call this run under `set -euo pipefail`. A resolver
# that let a failing `simctl` abort the shell mid-poll would never reach its own
# retry, so this is the wait's precondition, not a style check.
case_under_set_e() (
    xcrun() { printf 'boom\n' >&2; return 1; }
    # shellcheck source=lib/ios-simulator.sh
    . "$LIB"
    set -euo pipefail
    # shellcheck disable=SC2034  # consumed by the sourced library
    IOS_SIM_WAIT_SECONDS=1 IOS_SIM_POLL_SECONDS=1
    ios_simulator_udid
)

# --- the suite ---------------------------------------------------------------

run_suite() { # lib_path -> echoes the failure count for that library
    LIB="$1"
    FAILURES=0
    local cold_count transient_count
    cold_count="$(mktemp)"; transient_count="$(mktemp)"

    run_case 'nominal → newest runtime, iPhone over iPad' \
        0 "$WANT_UDID" case_nominal
    run_case 'cold start → waits for CoreSimulator, then resolves' \
        0 "$WANT_UDID" case_cold_start "$cold_count"
    run_case 'transient simctl failure → retries, then resolves' \
        0 "$WANT_UDID" case_transient_simctl_failure "$transient_count"
    run_case 'never ready → non-zero, no destination' \
        1 '' case_never_ready
    run_case 'simctl always fails → non-zero' \
        1 '' case_simctl_always_fails
    run_case 'destination format' \
        0 "platform=iOS Simulator,id=$WANT_UDID" case_destination_ok
    run_case 'destination propagates failure (no half-formed -destination)' \
        1 '' case_destination_fails
    run_case 'survives set -e (returns, never exits the caller)' \
        1 '' case_under_set_e

    # The cold-start case is only meaningful if it actually polled again.
    local polls; polls="$(cat "$cold_count")"
    if [ "${polls:-0}" -ge 3 ]; then
        printf '  ✅ cold start polled %s times (retry really happened)\n' "$polls"
    else
        printf '  ❌ cold start polled %s times — expected ≥3\n' "${polls:-0}"
        FAILURES=$((FAILURES + 1))
    fi

    rm -f "$cold_count" "$transient_count"
    printf '%s\n' "$FAILURES"
}

printf '=== lib/ios-simulator.sh ===\n'
REAL_FAILURES="$(run_suite "$REPO_ROOT/.claude/scripts/lib/ios-simulator.sh" | tee /dev/stderr | tail -1)"

# --- mutation test -----------------------------------------------------------
# A suite that cannot go red pins nothing. Break each half of the contract in a
# copy of the library and require this file to notice. Mutant A is the exact
# version that was nearly shipped for #3174: a resolver with no wait.

printf '\n=== mutation test (each mutant MUST be caught) ===\n'
MUTANT_DIR="$(mktemp -d)"
trap 'rm -rf "$MUTANT_DIR"' EXIT

mutate() { # name sed-expression
    local name="$1" expr="$2"
    local path="$MUTANT_DIR/mutant.sh"
    sed "$expr" "$REPO_ROOT/.claude/scripts/lib/ios-simulator.sh" > "$path"
    if cmp -s "$path" "$REPO_ROOT/.claude/scripts/lib/ios-simulator.sh"; then
        printf '  ❌ mutant "%s" changed nothing — the mutation no longer applies\n' "$name"
        MUTATION_FAILURES=$((MUTATION_FAILURES + 1))
        return
    fi
    local caught; caught="$(run_suite "$path" 2>/dev/null | tail -1)"
    if [ "${caught:-0}" -gt 0 ]; then
        printf '  ✅ mutant "%s" caught (%s failing cases)\n' "$name" "$caught"
    else
        printf '  ❌ mutant "%s" SURVIVED — the suite does not pin this\n' "$name"
        MUTATION_FAILURES=$((MUTATION_FAILURES + 1))
    fi
}

MUTATION_FAILURES=0
# A: no wait — ask once, give up. The regression #3174 nearly shipped.
# The sed patterns below are literal on purpose; $picked is sed's text, not ours.
# shellcheck disable=SC2016
mutate 'no wait (ask once, give up)' \
    's/^        \[ -n "\$picked" \] && break$/        break/'
# B: degrade instead of failing — the false-green.
mutate 'degrade to success on timeout' \
    's/^        return 1$/        return 0/'
# C: take the first iOS device instead of the newest runtime.
mutate 'prefer oldest runtime' \
    's/if (name ~ \/\^iPhone\/)/if (name ~ \/^iPhone\/ \&\& !iphone)/'

printf '\n'
if [ "${REAL_FAILURES:-1}" -eq 0 ] && [ "$MUTATION_FAILURES" -eq 0 ]; then
    printf '✅ ios-simulator resolver: all cases pass, all mutants caught\n'
    exit 0
fi
printf '❌ ios-simulator resolver: %s failing case(s), %s surviving mutant(s)\n' \
    "${REAL_FAILURES:-?}" "$MUTATION_FAILURES" >&2
exit 1
