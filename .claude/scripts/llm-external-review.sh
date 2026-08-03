#!/bin/bash
#
# llm-external-review.sh — cross-vendor ADVISORY second opinion on a diff.
#
# Runs non-Claude LLM CLIs (codex, gemini/agy) as INDEPENDENT reviewers over a
# change and prints a combined Markdown report on stdout. This is the daily-driver
# entry point that review-fanout / triptych / /review call to add an outside voice.
#
# ⛔ ADVISORY ONLY — by contract this NEVER gates a merge. It is a signal for the
#    Claude reviewers (the real gate) and the maintainer, nothing more. It always
#    exits 0 (a provider that is missing/unauthenticated degrades to an honest
#    "SKIPPED" line, never a failure), so a caller can never mistake "no external
#    opinion available" for "external opinion says merge".
#
# Usage:
#   bash .claude/scripts/llm-external-review.sh [--diff <ref>] [--pr <n>] \
#        [--providers codex,gemini] [--summary "one line"]
#
#   --diff <ref>   diff spec for `git diff <ref>` (default: main...HEAD)
#   --pr <n>       review a GitHub PR by number (diff via `gh pr diff`)
#   --providers    comma list (default: codex,gemini). kimi excluded (no sandbox).
#   --summary      optional one-line change description fed to the reviewers
#
# Filesystem access (measured 2026-07-23, see .claude/plans/multi-llm-delegation.md):
#   - codex reads the workspace natively under --sandbox read-only → it gets the
#     diff spec and explores the surrounding code itself (richer review).
#   - gemini/agy is filesystem-blind headless → it gets the diff text INLINE.
#
set -u

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
DELEGATE="$ROOT/.claude/scripts/llm-delegate.sh"
DIFF_REF="main...HEAD"
PR=""
PROVIDERS="codex,gemini"
SUMMARY=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --diff) DIFF_REF="${2:-}"; shift ;;
    --pr) PR="${2:-}"; shift ;;
    --providers) PROVIDERS="${2:-}"; shift ;;
    --summary) SUMMARY="${2:-}"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

[ -x "$DELEGATE" ] || { echo "ERROR: $DELEGATE not found/executable" >&2; exit 2; }

# --- Acquire the diff text (used inline for gemini; codex gets the spec) ---
DIFF_TEXT=""
TARGET_DESC=""
if [ -n "$PR" ]; then
  TARGET_DESC="GitHub PR #$PR"
  DIFF_TEXT="$(gh pr diff "$PR" --repo sceneview/sceneview 2>/dev/null)"
  [ -n "$DIFF_TEXT" ] || { echo "ERROR: empty diff for PR #$PR (gh auth? PR exists?)" >&2; exit 2; }
  CODEX_SCOPE="the change in GitHub PR #$PR. Get it with: gh pr diff $PR --repo sceneview/sceneview"
else
  TARGET_DESC="diff $DIFF_REF"
  git fetch origin --quiet 2>/dev/null || true
  DIFF_TEXT="$(git diff "$DIFF_REF" 2>/dev/null)"
  [ -n "$DIFF_TEXT" ] || { echo "ERROR: empty diff for '$DIFF_REF' — nothing to review" >&2; exit 2; }
  CODEX_SCOPE="the change in \`git diff $DIFF_REF\`. Read the whole diff AND the surrounding code."
fi

SUMMARY_LINE=""
[ -n "$SUMMARY" ] && SUMMARY_LINE="Change summary: $SUMMARY"

REVIEW_TASK="You are an INDEPENDENT, skeptical reviewer of a SceneView change — you did NOT write it. SceneView is an AI-first 3D/AR SDK (Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; shared Kotlin core). $SUMMARY_LINE
Report ONLY genuine issues, most severe first, each as: SEVERITY(blocker|warning) file:line — problem — suggested fix. Reproduce any runtime/compile claim before asserting it; do not invent findings. If the change looks clean, reply with exactly: LGTM. Be terse — this is an advisory second opinion, not the gate."

# --- Header ---
echo "## External advisory review — $TARGET_DESC"
echo
echo "> ⛔ ADVISORY ONLY. Cross-vendor second opinion; it does NOT gate the merge."
echo "> The Claude reviewers (review-fanout / triptych) remain the authority."
echo

IFS=',' read -r -a PROV_ARR <<< "$PROVIDERS"
ANY_RAN=0
for prov in "${PROV_ARR[@]}"; do
  prov="$(echo "$prov" | tr -d '[:space:]')"
  [ -n "$prov" ] || continue
  echo "### ${prov}"
  case "$prov" in
    codex)
      OUT="$(bash "$DELEGATE" codex "${REVIEW_TASK}

Review ${CODEX_SCOPE}" 2>/tmp/llm-ext-codex.err)"
      RC=$?
      ;;
    gemini)
      # filesystem-blind → feed the diff inline via --context stdin.
      # Measured 2026-07-23: headless agy is UNRELIABLE on large inline diffs (empty
      # output / hangs — it attempts an auto-denied tool call). Cap it honestly; codex
      # (native fs) still reviews any size. Threshold overridable via GEMINI_MAX_DIFF_BYTES.
      DIFF_BYTES=$(printf '%s' "$DIFF_TEXT" | wc -c | tr -d ' ')
      GEMINI_MAX="${GEMINI_MAX_DIFF_BYTES:-40000}"
      if [ "$DIFF_BYTES" -gt "$GEMINI_MAX" ]; then
        echo "_SKIPPED — diff ${DIFF_BYTES}B > ${GEMINI_MAX}B: headless agy is unreliable on large inline diffs (measured). codex covers this change._"
        echo; continue
      fi
      # Pure-reasoning mode: forbid tools (a headless tool call is auto-denied → empty output).
      OUT="$(printf '%s' "$DIFF_TEXT" | bash "$DELEGATE" gemini --context - "${REVIEW_TASK}

Review the diff in the context block above. Do NOT run any shell commands or tools — reason PURELY from the provided text (headless mode cannot run tools)." 2>/tmp/llm-ext-gemini.err)"
      RC=$?
      ;;
    kimi)
      OUT="$(printf '%s' "$DIFF_TEXT" | bash "$DELEGATE" kimi --context - "${REVIEW_TASK}

Review the diff provided in the context block above." 2>/tmp/llm-ext-kimi.err)"
      RC=$?
      ;;
    *)
      echo "_unknown provider '$prov' — skipped_"; echo; continue ;;
  esac

  if [ "$RC" = "3" ]; then
    # honest SKIP (not installed / not authenticated)
    REASON="$(cat /tmp/llm-ext-$prov.err 2>/dev/null | sed -n 's/^SKIP: //p' | head -1)"
    echo "_SKIPPED — ${REASON:-unavailable}_"
  elif [ "$RC" != "0" ] || [ -z "$OUT" ]; then
    echo "_ERROR — provider failed (rc=$RC); treated as no-opinion (advisory)_"
  else
    printf '%s\n' "$OUT"
    ANY_RAN=1
  fi
  echo
done

if [ "$ANY_RAN" = "0" ]; then
  echo "_No external provider produced an opinion — advisory step is a clean no-op._"
fi

exit 0
