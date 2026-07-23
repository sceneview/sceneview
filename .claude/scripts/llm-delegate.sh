#!/bin/bash
#
# llm-delegate.sh — single entry point to delegate a task to a non-Claude LLM CLI.
#
# Providers:
#   codex   — OpenAI Codex CLI   (`codex exec`,   auth: `codex login` / OPENAI_API_KEY)
#   gemini  — Google Antigravity (`agy -p`,       auth: interactive `agy` Google sign-in)
#   kimi    — Moonshot Kimi Code (`kimi --print`, auth: interactive `kimi` / MOONSHOT_API_KEY)
#
# Usage:
#   bash .claude/scripts/llm-delegate.sh <codex|gemini|kimi> [--write] [--model M] \
#        [--context FILE]... [--timeout SECS] "prompt"
#   echo "long prompt" | bash .claude/scripts/llm-delegate.sh codex -
#   git diff | bash .claude/scripts/llm-delegate.sh gemini --context - "Review this diff, be terse."
#
# Filesystem access differs by provider (measured 2026-07-23):
#   - codex  reads workspace files NATIVELY under --sandbox read-only. Just pass a task.
#   - gemini (agy -p) CANNOT run shell tools in headless mode (auto-denied). Feed file
#     content INLINE via --context FILE (repeatable; '-' = stdin). The orchestrator
#     reads the bytes; agy only reasons. Never let agy roam the filesystem headless.
#   - kimi   can read files but has no sandbox; keep it read-only by prompt discipline.
#
# Contract (designed for orchestration from Claude Code):
#   - READ-ONLY by default. --write is only honored inside a throwaway checkout
#     (a .claude/worktrees/* tree or a /tmp clone) — never in the main checkout.
#   - External LLMs NEVER commit/push. They produce text (answer, diff, report)
#     on stdout; the orchestrator reviews and applies.
#   - Honest SKIP: exit 3 + "SKIP:" line when the CLI is missing or not
#     authenticated. NOTE: agy and kimi exit 0 even when unauthenticated, so
#     auth is detected by probing output signatures, never by exit code alone.
#   - Exit codes: 0 = answer produced · 1 = provider error · 2 = usage · 3 = SKIP.
#
set -u

PROVIDER="${1:-}"; shift 2>/dev/null || true
WRITE=0
MODEL=""
TIMEOUT_SECS="${LLM_DELEGATE_TIMEOUT:-600}"
PROMPT=""
CONTEXT=""

append_context() {
  # $1 = file path, or '-' for stdin. Appends a fenced block to CONTEXT.
  local src="$1" body
  if [ "$src" = "-" ]; then body="$(cat)"; else
    [ -f "$src" ] || { echo "ERROR: --context file not found: $src" >&2; exit 2; }
    body="$(cat "$src")"
  fi
  CONTEXT="$CONTEXT

--- context: $src ---
$body
--- end context ---"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --write) WRITE=1 ;;
    --model) MODEL="${2:-}"; shift ;;
    --timeout) TIMEOUT_SECS="${2:-600}"; shift ;;
    --context) append_context "${2:-}"; shift ;;
    -) PROMPT="$(cat)" ;;
    *) PROMPT="$1" ;;
  esac
  shift
done

usage() { echo "usage: llm-delegate.sh <codex|gemini|kimi> [--write] [--model M] [--context FILE].. [--timeout SECS] \"prompt\" (or '-' for stdin)" >&2; exit 2; }
skip()  { echo "SKIP: $1" >&2; exit 3; }
[ -n "$PROVIDER" ] || usage
[ -n "$PROMPT" ] || usage

# Inline context: appended to the prompt so filesystem-blind providers (agy -p)
# can reason over file/diff content the orchestrator supplies.
[ -n "$CONTEXT" ] && PROMPT="$PROMPT
$CONTEXT"

export PATH="$HOME/.local/bin:$PATH"

# timeout: coreutils `timeout` or `gtimeout`; degrade to no-timeout with a warning.
TIMEOUT_BIN=""
command -v timeout >/dev/null 2>&1 && TIMEOUT_BIN="timeout"
[ -z "$TIMEOUT_BIN" ] && command -v gtimeout >/dev/null 2>&1 && TIMEOUT_BIN="gtimeout"
[ -z "$TIMEOUT_BIN" ] && echo "WARN: no timeout binary — running unbounded" >&2
run_bounded() { if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" "$TIMEOUT_SECS" "$@"; else "$@"; fi; }

# --write guard: only in a throwaway tree, never the main checkout.
if [ "$WRITE" = "1" ]; then
  TOP="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
  case "$TOP" in
    */.claude/worktrees/*|/tmp/*|/private/tmp/*) : ;;
    *) echo "ERROR: --write refused outside a throwaway worktree or /tmp clone (cwd tree: $TOP)" >&2; exit 2 ;;
  esac
fi

case "$PROVIDER" in
  codex)
    command -v codex >/dev/null 2>&1 || skip "codex CLI not installed (npm i -g @openai/codex)"
    codex login status >/dev/null 2>&1 || skip "codex not authenticated — run: codex login"
    SANDBOX="read-only"; [ "$WRITE" = "1" ] && SANDBOX="workspace-write"
    OUT="$(mktemp)"
    MODEL_ARGS=""; [ -n "$MODEL" ] && MODEL_ARGS="--model $MODEL"
    # shellcheck disable=SC2086
    run_bounded codex exec --sandbox "$SANDBOX" $MODEL_ARGS --color never \
      --output-last-message "$OUT" "$PROMPT" >&2
    RC=$?
    [ $RC -ne 0 ] && { echo "ERROR: codex exec failed (rc=$RC)" >&2; rm -f "$OUT"; exit 1; }
    cat "$OUT"; rm -f "$OUT"
    ;;

  gemini)
    command -v agy >/dev/null 2>&1 || skip "Antigravity CLI not installed (curl -fsSL https://antigravity.google/cli/install.sh | bash)"
    # agy exits 0 even when signed out — probe by output signature.
    if agy models </dev/null 2>&1 | grep -qi "sign in"; then
      skip "Antigravity not authenticated — run: agy (interactive Google sign-in)"
    fi
    AGY_ARGS="--sandbox"
    [ "$WRITE" = "1" ] && AGY_ARGS="--dangerously-skip-permissions"
    [ -n "$MODEL" ] && AGY_ARGS="$AGY_ARGS --model $MODEL"
    # shellcheck disable=SC2086
    RESPONSE="$(run_bounded agy -p "$PROMPT" $AGY_ARGS </dev/null 2>/dev/null)"
    RC=$?
    { [ $RC -ne 0 ] || [ -z "$RESPONSE" ]; } && { echo "ERROR: agy -p failed (rc=$RC)" >&2; exit 1; }
    printf '%s\n' "$RESPONSE"
    ;;

  kimi)
    command -v kimi >/dev/null 2>&1 || skip "kimi-cli not installed (uv tool install kimi-cli)"
    KIMI_ARGS=""
    [ "$WRITE" = "1" ] && KIMI_ARGS="--yolo"
    [ -n "$MODEL" ] && KIMI_ARGS="$KIMI_ARGS --model $MODEL"
    # shellcheck disable=SC2086
    RESPONSE="$(run_bounded kimi --print --final-message-only $KIMI_ARGS -p "$PROMPT" </dev/null 2>/dev/null)"
    RC=$?
    # kimi exits 0 even unauthenticated — "LLM not set" is the signed-out signature.
    printf '%s' "$RESPONSE" | grep -qi "LLM not set" && skip "kimi not authenticated — run: kimi (interactive setup) or set MOONSHOT_API_KEY"
    { [ $RC -ne 0 ] || [ -z "$RESPONSE" ]; } && { echo "ERROR: kimi --print failed (rc=$RC)" >&2; exit 1; }
    printf '%s\n' "$RESPONSE"
    ;;

  *) usage ;;
esac
