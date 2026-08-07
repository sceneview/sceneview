#!/usr/bin/env bash
# sync-assets.sh — Distribute shared assets to all platform demo apps
#
# Usage:
#   bash .claude/scripts/sync-assets.sh           # Check what needs syncing
#   bash .claude/scripts/sync-assets.sh --fix      # Copy assets to all platforms
#   bash .claude/scripts/sync-assets.sh --discover  # Search for new free assets online
#
# The shared assets directory (assets/) is the source of truth.
# Platform-specific copies are derived from it.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ASSETS_DIR="$REPO_ROOT/assets"
CATALOG="$ASSETS_DIR/catalog.json"

# Platform asset directories
ANDROID_MODELS="$REPO_ROOT/samples/android-demo/src/main/assets/models"
ANDROID_ENVS="$REPO_ROOT/samples/android-demo/src/main/assets/environments"
ANDROID_TV_MODELS="$REPO_ROOT/samples/android-tv-demo/src/main/assets/models"
ANDROID_TV_ENVS="$REPO_ROOT/samples/android-tv-demo/src/main/assets/environments"
IOS_MODELS="$REPO_ROOT/samples/ios-demo/SceneViewDemo/Models"
WEB_MODELS="$REPO_ROOT/samples/web-demo/public/models"
# These three pointed at `samples/flutter-demo/example/...` — a directory that
# does not exist. The demo's real tree is samples/flutter-demo/{android,ios,lib},
# so every Flutter leg of this sync silently addressed nothing, and the demo
# never received the USDZ its catalog entries already claimed it used.
FLUTTER_ANDROID_MODELS="$REPO_ROOT/samples/flutter-demo/android/app/src/main/assets/models"
# No FLUTTER_ANDROID_ENVS: it was declared here and used by nothing, and fixing
# its path would only have made a dead variable point somewhere better. The
# Flutter demo loads environments through Flutter's own asset bundle — the
# `assets: - environments/` entry in its pubspec.yaml, resolved from the package
# root — not through Android's native assets directory, so there is no Android
# environments leg for this demo to sync.
FLUTTER_IOS_MODELS="$REPO_ROOT/samples/flutter-demo/ios/Runner/Models"
RN_ANDROID_MODELS="$REPO_ROOT/samples/react-native-demo/android/app/src/main/assets/models"
RN_ANDROID_ENVS="$REPO_ROOT/samples/react-native-demo/android/app/src/main/assets/environments"
RN_IOS_MODELS="$REPO_ROOT/samples/react-native-demo/ios/Models"
WEBSITE_MODELS="$REPO_ROOT/website-static/models/platforms"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

errors=0
synced=0
missing=0

# verify_divergences() tallies inside a `while` fed by a pipe, i.e. a subshell,
# so it cannot increment `errors` directly — it appends a line here instead.
DIVERGENCE_LOG="$(mktemp)"
trap 'rm -f "$DIVERGENCE_LOG"' EXIT

# --- Intentional divergences (a platform copy that must NOT be overwritten) ---
#
# assets/ is the source of truth for every platform copy, with a short list of
# exceptions: assets re-authored for one runtime's importer. Copying the shared
# original back over them re-introduces the very bug the re-authoring fixed, so
# the sync skips them — and pins the expected checksum of the derived copy, so
# "skipped" can never quietly decay into "unchecked".
#
# Format, one per line: <path relative to repo root>|<sha256 of the copy>|<why>
DIVERGENT_COPIES="samples/ios-demo/SceneViewDemo/Models/tree_scene.usdz|e6a359d561073052668c2bae9fdbf607fa60ac0f2d676a56b52e98edb8ad2352|#2928 — 2665 sub-pixel grass prims stripped; the shared original needs 91.7 s to import in RealityKit, so the demo slot renders as absent"

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

# Echoes "<sha>|<why>" when $1 (a repo-relative path) is a pinned divergence.
divergence_for() {
    local rel="$1"
    printf '%s\n' "$DIVERGENT_COPIES" | while IFS='|' read -r path sha why; do
        [ -n "$path" ] && [ "$path" = "$rel" ] && printf '%s|%s\n' "$sha" "$why"
    done
    return 0
}

# Verify every pinned divergence. Runs unconditionally — a fresh clone has no
# assets/models/ at all, and that is precisely when a silent revert would go
# unnoticed the longest.
verify_divergences() {
    echo -e "${BLUE}[Divergences] platform copies deliberately not synced${NC}"
    printf '%s\n' "$DIVERGENT_COPIES" | while IFS='|' read -r rel want why; do
        [ -n "$rel" ] || continue
        local dst="$REPO_ROOT/$rel"
        if [ ! -f "$dst" ]; then
            echo -e "  ${RED}ABSENT${NC} $rel — pinned divergence is missing; restore it from git"
            echo "DIVERGENCE_ERROR" >> "$DIVERGENCE_LOG"
            continue
        fi
        local got
        got="$(sha256_of "$dst")"
        if [ "$got" = "$want" ]; then
            echo -e "  ${GREEN}PINNED${NC} $rel ($why)"
        else
            echo -e "  ${RED}DRIFT${NC} $rel no longer matches its pinned checksum"
            echo -e "         expected $want"
            echo -e "         actual   $got"
            echo -e "         reason for divergence: $why"
            echo -e "         If the change is deliberate, update DIVERGENT_COPIES in this script."
            echo "DIVERGENCE_ERROR" >> "$DIVERGENCE_LOG"
        fi
    done
    if [ -s "$DIVERGENCE_LOG" ]; then
        errors=$((errors + $(wc -l < "$DIVERGENCE_LOG" | tr -d ' ')))
    fi
    echo ""
}

check_or_fix() {
    local src="$1"
    local dst="$2"
    local mode="${3:-check}"

    if [ ! -f "$src" ]; then
        return 0  # Source doesn't exist, skip
    fi

    # Never let the shared original overwrite a deliberately re-authored copy.
    # verify_divergences() is what keeps this skip honest.
    if [ -n "$(divergence_for "${dst#"$REPO_ROOT"/}")" ]; then
        return 0
    fi

    if [ ! -f "$dst" ]; then
        missing=$((missing + 1))
        if [ "$mode" = "fix" ]; then
            mkdir -p "$(dirname "$dst")"
            cp "$src" "$dst"
            echo -e "  ${GREEN}COPIED${NC} $(basename "$src") → $(echo "$dst" | sed "s|$REPO_ROOT/||")"
            synced=$((synced + 1))
        else
            echo -e "  ${YELLOW}MISSING${NC} $(echo "$dst" | sed "s|$REPO_ROOT/||")"
        fi
    else
        # Check if files differ
        if ! cmp -s "$src" "$dst"; then
            missing=$((missing + 1))
            if [ "$mode" = "fix" ]; then
                cp "$src" "$dst"
                echo -e "  ${GREEN}UPDATED${NC} $(basename "$src") → $(echo "$dst" | sed "s|$REPO_ROOT/||")"
                synced=$((synced + 1))
            else
                echo -e "  ${YELLOW}OUTDATED${NC} $(echo "$dst" | sed "s|$REPO_ROOT/||")"
            fi
        fi
    fi
}

# Refreshes only the assets a platform ALREADY bundles, instead of pushing the
# whole shared library at it.
#
# The Flutter demo bundles exactly one model, wired into the Runner target's
# Resources build phase by hand; copying all ~90 shared models into its tree
# would commit tens of MB that no build ever reads. The full-fanout legs above
# are right for the demos that really do ship the whole catalogue (android-demo,
# ios-demo, web) — this one keeps a curated platform honest without growing it.
refresh_existing() {
    local src_dir="$1"
    local dst_dir="$2"
    local ext="$3"
    local mode="$4"

    [ -d "$dst_dir" ] || return 0
    shopt -s nullglob
    local found=0
    for dst in "$dst_dir"/*."$ext"; do
        found=1
        check_or_fix "$src_dir/$(basename "$dst")" "$dst" "$mode"
    done
    shopt -u nullglob
    [ "$found" -eq 1 ] || echo -e "  (no .$ext bundled yet — nothing to refresh)"
}

echo ""
echo -e "${BLUE}═══ SceneView Asset Sync ═══${NC}"
echo ""

MODE="check"
if [ "${1:-}" = "--fix" ]; then
    MODE="fix"
    echo -e "${BLUE}Mode: FIX (copying assets to platforms)${NC}"
elif [ "${1:-}" = "--discover" ]; then
    MODE="discover"
    echo -e "${BLUE}Mode: DISCOVER (searching for new assets)${NC}"
else
    echo -e "${BLUE}Mode: CHECK (dry run — use --fix to sync)${NC}"
fi
echo ""

if [ "$MODE" = "discover" ]; then
    echo -e "${BLUE}Searching free asset sources...${NC}"
    echo ""

    # Check Poly Haven for new HDR environments
    echo -e "${BLUE}[Poly Haven] Checking HDR environments...${NC}"
    if command -v curl &>/dev/null; then
        HDRIS=$(curl -s "https://api.polyhaven.com/assets?t=hdris&categories=studio,outdoor,urban" 2>/dev/null | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    for name in sorted(data.keys())[:20]:
        info = data[name]
        cats = ','.join(info.get('categories', []))
        print(f'  {name} [{cats}]')
except:
    print('  (failed to parse)')
" 2>/dev/null || echo "  (API unavailable)")
        echo "$HDRIS"
    fi
    echo ""

    # Check Sketchfab for popular free downloadable models
    echo -e "${BLUE}[Sketchfab] Popular free downloadable models...${NC}"
    if command -v curl &>/dev/null; then
        curl -s "https://api.sketchfab.com/v3/search?type=models&downloadable=true&sort_by=-likeCount&count=10" 2>/dev/null | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    for r in data.get('results', []):
        name = r.get('name', '?')
        likes = r.get('likeCount', 0)
        faces = r.get('faceCount', 0)
        uid = r.get('uid', '')
        print(f'  {name} ({likes} likes, {faces:,} faces)')
        print(f'    https://sketchfab.com/3d-models/{uid}')
except:
    print('  (failed to parse)')
" 2>/dev/null || echo "  (API unavailable)"
    fi
    echo ""
    exit 0
fi

verify_divergences

# --- Sync GLB models to Android ---
echo -e "${BLUE}[Android] GLB models → $( echo "$ANDROID_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for glb in "$ASSETS_DIR"/models/glb/*.glb; do
    [ -f "$glb" ] || continue
    check_or_fix "$glb" "$ANDROID_MODELS/$(basename "$glb")" "$MODE"
done

# --- Sync GLB models to Android TV ---
echo -e "${BLUE}[Android TV] GLB models → $( echo "$ANDROID_TV_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for glb in "$ASSETS_DIR"/models/glb/*.glb; do
    [ -f "$glb" ] || continue
    check_or_fix "$glb" "$ANDROID_TV_MODELS/$(basename "$glb")" "$MODE"
done

# --- Sync USDZ models to iOS ---
echo -e "${BLUE}[iOS] USDZ models → $( echo "$IOS_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for usdz in "$ASSETS_DIR"/models/usdz/*.usdz; do
    [ -f "$usdz" ] || continue
    check_or_fix "$usdz" "$IOS_MODELS/$(basename "$usdz")" "$MODE"
done

# --- Sync GLB models to Web ---
echo -e "${BLUE}[Web] GLB models → $( echo "$WEB_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for glb in "$ASSETS_DIR"/models/glb/*.glb; do
    [ -f "$glb" ] || continue
    check_or_fix "$glb" "$WEB_MODELS/$(basename "$glb")" "$MODE"
done

# --- Refresh the GLB models Flutter/Android already bundles ---
echo -e "${BLUE}[Flutter/Android] GLB models → $( echo "$FLUTTER_ANDROID_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
refresh_existing "$ASSETS_DIR/models/glb" "$FLUTTER_ANDROID_MODELS" "glb" "$MODE"

# --- Refresh the USDZ models Flutter/iOS already bundles ---
echo -e "${BLUE}[Flutter/iOS] USDZ models → $( echo "$FLUTTER_IOS_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
refresh_existing "$ASSETS_DIR/models/usdz" "$FLUTTER_IOS_MODELS" "usdz" "$MODE"

# --- Sync GLB models to React Native (Android side) ---
echo -e "${BLUE}[RN/Android] GLB models → $( echo "$RN_ANDROID_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for glb in "$ASSETS_DIR"/models/glb/*.glb; do
    [ -f "$glb" ] || continue
    check_or_fix "$glb" "$RN_ANDROID_MODELS/$(basename "$glb")" "$MODE"
done

# --- Sync USDZ models to React Native (iOS side) ---
echo -e "${BLUE}[RN/iOS] USDZ models → $( echo "$RN_IOS_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for usdz in "$ASSETS_DIR"/models/usdz/*.usdz; do
    [ -f "$usdz" ] || continue
    check_or_fix "$usdz" "$RN_IOS_MODELS/$(basename "$usdz")" "$MODE"
done

# --- Sync GLB models to Website ---
echo -e "${BLUE}[Website] GLB models → $( echo "$WEBSITE_MODELS" | sed "s|$REPO_ROOT/||" )${NC}"
for glb in "$ASSETS_DIR"/models/glb/*.glb; do
    [ -f "$glb" ] || continue
    check_or_fix "$glb" "$WEBSITE_MODELS/$(basename "$glb")" "$MODE"
done

# --- Summary ---
echo ""
if [ "$MODE" = "fix" ]; then
    echo -e "${GREEN}Synced $synced assets across platforms.${NC}"
else
    if [ "$missing" -eq 0 ]; then
        echo -e "${GREEN}All assets are in sync across platforms.${NC}"
    else
        echo -e "${YELLOW}$missing assets need syncing. Run with --fix to update.${NC}"
    fi
fi

# --- Catalog stats ---
echo ""
if [ -f "$CATALOG" ] && command -v python3 &>/dev/null; then
    python3 -c "
import json
with open('$CATALOG') as f:
    cat = json.load(f)
models = len(cat.get('models', []))
envs = len(cat.get('environments', []))
pending = len(cat.get('pendingReview', []))
print(f'Catalog: {models} models, {envs} environments, {pending} pending review')
" 2>/dev/null
fi

# A pinned divergence that drifted is a real failure — a platform copy was
# overwritten or re-authored without updating DIVERGENT_COPIES. Ordinary
# out-of-sync assets are not a failure: that is what --fix is for.
if [ "$errors" -gt 0 ]; then
    echo ""
    echo -e "${RED}$errors pinned divergence(s) failed verification.${NC}"
    exit 1
fi
