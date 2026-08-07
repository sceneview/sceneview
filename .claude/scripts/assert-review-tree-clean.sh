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
#   The one case it cannot separate is a reviewer reverting a sensitive file to
#   precisely the base content — which is indistinguishable from the restore
#   because it IS the restore's result, and which the action has already done
#   itself by the time any reviewer runs.
#
#   The list below is a copy of the action's `SENSITIVE_PATHS`. If the action
#   ever widens it, this script does not follow, and the new path reports as
#   contamination — red, not green. Drift fails closed.
#
# USAGE
#   assert-review-tree-clean.sh --base <ref>     # e.g. --base origin/main
#
# EXIT   0 = the tree is HEAD everywhere except the action's own restore
#        1 = contaminated, or the check could not be performed
#        2 = usage error

set -uo pipefail

BASE_REF=""

while [ $# -gt 0 ]; do
  case "$1" in
    --base)    BASE_REF="${2:-}"; shift 2 ;;
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
base_entry() {
  git ls-tree "$BASE_REF" -- "$1" 2>/dev/null | awk 'NR==1 {print $1" "$3}'
}

# `<mode> <sha>` for a path in the WORKING TREE, computed from the file itself
# and never from the index — the action stages and unstages these paths, so an
# index-relative comparison would describe git's bookkeeping rather than the
# bytes on disk.
worktree_entry() {
  local path="$1"
  if [ -L "$path" ]; then
    printf '120000 %s' "$(readlink -- "$path" | git hash-object --stdin)"
  elif [ -f "$path" ]; then
    if [ -x "$path" ]; then printf '100755 '; else printf '100644 '; fi
    git hash-object -- "$path"
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

RESTORED=()
CONTAMINATED=()

# `--porcelain=v1 -z` emits `XY <path>\0`, and for a rename or copy a SECOND
# NUL-terminated field carrying the original path. Read the pairs explicitly:
# splitting on NUL without consuming that extra field would read an original
# path as if it were a status line.
while IFS= read -r -d '' ENTRY; do
  XY="${ENTRY:0:2}"
  PATH_NAME="${ENTRY:3}"
  case "$XY" in
    R*|C*|*R|*C) IFS= read -r -d '' _ORIG || true ;;
  esac

  if is_sensitive "$PATH_NAME" && [ "$(worktree_entry "$PATH_NAME")" = "$(base_entry "$PATH_NAME")" ]; then
    RESTORED+=("$XY $PATH_NAME")
  else
    CONTAMINATED+=("$XY $PATH_NAME")
  fi
done < "$TMP/status"

if [ "${#RESTORED[@]}" -gt 0 ]; then
  echo "Restored from ${BASE_REF} by claude-code-action's PR-head hardening (NOT reviewer contamination):"
  printf '  %s\n' "${RESTORED[@]}"
  echo "Each of those matches ${BASE_REF} byte-for-byte, which is the action's own contract."
  echo "The reviewers read the PR's versions from the diff and from .claude-pr/."
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
