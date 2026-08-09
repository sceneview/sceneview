#!/usr/bin/env bash
#
# check-sceneview-swift-urls.sh — block PRs that reintroduce references to the
# archived `sceneview/sceneview-swift` SPM mirror.
#
# The `sceneview/sceneview-swift` mirror was retired in PR #1215: SPM consumers
# now resolve `SceneViewSwift` directly from the monorepo root `Package.swift`.
# The mirror repo is archived read-only. Any NEW occurrence of the
# `sceneview-swift` URL in docs / config / samples / generated bundles is a
# bug — a stale paste from old docs or an AI-suggestion regression — and would
# point users at a dead resolution path.
#
# This gate greps the tracked tree for `sceneview-swift` and fails on any hit
# outside the historical / attribution allowlist below.
#
# ── Extending the allowlist ──────────────────────────────────────────────
# If a NEW file legitimately needs a historical `sceneview-swift` mention
# (e.g. a future changelog entry, a migration note, an attribution comment),
# add its exact repo-root-relative path to the ALLOW regex below in the same
# commit. Each allowed file is there for a documented reason:
#   - Package.swift                       — comment explaining the monorepo SPM root
#   - .github/workflows/release.yml       — comment about the retired mirror
#   - CLAUDE.md                           — historical changelog narrative
#   - SceneViewSwift/.../SceneView.swift   — "Ported from ... sceneview-swift PR #1" attribution
#   - CHANGELOG.md                        — historical version entries (mirror retirement)
#   - changelog.d/*.md                    — the PRE-IMAGE of CHANGELOG.md (see below)
#   - docs/docs/migration.md              — migration guide quoting the old mirror URL
#   - .claude/scripts/check-sceneview-swift-urls.sh — this detector itself
#   - .github/workflows/ci.yml            — the repo-hygiene job comment that documents this detector
#
# `changelog.d/*.md` is allowed for exactly the reason `CHANGELOG.md` is, and
# omitting it was a hole opened by the move to fragments: `collate-changelog.sh`
# merges each fragment INTO `CHANGELOG.md` and then deletes it, so a fragment is
# literally tomorrow's changelog text. Without this, a release note that explains
# the mirror's retirement is blocked while it lives in `changelog.d/` and becomes
# allowed the moment it is collated — the same sentence, two verdicts. Fixed in
# #3068, whose own fragment tripped it. Prose about a dead URL is not a live
# pointer to one; a fragment that ships an actual install snippet for the mirror
# is still wrong, and review is the check for that.
#
# Usage:
#   bash .claude/scripts/check-sceneview-swift-urls.sh
#
# Exit code: 0 if clean, 1 if a non-allowlisted reference is found.
#
# Closes #1237.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
  echo "Not inside a git repo." >&2
  exit 0
fi
cd "$ROOT"

# Files where a historical `sceneview-swift` reference is allowed. Anchored
# repo-root-relative paths, alternation joined with '|'.
ALLOW='^(Package\.swift|\.github/workflows/release\.yml|\.github/workflows/ci\.yml|CLAUDE\.md|SceneViewSwift/Sources/SceneViewSwift/SceneView\.swift|CHANGELOG\.md|changelog\.d/[^/]+\.md|docs/docs/migration\.md|\.claude/scripts/check-sceneview-swift-urls\.sh)$'

# Grep only tracked files so build output / node_modules can't trip the gate.
# `git grep` respects .gitignore by definition and is fast on large trees.
HITS=$(git grep -l 'sceneview-swift' -- \
         '*.md' '*.txt' '*.yml' '*.yaml' '*.swift' '*.kt' \
         '*.json' '*.html' '*.js' '*.ts' \
         2>/dev/null | grep -vE "$ALLOW" || true)

if [ -n "$HITS" ]; then
  echo "::error::Files reintroduced 'sceneview-swift' references — the SPM mirror is archived (#1237)."
  echo ""
  echo "  The 'sceneview/sceneview-swift' mirror was retired in PR #1215."
  echo "  SPM consumers resolve SceneViewSwift from the monorepo root"
  echo "  Package.swift — use 'https://github.com/sceneview/sceneview' instead."
  echo ""
  echo "  Offending file(s):"
  echo "$HITS" | sed 's/^/    /'
  echo ""
  echo "  If a reference is genuinely historical, add the file path to the"
  echo "  ALLOW regex in .claude/scripts/check-sceneview-swift-urls.sh."
  exit 1
fi

echo "check-sceneview-swift-urls: OK — no non-historical mirror references."
exit 0
