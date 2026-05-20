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
#     In --allow-stale mode, `ahead=0` alone is no longer sufficient —
#     candidates additionally require a MERGED PR signal (via `gh`) so
#     that a locally-advanced origin/main can't sneak through.
#   - Skips worktrees that have a live `claude`/`node` process with cwd
#     inside them. cwd (via `lsof -F n -d cwd`) is the authoritative
#     signal — argv-based heuristics get polluted by shell wrappers
#     that inject candidate paths into every process command line.
#     The scan is re-run right before the destructive loop to close the
#     prompt-window race. Pass --no-check-active-sessions to disable.
#   - Uses plain `git worktree remove` (fails safe on dirty/locked trees),
#     never `--force`.
#   - Defaults to interactive prompt; --yes for non-interactive; --dry-run
#     for preview without deletion.
#
# Usage:
#   bash .claude/scripts/worktree-auto-prune.sh --dry-run --keep "$(git rev-parse --show-toplevel)"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --keep "$PATH_A" --keep "$PATH_B"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --no-check-active-sessions
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
# Active-session guard is on by default — the PR (#1830) that introduced
# it was filed because a worktree was deleted out from under an active
# session. Opting out via --no-check-active-sessions is supported.
CHECK_ACTIVE_SESSIONS=true

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --yes)     ASSUME_YES=true; shift ;;
        --keep)    KEEP_PATHS+=("${2:-}"); shift 2 ;;
        --keep=*)  KEEP_PATHS+=("${1#--keep=}"); shift ;;
        --allow-stale)              ALLOW_STALE=true; shift ;;
        --check-active-sessions)    CHECK_ACTIVE_SESSIONS=true; shift ;;
        --no-check-active-sessions) CHECK_ACTIVE_SESSIONS=false; shift ;;
        -h|--help)
            sed -n '2,39p' "$0"
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
# git-common-dir may be relative — normalise to absolute. `pwd -P`
# resolves symlinks so it matches what `git worktree list --porcelain`
# returns (canonical paths).
GIT_COMMON_DIR="$(cd "$GIT_COMMON_DIR" && pwd -P)"
REPO_ROOT="$(dirname "$GIT_COMMON_DIR")"

# Normalise each --keep path to an absolute, symlink-resolved path so the
# string compare in is_kept matches what `git worktree list --porcelain`
# returns. A typo or non-existent path is fatal — better an error here
# than a worktree deleted because the operator's protective intent
# silently no-op'd.
KEEP_ABS=()
for kp in "${KEEP_PATHS[@]:-}"; do
    [ -z "$kp" ] && continue
    if ! abs=$(cd "$kp" 2>/dev/null && pwd -P); then
        echo -e "${RED}Error: --keep path does not exist or is not accessible: $kp${NC}" >&2
        exit 1
    fi
    KEEP_ABS+=("$abs")
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

# refresh_active_cwds — populate ACTIVE_CWDS with the cwd of every running
# `node` or `claude` process. Re-runnable. cwd is the authoritative signal
# (a session actually working in worktree X has X as cwd); argv-based
# heuristics get polluted by shell wrappers that inject candidate paths
# into every process command line.
#
# `lsof -F n` is machine-readable: each record is `n<path>` on its own
# line, so paths containing spaces survive intact (an `awk $NF` parse
# would split them).
refresh_active_cwds() {
    ACTIVE_CWDS=()
    [ "$CHECK_ACTIVE_SESSIONS" = "true" ] || return 0
    if ! command -v lsof >/dev/null 2>&1; then
        echo -e "${YELLOW}Warning: lsof not found — active-session check disabled.${NC}"
        CHECK_ACTIVE_SESSIONS=false
        return 0
    fi
    local cwd
    while IFS= read -r cwd; do
        [ -n "$cwd" ] && ACTIVE_CWDS+=("$cwd")
    done < <(
        {
            lsof -F n -d cwd -a -c node   2>/dev/null
            lsof -F n -d cwd -a -c claude 2>/dev/null
        } | awk '/^n\// {print substr($0, 2)}' | sort -u
    )
}

# is_active_session <path> — true if any cwd in ACTIVE_CWDS is equal to
# or descended from <path>.
is_active_session() {
    local p="$1" cwd
    for cwd in "${ACTIVE_CWDS[@]:-}"; do
        case "$cwd" in
            "$p"|"$p"/*) return 0 ;;
        esac
    done
    return 1
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
        echo "  Check your network / VPN / GitHub auth, or re-run offline:" >&2
        echo "    bash .claude/scripts/worktree-auto-prune.sh --allow-stale ..." >&2
        exit 1
    fi
fi

CANDIDATES=()
SKIPPED_UNMERGED=()
SKIPPED_KEEP=()
SKIPPED_DIRTY=()
SKIPPED_ACTIVE=()

# Pre-compute the cwd of every running claude/node process for the
# candidate-evaluation pass. Re-scanned again right before the
# destructive loop to close the prompt-window race.
ACTIVE_CWDS=()
refresh_active_cwds

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
                        # A clean tree can still be mid-work (test runner,
                        # IDE indexing, agent paused on input) — cwd of a
                        # live node/claude process is the truth.
                        elif [ "$CHECK_ACTIVE_SESSIONS" = "true" ] && is_active_session "$current_path"; then
                            SKIPPED_ACTIVE+=("$current_path (${current_branch:-detached} — active claude session)")
                        elif [ -n "${current_branch:-}" ]; then
                            # ahead-count: commits in branch not yet on origin/main
                            ahead=$(git rev-list --count "origin/main..$current_branch" 2>/dev/null || echo "unknown")
                            if [ "$ahead" = "0" ] && [ "$ALLOW_STALE" = "true" ]; then
                                # Local origin/main can be artificially ahead
                                # (manual update-ref, prior bad fetch). In
                                # --allow-stale mode, require an explicit
                                # merged-PR signal before reclaiming on a
                                # bare ahead=0.
                                if pr_is_merged "$current_branch"; then
                                    CANDIDATES+=("$current_path|$current_branch|PR merged (stale-mode)")
                                else
                                    SKIPPED_UNMERGED+=("$current_path ($current_branch, ahead=0 — no merged-PR signal under --allow-stale)")
                                fi
                            elif [ "$ahead" = "0" ]; then
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
    echo "  (Disable with --no-check-active-sessions if you're sure.)"
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

# Race-recheck: a new session may have started in a candidate during
# the prompt or between the initial scan and now. Re-run the cwd scan
# and re-filter; anything newly-active is dropped from this pass.
refresh_active_cwds

REMOVED=0
FAILED=0
SKIPPED_RACE=0
for entry in "${CANDIDATES[@]}"; do
    path="${entry%%|*}"
    rest="${entry#*|}"
    branch="${rest%%|*}"
    if [ "$CHECK_ACTIVE_SESSIONS" = "true" ] && is_active_session "$path"; then
        SKIPPED_RACE=$((SKIPPED_RACE + 1))
        echo -e "  ${YELLOW}skipped${NC} $path ($branch — active claude session detected after prompt)"
        continue
    fi
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
[ "$SKIPPED_RACE" -gt 0 ] && echo "  Skipped (race-recheck active): $SKIPPED_RACE"
