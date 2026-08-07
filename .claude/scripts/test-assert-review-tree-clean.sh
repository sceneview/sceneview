#!/usr/bin/env bash
# Hermetic self-test for assert-review-tree-clean.sh — the #3016 contamination net.
#
# WHY THIS EXISTS
#   The guard it tests has to hold two opposite things true at once: the
#   working tree that `claude-code-action` legitimately reverts must pass, and
#   a reviewer's own edit to the very same directory must fail. A guard that
#   only ever gets exercised in the passing direction is prose — this repo has
#   already shipped one (#2947), and the assertion this replaces failed OPEN on
#   a crashed `git status` for exactly that reason.
#
#   Every case below builds a REAL repository and replays what the action does
#   (`src/github/operations/restore-config.ts`): snapshot, delete the eight
#   sensitive paths, check them out from base, unstage. Nothing is stubbed, so
#   the fixtures cannot drift into agreeing with a wrong implementation.
#
#   The last block is a mutation test: it weakens the script into the obvious
#   wrong fix — "ignore anything under .claude/" — and asserts the suite goes
#   RED. Without it, a future simplification to a path exclusion would leave
#   every case green while reopening the hole in the one directory that holds
#   the reviewer mandates and the hook dispatch.
#
# No network, no gh, no agent — real git in a scratch dir.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSERT="$SCRIPT_DIR/assert-review-tree-clean.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

# Build a repo with a `base` branch and a `pr` branch checked out, then replay
# the action's restore. $1 is a callback that edits the PR's tree before the
# commit; $2 a callback run after the restore (a reviewer misbehaving).
#
# The layout mirrors the real one closely enough to matter: a tracked file
# under `.claude/scripts/`, a tracked file outside it, and CLAUDE.md.
make_repo() {
  local dir="$1" pr_edits="$2" post_restore="${3:-}"
  rm -rf "$dir"; mkdir -p "$dir"
  (
    cd "$dir" || exit 1
    git init --quiet -b base .
    git config user.email t@example.com
    git config user.name Test
    # Hermetic against the HOST's git configuration, not just against the
    # network: a developer machine can carry a global `core.hooksPath`, and a
    # pre-commit hook there rejected these fixtures' commits outright — leaving
    # half-built repos that still produced ✓ for the wrong reason. An empty
    # hooks dir makes the fixture depend on nothing outside this file.
    mkdir -p "$dir/.nohooks"
    git config core.hooksPath "$dir/.nohooks"
    git config commit.gpgsign false
    mkdir -p .claude/scripts src
    printf 'base\n' > .claude/scripts/sync-assets.sh
    chmod +x .claude/scripts/sync-assets.sh
    printf 'base guide\n' > CLAUDE.md
    printf 'base code\n' > src/app.kt
    git add -A && git commit --quiet -m base

    git switch --quiet -c pr
    eval "$pr_edits"
    git add -A && git commit --quiet -m pr

    # --- replay claude-code-action's restoreConfigFromBase(base) -------------
    for p in .claude .mcp.json .claude.json .gitmodules .ripgreprc \
             CLAUDE.md CLAUDE.local.md .husky; do
      rm -rf "$p"
    done
    for p in .claude .mcp.json .claude.json .gitmodules .ripgreprc \
             CLAUDE.md CLAUDE.local.md .husky; do
      git checkout base -- "$p" 2>/dev/null || true
    done
    git reset --quiet -- .claude .mcp.json .claude.json .gitmodules \
                         .ripgreprc CLAUDE.md CLAUDE.local.md .husky 2>/dev/null || true
    # ------------------------------------------------------------------------

    [ -n "$post_restore" ] && eval "$post_restore"
    exit 0
  )
}

# run <dir> -> sets $OUT and $RC
run() {
  OUT="$(cd "$1" && bash "$ASSERT" --base base 2>&1)"
  RC=$?
}

check() {
  local name="$1" want_rc="$2"
  if [ "$RC" = "$want_rc" ]; then
    echo "  ✓ $name"
    PASS=$((PASS + 1))
  else
    echo "  ✗ $name — expected rc=$want_rc, got rc=$RC"
    printf '      %s\n' "$OUT"
    FAIL=$((FAIL + 1))
  fi
}

echo "assert-review-tree-clean.sh"

# --- the action's restore alone must PASS ------------------------------------

make_repo "$TMP/mod" "printf 'pr\n' > .claude/scripts/sync-assets.sh"
run "$TMP/mod"
check "PR modifies a .claude file (the #3048 shape) — restore is not contamination" 0

make_repo "$TMP/add" "printf 'new\n' > .claude/scripts/brand-new.sh"
run "$TMP/add"
check "PR adds a .claude file — the action deletes it, reported as ' D'" 0

make_repo "$TMP/del" "git rm --quiet .claude/scripts/sync-assets.sh"
run "$TMP/del"
check "PR deletes a .claude file — the action restores it, reported as '??'" 0

make_repo "$TMP/dir" "git rm -r --quiet .claude/scripts"
run "$TMP/dir"
check "PR deletes a whole .claude directory — every restored file judged on its bytes" 0

make_repo "$TMP/md" "printf 'pr guide\n' > CLAUDE.md"
run "$TMP/md"
check "PR modifies CLAUDE.md — also on the action's restore list" 0

make_repo "$TMP/untouched" "printf 'pr code\n' > src/app.kt"
run "$TMP/untouched"
check "PR touches nothing sensitive — tree is clean, nothing to explain" 0

# --- real contamination must FAIL, including inside .claude/ -----------------

make_repo "$TMP/dirty-src" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                           "printf 'reviewer was here\n' > src/app.kt"
run "$TMP/dirty-src"
check "reviewer edits a file outside .claude — contamination" 1

make_repo "$TMP/dirty-claude" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                              "printf 'reviewer was here\n' > .claude/scripts/sync-assets.sh"
run "$TMP/dirty-claude"
check "reviewer edits the SAME .claude file the action restored — contamination" 1

make_repo "$TMP/planted" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                         "mkdir -p .claude/agents && printf 'planted\n' > .claude/agents/evil.md"
run "$TMP/planted"
check "reviewer invents a file under .claude — absent on base, so contamination" 1

make_repo "$TMP/chmod" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                       "chmod -x .claude/scripts/sync-assets.sh"
run "$TMP/chmod"
check "reviewer chmods a restored file — content matches base, mode does not" 1

make_repo "$TMP/deleted" "printf 'pr code\n' > src/app.kt" \
                         "rm .claude/scripts/sync-assets.sh"
run "$TMP/deleted"
check "reviewer deletes a base-identical .claude file — contamination" 1

# --- the check must fail CLOSED when it cannot look -------------------------

mkdir -p "$TMP/norepo"
OUT="$(cd "$TMP/norepo" && bash "$ASSERT" --base base 2>&1)"; RC=$?
check "outside a git repository — absent is not clean" 1

make_repo "$TMP/nobase" "printf 'pr code\n' > src/app.kt"
OUT="$(cd "$TMP/nobase" && bash "$ASSERT" --base origin/nope 2>&1)"; RC=$?
check "unresolvable base ref — refuses to guess" 1

OUT="$(cd "$TMP/nobase" && bash "$ASSERT" 2>&1)"; RC=$?
check "missing --base — usage error" 2

# --- the suite must pin its own WIRING --------------------------------------
#
# Counting cases measures effort, not coverage: 15 green assertions say nothing
# if `pr-review.yml` never calls this script. That exact failure shipped on
# #3047 — a suite went 21/21 with the fix's only call site deleted from the
# workflow. The gate is the call, so the call is what gets asserted.
WORKFLOW="$SCRIPT_DIR/../../.github/workflows/pr-review.yml"
if [ -f "$WORKFLOW" ] && grep -q 'assert-review-tree-clean\.sh' "$WORKFLOW"; then
  echo "  ✓ pr-review.yml actually invokes this script"
  PASS=$((PASS + 1))
else
  echo "  ✗ pr-review.yml does not invoke assert-review-tree-clean.sh — the gate is unwired, and every case above is decoration"
  FAIL=$((FAIL + 1))
fi

# --- mutation: the obvious wrong fix must turn this suite RED ---------------
#
# Rewrites `is_sensitive` into a blanket "anything under .claude/ is fine",
# which is the shortcut a future reader will reach for. Three cases above are
# then wrong, and the suite has to say so.
echo
echo "mutation test (a path EXCLUSION instead of a content assertion)"
MUTANT="$TMP/mutant.sh"
sed 's|^  if is_sensitive "\$PATH_NAME" \&\& \[ .*$|  if is_sensitive "$PATH_NAME"; then|' "$ASSERT" > "$MUTANT"
if ! grep -q 'if is_sensitive "\$PATH_NAME"; then$' "$MUTANT"; then
  echo "  ✗ mutation could not be applied — the classification line moved; update this test"
  FAIL=$((FAIL + 1))
else
  MUT_CAUGHT=0
  for case_dir in dirty-claude planted chmod deleted; do
    (cd "$TMP/$case_dir" && bash "$MUTANT" --base base >/dev/null 2>&1)
    [ $? -ne 1 ] && MUT_CAUGHT=$((MUT_CAUGHT + 1))
  done
  if [ "$MUT_CAUGHT" -gt 0 ]; then
    echo "  ✓ weakening the assertion to a path exclusion breaks $MUT_CAUGHT/4 contamination cases"
    PASS=$((PASS + 1))
  else
    echo "  ✗ the mutant passed every contamination case — this suite does not pin the assertion"
    FAIL=$((FAIL + 1))
  fi
fi

echo
echo "assert-review-tree-clean: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
