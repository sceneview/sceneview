#!/usr/bin/env bash
# changelog-fragment.sh — shared parsing primitives for `changelog.d/` fragments.
#
# Two release-time scripts read the same fragment files and MUST agree on what a
# fragment says, so the primitives live here instead of being reimplemented
# twice and drifting:
#
#   * collate-changelog.sh        — turns fragments into the PUBLIC release notes.
#   * check-breaking-change-bump.sh — refuses a patch-level release that carries
#                                     a breaking change.
#
# WHY THE HTML-COMMENT STRIPPER EXISTS (#3037)
# --------------------------------------------
# `collate-changelog.sh` used to intercept exactly ONE comment shape — the
# single-line `<!-- category: X -->` tag — and append every other line of a
# fragment verbatim to the release section. A fragment in #3037 carried a
# nine-line `<!-- RELEASE NOTE: ... -->` block (maintainer sign-off, plus the
# publish-rn / GITHUB_REF_NAME mechanics of the release workflow): internal
# notes, one release away from being published as user-facing release notes.
# A human spotted it and deleted the block by hand; nothing in the repo would
# have stopped the next one, and the collator DELETES the fragments it consumes,
# so the source would have been gone by the time anyone read the published page.
#
# The stripper is therefore generic — every HTML comment is maintainer-only —
# rather than a special case for one block shape.
#
# WHY THE GUARD READS THE SAME STRIPPED BODY
# ------------------------------------------
# `check-breaking-change-bump.sh` judges a fragment's PROSE, and it must judge
# exactly the text a reader will see. Sharing the stripper means an internal
# note *arguing about* whether something is breaking can never trip the guard,
# while a published bullet that says "source-breaking" always can.
#
# STATE, NOT STDOUT
# -----------------
# The stripper is a state machine spanning lines (a comment may open on one line
# and close several lines later), so it writes its result to a global rather
# than echoing it. `x="$(f "$line")"` runs `f` in a SUBSHELL, which would
# discard `FRAG_IN_COMMENT` on every line and turn a multi-line comment back
# into verbatim prose — the exact bug this file exists to prevent.
#
# Usage:
#     source "$(dirname "$0")/lib/changelog-fragment.sh"
#     frag_strip_reset                       # once per file
#     while IFS= read -r line; do
#         frag_strip_comments_line "$line"   # -> $FRAG_STRIPPED, updates $FRAG_IN_COMMENT
#     done < "$f"
#     [ "$FRAG_IN_COMMENT" = false ] || die "unterminated <!-- in $f"

# Every FRAG_* global below is written here and read by the sourcing script, so
# the "appears unused" warning is structural, not a finding. (Do not start the
# line above with the linter's own name: it gets parsed as a malformed directive
# and the whole file is then skipped, SC1072/SC1073.)
# shellcheck disable=SC2034

# ─── HTML-comment stripper ───────────────────────────────────────────────────

# true while a `<!--` opened on an earlier line has not been closed yet. Read it
# AFTER the last line of a file: still true means the fragment has an
# unterminated comment, and everything after the `<!--` was swallowed. Callers
# must treat that as an error — silently dropping bullets is worse than the leak
# the stripper prevents.
FRAG_IN_COMMENT=false

# The comment-free text of the line most recently passed to
# frag_strip_comments_line. Empty for a line that was purely a comment.
FRAG_STRIPPED=""

frag_strip_reset() {
    FRAG_IN_COMMENT=false
    FRAG_STRIPPED=""
}

# Remove every HTML-comment span from one line, carrying open/close state across
# calls. Text outside the comments is preserved, so `- Bullet <!-- note -->` keeps
# its bullet.
#
# A delimiter inside a Markdown CODE SPAN is literal text, not a delimiter — the
# same reading a Markdown renderer gives it. That is not a nicety: fragments
# document this very convention. The first fragment written after the stripper
# landed contained the sentence "An unterminated `<!--` is a hard error", whose
# lone backticked `<!--` opened a comment that ran on and ate the file's next
# `<!-- category: Added -->` tag — silently refiling two bullets under the
# previous heading. Ignoring code spans does not merely mangle a sentence; it
# changes which bucket later bullets land in.
#
# Code spans apply only OUTSIDE a comment: once `<!--` is open, everything up to
# `-->` is comment text, backticks included.
frag_strip_comments_line() {
    local rest="$1" out="" prefix_comment prefix_tick tail run after content
    while [ -n "$rest" ]; do
        if [ "$FRAG_IN_COMMENT" = true ]; then
            case "$rest" in
                *'-->'*) rest="${rest#*-->}"; FRAG_IN_COMMENT=false ;;
                *)       rest="" ;;
            esac
            continue
        fi

        # Whichever comes first — a comment opener or a code span — is handled
        # first; `${x%%pat*}` returns $x unchanged when the pattern is absent, so
        # an equal length means "not found".
        prefix_comment="${rest%%<!--*}"
        prefix_tick="${rest%%\`*}"
        local has_comment=false has_tick=false
        [ "$prefix_comment" != "$rest" ] && has_comment=true
        [ "$prefix_tick" != "$rest" ] && has_tick=true

        if [ "$has_comment" = false ] && [ "$has_tick" = false ]; then
            out+="$rest"; rest=""
            continue
        fi

        if [ "$has_comment" = true ] && { [ "$has_tick" = false ] || [ "${#prefix_comment}" -lt "${#prefix_tick}" ]; }; then
            out+="$prefix_comment"
            rest="${rest#*<!--}"
            FRAG_IN_COMMENT=true
            continue
        fi

        # A code span opens with a run of N backticks and closes on the next run
        # of the same length. An unclosed run is literal text, so a stray
        # backtick cannot make the rest of the line invisible to the stripper.
        out+="$prefix_tick"
        tail="${rest:${#prefix_tick}}"
        run=""
        while [ "${tail:${#run}:1}" = '`' ]; do run+='`'; done
        after="${tail:${#run}}"
        if [[ "$after" == *"$run"* ]]; then
            content="${after%%"$run"*}"
            out+="$run$content$run"
            rest="${after:$(( ${#content} + ${#run} ))}"
        else
            out+="$run"
            rest="$after"
        fi
    done
    FRAG_STRIPPED="$out"
}

# ─── Tag recognisers (run on the RAW line, before stripping) ─────────────────

# The category tag is itself an HTML comment, so it must be recognised before
# the stripper eats it. Only the exact single-line shape counts; anything else
# is prose or an internal note.
FRAG_CATEGORY_RAW=""
frag_is_category_tag_line() {
    [[ "$1" =~ ^[[:space:]]*\<!--[[:space:]]*category:[[:space:]]*([A-Za-z]+)[[:space:]]*--\>[[:space:]]*$ ]] || return 1
    FRAG_CATEGORY_RAW="${BASH_REMATCH[1]}"
    return 0
}

# Drop Markdown code spans from a piece of text. Used by everything that has to
# tell a DECLARATION from an identifier being DISCUSSED: a backticked
# `<!-- breaking -->` in a doc fragment documents the marker, it does not set it.
# The run is `+, not one backtick: Markdown closes a span with a run of the same
# length, so a ``breaking`` written with two would otherwise survive.
#
# This never touches the PUBLISHED text — callers strip a copy for their own
# judgement and keep the original for the changelog.
FRAG_NO_SPANS=""
frag_drop_code_spans() {
    FRAG_NO_SPANS="$(printf '%s' "$1" | sed -E 's/`+[^`]*`+//g')"
}

# `<!-- breaking -->` / `<!-- breaking: true|false -->` — the EXPLICIT
# declaration read by check-breaking-change-bump.sh. Sets FRAG_BREAKING_MARKER
# to true/false.
#
# Returns 1 when the line carries no breaking marker at all, and 2 when it DOES
# carry one but with a value nobody defined (`<!-- breaking: maybe -->`). The
# caller must refuse on 2 rather than guess: reading it as false opens a hole in
# the guard, reading it as true blocks a release for a typo. Neither is a
# decision a script should make silently.
#
# The marker is looked for in EVERY comment span on the line, not just in a line
# that is nothing but the marker. Anchoring it to the whole line made a marker
# trailing a bullet (`- Foo changed. <!-- breaking -->`) unrecognisable: it fell
# through to the comment stripper, which removed it before the prose heuristic
# could see the word — a misplaced marker silently declaring nothing, which is
# the same "the typo does the opposite" failure the malformed-value branch below
# exists to prevent. Callers pass a code-span-stripped copy of the line, so a
# fragment DOCUMENTING the marker in backticks still declares nothing.
#
# Each span's content is isolated by shell substring removal, then matched with
# an ANCHORED regex — matching an un-anchored `<!--…-->` in one pass would need
# a non-greedy quantifier ERE does not have, and would read
# `<!-- a --> x <!-- b -->` as one span with " a --> x <!-- b " inside.
#
# The value is captured as "everything up to the `-->`", not as one alphanumeric
# token. A token-shaped capture makes the REGEX decide what is malformed:
# `<!-- breaking: not sure -->` would fail to match at all, return 1 ("not a
# marker"), be stripped as an ordinary comment, and ship unflagged. Matching
# broadly and rejecting in the `case` below keeps that decision in one place.
FRAG_BREAKING_MARKER=""
frag_is_breaking_marker_line() {
    local rest="$1" span value
    while [ -n "$rest" ]; do
        case "$rest" in *'<!--'*) ;; *) return 1 ;; esac
        rest="${rest#*<!--}"
        case "$rest" in *'-->'*) ;; *) return 1 ;; esac
        span="${rest%%-->*}"
        rest="${rest#*-->}"
        [[ "$span" =~ ^[[:space:]]*breaking[[:space:]]*(:(.*))?$ ]] || continue

        value="${BASH_REMATCH[2]}"
        # Trim, then lower-case. An empty BASH_REMATCH[1] means there was no
        # colon at all (`<!-- breaking -->`), which is the shorthand for true; a
        # colon with nothing after it is a typo and must reach the `*)` branch.
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        if [ -z "${BASH_REMATCH[1]}" ]; then
            value=true
        fi
        value="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
        case "$value" in
            true|yes|1)  FRAG_BREAKING_MARKER=true ;;
            false|no|0)  FRAG_BREAKING_MARKER=false ;;
            *)           FRAG_BREAKING_MARKER=""; return 2 ;;
        esac
        return 0
    done
    return 1
}

# ─── Prose heuristic ─────────────────────────────────────────────────────────

# Does a fragment's PUBLIC text claim a breaking change, with no explicit marker?
#
# The marker alone would be a guard nobody trips: it did not exist when #3037
# was written, and #3037's fragment says "this is source-breaking … it ships in
# a minor release, never a patch" in plain prose — the exact statement the guard
# exists to enforce, invisible to a marker-only reader.
#
# Two shapes are deliberately excluded, both taken from fragments in the tree:
#   * negations — `changelog.d/3008-contentid.md` says "and non-breaking";
#   * `groundbreaking` and friends — hence the leading non-alphanumeric
#     boundary, which `source-breaking` and `breaking change` still satisfy.
#
# A fragment that trips this by accident opts out with `<!-- breaking: false -->`,
# which always wins over the heuristic.
frag_prose_claims_breaking() { # $1 = comment-stripped body text
    local text
    # Newlines become spaces FIRST: sed is line-oriented, so a bullet hard-wrapped
    # between "non-" and "breaking" would slip past the negation filter below and
    # be read as a breaking declaration. Fragments in this repo are written as one
    # long line per bullet today, which is exactly why the wrapped case would go
    # unnoticed until the first contributor who wraps.
    text="$(printf '%s' "$1" | tr '\n' ' ' | tr '[:upper:]' '[:lower:]')"
    # Code spans are dropped for the heuristic only — the stripper preserves them
    # in the published text. A backticked `breaking` is an identifier being
    # discussed, never a declaration that this release breaks something.
    frag_drop_code_spans "$text"
    text="$FRAG_NO_SPANS"
    # "no longer breaking" is a negation with a word in the middle; the general
    # "any word between" form would swallow real declarations
    # ("no workaround exists — breaking"), so only this one phrasing is added.
    # The alternation is left-bounded for the same reason the final grep is:
    # unbounded, any word ENDING in one of these tokens ("piano breaking",
    # "casino breaking") reads as a negation and turns a real declaration into
    # a pass — the one failure direction this guard must not have.
    text="$(printf '%s' "$text" | sed -E "s/(^|[^a-z0-9])(non|not|no|isn.t|aren.t|won.t)([[:space:]-]+longer)?[[:space:]-]+breaking/\1xnegatedx/g")"
    printf '%s' "$text" | grep -qE '(^|[^a-z0-9])breaking'
}
