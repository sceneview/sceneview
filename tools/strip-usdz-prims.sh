#!/usr/bin/env bash
#
# strip-usdz-prims.sh — Reproducibly remove name-prefixed prims from a USDZ (#2948).
#
# ── Why this exists ──────────────────────────────────────────────────────────
#
# `samples/ios-demo/SceneViewDemo/Models/tree_scene.usdz` was re-authored by hand
# to drop 2 665 sub-pixel `Grass_*` prims: RealityKit's USD import cost scales
# with prim count, not file size or triangle count, so the untouched asset took
# 91.7 s to parse on the iOS Simulator (#2928) — past any settle window a demo
# or a screenshot waits for. The stripped file was committed, but the strip
# itself existed nowhere in the repo, so it could not be redone or checked.
# This script is that missing step.
#
# It is a purely subtractive text edit on the USD scene graph: every top-level
# prim whose name starts with the given prefix is deleted, brace-block and
# all; nothing else in the layer is touched (materials, bindings, other
# meshes, metadata — byte-identical). Textures are never edited here — an
# orphaned texture (bound by nothing after the strip) is a separate, visually
# verified decision, not something this script decides on its own.
#
# ── How ──────────────────────────────────────────────────────────────────────
#
#   1. Unzip the input .usdz (a flat `scene.usdc` + a `0/` texture directory
#      is the shape every asset in `assets/catalog.json` uses).
#   2. Decompile `scene.usdc` to text with `usdcat` (ships with Xcode Command
#      Line Tools / RealityKit on macOS — already on a machine that can build
#      the iOS demo, no extra install).
#   3. Delete every `def Xform "<prefix>..."` / `def Mesh "<prefix>..."` block
#      at any depth, matched by counting brace depth from the `def` line to
#      its closing `}` — indentation-agnostic, so it does not care how deep
#      the asset's scene graph happens to be. Collapse the blank-line gaps
#      the deletions leave behind (purely cosmetic — a fresh decompile of the
#      result should read exactly as if the prims had never been declared).
#   4. Recompile the edited text back to `scene.usdc` with `usdcat`.
#   5. Repack a `.usdz`: every entry Stored (uncompressed — required by the
#      USDZ spec, `usdzip`'s own default), every entry's *data* start
#      64-byte-aligned via zero-padded zip extra fields (also required —
#      mobile USD readers memory-map the archive and reject misaligned
#      payloads). Entries are written in alphabetical path order (`scene.usdc`
#      first) with unmodified bytes — deterministic and self-contained,
#      independent of any other tool's internal traversal order.
#
# `usdzip -a`/`--arkitAsset` were tried first and rejected: both re-derive the
# texture set from resolved material bindings, in an order that is an
# implementation detail of the installed USD version (verified to differ
# between two flag choices on the same host). This script never re-derives
# anything — it edits the one layer that changed and copies every other byte
# through untouched, which is what makes its output reproducible across USD
# toolchain versions in the first place (the caveat #2948 itself raises).
#
# ── Usage ────────────────────────────────────────────────────────────────────
#
#   bash tools/strip-usdz-prims.sh <input.usdz> <output.usdz> \
#     [--prefix <name>] [--drop <archive-path> ...]
#
# Example — reproducing the tree_scene.usdz strip from the shared original,
# in the same pass also dropping the two Grass_C textures that end up bound
# by nothing once the Grass_* prims are gone (#2948 — dropping a texture is
# never automatic: do this only after visually confirming, on a real render,
# that nothing still uses it):
#
#   bash tools/strip-usdz-prims.sh \
#     assets/models/usdz/tree_scene.usdz /tmp/tree_scene.stripped.usdz \
#     --prefix Grass \
#     --drop 0/Grass_C_baseColor_cutoff128.png \
#     --drop 0/Grass_C_normal.jpg
#
# Options:
#   --prefix <name>   Prims whose local name starts with this string are
#                      removed, wherever in the scene graph they occur.
#   --drop <path>      Archive-relative path (e.g. `0/foo.png`) to omit from
#                      the repacked USDZ, verbatim — no USD text is touched,
#                      so this only belongs in the same pass as a --prefix
#                      strip that actually orphans the file (or after you
#                      have independently confirmed nothing binds it any
#                      more). Repeatable.
#   -h, --help         Show this help
#
# At least one of --prefix / --drop is required.
#
# ── Verifying the output ────────────────────────────────────────────────────
#
# This script only edits the scene graph — it never judges whether the result
# looks right. Always check, in order of cost:
#   1. `usdcat <output> | grep -c 'def Mesh'` — the mesh-prim count moved the
#      way you expect (see `BundledAssetPrimBudgetTests.swift` for tree_scene's
#      own budget: <=100).
#   2. `unzip -v <output>` — every entry `Stored`, 0% compression.
#   3. Load it for real: drag onto a booted iOS Simulator, or
#      `xcodebuild test -only-testing:SceneViewDemoTests/BundledAssetPrimBudgetTests`
#      if it is wired into the iOS demo.
# A strip that "succeeds" but silently drops geometry it shouldn't have is a
# failure — never trust the exit code alone.
#
# Exit codes:
#   0  strip succeeded
#   1  `usdcat` missing, input malformed, nothing matched --prefix, or a --drop path was not found
#   2  invalid arguments
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

usage() {
    sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//'
}

# ---- Args ----
input=""
output=""
prefix=""
drops=()

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)
            [ $# -ge 2 ] || { echo -e "${RED}--prefix requires a value${NC}" >&2; exit 2; }
            prefix="$2"; shift 2 ;;
        --drop)
            [ $# -ge 2 ] || { echo -e "${RED}--drop requires a value${NC}" >&2; exit 2; }
            drops+=("$2"); shift 2 ;;
        -h|--help)
            usage; exit 0 ;;
        --*)
            echo -e "${RED}Unknown option: $1${NC}" >&2; exit 2 ;;
        *)
            if [ -z "$input" ]; then
                input="$1"
            elif [ -z "$output" ]; then
                output="$1"
            else
                echo -e "${RED}Unexpected argument: $1${NC}" >&2; exit 2
            fi
            shift ;;
    esac
done

if [ -z "$input" ] || [ -z "$output" ] || { [ -z "$prefix" ] && [ ${#drops[@]} -eq 0 ]; }; then
    echo -e "${RED}Usage: tools/strip-usdz-prims.sh <input.usdz> <output.usdz> [--prefix <name>] [--drop <path> ...]${NC}" >&2
    usage
    exit 2
fi

if [ ! -f "$input" ]; then
    echo -e "${RED}Input not found: $input${NC}" >&2
    exit 1
fi

if ! command -v usdcat >/dev/null 2>&1; then
    echo -e "${RED}usdcat not found.${NC} It ships with the USD toolchain — on macOS," >&2
    echo "Xcode Command Line Tools already put it on PATH (this is how the RealityKit" >&2
    echo "USDZ importer's own tools are exposed). Install Xcode CLT, or point PATH at" >&2
    echo "an existing USD install." >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/strip-usdz-prims.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

input_abs="$(cd "$(dirname "$input")" && pwd)/$(basename "$input")"
mkdir -p "$(dirname "$output")" 2>/dev/null || true
output_abs="$output"
case "$output_abs" in
    /*) ;;
    *) output_abs="$(pwd)/$output_abs" ;;
esac

echo "Extracting $input ..."
(cd "$work_dir" && unzip -q "$input_abs")

if [ ! -f "$work_dir/scene.usdc" ]; then
    echo -e "${RED}$input does not contain a top-level scene.usdc — not a plain USDZ this script understands.${NC}" >&2
    exit 1
fi

if [ -n "$prefix" ]; then
echo "Decompiling scene.usdc ..."
usdcat -o "$work_dir/scene.usda" "$work_dir/scene.usdc"

echo "Stripping prims named \"${prefix}*\" ..."
python3 - "$work_dir/scene.usda" "$prefix" << 'PYEOF'
import re
import sys

usda_path, prefix = sys.argv[1], sys.argv[2]

with open(usda_path, encoding="utf-8") as f:
    lines = f.readlines()

# Matches `def Xform "Grass_..."` / `def Mesh "Grass_..."` / `def Scope "Grass_..."`
# at ANY indentation — indentation-agnostic on purpose, see the header comment.
pattern = re.compile(r'^(\s*)def (Xform|Mesh|Scope) "' + re.escape(prefix) + r'[^"]*"\s*$')

out = []
i, n = 0, len(lines)
removed = 0
while i < n:
    line = lines[i]
    if pattern.match(line):
        j = i + 1
        if j >= n or lines[j].strip() != "{":
            print(f"error: expected '{{' after {line!r}", file=sys.stderr)
            sys.exit(1)
        depth = 1
        k = j + 1
        while depth > 0:
            if k >= n:
                print(f"error: unbalanced braces starting at {line!r}", file=sys.stderr)
                sys.exit(1)
            s = lines[k].strip()
            if s == "{":
                depth += 1
            elif s == "}":
                depth -= 1
            k += 1
        removed += 1
        i = k
        continue
    out.append(line)
    i += 1

if removed == 0:
    print(f"error: no prim named \"{prefix}*\" found — nothing to strip", file=sys.stderr)
    sys.exit(1)

# Collapse consecutive blank lines a deletion leaves behind, so the result
# reads exactly as if the removed prims had never been declared.
collapsed = []
prev_blank = False
for l in out:
    blank = (l.strip() == "")
    if blank and prev_blank:
        continue
    collapsed.append(l)
    prev_blank = blank

with open(usda_path, "w", encoding="utf-8") as f:
    f.writelines(collapsed)

print(f"Removed {removed} prim(s) named \"{prefix}*\"")
PYEOF

echo "Recompiling scene.usdc ..."
rm -f "$work_dir/scene.usdc"
usdcat -o "$work_dir/scene.usdc" "$work_dir/scene.usda"
rm -f "$work_dir/scene.usda"
fi

if [ ${#drops[@]} -gt 0 ]; then
    for rel in "${drops[@]}"; do
        if [ ! -f "$work_dir/$rel" ]; then
            echo -e "${RED}--drop $rel: not found in the archive${NC}" >&2
            exit 1
        fi
        echo "Dropping $rel ..."
        rm -f "$work_dir/$rel"
    done
fi

echo "Repacking $output (Stored, 64-byte-aligned) ..."
python3 - "$work_dir" "$output_abs" << 'PYEOF'
import os
import struct
import sys
import zipfile

work_dir, out_path = sys.argv[1], sys.argv[2]

# scene.usdc first (the boot layer USD readers look for), then every other
# file in alphabetical path order. Alphabetical rather than "whatever order
# the input archive happened to use": a directory listing after extraction
# is not a reliable proxy for the original zip's central-directory order, so
# alphabetical is the one ordering that is reproducible from the file set
# alone, independent of extraction/filesystem behaviour.
entries = ["scene.usdc"]
for root, _dirs, files in os.walk(work_dir):
    rel_root = os.path.relpath(root, work_dir)
    for name in sorted(files):
        rel = name if rel_root == "." else f"{rel_root}/{name}"
        if rel == "scene.usdc":
            continue
        entries.append(rel)

# Zeroed DOS date/time for every entry — what the already-committed
# tree_scene.usdz uses (`unzip -v` renders a raw 0 as "01-01-1980 00:00",
# the zip epoch, but the stored field is 0x0000, not the properly encoded
# epoch 0x0021). Fixed rather than "now" so two runs over the same input
# produce byte-identical archives.
DOS_TIME, DOS_DATE = 0, 0

def local_header(name: bytes, crc: int, size: int, extra: bytes) -> bytes:
    return struct.pack(
        "<IHHHHHIIIHH",
        0x04034B50, 20, 0, 0, DOS_TIME, DOS_DATE,
        crc, size, size, len(name), len(extra),
    ) + name + extra

with open(out_path, "wb") as out:
    central = []
    for rel in entries:
        path = os.path.join(work_dir, rel)
        with open(path, "rb") as f:
            data = f.read()
        crc = zipfile.crc32(data) & 0xFFFFFFFF
        name = rel.encode("utf-8")

        offset = out.tell()
        data_start = offset + 30 + len(name)
        pad = (-data_start) % 64
        # Zero-filled padding, not a spec TLV extra-field entry: matches what
        # the already-committed tree_scene.usdz uses byte-for-byte, and every
        # USD-aware reader here only cares that `exlen` skips to a 64-byte
        # boundary, not what is inside it.
        extra = b"\x00" * pad

        header = local_header(name, crc, len(data), extra)
        assert (offset + len(header)) % 64 == 0, "data offset misaligned"
        out.write(header)
        out.write(data)

        central.append((name, crc, len(data), offset))

    cd_start = out.tell()
    for name, crc, size, offset in central:
        out.write(struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, 0, 0, DOS_TIME, DOS_DATE, crc, size, size,
            len(name), 0, 0, 0, 0, 0, offset,
        ) + name)
    cd_end = out.tell()

    out.write(struct.pack(
        "<IHHHHIIH",
        0x06054B50, 0, 0, len(central), len(central),
        cd_end - cd_start, cd_start, 0,
    ))

print(f"Wrote {out_path} ({len(entries)} entries)")
PYEOF

echo -e "${GREEN}Done.${NC} $output"
echo "Verify: usdcat \"$output\" | grep -c 'def Mesh'   (and 'unzip -v \"$output\"' for Stored/alignment)"
