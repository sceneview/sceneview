#!/usr/bin/env bash
# test-release-checklist-version-checks.sh
#
# Guards the doc-version checks in `release-checklist.sh` §4 against the two
# ways they can lie:
#
#   FALSE RED  — grading an absent coordinate as a blocker. CLAUDE.md carries
#                no `io.github.sceneview:sceneview:` line (the file is held
#                short by test-context-budget.sh), so `MISSING` there is the
#                normal state, and sync-versions.sh — the single source of
#                truth for every version location — grades it SKIP. The
#                checklist graded the same input FAIL and produced a release
#                blocker nobody could clear. Measured on 4.29.0.
#   FALSE GREEN — "fixing" that by making the check inert. A CLAUDE.md that
#                does carry a stale coordinate must still FAIL, and README.md
#                — the file a user copies the dependency line from — must stay
#                a hard blocker whether its coordinate is stale OR absent.
#
# It runs the block AS SHIPPED: the code under test is extracted from
# release-checklist.sh at run time, never re-typed here, so a future edit to
# the script is what this suite grades. Same technique as
# test-dispatch-config-restore.sh and test-selfmod-guard.sh.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHECKLIST="$REPO_ROOT/.claude/scripts/release-checklist.sh"

PASS=0
FAIL=0
ok()  { printf '  \033[0;32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  \033[0;31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "=== release-checklist.sh §4 doc-version checks ==="

[ -f "$CHECKLIST" ] || { bad "release-checklist.sh not found at $CHECKLIST"; exit 1; }

# ─── Extract the two blocks as shipped ────────────────────────────────────
# Anchored on the assignment line, which is also what a rename would move, so
# a refactor that renames the variable fails loudly here instead of silently
# testing nothing. Patterns are literals inside the sed calls, never passed
# through `awk -v`: that flag runs its value through escape processing first,
# so `\$\(` reaches the regex engine as `$(` and awk dies on it.

for var in CLAUDE_V README_V; do
    n=$(grep -c "^${var}=\$(grep" "$CHECKLIST")
    if [ "$n" -eq 1 ]; then
        ok "exactly one ${var} assignment in the shipped script"
    else
        bad "expected exactly one ${var} assignment, found $n — extraction is ambiguous"
        exit 1
    fi
done

sed -n '/^CLAUDE_V=/,/^fi$/p'                     "$CHECKLIST" > "$TMP/claude-block.sh"
sed -n '/^README_V=/,/check "README.md" "FAIL"/p' "$CHECKLIST" > "$TMP/readme-block.sh"

# A sed range whose end pattern never matches silently runs to EOF, which would
# hand the harness the whole rest of the script — Gradle calls included. Bound
# it: both blocks are a handful of lines plus their comment.
for b in claude readme; do
    lines=$(wc -l < "$TMP/$b-block.sh" | tr -d " ")
    [ "$lines" -gt 0 ] && [ "$lines" -le 30 ] \
        && ok "extracted $b block is $lines lines (bounded)" \
        || { bad "extracted $b block is $lines lines — the sed range did not terminate"; exit 1; }
done

grep -q 'check "CLAUDE.md"' "$TMP/claude-block.sh" \
    && ok "extracted CLAUDE.md block still contains its check call" \
    || { bad "extraction produced a CLAUDE.md block with no check call"; exit 1; }
grep -q 'check "README.md"' "$TMP/readme-block.sh" \
    && ok "extracted README.md block still contains its check call" \
    || { bad "extraction produced a README.md block with no check call"; exit 1; }

# ─── Harness: run a block against a fixture, report what `check` was told ──
# Prints the status the block passed to `check`, or SKIPPED if it never called
# it. Only stdout is the verdict, so a stray message cannot be mistaken for one.
run_block() { # $1 = block file, $2 = fixture dir, $3 = target version
    (
        cd "$2" || exit 1
        set -uo pipefail
        # Both are consumed by the sourced block, not by this function.
        # shellcheck disable=SC2034
        TARGET_VERSION="$3"
        check() { echo "$2"; }   # $1=name $2=status $3=detail
        # shellcheck disable=SC1090
        called=$(source "$1" 2>/dev/null)
        [ -n "$called" ] && echo "$called" || echo "SKIPPED"
    )
}

FIX="$TMP/fixtures"
mkdir -p "$FIX/absent" "$FIX/current" "$FIX/stale"

# absent — today's real CLAUDE.md: prose only, no Maven coordinate anywhere
printf '# SceneView — Claude Code guide\n\nNo dependency snippet lives here.\n' > "$FIX/absent/CLAUDE.md"
printf '# SceneView\n\nNo dependency snippet here either.\n'                   > "$FIX/absent/README.md"
# current — coordinate present and matching
printf 'implementation("io.github.sceneview:sceneview:4.29.0")\n' > "$FIX/current/CLAUDE.md"
cp "$FIX/current/CLAUDE.md" "$FIX/current/README.md"
# stale — coordinate present, one release behind
printf 'implementation("io.github.sceneview:sceneview:4.28.0")\n' > "$FIX/stale/CLAUDE.md"
cp "$FIX/stale/CLAUDE.md" "$FIX/stale/README.md"

# ─── 1. CLAUDE.md: absent coordinate must SKIP, not block ─────────────────
V=$(run_block "$TMP/claude-block.sh" "$FIX/absent" 4.29.0)
[ "$V" = "SKIPPED" ] \
    && ok "CLAUDE.md with no coordinate is skipped (got SKIPPED)" \
    || bad "CLAUDE.md with no coordinate graded '$V' — expected SKIPPED (this is the 4.29.0 false blocker)"

# ─── 2. CLAUDE.md: present + current must PASS ────────────────────────────
V=$(run_block "$TMP/claude-block.sh" "$FIX/current" 4.29.0)
[ "$V" = "PASS" ] && ok "CLAUDE.md carrying the target version PASSes" \
                  || bad "CLAUDE.md carrying 4.29.0 graded '$V' — expected PASS"

# ─── 3. CLAUDE.md: present + stale must still FAIL ────────────────────────
# The whole risk of the fix is turning a false red into a false green. If a
# coordinate IS there and is wrong, the release must still be blocked.
V=$(run_block "$TMP/claude-block.sh" "$FIX/stale" 4.29.0)
[ "$V" = "FAIL" ] && ok "CLAUDE.md carrying a stale version still FAILs" \
                  || bad "CLAUDE.md carrying 4.28.0 graded '$V' — expected FAIL (the check went inert)"

# ─── 4. README.md keeps the hard blocker, absent included ─────────────────
V=$(run_block "$TMP/readme-block.sh" "$FIX/absent" 4.29.0)
[ "$V" = "FAIL" ] && ok "README.md with no coordinate still FAILs (unchanged)" \
                  || bad "README.md with no coordinate graded '$V' — expected FAIL; the user-facing install line lost its gate"
V=$(run_block "$TMP/readme-block.sh" "$FIX/stale" 4.29.0)
[ "$V" = "FAIL" ] && ok "README.md carrying a stale version still FAILs" \
                  || bad "README.md carrying 4.28.0 graded '$V' — expected FAIL"

# ─── 5. Mutation: put the old unguarded form back, scenario 1 must break ──
# Without this the suite could pass against any implementation at all.
sed 's|^if \[ "\$CLAUDE_V" != "MISSING" \]; then|if true; then|' \
    "$TMP/claude-block.sh" > "$TMP/claude-mutant.sh"
if diff -q "$TMP/claude-block.sh" "$TMP/claude-mutant.sh" >/dev/null; then
    bad "mutation was a no-op — the MISSING guard is not where this suite thinks it is, so tests 1-3 prove nothing"
else
    MUT=$(run_block "$TMP/claude-mutant.sh" "$FIX/absent" 4.29.0)
    # Demand the EXACT wrong verdict, not merely "something other than SKIPPED":
    # a mutant that fails to parse also returns something else, and accepting
    # that would let this suite report teeth it does not have.
    if [ "$MUT" = "FAIL" ]; then
        ok "mutation reproduces the original false blocker (absent → FAIL) — the guard is what makes tests 1-3 pass"
    else
        bad "mutant graded '$MUT', expected FAIL — the mutation did not reproduce the bug, so tests 1-3 are unproven"
    fi
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
    printf '\033[0;32m%s passed, 0 failed\033[0m\n' "$PASS"
    exit 0
else
    printf '\033[0;31m%s passed, %s failed\033[0m\n' "$PASS" "$FAIL"
    exit 1
fi
