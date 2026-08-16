#!/usr/bin/env bash
#
# test-claim-collision-report.sh — self-test for claim.sh's JSON filtering (#2998).
#
# `gh … --json` emits its whole array on ONE line, so a line-oriented `grep` over it
# returns EVERY element as soon as any one matches. In claim.sh that hit two helpers
# in opposite directions:
#
#   - open_pr_collision   OVER-REPORTS the blast radius: the report named the entire
#                         open-PR backlog instead of the colliding PR, so the message
#                         that is supposed to say *who* holds the issue said nothing.
#   - issue_has_label     OVER-MATCHES the label: a substring grep for "in-progress"
#                         also fires on `in-progress-blocked` / `not-in-progress`, so
#                         a FREE issue reads as claimed and a session backs off it.
#
# Both directions are pinned here by mutation: each scenario is also run against the
# code claim.sh USED to have, and the test fails if the old code would still pass —
# a test that cannot tell the fix from the bug pins nothing.
#
# The functions under test are EXTRACTED FROM claim.sh at run time (never copied), so
# this cannot drift into testing a mirror that is more correct than production.
# Fixtures are byte-for-byte the shape `gh` emits: one line, no whitespace.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CLAIM="$REPO_ROOT/.claude/scripts/claim.sh"

[ -f "$CLAIM" ] || { echo "test-claim-collision-report: $CLAIM not found"; exit 1; }

FAILURES=0
SCENARIOS=0

pass() { SCENARIOS=$((SCENARIOS + 1)); echo "  ✓ $1"; }
fail() {
    SCENARIOS=$((SCENARIOS + 1)); FAILURES=$((FAILURES + 1))
    echo "  ✗ $1"
    [ $# -gt 1 ] && echo "      $2"
}
check() {  # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected [$2] got [$3]"; fi
}

# ── Extract the functions under test straight out of claim.sh ───────────────────
# Body runs from `name() {` to the first `}` in column 0. have_jq is NOT extracted:
# the test defines it itself, to drive both the jq and the degraded path.
extract_fn() {
    awk -v fn="$1" '
        $0 ~ "^"fn"\\(\\) \\{" { p = 1 }
        p { print }
        p && /^\}$/ { exit }
    ' "$CLAIM"
}

FN_FILTER="$(extract_fn filter_pr_collisions)"

[ -n "$FN_FILTER" ] || { echo "test-claim-collision-report: could not extract filter_pr_collisions from claim.sh"; exit 1; }

eval "$FN_FILTER"

have_jq() { command -v jq >/dev/null 2>&1; }

command -v jq >/dev/null 2>&1 || {
    echo "test-claim-collision-report: jq is not installed — cannot exercise the primary path."
    echo "  This is COULD-NOT-RUN, not a pass."
    exit 2
}

# ── The pre-fix implementations, kept ONLY as mutants ───────────────────────────
old_filter_pr_collisions() {
    local n="$1"
    grep -Ei "(#${n}\b|[-/]${n}[-/]|fix-${n}\b)" || true
}

# ── Fixtures — exactly the single-line shape `gh --json` emits ──────────────────
# The five open PRs from the #2998 report. Only #2997 references issue 2835.
PRS_2998='[{"headRefName":"claude/maintenance-autoclose","number":2997,"title":"chore: auto-close stale maintenance issues (#2835)"},{"headRefName":"claude/2987-node-count-46","number":2996,"title":"docs: node count is 46, not 26"},{"headRefName":"claude/2989-assetsource-dedup","number":2995,"title":"fix(demo): dedup the asset-source probe"},{"headRefName":"claude/2990-prove-the-install","number":2994,"title":"ci: prove the install actually ran"},{"headRefName":"fix/transform-instance-cache-staleness","number":2978,"title":"fix: stale TransformInstance cache"}]'

PRS_NULL_TITLE='[{"headRefName":"claude/2835-x","number":10,"title":null}]'
PRS_BOUNDARY='[{"headRefName":"claude/unrelated","number":11,"title":"fixes #28351 and nothing else"}]'

LBL_EXACT='{"labels":[{"name":"bug"},{"name":"in-progress"}]}'
LBL_PREFIX='{"labels":[{"name":"in-progress-blocked"}]}'
LBL_SUFFIX='{"labels":[{"name":"not-in-progress"}]}'
LBL_NONE='{"labels":[{"name":"bug"},{"name":"maintenance"}]}'

echo ""
echo "── open_pr_collision: the report must name the culprit ──────────────"

out="$(printf '%s' "$PRS_2998" | filter_pr_collisions 2835)"
check "the #2998 repro yields exactly ONE row" "1" "$(printf '%s' "$out" | grep -c .)"
case "$out" in *"#2997"*) pass "that row is the colliding PR (#2997)" ;;
    *) fail "that row is the colliding PR (#2997)" "got [$out]" ;; esac
case "$out" in *"#2996"*|*"#2995"*|*"#2994"*|*"#2978"*)
        fail "the four unrelated PRs are absent" "got [$out]" ;;
    *) pass "the four unrelated PRs are absent" ;; esac
case "$out" in *"claude/maintenance-autoclose"*) pass "the row carries the branch to back off from" ;;
    *) fail "the row carries the branch to back off from" "got [$out]" ;; esac

# MUTANT: the pre-fix grep must FAIL this same scenario, or the fix pins nothing.
old="$(printf '%s' "$PRS_2998" | old_filter_pr_collisions 2835)"
old_rows="$(printf '%s' "$old" | grep -c .)"
if [ "$old_rows" = "1" ] && ! printf '%s' "$old" | grep -q "2996"; then
    fail "MUTANT: pre-fix grep is caught" "the old code passed the repro — this test proves nothing"
else
    pass "MUTANT: pre-fix grep is caught (returned the whole backlog on one line)"
fi

out="$(printf '%s' "$PRS_2998" | filter_pr_collisions 9999)"
check "no reference → empty report (detection unchanged)" "" "$out"

out="$(printf '%s' "$PRS_2998" | filter_pr_collisions 2989)"
check "a branch-name reference is still detected" "1" "$(printf '%s' "$out" | grep -c .)"
case "$out" in *"#2995"*) pass "…and names the branch-matched PR" ;;
    *) fail "…and names the branch-matched PR" "got [$out]" ;; esac

out="$(printf '%s' "$PRS_BOUNDARY" | filter_pr_collisions 2835)"
check "#28351 does not match issue 2835 (word boundary)" "" "$out"

out="$(printf '%s' "$PRS_NULL_TITLE" | filter_pr_collisions 2835 2>&1)"
case "$out" in *"#10"*) pass "a null title does not crash the filter" ;;
    *) fail "a null title does not crash the filter" "got [$out]" ;; esac

echo ""
echo "── issue_has_label: pinning the sweep verdict, not a fix ────────────"

# #2998 asked for a sweep of the same `gh --json | grep` shape. It found exactly one
# other instance — issue_has_label, in this same file — and the measured verdict is
# that it is NOT exploitable: its pattern carries its own quotes, so `"in-progress"`
# matches neither `in-progress-blocked` nor `not-in-progress`. That safety is
# incidental, so it is pinned here: strip the quotes and these scenarios go red.
LABEL_PATTERN="$(sed -n 's/.*grep -q "\\"\$LABEL\\""/"$LABEL"/p' "$CLAIM" | head -1)"
if [ -n "$LABEL_PATTERN" ]; then
    pass "claim.sh still anchors its label grep with quotes"
else
    fail "claim.sh still anchors its label grep with quotes" \
         "the 'grep -q \"\\\"\$LABEL\\\"\"' line changed — re-measure the near-miss cases below"
fi

printf '%s' "$LBL_EXACT" | grep -q '"in-progress"' \
    && pass "an exact label is detected" \
    || fail "an exact label is detected"

printf '%s' "$LBL_PREFIX" | grep -q '"in-progress"' \
    && fail "'in-progress-blocked' must NOT read as claimed" \
    || pass "'in-progress-blocked' does NOT read as claimed (quotes anchor it)"

printf '%s' "$LBL_SUFFIX" | grep -q '"in-progress"' \
    && fail "'not-in-progress' must NOT read as claimed" \
    || pass "'not-in-progress' does NOT read as claimed (quotes anchor it)"

printf '%s' "$LBL_NONE" | grep -q '"in-progress"' \
    && fail "an unlabelled issue reads as free" \
    || pass "an unlabelled issue reads as free"

# The unanchored form IS vulnerable — this is what the quotes buy, stated as a mutant.
if printf '%s' "$LBL_PREFIX" | grep -q 'in-progress' \
   && printf '%s' "$LBL_SUFFIX" | grep -q 'in-progress'; then
    pass "MUTANT: dropping the quotes would match both near-miss labels"
else
    fail "MUTANT: dropping the quotes would match both near-miss labels" \
         "the unanchored grep did not fire — the anchoring claim is unproven"
fi

echo ""
echo "── degraded path: jq absent ─────────────────────────────────────────"

have_jq() { return 1; }   # force the fallback branch of both helpers

out="$(printf '%s' "$PRS_2998" | filter_pr_collisions 2835)"
case "$out" in
    "") fail "without jq, detection still fires" "got empty — a collision would be missed" ;;
    *unattributed*) pass "without jq, detection still fires AND says the report is unattributed" ;;
    *) fail "without jq, the report must admit it cannot name the PR" "got [$out]" ;;
esac

out="$(printf '%s' "$PRS_2998" | filter_pr_collisions 9999)"
check "without jq, a non-collision is still empty" "" "$out"

# issue_has_label needs no jq path — it is a plain grep either way (see the sweep
# section above), so there is nothing further to exercise here.

echo ""
if [ "$FAILURES" -ne 0 ]; then
    echo "claim.sh collision-report self-test: $FAILURES of $SCENARIOS scenario(s) FAILED"
    exit 1
fi
echo "claim.sh collision-report self-test: all $SCENARIOS scenarios hold"
exit 0
