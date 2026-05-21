#!/usr/bin/env bash
# validate-demo-assets.sh — Detect broken asset references in demo apps
#
# Usage:
#   bash .claude/scripts/validate-demo-assets.sh              # All platforms
#   bash .claude/scripts/validate-demo-assets.sh --android    # Android only
#   bash .claude/scripts/validate-demo-assets.sh --ios        # iOS only
#   bash .claude/scripts/validate-demo-assets.sh --no-cdn     # Skip HTTP checks
#   bash .claude/scripts/validate-demo-assets.sh --strict     # Fail on first error
#
# What it does:
#   1. Scans demo source code for model/env/texture references
#   2. For each reference, verifies the bundled file exists on disk
#   3. For each CDN URL, sends a HEAD request to confirm it returns 200
#   4. Reports MISSING bundled files and BROKEN CDN URLs
#
# Exit codes:
#   0  all references resolve
#   1  at least one broken reference found
#   2  invalid arguments

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
GRAY='\033[0;90m'
NC='\033[0m'

# Args
platforms="all"
check_cdn=true
strict=false
while [ $# -gt 0 ]; do
    case "$1" in
        --android) platforms="android" ;;
        --ios) platforms="ios" ;;
        --web) platforms="web" ;;
        --tv) platforms="tv" ;;
        --flutter) platforms="flutter" ;;
        --rn) platforms="rn" ;;
        --no-cdn) check_cdn=false ;;
        --strict) strict=true ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown argument: $1${NC}" >&2
            exit 2
            ;;
    esac
    shift
done

total_bundled=0
total_cdn=0
missing_bundled=0
broken_cdn=0
broken_refs_list=""

append_broken() {
    broken_refs_list="${broken_refs_list}$1"$'\n'
    if [ "$strict" = true ]; then
        echo -e "${RED}Strict mode: stopping on first error${NC}"
        exit 1
    fi
}

# ---- CDN cache (avoid re-checking same URL) ----
# Uses a flat temp file: "<url><TAB>OK|FAIL(code)"
CDN_CACHE_FILE="$(mktemp -t validate-demo-assets.XXXXXX)"
trap 'rm -f "$CDN_CACHE_FILE"' EXIT

cdn_cache_get() {
    grep -F "$1"$'\t' "$CDN_CACHE_FILE" 2>/dev/null | head -1 | awk -F'\t' '{print $2}'
}
cdn_cache_set() {
    printf "%s\t%s\n" "$1" "$2" >> "$CDN_CACHE_FILE"
}

LAST_CDN_ERR=""

check_cdn_url() {
    local url="$1"
    if [ "$check_cdn" != true ]; then
        return 0
    fi
    local cached
    cached=$(cdn_cache_get "$url")
    if [ -n "$cached" ]; then
        if [ "$cached" = "OK" ]; then
            return 0
        else
            LAST_CDN_ERR="$cached"
            return 1
        fi
    fi
    local code
    # -L follows redirects (GitHub releases return 302 → S3/ObjectStore).
    # -I does HEAD. Some CDNs reject HEAD → fall back to ranged GET.
    code=$(curl -s -L -o /dev/null -w "%{http_code}" --max-time 15 -I "$url" 2>/dev/null || echo "000")
    if [ "$code" != "200" ] && [ "$code" != "206" ]; then
        code=$(curl -s -L -o /dev/null -w "%{http_code}" --max-time 15 -r 0-0 "$url" 2>/dev/null || echo "000")
    fi
    if [ "$code" = "200" ] || [ "$code" = "206" ]; then
        cdn_cache_set "$url" "OK"
        return 0
    else
        cdn_cache_set "$url" "FAIL($code)"
        LAST_CDN_ERR="FAIL($code)"
        return 1
    fi
}

# ---- Extract references from source files ----
# Picks up: "models/foo.glb", "foo.usdz", "environments/bar.hdr", "$CDN/baz.glb"
# Outputs one per line: <path-or-url>|<source-file>:<line>

extract_refs() {
    local glob="$1"
    local ext_pattern="$2"    # e.g. glb|gltf|usdz|hdr
    local path_prefix="$3"    # unused kept for signature

    # For each source file, grep for any string literal containing a known extension.
    # grep -oE gives us just the matching quoted token.
    find $glob -type f 2>/dev/null | while IFS= read -r file; do
        case "$file" in
            *.kt|*.java|*.swift|*.ts|*.tsx|*.js|*.jsx|*.dart|*.mm|*.m|*.html|*.json)
                ;;
            *)
                continue
                ;;
        esac
        # Skip Swift test files — they reference intentionally-missing assets
        # ("Models/missing.usdz", "valid-\(UUID().uuidString).usdz") to exercise
        # the not-found path of SketchfabAssetResolver.
        case "$file" in
            *Tests.swift|*+Tests.swift|*Test.swift) continue ;;
        esac
        # 1. Quoted literals with a known extension ("models/foo.glb", "bar.hdr")
        # Skip strings containing `$`, `\` (Swift `\(slug.uid).usdz`
        # interpolation), or `<` (placeholder docs like `Models/<name>.usdz`) —
        # those are runtime/template references, not static asset paths.
        # `|| true` so files with no match (grep exit 1) don't abort pipefail.
        grep -oE "\"[^\"\$\\\\<]*\.($ext_pattern)\"" "$file" 2>/dev/null |
            awk -v f="$file" '{ gsub(/"/, "", $0); printf "%s|%s\n", $0, f }' || true

        # 2. iOS Swift pattern — `asset: "name"` without extension. We emit the
        #    name with an implicit .usdz suffix so check_bundled_ref can find
        #    it in Models/. Only applied to .swift files to avoid false matches.
        if [[ "$file" == *.swift ]]; then
            grep -oE 'asset:[[:space:]]*"[^"]+"' "$file" 2>/dev/null |
                awk -v f="$file" '{
                    # pull the quoted name
                    match($0, /"[^"]+"/);
                    name = substr($0, RSTART+1, RLENGTH-2);
                    printf "%s.usdz|%s\n", name, f;
                }' || true
            # Also catch `ModelNode.load("name")`
            grep -oE 'ModelNode\.load\([[:space:]]*"[^"]+"' "$file" 2>/dev/null |
                awk -v f="$file" '{
                    match($0, /"[^"]+"/);
                    name = substr($0, RSTART+1, RLENGTH-2);
                    printf "%s.usdz|%s\n", name, f;
                }' || true
        fi
    done
}

# Expand known build-time constants.
# The android-demo, ios-demo, tv-demo all use the same CDN:
#   const val CDN = "https://github.com/sceneview/sceneview/releases/download/assets-v1"
# Uses sed because bash ${var/pat/repl} breaks when replacement contains '/'.
CDN_BASE="https://github.com/sceneview/sceneview/releases/download/assets-v1"
# sceneview-web Main.kt uses an absolute https://sceneview.github.io/assets/... prefix
# which we leave as-is (no substitution needed).
expand_cdn() {
    printf "%s" "$1" | sed \
        -e "s|[\$]CDN/|${CDN_BASE}/|g" \
        -e "s|[\$]{CDN}/|${CDN_BASE}/|g"
}

check_bundled_ref() {
    local ref="$1"
    local source="$2"
    local bundle_roots="$3"  # one or more roots separated by ':' — e.g. android-tv-demo merges assets from android-demo via sourceSets

    total_bundled=$((total_bundled + 1))
    local IFS=':'
    for root in $bundle_roots; do
        local candidates=(
            "$root/$ref"
            "$root/models/$ref"
            "$root/environments/$ref"
            "$root/$(basename "$ref")"
        )
        for c in "${candidates[@]}"; do
            if [ -f "$c" ]; then
                return 0
            fi
        done
    done
    missing_bundled=$((missing_bundled + 1))
    local rel_source="${source#$REPO_ROOT/}"
    append_broken "  ${RED}MISS${NC} $ref  ${GRAY}($rel_source)${NC}"
    return 1
}

check_url_ref() {
    local url="$1"
    local source="$2"
    total_cdn=$((total_cdn + 1))
    if check_cdn_url "$url"; then
        return 0
    fi
    broken_cdn=$((broken_cdn + 1))
    local rel_source="${source#$REPO_ROOT/}"
    append_broken "  ${RED}DEAD${NC} $url  ${GRAY}($rel_source) [${LAST_CDN_ERR}]${NC}"
    return 1
}

process_platform_refs() {
    local platform="$1"
    local src_glob="$2"
    local bundle_root="$3"
    local extensions="$4"

    echo -e "${BLUE}== $platform ==${NC}"
    local before_missing=$missing_bundled
    local before_broken=$broken_cdn

    # Use process substitution to keep counters in current shell
    while IFS='|' read -r ref source; do
        [ -z "$ref" ] && continue
        # Strip any $CDN/ prefix expansion
        local expanded
        expanded=$(expand_cdn "$ref")
        if [[ "$expanded" == http* ]]; then
            check_url_ref "$expanded" "$source" || true
        elif [[ "$ref" == http* ]]; then
            check_url_ref "$ref" "$source" || true
        else
            # Bundled ref — strip leading "models/" or "environments/" since bundle_root points to assets/ root
            local clean_ref="$ref"
            clean_ref="${clean_ref#models/}"
            clean_ref="${clean_ref#environments/}"
            check_bundled_ref "$clean_ref" "$source" "$bundle_root" || true
        fi
    done < <(extract_refs "$src_glob" "$extensions" "")

    local this_missing=$((missing_bundled - before_missing))
    local this_broken=$((broken_cdn - before_broken))
    if [ $this_missing -eq 0 ] && [ $this_broken -eq 0 ]; then
        echo -e "  ${GREEN}OK${NC}  ($total_bundled bundled, $total_cdn CDN checked so far)"
    fi
}

# ---------- Per-platform ----------

if [ "$platforms" = "all" ] || [ "$platforms" = "android" ]; then
    process_platform_refs \
        "android-demo" \
        "samples/android-demo/src/main/java" \
        "samples/android-demo/src/main/assets" \
        "glb|gltf|hdr|jpg|png"
fi

if [ "$platforms" = "all" ] || [ "$platforms" = "tv" ]; then
    # TV demo merges its own assets/ folder with android-demo's via build.gradle:
    # `sourceSets.main.assets.srcDirs += '../android-demo/src/main/assets'`
    process_platform_refs \
        "android-tv-demo" \
        "samples/android-tv-demo/src/main/java" \
        "samples/android-tv-demo/src/main/assets:samples/android-demo/src/main/assets" \
        "glb|gltf|hdr"
fi

if [ "$platforms" = "all" ] || [ "$platforms" = "ios" ]; then
    process_platform_refs \
        "ios-demo" \
        "samples/ios-demo/SceneViewDemo" \
        "samples/ios-demo/SceneViewDemo/Models" \
        "usdz|reality|hdr"
fi

if [ "$platforms" = "all" ] || [ "$platforms" = "web" ]; then
    process_platform_refs \
        "web-demo" \
        "samples/web-demo" \
        "samples/web-demo/public" \
        "glb|gltf|hdr"
fi

if [ "$platforms" = "all" ] || [ "$platforms" = "flutter" ]; then
    process_platform_refs \
        "flutter-demo" \
        "samples/flutter-demo/lib" \
        "samples/flutter-demo/environments" \
        "glb|gltf|usdz|hdr"
fi

if [ "$platforms" = "all" ] || [ "$platforms" = "rn" ]; then
    process_platform_refs \
        "react-native-demo" \
        "samples/react-native-demo/src" \
        "samples/react-native-demo/assets" \
        "glb|gltf|usdz|hdr"
fi

# ---------- Report ----------

echo
echo -e "${BLUE}== Summary ==${NC}"
echo -e "  Bundled refs checked : ${total_bundled}"
echo -e "  CDN refs checked     : ${total_cdn}"
if [ $missing_bundled -eq 0 ] && [ $broken_cdn -eq 0 ]; then
    echo -e "  ${GREEN}All references resolve ✓${NC}"
    exit 0
else
    echo -e "  ${RED}Missing bundled: ${missing_bundled}${NC}"
    echo -e "  ${RED}Broken CDN    : ${broken_cdn}${NC}"
    echo
    echo -e "${YELLOW}Broken references:${NC}"
    # %b interprets the \033 color escapes embedded in the list
    printf "%b\n" "$broken_refs_list"
    exit 1
fi
