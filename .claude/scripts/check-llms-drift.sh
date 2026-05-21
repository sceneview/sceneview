#!/usr/bin/env bash
#
# check-llms-drift.sh — block PRs that introduce llms.txt mirror drift.
#
# `llms.txt` (root) is the single source of truth for the SceneView API
# reference that LLMs and MCP servers consume. One checked-in mirror must
# stay byte-identical to it:
#
#   1. `docs/docs/llms.txt` — served by mkdocs (issue #899).
#
# A second consumer, `mcp/src/generated/llms-txt.ts` (the embedded bundle
# shipped to the Cloudflare-Workers MCP gateway and the npm `sceneview-mcp`
# package), used to be a committed mirror too — and was checked here. As of
# issue #1928 that file is no longer committed: it is generated at build time
# by `mcp/scripts/generate-llms-txt.js` (wired into the `prebuild` / `prepare`
# / `test` npm lifecycle scripts) and `.gitignore`d. Because it is now
# fresh-by-construction on every build, publish and test run, it can never
# drift from root `llms.txt` — there is no committed copy to compare against
# — so it no longer needs a drift check.
#
# PR #1815 wired the drift detector into `sync-versions.sh`. But
# `sync-versions.sh` is NOT invoked by `quality-gate.sh` (the PR-blocking CI
# gate), so PR #1822 was able to land `DepthHitResultNode` docs in root
# `llms.txt` without updating the mirror — the drift sat on `main` for 2
# commits until #1817 healed it (#1847).
#
# This script extracts the drift check into a focused, fast helper that the
# quality gate can call directly. It only reports drift (no `--fix`); use
# `bash .claude/scripts/sync-versions.sh --fix` to regenerate the mirror.
#
# Usage:
#   bash .claude/scripts/check-llms-drift.sh
#
# Exit code: 0 if the mirror is in sync, 1 if it has drifted.
#
# Closes #1847.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "check-llms-drift.sh: not inside a git checkout" >&2
    exit 2
fi

LLMS="$ROOT/llms.txt"
DOCS_LLMS="$ROOT/docs/docs/llms.txt"

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

# ─── 1. docs/docs/llms.txt mirror (issue #899) ─────────────────────────
if [ ! -f "$LLMS" ]; then
    echo "check-llms-drift.sh: root llms.txt missing — nothing to check" >&2
    exit 0
fi

if [ -f "$DOCS_LLMS" ]; then
    if ! diff -q "$LLMS" "$DOCS_LLMS" >/dev/null 2>&1; then
        echo -e "${RED}MISMATCH: docs/docs/llms.txt has drifted from root llms.txt${NC}"
        echo "  Run: bash .claude/scripts/sync-versions.sh --fix"
        echo "  Diff (first 5 hunks):"
        # `head -N` closes the pipe early; suppress SIGPIPE so the script keeps
        # running the MCP bundle check below.
        { diff -u "$LLMS" "$DOCS_LLMS" 2>&1 || true; } | head -25 | sed 's/^/    /'
        ERRORS=$((ERRORS + 1))
    fi
fi

# ─── 2. MCP bundle (issue #1928) ───────────────────────────────────────
# `mcp/src/generated/llms-txt.ts` is no longer a committed mirror — it is
# generated at build time (`npm install` / `npm run build` / `npm test` /
# `npm publish` all run `mcp/scripts/generate-llms-txt.js`) and `.gitignore`d.
# It is therefore fresh-by-construction and cannot drift; no check is needed.

if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}llms.txt mirror in sync${NC}"
    exit 0
else
    exit 1
fi
