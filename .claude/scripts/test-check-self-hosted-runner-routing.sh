#!/usr/bin/env bash
# Mutation test for check-self-hosted-runner-routing.py.
#
# A gate is only worth its runtime if it goes RED on a broken tree. The vendored
# download gate shipped PASS/PASS/rc=0 over a fully vulnerable tree whose only
# hardening was three comment lines — found by RUNNING it, not by reading it. So
# every case below builds a tree, runs the real gate against it, and asserts the
# exit code. Cases 2..N are all mutants that MUST be refused.
#
# Usage: bash .claude/scripts/test-check-self-hosted-runner-routing.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$ROOT/.claude/scripts/check-self-hosted-runner-routing.py"
GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; OFF=$'\033[0m'

pass=0; fail=0
TMPROOT="$(mktemp -d)"
trap 'rm -rf "$TMPROOT"' EXIT

# Build a throwaway tree holding the gate at the same relative depth it expects
# (<root>/.claude/scripts/), so `parents[2]` resolves to the fake root.
new_tree() {
    local d; d="$(mktemp -d "$TMPROOT/tree.XXXXXX")"
    mkdir -p "$d/.claude/scripts" "$d/.github/workflows"
    cp "$GATE" "$d/.claude/scripts/"
    printf '%s' "$d"
}

# write_job <tree> <file> <job> <runs-on value>
write_job() {
    local tree="$1" file="$2" job="$3" runson="$4"
    {
        printf 'name: t\non:\n  push:\n\njobs:\n'
        printf '  %s:\n' "$job"
        printf '    runs-on: %s\n' "$runson"
        printf '    steps:\n      - run: true\n'
    } > "$tree/.github/workflows/$file"
}

GOOD="\${{ (vars.SELF_HOSTED_MACOS_ONLINE == 'true' && (github.event_name != 'pull_request' && github.event_name != 'pull_request_target' || github.event.pull_request.head.repo.full_name == github.repository)) && 'sceneview-mac' || 'macos-15' }}"

# check <expected-rc> <label> <tree>
check() {
    local want="$1" label="$2" tree="$3" got out
    set +e
    out="$(python3 "$tree/.claude/scripts/check-self-hosted-runner-routing.py" 2>&1)"
    got=$?
    set -e
    if [ "$got" = "$want" ]; then
        printf '%s  ✓%s %s (rc=%s)\n' "$GREEN" "$OFF" "$label" "$got"
        pass=$((pass + 1))
    else
        printf '%s  ✗%s %s — expected rc=%s, got rc=%s\n' "$RED" "$OFF" "$label" "$want" "$got"
        printf '%s\n' "$out" | sed 's/^/        /'
        fail=$((fail + 1))
    fi
}

echo "Mutation-testing check-self-hosted-runner-routing.py"
echo

# --- 1. the real repo must pass, unmodified -----------------------------------
check 0 "the repo's own workflows pass" "$ROOT"

# --- 2. the canonical expression passes in isolation ---------------------------
t="$(new_tree)"; write_job "$t" a.yml build "$GOOD"
check 0 "canonical expression alone" "$t"

# --- 3. the OLD two-term form must be refused ---------------------------------
# This is the regression that motivated the gate: a fork PR would land on the
# persistent Mac.
t="$(new_tree)"
write_job "$t" a.yml build "\${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}"
check 1 "old two-term form (fork PR reaches the Mac)" "$t"

# --- 4. dropping the event_name term must be refused --------------------------
# Silently sends push/dispatch/schedule to the paid hosted runner.
t="$(new_tree)"
write_job "$t" a.yml build "\${{ (vars.SELF_HOSTED_MACOS_ONLINE == 'true' && github.event.pull_request.head.repo.full_name == github.repository) && 'sceneview-mac' || 'macos-15' }}"
check 1 "event_name term dropped (push loses the runner)" "$t"

# --- 5. dropping the head.repo term must be refused ---------------------------
t="$(new_tree)"
write_job "$t" a.yml build "\${{ (vars.SELF_HOSTED_MACOS_ONLINE == 'true' && github.event_name != 'pull_request') && 'sceneview-mac' || 'macos-15' }}"
check 1 "head.repo term dropped (same-repo PR loses the runner)" "$t"

# --- 6. ignoring the heartbeat must be refused --------------------------------
t="$(new_tree)"; write_job "$t" a.yml build "sceneview-mac"
check 1 "hardcoded sceneview-mac (no fallback when asleep)" "$t"

# --- 7. two workflows that disagree must be refused ---------------------------
# The drift this gate exists to catch: one file updated, the other left behind.
#
# The second expression is deliberately SEMANTICALLY EQUIVALENT to $GOOD and
# only textually different (one extra paren pair). An earlier version of this
# case paired $GOOD with the old two-term form — which case 3 already refuses on
# its own merits, so deleting the byte-identical check entirely left this case
# green. Measured: with the equivalent-but-different form, only the drift branch
# can turn it red, so the check is now uniquely exercised.
t="$(new_tree)"
write_job "$t" a.yml build "$GOOD"
write_job "$t" b.yml build "\${{ ((vars.SELF_HOSTED_MACOS_ONLINE == 'true') && (github.event_name != 'pull_request' && github.event_name != 'pull_request_target' || github.event.pull_request.head.repo.full_name == github.repository)) && 'sceneview-mac' || 'macos-15' }}"
check 1 "two workflows carrying different expressions" "$t"

# --- 8. discovery reaches a workflow the author never listed ------------------
# A hardcoded file list would report green here. The good file is checked first
# alphabetically, so a gate that stops at the first job would also miss this.
t="$(new_tree)"
write_job "$t" aaa-good.yml build "$GOOD"
write_job "$t" zzz-newly-opted-in.yml build "sceneview-mac"
check 1 "a 4th workflow opted in with a bad expression" "$t"

# --- 9. a function-call rewrite is refused, by construction -------------------
# NOTE THE LABEL. This case used to claim it proved a prefix-named fork
# (`sceneview/sceneview-evil`) slips past `startsWith()`. It does not: the
# evaluator has no `,` token, so ANY function form dies in the tokenizer before
# a single comparison runs. That is fail-closed and safe, but "refused as
# unparseable" is not "refused as wrong", and stating the stronger claim would
# be exactly the hollow assertion this suite exists to prevent.
t="$(new_tree)"
write_job "$t" a.yml build "\${{ (vars.SELF_HOSTED_MACOS_ONLINE == 'true' && (github.event_name != 'pull_request' || startsWith(github.event.pull_request.head.repo.full_name, github.repository))) && 'sceneview-mac' || 'macos-15' }}"
check 1 "a function-call rewrite is unparseable, so refused" "$t"

# --- 10. a tree with no self-hosted job at all is not a failure ---------------
# Opting every workflow back out is a legitimate state (runner decommissioned).
# Note this tree mentions the label NOWHERE — that is what makes it legitimate,
# and case 12 is the counterpart where the label IS present but undiscovered.
t="$(new_tree)"; write_job "$t" a.yml build "macos-15"
check 0 "no self-hosted job at all (opt-out is legitimate)" "$t"

# --- 11. a non-expression runs-on that mentions the label must be refused -----
t="$(new_tree)"
write_job "$t" a.yml build "[self-hosted, sceneview-mac]"
check 1 "label array instead of the fallback expression" "$t"

# --- 12. a reformat that hides the opt-in from discovery must be refused ------
# The gate's discovery is a set of regexes, i.e. a claim about FORMATTING. Each
# of these is valid YAML that pins a job to the persistent Mac with no heartbeat
# fallback and no fork clause, while matching none of those regexes. Before the
# unattributed-mention check, all three printed "no job routes to sceneview-mac"
# and exited 0 — the gate blessing the very leak it exists to stop. The 200+
# character expression makes a fold or a matrix extraction a realistic edit, not
# a contrived one.
t="$(new_tree)"
{ printf 'name: t\non:\n  pull_request:\n\njobs:\n  build:\n    runs-on: >-\n'
  printf '      ${{ vars.SELF_HOSTED_MACOS_ONLINE == '"'"'true'"'"' && '"'"'sceneview-mac'"'"' || '"'"'macos-15'"'"' }}\n'
  printf '    steps:\n      - run: true\n'; } > "$t/.github/workflows/folded.yml"
check 1 "folded scalar hides the old two-term form from discovery" "$t"

t="$(new_tree)"
{ printf 'name: t\non:\n  pull_request:\n\njobs:\n  build:\n    runs-on:\n      - self-hosted\n      - sceneview-mac\n'
  printf '    steps:\n      - run: true\n'; } > "$t/.github/workflows/blockseq.yml"
check 1 "block-sequence label list pinned to the Mac" "$t"

t="$(new_tree)"
{ printf 'name: t\non:\n  pull_request:\n\njobs:\n  build:\n    strategy:\n      matrix:\n        runner: [ubuntu-latest, sceneview-mac]\n'
  printf '    runs-on: ${{ matrix.runner }}\n    steps:\n      - run: true\n'; } > "$t/.github/workflows/matrix.yml"
check 1 "matrix indirection reaches the Mac unguarded" "$t"

# --- 13. a comment naming the runner is not an opt-in ------------------------
# The counterpart to case 12: the unattributed-mention check must not fire on
# prose. Several real workflows (rn-ios-compile.yml, app-store-screenshots.yml)
# explain in comments why they are NOT opted in — if those tripped the gate, it
# would be red on main and get switched off.
t="$(new_tree)"
{ printf 'name: t\non:\n  push:\n\njobs:\n  build:\n'
  printf '    # deliberately NOT on sceneview-mac: npm ci writes shared caches\n'
  printf '    runs-on: macos-15\n    steps:\n      - run: true\n'; } > "$t/.github/workflows/prose.yml"
check 0 "a comment naming the runner is not an opt-in" "$t"

echo
if [ "$fail" -eq 0 ]; then
    printf '%s  ✓ %d/%d assertions pass%s\n' "$GREEN" "$pass" "$((pass + fail))" "$OFF"
    exit 0
fi
printf '%s  ✗ %d of %d assertions FAILED%s\n' "$RED" "$fail" "$((pass + fail))" "$OFF"
exit 1
