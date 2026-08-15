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

# Measured FAIL-OPEN, and a regression against the `git status | test -z` this
# replaced: in `-z` the rename DESTINATION comes first, so judging only that
# path let the source vanish unseen. `.claude/evil` is absent from both the
# worktree and base, so "" = "" filed the whole entry under RESTORED while
# `src/app.kt` had been deleted. `git mv` is not in the orchestrator's deny list.
make_repo "$TMP/rename" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                        "git mv src/app.kt .claude/evil && rm .claude/evil"
run "$TMP/rename"
check "reviewer renames a tracked file INTO .claude and deletes it — contamination" 1

# NO FIFO / empty-directory case here, on purpose. Measured with git 2.46:
# `git status --porcelain -uall` lists neither — only regular files and symlinks
# reach the loop at all. An assertion built on `git status` therefore cannot see
# them, and a test claiming otherwise would pass only by accident. The script's
# `unsupported-file-type` branch stays as cheap defence in case a future git
# does report one; the limitation is documented in its header rather than
# papered over with a green ✓.

# The symlink branch must not red-flag a LEGITIMATE restore: `readlink` appends
# a newline that git's 120000 blob does not carry, so hashing it raw could never
# match and every restored symlink would read as contamination.
make_repo "$TMP/symlink" "ln -s scripts/sync-assets.sh .claude/link && git add .claude/link"
run "$TMP/symlink"
check "PR adds a symlink under .claude — the restore deletes it, still benign" 0

# `git ls-tree` takes PATHSPECS: a planted name containing glob metacharacters
# must not be compared against a SIBLING's blob.
make_repo "$TMP/glob" "printf 'pr\n' > .claude/scripts/sync-assets.sh" \
                      "cp .claude/scripts/sync-assets.sh '.claude/scripts/sync-assets*.sh'"
run "$TMP/glob"
check "planted file whose name is a glob — matched literally, contamination" 1

# --- the check must fail CLOSED when it cannot look -------------------------

mkdir -p "$TMP/norepo"
OUT="$(cd "$TMP/norepo" && bash "$ASSERT" --base base 2>&1)"; RC=$?
check "outside a git repository — absent is not clean" 1

make_repo "$TMP/nobase" "printf 'pr code\n' > src/app.kt"
OUT="$(cd "$TMP/nobase" && bash "$ASSERT" --base origin/nope 2>&1)"; RC=$?
check "unresolvable base ref — refuses to guess" 1

OUT="$(cd "$TMP/nobase" && bash "$ASSERT" 2>&1)"; RC=$?
check "missing --base — usage error" 2

# `shift 2` on a lone `--base` shifts nothing and returns non-zero; without a
# guard the arg loop spins on the same token until the job timeout. A hang is
# not a usage error, and `timeout` is how the difference gets asserted.
# `timeout` is coreutils: present on the CI runner, absent from a bare macOS.
# Without the guard this case would exit 127 and red-flag a contributor's
# machine for a tool that was never the thing under test.
if command -v timeout >/dev/null 2>&1; then
  OUT="$(cd "$TMP/nobase" && timeout 10 bash "$ASSERT" --base 2>&1)"; RC=$?
  check "--base with no value — exits, does not hang" 2
else
  echo "  – skipped: --base with no value (needs coreutils \`timeout\`)"
fi

# --- the moving-base race (#3182) -------------------------------------------
#
# The measured shape, reproduced exactly: `pr-review.yml` pins the base SHA
# before the fan-out, then the base branch MOVES, and only afterwards does the
# action restore — from the branch, which is what it actually resolves. On run
# 31820409662 those two commits were 3 seconds apart and the job died on
# ` M CLAUDE.md`, a file the PR never touched.
#
# Note which file moves here: CLAUDE.md is edited on BASE and left alone by the
# PR. That is the whole point — the failure lands on a path outside the diff, so
# no amount of reading the PR explains it.
echo
echo "the moving-base race (#3182)"
make_repo_moving_base() {
  local dir="$1"
  rm -rf "$dir"; mkdir -p "$dir"
  (
    cd "$dir" || exit 1
    git init --quiet -b base .
    git config user.email t@example.com
    git config user.name Test
    mkdir -p "$dir/.nohooks"
    git config core.hooksPath "$dir/.nohooks"
    git config commit.gpgsign false
    mkdir -p .claude/scripts src
    printf 'base\n' > .claude/scripts/sync-assets.sh
    chmod +x .claude/scripts/sync-assets.sh
    printf 'base guide\n' > CLAUDE.md
    printf 'base code\n' > src/app.kt
    git add -A && git commit --quiet -m base
    git tag pinned                      # what `Materialise the diff` captured

    git switch --quiet -c pr
    printf 'pr\n' > .claude/scripts/sync-assets.sh   # the PR's own change
    git add -A && git commit --quiet -m pr

    # …and now the base branch moves, touching a DIFFERENT sensitive path.
    git switch --quiet base
    printf 'guide, revised\n' > CLAUDE.md
    git add -A && git commit --quiet -m "base moves"
    git switch --quiet pr

    # The action restores from the BRANCH, not from the pinned tag.
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
    exit 0
  )
}

make_repo_moving_base "$TMP/moved"

# 1. The bug, pinned. Without --also-base this MUST still be red: if it were
#    green here the fix below would be proving nothing.
OUT="$(cd "$TMP/moved" && bash "$ASSERT" --base pinned 2>&1)"; RC=$?
check "pinned base only — the moved branch reads as contamination (the #3182 bug)" 1
case "$OUT" in
  *"CLAUDE.md"*) echo "  ✓ …and it names CLAUDE.md, a file the PR never touched"; PASS=$((PASS + 1)) ;;
  *) echo "  ✗ the reproduction does not name CLAUDE.md — it is not the measured shape"; FAIL=$((FAIL + 1)) ;;
esac

# 2. The fix.
OUT="$(cd "$TMP/moved" && bash "$ASSERT" --base pinned --also-base base 2>&1)"; RC=$?
check "accepting the branch tip as a second trusted ref absorbs the race" 0

# 2b. The verdict has to name the refs it trusted, and BOTH of them once there
#     are two. This is not cosmetic and it is not the sibling suite's job: the
#     first draft of #3182 moved the ref out of the header onto each line, which
#     read fine and silently broke `test-dispatch-config-restore.sh`, whose whole
#     assertion is that a restore is named rather than merely tolerated. Pinning
#     it here means the file that owns the output owns the contract too.
case "$OUT" in
  *"Restored from pinned or base by "*)
    echo "  ✓ the verdict header names both trusted refs"; PASS=$((PASS + 1)) ;;
  *)
    echo "  ✗ the verdict header does not name both trusted refs"; FAIL=$((FAIL + 1))
    printf '%s\n' "$OUT" | grep -n 'Restored' | sed -n '1,3p' ;;
esac

# 3. …and it must absorb ONLY that. A reviewer's edit matches neither ref, and
#    adding a second trusted base must not become a way in. This is the case
#    that would break first if `--also-base` were ever loosened to a path skip.
make_repo_moving_base "$TMP/moved-dirty"
printf 'reviewer was here\n' > "$TMP/moved-dirty/.claude/scripts/sync-assets.sh"
OUT="$(cd "$TMP/moved-dirty" && bash "$ASSERT" --base pinned --also-base base 2>&1)"; RC=$?
check "a reviewer edit matching NEITHER ref is still contamination" 1

# 4. An unresolvable second ref is a warning, not a crash and not a pass. The
#    fetch that produces it is best-effort in the workflow (`|| true`), so this
#    path is reachable, and it must degrade to the strict old behaviour.
OUT="$(cd "$TMP/moved" && bash "$ASSERT" --base pinned --also-base origin/nope 2>&1)"; RC=$?
check "an unresolvable --also-base degrades to strict, it does not fail open" 1
case "$OUT" in
  *"::warning"*"#3182"*) echo "  ✓ …and says so, pointing at the race rather than at the reviewers"; PASS=$((PASS + 1)) ;;
  *) echo "  ✗ an unresolvable --also-base is silent — the next reader gets the #3016 diagnosis again"; FAIL=$((FAIL + 1)) ;;
esac

# 5. Mutation: drop the second ref from the lookup and case 2 must go red again.
#    Without this, `--also-base` could be accepted and quietly ignored.
MUTANT3="$TMP/mutant3.sh"
sed 's|^  if \[ -n "\$ALSO_BASE_REF" \] .*$|  if false; then|' "$ASSERT" > "$MUTANT3"
if ! grep -q '^  if false; then$' "$MUTANT3"; then
  echo "  ✗ mutation could not be applied — the second-ref lookup moved; update this test"
  FAIL=$((FAIL + 1))
else
  (cd "$TMP/moved" && bash "$MUTANT3" --base pinned --also-base base >/dev/null 2>&1)
  if [ $? -eq 1 ]; then
    echo "  ✓ ignoring --also-base brings the race back, so case 2 proves the fix"
    PASS=$((PASS + 1))
  else
    echo "  ✗ the race case passes even with --also-base ignored — something else makes it green"
    FAIL=$((FAIL + 1))
  fi
fi

# --- the suite must pin its own WIRING --------------------------------------
#
# Counting cases measures effort, not coverage: green assertions say nothing if
# `pr-review.yml` never calls this script. That exact failure shipped on #3047 —
# a suite went 21/21 with the fix's only call site deleted from the workflow.
#
# ⛔ GREP THE CALL, NOT THE NAME. The first version of this check matched the
# bare filename anywhere in the file, and `pr-review.yml` mentions this script
# in FOUR comment lines — so deleting the only real invocation left the check
# green off a comment. It reproduced #3047 inside the very block written to
# prevent it. The mutation below is what keeps that honest.
WORKFLOW="$SCRIPT_DIR/../../.github/workflows/pr-review.yml"
CALL_PATTERN='^[[:space:]]*bash "\$RUNNER_TEMP/assert-review-tree-clean\.sh" --base'

wiring_ok() { grep -qE "$CALL_PATTERN" "$1"; }

if [ -f "$WORKFLOW" ] && wiring_ok "$WORKFLOW"; then
  echo "  ✓ pr-review.yml actually invokes this script"
  PASS=$((PASS + 1))
else
  echo "  ✗ pr-review.yml does not invoke assert-review-tree-clean.sh — the gate is unwired, and every case above is decoration"
  FAIL=$((FAIL + 1))
fi

# Mutation on the wiring check itself: strip the invocation, keep every comment
# that names the script, and the check must still go red.
if [ -f "$WORKFLOW" ]; then
  grep -vE "$CALL_PATTERN" "$WORKFLOW" > "$TMP/unwired.yml"
  if wiring_ok "$TMP/unwired.yml"; then
    echo "  ✗ removing the invocation left the wiring check green — it is matching prose, not the call (#3047)"
    FAIL=$((FAIL + 1))
  elif ! grep -q 'assert-review-tree-clean\.sh' "$TMP/unwired.yml"; then
    echo "  ✗ mutation removed every mention, so this proves nothing — the check must survive comments that name the script"
    FAIL=$((FAIL + 1))
  else
    echo "  ✓ deleting the invocation turns the wiring check red, even with 4 comments still naming the script"
    PASS=$((PASS + 1))
  fi
fi

# --- mutation: the obvious wrong fix must turn this suite RED ---------------
#
# Rewrites `is_sensitive` into a blanket "anything under .claude/ is fine",
# which is the shortcut a future reader will reach for. Three cases above are
# then wrong, and the suite has to say so.
echo
echo "mutation test (a path EXCLUSION instead of a content assertion)"
MUTANT="$TMP/mutant.sh"
# Anchored on `is_sensitive` and the `&&`, NOT on what follows it: the content
# half of that condition was a `[ … ]` test until #3182 made it a call to
# `matching_base_ref`, and a mutation keyed to the old spelling silently stopped
# applying. It reported that honestly (this suite has a guard for exactly that),
# but a mutation that cannot apply proves nothing about the assertion.
sed 's|^  if is_sensitive "\$path" \&\& .*$|  if is_sensitive "$path"; then|' "$ASSERT" > "$MUTANT"
if ! grep -q 'if is_sensitive "\$path"; then$' "$MUTANT"; then
  echo "  ✗ mutation could not be applied — the classification line moved; update this test"
  FAIL=$((FAIL + 1))
else
  # `rename` is deliberately NOT here. Its source path (`src/app.kt`) is not
  # sensitive, so a path EXCLUSION does not weaken it — that hole is orthogonal
  # and gets its own mutation below. Listing it would have made this gate
  # unsatisfiable, which is how a suite ends up loosened to `-gt 0`.
  MUT_CASES=(dirty-claude planted chmod deleted glob)
  MUT_CAUGHT=0
  for case_dir in "${MUT_CASES[@]}"; do
    (cd "$TMP/$case_dir" && bash "$MUTANT" --base base >/dev/null 2>&1)
    [ $? -ne 1 ] && MUT_CAUGHT=$((MUT_CAUGHT + 1))
  done
  # ⛔ ALL of them, not "at least one". `-gt 0` would let five of the six stop
  # discriminating while the suite still printed a ✓ — the same "counting
  # measures effort, not coverage" trap the wiring check above exists for.
  if [ "$MUT_CAUGHT" -eq "${#MUT_CASES[@]}" ]; then
    echo "  ✓ weakening the assertion to a path exclusion breaks all ${#MUT_CASES[@]} contamination cases"
    PASS=$((PASS + 1))
  else
    echo "  ✗ the mutant still blocked $(( ${#MUT_CASES[@]} - MUT_CAUGHT )) of ${#MUT_CASES[@]} contamination cases — this suite does not pin the assertion"
    FAIL=$((FAIL + 1))
  fi
fi


# --- mutation 2: dropping the rename SOURCE must reopen the measured fail-open
#
# This is the hole the review found: judging only the rename destination let
# `git mv src/app.kt .claude/evil && rm .claude/evil` pass as "restored" while a
# tracked file was deleted. Reverting that one line must make the `rename` case
# go green-when-it-should-be-red, or this suite is not pinning the fix.
echo
echo "mutation test (drop the rename/copy SOURCE from classification)"
MUTANT2="$TMP/mutant2.sh"
sed 's|^        classify "\$XY" "\$ORIG_PATH" .*$|        :|' "$ASSERT" > "$MUTANT2"
if ! grep -q '^        :$' "$MUTANT2"; then
  echo "  ✗ mutation could not be applied — the rename-source classification moved; update this test"
  FAIL=$((FAIL + 1))
else
  (cd "$TMP/rename" && bash "$MUTANT2" --base base >/dev/null 2>&1)
  if [ $? -eq 0 ]; then
    echo "  ✓ dropping the rename source reopens the fail-open, and this suite catches it"
    PASS=$((PASS + 1))
  else
    echo "  ✗ the rename case still blocks without the source classification — it is not what makes that case red"
    FAIL=$((FAIL + 1))
  fi
fi

echo
echo "assert-review-tree-clean: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
