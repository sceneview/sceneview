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

# Rows are COLLECTED, then printed LARGEST FIRST. The first version printed them
# in the order I happened to write them, so the biggest item sat in the middle
# behind a one-character `!` — and the reader (me) optimised the file at the top
# of the list instead of the file at the top of the cost. Sorting is the whole
# point of a budget report: it must answer "what do I cut next", not "did the
# thing I already cut get smaller".
ROWS=""

# row <path> <soft-ceiling-bytes> <why> [base-dir]
row() {
  local rel="$1" cap="$2" why="$3" f="${4:-$ROOT}/$1"
  if [ ! -f "$f" ]; then
    ROWS="$ROWS$(printf '%012d\t%s\t%s\t%s\t%s' 0 "$rel" "absent" "-" "$why")
"
    return
  fi
  local bytes flag
  bytes=$(wc -c < "$f" | tr -d ' ')
  TOTAL=$((TOTAL + bytes))
  flag="  "
  if [ "$cap" -gt 0 ] && [ "$bytes" -gt "$cap" ]; then
    flag="!!"
    OVER=$((OVER + 1))
    OVER_NAMES="${OVER_NAMES:+$OVER_NAMES, }$rel"
  fi
  ROWS="$ROWS$(printf '%012d\t%s\t%s\t%s\t%s' "$bytes" "$rel" "$(human "$bytes")" "$flag" "$why")
"
}

OVER_NAMES=""
row "CLAUDE.md"                  24576 "always loaded; ceiling gated in CI"
row ".claude/STATE.md"           16384 "session start; spec = NOW + IN-FLIGHT + NEXT(<=6) + BOOTSTRAP" "$MAIN"
row ".claude/workflows/README.md" 24576 "read when choosing a workflow"

# Derive the agent-memory slug from the MAIN checkout path — same rule the
# /status skill uses — rather than hardcoding one workstation's absolute path
# into a file committed to a public repo.
MEM="$HOME/.claude/projects/$(printf '%s' "$MAIN" | tr '/.' '--')/memory/MEMORY.md"
if [ -f "$MEM" ]; then
  b=$(wc -c < "$MEM" | tr -d ' '); TOTAL=$((TOTAL + b))
  ROWS="$ROWS$(printf '%012d\t%s\t%s\t%s\t%s' "$b" "MEMORY.md (agent memory index)" "$(human "$b")" "  " "always loaded; index only, topic files load on demand")
"
fi

echo "Standing context — re-sent on every turn of every session (largest first)"
echo
printf '%s' "$ROWS" | grep -v '^$' | sort -rn | while IFS="$(printf '\t')" read -r _b rel size flag why; do
  # 10# forces base-10: the sort key is zero-padded, and a leading zero would
  # otherwise make bash read it as octal and fail on any digit 8 or 9.
  printf "  %-34s %10s %s ~%5s tok  %s\n" "$rel" "$size" "$flag" "$(( 10#$_b / 4 ))" "$why"
done
echo
printf "  %-34s %10s    ~%5s tok\n" "TOTAL standing context" "$(human "$TOTAL")" "$((TOTAL / 4))"
echo
echo "  (token column is an ESTIMATE at ~4 chars/token — orders of magnitude only)"
if [ -n "$OVER_NAMES" ]; then
  echo
  echo "  !! OVER SPEC: $OVER_NAMES — this is the next thing to cut, not the"
  echo "     file you cut last time."
fi

# --- deferred is NOT free -------------------------------------------------
# The first version said the skills held N Ko "that is NOT in the standing cost",
# which reads as a saving. It is a DEFERRAL: a session pays per skill it opens,
# and one 15 Ko skill is ~19% of the whole standing budget. Measured on the first
# real use (#2986): one skill opened, 14.9 Ko paid. Print the price list.
if [ -d "$ROOT/.claude/skills" ]; then
  sb=0; sn=0
  for f in "$ROOT"/.claude/skills/*/SKILL.md; do
    [ -f "$f" ] || continue
    sb=$((sb + $(wc -c < "$f" | tr -d ' '))); sn=$((sn + 1))
  done
  echo
  echo "  Deferred — NOT free. $sn skills, $(human "$sb") total, charged per skill OPENED:"
  for f in "$ROOT"/.claude/skills/*/SKILL.md; do
    [ -f "$f" ] || continue
    b=$(wc -c < "$f" | tr -d ' ')
    printf '%012d\t%s\t%s\n' "$b" "$(basename "$(dirname "$f")")" "$(human "$b")"
  done | sort -rn | head -3 | while IFS="$(printf '\t')" read -r b name size; do
    printf "    %-24s %9s   %s%% of the standing budget if opened\n" \
      "$name" "$size" "$(( 100 * 10#$b / (TOTAL > 0 ? TOTAL : 1) ))"
  done
  echo "    (top 3 shown — open three of these and you are back to pre-split cost)"
fi

# --- the local half CI can never see --------------------------------------
HO="$MAIN/.claude/handoff.md"
if [ -f "$HO" ]; then
  # `stat -f %m` is BSD/macOS; on GNU coreutils -f means --file-system and %m is
  # read as a second FILE, so it prints a filesystem block to STDOUT before
  # failing — a plain `A || B` would concatenate that garbage into the number
  # and the arithmetic below would die. Validate the result is digits instead of
  # trusting the exit status.
  mtime="$(stat -f %m "$HO" 2>/dev/null || true)"
  case "$mtime" in
    ''|*[!0-9]*) mtime="$(stat -c %Y "$HO" 2>/dev/null || echo 0)" ;;
  esac
  case "$mtime" in ''|*[!0-9]*) mtime=0 ;; esac
  if [ "$mtime" -eq 0 ]; then
    age_days=-1
  else
    age_days=$(( ( $(date +%s) - mtime ) / 86400 ))
  fi
  echo
  if [ "$age_days" -lt 0 ]; then
    # Say so rather than printing a confident "0 days" — an unread mtime is not
    # a fresh handoff.
    echo "  handoff.md age UNREADABLE (no usable stat) — not graded."
  elif [ "$age_days" -ge 2 ]; then
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
  # Ordered by what the report ACTUALLY flagged, not by a fixed list. The first
  # version led with the CLAUDE.md remedy regardless of which file was over —
  # which is how the largest item got left alone through three passes.
  echo "  ${OVER} item(s) over spec — remedies for those items, in order:"
  case "$OVER_NAMES" in
    *STATE.md*)
      echo "    1. move finished NOW bullets into .claude/handoff.md (their documented home);"
      echo "       the spec is <=8 bullets and 'done' means MOVED, not kept"
      echo "    2. run /handoff to reconcile the rest" ;;
  esac
  case "$OVER_NAMES" in
    *CLAUDE.md*)
      echo "    - move a CLAUDE.md section into .claude/skills/<name>/SKILL.md + index it" ;;
  esac
fi

[ "$STRICT" -eq 1 ] && [ "$OVER" -gt 0 ] && exit 1
exit 0
