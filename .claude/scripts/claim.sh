#!/usr/bin/env bash
#
# claim.sh — atomic issue-claim registry.
#
# Prevents parallel sessions / agents from duplicate-implementing the same issue
# (the #2300 claim-race). Two layers:
#   1. PRIMARY lock  — the GitHub `in-progress` label on the issue. GitHub is the
#                      serialization point, so this works ACROSS hosts and survives
#                      a crash. This is the real lock.
#   2. LOCAL mirror  — the `## IN-FLIGHT` table in the main worktree's
#                      `.claude/STATE.md`, for fast human-readable "who's on what".
#
# Before claiming, we also grep OPEN PRs (title / body / branch) for a reference to
# the issue — an open PR already addressing it is a soft collision (warn + refuse).
#
# Usage:
#   claim.sh <issue#|slug> [branch] [status]   # claim (refuses on collision)
#   claim.sh --check  <issue#|slug>            # report status, no mutation
#   claim.sh --release <issue#|slug>           # drop the claim (label + row)
#   claim.sh --list                            # print the IN-FLIGHT ledger
#   claim.sh --force <issue#|slug> [branch] [status]   # claim even on collision
#
# Exit: 0 = claimed / clear, 2 = collision (already claimed), 1 = error / bad usage.
#
# macOS-safe: no `flock` (absent on macOS) — a bounded, sleepless `mkdir` lock.

set -uo pipefail

REPO="${SCENEVIEW_REPO:-sceneview/sceneview}"
LABEL="in-progress"

# ── Resolve the canonical (main worktree) .claude/STATE.md ──────────────────────
# Shared across every worktree on this host: --git-common-dir points at the main
# repo's .git, whose parent is the main worktree root.
common_git="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "$common_git" ]; then
  MAIN_ROOT="$(dirname "$common_git")"
else
  MAIN_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
STATE="$MAIN_ROOT/.claude/STATE.md"

have_gh() { command -v gh >/dev/null 2>&1; }

# ── Genesis / self-heal ─────────────────────────────────────────────────────────
# Guarantee STATE.md has the IN-FLIGHT ledger scaffold, so existing_row()/insert_row()
# always work and the local collision check can NEVER silently no-op (#2342 review #6/#7).
# Seeds from the tracked .claude/STATE.md.template when present, else an embedded scaffold.
ensure_scaffold() {
  if [ -f "$STATE" ] && grep -q '<!-- claim-rows' "$STATE"; then return 0; fi
  local tmpl="$MAIN_ROOT/.claude/STATE.md.template"
  if [ ! -f "$STATE" ] && [ -f "$tmpl" ]; then
    cp "$tmpl" "$STATE" && echo "claim.sh: seeded $STATE from template." >&2
    return 0
  fi
  if [ ! -f "$STATE" ]; then
    {
      printf '# SceneView — Session State\n\n'
      printf '> Single live source of truth for "where are we". Gitignored. See .claude/workflows/README.md §5.\n\n'
      printf '## NOW\n\n'
      printf '## IN-FLIGHT\n| Key | Branch / worktree | Session | Claimed | Status |\n|---|---|---|---|---|\n'
      printf '%s\n\n' '<!-- claim-rows: claim.sh inserts new rows immediately ABOVE this line -->'
      printf '## NEXT\n\n'
      printf '## BOOTSTRAP\n```\ncat .claude/STATE.md\nbash .claude/scripts/sync-versions.sh\ngh issue list --repo sceneview/sceneview --label in-progress\n```\n'
    } > "$STATE"
    echo "claim.sh: seeded $STATE (embedded scaffold; no template found)." >&2
    return 0
  fi
  # STATE.md exists but lacks the ledger marker → append a proper IN-FLIGHT section.
  echo "claim.sh: WARN $STATE had no claim-rows ledger — appended one so collision detection works." >&2
  {
    printf '\n## IN-FLIGHT\n| Key | Branch / worktree | Session | Claimed | Status |\n|---|---|---|---|---|\n'
    printf '%s\n' '<!-- claim-rows: claim.sh inserts new rows immediately ABOVE this line -->'
  } >> "$STATE"
}

# ── Argument parsing ────────────────────────────────────────────────────────────
MODE="claim"
case "${1:-}" in
  --check)   MODE="check";   shift ;;
  --release) MODE="release"; shift ;;
  --list)    MODE="list" ;;
  --force)   MODE="force";   shift ;;
  "" )       echo "usage: claim.sh <issue#|slug> [branch] [status] | --check N | --release N | --list" >&2; exit 1 ;;
esac

if [ "$MODE" = "list" ]; then
  if [ -f "$STATE" ]; then
    awk '/^## IN-FLIGHT/{f=1} /^## NEXT/{f=0} f' "$STATE"
  else
    echo "(no STATE.md at $STATE)"
  fi
  exit 0
fi

RAW="${1:-}"
[ -z "$RAW" ] && { echo "claim.sh: missing issue#/slug" >&2; exit 1; }
# Normalize the key: a bare number becomes #N; anything else is a slug kept verbatim.
if printf '%s' "$RAW" | grep -Eq '^[0-9]+$'; then KEY="#$RAW"; ISSUE_NUM="$RAW"; else KEY="$RAW"; ISSUE_NUM=""; fi

BRANCH="${2:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')}"
STATUS="${3:-claimed}"
SESSION="${CLAUDE_SESSION_ID:-$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")}"
TODAY="$(date -u +%Y-%m-%d)"

# Genesis/self-heal the ledger before any read or mutation (--list stays a pure read).
case "$MODE" in claim|force|release|check) ensure_scaffold ;; esac

# ── STATE.md helpers (bounded sleepless mkdir lock) ─────────────────────────────
LOCK="$STATE.lock"
lock_acquire() {
  local i
  for i in $(seq 1 200); do
    if mkdir "$LOCK" 2>/dev/null; then trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT; return 0; fi
  done
  echo "claim.sh: WARN could not acquire $LOCK — proceeding best-effort" >&2
  return 0
}
lock_release() { rmdir "$LOCK" 2>/dev/null || true; trap - EXIT; }

# First IN-FLIGHT cell that equals KEY → prints the whole row, else empty.
existing_row() {
  [ -f "$STATE" ] || return 0
  awk -v key="$KEY" '
    /^## IN-FLIGHT/{s=1} /^## NEXT/{s=0}
    s && /^\|/ { l=$0; sub(/^\|[ ]*/,"",l); sub(/[ ]*\|.*/,"",l); if (l==key) print $0 }
  ' "$STATE"
}

remove_row() {
  [ -f "$STATE" ] || return 0
  awk -v key="$KEY" '
    /^## IN-FLIGHT/{s=1} /^## NEXT/{s=0}
    { if (s && /^\|/) { l=$0; sub(/^\|[ ]*/,"",l); sub(/[ ]*\|.*/,"",l); if (l==key) next } print }
  ' "$STATE" > "$STATE.tmp" && mv "$STATE.tmp" "$STATE"
}

insert_row() {
  local row="| $KEY | $BRANCH | $SESSION | $TODAY | $STATUS |"
  if [ -f "$STATE" ] && grep -q '<!-- claim-rows' "$STATE"; then
    awk -v row="$row" '/<!-- claim-rows/{print row} {print}' "$STATE" > "$STATE.tmp" && mv "$STATE.tmp" "$STATE"
  else
    echo "claim.sh: WARN no claim-rows marker in $STATE — appending row at EOF" >&2
    printf '%s\n' "$row" >> "$STATE"
  fi
}

# ── GitHub helpers ──────────────────────────────────────────────────────────────
open_pr_collision() {  # echoes a description if an OPEN PR references the issue
  have_gh || return 0
  [ -n "$ISSUE_NUM" ] || return 0
  gh pr list --repo "$REPO" --state open --json number,title,headRefName 2>/dev/null \
    | grep -Ei "(#${ISSUE_NUM}\b|[-/]${ISSUE_NUM}[-/]|fix-${ISSUE_NUM}\b)" || true
}

issue_has_label() {  # 0 if the issue already carries the in-progress label
  have_gh || return 1
  [ -n "$ISSUE_NUM" ] || return 1
  gh issue view "$ISSUE_NUM" --repo "$REPO" --json labels 2>/dev/null | grep -q "\"$LABEL\""
}

# ── Modes ───────────────────────────────────────────────────────────────────────
report() {
  echo "── claim status: $KEY ─────────────────────────────"
  local row; row="$(existing_row)"
  [ -n "$row" ] && echo "LOCAL ledger : $row" || echo "LOCAL ledger : (not claimed)"
  if have_gh && [ -n "$ISSUE_NUM" ]; then
    if issue_has_label; then echo "GitHub label : $LABEL PRESENT (claimed)"; else echo "GitHub label : absent"; fi
    local prs; prs="$(open_pr_collision)"
    [ -n "$prs" ] && { echo "Open PR(s)   :"; echo "$prs" | sed 's/^/  /'; } || echo "Open PR(s)   : none referencing #$ISSUE_NUM"
  else
    echo "GitHub       : (gh unavailable or slug key — label/PR checks skipped)"
  fi
}

case "$MODE" in
  check) report; exit 0 ;;
  list)  exit 0 ;;
  release)
    lock_acquire; remove_row; lock_release
    if have_gh && [ -n "$ISSUE_NUM" ]; then gh issue edit "$ISSUE_NUM" --repo "$REPO" --remove-label "$LABEL" >/dev/null 2>&1 || true; fi
    echo "released: $KEY"
    exit 0 ;;
  claim|force)
    # Collision detection (skipped on --force).
    if [ "$MODE" = "claim" ]; then
      row="$(existing_row)"
      if [ -n "$row" ]; then
        owner="$(printf '%s' "$row" | awk -F'|' '{gsub(/^ +| +$/,"",$4); print $4}')"
        if [ "$owner" != "$SESSION" ]; then
          echo "COLLISION: $KEY already claimed by another session:" >&2
          echo "  $row" >&2
          echo "Use --force to override, or pick another issue." >&2
          exit 2
        fi
      fi
      if issue_has_label; then
        echo "COLLISION: issue #$ISSUE_NUM already carries the '$LABEL' label (claimed elsewhere)." >&2
        echo "Use --force to override." >&2
        exit 2
      fi
      prs="$(open_pr_collision)"
      if [ -n "$prs" ]; then
        echo "COLLISION: an OPEN PR already references #$ISSUE_NUM:" >&2
        echo "$prs" | sed 's/^/  /' >&2
        echo "Use --force if you are intentionally iterating on it." >&2
        exit 2
      fi
    fi
    # Degraded-mode transparency (#2342 review #7): without the GitHub label the only
    # protection is the local ledger — loudly say so rather than silently under-protect.
    if ! have_gh || [ -z "$ISSUE_NUM" ]; then
      reason="$([ -z "$ISSUE_NUM" ] && echo 'slug key — no GitHub issue' || echo 'gh unavailable')"
      echo "claim.sh: WARN collision detection is LOCAL-LEDGER-ONLY ($reason — no cross-host '$LABEL' label). Another host could double-claim; verify manually for high-stakes work." >&2
    fi
    # Claim: GitHub label first (the real lock), then the local mirror.
    if have_gh && [ -n "$ISSUE_NUM" ]; then
      gh label create "$LABEL" --repo "$REPO" --color FBCA04 --description "Actively being worked by a session/agent" >/dev/null 2>&1 || true
      gh issue edit "$ISSUE_NUM" --repo "$REPO" --add-label "$LABEL" >/dev/null 2>&1 \
        && echo "GitHub: labelled #$ISSUE_NUM '$LABEL'" \
        || echo "claim.sh: WARN could not label #$ISSUE_NUM (continuing with local mirror)" >&2
    fi
    lock_acquire; remove_row; insert_row; lock_release
    echo "claimed: $KEY  →  $BRANCH  ($STATUS)"
    exit 0 ;;
esac
