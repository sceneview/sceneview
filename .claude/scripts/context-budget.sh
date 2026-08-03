#!/usr/bin/env bash
# Report what EVERY session in this repo pays before it does any work.
#
# WHY THIS EXISTS
#   `agent-cost-report.sh` measures what was spent. This measures what will be
#   spent, and why — the standing context every session re-sends on every turn.
#   Measured 2026-08-03, before the skills split: CLAUDE.md 72.7 Ko + STATE.md
#   62.2 Ko + MEMORY.md 19.4 Ko + workflows/README.md 19.5 Ko = ~174 Ko of
#   preamble, re-sent forever, growing monotonically because nothing ever
#   reported it. That is the step-3 bottleneck ("ensuring tokens are used
#   efficiently as usage increases") in its most literal form.
#
#   This is a REPORT, not a gate. The committed half is gated by
#   test-context-budget.sh in CI; the local half (STATE.md, handoff.md) cannot
#   be — both are gitignored, so CI has never seen them and never will.
#
# HONESTY ABOUT THE NUMBERS
#   Bytes are measured. The token column is an ESTIMATE at ~4 chars/token and
#   is labelled as such: the real tokeniser is not available here, and a
#   precise-looking token count that nothing produced would be a fabrication.
#   Use it for orders of magnitude and for before/after deltas, never as truth.
#
# Usage: bash .claude/scripts/context-budget.sh [--strict]
#   --strict  exit non-zero when a file is over its documented spec

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# STATE.md and handoff.md are GITIGNORED, so every worktree carries its own
# stale copy of them while the real ones live in the main checkout. Reading the
# worktree's copy reports a handoff as days old moments after it was written.
# Resolve them from the main repo, the way the /status skill does.
# Test the git output BEFORE dirname: `dirname ""` is `.`, which is a real
# directory, so the guard would pass and silently resolve against $PWD — the
# very stale-copy bug this block exists to prevent.
GIT_COMMON="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "$GIT_COMMON" ] && [ -d "$GIT_COMMON" ]; then
  MAIN="$(dirname "$GIT_COMMON")"
else
  MAIN="$ROOT"
  echo "  (not a git checkout, or git too old for --path-format — STATE/handoff read from $ROOT)"
fi

STRICT=0
[ "${1:-}" = "--strict" ] && STRICT=1

OVER=0
TOTAL=0

human() { awk -v b="$1" 'BEGIN{ printf (b<1024) ? "%d B" : "%.1f Ko", (b<1024)?b:b/1024 }'; }

# row <path> <soft-ceiling-bytes> <why> [base-dir]
row() {
  local rel="$1" cap="$2" why="$3" f="${4:-$ROOT}/$1"
  if [ ! -f "$f" ]; then
    printf "  %-34s %10s   %s\n" "$rel" "absent" "$why"
    return
  fi
  local bytes lines flag
  bytes=$(wc -c < "$f" | tr -d ' ')
  lines=$(wc -l < "$f" | tr -d ' ')
  TOTAL=$((TOTAL + bytes))
  flag="   "
  if [ "$cap" -gt 0 ] && [ "$bytes" -gt "$cap" ]; then
    flag=" ! "
    OVER=$((OVER + 1))
  fi
  printf "  %-34s %10s %s ~%5s tok  %4s l  %s\n" \
    "$rel" "$(human "$bytes")" "$flag" "$((bytes / 4))" "$lines" "$why"
}

echo "Standing context — re-sent on every turn of every session"
echo
row "CLAUDE.md"                  24576 "always loaded; ceiling gated in CI"
row ".claude/STATE.md"           16384 "session start; spec = NOW + IN-FLIGHT + NEXT(<=6) + BOOTSTRAP" "$MAIN"
row ".claude/workflows/README.md" 24576 "read when choosing a workflow"

MEM="$HOME/.claude/projects/-Users-thomasgorisse-Projects-sceneview/memory/MEMORY.md"
if [ -f "$MEM" ]; then
  b=$(wc -c < "$MEM" | tr -d ' '); TOTAL=$((TOTAL + b))
  printf "  %-34s %10s     ~%5s tok  %4s l  %s\n" \
    "MEMORY.md (agent memory index)" "$(human "$b")" "$((b / 4))" "$(wc -l < "$MEM" | tr -d ' ')" \
    "always loaded; index only, topic files load on demand"
fi

echo
printf "  %-34s %10s     ~%5s tok\n" "TOTAL standing context" "$(human "$TOTAL")" "$((TOTAL / 4))"
echo
echo "  (token column is an ESTIMATE at ~4 chars/token — orders of magnitude only)"

# --- lazy surface, for contrast -------------------------------------------
if [ -d "$ROOT/.claude/skills" ]; then
  sb=0; sn=0
  for f in "$ROOT"/.claude/skills/*/SKILL.md; do
    [ -f "$f" ] || continue
    sb=$((sb + $(wc -c < "$f" | tr -d ' '))); sn=$((sn + 1))
  done
  echo
  printf "  %s skills hold %s (~%s tok) that is NOT in the standing cost.\n" \
    "$sn" "$(human "$sb")" "$((sb / 4))"
fi

# --- the local half CI can never see --------------------------------------
HO="$MAIN/.claude/handoff.md"
if [ -f "$HO" ]; then
  age_days=$(( ( $(date +%s) - $(stat -f %m "$HO" 2>/dev/null || stat -c %Y "$HO") ) / 86400 ))
  echo
  if [ "$age_days" -ge 2 ]; then
    echo "  ! handoff.md last written ${age_days} days ago."
    echo "    It is gitignored, so no CI check has ever looked at it. A session that"
    echo "    ends without reconciling it leaves the next one to rebuild context by"
    echo "    reading git log — which is the expensive path this file exists to avoid."
    OVER=$((OVER + 1))
  else
    echo "  handoff.md written ${age_days} day(s) ago."
  fi
fi

if [ "$OVER" -gt 0 ]; then
  echo
  echo "  ${OVER} item(s) over spec. Remedies, cheapest first:"
  echo "    - move a CLAUDE.md section into .claude/skills/<name>/SKILL.md + index it"
  echo "    - move finished STATE.md entries into .claude/handoff.md (its documented home)"
  echo "    - run /handoff to reconcile STATE.md -> handoff.md"
fi

[ "$STRICT" -eq 1 ] && [ "$OVER" -gt 0 ] && exit 1
exit 0
