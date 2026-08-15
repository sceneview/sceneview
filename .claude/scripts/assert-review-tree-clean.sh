#!/usr/bin/env bash
# Assert that a `pr-review.yml` run left its checkout unmodified — DETERMINISTIC.
#
# WHY THIS IS NOT `git status --porcelain | test -z`
#   That was the original assertion, inline in `pr-review.yml`, and it was
#   RIGHT about #3016: a reviewer with a shell can revert a file, the
#   orchestrator then reads the dirty tree, and the reviewer's own edit comes
#   back as a confirmed error against the PR (measured three times, #3009 and
#   #3015). The net must stay.
#
#   But it had a second, invisible writer. `claude-code-action` itself rewrites
#   part of the checkout before the CLI starts, on purpose:
#
#       Restoring .claude, .mcp.json, .claude.json, .gitmodules, .ripgreprc,
#       CLAUDE.md, CLAUDE.local.md, .husky from origin/main (PR head is untrusted)
#
#   (`src/github/operations/restore-config.ts`, in the action since 2026-03-18).
#   The CLI reads `.claude/settings.json` and `.mcp.json` from cwd and acts on
#   them — hooks, env vars, apiKeyHelper — BEFORE any tool-permission gating, so
#   on a PR-head checkout every one of those is attacker-controlled. The action
#   deletes the eight paths and checks them out from the base branch, then
#   unstages them. Its own source calls the consequence a "Known limitation":
#   a PR that legitimately modifies `.claude/` ends the run with those files
#   reverted in the working tree.
#
#   So on any PR touching `.claude/**`, `git status` is dirty before a single
#   reviewer has read a line, and the old assertion blamed the reviewers for it.
#   Measured on run 31193247720 (PR #3048): ` M .claude/scripts/sync-assets.sh`,
#   0 permission denials, and an error naming #3016 — the wrong cause. The same
#   step is the top failure of this workflow: 11 of the 14 failing runs sampled
#   on 2026-08-07, every reported path under `.claude/`.
#
# WHAT THIS ASSERTS INSTEAD — and why it is not weaker
#   A path is exempt only if BOTH hold:
#     1. it is one of the eight paths the action restores, and
#     2. its working-tree content and mode are BYTE-IDENTICAL to `origin/<base>`.
#   That is the action's contract, stated as an assertion rather than assumed.
#   It is not a path exclusion: an excluded path is a path a reviewer can hide
#   in, and `.claude/` is the worst possible place to offer one — it is where
#   the reviewer mandates and the hook dispatch live. Under this rule a
#   reviewer that edits `.claude/scripts/foo.sh` to anything other than exactly
#   the base version still fails the job, and so does a file it invents there.
#   Everything outside the eight paths must still be byte-identical to HEAD,
#   with no carve-outs at all.
#
#   Two residuals, both stated rather than papered over:
#     * A reviewer reverting a sensitive file to PRECISELY the base content is
#       indistinguishable from the restore, because it IS the restore's result,
#       and the action has already done it by the time any reviewer runs.
#     * `git status` never lists IGNORED paths, so a write to something matched
#       by `.gitignore` (`.claude/settings.json`, `.claude/plans/`, `build/`, …)
#       is invisible here. Measured with git 2.46, it also lists neither FIFOs
#       and sockets nor empty directories, even under `-uall`. Both blind spots
#       are identical in the assertion this replaces — not regressions, and not
#       covered. Do not read "byte-identical to HEAD" as covering them.
#
#   ON `workflow_dispatch` THE RESTORE IS OURS, NOT THE ACTION'S. The action
#   gates `restoreConfigFromBase()` on a PR context, which a dispatch does not
#   have, so it restores NOTHING there — measured on run 31219325024. That is
#   why `pr-review.yml` carries its own "Restore config paths from base" step
#   for that trigger. Same eight paths, same base commit, so everything this
#   script asserts holds identically on both triggers; only the writer differs.
#
#   The list below is a copy of the action's `SENSITIVE_PATHS`. If the action
#   ever widens it, this script does not follow, and the new path reports as
#   contamination — red, not green. Drift fails closed.
#
#   TWO TRUSTED BASES, BECAUSE THE ACTION AND THE CALLER DISAGREE (#3182).
#   `pr-review.yml` passes the base SHA pinned BEFORE the fan-out, deliberately,
#   so the comparison cannot follow a branch that moves during the review. But
#   `restoreConfigFromBase()` does not use that SHA — it resolves the base
#   BRANCH at its own runtime. When the branch moves in between, the action
#   restores one commit's bytes and this script demands another's, and every
#   sensitive path that differs between the two reads as contamination.
#
#   Measured on run 31820409662 (PR #3181, a shell-script-only diff): the base
#   was pinned at 16:40:43, #3179 merged to `main` at 16:40:46, the action
#   restored from the tip one second later, and the job died on ` M CLAUDE.md`
#   — a file that PR does not touch in any commit. The four paths it DOES touch
#   were forgiven correctly, which is what pointed at the moving branch rather
#   than at the reviewers.
#
#   So `--also-base` accepts the branch tip as a SECOND trusted reference. This
#   does not widen what a reviewer can hide: both refs are commits on a
#   protected branch that no PR controls, and a reviewer would have to make its
#   edit byte-identical to one of them — already true of the pinned base alone.
#   Re-resolving the branch INSTEAD of pinning would not fix it either; it would
#   just move the race to the other side, since the tip can move again between
#   the action's restore and this assertion.
#
# USAGE
#   assert-review-tree-clean.sh --base <ref> [--also-base <ref>]
#     e.g. --base "$PINNED_SHA" --also-base origin/main
#
# EXIT   0 = the tree is HEAD everywhere except the action's own restore
#        1 = contaminated, or the check could not be performed
#        2 = usage error

set -uo pipefail

BASE_REF=""
ALSO_BASE_REF=""

while [ $# -gt 0 ]; do
  case "$1" in
    # `shift 2` on a lone `--base` shifts NOTHING and returns non-zero, and with
    # no `-e` the loop then spins on the same argument until the job times out.
    # A usage error must be an exit, not a hang.
    --base)    [ $# -ge 2 ] || { echo "assert-review-tree-clean.sh: --base needs a value" >&2; exit 2; }
               BASE_REF="$2"; shift; shift ;;
    --also-base) [ $# -ge 2 ] || { echo "assert-review-tree-clean.sh: --also-base needs a value" >&2; exit 2; }
               ALSO_BASE_REF="$2"; shift; shift ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *)         echo "assert-review-tree-clean.sh: unexpected argument $1" >&2; exit 2 ;;
  esac
done

[ -n "$BASE_REF" ] || { echo "assert-review-tree-clean.sh: --base <ref> is required" >&2; exit 2; }

# The eight paths `claude-code-action` restores from the base branch.
SENSITIVE_PATHS=(
  .claude
  .mcp.json
  .claude.json
  .gitmodules
  .ripgreprc
  CLAUDE.md
  CLAUDE.local.md
  .husky
)

is_sensitive() {
  local path="$1" sensitive
  for sensitive in "${SENSITIVE_PATHS[@]}"; do
    [ "$path" = "$sensitive" ] && return 0
    case "$path" in "$sensitive"/*) return 0 ;; esac
  done
  return 1
}

# `<mode> <sha>` for a path in the base commit, empty when the path is absent
# there. Mode is compared too: a chmod on a restored file is not something the
# restore can produce, so it is a write by something else.
#
# `:(literal)` is load-bearing. `git ls-tree` takes PATHSPECS, not names, so a
# planted path containing `*`, `?` or `[` would match its siblings, print
# several rows, and the comparison would then be against a DIFFERENT file's
# blob — the one way this script could have failed open on a path it was
# actually looking at.
base_entry() {
  git ls-tree "$1" -- ":(literal)$2" 2>/dev/null | awk 'NR==1 {print $1" "$3}'
}

# Which trusted ref, if any, the worktree copy of <path> matches. Echoes the ref
# on a match and nothing otherwise, so an unresolvable `--also-base` simply
# stops contributing matches — strictly stricter, never fail-open.
matching_base_ref() {
  local path="$1" here
  here="$(worktree_entry "$path")"
  if [ "$here" = "$(base_entry "$BASE_REF" "$path")" ]; then
    printf '%s' "$BASE_REF"; return 0
  fi
  if [ -n "$ALSO_BASE_REF" ] && [ "$here" = "$(base_entry "$ALSO_BASE_REF" "$path")" ]; then
    printf '%s' "$ALSO_BASE_REF"; return 0
  fi
  return 1
}

# `<mode> <sha>` for a path in the WORKING TREE, computed from the file itself
# and never from the index — the action stages and unstages these paths, so an
# index-relative comparison would describe git's bookkeeping rather than the
# bytes on disk.
worktree_entry() {
  local path="$1"
  if [ -L "$path" ]; then
    # `readlink` appends a newline; git's symlink blob is the target with none.
    # Hashing readlink's output directly can therefore NEVER equal the 120000
    # blob in the tree, which would red-flag every legitimately restored
    # symlink.
    printf '120000 %s' "$(printf '%s' "$(readlink -- "$path")" | git hash-object --stdin)"
  elif [ -f "$path" ]; then
    if [ -x "$path" ]; then printf '100755 '; else printf '100644 '; fi
    git hash-object -- "$path"
  elif [ -e "$path" ]; then
    # A directory, FIFO, socket or device sitting at a sensitive path. Printing
    # nothing here would make it indistinguishable from ABSENT, so a planted
    # non-regular file at a path missing from base would compare "" = "" and be
    # waved through. Emit something that cannot collide with any tree entry.
    printf 'unsupported-file-type %s' "$path"
  fi
  # Absent: print nothing. Absent-on-both is then a match, which is exactly
  # right for a file the PR adds under `.claude/` and the action deletes.
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A FAILED `git status` is not a clean tree. Capturing it without checking the
# exit code made the original assertion fail OPEN: outside a repository git
# printed "fatal: not a git repository" and the step still announced a pristine
# checkout it had never managed to look at.
#
# Redirected to a FILE rather than captured in a variable: bash cannot hold a
# NUL byte, so `$(git status -z)` silently glues every entry into one string,
# and a path containing a space would then be read as a status code.
#
# `-uall` rather than the default: an untracked DIRECTORY is reported as a
# single `?? dir/` entry, which would be compared against a base blob that
# cannot exist and reported as contamination without naming a file. Expanding
# it means each restored file is judged on its own bytes.
if ! git status --porcelain=v1 -uall -z > "$TMP/status" 2> "$TMP/err"; then
  echo "::error title=Clean-tree assertion could not run::git status failed in ${PWD} ($(head -3 "$TMP/err" | tr '\n' ' ')), so this run proves nothing about the checkout. Absent is not clean — treating it as contaminated."
  exit 1
fi

if ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null 2>&1; then
  echo "::error title=Clean-tree assertion could not run::Could not resolve ${BASE_REF}, so a restored path cannot be told apart from a reviewer's edit. Treating the tree as contaminated rather than guessing."
  exit 1
fi

# An unresolvable `--also-base` is a WARNING, not a failure. Dropping it makes
# the assertion stricter, never looser, so the run can still pass on its own
# merits — it is only the #3182 race that stops being absorbed. Failing the job
# outright here would turn one flaky fetch into a red review.
if [ -n "$ALSO_BASE_REF" ] && ! git rev-parse --verify --quiet "$ALSO_BASE_REF^{commit}" >/dev/null 2>&1; then
  echo "::warning title=Second trusted base unavailable::Could not resolve ${ALSO_BASE_REF}, so only ${BASE_REF} is being compared against. That is stricter, not looser — but if this run reports a sensitive path the PR never touched, the base branch moving mid-run (#3182) is the first thing to check."
  ALSO_BASE_REF=""
fi

RESTORED=()
CONTAMINATED=()

classify() {
  local xy="$1" path="$2" label="${3:-}" ref
  if is_sensitive "$path" && ref="$(matching_base_ref "$path")"; then
    RESTORED+=("$xy $path$label  [= $ref]")
  else
    CONTAMINATED+=("$xy $path$label")
  fi
}

# `--porcelain=v1 -z` emits `XY <path>\0`, and for a rename or copy a SECOND
# NUL-terminated field carrying the original path. Read the pairs explicitly:
# splitting on NUL without consuming that extra field would read an original
# path as if it were a status line.
#
# ⛔ BOTH SIDES OF A RENAME GET CLASSIFIED, and skipping the source was a
# measured FAIL-OPEN — a regression against the `git status | test -z` this
# replaced. In `-z` the destination comes FIRST, so judging only `$PATH_NAME`
# judged the wrong path:
#     git mv src/Foo.kt .claude/evil && rm .claude/evil
# emits one entry, `RD .claude/evil\0src/Foo.kt\0`. The destination is absent
# from both the worktree and base, so "" = "" filed it under RESTORED and the
# script announced a pristine tree while `src/Foo.kt` had been deleted outright.
# The orchestrator holds bare `Bash`, and its deny list covers `checkout`,
# `switch`, `reset`, `stash`, `restore`, `apply` and `clean` — not `git mv`.
# The action's restore (`rm -rf` + `git checkout base --` + `git reset --`)
# cannot produce a rename entry at all, so anything here is another writer.
while IFS= read -r -d '' ENTRY; do
  XY="${ENTRY:0:2}"
  PATH_NAME="${ENTRY:3}"
  classify "$XY" "$PATH_NAME"
  case "$XY" in
    R*|C*|*R|*C)
      if IFS= read -r -d '' ORIG_PATH; then
        classify "$XY" "$ORIG_PATH" " (rename/copy source)"
      fi
      ;;
  esac
done < "$TMP/status"

if [ "${#RESTORED[@]}" -gt 0 ]; then
  # Two writers can produce this, and naming only one of them would be a lie on
  # the other path: `claude-code-action` on `pull_request`, and `pr-review.yml`'s
  # own "Restore config paths from base" step on `workflow_dispatch`, where the
  # action does no restore at all (no PR context — see that step).
  # The header names every ref a match was allowed to come from, and each line
  # names the one it actually matched. Naming the accepted set only per-line was
  # not enough: `test-dispatch-config-restore.sh` asserts on this header, and a
  # reader who is handed a verdict without the refs it trusted cannot audit it.
  trusted="$BASE_REF"
  [ -n "$ALSO_BASE_REF" ] && trusted="$trusted or $ALSO_BASE_REF"
  echo "Restored from ${trusted} by the PR-head config hardening (NOT reviewer contamination):"
  printf '  %s\n' "${RESTORED[@]}"
  echo "Each of those matches the trusted ref named beside it byte-for-byte, which is the action's own contract."
  # `.claude-pr/` never appears above: the action appends `/.claude-pr/` to
  # `.git/info/exclude` (`ensureClaudePrExcludedFromGit`), so `git status` does
  # not list it even under `-uall`.
  echo "The reviewers read the PR's versions from pr.diff and from .claude-pr/."
fi

if [ "${#CONTAMINATED[@]}" -gt 0 ]; then
  printf '%s\n' "${CONTAMINATED[@]}"
  echo "::error title=Review contaminated its own checkout::This job modified the working tree during the run (see the paths above). CI checks out a clean tree and the review writes everything under RUNNER_TEMP, so every one of those changes was made here — any finding derived from them is false. This is #3016. Re-run the review; do not act on its verdict."
  exit 1
fi

if [ "${#RESTORED[@]}" -eq 0 ]; then
  echo "Working tree clean — the review did not mutate the checkout."
fi
exit 0
