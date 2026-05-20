#!/usr/bin/env bash
# worktree-auto-prune.sh — safe GC for .claude/worktrees/*
#
# Why: orchestrator marathons leave behind worktrees from agents whose
# PR has long since merged. Each worktree is ~1 GB. 16 stale worktrees
# on 2026-05-14 dropped local disk below the 15 GB alert threshold.
#
# Safety contract:
#   - NEVER removes the caller's own worktree (use --keep <path>, repeatable).
#   - NEVER removes a worktree with uncommitted changes — a non-empty
#     `git status --porcelain` skips the worktree unconditionally.
#   - NEVER removes a worktree whose branch is NOT merged. "Merged" means
#     either ahead-count == 0 vs origin/main, OR the branch's associated
#     PR is MERGED on GitHub (squash-merge case — ahead-count stays > 0).
#   - ABORTS if `origin/main` cannot be refreshed via `git fetch` (silent
#     staleness is the #1 cause of false ahead=0 → spurious deletion of
#     unmerged work). Pass --allow-stale to opt back into local refs.
#   - With --check-active-sessions, also skips worktrees whose path appears
#     in any running `claude*` process command line.
#   - Uses plain `git worktree remove` (fails safe on dirty/locked trees),
#     never `--force`.
#   - Defaults to interactive prompt; --yes for non-interactive; --dry-run
#     for preview without deletion.
#
# Usage:
#   bash .claude/scripts/worktree-auto-prune.sh --dry-run --keep "$(git rev-parse --show-toplevel)"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --keep "$PATH_A" --keep "$PATH_B"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --check-active-sessions
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --allow-stale   # offline
#
# Tracking: https://github.com/sceneview/sceneview/issues/1242
#
# Exit codes:
#   0 = success (or no candidates)
#   1 = unexpected error (not "user said no", that's still 0)

set -euo pipefail

DRY_RUN=false
ASSUME_YES=false
KEEP_PATHS=()
ALLOW_STALE=false
CHECK_ACTIVE_SESSIONS=false

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --yes)     ASSUME_YES=true; shift ;;
        --keep)    KEEP_PATHS+=("${2:-}"); shift 2 ;;
        --keep=*)  KEEP_PATHS+=("${1#--keep=}"); shift ;;
        --allow-stale)           ALLOW_STALE=true; shift ;;
        --check-active-sessions) CHECK_ACTIVE_SESSIONS=true; shift ;;
        -h|--help)
            sed -n '2,33p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown flag: $1" >&2
            exit 1
            ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Resolve the MAIN repo root (the original checkout, not whatever
# linked worktree we're currently in). `git rev-parse --git-common-dir`
# always points at the shared `.git/` directory; its parent is the main
# working tree.
GIT_COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null || true)"
if [ -z "$GIT_COMMON_DIR" ]; then
    echo -e "${RED}Not inside a git repo.${NC}" >&2
    exit 1
fi
# git-common-dir may be relative — normalise to absolute.
GIT_COMMON_DIR="$(cd "$GIT_COMMON_DIR" && pwd)"
REPO_ROOT="$(dirname "$GIT_COMMON_DIR")"

# Normalise each --keep path to an absolute path.
KEEP_ABS=()
for kp in "${KEEP_PATHS[@]:-}"; do
    [ -z "$kp" ] && continue
    KEEP_ABS+=("$(cd "$kp" 2>/dev/null && pwd || echo "$kp")")
done

# is_kept <path> — true if the path matches any --keep argument.
is_kept() {
    local p="$1" k
    for k in "${KEEP_ABS[@]:-}"; do
        [ -n "$k" ] && [ "$p" = "$k" ] && return 0
    done
    return 1
}

# gh availability — only used for the merged-PR signal. The script must
# still work fully offline / without `gh`, falling back to ahead=0 only.
GH_AVAILABLE=false
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    GH_AVAILABLE=true
fi

# pr_is_merged <branch> — true if the branch has an associated MERGED PR.
# Degrades safely: any failure (offline, no gh, no PR) returns false.
pr_is_merged() {
    local branch="$1" state
    [ "$GH_AVAILABLE" = "true" ] || return 1
    state="$(gh pr view "$branch" --json state --jq .state 2>/dev/null || echo "")"
    [ "$state" = "MERGED" ]
}

# is_active_session <path> — true if any running `claude*` process has the
# worktree path on its command line. Heuristic: Claude Code passes the cwd
# and tool args containing the worktree path, so a fixed-string match
# against `pgrep -af claude` is a low-cost, low-false-positive signal.
# Only consulted when --check-active-sessions is set.
is_active_session() {
    local p="$1"
    command -v pgrep >/dev/null 2>&1 || return 1
    pgrep -af claude 2>/dev/null | grep -qF -- "$p"
}

WORKTREES_DIR="$REPO_ROOT/.claude/worktrees"

if [ ! -d "$WORKTREES_DIR" ]; then
    echo -e "${YELLOW}No worktrees directory at $WORKTREES_DIR — nothing to do.${NC}"
    exit 0
fi

echo -e "${CYAN}=== Worktree auto-prune ===${NC}"
echo "Repo root:     $REPO_ROOT"
echo "Worktrees dir: $WORKTREES_DIR"
if [ "${#KEEP_ABS[@]:-0}" -gt 0 ]; then
    for k in "${KEEP_ABS[@]}"; do
        echo "Keeping:       $k"
    done
fi
echo ""

# Refresh origin/main before computing any ahead-count. A stale `origin/main`
# is the #1 root cause of a branch with unmerged work being misclassified as
# `ahead=0` and then deleted (see https://github.com/sceneview/sceneview —
# session report 2026-05-20, `claude/v4.11.1-patch`). Default: abort if the
# fetch fails. Pass `--allow-stale` to opt back into local refs (offline use).
if ! git fetch --quiet origin main 2>/dev/null; then
    if [ "$ALLOW_STALE" = "true" ]; then
        echo -e "${YELLOW}Warning: fetch origin/main failed — proceeding with local refs (--allow-stale).${NC}"
    else
        echo -e "${RED}Error: couldn't fetch origin/main.${NC}" >&2
        echo "  A stale origin/main can make worktrees with unmerged work" >&2
        echo "  look like ahead=0 and be wrongly removed." >&2
        echo "  Re-run with --allow-stale to use local refs anyway." >&2
        exit 1
    fi
fi

CANDIDATES=()
SKIPPED_UNMERGED=()
SKIPPED_KEEP=()
SKIPPED_DIRTY=()
SKIPPED_ACTIVE=()

# Iterate worktrees registered with git (avoids stale dirs that aren't
# real worktrees, and respects locked-state).
# `git worktree list --porcelain` yields blocks separated by blank lines.
while IFS= read -r line; do
    case "$line" in
        worktree\ *)
            current_path="${line#worktree }"
            ;;
        branch\ *)
            current_branch="${line#branch refs/heads/}"
            ;;
        "")
            # End of a record — evaluate it.
            if [ -n "${current_path:-}" ]; then
                # Only consider worktrees under .claude/worktrees/
                case "$current_path" in
                    "$WORKTREES_DIR"/*)
                        # Skip --keep paths
                        if is_kept "$current_path"; then
                            SKIPPED_KEEP+=("$current_path")
                        # Dirty-tree check — uncommitted edits mean a session
                        # is mid-work. NEVER prune; data-loss risk (#1278).
                        elif [ -n "$(git -C "$current_path" status --porcelain 2>/dev/null)" ]; then
                            SKIPPED_DIRTY+=("$current_path (${current_branch:-detached} — uncommitted changes)")
                        # Active-session guard (opt-in via --check-active-sessions):
                        # a worktree path showing up in a live claude process is
                        # almost certainly mid-work even if currently clean.
                        elif [ "$CHECK_ACTIVE_SESSIONS" = "true" ] && is_active_session "$current_path"; then
                            SKIPPED_ACTIVE+=("$current_path (${current_branch:-detached} — active claude process)")
                        elif [ -n "${current_branch:-}" ]; then
                            # ahead-count: commits in branch not yet on origin/main
                            ahead=$(git rev-list --count "origin/main..$current_branch" 2>/dev/null || echo "unknown")
                            if [ "$ahead" = "0" ]; then
                                CANDIDATES+=("$current_path|$current_branch|ahead=0")
                            elif pr_is_merged "$current_branch"; then
                                # Squash-merge case: commits stay distinct
                                # (ahead>0) but the PR is merged → reclaimable.
                                CANDIDATES+=("$current_path|$current_branch|PR merged")
                            else
                                SKIPPED_UNMERGED+=("$current_path ($current_branch, ahead=$ahead)")
                            fi
                        else
                            # Detached HEAD or no branch — treat as unmerged for safety
                            SKIPPED_UNMERGED+=("$current_path (detached HEAD)")
                        fi
                        ;;
                esac
            fi
            current_path=""
            current_branch=""
            ;;
    esac
done < <(git worktree list --porcelain; echo "")

echo -e "${CYAN}--- Candidates (merged / ahead=0) ---${NC}"
if [ "${#CANDIDATES[@]}" -eq 0 ]; then
    echo "  (none)"
else
    for entry in "${CANDIDATES[@]}"; do
        path="${entry%%|*}"
        rest="${entry#*|}"
        branch="${rest%%|*}"
        reason="${rest#*|}"
        size=$(du -sh "$path" 2>/dev/null | awk '{print $1}')
        echo "  $path  [$branch]  $size  ($reason)"
    done
fi

if [ "${#SKIPPED_DIRTY[@]}" -gt 0 ]; then
    echo ""
    echo -e "${YELLOW}--- Skipped (uncommitted changes — DO NOT delete) ---${NC}"
    for entry in "${SKIPPED_DIRTY[@]}"; do
        echo "  $entry"
    done
fi

if [ "${#SKIPPED_ACTIVE[@]}" -gt 0 ]; then
    echo ""
    echo -e "${YELLOW}--- Skipped (active claude session — DO NOT delete) ---${NC}"
    for entry in "${SKIPPED_ACTIVE[@]}"; do
        echo "  $entry"
    done
fi

if [ "${#SKIPPED_UNMERGED[@]}" -gt 0 ]; then
    echo ""
    echo -e "${CYAN}--- Skipped (unmerged work — DO NOT delete) ---${NC}"
    for entry in "${SKIPPED_UNMERGED[@]}"; do
        echo "  $entry"
    done
fi

if [ "${#SKIPPED_KEEP[@]}" -gt 0 ]; then
    echo ""
    echo -e "${CYAN}--- Skipped (--keep) ---${NC}"
    for entry in "${SKIPPED_KEEP[@]}"; do
        echo "  $entry"
    done
fi

if [ "${#CANDIDATES[@]}" -eq 0 ]; then
    echo ""
    echo -e "${GREEN}Nothing to prune.${NC}"
    exit 0
fi

if [ "$DRY_RUN" = "true" ]; then
    echo ""
    echo -e "${YELLOW}DRY RUN — no worktrees removed.${NC}"
    exit 0
fi

if [ "$ASSUME_YES" != "true" ]; then
    echo ""
    printf "Remove %d worktree(s) listed above? [y/N] " "${#CANDIDATES[@]}"
    read -r reply
    case "$reply" in
        y|Y|yes|YES) ;;
        *) echo "Aborted."; exit 0 ;;
    esac
fi

REMOVED=0
FAILED=0
for entry in "${CANDIDATES[@]}"; do
    path="${entry%%|*}"
    # Plain `git worktree remove` (no --force): it fails safe on a dirty
    # or locked tree. A locked-but-clean worktree is unlocked first, then
    # removed plainly — the dirty check above already gated data safety.
    git worktree unlock "$path" 2>/dev/null || true
    if git worktree remove "$path" 2>/dev/null; then
        REMOVED=$((REMOVED + 1))
        echo -e "  ${GREEN}removed${NC} $path"
    else
        FAILED=$((FAILED + 1))
        echo -e "  ${RED}failed ${NC} $path (dirty/locked — left intact)"
    fi
done

# Clean up any dangling refs in .git/worktrees/
git worktree prune

echo ""
echo -e "${GREEN}=== Summary ===${NC}"
echo "  Removed: $REMOVED"
echo "  Failed:  $FAILED"
