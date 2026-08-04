#!/usr/bin/env bash
# Verify the vendored Filament KMP copy against its pinned upstream tag.
#
# Apache-2.0 §4(b) requires modified files to carry a notice saying they were
# changed. This script is how that claim gets CHECKED instead of trusted.
#
# It enumerates in BOTH directions, because a one-way walk of the vendored tree
# is blind to whole classes of change:
#
#   * a DELETED file is invisible to a walk that only lists what is present;
#   * an ADDED file escapes any extension filter it does not happen to match —
#     including a `.so` blob or a `.sh` that runs at build time;
#   * a SYMLINK is not a regular file, so `find -type f` never sees it.
#
# So the vendored tree is pinned by MANIFEST.sha256 (every file, no extension
# filter at all), and the manifest is in turn checked against upstream. A change
# has to survive both to go unnoticed.
#
# Exit codes: 0 = the copy matches upstream and the manifest, 1 = it does not.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM_URL="https://github.com/Erkko68/filament-kmp.git"
MANIFEST="$HERE/MANIFEST.sha256"

# Our own files, which have no upstream twin by construction. ANCHORED on
# purpose: upstream ships its own nested README.md files (java/README.md), and
# an unanchored name match would silently drop them from the manifest — leaving
# exactly the blind spot this script exists to close.
OURS_RE='^(MANIFEST\.sha256|NOTICE|README\.md|LICENSE|diff-upstream\.sh)$'

# `--regenerate` rewrites MANIFEST.sha256 from the tree as it stands. It lives
# here rather than in a doc comment so the pinning command can never drift from
# the checking command.
list_vendored_files() {
    (cd "$HERE" && find . -type f) | sed 's#^\./##' | grep -Ev "$OURS_RE" | LC_ALL=C sort
}

if [[ "${1:-}" == "--regenerate" ]]; then
    # Staged OUTSIDE the vendored tree: a temp file written next to the manifest
    # exists by the time `find` walks the directory, so it would list itself.
    staging="$(mktemp)"
    (cd "$HERE" && list_vendored_files | xargs shasum -a 256) > "$staging"
    mv "$staging" "$MANIFEST"
    echo "Wrote $MANIFEST ($(wc -l < "$MANIFEST" | tr -d ' ') files)."
    echo "Review the diff: a file appearing or vanishing here is the whole point."
    exit 0
fi

# Pinned in NOTICE — the single source of truth for what we copied.
UPSTREAM_TAG="$(sed -n 's/^Copied from upstream tag \(.*\)$/\1/p' "$HERE/NOTICE" | head -1)"
UPSTREAM_COMMIT="$(sed -n 's/^ *commit *\([0-9a-f]\{40\}\).*$/\1/p' "$HERE/NOTICE" | head -1)"

if [[ -z "$UPSTREAM_TAG" || -z "$UPSTREAM_COMMIT" ]]; then
    echo "FAIL: could not read the pinned tag/commit out of $HERE/NOTICE" >&2
    exit 1
fi
if [[ ! -f "$MANIFEST" ]]; then
    echo "FAIL: $MANIFEST is missing — the vendored tree is unpinned." >&2
    exit 1
fi

fail=0
note() { echo "$1"; fail=1; }

# ─── 1. No symlinks ─────────────────────────────────────────────────────────
# The vendored copy is plain files. A symlink is never a legitimate part of it,
# and `find -type f` (which the manifest check below uses) cannot see one — so
# it is rejected on sight rather than compared.
while IFS= read -r link; do
    note "SYMLINK (not allowed)  : ${link#"$HERE"/} -> $(readlink "$link")"
done < <(find "$HERE" -type l)

# ─── 2. The tree matches the manifest, in both directions ───────────────────
# No extension filter: every regular file is covered, including binaries.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A newline in a filename would desynchronise every line-based comparison
# below, so it is refused rather than silently mis-parsed.
if [[ $(find "$HERE" -type f -print0 | tr -dc '\0' | wc -c) -ne $(find "$HERE" -type f | wc -l) ]]; then
    echo "FAIL: a vendored filename contains a newline — refusing to compare." >&2
    exit 1
fi

list_vendored_files > "$TMP/on-disk"
sed 's/^[0-9a-f]*  *//' "$MANIFEST" | LC_ALL=C sort > "$TMP/in-manifest"

while IFS= read -r f; do
    [[ -n "$f" ]] && note "ADDED (not in manifest): $f"
done < <(comm -23 "$TMP/on-disk" "$TMP/in-manifest")

while IFS= read -r f; do
    [[ -n "$f" ]] && note "DELETED (in manifest)  : $f"
done < <(comm -13 "$TMP/on-disk" "$TMP/in-manifest")

# Content, for the files present on both sides.
(cd "$HERE" && shasum -a 256 -c "$MANIFEST" >"$TMP/sums" 2>/dev/null) || true
while IFS= read -r line; do
    [[ -n "$line" ]] && note "CONTENT CHANGED        : ${line%%:*}"
done < <(grep -E ': *FAILED$' "$TMP/sums" | grep -v 'FAILED open or read' || true)

# ─── 3. The manifest itself matches upstream ────────────────────────────────
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

# NOTICE declares the copy verbatim. Until it says otherwise — with a per-file
# list — ANY difference from upstream is undeclared, and a change notice pasted
# into a file header is not enough on its own to make it declared.
declares_verbatim=0
grep -qi 'no file .* modified\|unmodified as of vendoring' "$HERE/NOTICE" && declares_verbatim=1

modified=0
while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    up="$TMP/upstream/$rel"
    if [[ ! -f "$up" ]]; then
        note "NO UPSTREAM TWIN       : $rel"
        continue
    fi
    if ! cmp -s "$HERE/$rel" "$up"; then
        modified=$((modified + 1))
        if (( declares_verbatim )); then
            note "MODIFIED (UNDECLARED)  : $rel"
        else
            echo "MODIFIED               : $rel"
        fi
    fi
done < "$TMP/in-manifest"

echo
echo "Files in manifest: $(wc -l < "$TMP/in-manifest" | tr -d ' ')  |  differing from upstream: $modified"

if (( fail )); then
    echo
    echo "FAIL: the vendored copy no longer matches upstream + MANIFEST.sha256." >&2
    echo "      Apache-2.0 §4(b) requires modified files to state that they were" >&2
    echo "      changed. If a change is intentional:" >&2
    echo "        1. add a notice at the top of each changed file reading" >&2
    echo "           'Modified by the SceneView project (<what changed>).'" >&2
    echo "        2. list it in $HERE/NOTICE, replacing the verbatim claim" >&2
    echo "        3. re-pin the manifest, then review its diff:" >&2
    echo "           bash $HERE/diff-upstream.sh --regenerate" >&2
    exit 1
fi

echo "OK: vendored copy is byte-identical to upstream $UPSTREAM_TAG."
