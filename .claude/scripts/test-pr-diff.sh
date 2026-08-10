#!/usr/bin/env bash
# Hermetic self-test for pr-diff.sh — real git repositories, no network.
#
# What is at stake: this script decides WHAT the four PR reviewers read. Get it
# wrong and the failure is not a missing review, it is a confident review of
# someone else's work — measured on run 31370116851, where a shallow graft made
# the old inline code fall back to a two-dot diff and the reviewers filed two
# blocking errors against files the PR never touched.
#
# So the suite builds the exact topology that produced that bug (base advanced
# after the branch point, then a `--depth=1` fetch), and asserts BOTH
# directions: the fixed script must produce the branch's changes only, and a
# mutant carrying the old two-dot fallback must produce the poisoned diff. If
# the mutant passed too, the assertion above would be measuring nothing.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PR_DIFF="$SCRIPT_DIR/pr-diff.sh"
WORKFLOW="$(cd "$SCRIPT_DIR/../.." && pwd)/.github/workflows/pr-review.yml"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# `core.hooksPath=/dev/null` is not decoration: this machine installs a global
# pre-commit hook, and a fixture repo that silently fails to commit produces an
# EMPTY clone — every assertion below would then be measuring a missing remote
# rather than the shallow-graft behaviour it claims to measure.
git_quiet() {
    git -c init.defaultBranch=main -c core.hooksPath=/dev/null \
        -c user.email=test@example.com -c user.name=Test -c commit.gpgsign=false \
        "$@" >/dev/null 2>&1
}

# ── the topology that produced the bug ────────────────────────────────────────
# upstream: C0 ──── C1 (touches OTHER.md, merged after the branch point)
#                └─ feature (touches MINE.md)
build_repos() {
    local root="$1"
    rm -rf "$root"
    mkdir -p "$root/upstream"
    (
        cd "$root/upstream" || exit 1
        git_quiet init
        echo "base" > OTHER.md
        echo "base" > MINE.md
        git_quiet add -A && git_quiet commit -m C0
        git_quiet checkout -b feature
        echo "the change this PR ships" > MINE.md
        git_quiet add -A && git_quiet commit -m "feature work"
        git_quiet checkout main
        echo "a revert that landed on main after the branch point" > OTHER.md
        git_quiet add -A && git_quiet commit -m C1
    )
    git_quiet clone "$root/upstream" "$root/work"
    # A fixture that did not build is the worst possible pass: the script would
    # then be exercised against a repo with no remote and no history, and every
    # verdict below would be about that instead of about the graft.
    if ! git -C "$root/work" rev-parse --verify --quiet origin/main >/dev/null 2>&1; then
        bad "fixture $root did not build (no origin/main) — the assertions that follow would be meaningless"
        return 1
    fi
    (
        cd "$root/work" || exit 1
        git_quiet checkout feature
        # THE POISON: pr-review.yml's own self-modification step used to run
        # this on the fetch-depth: 0 checkout. It grafts a .git/shallow and
        # `origin/main...HEAD` stops resolving.
        git_quiet fetch origin main --depth=1
    )
}

# Sets RC in THIS shell and leaves the combined output in $OUT_FILE. Not a
# command substitution: `OUT="$(run_pr_diff …)"` would capture the exit status
# of the subshell's last command and hand every assertion below a stale RC.
OUT_FILE="$TMP/out.txt"
run_pr_diff() {  # <script> <repo>
    local script="$1" repo="$2"
    ( cd "$repo" && bash "$script" --base main --out "$repo/pr.diff" ) > "$OUT_FILE" 2>&1
    RC=$?
    OUT="$(cat "$OUT_FILE")"
}

echo "── the shallow graft that broke run 31370116851 ──"
build_repos "$TMP/case1"
run_pr_diff "$PR_DIFF" "$TMP/case1/work"
RC1=$RC
if [ "$RC1" -eq 0 ]; then
    ok "pr-diff.sh recovers from the shallow graft instead of giving up"
else
    bad "pr-diff.sh exited $RC1 on a repairable shallow clone: $OUT"
fi
if grep -q "MINE.md" "$TMP/case1/work/pr.diff" 2>/dev/null; then
    ok "the diff contains the branch's own change"
else
    bad "the diff is missing the branch's own change — the reviewers would read the wrong PR"
fi
if grep -q "OTHER.md" "$TMP/case1/work/pr.diff" 2>/dev/null; then
    bad "the diff contains a file only the BASE changed — this is the two-dot bug, reversed and blamed on the author"
else
    ok "the diff excludes what the base gained since the branch point"
fi
case "$OUT" in
    *"Truncated history"*) ok "it says out loud that it deepened a shallow clone" ;;
    *) bad "a silent repair: nothing in the log explains why the clone had to be deepened" ;;
esac

echo "── MUTANT: the old two-dot fallback ──"
# Exactly what pr-review.yml did before this script existed. It must produce
# the poisoned diff — otherwise the three assertions above prove nothing.
MUTANT="$TMP/mutant-twodot.sh"
sed 's|^    exit 3$|    git diff "origin/$BASE" HEAD > "$OUT"; echo "base_sha=x"; exit 0|' "$PR_DIFF" > "$MUTANT"
build_repos "$TMP/case2"
# Deny the repair too, so the mutant reaches its fallback the way the old code
# did: no network beyond the local remote is involved, so drop the remote.
( cd "$TMP/case2/work" && git_quiet remote set-url origin "$TMP/does-not-exist" )
run_pr_diff "$MUTANT" "$TMP/case2/work"
if [ "$RC" -eq 0 ] && grep -q "OTHER.md" "$TMP/case2/work/pr.diff" 2>/dev/null; then
    ok "the two-dot mutant DOES report the base's own commit as the PR's work"
else
    bad "the two-dot mutant produced no poisoned diff (rc=$RC) — the assertions above are hollow"
fi

echo "── refusing, when the merge base is genuinely unreachable ──"
build_repos "$TMP/case3"
( cd "$TMP/case3/work" && git_quiet remote set-url origin "$TMP/does-not-exist" )
run_pr_diff "$PR_DIFF" "$TMP/case3/work"
if [ "$RC" -eq 3 ]; then
    ok "an unrepairable shallow clone exits 3 — no review rather than a wrong one"
else
    bad "expected exit 3 when the merge base cannot be recovered, got $RC"
fi
case "$OUT" in
    *"CI problem, not a finding about the PR"*) ok "the refusal names itself as a CI problem, not as the author's fault" ;;
    *) bad "the refusal does not say whose problem it is: $OUT" ;;
esac

echo "── the other two failure modes ──"
build_repos "$TMP/case4"
run_pr_diff "$PR_DIFF" "$TMP/case4/work"  # warm-up, repairs the graft
( cd "$TMP/case4/work" && git_quiet checkout main )
run_pr_diff "$PR_DIFF" "$TMP/case4/work"
if [ "$RC" -eq 4 ]; then
    ok "an empty diff exits 4 instead of collecting a PASS nobody earned"
else
    bad "expected exit 4 on an empty diff, got $RC: $OUT"
fi
OUT="$( cd "$TMP/case4/work" && bash "$PR_DIFF" --base no-such-branch --out "$TMP/x.diff" 2>&1 )"
RC=$?
if [ "$RC" -eq 3 ]; then
    ok "an unresolvable base ref exits 3"
else
    bad "expected exit 3 on an unresolvable base ref, got $RC: $OUT"
fi
OUT="$( bash "$PR_DIFF" --base main 2>&1 )"; RC=$?
if [ "$RC" -eq 2 ]; then ok "missing --out is a usage error, not a silent success"; else bad "expected exit 2 on bad usage, got $RC"; fi

# ── the wiring: no depth-limited fetch may re-poison the job ──────────────────
# `pr-diff.sh` repairs a shallow graft, but the repair costs a full fetch on
# every run and only ever exists because something upstream created the graft.
# The rule is that nothing in that workflow fetches with `--depth`.
#
# Discovery FIRST, assertion second: a grep that finds zero `git fetch` lines
# would "pass" a workflow that had stopped fetching at all, which is #3050's
# lesson. Count them, require the count to be real, then assert on the flag.
DEPTH_TOTAL=0
depth_fetch_verdict() {  # <workflow-file> -> 0 clean · 1 carries --depth · 2 discovered nothing
    local file="$1" depth
    DEPTH_TOTAL="$(grep -cE '^[[:space:]]*git fetch ' "$file" 2>/dev/null || true)"
    depth="$(grep -cE '^[[:space:]]*git fetch .*--depth' "$file" 2>/dev/null || true)"
    [ "${DEPTH_TOTAL:-0}" -ge 1 ] || return 2
    [ "${depth:-0}" -eq 0 ] || return 1
    return 0
}

echo "── pr-review.yml must not shallow its own checkout ──"
if [ ! -f "$WORKFLOW" ]; then
    bad "pr-review.yml not found at $WORKFLOW — the wiring check silently checked nothing"
else
    depth_fetch_verdict "$WORKFLOW"
    case $? in
        0) ok "pr-review.yml: all $DEPTH_TOTAL 'git fetch' lines are full-depth" ;;
        1) bad "pr-review.yml carries a '--depth' fetch — it grafts .git/shallow onto the fetch-depth: 0 checkout and breaks the merge base the reviewers' diff needs (run 31370116851)" ;;
        2) bad "no 'git fetch' line found in pr-review.yml — this check discovered nothing and must not report a pass" ;;
    esac

    # MUTANT: put one `--depth=1` back. The check must reject it, or it is prose.
    MUTATED="$TMP/pr-review-mutated.yml"
    # awk, not `sed '0,/re/'`: that address form is GNU-only and this suite runs
    # on both the macOS dev machine and the Linux runner.
    awk 'BEGIN{d=0} /^[[:space:]]*git fetch origin /&&d==0{sub(/git fetch /,"git fetch --depth=1 ");d=1} {print}' "$WORKFLOW" > "$MUTATED"
    if ! grep -qE '^[[:space:]]*git fetch .*--depth' "$MUTATED"; then
        bad "could not build the --depth mutant — the wiring check above is untested"
    else
        depth_fetch_verdict "$MUTATED"
        if [ $? -eq 1 ]; then
            ok "MUTANT(--depth=1 restored) is rejected — the check reads the flag, not the line count"
        else
            bad "the wiring check passed a workflow carrying --depth=1 — it is not checking anything"
        fi
    fi
fi

echo ""
echo "test-pr-diff: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
