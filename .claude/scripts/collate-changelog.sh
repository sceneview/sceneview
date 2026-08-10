#!/usr/bin/env bash
# collate-changelog.sh — Collate changelog.d/ fragments into CHANGELOG.md
#
# Reads every fragment in `changelog.d/*.md` (towncrier-style), groups the
# bullets by category, prepends a new `## vX.Y.Z — <date>` section to
# `CHANGELOG.md`, and deletes the consumed fragments.
#
# Any entries still living under the legacy `## Unreleased` section are folded
# into the same new release section, and an empty `## Unreleased` placeholder
# is left in place for backward compatibility.
#
# A fragment's HTML COMMENTS are maintainer-only and never reach CHANGELOG.md —
# see lib/changelog-fragment.sh for the #3037 internal note that nearly shipped.
# Before anything is written, the breaking-change/patch guard runs
# (check-breaking-change-bump.sh): collation is the last moment a fragment's
# `<!-- breaking -->` declaration still exists.
#
# Usage:
#   ./collate-changelog.sh <version> [--date YYYY-MM-DD] [--dry-run]
#   Example: ./collate-changelog.sh 4.5.0
#
# Exit 0 on success, non-zero on error.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/changelog-fragment.sh
source "$REPO_ROOT/.claude/scripts/lib/changelog-fragment.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo -e "${RED}Error:${NC} version argument required."
    echo "Usage: $0 <version> [--date YYYY-MM-DD] [--dry-run]"
    exit 1
fi
shift || true

# Strip a leading 'v' if the caller passed a tag-style version.
VERSION="${VERSION#v}"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo -e "${RED}Error:${NC} '$VERSION' is not a valid X.Y.Z version."
    exit 1
fi

DATE="$(date +%Y-%m-%d)"
DRY_RUN=false
while [ $# -gt 0 ]; do
    case "$1" in
        --date) DATE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        *) echo -e "${RED}Error:${NC} unknown argument '$1'"; exit 1 ;;
    esac
done

FRAG_DIR="changelog.d"
CHANGELOG="CHANGELOG.md"

[ -f "$CHANGELOG" ] || { echo -e "${RED}Error:${NC} $CHANGELOG not found."; exit 1; }
[ -d "$FRAG_DIR" ]   || { echo -e "${RED}Error:${NC} $FRAG_DIR/ not found."; exit 1; }

# Collect fragment files (skip README.md and .gitkeep).
FRAGMENTS=()
for f in "$FRAG_DIR"/*.md; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in
        README.md) continue ;;
    esac
    FRAGMENTS+=("$f")
done

if [ "${#FRAGMENTS[@]}" -eq 0 ]; then
    echo -e "${YELLOW}No fragments in $FRAG_DIR/${NC} — only legacy ## Unreleased entries will be collated."
fi

# ─── 0. Breaking-change / patch-bump guard ───────────────────────────────
# Runs BEFORE anything is written or deleted, and before --dry-run returns:
# this is the last moment a fragment's `<!-- breaking -->` declaration exists.
# Collation consumes the fragments and strips the markers, so after this script
# succeeds there is nothing left for a later gate to read.
#
# A missing guard is an ERROR, not a skip — the #2988 lesson, where a gate
# silently skipped itself inside every worktree because absence read as success.
GUARD="$REPO_ROOT/.claude/scripts/check-breaking-change-bump.sh"
if [ ! -f "$GUARD" ]; then
    echo -e "${RED}Error:${NC} $GUARD is missing — the breaking-change/patch guard cannot run."
    echo "  Refusing to collate rather than silently skipping it (#2988)."
    exit 1
fi
bash "$GUARD" "$VERSION"

# Canonical category order. "Changed" is the fallback bucket. `Performance` (#1844)
# is dedicated to perf-only commits — splits them out of the noisy `Fixed` bucket so
# release notes credit pure perf wins distinctly from behaviour fixes.
CATEGORIES=(Added Changed Fixed Performance Removed Tests Docs)
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
for cat in "${CATEGORIES[@]}"; do : > "$TMP_DIR/$cat"; done

# Strip leading and trailing blank lines from stdin (portable: awk, no sed).
trim_blank_lines() {
    awk '
        { lines[NR] = $0 }
        END {
            start = 1
            while (start <= NR && lines[start] ~ /^[[:space:]]*$/) start++
            end = NR
            while (end >= start && lines[end] ~ /^[[:space:]]*$/) end--
            for (i = start; i <= end; i++) print lines[i]
        }
    '
}

# Map an arbitrary category string to a canonical bucket.
canonical_category() {
    local raw
    raw="$(echo "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
    case "$raw" in
        added|new)            echo "Added" ;;
        fixed|fix|bugfix)     echo "Fixed" ;;
        performance|perf)     echo "Performance" ;;
        removed|remove)       echo "Removed" ;;
        tests|test)           echo "Tests" ;;
        docs|doc|documentation) echo "Docs" ;;
        changed|change|improved|*) echo "Changed" ;;
    esac
}

# Flush whatever `body` has accumulated into `$TMP_DIR/$category`, then reset it.
#
# This runs at EVERY category-tag transition and once more at EOF, because a
# fragment may carry SEVERAL tags: `changelog.d/README.md` allows more than one
# bullet per fragment and never forbids more than one category, so each tag must
# own the bullets that FOLLOW it. Flushing only at EOF — the original behaviour —
# filed a whole multi-tag fragment under whichever tag happened to appear LAST
# (measured: a `Fixed` + `Tests` fragment shipped its Fixed bullets under
# `### Tests`). Bullets that precede any tag flush under the `Changed` default.
#
# Reads `body`/`category`/`f` from the caller's scope and writes back `body`.
flush_fragment_body() {
    local trimmed
    trimmed="$(printf '%s' "$body" | trim_blank_lines)"
    body=""
    [ -n "$trimmed" ] || return 0
    printf '%s\n' "$trimmed" >> "$TMP_DIR/$category"
    echo -e "  ${GREEN}+${NC} $(basename "$f") → ${CYAN}$category${NC}"
}

# ─── 1. Parse fragments ──────────────────────────────────────────────────
# Every HTML comment is stripped here. Only the `<!-- category: X -->` tag has
# meaning to this script; every OTHER comment is a maintainer-only note and must
# never reach the public release notes (#3037 — see lib/changelog-fragment.sh).
for f in "${FRAGMENTS[@]}"; do
    category="Changed"
    body=""
    frag_strip_reset
    while IFS= read -r line || [ -n "$line" ]; do
        # Category tag line: <!-- category: Fixed -->  (anywhere in the file).
        # Recognised on the RAW line, before stripping — the tag is itself a
        # comment. Suppressed while inside a multi-line comment, so an internal
        # note that quotes a category tag stays inert.
        if [ "$FRAG_IN_COMMENT" = false ] && frag_is_category_tag_line "$line"; then
            # Close the preceding block BEFORE switching bucket.
            flush_fragment_body
            category="$(canonical_category "$FRAG_CATEGORY_RAW")"
            continue
        fi

        was_in_comment="$FRAG_IN_COMMENT"
        frag_strip_comments_line "$line"

        # Drop a line that contributed only comment: either it was pure comment
        # text, or it lay inside an open one. A genuinely blank line OUTSIDE a
        # comment is kept — it is what separates bullets in a multi-bullet
        # fragment.
        if [ -z "${FRAG_STRIPPED//[[:space:]]/}" ] &&
           { [ "$was_in_comment" = true ] || [ -n "${line//[[:space:]]/}" ]; }; then
            continue
        fi

        # Right-trim only when a comment was actually removed, so an author's
        # deliberate two-space Markdown line break survives untouched elsewhere.
        if [ "$FRAG_STRIPPED" != "$line" ]; then
            FRAG_STRIPPED="${FRAG_STRIPPED%"${FRAG_STRIPPED##*[![:space:]]}"}"
        fi
        body+="$FRAG_STRIPPED"$'\n'
    done < "$f"

    # An unterminated `<!--` swallowed everything after it. Failing loudly is the
    # only honest option: the alternative is a release section that is silently
    # missing bullets, discovered after the fragments have been deleted.
    if [ "$FRAG_IN_COMMENT" = true ]; then
        echo -e "${RED}Error:${NC} $f has an unterminated '<!--'."
        echo "  Every bullet after it would be silently dropped from the release notes."
        exit 1
    fi

    flush_fragment_body
done

# ─── 2. Carry over legacy ## Unreleased entries ──────────────────────────
# Everything between '## Unreleased' and the next '## ' header is preserved.
UNRELEASED_BODY="$(awk '
    /^## Unreleased[[:space:]]*$/ { found=1; next }
    found && /^## / { exit }
    found { print }
' "$CHANGELOG")"
# Trim blank padding.
UNRELEASED_BODY="$(printf '%s' "$UNRELEASED_BODY" | trim_blank_lines)"
HAS_UNRELEASED=false
if [ -n "$UNRELEASED_BODY" ]; then
    HAS_UNRELEASED=true
fi

# ─── 3. Build the new release section ───────────────────────────────────
NEW_SECTION="$TMP_DIR/new_section"
{
    echo "## v$VERSION — $DATE"
    echo ""
    HAD_CONTENT=false
    for cat in "${CATEGORIES[@]}"; do
        if [ -s "$TMP_DIR/$cat" ]; then
            echo "### $cat"
            echo ""
            cat "$TMP_DIR/$cat"
            echo ""
            HAD_CONTENT=true
        fi
    done
    if [ "$HAS_UNRELEASED" = true ]; then
        # The legacy block already carries its own ### sub-headers — append verbatim.
        printf '%s\n\n' "$UNRELEASED_BODY"
        HAD_CONTENT=true
    fi
    if [ "$HAD_CONTENT" = false ]; then
        echo "_No user-facing changes._"
        echo ""
    fi
} > "$NEW_SECTION"

# ─── 4. Splice into CHANGELOG.md ────────────────────────────────────────
# Result layout:
#   # Changelog
#   <blank>
#   ## Unreleased        <- emptied placeholder, kept for backward-compat
#   <blank>
#   ## vX.Y.Z — <date>   <- new collated section
#   ...
#   ## v<previous> ...
RESULT="$TMP_DIR/changelog_new"
{
    # Preamble: everything BEFORE the first '## ' section header — the
    # '# Changelog' title plus any intro prose / Keep-a-Changelog blurb
    # that sits between the title and the first section. The old
    # `NR==1{print; ...exit}` form printed only line 1 and silently dropped
    # that preamble on every release. A blank line is appended only when the
    # preamble does not already end with one, so the layout stays stable.
    awk '
        /^## / { exit }
        { lines[NR] = $0; last = NR }
        END {
            for (i = 1; i <= last; i++) print lines[i]
            if (last > 0 && lines[last] !~ /^[[:space:]]*$/) print ""
        }
    ' "$CHANGELOG"
    # Empty Unreleased placeholder.
    echo "## Unreleased"
    echo ""
    # New release section.
    cat "$NEW_SECTION"
    # All pre-existing release sections (first '## v...' onward), verbatim.
    awk '/^## v[0-9]/ { found=1 } found { print }' "$CHANGELOG"
} > "$RESULT"

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo -e "${CYAN}=== DRY RUN — new CHANGELOG.md head ===${NC}"
    head -40 "$RESULT"
    echo -e "${CYAN}=== (fragments NOT deleted) ===${NC}"
    exit 0
fi

mv "$RESULT" "$CHANGELOG"

# ─── 5. Delete consumed fragments ───────────────────────────────────────
for f in "${FRAGMENTS[@]}"; do
    rm -f "$f"
done

echo ""
echo -e "${GREEN}✓${NC} Collated ${#FRAGMENTS[@]} fragment(s) into ${CYAN}## v$VERSION — $DATE${NC}"
[ "$HAS_UNRELEASED" = true ] && echo -e "${GREEN}✓${NC} Folded legacy ## Unreleased entries into the new section"
echo -e "${GREEN}✓${NC} Emptied ## Unreleased placeholder kept for backward-compat"
echo -e "  Review the result:  git diff $CHANGELOG"
