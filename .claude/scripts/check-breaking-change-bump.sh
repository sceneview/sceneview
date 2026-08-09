#!/usr/bin/env bash
# check-breaking-change-bump.sh — refuse a PATCH-level release that ships a
# breaking change.
#
# WHY (#3037)
# -----------
# The repo's version policy freezes major `4` and ships breaking changes as a
# MINOR bump. Until now that policy lived entirely in prose — including, with
# perfect irony, inside the #3037 fragment itself ("Because the change is
# source-breaking, it ships in a minor release, never a patch"). Nothing read it.
#
# What made that cheap sentence expensive: `.github/workflows/release.yml`'s
# `publish-rn` job derives the npm version straight from the git tag
# (`VERSION=${GITHUB_REF_NAME#v}`). Tagging `v4.26.1` would therefore publish
# #3037's source-breaking `.d.ts` change to `@sceneview-sdk/react-native` as a
# semver PATCH — the one version class every RN consumer's `^` range picks up
# without review, and the one that cannot be un-published after 72 hours.
#
# WHERE IT RUNS
# -------------
# The release preflight, not a per-PR CI gate: at PR time the release version
# does not exist yet, so a per-PR check could only assert that a marker parses.
# The declaration has to meet a version number, and that happens exactly once,
# at collation.
#
# It is wired in three places on purpose (#2988 — a guard no job invokes is
# prose):
#   1. `collate-changelog.sh` calls it before touching anything. This is the
#      load-bearing one: collation is MANDATORY (release-checklist.sh FAILs
#      while `changelog.d/` has pending fragments) and it is the last moment a
#      fragment's declaration exists — the collator deletes the fragments and
#      strips the markers out of the public notes.
#   2. `release-fast.yml` calls it right after validating the version input, so
#      a one-click release fails in seconds instead of after the 45-minute QA
#      gate and the bump commit.
#   3. `release-checklist.sh` §6 calls it too, covering the manual path where
#      someone runs the checklist before collating.
#
# HOW A FRAGMENT DECLARES A BREAKING CHANGE
# -----------------------------------------
#   * explicit:  a `<!-- breaking -->` / `<!-- breaking: true|false -->` line;
#   * implicit:  its public prose says so (see frag_prose_claims_breaking).
# The explicit marker always wins, in BOTH directions — `<!-- breaking: false -->`
# is the documented opt-out for a fragment that merely discusses breakage.
#
# The check is deliberately category-independent. #3037's breaking fragment is
# tagged `Changed`, but a `Removed` fragment (a deleted public symbol) is just
# as breaking, and filing it under the wrong bucket must not buy a patch tag.
#
# There is NO override flag. The remedy is to tag `4.X+1.0` instead of
# `4.X.Y+1` — one character, on a repo whose last several releases were all
# minor bumps. An escape hatch that exists gets used.
#
# Usage:
#   ./check-breaking-change-bump.sh <target-version> [--previous X.Y.Z] [--quiet]
#
# Exit codes:
#   0  target is not patch-level, or no fragment declares a breaking change
#   1  REFUSED — a breaking fragment meets a patch-level target
#   2  malformed input (unparsable version, bad marker, unterminated comment)

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

FRAG_DIR="changelog.d"
CHANGELOG="CHANGELOG.md"

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo -e "${RED}Error:${NC} target version argument required."
    echo "Usage: $0 <target-version> [--previous X.Y.Z] [--quiet]"
    exit 2
fi
shift

PREVIOUS=""
QUIET=false
while [ $# -gt 0 ]; do
    case "$1" in
        --previous) PREVIOUS="${2:-}"; shift 2 ;;
        --quiet)    QUIET=true; shift ;;
        *) echo -e "${RED}Error:${NC} unknown argument '$1'"; exit 2 ;;
    esac
done

say() { [ "$QUIET" = true ] || echo -e "$@"; }

# Split X.Y.Z into V_MAJ / V_MIN / V_PAT. A pre-release or build suffix is cut
# before parsing so `4.27.0-rc1` compares as 4.27.0.
V_MAJ=""; V_MIN=""; V_PAT=""
split_version() {
    local v="${1#v}"
    v="${v%%[-+]*}"
    [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
    V_MAJ="${BASH_REMATCH[1]}"; V_MIN="${BASH_REMATCH[2]}"; V_PAT="${BASH_REMATCH[3]}"
    return 0
}

split_version "$TARGET" || {
    echo -e "${RED}Error:${NC} '$TARGET' is not a valid X.Y.Z version."
    exit 2
}
T_MAJ="$V_MAJ"; T_MIN="$V_MIN"; T_PAT="$V_PAT"
TARGET_NORM="$T_MAJ.$T_MIN.$T_PAT"

# ─── 1. Which fragments declare a breaking change? ───────────────────────────
BREAKING_FILES=()
BREAKING_WHY=()

for f in "$FRAG_DIR"/*.md; do
    [ -e "$f" ] || continue
    [ "$(basename "$f")" = "README.md" ] && continue

    marker=""          # "", "true" or "false" — the explicit declaration
    body=""            # the fragment's PUBLIC text, comments removed
    frag_strip_reset
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$FRAG_IN_COMMENT" = false ]; then
            rc=0
            frag_is_breaking_marker_line "$line" || rc=$?
            if [ "$rc" -eq 0 ]; then
                marker="$FRAG_BREAKING_MARKER"
                continue
            elif [ "$rc" -eq 2 ]; then
                echo -e "${RED}Error:${NC} $f: unrecognised breaking marker value in:"
                echo "    $line"
                echo "  Use '<!-- breaking -->', '<!-- breaking: true -->' or '<!-- breaking: false -->'."
                exit 2
            fi
        fi
        frag_strip_comments_line "$line"
        body+="$FRAG_STRIPPED"$'\n'
    done < "$f"

    if [ "$FRAG_IN_COMMENT" = true ]; then
        echo -e "${RED}Error:${NC} $f has an unterminated '<!--' — close it before releasing."
        exit 2
    fi

    if [ "$marker" = "true" ]; then
        BREAKING_FILES+=("$f")
        BREAKING_WHY+=("explicit <!-- breaking --> marker")
    elif [ "$marker" = "false" ]; then
        : # explicit opt-out — always wins over the prose heuristic
    elif frag_prose_claims_breaking "$body"; then
        BREAKING_FILES+=("$f")
        BREAKING_WHY+=("its public prose says so (add '<!-- breaking: false -->' if it does not)")
    fi
done

# ─── 2. Is the target a patch-level bump? ────────────────────────────────────
# "Patch-level" means same major AND same minor as the last released version.
# The previous version is read from CHANGELOG.md rather than from
# gradle.properties, because this script runs on BOTH sides of the release bump:
# release-fast.yml calls it before VERSION_NAME moves, release-checklist.sh
# after. CHANGELOG's newest `## vX.Y.Z` section is the last shipped release in
# either case — skipping one that already names the target, which is what a
# post-collation run sees.
if [ -z "$PREVIOUS" ] && [ -f "$CHANGELOG" ]; then
    while IFS= read -r v; do
        [ "$v" = "$TARGET_NORM" ] && continue
        PREVIOUS="$v"
        break
    done < <(grep -oE '^## v[0-9]+\.[0-9]+\.[0-9]+' "$CHANGELOG" | sed 's|^## v||')
fi

BREAKING_COUNT="${#BREAKING_FILES[@]}"

if [ -z "$PREVIOUS" ]; then
    # No prior release to compare against (a fresh CHANGELOG). Report honestly
    # instead of inventing a verdict — "not measured" is not "passed".
    say "${YELLOW}⚠${NC}  breaking-change/patch guard: no previous release found in $CHANGELOG — bump level not measured."
    say "   ($BREAKING_COUNT fragment(s) declare a breaking change.)"
    exit 0
fi

split_version "$PREVIOUS" || {
    echo -e "${RED}Error:${NC} previous version '$PREVIOUS' is not a valid X.Y.Z version."
    exit 2
}
# Only major+minor decide "patch-level"; the previous patch number is irrelevant.
P_MAJ="$V_MAJ"; P_MIN="$V_MIN"

IS_PATCH=false
if [ "$T_MAJ" = "$P_MAJ" ] && [ "$T_MIN" = "$P_MIN" ]; then
    IS_PATCH=true
fi

# ─── 3. Verdict ──────────────────────────────────────────────────────────────
if [ "$IS_PATCH" = false ]; then
    say "${GREEN}✓${NC} breaking-change/patch guard: v$TARGET_NORM is a minor/major bump over v$PREVIOUS — $BREAKING_COUNT breaking fragment(s) may ship."
    exit 0
fi

if [ "$BREAKING_COUNT" -eq 0 ]; then
    say "${GREEN}✓${NC} breaking-change/patch guard: v$TARGET_NORM is patch-level over v$PREVIOUS, and no fragment declares a breaking change."
    exit 0
fi

echo -e "${RED}REFUSED:${NC} v$TARGET_NORM is a PATCH bump over v$PREVIOUS, but $BREAKING_COUNT fragment(s) declare a breaking change:"
echo ""
i=0
while [ "$i" -lt "$BREAKING_COUNT" ]; do
    echo -e "  ${CYAN}${BREAKING_FILES[$i]}${NC}"
    echo    "      ${BREAKING_WHY[$i]}"
    i=$((i + 1))
done
echo ""
echo "SceneView freezes major 4 and ships breaking changes as a MINOR bump. A patch"
echo "tag would publish this to npm as @sceneview-sdk/react-native@$TARGET_NORM —"
echo "release.yml's publish-rn derives that version straight from the tag — which"
echo "every consumer's caret range picks up without review."
echo ""
echo -e "Fix: tag ${GREEN}v$P_MAJ.$((P_MIN + 1)).0${NC} instead."
echo "     If a fragment is not actually breaking, add '<!-- breaking: false -->' to it."
exit 1
