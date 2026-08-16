#!/usr/bin/env bash
#
# Pins the pre-push gate against ci.yml's `unit-test` job, task by task.
#
# WHY THIS EXISTS
#
# `pre-push-check.sh` opens with: "If a CI check appears in NEITHER [the legs
# nor the deliberately-not-covered list], nobody has audited it — that is a
# gap, not a decision." Nothing enforced that sentence, so the gap arrived on
# schedule. Measured 2026-08-16 on `main`:
#
#   ci.yml unit-test runs 7 gradle tasks. The gate ran 4 of them. The three it
#   never ran — `:sceneview-core:androidTest`, `:samples:common:testDebugUnitTest`,
#   `:samples:android-tv-demo:testDebugUnitTest` — were named nowhere in the
#   CI-PARITY block either. `:samples:android-tv-demo:testDebugUnitTest` had
#   been added to CI the previous day by #3193, whose whole subject was a test
#   suite no workflow invoked; the fix wired CI and left the local gate behind,
#   one storey down.
#
# That is the shape this file refuses: a session runs the gate, reads "ALL
# CHECKS PASSED — safe to push", and pushes a tree whose tests CI has not run
# yet and the gate never will. The failure is silent by construction — a task
# nobody invokes cannot go red.
#
# WHAT COUNTS AS COVERED
#
#   1. the gate passes the exact task to `gradle_run`, or
#   2. the gate passes the module's aggregate `:module:test`, which Gradle
#      wires to depend on `testDebugUnitTest` / `testReleaseUnitTest` — so
#      `:sceneview:test` covers CI's `:sceneview:testDebugUnitTest`, or
#   3. the task is declared unrunnable here by a machine-readable line in
#      pre-push-check.sh:
#
#         #   CI-PARITY-EXCLUDE: :module:task — why it cannot run locally
#
# Rule 3 is the escape hatch, and it is deliberately noisy: an exclusion is a
# sentence someone had to write and a reviewer can read. Silence is not one.
#
# Only lines that are NOT shell comments are parsed on either side. Both files
# quote gradle tasks inside prose — ci.yml's own comment names
# `recordRoborazziDebug`, the gate echoes it as a hint — and a parser that
# reads comments would call a task covered because someone mentioned it.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CI_YML="$REPO_ROOT/.github/workflows/ci.yml"
GATE="$REPO_ROOT/.claude/scripts/pre-push-check.sh"

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ── Extractors ────────────────────────────────────────────────────────────────

# Gradle tasks in ci.yml's `unit-test` job, from non-comment lines only.
ci_unit_test_tasks() {
    awk '
        /^  unit-test:/       { injob = 1; next }
        injob && /^  [a-z]/   { injob = 0 }
        injob {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (substr(line, 1, 1) == "#") next
            print line
        }
    ' "$1" |
        grep -oE ':[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)+' |
        sort -u
}

# Gradle tasks the gate hands to gradle_run. Continuation lines are joined so a
# multi-task invocation split over several lines is read whole.
gate_tasks() {
    awk '
        {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (substr(line, 1, 1) == "#") { if (!cont) next }
            if (cont || line ~ /gradle_run/) {
                printf "%s ", line
                cont = (line ~ /\\$/)
                if (!cont) printf "\n"
            }
        }
    ' "$1" |
        grep -oE ':[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)+' |
        sort -u
}

# Declared exclusions: `#   CI-PARITY-EXCLUDE: :module:task — reason`
gate_exclusions() {
    sed -n 's/^[[:space:]]*#[[:space:]]*CI-PARITY-EXCLUDE:[[:space:]]*\(:[A-Za-z0-9_.:-]*\).*/\1/p' "$1" |
        sort -u
}

echo "CI ↔ gate gradle-task parity"
echo

# ── 0. The extractors must actually extract something ─────────────────────────
#
# Every assertion below is a set difference. Two empty sets differ by nothing,
# so a parser that silently stopped matching would print a clean green sheet.
# That is the exact failure this suite exists to catch, so it is asserted first.

CI_TASKS="$(ci_unit_test_tasks "$CI_YML")"
GATE_TASKS="$(gate_tasks "$GATE")"
EXCLUDED="$(gate_exclusions "$GATE")"

ci_count=$(printf '%s\n' "$CI_TASKS" | grep -c . || true)
gate_count=$(printf '%s\n' "$GATE_TASKS" | grep -c . || true)

if [ "$ci_count" -ge 5 ]; then
    ok "parsed $ci_count gradle tasks from ci.yml's unit-test job"
else
    bad "parsed only $ci_count tasks from ci.yml's unit-test job — the parser broke, every check below is vacuous"
fi

if [ "$gate_count" -ge 3 ]; then
    ok "parsed $gate_count gradle tasks from the gate's gradle_run calls"
else
    bad "parsed only $gate_count tasks from the gate — the parser broke, every check below is vacuous"
fi

# ── 1. Comments must not be read as coverage ──────────────────────────────────
#
# ci.yml's unit-test job names `recordRoborazziDebug` in prose, as the command
# to run when a golden change is intentional. CI does not run it. If it shows
# up as a CI task, the extractor is reading comments — and a gate task quoted
# in a comment would then count as coverage.

if printf '%s\n' "$CI_TASKS" | grep -q 'recordRoborazziDebug'; then
    bad "the ci.yml extractor is reading comments (recordRoborazziDebug is prose, not a step)"
else
    ok "the ci.yml extractor skips comments"
fi

# ── 2. Every CI task is run locally, aggregated, or excluded in writing ───────

missing=""
for task in $CI_TASKS; do
    covered=""

    printf '%s\n' "$GATE_TASKS" | grep -qx -- "$task" && covered="exact"

    # Aggregate: CI's :m:testDebugUnitTest is covered by the gate's :m:test.
    if [ -z "$covered" ]; then
        case "$task" in
            *:test*UnitTest)
                module="${task%:*}"
                printf '%s\n' "$GATE_TASKS" | grep -qx -- "$module:test" && covered="aggregate"
                ;;
        esac
    fi

    [ -z "$covered" ] && printf '%s\n' "$EXCLUDED" | grep -qx -- "$task" && covered="excluded"

    if [ -n "$covered" ]; then
        ok "$task — $covered"
    else
        bad "$task runs in CI, but the gate neither runs it nor excludes it in writing"
        missing="$missing $task"
    fi
done

# ── 3. An exclusion must name a task CI actually runs ─────────────────────────
#
# Otherwise an exclusion outlives the task it excused and reads as an audited
# decision about something that no longer exists.

for task in $EXCLUDED; do
    if printf '%s\n' "$CI_TASKS" | grep -qx -- "$task"; then
        ok "exclusion $task matches a task ci.yml still runs"
    else
        bad "exclusion $task names a task ci.yml's unit-test job no longer runs — stale excuse"
    fi
done

echo
if [ -n "$missing" ]; then
    echo "Unaudited gap — add each task to a gate leg, or declare it in pre-push-check.sh:"
    for task in $missing; do
        echo "    #   CI-PARITY-EXCLUDE: $task — <why it cannot run locally>"
    done
    echo
fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
