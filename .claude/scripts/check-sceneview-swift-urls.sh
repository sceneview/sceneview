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
#   - .claude/scripts/test-check-sceneview-swift-urls.sh — its self-test, whose fixtures ARE mirror pins
#   - .claude/scripts/impact-check.sh                   — its sibling SPM gate, whose comments explain the split of duty
#   - .claude/scripts/test-impact-check.sh              — that gate's self-test
#   - .claude/scripts/quality-gate.sh                   — the consumer that runs this detector and parses its output
#   - .claude/scripts/sync-versions.sh                  — the SPM updater, which still recognises the legacy mirror clause
#   - .claude/skills/automation-map/SKILL.md — the skill row indexing this detector
#   - .github/workflows/ci.yml            — the repo-hygiene job comment that documents this detector
#
# `changelog.d/*.md` is allowed for exactly the reason `CHANGELOG.md` is, and
# omitting it was a hole opened by the move to fragments: `collate-changelog.sh`
# merges each fragment INTO `CHANGELOG.md` and then deletes it, so a fragment is
# literally tomorrow's changelog text. Without this, a release note that explains
# the mirror's retirement is blocked while it lives in `changelog.d/` and becomes
# allowed the moment it is collated — the same sentence, two verdicts. Fixed in
# #3068, whose own fragment tripped it.
#
# Prose about a dead URL is not a live pointer to one — but "review will catch
# an install snippet in a fragment" is a hope, not a gate, and CHANGELOG.md and
# changelog.d/ are the only two entries here allowlisted WHOLESALE rather than
# by exact path. So they get a second, narrower pass: the mirror may be NAMED,
# never PINNED. A line where a version constraint sits adjacent to the mirror
# URL is a copy-pasteable install line pointing at an archived repo, which is
# the exact harm this gate exists to prevent, and no amount of surrounding
# release-note prose makes it resolve. A bare `git clone` of the mirror is the
# same harm with no version in sight, so the fetch verbs count as pins too.

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
ALLOW='^(Package\.swift|\.github/workflows/release\.yml|\.github/workflows/ci\.yml|CLAUDE\.md|SceneViewSwift/Sources/SceneViewSwift/SceneView\.swift|CHANGELOG\.md|changelog\.d/[^/]+\.md|docs/docs/migration\.md|\.claude/scripts/check-sceneview-swift-urls\.sh|\.claude/scripts/test-check-sceneview-swift-urls\.sh|\.claude/scripts/impact-check\.sh|\.claude/scripts/test-impact-check\.sh|\.claude/scripts/quality-gate\.sh|\.claude/scripts/sync-versions\.sh|\.claude/skills/automation-map/SKILL\.md)$'

# Surfaces where the mirror may be NAMED but never PINNED. The two changelog
# entries are the only ones allowlisted WHOLESALE (any fragment, current or
# future), so they carry the most risk; the automation-map row is here because
# a doc indexing this gate must quote the string it is about, and has no
# business shipping a resolvable install line either.
#
# `test-check-sceneview-swift-urls.sh` is deliberately NOT in this list: its
# fixtures ARE pins, by design — that is how the second pass is proven to fire.
#
# The `changelog.d/` entry is FLAT in ALLOW (`[^/]+\.md`) but NESTED in this
# pathspec (git's `*` crosses `/`), and the asymmetry is the safe way round on
# purpose: a hypothetical `changelog.d/sub/x.md` is NOT wholesale-allowlisted
# by pass 1 — it just fails — while pass 2 still refuses to let it ship a pin.
# Widening ALLOW to match would hand a subdirectory the wholesale exemption.
PROSE_ONLY=(
    'CHANGELOG.md'
    'changelog.d/*.md'
    '.claude/skills/automation-map/SKILL.md'
)

# A live install snippet: the mirror URL with a version constraint next to it.
# Near-adjacency, never `.*` — a permissive gap makes every prose sentence that
# happens to contain "from" a false positive (measured on the sibling SPM gate:
# 25 files matched, 10 of them plain prose).
SNIPPET='sceneview-swift(\.git)?['"'"'"`]?[,)]?[[:space:]]*[.(]?[[:space:]]*(from|upToNextMajor|upToNextMinor|exact)[[:space:]]*[:(]'

# A version constraint is not the only way to ship a runnable line. `git clone
# https://…/sceneview-swift.git` carries no constraint at all and still fails
# the moment someone pastes it, so the FETCH VERB is a pin in its own right.
# The verb is anchored BEFORE the URL, which is what keeps this specific: a
# paragraph that merely says the word "clone" is not a command, and `clone`
# alone is not in the alternation. The gap is ONE whitespace-free token —
# an argument, not prose. `.*` was tried and is exactly the mistake SNIPPET
# already documents: grep bounds a match to a physical line, and a line can
# be a whole paragraph. It fired on this PR's own changelog fragment, where
# `git clone` and the literal repo path sit in one bullet with sentences
# between them, and turned the gate red against itself.
#
# NOT `[^\n]*` either: in POSIX ERE that is the class "neither backslash nor
# the letter n", which the URL itself contains, so the pattern silently never
# fires. It was written that way first, and case 7 caught it.
#
# The target is the REPO form `sceneview/sceneview-swift`, not the bare token:
# this detector's own filename contains `sceneview-swift`, so any doc line
# that mentions `curl` and then names the script matched — measured on this
# repo, the automation-map row failed the gate. Only the org-qualified path
# can actually be cloned.
FETCH='(git[[:space:]]+clone|curl|wget|svn[[:space:]]+checkout)[[:space:]]+[^[:space:]]*sceneview/sceneview-swift'

# SPM also accepts a KEYWORD-LESS range: `.package(url: "…", "4.0.0"..<"5.0.0")`.
# It names no constraint, so neither of the two patterns above sees it, and it
# is as copy-pasteable as any other install line. Same near-adjacency rule: a
# version literal next to the URL, followed by a range operator.
RANGE='sceneview-swift(\.git)?['"'"'"`]?[,)]?[[:space:]]*['"'"'"`]?[0-9][0-9.]*['"'"'"`]?[[:space:]]*\.\.[.<]'

# Grep only tracked files so build output / node_modules can't trip the gate.
# `git grep` respects .gitignore by definition and is fast on large trees.
#
# `*.sh` is in the list because a shell script is the one surface where a dead
# URL is not a bad paste but a broken command — a setup or install script that
# curls / clones the archived mirror fails at run time. It was missing, which
# also made the two `.sh` entries in ALLOW dead surface: the gate scripts they
# name were scanned by neither pass, so the comment above documented a
# protection that did not exist. Four more gate scripts join them now, each for
# the same reason as the detector itself — they mention the string BECAUSE
# their own logic is about it.
HITS=$(git grep -l 'sceneview-swift' -- \
         '*.md' '*.txt' '*.yml' '*.yaml' '*.swift' '*.kt' \
         '*.json' '*.html' '*.js' '*.ts' '*.sh' \
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

# Second pass: the two WHOLESALE-allowlisted surfaces may name the mirror, but
# must never ship a resolvable pin to it.
PINS=$(git grep -nE -e "$SNIPPET" -e "$FETCH" -e "$RANGE" -- "${PROSE_ONLY[@]}" 2>/dev/null || true)

if [ -n "$PINS" ]; then
  echo "::error::A prose-only surface ships a live SPM pin to the ARCHIVED 'sceneview-swift' mirror (#1237)."
  echo ""
  echo "  Naming the retired mirror is fine on these surfaces — pinning a"
  echo "  version to it, or cloning it, is a copy-pasteable line that fails."
  echo "  Use 'https://github.com/sceneview/sceneview' instead."
  echo ""
  echo "  Offending line(s):"
  echo "$PINS" | sed 's/^/    /'
  exit 1
fi

echo "check-sceneview-swift-urls: OK — no non-historical mirror references."
exit 0
