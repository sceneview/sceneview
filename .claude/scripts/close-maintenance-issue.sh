#!/usr/bin/env bash
# close-maintenance-issue.sh — close an auto-filed maintenance issue once the
# condition it describes has cleared.
#
# WHY THIS EXISTS
# ---------------
# Every auto-filer in maintenance.yml was one-directional: each one opens or
# refreshes a tracking issue while its condition holds, and not one of them ever
# closed it again — `gh issue close` appeared nowhere in the workflow. #2835
# ("sceneview-mcp npm is stale (4.0.14 < 4.0.15)") stayed open for 13 days after
# 4.0.15 was published, and would have stayed open forever.
#
# An open issue that no longer describes anything true is worse than no issue:
# it costs a maintainer a full investigation to discover there is nothing to do,
# and it teaches everyone that an open auto-filed issue means nothing. The
# auto-filers only carry signal if they are symmetric.
#
# SAFETY
# ------
# Closing is the one operation here that can destroy signal, so the lookup is
# deliberately narrow. It only ever matches an issue that is:
#   - OPEN,
#   - filed by `app/github-actions` (the bot — never a human's issue),
#   - labelled `maintenance` (every auto-filer applies it), and
#   - carrying the title fragment.
# An empty result is the normal case and exits 0.
#
# CALLERS MUST PASS A *MEASURED* CLEAR
# -----------------------------------
# Never call this on a bare "exit code 0". In this workflow a 0 means "clean OR
# credentials absent OR the tool never ran" for the store jobs, and `npm view`
# failing yields the same `lag=false` as a genuine match. Closing on an
# unmeasured 0 would silently retract a real, still-live finding the first time
# a secret expired. Each caller derives its own positive "measured and clear"
# signal and gates on that — see the `clean` / `resolved` step outputs.
#
# Usage: close-maintenance-issue.sh <title-fragment> <closing-comment> [--dry-run]
set -euo pipefail

TITLE_FRAGMENT="${1:-}"
COMMENT="${2:-}"
DRY_RUN="${3:-}"

if [ -z "$TITLE_FRAGMENT" ] || [ -z "$COMMENT" ]; then
    echo "usage: $(basename "$0") <title-fragment> <closing-comment> [--dry-run]" >&2
    exit 64
fi

NUMBER=$(gh issue list \
    --state open \
    --search "$TITLE_FRAGMENT in:title" \
    --author app/github-actions \
    --label maintenance \
    --json number --jq '.[0].number' 2>/dev/null || echo "")

if [ -z "$NUMBER" ] || [ "$NUMBER" = "null" ]; then
    echo "[close] no open auto-filed issue titled \"$TITLE_FRAGMENT\" — nothing to close."
    exit 0
fi

if [ "$DRY_RUN" = "--dry-run" ]; then
    echo "[close] [dry-run] would close #$NUMBER (\"$TITLE_FRAGMENT\") with: $COMMENT"
    exit 0
fi

# `--reason completed`: the condition was reconciled, not abandoned.
if gh issue close "$NUMBER" --reason completed --comment "$COMMENT"; then
    echo "[close] closed #$NUMBER — \"$TITLE_FRAGMENT\" no longer applies."
else
    # Advisory, exactly like the open/refresh side: a token without issue-write
    # must never fail the maintenance run.
    echo "::warning::Could not close auto-filed issue #$NUMBER ($TITLE_FRAGMENT)"
fi
