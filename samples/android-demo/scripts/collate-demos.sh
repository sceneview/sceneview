#!/usr/bin/env bash
# collate-demos.sh — Regenerate GeneratedDemos.kt and the llms.txt
# "Sample app demos (Android)" section from the per-demo fragments under
# `src/main/java/io/github/sceneview/demo/fragments/`.
#
# Each fragment file declares ONE `object <Id>Fragment : DemoFragment { ... }`.
# This script discovers every such fragment and emits:
#
#   1. `GeneratedDemos.kt` — aggregates fragments into
#        - `GeneratedDemos.all: List<DemoEntry>` — drives the central registry
#          (`ALL_DEMOS`) and the demo grid in `DemoListScreen`.
#        - `GeneratedDemos.route(id, onBack)` — drives the deep-link router
#          (`DemoRouter`); returns a composable lambda or null.
#
#   2. A markered block in the root `llms.txt` enumerating every demo (id,
#      title, subtitle, category) so AI consumers see the sample-app surface
#      without anyone hand-editing `llms.txt` on every new demo (issue #1871).
#
# Note: `docs/docs/llms.txt` was once a committed mirror this script also
# rewrote. It is no longer committed — it is regenerated from root `llms.txt`
# at docs-build time and `.gitignore`d (issue #899 hardening) — so the
# collator only touches root `llms.txt`. The docs mirror inherits the
# up-to-date demos block automatically when the docs build copies the file.
#
# Fragments are sorted by demo id for a stable, conflict-free diff so two
# parallel PRs adding two different demos never collide on this file.
#
# Idempotent: running this script twice produces byte-identical outputs.
#
# Usage:
#   bash samples/android-demo/scripts/collate-demos.sh           # write both
#   bash samples/android-demo/scripts/collate-demos.sh --check   # exit non-zero
#                                                                # if any output is stale
#   bash samples/android-demo/scripts/collate-demos.sh --kt-only # write only
#                                                                # GeneratedDemos.kt
#
# `--kt-only` is what the Gradle build wires in: `GeneratedDemos.kt` is no
# longer committed (it is `.gitignore`d, issue #1976) and is regenerated before
# `:samples:android-demo` Kotlin compilation. The root `llms.txt` stays tracked
# (it carries hand-maintained content outside the generated block) so the Gradle
# build must not rewrite it — `--kt-only` skips it entirely.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

FRAG_DIR="samples/android-demo/src/main/java/io/github/sceneview/demo/fragments"
OUT_FILE="$FRAG_DIR/GeneratedDemos.kt"
# Per-demo string fragments live alongside `strings.xml` under
# `res/values/strings_demo_<id>.xml` (issue #1870). Android's resource merger
# fans every `res/values/*.xml` in, so the lookup below must walk all of them
# rather than just the central `strings.xml`.
VALUES_DIR="samples/android-demo/src/main/res/values"
STRINGS_XML="$VALUES_DIR/strings.xml"
LLMS_ROOT="llms.txt"
LLMS_BEGIN_MARKER="<!-- BEGIN GENERATED DEMOS — DO NOT EDIT — run samples/android-demo/scripts/collate-demos.sh -->"
LLMS_END_MARKER="<!-- END GENERATED DEMOS -->"

CHECK_MODE=false
KT_ONLY=false
case "${1:-}" in
    --check)   CHECK_MODE=true ;;
    --kt-only) KT_ONLY=true ;;
    "") ;;
    *) echo "Usage: $0 [--check|--kt-only]" >&2; exit 1 ;;
esac

[ -d "$FRAG_DIR" ] || { echo "Error: $FRAG_DIR not found." >&2; exit 1; }
[ -d "$VALUES_DIR" ] || { echo "Error: $VALUES_DIR not found." >&2; exit 1; }
[ -f "$STRINGS_XML" ] || { echo "Error: $STRINGS_XML not found." >&2; exit 1; }
# Root `llms.txt` is only touched by the full (write) and `--check` modes.
# `--kt-only` (the Gradle build path, #1976) emits ONLY `GeneratedDemos.kt`,
# so it must not require `llms.txt`.
if [ "$KT_ONLY" != true ]; then
    [ -f "$LLMS_ROOT" ] || { echo "Error: $LLMS_ROOT not found." >&2; exit 1; }
fi

# ─── 1. Discover fragments and collect per-demo metadata ──────────────────
#
# Each fragment declares:
#   object <Name>Fragment : DemoFragment {
#       override val entry: DemoEntry = DemoEntry(
#           id = "<demo-id>",
#           titleRes = R.string.<title_res>,
#           subtitleRes = R.string.<subtitle_res>,
#           category = DemoCategory.<CATEGORY_ENUM>,
#           ...
#       )
#       ...
#   }
#
# We extract: id, object-name, titleRes, subtitleRes, category-enum-key.
# Title and subtitle texts are resolved from strings.xml further below.
#
# TMP_META rows are TAB-separated: id\tobjName\ttitleRes\tsubtitleRes\tcategory
TMP_META="$(mktemp)"
trap 'rm -f "$TMP_META"' EXIT

shopt -s nullglob
fragment_count=0
for f in "$FRAG_DIR"/*Fragment.kt; do
    base="$(basename "$f")"
    [ "$base" = "DemoFragment.kt" ] && continue

    obj_name=$(grep -oE '^object [A-Za-z0-9_]+Fragment\b' "$f" | head -n1 | awk '{print $2}' || true)
    if [ -z "$obj_name" ]; then
        echo "Error: $f does not declare a top-level 'object <Name>Fragment : DemoFragment'." >&2
        exit 1
    fi

    demo_id=$(grep -oE 'id\s*=\s*"[^"]+"' "$f" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
    if [ -z "$demo_id" ]; then
        echo "Error: $f does not declare 'id = \"<demo-id>\"' on its DemoEntry." >&2
        exit 1
    fi

    title_res=$(grep -oE 'titleRes\s*=\s*R\.string\.[a-zA-Z0-9_]+' "$f" | head -n1 | sed -E 's/.*R\.string\.//')
    if [ -z "$title_res" ]; then
        echo "Error: $f does not declare 'titleRes = R.string.<id>' on its DemoEntry." >&2
        exit 1
    fi

    subtitle_res=$(grep -oE 'subtitleRes\s*=\s*R\.string\.[a-zA-Z0-9_]+' "$f" | head -n1 | sed -E 's/.*R\.string\.//')
    if [ -z "$subtitle_res" ]; then
        echo "Error: $f does not declare 'subtitleRes = R.string.<id>' on its DemoEntry." >&2
        exit 1
    fi

    category=$(grep -oE 'category\s*=\s*DemoCategory\.[A-Z_0-9]+' "$f" | head -n1 | sed -E 's/.*DemoCategory\.//')
    if [ -z "$category" ]; then
        echo "Error: $f does not declare 'category = DemoCategory.<KEY>' on its DemoEntry." >&2
        exit 1
    fi

    printf '%s\t%s\t%s\t%s\t%s\n' "$demo_id" "$obj_name" "$title_res" "$subtitle_res" "$category" >> "$TMP_META"
    fragment_count=$((fragment_count + 1))
done

if [ "$fragment_count" -eq 0 ]; then
    echo "Error: no *Fragment.kt files found under $FRAG_DIR (other than DemoFragment.kt)." >&2
    exit 1
fi

# Detect duplicate ids — two fragments registering the same demo id would
# silently shadow each other in the router.
DUPES=$(cut -f1 "$TMP_META" | sort | uniq -d)
if [ -n "$DUPES" ]; then
    echo "Error: duplicate demo ids:" >&2
    echo "$DUPES" >&2
    exit 1
fi

# Sort by demo id (column 1) for a stable diff.
SORTED_META="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META"' EXIT
sort -k1,1 "$TMP_META" > "$SORTED_META"

# ─── 2. Emit GeneratedDemos.kt ────────────────────────────────────────────
NEW_KT="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META" "$NEW_KT"' EXIT

{
    cat <<'HEADER'
// AUTO-GENERATED by samples/android-demo/scripts/collate-demos.sh — DO NOT EDIT.
//
// This file is regenerated from every `*Fragment.kt` under
// `src/main/java/io/github/sceneview/demo/fragments/`. To add or remove a demo,
// add or remove a fragment file and re-run the collator; never edit this file
// by hand.
//
// Fragments are sorted by demo id so two parallel PRs adding two different
// demos never collide on this file (issue #1797).
package io.github.sceneview.demo.fragments

import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoEntry

/**
 * Generated aggregate of every [DemoFragment] declared under
 * `io.github.sceneview.demo.fragments`. Drives [io.github.sceneview.demo.ALL_DEMOS]
 * and [io.github.sceneview.demo.DemoRouter]. Regenerate with
 * `samples/android-demo/scripts/collate-demos.sh`.
 */
object GeneratedDemos {
    /** All fragments, sorted by demo id. */
    val fragments: List<DemoFragment> = listOf(
HEADER

    # Emit list entries, sorted by id.
    while IFS=$'\t' read -r _id obj _t _s _c; do
        printf '        %s,\n' "$obj"
    done < "$SORTED_META"

    cat <<'MIDDLE'
    )

    /** Convenience: every demo's metadata, in the same order as [fragments]. */
    val all: List<DemoEntry> = fragments.map { it.entry }

    /**
     * Returns the composable for the demo with the given [id], or `null` if no
     * fragment registers that id. The caller (typically
     * [io.github.sceneview.demo.DemoRouter]) is expected to handle the null
     * case by falling back to a placeholder screen.
     */
    @Composable
    fun Screen(id: String, onBack: () -> Unit): Boolean {
        when (id) {
MIDDLE

    # Emit the `when` branches, also sorted by id for diff stability.
    while IFS=$'\t' read -r demo_id obj _t _s _c; do
        printf '            "%s" -> %s.Screen(onBack)\n' "$demo_id" "$obj"
    done < "$SORTED_META"

    cat <<'FOOTER'
            else -> return false
        }
        return true
    }
}
FOOTER
} > "$NEW_KT"

# ─── 2b. --kt-only fast path ──────────────────────────────────────────────
#
# The Gradle build (issue #1976) only needs `GeneratedDemos.kt`: that file is
# `.gitignore`d and regenerated before `:samples:android-demo` compiles. The
# `llms.txt` files stay TRACKED and carry hand-maintained content outside the
# generated block, so a build step must never rewrite them. `--kt-only` writes
# `GeneratedDemos.kt` (only if its bytes changed, to keep Gradle up-to-date
# checks honest and avoid needless recompiles) and stops here.
if [ "$KT_ONLY" = true ]; then
    if [ ! -f "$OUT_FILE" ] || ! cmp -s "$NEW_KT" "$OUT_FILE"; then
        mv "$NEW_KT" "$OUT_FILE"
        echo "Regenerated $OUT_FILE from $fragment_count fragment(s)."
    else
        echo "OK: $OUT_FILE already in sync with $fragment_count fragment(s)."
    fi
    exit 0
fi

# ─── 3. Resolve title/subtitle from values/*.xml ─────────────────────────
#
# Each fragment carries `titleRes` and `subtitleRes` pointing at a `<string
# name="...">` entry under
# `samples/android-demo/src/main/res/values/*.xml`. Per-demo strings ship in
# their own `strings_demo_<id>.xml` file alongside the central `strings.xml`
# (issue #1870); Android's resource merger fans them all in so a `R.string.*`
# lookup is file-agnostic. We mirror that behaviour here by grepping the whole
# `values/` directory, returning the first hit (uniqueness of `name` is enforced
# at compile time by `aapt2`, so the first hit is the canonical value).
#
# The lookup is line-anchored: each `<string name="key">value</string>` is on
# a single line in the source files.
resolve_string() {
    local key="$1"
    # Match the first `<string name="<key>">...</string>` line across every
    # `values/*.xml`. Captures everything between the opening and closing tags.
    # The matcher is forgiving of attributes after `name="…"` (none in these
    # files today, but cheap to tolerate) and unescapes the XML entities
    # Android actually uses (`&amp;`, `&apos;`, `&quot;`, `&lt;`, `&gt;`).
    local raw
    raw=$(grep -h -m1 -E "<string name=\"${key}\"[^>]*>" "$VALUES_DIR"/*.xml 2>/dev/null \
            | head -n1 \
            | sed -E "s|.*<string name=\"${key}\"[^>]*>(.*)</string>.*|\1|" \
            | sed -e 's/&amp;/\&/g' -e "s/&apos;/'/g" -e 's/&quot;/"/g' -e 's/&lt;/</g' -e 's/&gt;/>/g')
    if [ -z "$raw" ]; then
        echo "Error: no <string name=\"${key}\"> under $VALUES_DIR/*.xml." >&2
        return 1
    fi
    printf '%s' "$raw"
}

# Map the Kotlin enum key to its display string (mirrors `DemoCategory` in
# `samples/android-demo/src/main/java/io/github/sceneview/demo/DemoRegistry.kt`).
category_display() {
    case "$1" in
        BASICS_3D)             printf '3D Basics' ;;
        LIGHTING_ENVIRONMENT)  printf 'Lighting & Environment' ;;
        CONTENT)               printf 'Content' ;;
        INTERACTION)           printf 'Interaction' ;;
        ADVANCED)              printf 'Advanced' ;;
        AUGMENTED_REALITY)     printf 'Augmented Reality' ;;
        *)
            echo "Error: unknown DemoCategory.$1 — update collate-demos.sh." >&2
            return 1
            ;;
    esac
}

# ─── 4. Build the llms.txt "Sample app demos" block ───────────────────────
#
# Format: one bullet per demo, sorted alphabetically by id within each
# category, categories in display order. Each bullet is:
#     - `<id>` — <title>. <subtitle>.
#
# That format is single-line / id-keyed so an AI agent reading llms.txt can
# trivially extract the demo set with one regex per line and use the id with
# the deep-link router (`sceneview://demo/<id>`).
NEW_BLOCK="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META" "$NEW_KT" "$NEW_BLOCK"' EXIT

CATEGORY_ORDER="BASICS_3D LIGHTING_ENVIRONMENT CONTENT INTERACTION ADVANCED AUGMENTED_REALITY"

{
    printf '%s\n' "$LLMS_BEGIN_MARKER"
    printf '\n'
    printf '## Sample app demos (Android)\n'
    printf '\n'
    printf 'Every demo bundled in `samples/android-demo` (the Play Store showcase).\n'
    printf 'Each demo is addressable through the deep-link router as `sceneview://demo/<id>`\n'
    printf 'and surfaces in the Samples tab. This section is generated from the per-demo\n'
    printf 'fragments under `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/`\n'
    printf 'by `samples/android-demo/scripts/collate-demos.sh` — never edit between the markers (#1871).\n'

    for cat_key in $CATEGORY_ORDER; do
        # Filter sorted-meta rows for this category.
        rows=$(awk -F'\t' -v c="$cat_key" '$5 == c' "$SORTED_META")
        [ -z "$rows" ] && continue

        cat_display=$(category_display "$cat_key")
        printf '\n'
        printf '### %s\n' "$cat_display"
        printf '\n'

        while IFS=$'\t' read -r demo_id _obj title_res subtitle_res _cat; do
            title=$(resolve_string "$title_res")
            subtitle=$(resolve_string "$subtitle_res")
            printf -- '- `%s` — %s. %s.\n' "$demo_id" "$title" "$subtitle"
        done <<< "$rows"
    done

    printf '\n'
    printf '%s\n' "$LLMS_END_MARKER"
} > "$NEW_BLOCK"

# ─── 5. Splice the block into root llms.txt ───────────────────────────────
#
# If the markers are not yet present in the target file, splice the block in
# at a stable anchor (just before `## Sketchfab streaming for samples`) so the
# first run is a one-shot install. Subsequent runs replace the existing block
# in place — surrounding text is untouched.
splice_llms() {
    local src="$1"
    local dst_tmp="$2"
    awk -v block_file="$NEW_BLOCK" -v begin="$LLMS_BEGIN_MARKER" -v end="$LLMS_END_MARKER" '
        BEGIN {
            inside = 0
            spliced = 0
            # Slurp the generated block (already terminated by a trailing newline).
            while ((getline line < block_file) > 0) {
                block[++bn] = line
            }
            close(block_file)
        }
        # When we hit the BEGIN marker, emit the new block in full and skip
        # everything up to and including the END marker.
        $0 == begin {
            for (i = 1; i <= bn; i++) print block[i]
            inside = 1
            spliced = 1
            next
        }
        inside {
            if ($0 == end) inside = 0
            next
        }
        # First-time install: splice the block above the well-known anchor.
        # The anchor heading is stable (#1152, untouched by this PR).
        !spliced && /^## Sketchfab streaming for samples/ {
            for (i = 1; i <= bn; i++) print block[i]
            print ""
            print "---"
            print ""
            print
            spliced = 1
            next
        }
        { print }
        END {
            if (!spliced) {
                print "Error: collate-demos.sh could not find a place to splice the demos block." > "/dev/stderr"
                exit 1
            }
        }
    ' "$src" > "$dst_tmp"
}

NEW_LLMS_ROOT="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META" "$NEW_KT" "$NEW_BLOCK" "$NEW_LLMS_ROOT"' EXIT

splice_llms "$LLMS_ROOT" "$NEW_LLMS_ROOT"

# ─── 6. Apply or check ────────────────────────────────────────────────────
if [ "$CHECK_MODE" = true ]; then
    # `GeneratedDemos.kt` is no longer committed — it is `.gitignore`d and
    # regenerated at build time by the `generateDemoRegistry` Gradle task
    # (issue #1976). `docs/docs/llms.txt` is likewise no longer committed —
    # it is regenerated from root `llms.txt` at docs-build time and
    # `.gitignore`d (issue #899 hardening). Both are fresh by construction
    # and cannot drift, so `--check` compares neither. Only the root
    # `llms.txt` stays tracked with hand-maintained content, so its generated
    # demo block is still drift-checked here.
    if ! cmp -s "$NEW_LLMS_ROOT" "$LLMS_ROOT"; then
        echo "Error: $LLMS_ROOT demos block is out of date. Run 'bash samples/android-demo/scripts/collate-demos.sh'." >&2
        diff -u "$LLMS_ROOT" "$NEW_LLMS_ROOT" | head -40 || true
        exit 1
    fi
    echo "OK: llms.txt demos section in sync with $fragment_count fragment(s)."
    exit 0
fi

mv "$NEW_KT" "$OUT_FILE"
mv "$NEW_LLMS_ROOT" "$LLMS_ROOT"
echo "Regenerated $OUT_FILE, $LLMS_ROOT from $fragment_count fragment(s)."
