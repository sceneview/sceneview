#!/usr/bin/env bash
#
# GenerateFilamat.sh — compile every .mat source to its .filamat blob,
# using a matc binary pinned to the Filament version in gradle/libs.versions.toml.
#
# This script is the single entry point for every Filament material in the
# repo. It exists because the Filament runtime ↔ .filamat ABI invariant has
# already caused a real shipping incident (v4.1.0 — 10 demos crashed at
# runtime because the .filamat blobs were compiled with a different matc
# version than the runtime expected). See CLAUDE.md "Filament runtime ↔
# .filamat ABI invariant" and CONTRIBUTING.md.
#
# Inventory (21 mats → 21 filamats):
#   sceneview/src/main/materials/         (12) → sceneview/src/main/assets/materials/
#   arsceneview/src/main/materials/        (6) → arsceneview/src/main/assets/materials/
#   website-static/materials/              (3) → website-static/materials/
#
# Usage:
#   bash tools/GenerateFilamat.sh                 # regenerate all 21 filamats
#   bash tools/GenerateFilamat.sh --check         # diff all 21 against committed blobs; exit 1 on drift
#   bash tools/GenerateFilamat.sh --mat <name>    # regenerate one (e.g. --mat opaque_colored)
#   bash tools/GenerateFilamat.sh --ci-tolerant   # treat matc download failure as WARN, not FAIL
#   bash tools/GenerateFilamat.sh --help
#
# matc cache: ~/.cache/sceneview/matc-<version>/  (override with $XDG_CACHE_HOME)
#
# Closes #1912 (Part B).

set -u
set -o pipefail

# ─── Locate repo root ───────────────────────────────────────────────────
ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$ROOT" ]; then
    SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
    ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
fi
cd "$ROOT"

# ─── Parse args ─────────────────────────────────────────────────────────
MODE="generate"          # generate | check
ONLY_MAT=""              # empty = all
CI_TOLERANT="0"

print_help() {
    sed -n '1,30p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --check)         MODE="check"; shift ;;
        --mat)           shift; ONLY_MAT="${1:-}"; [ -z "$ONLY_MAT" ] && { echo "ERROR: --mat requires a name" >&2; exit 2; }; shift ;;
        --ci-tolerant)   CI_TOLERANT="1"; shift ;;
        -h|--help)       print_help; exit 0 ;;
        *)               echo "ERROR: unknown flag '$1' (try --help)" >&2; exit 2 ;;
    esac
done

# ─── Colors ─────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; CYAN=''; NC=''
fi

log()  { printf '%s\n' "$*"; }
ok()   { printf "${GREEN}[OK]${NC}   %s\n" "$*"; }
warn() { printf "${YELLOW}[WARN]${NC} %s\n" "$*"; }
err()  { printf "${RED}[FAIL]${NC} %s\n" "$*" >&2; }

# ─── Resolve Filament version ───────────────────────────────────────────
TOML="$ROOT/gradle/libs.versions.toml"
if [ ! -f "$TOML" ]; then
    err "gradle/libs.versions.toml not found at $TOML"
    exit 2
fi

FILAMENT_VERSION=$(grep -E '^filament[[:space:]]*=' "$TOML" | head -1 | sed -E 's/^filament[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')
if [ -z "$FILAMENT_VERSION" ]; then
    err "Failed to parse 'filament = ...' from $TOML"
    exit 2
fi
log "${CYAN}Filament version (pinned):${NC} $FILAMENT_VERSION"

# ─── Inventory ──────────────────────────────────────────────────────────
# Format: "<module>:<name>:<src-path>:<out-path>:<matc-flags>"
# matc-flags is the full flag list (excluding -o and the source path).
#
# Why per-mat flags: the committed .filamat blobs were compiled with FIVE
# distinct profiles (visible in each blob's MRPC chunk via `strings`).
# We preserve each blob's exact compile profile — including flag *order*,
# because matc embeds the verbatim flag string and any reorder produces
# a different MRPC chunk hence a non-identical blob:
#
#   Profile A — heavy Android (sceneview lit + textured + video + view):
#     "-p all -a all"   (no --optimize-size)
#
#   Profile B — lean Android (sceneview unlit, 2 mats):
#     "-a opengl -p mobile"   (note flag order!)
#
#   Profile C — ARCore (arsceneview, 6 mats):
#     "--optimize-size -p mobile -a opengl -a vulkan"
#
#   Profile D — website-static (Filament.js / WebGL, 3 mats):
#     "-p mobile -a opengl"
#
#   Profile E — Android occluder (sceneview occlusion, 1 mat):
#     "-a vulkan -a opengl -p mobile"   (note flag order!)
#
# When adding a new material, pick a profile based on the deployment
# target. Audit #1918 (Part A) reviewed the A-vs-B split: Profile B
# (`-a opengl -p mobile`) is a deliberate size optimisation for the two
# tiny unlit colour mats — the rest of the sceneview set stays on
# Profile A so the lit/textured shaders keep every backend. The split is
# intentional, not drift; left as-is. See the audit summary in the #1918 PR.
MATS=(
    "sceneview:image_texture:sceneview/src/main/materials/image_texture.mat:sceneview/src/main/assets/materials/image_texture.filamat:-p all -a all"
    "sceneview:occlusion:sceneview/src/main/materials/occlusion.mat:sceneview/src/main/assets/materials/occlusion.filamat:-a vulkan -a opengl -p mobile"
    "sceneview:opaque_colored:sceneview/src/main/materials/opaque_colored.mat:sceneview/src/main/assets/materials/opaque_colored.filamat:-p all -a all"
    "sceneview:opaque_textured:sceneview/src/main/materials/opaque_textured.mat:sceneview/src/main/assets/materials/opaque_textured.filamat:-p all -a all"
    "sceneview:opaque_unlit_colored:sceneview/src/main/materials/opaque_unlit_colored.mat:sceneview/src/main/assets/materials/opaque_unlit_colored.filamat:-a opengl -p mobile"
    "sceneview:transparent_colored:sceneview/src/main/materials/transparent_colored.mat:sceneview/src/main/assets/materials/transparent_colored.filamat:-p all -a all"
    "sceneview:transparent_textured:sceneview/src/main/materials/transparent_textured.mat:sceneview/src/main/assets/materials/transparent_textured.filamat:-p all -a all"
    "sceneview:transparent_unlit_colored:sceneview/src/main/materials/transparent_unlit_colored.mat:sceneview/src/main/assets/materials/transparent_unlit_colored.filamat:-a opengl -p mobile"
    "sceneview:video_texture:sceneview/src/main/materials/video_texture.mat:sceneview/src/main/assets/materials/video_texture.filamat:-p all -a all"
    "sceneview:video_texture_chroma_key:sceneview/src/main/materials/video_texture_chroma_key.mat:sceneview/src/main/assets/materials/video_texture_chroma_key.filamat:-p all -a all"
    "sceneview:view_texture_lit:sceneview/src/main/materials/view_texture_lit.mat:sceneview/src/main/assets/materials/view_texture_lit.filamat:-p all -a all"
    "sceneview:view_texture_unlit:sceneview/src/main/materials/view_texture_unlit.mat:sceneview/src/main/assets/materials/view_texture_unlit.filamat:-p all -a all"
    "arsceneview:camera_stream_depth:arsceneview/src/main/materials/camera_stream_depth.mat:arsceneview/src/main/assets/materials/camera_stream_depth.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:camera_stream_flat:arsceneview/src/main/materials/camera_stream_flat.mat:arsceneview/src/main/assets/materials/camera_stream_flat.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:face_mesh:arsceneview/src/main/materials/face_mesh.mat:arsceneview/src/main/assets/materials/face_mesh.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:face_mesh_occluder:arsceneview/src/main/materials/face_mesh_occluder.mat:arsceneview/src/main/assets/materials/face_mesh_occluder.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:plane_renderer:arsceneview/src/main/materials/plane_renderer.mat:arsceneview/src/main/assets/materials/plane_renderer.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:plane_renderer_shadow:arsceneview/src/main/materials/plane_renderer_shadow.mat:arsceneview/src/main/assets/materials/plane_renderer_shadow.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "website-static:lit_colored:website-static/materials/lit_colored.mat:website-static/materials/lit_colored.filamat:-p mobile -a opengl"
    "website-static:transparent_colored:website-static/materials/transparent_colored.mat:website-static/materials/transparent_colored.filamat:-p mobile -a opengl"
    "website-static:unlit_colored:website-static/materials/unlit_colored.mat:website-static/materials/unlit_colored.filamat:-p mobile -a opengl"
)

# ─── Resolve matc ───────────────────────────────────────────────────────
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/sceneview"
MATC_DIR="$CACHE_BASE/matc-$FILAMENT_VERSION"
MATC_BIN="$MATC_DIR/bin/matc"

detect_os_tarball() {
    case "$(uname -s)" in
        Darwin) echo "mac" ;;
        Linux)  echo "linux" ;;
        *)      echo "unsupported" ;;
    esac
}

download_matc() {
    local os_tag tarball_url tarball_path
    os_tag=$(detect_os_tarball)
    if [ "$os_tag" = "unsupported" ]; then
        err "Unsupported host OS: $(uname -s) — only macOS and Linux have prebuilt matc tarballs."
        return 1
    fi

    tarball_url="https://github.com/google/filament/releases/download/v${FILAMENT_VERSION}/filament-v${FILAMENT_VERSION}-${os_tag}.tgz"
    tarball_path="$CACHE_BASE/filament-v${FILAMENT_VERSION}-${os_tag}.tgz"

    mkdir -p "$CACHE_BASE"

    if ! command -v curl >/dev/null 2>&1; then
        err "curl not found — required to download matc"
        return 1
    fi
    if ! command -v tar >/dev/null 2>&1; then
        err "tar not found — required to extract matc"
        return 1
    fi

    log "${CYAN}Downloading${NC} $tarball_url"
    # -L follow redirects, -f fail on HTTP errors, -S show errors, -s silent progress
    if ! curl -fLsS -o "$tarball_path.part" "$tarball_url"; then
        err "Failed to download $tarball_url (network sandbox? proxy? release missing?)"
        rm -f "$tarball_path.part"
        return 1
    fi
    mv "$tarball_path.part" "$tarball_path"

    log "${CYAN}Extracting matc${NC} to $MATC_DIR"
    local tmp_extract="$CACHE_BASE/extract-$FILAMENT_VERSION.$$"
    rm -rf "$tmp_extract"
    mkdir -p "$tmp_extract"

    if ! tar -xzf "$tarball_path" -C "$tmp_extract"; then
        err "Failed to extract $tarball_path"
        rm -rf "$tmp_extract"
        return 1
    fi

    # The tarball root is "filament/", so locate matc under it.
    local extracted_matc
    extracted_matc=$(find "$tmp_extract" -type f -name matc -path '*/bin/matc' 2>/dev/null | head -1)
    if [ -z "$extracted_matc" ]; then
        err "matc binary not found in extracted tarball at $tmp_extract"
        rm -rf "$tmp_extract"
        return 1
    fi

    rm -rf "$MATC_DIR"
    mkdir -p "$MATC_DIR/bin"
    cp "$extracted_matc" "$MATC_DIR/bin/matc"
    chmod +x "$MATC_DIR/bin/matc"

    rm -rf "$tmp_extract"
    # Keep the tarball as cache; can be deleted manually.
    return 0
}

ensure_matc() {
    if [ -x "$MATC_BIN" ]; then
        return 0
    fi
    download_matc
}

# ─── Verify matc is operational and reports a sane MATERIAL_VERSION ───
# `matc --version` returns Filament's MATERIAL_VERSION integer (e.g. "71"
# for Filament 1.71.0). It is NOT the semver release tag. The mapping is
# stable for a given Filament minor release — Filament 1.71.x always emits
# MATERIAL_VERSION 71. We use this as a sanity check that matc executes
# and reports a value consistent with the FILAMENT_VERSION's minor digit.
verify_matc_version() {
    local out expected_minor
    if out=$("$MATC_BIN" --version 2>&1); then :; else out=""; fi

    if [ -z "$out" ]; then
        warn "matc did not respond to --version; trusting tarball naming"
        return 0
    fi

    # Extract minor version from FILAMENT_VERSION (e.g. "1.71.0" → "71").
    expected_minor=$(printf '%s' "$FILAMENT_VERSION" | awk -F. '{print $2}')
    if [ -z "$expected_minor" ]; then
        warn "could not parse minor from FILAMENT_VERSION='$FILAMENT_VERSION'; skipping version check"
        return 0
    fi

    if printf '%s' "$out" | grep -qE "^${expected_minor}$"; then
        ok "matc MATERIAL_VERSION=$out matches Filament 1.${expected_minor}.x"
        return 0
    fi

    err "matc reports MATERIAL_VERSION='$out' but expected '$expected_minor' (from $FILAMENT_VERSION)"
    err "Delete $MATC_DIR and re-run to re-download."
    return 1
}

# ─── Compile a single .mat → .filamat ───────────────────────────────────
# Each inventory entry provides its full matc flag list (excluding -o and
# the source path). matc embeds these flags in the .filamat MRPC chunk;
# byte-identical regeneration requires reproducing both the flag values
# and their order (`-p mobile -a opengl` vs `-a opengl -p mobile` produce
# different blobs).
compile_mat() {
    local src="$1" out="$2" flags="${3:-}"
    if [ -n "$flags" ]; then
        # shellcheck disable=SC2086  # intentional word-splitting of flag list
        "$MATC_BIN" $flags -o "$out" "$src"
    else
        "$MATC_BIN" -o "$out" "$src"
    fi
}

# ─── Bootstrap matc (with --ci-tolerant fallback) ───────────────────────
if ! ensure_matc; then
    if [ "$CI_TOLERANT" = "1" ]; then
        warn "matc unavailable; --ci-tolerant set, skipping filamat check."
        exit 0
    fi
    err "matc unavailable — re-run with network access, or pass --ci-tolerant to skip."
    exit 2
fi

if ! verify_matc_version; then
    if [ "$CI_TOLERANT" = "1" ]; then
        warn "matc version verification failed; --ci-tolerant set, skipping."
        exit 0
    fi
    exit 2
fi

# ─── Iterate inventory ──────────────────────────────────────────────────
log ""
if [ "$MODE" = "check" ]; then
    log "${CYAN}Mode:${NC} --check (regenerate to tmp + diff against committed blobs)"
else
    log "${CYAN}Mode:${NC} generate (regenerate all .filamat blobs in place)"
fi
log ""

TMP_ROOT=$(mktemp -d -t sceneview-filamat-XXXXXX)
trap 'rm -rf "$TMP_ROOT"' EXIT

DRIFT=0
COMPILED=0
SKIPPED=0
DRIFTED_MATS=()
MISSING_SRC=()

for entry in "${MATS[@]}"; do
    IFS=':' read -r module name src out extra <<< "$entry"

    if [ -n "$ONLY_MAT" ] && [ "$name" != "$ONLY_MAT" ]; then
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    full_src="$ROOT/$src"
    full_out="$ROOT/$out"

    if [ ! -f "$full_src" ]; then
        err "[$module:$name] source missing: $src"
        MISSING_SRC+=("$module:$name")
        DRIFT=$((DRIFT + 1))
        continue
    fi

    tmp_out="$TMP_ROOT/${module}_${name}.filamat"

    if ! compile_mat "$full_src" "$tmp_out" "$extra" >/dev/null 2>&1; then
        err "[$module:$name] matc compilation failed for $src"
        # Re-run loud for the user
        compile_mat "$full_src" "$tmp_out" "$extra" || true
        DRIFTED_MATS+=("$module:$name")
        DRIFT=$((DRIFT + 1))
        continue
    fi

    if [ "$MODE" = "check" ]; then
        if [ ! -f "$full_out" ]; then
            err "[$module:$name] committed .filamat missing: $out"
            DRIFTED_MATS+=("$module:$name (missing committed blob)")
            DRIFT=$((DRIFT + 1))
            continue
        fi
        if cmp -s "$full_out" "$tmp_out"; then
            ok "[$module:$name] in sync ($(wc -c < "$tmp_out" | tr -d ' ') bytes)"
            COMPILED=$((COMPILED + 1))
        else
            committed_size=$(wc -c < "$full_out" | tr -d ' ')
            fresh_size=$(wc -c < "$tmp_out" | tr -d ' ')
            err "[$module:$name] DRIFTED — committed=$committed_size bytes, fresh=$fresh_size bytes"
            DRIFTED_MATS+=("$module:$name")
            DRIFT=$((DRIFT + 1))
        fi
    else
        # generate mode — move temp to target
        mkdir -p "$(dirname "$full_out")"
        if [ -f "$full_out" ] && cmp -s "$full_out" "$tmp_out"; then
            ok "[$module:$name] unchanged"
        else
            mv "$tmp_out" "$full_out"
            ok "[$module:$name] regenerated → $out"
        fi
        COMPILED=$((COMPILED + 1))
    fi
done

log ""

# ─── Final report ───────────────────────────────────────────────────────
if [ "$MODE" = "check" ]; then
    if [ "$DRIFT" -eq 0 ]; then
        log "${GREEN}All $COMPILED filamat blob(s) are in sync with their .mat sources (matc $FILAMENT_VERSION).${NC}"
        exit 0
    else
        err "${DRIFT} filamat blob(s) drifted from their .mat sources:"
        for d in "${DRIFTED_MATS[@]}"; do
            err "  - $d"
        done
        if [ "${#MISSING_SRC[@]}" -gt 0 ]; then
            err "  missing sources:"
            for m in "${MISSING_SRC[@]}"; do
                err "    - $m"
            done
        fi
        err ""
        err "Fix: re-run 'bash tools/GenerateFilamat.sh' and commit the updated .filamat blobs"
        err "alongside the .mat source edits. See CLAUDE.md 'Filament runtime ↔ .filamat ABI invariant'."
        exit 1
    fi
else
    if [ "$DRIFT" -gt 0 ]; then
        err "${DRIFT} mat(s) failed to compile."
        exit 1
    fi
    if [ -n "$ONLY_MAT" ]; then
        log "${GREEN}Regenerated $COMPILED filamat blob(s) (filter: --mat $ONLY_MAT).${NC}"
    else
        log "${GREEN}Regenerated $COMPILED filamat blob(s) with matc $FILAMENT_VERSION.${NC}"
    fi
    exit 0
fi
