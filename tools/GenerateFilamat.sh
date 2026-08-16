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
# Inventory (28 mats → 28 filamats), across THREE pinned toolchains:
#   sceneview/src/main/materials/         (15) → sceneview/src/main/assets/materials/
#   arsceneview/src/main/materials/        (9) → arsceneview/src/main/assets/materials/
#   website-static/materials/              (3) → website-static/materials/     [filamentWebsite]
#   sceneview-web/materials/               (1) → sceneview-web/materials/      [filamentWeb]
#
# Three toolchains because there are three runtimes, each with its own
# MATERIAL_VERSION, and Filament refuses any package whose version is not an
# exact match:
#   `filament`        — Android (Filament AAR)            → v72
#   `filamentWebsite` — the Filament.js vendored at
#                       website-static/js/filament/       → v70   (#2783)
#   `filamentWeb`     — the npm `filament` the Kotlin/JS
#                       bundle loads                      → v52   (#2646 P2)
#
# The sceneview-web blob is additionally emitted as a generated base64 Kotlin
# file (SplatMaterialBlob.kt) so the single-file npm bundle needs no runtime
# fetch — both artifacts are committed and --check-diffed (#2646 P2).
#
# Usage:
#   bash tools/GenerateFilamat.sh                 # regenerate all 27 filamats
#   bash tools/GenerateFilamat.sh --check         # diff all 27 against committed blobs; exit 1 on drift
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
log "${CYAN}Filament version (pinned, Android):${NC} $FILAMENT_VERSION"
FILAMENT_ANDROID_VERSION="$FILAMENT_VERSION"

# The WEB runtime toolchain — a second pinned matc tracking the npm `filament`
# runtime the Kotlin/JS bundle loads (see the `filamentWeb` comment in the toml).
FILAMENT_WEB_VERSION=$(grep -E '^filamentWeb[[:space:]]*=' "$TOML" | head -1 | sed -E 's/^filamentWeb[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')
if [ -z "$FILAMENT_WEB_VERSION" ]; then
    err "Failed to parse 'filamentWeb = ...' from $TOML"
    exit 2
fi
log "${CYAN}Filament version (pinned, web npm):${NC} $FILAMENT_WEB_VERSION"

# The WEBSITE runtime toolchain — a third pinned matc tracking the Filament.js
# build vendored under website-static/js/filament/ (see the `filamentWebsite`
# comment in the toml). #2783: before this pin existed the 3 website blobs rode
# the Android pin and were two material versions ahead of their own runtime.
FILAMENT_WEBSITE_VERSION=$(grep -E '^filamentWebsite[[:space:]]*=' "$TOML" | head -1 | sed -E 's/^filamentWebsite[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')
if [ -z "$FILAMENT_WEBSITE_VERSION" ]; then
    err "Failed to parse 'filamentWebsite = ...' from $TOML"
    exit 2
fi
log "${CYAN}Filament version (pinned, website):${NC} $FILAMENT_WEBSITE_VERSION"

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
#   Profile C — ARCore (arsceneview 7 mats + sceneview semantics_overlay):
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
    "sceneview:contact_shadow:sceneview/src/main/materials/contact_shadow.mat:sceneview/src/main/assets/materials/contact_shadow.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "sceneview:image_texture:sceneview/src/main/materials/image_texture.mat:sceneview/src/main/assets/materials/image_texture.filamat:-p all -a all"
    "sceneview:occlusion:sceneview/src/main/materials/occlusion.mat:sceneview/src/main/assets/materials/occlusion.filamat:-a vulkan -a opengl -p mobile"
    "sceneview:opaque_colored:sceneview/src/main/materials/opaque_colored.mat:sceneview/src/main/assets/materials/opaque_colored.filamat:-p all -a all"
    "sceneview:semantics_overlay:sceneview/src/main/materials/semantics_overlay.mat:sceneview/src/main/assets/materials/semantics_overlay.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "sceneview:opaque_textured:sceneview/src/main/materials/opaque_textured.mat:sceneview/src/main/assets/materials/opaque_textured.filamat:-p all -a all"
    "sceneview:opaque_unlit_colored:sceneview/src/main/materials/opaque_unlit_colored.mat:sceneview/src/main/assets/materials/opaque_unlit_colored.filamat:-a opengl -p mobile"
    "sceneview:splat:sceneview/src/main/materials/splat.mat:sceneview/src/main/assets/materials/splat.filamat:-a opengl -p mobile"
    "sceneview:transparent_colored:sceneview/src/main/materials/transparent_colored.mat:sceneview/src/main/assets/materials/transparent_colored.filamat:-p all -a all"
    "sceneview:transparent_textured:sceneview/src/main/materials/transparent_textured.mat:sceneview/src/main/assets/materials/transparent_textured.filamat:-p all -a all"
    "sceneview:transparent_unlit_colored:sceneview/src/main/materials/transparent_unlit_colored.mat:sceneview/src/main/assets/materials/transparent_unlit_colored.filamat:-a opengl -p mobile"
    "sceneview:video_texture:sceneview/src/main/materials/video_texture.mat:sceneview/src/main/assets/materials/video_texture.filamat:-p all -a all"
    "sceneview:video_texture_chroma_key:sceneview/src/main/materials/video_texture_chroma_key.mat:sceneview/src/main/assets/materials/video_texture_chroma_key.filamat:-p all -a all"
    "sceneview:view_texture_lit:sceneview/src/main/materials/view_texture_lit.mat:sceneview/src/main/assets/materials/view_texture_lit.filamat:-p all -a all"
    "sceneview:view_texture_unlit:sceneview/src/main/materials/view_texture_unlit.mat:sceneview/src/main/assets/materials/view_texture_unlit.filamat:-p all -a all"
    "arsceneview:camera_stream_depth:arsceneview/src/main/materials/camera_stream_depth.mat:arsceneview/src/main/assets/materials/camera_stream_depth.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:camera_stream_person_occlusion:arsceneview/src/main/materials/camera_stream_person_occlusion.mat:arsceneview/src/main/assets/materials/camera_stream_person_occlusion.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:camera_stream_flat:arsceneview/src/main/materials/camera_stream_flat.mat:arsceneview/src/main/assets/materials/camera_stream_flat.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:face_mesh:arsceneview/src/main/materials/face_mesh.mat:arsceneview/src/main/assets/materials/face_mesh.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:face_mesh_occluder:arsceneview/src/main/materials/face_mesh_occluder.mat:arsceneview/src/main/assets/materials/face_mesh_occluder.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:plane_renderer:arsceneview/src/main/materials/plane_renderer.mat:arsceneview/src/main/assets/materials/plane_renderer.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:plane_renderer_v2:arsceneview/src/main/materials/plane_renderer_v2.mat:arsceneview/src/main/assets/materials/plane_renderer_v2.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:plane_renderer_shadow:arsceneview/src/main/materials/plane_renderer_shadow.mat:arsceneview/src/main/assets/materials/plane_renderer_shadow.filamat:--optimize-size -p mobile -a opengl -a vulkan"
    "arsceneview:shadow_receiver:arsceneview/src/main/materials/shadow_receiver.mat:arsceneview/src/main/assets/materials/shadow_receiver.filamat:--optimize-size -p mobile -a opengl -a vulkan"
)

# ─── Website inventory (compiled with the filamentWebsite toolchain) ────
#   Profile D — website-static (Filament.js / WebGL, 3 mats):
#     "-p mobile -a opengl", matc pinned to `filamentWebsite` (MATERIAL_VERSION
#     70 track — the vendored website-static/js/filament/ build), NOT the
#     Android pin. See #2783.
MATS_WEBSITE=(
    "website-static:lit_colored:website-static/materials/lit_colored.mat:website-static/materials/lit_colored.filamat:-p mobile -a opengl"
    "website-static:transparent_colored:website-static/materials/transparent_colored.mat:website-static/materials/transparent_colored.filamat:-p mobile -a opengl"
    "website-static:unlit_colored:website-static/materials/unlit_colored.mat:website-static/materials/unlit_colored.filamat:-p mobile -a opengl"
)

# ─── Web-runtime inventory (compiled with the filamentWeb toolchain) ────
#   Profile W — sceneview-web (npm filament runtime, WebGL2 only):
#     "-p mobile -a opengl", matc pinned to `filamentWeb` (MATERIAL_VERSION 52
#     track). See splat_web.mat's header for why it is a (float-params) fork of
#     the Android splat.mat (#2646 P2).
MATS_WEB=(
    "sceneview-web:splat_web:sceneview-web/materials/splat_web.mat:sceneview-web/materials/splat_web.filamat:-p mobile -a opengl"
)

# The web blob is additionally embedded in the Kotlin/JS bundle as a generated
# base64 constant (no runtime fetch, works through the single-file CDN
# distribution). Generated + committed + --check-diffed like the blob itself.
WEB_BLOB_FOR_KT="sceneview-web/materials/splat_web.filamat"
WEB_BLOB_KT_OUT="sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/splat/SplatMaterialBlob.kt"

# ─── Resolve matc ───────────────────────────────────────────────────────
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/sceneview"

# Point the download/verify/compile helpers at the matc for <version>.
# The helpers all read these globals, so switching toolchains between the
# Android and web inventory groups is one call.
select_toolchain() {
    FILAMENT_VERSION="$1"
    MATC_DIR="$CACHE_BASE/matc-$FILAMENT_VERSION"
    MATC_BIN="$MATC_DIR/bin/matc"
}

select_toolchain "$FILAMENT_VERSION"

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
# Downloads + version-verifies the CURRENTLY SELECTED toolchain (see
# select_toolchain). Exits 0 under --ci-tolerant when the network is absent.
bootstrap_toolchain() {
    if ! ensure_matc; then
        if [ "$CI_TOLERANT" = "1" ]; then
            warn "matc $FILAMENT_VERSION unavailable; --ci-tolerant set, skipping filamat check."
            exit 0
        fi
        err "matc $FILAMENT_VERSION unavailable — re-run with network access, or pass --ci-tolerant to skip."
        exit 2
    fi

    if ! verify_matc_version; then
        if [ "$CI_TOLERANT" = "1" ]; then
            warn "matc version verification failed; --ci-tolerant set, skipping."
            exit 0
        fi
        exit 2
    fi
}

bootstrap_toolchain

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

# Process one inventory entry with the CURRENTLY SELECTED toolchain.
process_entry() {
    local entry="$1"
    local module name src out extra
    IFS=':' read -r module name src out extra <<< "$entry"

    if [ -n "$ONLY_MAT" ] && [ "$name" != "$ONLY_MAT" ]; then
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi

    local full_src="$ROOT/$src"
    local full_out="$ROOT/$out"

    if [ ! -f "$full_src" ]; then
        err "[$module:$name] source missing: $src"
        MISSING_SRC+=("$module:$name")
        DRIFT=$((DRIFT + 1))
        return 0
    fi

    local tmp_out="$TMP_ROOT/${module}_${name}.filamat"

    if ! compile_mat "$full_src" "$tmp_out" "$extra" >/dev/null 2>&1; then
        err "[$module:$name] matc compilation failed for $src"
        # Re-run loud for the user
        compile_mat "$full_src" "$tmp_out" "$extra" || true
        DRIFTED_MATS+=("$module:$name")
        DRIFT=$((DRIFT + 1))
        return 0
    fi

    if [ "$MODE" = "check" ]; then
        if [ ! -f "$full_out" ]; then
            err "[$module:$name] committed .filamat missing: $out"
            DRIFTED_MATS+=("$module:$name (missing committed blob)")
            DRIFT=$((DRIFT + 1))
            return 0
        fi
        if cmp -s "$full_out" "$tmp_out"; then
            ok "[$module:$name] in sync ($(wc -c < "$tmp_out" | tr -d ' ') bytes)"
            COMPILED=$((COMPILED + 1))
        else
            local committed_size fresh_size
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
}

for entry in "${MATS[@]}"; do
    process_entry "$entry"
done

# ─── Website group (filamentWebsite toolchain) ──────────────────────────
# Same processing, different pinned matc: these blobs must match the
# Filament.js build vendored under website-static/js/filament/, not the
# Android runtime (#2783).
log ""
log "${CYAN}Website materials (matc $FILAMENT_WEBSITE_VERSION):${NC}"
select_toolchain "$FILAMENT_WEBSITE_VERSION"
bootstrap_toolchain
for entry in "${MATS_WEBSITE[@]}"; do
    process_entry "$entry"
done

# ─── Web-runtime group (filamentWeb toolchain) ──────────────────────────
# Same processing, different pinned matc: these blobs must match the npm
# `filament` runtime the Kotlin/JS bundle loads, not the Android runtime.
log ""
log "${CYAN}Web-runtime materials (matc $FILAMENT_WEB_VERSION):${NC}"
select_toolchain "$FILAMENT_WEB_VERSION"
bootstrap_toolchain
for entry in "${MATS_WEB[@]}"; do
    process_entry "$entry"
done

# ─── Generated Kotlin embed of the web blob ─────────────────────────────
# Deterministic emission (no timestamps): base64 of the blob, folded to
# 96-char literal chunks. --check regenerates from the FRESH blob and diffs
# against the committed .kt, so blob and embed can never drift apart.
emit_web_blob_kt() {
    local blob="$1" out_kt="$2"
    local bytes minor
    bytes=$(wc -c < "$blob" | tr -d ' ')
    minor=$(printf '%s' "$FILAMENT_WEB_VERSION" | awk -F. '{print $2}')
    {
        printf '@file:Suppress("MaxLineLength")\n\n'
        printf 'package io.github.sceneview.web.splat\n\n'
        printf '// GENERATED FILE — DO NOT EDIT BY HAND (tools/GenerateFilamat.sh).\n'
        printf '// Base64 of sceneview-web/materials/splat_web.filamat (%s bytes), compiled with\n' "$bytes"
        printf '// matc %s (MATERIAL_VERSION %s) — the npm `filament` runtime pin (`filamentWeb`\n' "$FILAMENT_WEB_VERSION" "$minor"
        printf '// in gradle/libs.versions.toml). Regenerate: bash tools/GenerateFilamat.sh\n'
        printf '// (drift is caught by --check in the quality gate). See #2646 P2.\n\n'
        printf '/** The splat_web.filamat material package, embedded so the bundle needs no fetch. */\n'
        printf 'internal const val SPLAT_WEB_MATERIAL_BASE64: String =\n'
        base64 < "$blob" | tr -d '\n' | fold -w 96 | sed -e 's/^/    "/' -e 's/$/" +/' -e '$ s/ +$//'
        printf '\n'
    } > "$out_kt"
}

process_web_blob_kt() {
    # Under --mat filtering, only run when the web blob's own entry ran.
    if [ -n "$ONLY_MAT" ] && [ "$ONLY_MAT" != "splat_web" ]; then
        return 0
    fi
    local fresh_blob="$TMP_ROOT/sceneview-web_splat_web.filamat"
    local committed_blob="$ROOT/$WEB_BLOB_FOR_KT"
    local full_kt="$ROOT/$WEB_BLOB_KT_OUT"
    # In generate mode the tmp blob was moved into place; use the committed one.
    local blob="$fresh_blob"
    [ -f "$blob" ] || blob="$committed_blob"
    if [ ! -f "$blob" ]; then
        err "[sceneview-web:splat_web.kt] no blob to embed (compile failed above?)"
        DRIFT=$((DRIFT + 1))
        return 0
    fi
    local tmp_kt="$TMP_ROOT/SplatMaterialBlob.kt"
    emit_web_blob_kt "$blob" "$tmp_kt"
    if [ "$MODE" = "check" ]; then
        if [ ! -f "$full_kt" ]; then
            err "[sceneview-web:splat_web.kt] committed embed missing: $WEB_BLOB_KT_OUT"
            DRIFTED_MATS+=("sceneview-web:splat_web.kt (missing committed embed)")
            DRIFT=$((DRIFT + 1))
            return 0
        fi
        if cmp -s "$full_kt" "$tmp_kt"; then
            ok "[sceneview-web:splat_web.kt] embed in sync"
        else
            err "[sceneview-web:splat_web.kt] embed DRIFTED from the blob"
            DRIFTED_MATS+=("sceneview-web:splat_web.kt")
            DRIFT=$((DRIFT + 1))
        fi
    else
        mkdir -p "$(dirname "$full_kt")"
        if [ -f "$full_kt" ] && cmp -s "$full_kt" "$tmp_kt"; then
            ok "[sceneview-web:splat_web.kt] embed unchanged"
        else
            mv "$tmp_kt" "$full_kt"
            ok "[sceneview-web:splat_web.kt] embed regenerated → $WEB_BLOB_KT_OUT"
        fi
    fi
}
process_web_blob_kt

log ""

# ─── Final report ───────────────────────────────────────────────────────
if [ "$MODE" = "check" ]; then
    if [ "$DRIFT" -eq 0 ]; then
        log "${GREEN}All $COMPILED filamat blob(s) are in sync with their .mat sources (matc android=$FILAMENT_ANDROID_VERSION, website=$FILAMENT_WEBSITE_VERSION, web=$FILAMENT_WEB_VERSION).${NC}"
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
        log "${GREEN}Regenerated $COMPILED filamat blob(s) (matc android=$FILAMENT_ANDROID_VERSION, website=$FILAMENT_WEBSITE_VERSION, web=$FILAMENT_WEB_VERSION).${NC}"
    fi
    exit 0
fi
