#!/usr/bin/env bash
# Mutation test for check-hygiene-checkout-guard.py.
#
# A gate is only worth its runtime if it goes RED on a broken tree. The vendored
# download gate shipped PASS/PASS/rc=0 over a fully vulnerable tree whose only
# hardening was three comment lines — found by RUNNING it, not by reading it. So
# every case below writes a workflow, runs the real gate against it, and asserts
# the exit code.
#
# Two things this suite is careful about, both learned from sibling suites:
#
#   - Every fixture carries a FULL-SIZE job (24 correct gates). A three-step
#     fixture would be refused by the parser floor, and "refused" would then say
#     nothing about the mutation under test — the hollow-assertion shape.
#   - The three near-miss guards each claim to be caught by ONE contract row,
#     and section 4 MEASURES that claim by deleting the row and asserting the
#     fixture flips to green. A claim of "this row is load-bearing" that no test
#     can falsify is prose.
#
# Usage: bash .claude/scripts/test-check-hygiene-checkout-guard.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$ROOT/.claude/scripts/check-hygiene-checkout-guard.py"
GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; OFF=$'\033[0m'

pass=0; fail=0
TMPROOT="$(mktemp -d)"
trap 'rm -rf "$TMPROOT"' EXIT

GUARD="always() && steps.checkout.outcome == 'success'"

# new_wf — a fresh workflow path under its own temp dir.
new_wf() {
    local d; d="$(mktemp -d "$TMPROOT/wf.XXXXXX")"
    mkdir -p "$d"
    printf '%s/ci.yml' "$d"
}

# write_job <file> <checkout-id|-> [extra-if-expr ...]
# Emits a repo-hygiene job with 24 correctly guarded gates, then one extra gate
# per trailing argument. 24 clears the gate's parser floor, so a refusal in the
# cases below is always about the mutation and never about the job being small.
write_job() {
    local file="$1" ckid="$2"; shift 2
    {
        printf 'name: t\non:\n  push:\n\njobs:\n'
        printf '  repo-hygiene:\n    runs-on: ubuntu-latest\n    timeout-minutes: 10\n    steps:\n'
        printf '      - uses: actions/checkout@v7\n'
        [ "$ckid" = "-" ] || printf '        id: %s\n' "$ckid"
        local i=1
        while [ "$i" -le 24 ]; do
            printf '\n      # a gate\n      - name: gate %d\n        if: %s\n        shell: bash\n        run: bash .claude/scripts/g%d.sh\n' \
                "$i" "$GUARD" "$i"
            i=$((i + 1))
        done
        local expr
        for expr in "$@"; do
            printf '\n      - name: mutant\n        if: %s\n        shell: bash\n        run: bash .claude/scripts/m.sh\n' "$expr"
        done
    } > "$file"
}

# check <expected-rc> <label> <workflow-file>
check() {
    local want="$1" label="$2" wf="$3" got out
    set +e
    out="$(python3 "$GATE" "$wf" repo-hygiene 2>&1)"
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

echo "Mutation-testing check-hygiene-checkout-guard.py"
echo

# ── 1. the real repo, and the canonical shape in isolation ────────────────────
set +e
out="$(python3 "$GATE" 2>&1)"; got=$?
set -e
if [ "$got" = 0 ]; then
    printf '%s  ✓%s the repo'"'"'s own ci.yml passes (rc=0)\n' "$GREEN" "$OFF"
    pass=$((pass + 1))
else
    printf '%s  ✗%s the repo'"'"'s own ci.yml — expected rc=0, got rc=%s\n' "$RED" "$OFF" "$got"
    printf '%s\n' "$out" | sed 's/^/        /'
    fail=$((fail + 1))
fi

w="$(new_wf)"; write_job "$w" checkout
check 0 "canonical guard alone" "$w"

# ── 2. the mutants that MUST be refused ──────────────────────────────────────

# The measured bug (run 31516160366): a bare always() runs against an empty
# working directory and reports a gate failure that measured nothing.
w="$(new_wf)"; write_job "$w" checkout "always()"
check 1 "bare always() (the lost-checkout bug)" "$w"

# The regression the fix must not introduce. success() is false the moment any
# earlier gate fails, so this restores the fix-them-one-at-a-time behaviour that
# always() is in this job to prevent.
w="$(new_wf)"; write_job "$w" checkout "success() && steps.checkout.outcome == 'success'"
check 1 "success() instead of always() (gates stop at the first failure)" "$w"

# Denylist instead of allowlist: right for every outcome it names, wrong for the
# one it cannot — a steps context that has no entry for the step at all.
w="$(new_wf)"; write_job "$w" checkout \
    "always() && steps.checkout.outcome != 'cancelled' && steps.checkout.outcome != 'failure' && steps.checkout.outcome != 'skipped'"
check 1 "denylist of outcomes (blind to a null outcome)" "$w"

# `||` binds the guard to nothing: always() is true, so the whole expression is.
w="$(new_wf)"; write_job "$w" checkout "always() || steps.checkout.outcome == 'success'"
check 1 "|| instead of && (guard is dead weight)" "$w"

# The FALSE GREEN, and the worst outcome of the lot. With no `id:` on the
# checkout, steps.checkout.outcome is null under every state, so all 24 gates
# skip and the job reports success having measured nothing. Semantic evaluation
# alone would bless this: "skips when the checkout is lost" is satisfied
# vacuously when it skips always.
w="$(new_wf)"; write_job "$w" -
check 1 "checkout has no id: (every gate skips, job goes green)" "$w"

# Same shape, one rename away: the id exists but the expressions name another.
w="$(new_wf)"; write_job "$w" scm
check 1 "guard names an id no step declares" "$w"

# A step guarded on an id that only appears LATER cannot see it either.
w="$(new_wf)"; write_job "$w" checkout "always() && steps.upload.outcome == 'success'"
check 1 "guard names an id declared by no earlier step" "$w"

# One unguarded gate among 24 correct ones. This is how the bug comes back:
# nobody rewrites 44 steps, someone appends a 45th and copies the wrong
# neighbour.
w="$(new_wf)"; write_job "$w" checkout
{ printf '\n      - name: newly added gate\n        if: always()\n        shell: bash\n        run: bash .claude/scripts/new.sh\n'; } >> "$w"
check 1 "a single unguarded step appended to a correct job" "$w"

# ── 3. shapes that must NOT be refused ───────────────────────────────────────
# A gate that blocks a legitimate rewrite gets switched off, so the accepting
# cases are as load-bearing as the refusing ones.

w="$(new_wf)"; write_job "$w" checkout "always() && !(steps.checkout.outcome != 'success')"
check 0 "semantically equivalent rewrite with ! and parens" "$w"

# No step in this job sets continue-on-error, so `conclusion` is the same value.
w="$(new_wf)"; write_job "$w" checkout "always() && steps.checkout.conclusion == 'success'"
check 0 "conclusion instead of outcome" "$w"

w="$(new_wf)"; write_job "$w" checkout "\${{ always() && steps.checkout.outcome == 'success' }}"
check 0 "\${{ }}-wrapped expression" "$w"

w="$(new_wf)"; write_job "$w" checkout "\"always() && steps.checkout.outcome == 'success'\""
check 0 "double-quoted scalar" "$w"

w="$(new_wf)"; write_job "$w" checkout "steps.checkout.outcome == 'success' && always()"
check 0 "operands in the other order" "$w"

# ── 4. discovery must be falsifiable, not merely thorough ────────────────────
# The gate reads raw text, i.e. it makes claims about FORMATTING. Each of these
# is a job the parser cannot read correctly; every one must fail rather than
# report "all guarded" over a step it never saw.

# A folded `if:` is valid YAML and unreadable to a line-oriented parser.
w="$(new_wf)"; write_job "$w" checkout
{ printf '\n      - name: folded\n        if: >-\n          always()\n        shell: bash\n        run: bash .claude/scripts/f.sh\n'; } >> "$w"
check 1 "block-scalar if: is refused, not skipped" "$w"

# An `if:` at an indent the step parser does not claim. Before the
# unattributed-if check, this printed "all guarded" over an unguarded gate.
w="$(new_wf)"; write_job "$w" checkout
{ printf '\n      - name: oddly indented\n        shell: bash\n          if: always()\n        run: bash .claude/scripts/o.sh\n'; } >> "$w"
check 1 "an if: the step parser could not attribute" "$w"

# "Found nothing" must never read as "found nothing wrong".
w="$(new_wf)"
{ printf 'name: t\non:\n  push:\n\njobs:\n  repo-hygiene:\n    runs-on: ubuntu-latest\n    steps:\n'
  printf '      - uses: actions/checkout@v7\n        id: checkout\n'
  printf '      - name: only gate\n        if: %s\n        run: bash x.sh\n' "$GUARD"; } > "$w"
check 1 "a job below the parser floor is a broken parse, not a clean tree" "$w"

# A job that is not there at all must fail loudly rather than pass vacuously.
w="$(new_wf)"; write_job "$w" checkout
set +e
python3 "$GATE" "$w" no-such-job >/dev/null 2>&1; got=$?
set -e
if [ "$got" = 1 ]; then
    printf '%s  ✓%s a missing job is a failure, not a vacuous pass (rc=1)\n' "$GREEN" "$OFF"
    pass=$((pass + 1))
else
    printf '%s  ✗%s a missing job — expected rc=1, got rc=%s\n' "$RED" "$OFF" "$got"
    fail=$((fail + 1))
fi

# ── 5. every contract row is load-bearing, measured ──────────────────────────
# Sections 2 and 3 prove the mutants are refused. They do NOT prove WHICH
# assertion refuses them, and a row nothing uniquely exercises can be deleted
# with the suite still green — the shape that let a sibling suite keep a case
# whose stated claim was no longer the reason it went red. So: delete one CASES
# row, run a guard built to be wrong in exactly that one state, and require it
# to turn GREEN. If it stays red, some other row was doing the work.
#
# This section has already earned its keep. The gate first claimed the
# 'cancelled' row was the only one refusing a bare `always()`; the first run of
# this harness refused that fixture with the row deleted, because a bare
# `always()` is wrong in all four checkout-lost states at once. The pairings
# below use widened-ALLOWLIST near-misses instead — `outcome == 'success' ||
# outcome == '<one other>'` is wrong in exactly one state, which is what makes
# the isolation real rather than plausible.
MUT="$TMPROOT/mutate.py"
cat > "$MUT" <<'PY'
import importlib.util, sys
gate, drop, workflow = sys.argv[1], sys.argv[2], sys.argv[3]
spec = importlib.util.spec_from_file_location("gate", gate)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
kept = [c for c in mod.CASES if c[0] != drop]
if len(kept) == len(mod.CASES):
    print(f"no CASES row named {drop!r} — the pairing has drifted", file=sys.stderr)
    sys.exit(2)
mod.CASES = kept
sys.argv = ["gate", workflow, "repo-hygiene"]
sys.exit(mod.main())
PY

# mutation_check <label> <case-row-name> <if-expr>
mutation_check() {
    local label="$1" row="$2" expr="$3" wf got
    wf="$(new_wf)"; write_job "$wf" checkout "$expr"
    set +e
    python3 "$MUT" "$GATE" "$row" "$wf" >/dev/null 2>&1
    got=$?
    set -e
    if [ "$got" = 0 ]; then
        printf '%s  ✓%s dropping '"'"'%s'"'"' lets %s through — the row is load-bearing\n' \
            "$GREEN" "$OFF" "$row" "$label"
        pass=$((pass + 1))
    else
        printf '%s  ✗%s dropping '"'"'%s'"'"' still refuses %s (rc=%s) — another row is doing the work\n' \
            "$RED" "$OFF" "$row" "$label" "$got"
        fail=$((fail + 1))
    fi
}

# The two run-anyway rows. Each fixture runs in every state the guard should
# run in, except the one its row names.
mutation_check "a success()-based guard" \
    "checkout ok, an earlier gate failed" \
    "success() && steps.checkout.outcome == 'success'"
mutation_check "a guard that bails out of a cancelled run" \
    "checkout ok, the run was cancelled" \
    "always() && !cancelled() && steps.checkout.outcome == 'success'"

# The four checkout-lost rows. A widened allowlist re-admits exactly one lost
# state, so each of these is wrong in one state and right in the other six.
mutation_check "an allowlist that re-admits a cancelled checkout" \
    "checkout cancelled by the job timeout" \
    "steps.checkout.outcome == 'success' || steps.checkout.outcome == 'cancelled'"
mutation_check "an allowlist that re-admits a failed checkout" \
    "checkout failed outright" \
    "steps.checkout.outcome == 'success' || steps.checkout.outcome == 'failure'"
mutation_check "an allowlist that re-admits a skipped checkout" \
    "checkout was skipped" \
    "steps.checkout.outcome == 'success' || steps.checkout.outcome == 'skipped'"
mutation_check "the outcome denylist" \
    "checkout absent from the steps context" \
    "always() && steps.checkout.outcome != 'cancelled' && steps.checkout.outcome != 'failure' && steps.checkout.outcome != 'skipped'"

echo
if [ "$fail" -eq 0 ]; then
    printf '%s  ✓ %d/%d assertions pass%s\n' "$GREEN" "$pass" "$((pass + fail))" "$OFF"
    exit 0
fi
printf '%s  ✗ %d of %d assertions FAILED%s\n' "$RED" "$fail" "$((pass + fail))" "$OFF"
exit 1
