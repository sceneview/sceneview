#!/usr/bin/env bash
# Project statusline for SceneView. Wired via .claude/settings.json statusLine.
#
# Receives Claude Code context as JSON on stdin (cwd, model, etc.). Outputs ONE
# line of text shown at the bottom of the prompt — fast, no network calls.
#
# Displays:
#   <branch>[~worktree] · v<VERSION_NAME> · <free-RAM>GB free · <model>
#
# Worktree marker: a "~" prefix on the worktree's last path segment when the
# session runs inside .claude/worktrees/<slug>, so parallel sessions don't
# confuse which checkout they're editing (feedback_edit_files_in_correct_worktree).

set -eo pipefail

# Color helpers — Claude Code's statusline supports ANSI escapes. Use ANSI-C
# quoting so \033 becomes a real ESC byte (single-quoted '\033' is literal).
ESC=$'\033'
DIM="${ESC}[2m"
BOLD="${ESC}[1m"
GREEN="${ESC}[0;32m"
CYAN="${ESC}[0;36m"
YELLOW="${ESC}[0;33m"
RESET="${ESC}[0m"

# Read context (cwd, model.id, model.display_name available since 2024).
CTX="$(cat 2>/dev/null || true)"
MODEL_NAME="$(printf '%s' "$CTX" | /usr/bin/python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); print(d.get("model",{}).get("display_name",""))
except Exception:
    pass' 2>/dev/null)"

# Resolve repo root from cwd; fall back gracefully when not in a git tree.
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$ROOT" ]]; then
  printf '%b(not in git)%b · %s\n' "$DIM" "$RESET" "${MODEL_NAME:-Claude}"
  exit 0
fi

# Branch — short, omit "(HEAD detached at …)" verbosity. A detached HEAD is called out
# explicitly: in a repo worked through worktrees it is a real footgun (commits land on no
# branch, and the checkout silently serves weeks-old files), and a bare SHA does not say
# so. The main clone sat detached for three weeks before anyone noticed.
# `|| true` is load-bearing: under `set -e`, symbolic-ref's non-zero exit on a detached
# HEAD kills the whole statusline and prints nothing at all.
BRANCH="$(git -C "$ROOT" symbolic-ref --short HEAD 2>/dev/null || true)"
if [[ -z "$BRANCH" ]]; then
  BRANCH="detached@$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null)"
  DETACHED=1
fi

# Worktree marker: when ROOT lives under .claude/worktrees/, prefix the slug.
WORKTREE_MARKER=""
case "$ROOT" in
  */.claude/worktrees/*)
    SLUG="$(basename "$ROOT")"
    WORKTREE_MARKER="${DIM}~${SLUG}${RESET}"
    ;;
esac

# VERSION_NAME from gradle.properties — single source of truth.
VERSION=""
if [[ -f "$ROOT/gradle.properties" ]]; then
  VERSION="$(awk -F= '$1=="VERSION_NAME"{print $2; exit}' "$ROOT/gradle.properties" 2>/dev/null)"
fi

# Unsafe work — the two signals that answer "is anything of mine still stranded?".
# Both are local git, sub-10ms; deliberately SILENT when zero, so the line only speaks
# when something can actually be lost. This is what a /status turn used to cost a model
# call to find out (546 invocations in 30 days, measured 2026-08-11).
DIRTY="$( { git -C "$ROOT" status --porcelain 2>/dev/null || true; } | wc -l | tr -d ' ')"
[[ "$DIRTY" == "0" ]] && DIRTY=""
UNPUSHED="$(git -C "$ROOT" rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)"
[[ "$UNPUSHED" == "0" ]] && UNPUSHED=""

# Free RAM (GB) — useful for the emulator pool. macOS-only path; Linux fallback skipped.
FREE_RAM=""
if command -v vm_stat >/dev/null 2>&1; then
  # vm_stat reports pages of 4096 bytes; free + inactive + speculative ≈ usable.
  FREE_RAM="$(vm_stat 2>/dev/null | awk '
    /Pages free/                    {free=$3}
    /Pages inactive/                {inact=$3}
    /Pages speculative/             {spec=$3}
    END {gsub(/\./, "", free); gsub(/\./, "", inact); gsub(/\./, "", spec);
         printf "%.1f", (free+inact+spec)*4096/1024/1024/1024}')"
fi

# Compose the line — terse, single-line, no trailing newline (Claude adds one).
parts=()
if [[ -n "$BRANCH" ]]; then
  BRANCH_COLOR="$CYAN"; [[ -n "${DETACHED:-}" ]] && BRANCH_COLOR="$YELLOW"
  parts+=("${BRANCH_COLOR}${BRANCH}${RESET}${WORKTREE_MARKER}")
fi
[[ -n "$DIRTY" ]]      && parts+=("${YELLOW}●${DIRTY}${RESET}")
[[ -n "$UNPUSHED" ]]   && parts+=("${YELLOW}↑${UNPUSHED}${RESET}")
[[ -n "$VERSION" ]]    && parts+=("${GREEN}v${VERSION}${RESET}")
[[ -n "$FREE_RAM" ]]   && parts+=("${YELLOW}${FREE_RAM}GB free${RESET}")
[[ -n "$MODEL_NAME" ]] && parts+=("${DIM}${MODEL_NAME}${RESET}")

(IFS=' · '; printf '%s' "${parts[*]}")
