#!/usr/bin/env bash
# check-changelog-fragments.sh — reject a changelog.d/ fragment that is not
# shaped as the bullet(s) `collate-changelog.sh` expects.
#
# WHY (see the #3614 postmortem)
# -------------------------------
# `collate-changelog.sh` copies a fragment's stripped body VERBATIM into
# `CHANGELOG.md` — it never reformats prose into a bullet. Six fragments
# merged on 2026-09-11 were free paragraphs with no leading `- `, and nothing
# checked that at PR time. They collated fine (the collator only groups by
# category, it does not validate shape) and landed as paragraphs under
# `## v4.36.0`. The demo's "What's new" parser
# (`WhatsNewChangelog.kt`) only recognises a highlight on a line that
# `startsWith("- ")`; none of the six paragraphs qualified, so the release
# shipped zero highlights and `WhatsNewAssetIntegrityTest` only went red once
# the release PR (#3614) ran against the real collated CHANGELOG.md — three
# weeks after the fragments themselves were reviewable.
#
# This script runs on every PR that touches `changelog.d/**` (wired into
# `.github/workflows/ci.yml`'s `changelog-lint` job) so a paragraph fragment
# is caught at the PR that adds it, not at release time. It also runs at the
# top of `collate-changelog.sh` itself, since that script can be invoked
# directly and must not silently collate malformed input either.
#
# WHAT COUNTS AS VALID (see changelog.d/README.md)
# --------------------------------------------------
# After HTML comments are stripped (the same stripper collation itself uses,
# via lib/changelog-fragment.sh — a fragment is judged on exactly the text
# that will reach CHANGELOG.md), every remaining non-blank line must be
# either:
#   * a top-level bullet:      `- ` at the start of the line (ideally
#     `- **Headline.** …`, but the leading marker is what the parser and the
#     collator's category grouping actually require);
#   * a continuation line:     indented by at least 2 spaces, wrapping the
#     bullet above it.
# A line that is neither — plain unindented prose with no `- ` marker — is
# exactly the #3614 shape and fails this check.
#
# Usage:
#   ./check-changelog-fragments.sh [file ...]
#   With no arguments, checks every changelog.d/*.md fragment (skipping
#   README.md and .gitkeep).
#
# Exit 0 if every fragment is well-formed, 1 otherwise (naming the file and
# line).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/changelog-fragment.sh
source "$REPO_ROOT/.claude/scripts/lib/changelog-fragment.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FRAG_DIR="changelog.d"

FILES=("$@")
if [ "${#FILES[@]}" -eq 0 ]; then
    for f in "$FRAG_DIR"/*.md; do
        [ -e "$f" ] || continue
        case "$(basename "$f")" in
            README.md|.gitkeep) continue ;;
        esac
        FILES+=("$f")
    done
fi

if [ "${#FILES[@]}" -eq 0 ]; then
    echo -e "${YELLOW}No changelog.d fragments to check.${NC}"
    exit 0
fi

FAIL=0

# `<number>-<slug>.md` — matches the convention documented in
# changelog.d/README.md and CONTRIBUTING.md. `<number>` is an issue or PR
# number, `<slug>` is lowercase words separated by hyphens.
NAME_RE='^[0-9]+-[a-z0-9]+(-[a-z0-9]+)*\.md$'

for f in "${FILES[@]}"; do
    base="$(basename "$f")"
    case "$base" in
        README.md|.gitkeep) continue ;;
    esac

    if ! [[ "$base" =~ $NAME_RE ]]; then
        echo -e "${RED}Error:${NC} $f — filename does not match '<issue-or-pr-number>-<short-slug>.md' (see changelog.d/README.md)."
        FAIL=1
    fi

    frag_strip_reset
    lineno=0
    frag_has_bullet=false
    frag_unterminated=false
    prev_was_bullet_block=false

    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))

        # Category tag lines are structural, not prose — never subject to the
        # bullet/continuation rule.
        if [ "$FRAG_IN_COMMENT" = false ] && frag_is_category_tag_line "$line"; then
            prev_was_bullet_block=false
            continue
        fi

        frag_strip_comments_line "$line"
        stripped="$FRAG_STRIPPED"

        # A genuinely blank line (outside a comment) just separates bullets.
        if [ -z "${stripped//[[:space:]]/}" ]; then
            prev_was_bullet_block=false
            continue
        fi

        if [[ "$stripped" == "- "* ]]; then
            frag_has_bullet=true
            prev_was_bullet_block=true
            continue
        fi

        # A continuation line: indented by >= 2 spaces, wrapping the bullet
        # immediately above it.
        if [[ "$stripped" == "  "* ]] && [ "$prev_was_bullet_block" = true ]; then
            continue
        fi

        echo -e "${RED}Error:${NC} $f:$lineno — line is neither a bullet ('- ') nor an indented continuation of one:"
        echo "    ${stripped}"
        echo "  Fragments are copied verbatim into CHANGELOG.md and must be written as bullets — see changelog.d/README.md."
        echo "  Expected shape:"
        echo "    <!-- category: Fixed -->"
        echo "    - **Short headline ([#1234](https://github.com/sceneview/sceneview/issues/1234)).** What changed and why."
        FAIL=1
        prev_was_bullet_block=false
    done < "$f"

    if [ "$FRAG_IN_COMMENT" = true ]; then
        frag_unterminated=true
    fi

    # Let collate-changelog.sh / the shared stripper be the one authority on
    # unterminated comments — do not duplicate that error here, just skip the
    # bullet-shape verdict for a file we could not fully parse.
    if [ "$frag_unterminated" = true ]; then
        continue
    fi

    if [ "$frag_has_bullet" = false ]; then
        echo -e "${RED}Error:${NC} $f — no top-level bullet ('- ') found. A fragment must contain at least one bullet; see changelog.d/README.md."
        FAIL=1
    fi
done

if [ "$FAIL" -ne 0 ]; then
    echo ""
    echo -e "${RED}✗${NC} changelog.d fragment format check failed."
    exit 1
fi

echo -e "${GREEN}✓${NC} ${#FILES[@]} changelog.d fragment(s) well-formed."
