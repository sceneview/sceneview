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
#   - Skips worktrees that have a live process with cwd inside them
#     (#1834: any process — `node`, `claude`, `java`/gradle, `python`,
#     `pytest`, IDE indexer, `bash` test runner, …). cwd (via
#     `lsof -F n -d cwd`) is the authoritative signal — argv-based
#     heuristics get polluted by shell wrappers that inject candidate
#     paths into every process command line. The scan is re-run right
#     before the destructive loop to close the prompt-window race. The
#     scan is wrapped in `timeout 10s` (#1839) so a hung `lsof` cannot
#     hang the script. Pass --no-check-active-sessions to disable.
#   - Skips worktrees marked `locked` via `git worktree lock` (#1833) —
#     the lock signals intentional preservation ("keeping for next
#     sprint"). Pass --unlock-locked to opt back into pruning a locked
#     tree (the previous behaviour); the dirty / active-session checks
#     still gate data safety.
#   - Uses plain `git worktree remove` (fails safe on dirty/locked trees),
#     never `--force`.
#   - Defaults to interactive prompt; --yes for non-interactive; --dry-run
#     for preview without deletion.
#   - Writes one JSON line per evaluated worktree to
#     `~/.claude/logs/worktree-prune-$(date +%Y%m%d).log` (rotated daily,
#     never auto-deleted) — forensic evidence if a future incident
#     occurs (#1839).
#
# Usage:
#   bash .claude/scripts/worktree-auto-prune.sh --dry-run --keep "$(git rev-parse --show-toplevel)"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --keep "$PATH_A" --keep "$PATH_B"
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --no-check-active-sessions
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --allow-stale     # offline
#   bash .claude/scripts/worktree-auto-prune.sh --yes  --unlock-locked   # force-prune locked
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
# Locked-worktree skip is on by default (#1833) — `git worktree lock`
# is the operator's "I'll come back to this clean tree later" signal.
# --unlock-locked restores the pre-#1833 unconditional-unlock behaviour.
UNLOCK_LOCKED=false

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --yes)     ASSUME_YES=true; shift ;;
        --keep)    KEEP_PATHS+=("${2:-}"); shift 2 ;;
        --keep=*)  KEEP_PATHS+=("${1#--keep=}"); shift ;;
        --allow-stale)              ALLOW_STALE=true; shift ;;
        --check-active-sessions)    CHECK_ACTIVE_SESSIONS=true; shift ;;
        --no-check-active-sessions) CHECK_ACTIVE_SESSIONS=false; shift ;;
        --unlock-locked)            UNLOCK_LOCKED=true; shift ;;
        -h|--help)
            sed -n '2,49p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown flag: $1" >&2
            exit 1
            ;;
    esac
done

# Forensic log path (#1839). Daily-rotated, never auto-deleted: one JSON
# line per worktree evaluated this run. Cheap to write, priceless during
# a post-incident reconstruction.
FORENSIC_LOG_DIR="${HOME}/.claude/logs"
FORENSIC_LOG="${FORENSIC_LOG_DIR}/worktree-prune-$(date +%Y%m%d).log"
mkdir -p "$FORENSIC_LOG_DIR" 2>/dev/null || true

# json_escape <string> — minimal JSON string escape (quotes, backslashes,
# control chars). Enough for the few free-form fields we emit.
json_escape() {
    local s="${1:-}"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\t'/\\t}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\r'/\\r}"
    printf '%s' "$s"
}

# log_decision <path> <branch> <decision> <reason> [extra-kv-pairs...]
# Appends one JSON line to the forensic log. Failures are silent — the
# log is best-effort, never a hard dependency.
log_decision() {
    local path="$1" branch="$2" decision="$3" reason="$4"; shift 4 || true
    local extras=""
    while [ $# -ge 2 ]; do
        extras="${extras},\"$(json_escape "$1")\":\"$(json_escape "$2")\""
        shift 2
    done
    local ts
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    {
        printf '{"ts":"%s","path":"%s","branch":"%s","decision":"%s","reason":"%s","allow_stale":%s,"check_active":%s,"unlock_locked":%s,"dry_run":%s%s}\n' \
            "$ts" \
            "$(json_escape "$path")" \
            "$(json_escape "$branch")" \
            "$(json_escape "$decision")" \
            "$(json_escape "$reason")" \
            "$ALLOW_STALE" \
            "$CHECK_ACTIVE_SESSIONS" \
            "$UNLOCK_LOCKED" \
            "$DRY_RUN" \
            "$extras"
    } >>"$FORENSIC_LOG" 2>/dev/null || true
}

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

# Bulk-prefetch merged PR head refs (#1839 item 2). The previous
# implementation shelled out to `gh pr view <branch>` once per
# candidate — with N stale worktrees that's N × ~500ms. One batched
# `gh pr list` covers the whole forest. Mirrors the same pattern in
# `cleanup-branches-worktrees.sh:151`. Newline-separated for portable
# `printf '%s\n' ... | grep -Fxq` lookup (no associative array → bash 3.2
# on macOS still works).
MERGED_PR_BRANCHES=""
if [ "$GH_AVAILABLE" = "true" ]; then
    MERGED_PR_BRANCHES="$(gh pr list --state merged --limit 2000 \
        --json headRefName --jq '.[].headRefName' 2>/dev/null || echo "")"
fi

# pr_is_merged <branch> — true if the branch has an associated MERGED PR.
# Uses the pre-fetched MERGED_PR_BRANCHES set; degrades safely to a single
# `gh pr view` only if the bulk prefetch returned nothing (cold-cache
# fallback, also covers very-old branches beyond the 2000-PR limit).
pr_is_merged() {
    local branch="$1" state
    [ "$GH_AVAILABLE" = "true" ] || return 1
    if [ -n "$MERGED_PR_BRANCHES" ]; then
        printf '%s\n' "$MERGED_PR_BRANCHES" | grep -Fxq -- "$branch" && return 0
        # Not in the bulk list → the branch is NOT a merged PR. No need
        # to fall back: 2000 PRs is several years of history on this repo.
        return 1
    fi
    # Bulk prefetch failed (gh transient error) — fall back to per-branch
    # query so we don't regress to "every branch unmerged" silently.
    state="$(gh pr view "$branch" --json state --jq .state 2>/dev/null || echo "")"
    [ "$state" = "MERGED" ]
}

# refresh_active_cwds — populate ACTIVE_CWDS with the cwd of every running
# process on the host (#1834). The previous filter (`-c node`/`-c claude`)
# missed gradle daemons (`java`), Python venvs, `pytest`, `bash` test
# runners, and IDE indexers whose parent claude/node session had already
# exited. cwd is the authoritative signal (the process is actually inside
# the worktree); argv-based heuristics get polluted by shell wrappers
# that inject candidate paths into every process command line.
#
# `lsof -F n` is machine-readable: each record is `n<path>` on its own
# line, so paths containing spaces survive intact (an `awk $NF` parse
# would split them, per `feedback_lsof_machine_readable.md`).
#
# Wrapped in `timeout 10s` (#1839 item 3): on a busy host with thousands
# of open files lsof can hang. On timeout we proceed with an empty
# ACTIVE_CWDS and a yellow warning rather than hanging the whole prune.
refresh_active_cwds() {
    ACTIVE_CWDS=()
    [ "$CHECK_ACTIVE_SESSIONS" = "true" ] || return 0
    local scanner=""
    if command -v lsof >/dev/null 2>&1; then
        scanner="lsof"
    elif command -v fuser >/dev/null 2>&1; then
        scanner="fuser"
    else
        echo -e "${YELLOW}Warning: neither lsof nor fuser found — active-session check disabled.${NC}"
        CHECK_ACTIVE_SESSIONS=false
        return 0
    fi

    # Helper: pick a `timeout` binary if available. macOS doesn't ship
    # `timeout` by default — `gtimeout` (from `coreutils` on brew) is the
    # common alias. Degrade gracefully to no timeout if neither exists.
    # Using a string instead of an array avoids `set -u` empty-array
    # unbound-variable noise across bash 3.2 / 5.x.
    local timeout_cmd=""
    if command -v timeout >/dev/null 2>&1; then
        timeout_cmd="timeout 10s"
    elif command -v gtimeout >/dev/null 2>&1; then
        timeout_cmd="gtimeout 10s"
    fi

    local cwd raw rc
    if [ "$scanner" = "lsof" ]; then
        # Scan every process's cwd (no -c filter, per #1834). Resilient to
        # SIP-protected processes which lsof can't introspect (stderr
        # discarded). Exit code is non-zero whenever any process was
        # skipped, so we accept any rc and trust the stdout we captured.
        # shellcheck disable=SC2086  # intentional word-split on $timeout_cmd
        raw="$( $timeout_cmd lsof -F n -d cwd 2>/dev/null || true )"
        rc=$?
    else
        # fuser fallback: enumerate /proc/*/cwd symlinks. Linux-only, but
        # if we're here lsof is missing, so we're almost certainly on
        # Linux CI. Resolves to absolute paths via readlink.
        raw=""
        if [ -d /proc ]; then
            local pid_cwd
            for pid_cwd in /proc/[0-9]*/cwd; do
                [ -L "$pid_cwd" ] || continue
                cwd="$(readlink "$pid_cwd" 2>/dev/null || true)"
                [ -n "$cwd" ] && raw="${raw}n${cwd}"$'\n'
            done
        fi
        rc=0
    fi

    # Detect the `timeout` 124 exit code (lsof hung past 10s) — surface
    # a warning but proceed with whatever cwds we already collected.
    if [ "${rc:-0}" = "124" ]; then
        echo -e "${YELLOW}Warning: lsof scan exceeded 10s ceiling — active-session check may be incomplete.${NC}"
    fi

    while IFS= read -r cwd; do
        [ -n "$cwd" ] && ACTIVE_CWDS+=("$cwd")
    done < <(printf '%s\n' "$raw" | awk '/^n\// {print substr($0, 2)}' | sort -u)
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
SKIPPED_LOCKED=()

# Pre-compute the cwd of every running claude/node process for the
# candidate-evaluation pass. Re-scanned again right before the
# destructive loop to close the prompt-window race.
ACTIVE_CWDS=()
refresh_active_cwds

# Iterate worktrees registered with git (avoids stale dirs that aren't
# real worktrees, and respects locked-state).
# `git worktree list --porcelain` yields blocks separated by blank lines.
# A `locked [reason]` line appears between `worktree` and the blank
# separator when the worktree is locked via `git worktree lock`.
while IFS= read -r line; do
    case "$line" in
        worktree\ *)
            current_path="${line#worktree }"
            ;;
        branch\ *)
            current_branch="${line#branch refs/heads/}"
            ;;
        locked|locked\ *)
            current_locked=true
            # Capture the optional reason — `locked some text` → "some text".
            if [ "$line" = "locked" ]; then
                current_lock_reason=""
            else
                current_lock_reason="${line#locked }"
            fi
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
                            log_decision "$current_path" "${current_branch:-}" "SKIPPED_KEEP" "matches --keep"
                        # Dirty-tree check — uncommitted edits mean a session
                        # is mid-work. NEVER prune; data-loss risk (#1278).
                        elif [ -n "$(git -C "$current_path" status --porcelain 2>/dev/null)" ]; then
                            SKIPPED_DIRTY+=("$current_path (${current_branch:-detached} — uncommitted changes)")
                            log_decision "$current_path" "${current_branch:-}" "SKIPPED_DIRTY" "uncommitted changes"
                        # Locked-worktree skip (#1833). The lock is the operator's
                        # "keep this clean tree for later" signal — auto-prune
                        # respects it unless --unlock-locked is passed. The dirty
                        # check above wins (a locked-dirty tree still goes to
                        # SKIPPED_DIRTY) so this guard never weakens data safety.
                        elif [ "${current_locked:-false}" = "true" ] && [ "$UNLOCK_LOCKED" != "true" ]; then
                            SKIPPED_LOCKED+=("$current_path (${current_branch:-detached} — locked${current_lock_reason:+: $current_lock_reason})")
                            log_decision "$current_path" "${current_branch:-}" "SKIPPED_LOCKED" "${current_lock_reason:-no-reason}"
                        # A clean tree can still be mid-work (test runner,
                        # IDE indexing, agent paused on input) — cwd of a
                        # live process is the truth (any process, #1834).
                        elif [ "$CHECK_ACTIVE_SESSIONS" = "true" ] && is_active_session "$current_path"; then
                            SKIPPED_ACTIVE+=("$current_path (${current_branch:-detached} — active session)")
                            log_decision "$current_path" "${current_branch:-}" "SKIPPED_ACTIVE" "live process cwd inside worktree"
                        elif [ -n "${current_branch:-}" ]; then
                            # ahead-count: commits in branch not yet on origin/main
                            ahead=$(git rev-list --count "origin/main..$current_branch" 2>/dev/null || echo "unknown")
                            merged_pr=$(pr_is_merged "$current_branch" && echo "true" || echo "false")
                            if [ "$ahead" = "0" ] && [ "$ALLOW_STALE" = "true" ]; then
                                # Local origin/main can be artificially ahead
                                # (manual update-ref, prior bad fetch). In
                                # --allow-stale mode, require an explicit
                                # merged-PR signal before reclaiming on a
                                # bare ahead=0.
                                if [ "$merged_pr" = "true" ]; then
                                    CANDIDATES+=("$current_path|$current_branch|PR merged (stale-mode)")
                                    log_decision "$current_path" "$current_branch" "CANDIDATE" "PR merged (stale-mode)" "ahead" "$ahead" "pr_merged" "$merged_pr"
                                else
                                    SKIPPED_UNMERGED+=("$current_path ($current_branch, ahead=0 — no merged-PR signal under --allow-stale)")
                                    log_decision "$current_path" "$current_branch" "SKIPPED_UNMERGED" "ahead=0 + no merged-PR under --allow-stale" "ahead" "$ahead" "pr_merged" "$merged_pr"
                                fi
                            elif [ "$ahead" = "0" ]; then
                                CANDIDATES+=("$current_path|$current_branch|ahead=0")
                                log_decision "$current_path" "$current_branch" "CANDIDATE" "ahead=0" "ahead" "$ahead" "pr_merged" "$merged_pr"
                            elif [ "$merged_pr" = "true" ]; then
                                # Squash-merge case: commits stay distinct
                                # (ahead>0) but the PR is merged → reclaimable.
                                CANDIDATES+=("$current_path|$current_branch|PR merged")
                                log_decision "$current_path" "$current_branch" "CANDIDATE" "PR merged" "ahead" "$ahead" "pr_merged" "$merged_pr"
                            else
                                SKIPPED_UNMERGED+=("$current_path ($current_branch, ahead=$ahead)")
                                log_decision "$current_path" "$current_branch" "SKIPPED_UNMERGED" "ahead=$ahead, no merged PR" "ahead" "$ahead" "pr_merged" "$merged_pr"
                            fi
                        else
                            # Detached HEAD or no branch — treat as unmerged for safety
                            SKIPPED_UNMERGED+=("$current_path (detached HEAD)")
                            log_decision "$current_path" "" "SKIPPED_UNMERGED" "detached HEAD"
                        fi
                        ;;
                esac
            fi
            current_path=""
            current_branch=""
            current_locked=false
            current_lock_reason=""
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
    echo -e "${YELLOW}--- Skipped (active session — DO NOT delete) ---${NC}"
    echo "  (Disable with --no-check-active-sessions if you're sure.)"
    for entry in "${SKIPPED_ACTIVE[@]}"; do
        echo "  $entry"
    done
fi

if [ "${#SKIPPED_LOCKED[@]}" -gt 0 ]; then
    echo ""
    echo -e "${CYAN}--- Skipped (locked — operator-marked, preserved) ---${NC}"
    echo "  (Pass --unlock-locked to override.)"
    for entry in "${SKIPPED_LOCKED[@]}"; do
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
        echo -e "  ${YELLOW}skipped${NC} $path ($branch — active session detected after prompt)"
        log_decision "$path" "$branch" "SKIPPED_RACE" "active session detected after prompt"
        continue
    fi
    # Plain `git worktree remove` (no --force): it fails safe on a dirty
    # or locked tree. The locked-tree check upstream already routed
    # operator-locked trees to SKIPPED_LOCKED, so the only locked
    # candidates reaching this loop are those allowed under
    # --unlock-locked — for those we explicitly unlock first.
    if [ "$UNLOCK_LOCKED" = "true" ]; then
        git worktree unlock "$path" 2>/dev/null || true
    fi
    if git worktree remove "$path" 2>/dev/null; then
        REMOVED=$((REMOVED + 1))
        echo -e "  ${GREEN}removed${NC} $path"
        log_decision "$path" "$branch" "REMOVED" "git worktree remove succeeded"
    else
        FAILED=$((FAILED + 1))
        echo -e "  ${RED}failed ${NC} $path (dirty/locked — left intact)"
        log_decision "$path" "$branch" "FAILED" "git worktree remove failed (dirty/locked)"
    fi
done

# Clean up any dangling refs in .git/worktrees/
git worktree prune

echo ""
echo -e "${GREEN}=== Summary ===${NC}"
echo "  Removed: $REMOVED"
echo "  Failed:  $FAILED"
[ "$SKIPPED_RACE" -gt 0 ] && echo "  Skipped (race-recheck active): $SKIPPED_RACE"
