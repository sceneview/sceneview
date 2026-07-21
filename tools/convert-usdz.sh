#!/usr/bin/env bash
#
# convert-usdz.sh — Convert glTF/GLB models to USDZ for iOS/RealityKit bundling.
#
# SceneView's Apple targets (SceneViewSwift / RealityKit) can only load
# `.usdz` / `.reality` (see `ModelSource.swift`'s own comment: RealityKit's
# `Entity(contentsOf:)` loads only those two formats — it does not transcode
# glTF). Every other platform bundles the source `.glb` directly, so any GLB
# model that should also ship on iOS needs a one-time USDZ conversion. This
# script is that conversion pipeline (issue #2806).
#
# ── Why Blender, not usdzconvert/USDPython ──────────────────────────────────
#
# Apple's own `usdzconvert` (part of the USDPython / Reality Converter
# tooling) is NOT installed on this host and pulling it in means a multi-
# hundred-MB Apple download (Reality Converter.app or the USD Python tools)
# for a single conversion script. Blender is already installed
# (`/Applications/Blender.app`) and ships, out of the box:
#   - a glTF 2.0 importer (`bpy.ops.import_scene.gltf`, handles Draco)
#   - a native USD/USDZ exporter (`bpy.ops.wm.usd_export`) that zips straight
#     to `.usdz` when the output path ends in that extension
#   - full headless scripting (`blender --background --python <script>`)
# So Blender is the zero-extra-install path and the one this script uses.
# If a future contributor prefers `usdzconvert`, this script's CLI surface
# (glb in, usdz out) is a drop-in replacement target — nothing else in the
# pipeline depends on Blender specifically.
#
# ── What this script does NOT do ────────────────────────────────────────────
#
# It only converts. It never touches the repo working tree — every output
# lands under `--out-dir` (default: a fresh `mktemp -d` under `${TMPDIR:-/tmp}`,
# printed at the end). Copying a verified `.usdz` into
# `samples/ios-demo/SceneViewDemo/Models/`, registering it in
# `SceneViewDemo.xcodeproj/project.pbxproj`, and declaring it in
# `assets/catalog.json` are separate, deliberate steps performed by hand
# (or by a caller script) AFTER visually verifying the output — never
# automatically. That split is what makes a failed run leave the working
# tree clean: there is nothing in the repo for a failure to dirty.
#
# ── Usage ────────────────────────────────────────────────────────────────
#
#   bash tools/convert-usdz.sh <input.glb> [<input2.glb> ...] [options]
#
# Options:
#   --out-dir <dir>   Where to write the .usdz files (default: a fresh
#                      mktemp -d under ${TMPDIR:-/tmp}; printed on exit)
#   --force            Reconvert even if a same-named, newer output already
#                      exists in --out-dir (default: skip — idempotent reruns)
#   --blender <path>   Explicit Blender executable (default: $BLENDER_BIN,
#                      then /Applications/Blender.app/…, then `blender` in PATH)
#   -h, --help         Show this help
#
# Example — the 4 Khronos sample models this script was written for:
#
#   bash tools/convert-usdz.sh \
#     samples/android-demo/src/main/assets/models/khronos_lantern.glb \
#     samples/android-demo/src/main/assets/models/khronos_toy_car.glb \
#     samples/android-demo/src/main/assets/models/khronos_fox.glb \
#     samples/android-demo/src/main/assets/models/khronos_damaged_helmet.glb \
#     --out-dir /tmp/usdz-out
#
# ── Verifying the output ────────────────────────────────────────────────────
#
# A conversion that "succeeds" but produces a black / textureless / absurdly
# -scaled model is a failure — always look at the result, never trust the
# exit code alone. Two options, in order of preference:
#
#   1. `qlmanage -p /tmp/usdz-out/foo.usdz` (or `qlmanage -t -s 1200 -o dir
#      file.usdz` for a headless thumbnail) opens the real macOS Quick Look
#      renderer. NOTE: in a sandboxed / non-interactive exec context (no
#      WindowServer session attached to the calling process) this has been
#      observed to hang indefinitely for `.usdz` specifically — even though
#      it works fine for ordinary files in the same shell. If it hangs,
#      don't fight it; use option 2 or drag the file onto a booted iOS
#      Simulator window (or serve it over local HTTP and open the URL in
#      the Simulator's Safari — the correct MIME type, `model/vnd.usdz+zip`,
#      is required for Safari to offer the "View 3D Object" AR Quick Look
#      prompt instead of just downloading the bytes).
#   2. Headless structural sanity check with no GUI dependency at all —
#      re-import the produced file and print mesh/material/texture/bounds:
#
#      blender --background --factory-startup --python-expr "
#      import bpy
#      bpy.ops.wm.usd_import(filepath='/tmp/usdz-out/foo.usdz')
#      for o in bpy.data.objects:
#          print(o.name, o.type)
#      for m in bpy.data.materials:
#          print('material', m.name)
#      for i in bpy.data.images:
#          print('image', i.name, i.size[:])
#      "
#
#      An empty image list, or an image with size (0, 0), means textures did
#      not come through — that is the "black / textureless" failure mode.
#
# Exit codes:
#   0  every input converted (or already up to date) — see per-file PASS/SKIP
#   1  at least one input failed to convert, was missing, or Blender itself
#      could not be found
#   2  invalid arguments
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

usage() {
    sed -n '2,72p' "$0" | sed 's/^# \{0,1\}//'
}

# ---- Args ----
inputs=()
out_dir=""
force=false
blender_bin="${BLENDER_BIN:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --out-dir)
            [ $# -ge 2 ] || { echo -e "${RED}--out-dir requires a value${NC}" >&2; exit 2; }
            out_dir="$2"; shift 2 ;;
        --force)
            force=true; shift ;;
        --blender)
            [ $# -ge 2 ] || { echo -e "${RED}--blender requires a value${NC}" >&2; exit 2; }
            blender_bin="$2"; shift 2 ;;
        -h|--help)
            usage; exit 0 ;;
        --*)
            echo -e "${RED}Unknown option: $1${NC}" >&2; exit 2 ;;
        *)
            inputs+=("$1"); shift ;;
    esac
done

if [ ${#inputs[@]} -eq 0 ]; then
    echo -e "${RED}No input .glb/.gltf files given.${NC}" >&2
    usage
    exit 2
fi

# ---- Locate Blender ----
if [ -z "$blender_bin" ]; then
    if [ -x "/Applications/Blender.app/Contents/MacOS/Blender" ]; then
        blender_bin="/Applications/Blender.app/Contents/MacOS/Blender"
    elif command -v blender >/dev/null 2>&1; then
        blender_bin="$(command -v blender)"
    fi
fi

if [ -z "$blender_bin" ] || [ ! -x "$blender_bin" ]; then
    echo -e "${RED}Blender not found.${NC} Install it (https://www.blender.org/download/)," >&2
    echo "or point --blender / \$BLENDER_BIN at an existing install." >&2
    exit 1
fi

# ---- Output dir — always under /tmp unless the caller overrides it ----
# Intermediate conversion state (Blender scene, unpacked textures before the
# usdz zip step) never touches the repo — see the header comment.
if [ -z "$out_dir" ]; then
    out_dir="$(mktemp -d "${TMPDIR:-/tmp}/convert-usdz.XXXXXX")"
else
    mkdir -p "$out_dir"
fi

# ---- The Blender driver — written once per run to a /tmp work dir ----
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/convert-usdz-work.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

driver="$work_dir/driver.py"
cat > "$driver" << 'PYEOF'
import sys
import bpy

argv = sys.argv[sys.argv.index("--") + 1:]
src_glb, dst_usdz = argv[0], argv[1]

# Start from a truly empty scene so nothing from Blender's factory startup
# file (default cube/camera/light) leaks into the export.
bpy.ops.wm.read_factory_settings(use_empty=True)

import_result = bpy.ops.import_scene.gltf(filepath=src_glb)
if import_result != {'FINISHED'}:
    print(f"IMPORT FAILED: {import_result}", file=sys.stderr)
    sys.exit(1)

export_result = bpy.ops.wm.usd_export(
    filepath=dst_usdz,
    export_textures=True,          # pack textures into the .usdz zip
    generate_preview_surface=True, # UsdPreviewSurface shader RealityKit reads
    export_materials=True,
    export_animation=True,
    export_armatures=True,
    export_shapekeys=True,
    triangulate_meshes=False,
    overwrite_textures=True,
)
if export_result != {'FINISHED'}:
    print(f"EXPORT FAILED: {export_result}", file=sys.stderr)
    sys.exit(1)

# Structural sanity report — cheap, no render, but catches the "black /
# textureless" failure mode: a converted model with zero image textures, or
# an image that decoded to 0x0, did not actually bring its material along.
mesh_objs = [o for o in bpy.data.objects if o.type == 'MESH']
mats = list(bpy.data.materials)
imgs = list(bpy.data.images)

import mathutils
min_co = mathutils.Vector((float("inf"),) * 3)
max_co = mathutils.Vector((float("-inf"),) * 3)
for o in mesh_objs:
    for corner in o.bound_box:
        world_co = o.matrix_world @ mathutils.Vector(corner)
        min_co = mathutils.Vector(min(a, b) for a, b in zip(min_co, world_co))
        max_co = mathutils.Vector(max(a, b) for a, b in zip(max_co, world_co))
size = (max_co - min_co) if mesh_objs else mathutils.Vector((0, 0, 0))

zero_size_images = [i.name for i in imgs if i.size[0] == 0 or i.size[1] == 0]

print(
    "REPORT "
    f"meshes={len(mesh_objs)} materials={len(mats)} textures={len(imgs)} "
    f"zero_size_textures={len(zero_size_images)} "
    f"bbox={size.x:.3f}x{size.y:.3f}x{size.z:.3f}"
)
if not mesh_objs:
    print("WARNING: no mesh objects after import — output usdz has no geometry", file=sys.stderr)
if imgs and zero_size_images:
    print(f"WARNING: {len(zero_size_images)} zero-size texture(s): {zero_size_images}", file=sys.stderr)
PYEOF

# ---- Convert each input ----
fail_count=0
skip_count=0
ok_count=0

for src in "${inputs[@]}"; do
    if [ ! -f "$src" ]; then
        echo -e "  ${RED}MISSING${NC} $src"
        fail_count=$((fail_count + 1))
        continue
    fi
    case "$src" in
        *.glb|*.gltf) ;;
        *)
            echo -e "  ${RED}SKIP${NC} $src (not a .glb/.gltf)"
            fail_count=$((fail_count + 1))
            continue
            ;;
    esac

    base="$(basename "$src")"
    base="${base%.glb}"
    base="${base%.gltf}"
    dst="$out_dir/$base.usdz"

    if [ "$force" != true ] && [ -f "$dst" ] && [ "$dst" -nt "$src" ]; then
        echo -e "  ${YELLOW}SKIP${NC} $base.usdz (up to date — use --force to reconvert)"
        skip_count=$((skip_count + 1))
        continue
    fi

    echo -e "${BLUE}==${NC} $base.glb -> $base.usdz"
    if log="$("$blender_bin" --background --factory-startup --python "$driver" -- \
                "$(cd "$(dirname "$src")" && pwd)/$(basename "$src")" "$dst" 2>&1)"; then
        echo "$log" | grep -E "^REPORT|^WARNING" || true
        if [ -s "$dst" ]; then
            size_h="$(du -h "$dst" | cut -f1)"
            echo -e "  ${GREEN}OK${NC} $dst ($size_h)"
            ok_count=$((ok_count + 1))
        else
            echo -e "  ${RED}FAIL${NC} $base — Blender exited 0 but produced no/empty output"
            echo "$log" | tail -20
            fail_count=$((fail_count + 1))
        fi
    else
        echo -e "  ${RED}FAIL${NC} $base — Blender conversion failed"
        echo "$log" | tail -20
        fail_count=$((fail_count + 1))
    fi
done

echo
echo -e "${BLUE}== Summary ==${NC}  ok=$ok_count skip=$skip_count fail=$fail_count"
echo "Output directory: $out_dir"
echo "(Nothing was written to the repo — copy the verified .usdz files in by hand.)"

[ "$fail_count" -eq 0 ]
