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
# It is wired in four places on purpose (#2988 — a guard no job invokes is
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
#   4. `release.yml`'s `breaking-change-guard` job, with `--from-changelog`
#      (#3061) — see below.
#
# THE `--from-changelog` MODE, AND WHY THE OBVIOUS WIRING WOULD HAVE BEEN A
# FALSE GREEN (#3061)
# -------------------------------------------------------------------------
# `release.yml` publishes to Maven Central, npm ×3 and pub.dev on a `v*` tag,
# and until #3061 none of those five jobs consulted this guard at all. The
# tempting fix — call this script from `release.yml` — would have produced a
# job that CANNOT fail: by tag time `collate-changelog.sh` has already consumed
# and DELETED every fragment, so the loop over `changelog.d/*.md` iterates zero
# times, `BREAKING_COUNT` is 0, and the guard prints `✓` having examined
# nothing. A job that can only pass is not a gate.
#
# After collation the same text lives in `CHANGELOG.md`'s `## vX.Y.Z` section.
# `--from-changelog` reads THAT, through the same scanner, so a declaration is
# judged identically on both sides of collation. Two properties hold it honest:
#
#   * Every verdict quotes the number of lines it actually read. A green that
#     measured nothing is not expressible.
#   * "Nothing to examine" is exit 2, never exit 0 — a missing `## vX.Y.Z`
#     section, or one with an empty body, means the release was tagged without
#     collating and the guard has no content to judge.
#
# What collation does NOT preserve is the `<!-- breaking -->` MARKER: every
# HTML comment is stripped from the public notes on purpose (#3037). The marker
# is therefore caught at wiring point 1, which runs while the fragment still
# exists; `--from-changelog` catches the PROSE path, which is the one #3037
# actually exercised and the one #3061 names. Pending fragments are scanned too
# when this mode runs — a tag pushed without collating ships them, and the
# CHANGELOG section would not contain their bullets.
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
#   ./check-breaking-change-bump.sh <target-version> \
#       [--from-changelog] [--previous X.Y.Z] [--quiet]
#
# Exit codes:
#   0  target is not patch-level, or nothing examined declares a breaking change
#   1  REFUSED — a breaking declaration meets a patch-level target
#   2  malformed input (unparsable version, bad marker, unterminated comment),
#      or nothing to examine (--from-changelog with no usable section)

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
    echo "Usage: $0 <target-version> [--from-changelog] [--previous X.Y.Z] [--quiet]"
    exit 2
fi
shift

PREVIOUS=""
QUIET=false
FROM_CHANGELOG=false
while [ $# -gt 0 ]; do
    case "$1" in
        # `shift 2` with only one argument left aborts under `set -e`, and the
        # resulting exit 1 is this script's REFUSED code — a usage typo would
        # read as "a breaking fragment blocks this release".
        --previous)
            [ $# -ge 2 ] && [ -n "$2" ] || {
                echo -e "${RED}Error:${NC} --previous requires a version argument."
                exit 2
            }
            PREVIOUS="$2"; shift 2 ;;
        --from-changelog) FROM_CHANGELOG=true; shift ;;
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

# ─── 0. The shared scanner ───────────────────────────────────────────────────
# Reads Markdown on STDIN and reports what it declares. BOTH modes go through
# this one function and nothing else — that is what makes the #3061 invariant
# hold by construction rather than by two implementations agreeing today: the
# same text yields the same verdict whether it arrives as a `changelog.d/`
# fragment (before collation) or as a `## vX.Y.Z` section body (after it).
#
# Sets, on return 0:
#   SCAN_MARKER  ""|"true"|"false"  — the explicit declaration, if any
#   SCAN_BODY    the PUBLIC text, HTML comments removed
#   SCAN_LINES   how many non-blank lines were actually read
# On return 2, SCAN_ERROR carries the reason (the caller names the source).
SCAN_MARKER=""
SCAN_BODY=""
SCAN_LINES=0
SCAN_ERROR=""
scan_stream() {
    SCAN_MARKER=""; SCAN_BODY=""; SCAN_LINES=0; SCAN_ERROR=""
    local line rc
    frag_strip_reset
    while IFS= read -r line || [ -n "$line" ]; do
        if [ -n "${line//[[:space:]]/}" ]; then
            SCAN_LINES=$((SCAN_LINES + 1))
        fi
        if [ "$FRAG_IN_COMMENT" = false ]; then
            rc=0
            # The marker is looked for on a code-span-stripped COPY: a fragment
            # documenting `<!-- breaking -->` in backticks must declare nothing.
            # The ORIGINAL line still goes to the stripper below — a marker
            # trailing a bullet must not take the bullet's public text with it.
            frag_drop_code_spans "$line"
            frag_is_breaking_marker_line "$FRAG_NO_SPANS" || rc=$?
            if [ "$rc" -eq 0 ]; then
                SCAN_MARKER="$FRAG_BREAKING_MARKER"
            elif [ "$rc" -eq 2 ]; then
                SCAN_ERROR="unrecognised breaking marker value in:
    $line
  Use '<!-- breaking -->', '<!-- breaking: true -->' or '<!-- breaking: false -->'."
                return 2
            fi
        fi
        # No `continue` above: the stripper removes the marker comment like any
        # other, so a marker alone on its line contributes an empty line and a
        # trailing one leaves its bullet's public text behind. The body is only
        # consulted when no marker was found, so keeping the text changes no
        # verdict today — it removes the dependency on that ordering.
        frag_strip_comments_line "$line"
        SCAN_BODY+="$FRAG_STRIPPED"$'\n'
    done

    if [ "$FRAG_IN_COMMENT" = true ]; then
        SCAN_ERROR="unterminated '<!--' — close it before releasing."
        return 2
    fi
    return 0
}

# Fold one scanned unit into the verdict. $1 = how to name it in the refusal.
BREAKING_FILES=()
BREAKING_WHY=()
EXAMINED_UNITS=0
EXAMINED_LINES=0
judge_scanned() { # source-label
    EXAMINED_UNITS=$((EXAMINED_UNITS + 1))
    EXAMINED_LINES=$((EXAMINED_LINES + SCAN_LINES))
    if [ "$SCAN_MARKER" = "true" ]; then
        BREAKING_FILES+=("$1")
        BREAKING_WHY+=("explicit <!-- breaking --> marker")
    elif [ "$SCAN_MARKER" = "false" ]; then
        : # explicit opt-out — always wins over the prose heuristic
    elif frag_prose_claims_breaking "$SCAN_BODY"; then
        BREAKING_FILES+=("$1")
        BREAKING_WHY+=("its public prose says so (add '<!-- breaking: false -->' if it does not)")
    fi
}

# ─── 1. What is there to examine? ────────────────────────────────────────────
FRAGMENTS_SEEN=0
scan_pending_fragments() {
    for f in "$FRAG_DIR"/*.md; do
        [ -e "$f" ] || continue
        [ "$(basename "$f")" = "README.md" ] && continue
        if ! scan_stream < "$f"; then
            echo -e "${RED}Error:${NC} $f: $SCAN_ERROR"
            exit 2
        fi
        FRAGMENTS_SEEN=$((FRAGMENTS_SEEN + 1))
        judge_scanned "$f"
    done
}

SOURCE_DESC=""
if [ "$FROM_CHANGELOG" = false ]; then
    scan_pending_fragments
    SOURCE_DESC="$FRAGMENTS_SEEN pending fragment(s), $EXAMINED_LINES line(s)"
else
    # Post-collation mode. The fragments are gone; the text lives in the
    # `## vX.Y.Z` section. Everything that could make this read as a pass
    # WITHOUT reading content is an explicit exit 2 below — that hole is the
    # whole of #3061.
    if [ ! -f "$CHANGELOG" ]; then
        echo -e "${RED}Error:${NC} --from-changelog: $CHANGELOG does not exist."
        echo "  There is nothing to examine, so this is NOT a pass (#3061)."
        exit 2
    fi

    # `4.26.1` as a regex would match `4x26x1`; escape the dots once, and use
    # the same anchored pattern to FIND the section and to detect it is absent.
    # `([^0-9.]|$)` stops `## v4.2.6` matching a `## v4.2.60` header.
    HDR_RE="^## v${TARGET_NORM//./\\.}([^0-9.]|\$)"
    if ! grep -qE "$HDR_RE" "$CHANGELOG"; then
        echo -e "${RED}Error:${NC} --from-changelog: no '## v$TARGET_NORM' section in $CHANGELOG."
        echo "  The tag was pushed without collating the changelog, so this guard has"
        echo "  no content to judge. Refusing rather than passing over an unread"
        echo "  release (#3061)."
        exit 2
    fi

    # The section body: everything after the `## vX.Y.Z …` header, up to the
    # next `## ` header. The header line itself is excluded on purpose — it is
    # the collator's generated `vX.Y.Z — DATE` plus an editorial title, and
    # judging it would make the two modes read different populations, which is
    # exactly the divergence this mode exists to rule out.
    SECTION_BODY="$(awk -v hdr="$HDR_RE" '
        found && /^## / { exit }
        found           { print }
        $0 ~ hdr        { found = 1 }
    ' "$CHANGELOG")"

    if ! scan_stream <<< "$SECTION_BODY"; then
        echo -e "${RED}Error:${NC} $CHANGELOG § v$TARGET_NORM: $SCAN_ERROR"
        exit 2
    fi
    if [ "$SCAN_LINES" -eq 0 ]; then
        echo -e "${RED}Error:${NC} --from-changelog: '## v$TARGET_NORM' in $CHANGELOG has an empty body."
        echo "  A gate that finds nothing to examine must not report a pass (#3061)."
        exit 2
    fi
    judge_scanned "$CHANGELOG § v$TARGET_NORM"
    CHANGELOG_LINES="$SCAN_LINES"

    # Un-collated fragments still ship on this tag, and their bullets are NOT
    # in the section above. Scanning them here is what stops a half-collated
    # release from hiding a declaration in the one place this mode does not
    # look.
    scan_pending_fragments
    SOURCE_DESC="$CHANGELOG_LINES line(s) of $CHANGELOG § v$TARGET_NORM"
    if [ "$FRAGMENTS_SEEN" -gt 0 ]; then
        SOURCE_DESC="$SOURCE_DESC + $FRAGMENTS_SEEN un-collated fragment(s)"
    fi
fi

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
    say "   (examined $SOURCE_DESC; $BREAKING_COUNT declare a breaking change.)"
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
# EVERY verdict line below quotes what was actually read. That is not decoration:
# the #3061 hole was a guard reporting `✓` after iterating over an empty
# `changelog.d/`, and a green with no measurement in it is indistinguishable
# from a green that measured everything. With the count in the sentence, a
# release log that says "examined 0 line(s)" reads as the defect it is.
if [ "$IS_PATCH" = false ]; then
    say "${GREEN}✓${NC} breaking-change/patch guard: v$TARGET_NORM is a minor/major bump over v$PREVIOUS — examined $SOURCE_DESC, $BREAKING_COUNT breaking declaration(s) may ship."
    exit 0
fi

if [ "$BREAKING_COUNT" -eq 0 ]; then
    say "${GREEN}✓${NC} breaking-change/patch guard: v$TARGET_NORM is patch-level over v$PREVIOUS — examined $SOURCE_DESC, none declares a breaking change."
    exit 0
fi

echo -e "${RED}REFUSED:${NC} v$TARGET_NORM is a PATCH bump over v$PREVIOUS, but $BREAKING_COUNT of $EXAMINED_UNITS source(s) examined declare a breaking change:"
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
if [ "$FROM_CHANGELOG" = true ]; then
    echo "     This ran at RELEASE time, so nothing has been published yet — delete the"
    echo "     tag, re-tag at the minor version, and the publishers run against it."
    echo "     If the section does not actually describe a breaking change, reword the"
    echo "     bullet: '<!-- breaking: false -->' opts a FRAGMENT out, and the fragments"
    echo "     were consumed at collation."
else
    echo "     If a fragment is not actually breaking, add '<!-- breaking: false -->' to it."
fi
exit 1
