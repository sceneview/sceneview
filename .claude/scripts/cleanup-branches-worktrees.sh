#!/usr/bin/env bash
# cleanup-branches-worktrees.sh — reusable GC for stale claude/* branches
# and stale .claude/worktrees/* directories.
#
# Why: an orchestrator marathon leaves behind dozens of `claude/*` local
# branches, the same on `origin`, and one ~1 GB worktree per spawned agent.
# Disk pressure builds; the remote ref list gets unwieldy. This is the one
# command to reclaim all three at once, safely.
#
# What it does (in order):
#   1. `git fetch origin --prune`, then ONE bulk `gh pr list` per state
#      (open / merged) so PR status is a pure in-memory lookup — no
#      per-branch `gh pr view` (that's O(branches) API calls and times the
#      CI job out before it can delete anything).
#   2. Deletes MERGED local `claude/*` branches — "merged" means
#      `git rev-list --count origin/main..<branch> == 0` (zero commits the
#      branch carries that origin/main doesn't). Never the current branch.
#   3. Deletes MERGED remote `claude/*` branches on `origin` in a SINGLE
#      `git push origin --delete b1 b2 ...` call (one API operation — a
#      loop of pushes looks bot-ish and is ban-risky, see
#      feedback_no_pr_burst). A branch with an OPEN PR is never deleted.
#   4. Removes stale worktrees under `.claude/worktrees/` by delegating to
#      the sibling `worktree-auto-prune.sh` (which already handles the
#      dirty-tree / unmerged / squash-merged-PR cases).
#
# Safety contract:
#   - NEVER touches the current branch or the current worktree.
#   - NEVER deletes a branch that is ahead>0 vs origin/main (unmerged work)
#     UNLESS its PR is MERGED on GitHub (squash-merge: ahead stays >0).
#   - NEVER deletes a branch whose PR is OPEN on GitHub.
#   - NEVER deletes anything passed via --keep.
#   - Defaults to DRY-RUN: prints what it would delete and stops. Pass
#     --yes to actually delete (matches worktree-auto-prune.sh's --yes,
#     but here --yes is *required* for any destructive action — there is
#     no interactive prompt, so CI and humans behave identically).
#
# Usage:
#   bash .claude/scripts/cleanup-branches-worktrees.sh                 # dry-run
#   bash .claude/scripts/cleanup-branches-worktrees.sh --dry-run       # explicit dry-run
#   bash .claude/scripts/cleanup-branches-worktrees.sh --yes           # actually delete
#   bash .claude/scripts/cleanup-branches-worktrees.sh --yes \
#        --keep claude/my-wip --keep "$(git rev-parse --show-toplevel)"
#   bash .claude/scripts/cleanup-branches-worktrees.sh --yes --no-worktrees
#
# Flags:
#   --dry-run        Preview only (default when --yes is absent).
#   --yes            Perform deletions. Without it, nothing is deleted.
#   --keep <ref>     Branch name OR worktree path to spare. Repeatable.
#   --no-worktrees   Skip the worktree-prune step (branches only).
#   -h, --help       Show this header.
#
# Exit codes:
#   0 = success (including "nothing to clean")
#   1 = unexpected error

set -euo pipefail

DRY_RUN=true
DO_WORKTREES=true
KEEP=()

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)      DRY_RUN=true; shift ;;
        --yes)          DRY_RUN=false; shift ;;
        --no-worktrees) DO_WORKTREES=false; shift ;;
        --keep)         KEEP+=("${2:-}"); shift 2 ;;
        --keep=*)       KEEP+=("${1#--keep=}"); shift ;;
        -h|--help)      sed -n '2,52p' "$0"; exit 0 ;;
        *)              echo "Unknown flag: $1" >&2; exit 1 ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# --- locate the main repo root (works from a linked worktree too) ----------
GIT_COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null || true)"
if [ -z "$GIT_COMMON_DIR" ]; then
    echo -e "${RED}Not inside a git repo.${NC}" >&2
    exit 1
fi
GIT_COMMON_DIR="$(cd "$GIT_COMMON_DIR" && pwd)"
REPO_ROOT="$(dirname "$GIT_COMMON_DIR")"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"

# is_kept <ref> — true if the ref matches any --keep argument (verbatim).
is_kept() {
    local ref="$1" k
    for k in "${KEEP[@]:-}"; do
        [ -n "$k" ] && [ "$ref" = "$k" ] && return 0
    done
    return 1
}

# gh availability — only used for the open-PR safety signal. The script
# works fully offline / without `gh`; without it, a branch is considered
# deletable purely on the ahead=0 signal (still safe — ahead=0 = merged).
GH_AVAILABLE=false
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    GH_AVAILABLE=true
fi

# Bulk PR state — fetched ONCE, not per-branch. A repo with hundreds of
# stale branches would otherwise fire hundreds of sequential `gh pr view`
# calls and blow the CI timeout — exactly why the daily job never managed
# to drain a 190-branch backlog. Two `gh pr list` calls cover everything.
OPEN_PR_BRANCHES=""
MERGED_PR_BRANCHES=""

# pr_state <branch> — echoes OPEN / MERGED or "" (no PR, closed-unmerged,
# or gh unavailable). Pure in-memory lookup against the bulk lists above.
# A closed-unmerged PR returns "" — callers treat that identically: ahead>0
# keeps it as unmerged work, ahead=0 makes it deletable.
pr_state() {
    local branch="$1"
    [ "$GH_AVAILABLE" = "true" ] || { echo ""; return 0; }
    # Herestrings, NOT `printf … | grep -Fxq`. `grep -q` exits on its first
    # match and closes the pipe; printf then takes EPIPE, and under `pipefail`
    # the pipeline reports failure *because the match succeeded* (#3180). Here
    # that lands on the destructive side: a branch that DOES have an open PR
    # would not be classified OPEN, and this function is what stands between a
    # branch and `git branch -D`. The odds grow with the branch list.
    if grep -Fxq -- "$branch" <<< "$OPEN_PR_BRANCHES";   then echo "OPEN";   return 0; fi
    if grep -Fxq -- "$branch" <<< "$MERGED_PR_BRANCHES"; then echo "MERGED"; return 0; fi
    echo ""
}

echo -e "${CYAN}=== Branch + worktree cleanup ===${NC}"
echo "Repo root:      $REPO_ROOT"
echo "Current branch: ${CURRENT_BRANCH:-<detached>}"
if [ "$DRY_RUN" = "true" ]; then
    echo -e "Mode:           ${YELLOW}DRY RUN${NC} (pass --yes to delete)"
else
    echo -e "Mode:           ${RED}DELETE${NC}"
fi
[ "$GH_AVAILABLE" = "true" ] && echo "GitHub:         gh authenticated (open-PR guard active)" \
                             || echo "GitHub:         gh unavailable (ahead=0 guard only)"
echo ""

# --- 1. refresh refs -------------------------------------------------------
echo -e "${CYAN}--- Fetching origin --prune ---${NC}"
FETCH_FAILED=false
if ! git -C "$REPO_ROOT" fetch origin --prune --quiet 2>/dev/null; then
    echo -e "${YELLOW}Warning: fetch failed — working from local refs.${NC}"
    FETCH_FAILED=true
fi
echo ""

# --- 1b. bulk PR state -----------------------------------------------------
if [ "$GH_AVAILABLE" = "true" ]; then
    echo -e "${CYAN}--- Fetching PR states (bulk) ---${NC}"
    OPEN_PR_BRANCHES="$(gh pr list --state open --limit 1000 \
        --json headRefName --jq '.[].headRefName' 2>/dev/null || echo "")"
    MERGED_PR_BRANCHES="$(gh pr list --state merged --limit 2000 \
        --json headRefName --jq '.[].headRefName' 2>/dev/null || echo "")"
    echo "  open PRs:   $(printf '%s\n' "$OPEN_PR_BRANCHES"   | grep -c . || true)"
    echo "  merged PRs: $(printf '%s\n' "$MERGED_PR_BRANCHES" | grep -c . || true)"
    echo ""
fi

# ===========================================================================
# 2. MERGED LOCAL claude/* branches
# ===========================================================================
echo -e "${CYAN}--- Local claude/* branches ---${NC}"
LOCAL_DELETE=()
LOCAL_SKIP=()

while IFS= read -r branch; do
    [ -z "$branch" ] && continue
    if [ "$branch" = "$CURRENT_BRANCH" ]; then
        LOCAL_SKIP+=("$branch (current branch)")
        continue
    fi
    if is_kept "$branch"; then
        LOCAL_SKIP+=("$branch (--keep)")
        continue
    fi
    ahead=$(git -C "$REPO_ROOT" rev-list --count "origin/main..$branch" 2>/dev/null || echo "unknown")
    if [ "$ahead" = "0" ]; then
        LOCAL_DELETE+=("$branch")
    elif [ "$(pr_state "$branch")" = "MERGED" ]; then
        # Squash-merge case: the branch's commits stay distinct (ahead>0)
        # but its PR is MERGED on GitHub → the work is safely on main.
        LOCAL_DELETE+=("$branch")
    else
        LOCAL_SKIP+=("$branch (ahead=$ahead — unmerged work)")
    fi
done < <(git -C "$REPO_ROOT" for-each-ref --format='%(refname:short)' 'refs/heads/claude/*')

if [ "${#LOCAL_DELETE[@]}" -eq 0 ]; then
    echo "  (no merged local claude/* branches)"
else
    for b in "${LOCAL_DELETE[@]}"; do echo -e "  ${GREEN}delete${NC}  $b"; done
fi
for s in "${LOCAL_SKIP[@]:-}"; do [ -n "$s" ] && echo -e "  ${YELLOW}keep${NC}    $s"; done
echo ""

# ===========================================================================
# 3. MERGED REMOTE origin/claude/* branches
# ===========================================================================
echo -e "${CYAN}--- Remote origin/claude/* branches ---${NC}"
REMOTE_DELETE=()
REMOTE_SKIP=()

while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    branch="${ref#origin/}"
    # Never delete the remote tracking branch of the branch we're on.
    if [ "$branch" = "$CURRENT_BRANCH" ]; then
        REMOTE_SKIP+=("$branch (current branch)")
        continue
    fi
    if is_kept "$branch"; then
        REMOTE_SKIP+=("$branch (--keep)")
        continue
    fi
    ahead=$(git -C "$REPO_ROOT" rev-list --count "origin/main..$ref" 2>/dev/null || echo "unknown")
    state="$(pr_state "$branch")"
    # Never delete a branch with an OPEN PR — that PR carries live work.
    if [ "$state" = "OPEN" ]; then
        REMOTE_SKIP+=("$branch (open PR)")
        continue
    fi
    if [ "$ahead" != "0" ] && [ "$state" != "MERGED" ]; then
        # ahead>0 and no merged PR — genuine unmerged work, keep it.
        # (ahead>0 + MERGED PR = squash-merge; the work is on main → ok.)
        REMOTE_SKIP+=("$branch (ahead=$ahead — unmerged work)")
        continue
    fi
    REMOTE_DELETE+=("$branch")
done < <(git -C "$REPO_ROOT" for-each-ref --format='%(refname:short)' 'refs/remotes/origin/claude/*')

if [ "${#REMOTE_DELETE[@]}" -eq 0 ]; then
    echo "  (no merged remote claude/* branches)"
else
    for b in "${REMOTE_DELETE[@]}"; do echo -e "  ${GREEN}delete${NC}  origin/$b"; done
fi
for s in "${REMOTE_SKIP[@]:-}"; do [ -n "$s" ] && echo -e "  ${YELLOW}keep${NC}    $s"; done
echo ""

# ===========================================================================
# Execute (or stop here on dry-run)
# ===========================================================================
if [ "$DRY_RUN" = "true" ]; then
    echo -e "${YELLOW}DRY RUN — nothing deleted.${NC}"
    echo "  Local branches that would be deleted:  ${#LOCAL_DELETE[@]}"
    echo "  Remote branches that would be deleted: ${#REMOTE_DELETE[@]}"
    echo ""
else
    if [ "${#LOCAL_DELETE[@]}" -gt 0 ]; then
        echo -e "${CYAN}--- Deleting local branches ---${NC}"
        # -D (not -d): ahead=0 already proves merged-into-origin/main; -d
        # can still refuse if the local branch lacks an upstream merge ref.
        for b in "${LOCAL_DELETE[@]}"; do
            if git -C "$REPO_ROOT" branch -D "$b" >/dev/null 2>&1; then
                echo -e "  ${GREEN}deleted${NC} $b"
            else
                echo -e "  ${RED}failed ${NC} $b"
            fi
        done
        echo ""
    fi

    if [ "${#REMOTE_DELETE[@]}" -gt 0 ]; then
        echo -e "${CYAN}--- Deleting remote branches (single push) ---${NC}"
        # One git push --delete with every branch — a single API operation,
        # NOT a loop of pushes (bot-burst / ban risk, feedback_no_pr_burst).
        if git -C "$REPO_ROOT" push origin --delete "${REMOTE_DELETE[@]}"; then
            echo -e "  ${GREEN}deleted ${#REMOTE_DELETE[@]} remote branch(es).${NC}"
        else
            echo -e "  ${RED}remote delete failed — see git output above.${NC}"
        fi
        echo ""
    fi
fi

# ===========================================================================
# 4. Stale worktrees — delegate to worktree-auto-prune.sh
# ===========================================================================
if [ "$DO_WORKTREES" = "true" ]; then
    PRUNE="$REPO_ROOT/.claude/scripts/worktree-auto-prune.sh"
    if [ -f "$PRUNE" ]; then
        echo -e "${CYAN}--- Worktrees (delegating to worktree-auto-prune.sh) ---${NC}"
        PRUNE_ARGS=()
        [ "$DRY_RUN" = "true" ] && PRUNE_ARGS+=(--dry-run) || PRUNE_ARGS+=(--yes)
        # If our own fetch above failed, pass --allow-stale so the
        # delegate doesn't abort on its own re-fetch attempt (we've
        # already accepted local refs at the wrapper level).
        [ "$FETCH_FAILED" = "true" ] && PRUNE_ARGS+=(--allow-stale)
        # Always spare the current worktree, plus any --keep paths.
        PRUNE_ARGS+=(--keep "$(git rev-parse --show-toplevel)")
        for k in "${KEEP[@]:-}"; do
            [ -n "$k" ] && [ -d "$k" ] && PRUNE_ARGS+=(--keep "$k")
        done
        bash "$PRUNE" "${PRUNE_ARGS[@]}" || echo -e "${YELLOW}worktree-auto-prune.sh exited non-zero.${NC}"
        echo ""
    else
        echo -e "${YELLOW}worktree-auto-prune.sh not found — skipping worktree cleanup.${NC}"
        echo ""
    fi
fi

echo -e "${GREEN}=== Done ===${NC}"
exit 0
