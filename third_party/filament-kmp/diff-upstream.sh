#!/usr/bin/env bash
# Diff the vendored Filament KMP copy against its upstream tag.
#
# Apache-2.0 §4(b) requires modified files to carry a notice saying they were
# changed. This script is how that claim gets checked instead of trusted: it
# fetches the pinned upstream tag into a temp clone and reports every vendored
# file that differs.
#
# Exit codes: 0 = every difference is declared, 1 = an undeclared modification.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM_URL="https://github.com/Erkko68/filament-kmp.git"

# Pinned in NOTICE — the single source of truth for what we copied.
UPSTREAM_TAG="$(sed -n 's/^Copied from upstream tag \(.*\)$/\1/p' "$HERE/NOTICE" | head -1)"
UPSTREAM_COMMIT="$(sed -n 's/^ *commit *\([0-9a-f]\{40\}\).*$/\1/p' "$HERE/NOTICE" | head -1)"

if [[ -z "$UPSTREAM_TAG" || -z "$UPSTREAM_COMMIT" ]]; then
    echo "FAIL: could not read the pinned tag/commit out of $HERE/NOTICE" >&2
    exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Vendored copy : $HERE"
echo "Upstream tag  : $UPSTREAM_TAG ($UPSTREAM_COMMIT)"
echo

git clone --quiet --depth 1 --branch "$UPSTREAM_TAG" "$UPSTREAM_URL" "$TMP/upstream" 2>/dev/null || {
    echo "FAIL: could not clone $UPSTREAM_URL at tag $UPSTREAM_TAG" >&2
    exit 1
}

actual_commit="$(git -C "$TMP/upstream" rev-parse HEAD)"
if [[ "$actual_commit" != "$UPSTREAM_COMMIT" ]]; then
    echo "FAIL: tag $UPSTREAM_TAG now points at" >&2
    echo "        $actual_commit" >&2
    echo "      but NOTICE pins" >&2
    echo "        $UPSTREAM_COMMIT" >&2
    echo >&2
    echo "      The upstream tag has been moved. Diffing against it would compare" >&2
    echo "      the copy to a baseline it was never taken from, which can hide a" >&2
    echo "      real modification. Re-vendor from the pinned commit, or update" >&2
    echo "      NOTICE deliberately after reviewing what upstream changed." >&2
    exit 1
fi

modified=0
undeclared=0

# Walk every vendored source file and compare it with its upstream twin. The
# vendored layout drops the `src/` prefix for `java/`, so map that one back.
while IFS= read -r -d '' file; do
    rel="${file#"$HERE"/}"
    case "$rel" in
        NOTICE|README.md|LICENSE|diff-upstream.sh) continue ;;
        java/*) up="$TMP/upstream/java/${rel#java/}" ;;
        *)      up="$TMP/upstream/$rel" ;;
    esac

    if [[ ! -f "$up" ]]; then
        # A file with no upstream twin is either genuinely new or a renamed
        # upstream file. Both are "modified work" under Apache-2.0 §4(b), and a
        # rename is the easy way to smuggle an edit past a content diff — so this
        # counts as undeclared unless the file says what it is.
        if head -40 "$file" | grep -qi 'modified by the SceneView project\|added by the SceneView project'; then
            echo "ADDED (declared)     : $rel"
        else
            echo "ADDED (UNDECLARED)   : $rel"
            undeclared=$((undeclared + 1))
        fi
        modified=$((modified + 1))
        continue
    fi

    if ! cmp -s "$file" "$up"; then
        modified=$((modified + 1))
        # A modified file must say so, per Apache-2.0 §4(b). Look for the marker
        # in the first 40 lines, where a file-top notice belongs.
        if head -40 "$file" | grep -qi 'modified by the SceneView project'; then
            echo "MODIFIED (declared)  : $rel"
        else
            echo "MODIFIED (UNDECLARED): $rel"
            undeclared=$((undeclared + 1))
        fi
    fi
done < <(find "$HERE" -type f \
    \( -name '*.kt' -o -name '*.kts' -o -name '*.java' -o -name '*.c' -o -name '*.cpp' \
       -o -name '*.h' -o -name '*.hpp' -o -name '*.map' -o -name '*.txt' -o -name '*.md' \
       -o -name '*.properties' -o -name '*.pro' \) -print0)
# `.java` covers the two FFM support files and `.map` covers c/filament-c.map, the
# linker version script that shapes the exported native ABI — both are plausible
# things to edit, and both were invisible to an earlier version of this filter.

echo
echo "Files differing from upstream: $modified"

if (( undeclared > 0 )); then
    echo
    echo "FAIL: $undeclared modified file(s) carry no change notice." >&2
    echo "      Apache-2.0 §4(b) requires modified files to state that they" >&2
    echo "      were changed. Add a notice at the top of each file reading:" >&2
    echo >&2
    echo "        Modified by the SceneView project (<what changed>)." >&2
    exit 1
fi

echo "All differences are declared."
