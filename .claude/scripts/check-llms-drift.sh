#!/usr/bin/env bash
#
# check-llms-drift.sh — verify the llms.txt mirror cannot drift.
#
# `llms.txt` (root) is the single source of truth for the SceneView API
# reference that LLMs and MCP servers consume. It has historically had two
# checked-in mirrors that had to be kept byte-identical — and both were a
# recurring source of CI-red drift on otherwise-clean PRs:
#
#   1. `docs/docs/llms.txt` — served by mkdocs at `/docs/llms.txt` (#899).
#      As of the CI-drift hardening it is NO LONGER COMMITTED: it is
#      `.gitignore`d and regenerated from root `llms.txt` at docs-build time
#      by `.github/workflows/docs.yml` (the "Mirror root llms.txt into docs"
#      step, before `mkdocs build`). Being a fresh copy on every build it is
#      fresh-by-construction and cannot drift — there is no committed copy to
#      compare against.
#
#   2. `mcp/src/generated/llms-txt.ts` — the embedded bundle shipped to the
#      Cloudflare-Workers MCP gateway and the npm `sceneview-mcp` package.
#      Made build-time-generated and `.gitignore`d in #1928 for the same
#      reason.
#
# Because BOTH mirrors are now generated artefacts, neither can drift from
# root `llms.txt`. This script is retained as a stable entrypoint for the
# quality gate: it verifies the structural invariant (no committed mirror)
# rather than diffing content. If a committed `docs/docs/llms.txt` ever
# reappears — e.g. an editor or `--fix` sweep recreates it and someone
# `git add`s it — the check fails loudly with a clear remediation message,
# so the gitignore-and-generate fix can never silently regress.
#
# Usage:
#   bash .claude/scripts/check-llms-drift.sh
#
# Exit code: 0 if the invariant holds, 1 if a committed mirror has reappeared.
#
# History: #1847 wired a content drift check here; the CI-drift hardening PR
# replaced it with this structural invariant check after `docs/docs/llms.txt`
# became build-generated.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "check-llms-drift.sh: not inside a git checkout" >&2
    exit 2
fi

LLMS="$ROOT/llms.txt"
DOCS_LLMS_REL="docs/docs/llms.txt"

ERRORS=0

# Colors (only when stdout is a TTY).
if [ -t 1 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    NC=''
fi

# ─── 1. Root llms.txt must exist ───────────────────────────────────────
if [ ! -f "$LLMS" ]; then
    echo "check-llms-drift.sh: root llms.txt missing — nothing to check" >&2
    exit 0
fi

# ─── 2. docs/docs/llms.txt must NOT be a committed mirror (issue #899) ──
# It is generated at docs-build time and `.gitignore`d. A committed copy
# would reintroduce the exact drift class this hardening removed: a PR
# touching root `llms.txt` forgetting to update the mirror.
if git -C "$ROOT" ls-files --error-unmatch "$DOCS_LLMS_REL" >/dev/null 2>&1; then
    echo -e "${RED}MISMATCH: $DOCS_LLMS_REL is committed again${NC}"
    echo "  $DOCS_LLMS_REL must NOT be tracked — it is generated from root"
    echo "  llms.txt at docs-build time and is .gitignore'd."
    echo "  Untrack it:  git rm --cached $DOCS_LLMS_REL"
    ERRORS=$((ERRORS + 1))
fi

# ─── 3. MCP bundle (issue #1928) ───────────────────────────────────────
# `mcp/src/generated/llms-txt.ts` is generated at build time and `.gitignore`d
# (same rationale). No content check needed.

if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}llms.txt mirror in sync (mirrors are build-generated, cannot drift)${NC}"
    exit 0
else
    exit 1
fi
