#!/usr/bin/env bash
# Hermetic self-test for the `pipefail` + short-circuit-consumer defect (#3180).
#
# What is at stake
# ----------------
# `grep -q` exits on its FIRST match and closes the read end of its pipe. A
# producer still writing then takes EPIPE and returns non-zero — and under
# `set -o pipefail` the pipeline reports FAILURE *because the match succeeded*.
# The membership test inverts, and a present item is reported absent.
#
# Measured 2026-08-14 in `validate-demo-assets.sh`: `cyberpunk_car.usdz` —
# declared in `assets/catalog.json` with its CC-BY-4.0 licence and author — was
# reported UNDECL on one run of `pre-push-check.sh`, with
# `printf: write error: Broken pipe` printed on the comparison line itself.
# Five standalone re-runs were clean. The diff under test contained two prose
# files and no assets.
#
# The consequence is not uniform, which is why this is pinned centrally rather
# than left to each script:
#   - `validate-demo-assets.sh`  → false RED in a licence-compliance gate;
#   - `test-context-budget.sh`   → an indexed skill reported as an orphan;
#   - `cleanup-branches-worktrees.sh` → a branch that HAS an open PR is not
#     classified OPEN, i.e. the wrong answer lands on the deletion side.
#
# What this suite pins
# --------------------
# The BLOCKING assertion is the call-site FORM, because that is deterministic
# and mutation-testable. The race itself is measured and printed as evidence,
# not asserted: a suite whose verdict depends on winning a race would be the
# same class of flake it exists to remove.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# The three sites fixed in #3180. Each does a membership test against a
# `$(...)`-captured list that grows with the repo, so the odds grow with it.
GUARDED_FILES='
.claude/scripts/validate-demo-assets.sh
.claude/scripts/cleanup-branches-worktrees.sh
.claude/scripts/test-context-budget.sh
'

# ── 1. The safe form is correct, in both directions ─────────────────────────
# Deterministic: a herestring keeps the shell out of the pipeline, so only
# grep's own status governs. The haystack is large and the needle is its FIRST
# line — the arrangement that makes the piped form fail 20/20 (see §4).
echo "── the herestring form answers correctly ──"

herestring_probe() {
    # $1 = needle. Echoes the exit code, under the same shell options the
    # guarded scripts run with.
    bash -c '
        set -euo pipefail
        haystack="$(seq 1 200000)"
        grep -qxF "$1" <<< "$haystack"
    ' _ "$1" 2>/dev/null
    echo $?
}

rc="$(herestring_probe 1)"
[ "$rc" = 0 ] && ok "present needle (first of 200000 lines) → found" \
               || bad "present needle → reported ABSENT (rc=$rc)"

rc="$(herestring_probe 999999)"
[ "$rc" = 1 ] && ok "absent needle → not found" \
               || bad "absent needle → expected rc=1, got rc=$rc"

# Repeat the positive case: the defect being fixed is intermittent, so a single
# green run proves nothing about a form that must never flake.
flakes=0
for _ in $(seq 1 20); do
    [ "$(herestring_probe 1)" = 0 ] || flakes=$((flakes + 1))
done
[ "$flakes" -eq 0 ] && ok "20/20 trials stable" \
                    || bad "$flakes/20 trials misreported a present needle"

# ── 2. No guarded site still uses the piped form ────────────────────────────
# This is the blocking pin. Comment lines are skipped: every fixed site carries
# a comment quoting the bad form, and a checker that cannot tell a warning from
# the defect makes documenting the defect impossible.
echo "── guarded call sites use no short-circuit pipeline ──"

# A producer piped into a consumer that exits early. `grep -q`, `head -n` and
# `read` all close the pipe before the producer is done.
BAD_FORM='(printf|echo)[^|]*\|[[:space:]]*(grep[^|]*-[a-zA-Z]*q|head[[:space:]]|read[[:space:]])'

check_call_sites() {
    local failures=0 checked=0 f line lineno
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ ! -f "$REPO_ROOT/$f" ]; then
            bad "guarded file is missing: $f"
            failures=$((failures + 1))
            continue
        fi
        checked=$((checked + 1))
        while IFS=: read -r lineno line; do
            [ -n "$lineno" ] || continue
            # Skip comments — they quote the bad form on purpose.
            case "${line#"${line%%[![:space:]]*}"}" in '#'*) continue ;; esac
            bad "$f:$lineno still pipes into a short-circuiting consumer: ${line#"${line%%[![:space:]]*}"}"
            failures=$((failures + 1))
        done < <(grep -nE "$BAD_FORM" "$REPO_ROOT/$f" 2>/dev/null)
    done <<< "$1"

    # A checker that inspected nothing must not read as a pass.
    if [ "$checked" -eq 0 ]; then
        bad "call-site check went vacuous — no guarded file was inspected"
        return 1
    fi
    [ "$failures" -eq 0 ] && ok "$checked guarded file(s) clean"
    return 0
}
check_call_sites "$GUARDED_FILES"

# ── 3. Mutation control on the checker itself ───────────────────────────────
# Reintroduce the defect into a copy and require the checker to catch it. A
# checker that cannot go red pins nothing.
echo "── mutation control ──"

mutant() {
    local desc="$1" injected="$2"
    local dir="$TMP/mut"
    rm -rf "$dir"; mkdir -p "$dir/.claude/scripts"
    printf '#!/usr/bin/env bash\nset -euo pipefail\n%s\n' "$injected" \
        > "$dir/.claude/scripts/victim.sh"
    local failures=0 lineno line
    while IFS=: read -r lineno line; do
        [ -n "$lineno" ] || continue
        case "${line#"${line%%[![:space:]]*}"}" in '#'*) continue ;; esac
        failures=$((failures + 1))
    done < <(grep -nE "$BAD_FORM" "$dir/.claude/scripts/victim.sh" 2>/dev/null)
    if [ "$failures" -gt 0 ]; then ok "mutant caught: $desc"
    else bad "mutant SURVIVED: $desc"; fi
}

mutant 'printf | grep -qxF (the measured defect)' \
       'if ! printf "%s\n" "$declared" | grep -qxF "$base"; then echo no; fi'
mutant 'echo | grep -Fxq' \
       'if echo "$list" | grep -Fxq -- "$branch"; then echo yes; fi'
mutant 'printf | head -1' \
       'first="$(printf "%s\n" "$big" | head -1)"'

# The checker must NOT flag the form we migrated to, or the fix is unshippable.
{
    dir="$TMP/clean"; rm -rf "$dir"; mkdir -p "$dir"
    printf '#!/usr/bin/env bash\nif grep -qxF "$base" <<< "$declared"; then echo yes; fi\n' \
        > "$dir/victim.sh"
    if grep -qE "$BAD_FORM" "$dir/victim.sh"; then
        bad "false positive: the herestring form is flagged as the defect"
    else
        ok "herestring form is not flagged"
    fi
}

# ── 4. Evidence, not an assertion ───────────────────────────────────────────
# Why the form above is mandatory rather than a preference. Deliberately NOT a
# pass/fail: this measures a race, and if a future bash or grep stopped losing
# it the suite must not go red for a non-defect. If this ever prints 0/20, the
# call-site rule is worth revisiting — but it is still not a regression.
echo "── evidence: the piped form under pipefail ──"
piped=0
for _ in $(seq 1 20); do
    bash -c '
        set -euo pipefail
        haystack="$(seq 1 200000)"
        printf "%s\n" "$haystack" | grep -qxF "1"
    ' 2>/dev/null || piped=$((piped + 1))
done
echo "  ℹ piped form misreported a PRESENT needle in $piped/20 trials"
[ "$piped" -eq 0 ] && echo "  ℹ 0/20 — the EPIPE race did not reproduce on this host/toolchain;" \
                   && echo "    the call-site rule above is unaffected, but re-read #3180 before relying on it"

# ── Summary ─────────────────────────────────────────────────────────────────
echo
if [ "$FAIL" -eq 0 ]; then
    echo "✅ pipefail membership: $PASS check(s) pass, guarded sites clean, all mutants caught"
    exit 0
fi
echo "❌ pipefail membership: $FAIL failed, $PASS passed"
exit 1
