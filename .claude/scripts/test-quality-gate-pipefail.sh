#!/usr/bin/env bash
# test-quality-gate-pipefail.sh — pin the "silent exit 1" class in quality-gate.sh.
#
# THE DEFECT (measured 2026-08-12, on the KDoc-only follow-up to #3129)
#
#   quality-gate.sh runs under `set -euo pipefail`. Its cross-platform section had:
#
#       NEW_PUBLIC=$(git diff HEAD -- sceneview/src/ … | grep "^+.*fun …" | wc -l | tr -d ' ')
#
#   A grep that matches nothing exits 1. `pipefail` promotes that 1 to the whole
#   pipeline, the command substitution inherits it, and `set -e` kills the script
#   ON THAT LINE — before the Summary block that prints the verdict. So the gate
#   exited 1 having printed no `[FAIL]`, no count, no summary. `pre-push-check.sh`
#   could only say "the checker exited 1 without reaching its verdict", which is
#   indistinguishable from a real blocker.
#
#   The trigger is the SAFEST possible input: a diff that touches `sceneview/src/`
#   and adds no public API line — a KDoc-only or comment-only edit. And CI never
#   saw it: there `git diff HEAD` is empty, so the `[ -n "$CHANGED_ANDROID" ]`
#   guard is false and the block is skipped entirely. Local-only, exactly like the
#   `as_count` defect this file sits next to.
#
# WHAT IS PINNED
#
#   1. The shell semantics, measured rather than asserted from memory: the
#      un-neutralised shape really does abort under `set -euo pipefail`, and the
#      neutralised shape really does survive AND still counts correctly. Without
#      this half, the static rule below would be unanchored style policing.
#   2. A static rule over the real quality-gate.sh: no command substitution may
#      pipe through `grep` without neutralising its exit status. Proven falsifiable
#      against a fixture carrying the exact pre-fix line — if the detector cannot
#      flag that, it is not measuring anything.
#
# Hermetic: pure shell + a temp fixture. No repo state, no network, no Gradle.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GATE="$REPO_ROOT/.claude/scripts/quality-gate.sh"

FAILURES=0

expect() { # $1 = label, $2 = expected, $3 = actual
    if [ "$2" = "$3" ]; then
        echo "[PASS] $1"
    else
        echo "[FAIL] $1 — expected '$2', got '$3'"
        FAILURES=$((FAILURES + 1))
    fi
}

# ── 1. The shell semantics the whole file rests on ───────────────────────────
#
# Run each shape in a child bash with the SAME options quality-gate.sh sets, and
# report whether the script survived to its next statement. `echo SURVIVED` is
# the only evidence that matters: a non-zero exit alone would not tell us the
# script died *at the assignment* rather than at the echo.

run_shape() { # $1 = the assignment line under test; prints SURVIVED or DIED
    local out
    out=$(bash -c "
        set -euo pipefail
        $1
        echo SURVIVED:\$N
    " 2>/dev/null)
    if [ -z "$out" ]; then printf 'DIED'; else printf '%s' "$out"; fi
}

expect "the pre-fix shape dies on a no-match, printing nothing" \
    "DIED" \
    "$(run_shape 'N=$(printf "no match here\n" | grep "^+.*fun " | wc -l | tr -d " ")')"

expect "the pre-fix shape survives when the grep DOES match" \
    "SURVIVED:1" \
    "$(run_shape 'N=$(printf "+    fun foo()\n" | grep "^+.*fun " | wc -l | tr -d " ")')"

# ^ the two above together are the reason this was invisible for so long: the
#   line works perfectly on every diff that adds an API, and only ever dies on
#   the diffs that add none.

expect "the fixed shape survives a no-match and counts 0" \
    "SURVIVED:0" \
    "$(run_shape 'N=$( { printf "no match here\n" || true; } | { grep -c "^+.*fun " || true; }); N=${N%%$'"'"'\n'"'"'*}')"

expect "the fixed shape still counts a real match" \
    "SURVIVED:2" \
    "$(run_shape 'N=$( { printf "+  fun a()\n+  fun b()\n" || true; } | { grep -c "^+.*fun " || true; }); N=${N%%$'"'"'\n'"'"'*}')"

# ── 2. The static rule over the real file ────────────────────────────────────
#
# Any line that opens a command substitution and pipes through grep must
# neutralise grep's exit status somewhere on that line. `||` is the neutraliser,
# in either of the two forms the file already uses — `|| true` inside the
# substitution, or a trailing `|| echo "MISSING"` on the whole pipeline (which
# also swallows the pipefail status, and additionally supplies a value the
# comparison below can report). Both are accepted; the absence of any `||` is not.
#
# Comment lines are excluded — the prose above each fix quotes the defective shape
# verbatim, and a recogniser that cannot tell prose from code turns documentation
# into a failure (the #3121 lesson, where an identical exclusion had to be made
# load-bearing).
#
# The `||` must also be in the right PLACE. Accepting one anywhere on the line
# (the first cut of this rule, caught in review of #3135) lets an unrelated
# trailing guard vouch for an unguarded grep:
#
#     X=$(git diff … | grep …); [ -z "$X" ] || echo "none"
#      ^ unguarded substitution                 ^ `||` that has nothing to do with it
#
# So the search is scoped to the span between the first `grep` and the LAST `)`
# on the line — i.e. inside the command substitution, where a neutraliser has to
# sit to actually neutralise anything. In the counter-example above the
# substitution closes before the `;`, so that span holds no `||` and the line is
# correctly flagged.
#
# Known limit, stated rather than hidden: the rule is line-scoped, so a command
# substitution split across several physical lines is not covered. Every one in
# this file is single-line today, and a line-scoped rule that is honest about its
# reach beats a multi-line parser nobody can falsify.

scan_for_unguarded_grep() { # $1 = file; prints "<lineno>: <line>" per offender
    awk '
        # strip full-line comments; they carry example code on purpose
        /^[[:space:]]*#/ { next }
        !/\$\(/ || !/grep/ { next }
        {
            g = index($0, "grep")
            # last ")" on the line — the closing paren of the outermost
            # substitution, since nothing here trails one
            c = 0
            for (i = length($0); i > 0; i--) {
                if (substr($0, i, 1) == ")") { c = i; break }
            }
            if (c <= g) { printf "%d: %s\n", NR, $0; next }
            span = substr($0, g, c - g + 1)
            if (index(span, "||") == 0) printf "%d: %s\n", NR, $0
        }
    ' "$1"
}

# POSITIVE INDICATOR FIRST — "0 offenders" is only good news if the scan could
# see anything at all. Caught on this very file: run from the wrong directory,
# awk printed `can't open file …/quality-gate.sh` to stderr, produced no lines,
# and the assertion below reported PASS. A probe that reports success when its
# subject is missing is the hollow-assertion defect, and it would have shipped
# inside the suite whose whole job is to stop that shape.
if [ ! -s "$GATE" ]; then
    echo "[FAIL] quality-gate.sh not found or empty at $GATE — the scan below would report 0 offenders having read nothing"
    FAILURES=$((FAILURES + 1))
fi
CANDIDATES=$(grep -c '\$(.*grep' "$GATE" 2>/dev/null || true)
CANDIDATES=${CANDIDATES%%$'\n'*}
CANDIDATES=${CANDIDATES:-0}
if [ "$CANDIDATES" -lt 5 ]; then
    echo "[FAIL] only $CANDIDATES command-substitution-with-grep line(s) found in quality-gate.sh — the detector is not reaching the file it claims to audit (broken probe, not a clean bill of health)"
    FAILURES=$((FAILURES + 1))
else
    echo "[PASS] the detector reaches its subject — $CANDIDATES candidate line(s) scanned"
fi

OFFENDERS="$(scan_for_unguarded_grep "$GATE")"
OFFENDER_COUNT=$(printf '%s' "$OFFENDERS" | grep -c . || true)
OFFENDER_COUNT=${OFFENDER_COUNT%%$'\n'*}
if [ "$OFFENDER_COUNT" != "0" ]; then
    echo "        offending line(s):"
    printf '%s\n' "$OFFENDERS" | sed 's/^/          /'
fi
expect "quality-gate.sh has no command substitution piping through an unguarded grep" \
    "0" "$OFFENDER_COUNT"

# ── 3. The detector is falsifiable ───────────────────────────────────────────
#
# A detector that reports "0 offenders" on a file that HAS one is worth nothing.
# Feed it the exact pre-fix line and require it to be named.

FIXTURE="$(mktemp)"
trap 'rm -f "$FIXTURE"' EXIT
cat > "$FIXTURE" <<'FIX'
#!/usr/bin/env bash
set -euo pipefail
# This comment mentions $(grep …) on purpose and must NOT be flagged.
CHANGED=$(git diff --name-only HEAD 2>/dev/null | grep "^sceneview/src/" || true)
V=$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2 || echo "MISSING")
DECOY=$(git diff --name-only | grep "^docs/"); [ -z "$DECOY" ] || echo "docs changed"
NEW_PUBLIC=$(git diff HEAD -- sceneview/src/ 2>/dev/null | grep "^+.*fun " | wc -l | tr -d ' ')
FIX

FIXTURE_HITS="$(scan_for_unguarded_grep "$FIXTURE")"
FIXTURE_COUNT=$(printf '%s' "$FIXTURE_HITS" | grep -c . || true)
FIXTURE_COUNT=${FIXTURE_COUNT%%$'\n'*}
expect "the detector flags the pre-fix line AND the trailing-'||' decoy, and nothing else" \
    "2" "$FIXTURE_COUNT"
case "$FIXTURE_HITS" in
    *NEW_PUBLIC*) expect "the flagged line is the NEW_PUBLIC assignment" "yes" "yes" ;;
    *)            expect "the flagged line is the NEW_PUBLIC assignment" "yes" "no ($FIXTURE_HITS)" ;;
esac
case "$FIXTURE_HITS" in
    *"must NOT be flagged"*) expect "the prose line is not flagged" "yes" "no" ;;
    *)                       expect "the prose line is not flagged" "yes" "yes" ;;
esac
case "$FIXTURE_HITS" in
    *CHANGED=*) expect "a line guarded by '|| true' is not flagged" "yes" "no" ;;
    *)          expect "a line guarded by '|| true' is not flagged" "yes" "yes" ;;
esac
case "$FIXTURE_HITS" in
    *"V=\$(grep"*) expect "a line guarded by a trailing '|| echo' is not flagged" "yes" "no" ;;
    *)             expect "a line guarded by a trailing '|| echo' is not flagged" "yes" "yes" ;;
esac
# The over-match this rule was narrowed for (#3135 review): an unguarded grep
# inside the substitution, with an unrelated `||` after it closes. Accepting a
# `||` anywhere on the line would have let this through.
case "$FIXTURE_HITS" in
    *DECOY*) expect "a '||' OUTSIDE the substitution does not vouch for the grep inside it" "yes" "yes" ;;
    *)       expect "a '||' OUTSIDE the substitution does not vouch for the grep inside it" "yes" "no" ;;
esac

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "All quality-gate pipefail tests passed"
    exit 0
fi
echo "$FAILURES test(s) failed"
exit 1
