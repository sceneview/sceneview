#!/usr/bin/env bash
#
# check-ios-floor.sh — drift gate for the Apple platform floor. Closes #3046.
#
# WHY THIS EXISTS
# ---------------
# `SceneViewSwift/Package.swift` is the only place the Apple deployment floor
# is *enforced* (the compiler refuses a lower target). Every other statement of
# it — the three CocoaPods podspecs, the root `Package.swift`, README badges,
# the docs site, llms.txt, the MCP guides, the demo's About screen — is prose
# that nothing compared to the manifest. The floor moved from iOS 17 to iOS 18
# in #719 and eight surfaces kept saying 17 for months; `sync-versions.sh`
# syncs the podspecs' *version* and their SceneViewSwift dependency floor, never
# the *platform* floor, so it could not catch this.
#
# WHAT IT CHECKS (all hard-fail, no network, no toolchain)
# --------------------------------------------------------
#   1. Manifests: the root `Package.swift` declares the same iOS / macOS /
#      visionOS floors as `SceneViewSwift/Package.swift`.
#   2. Podspecs: every `s.platform = :ios, 'X'` / `s.platforms = { :ios => "X" }`
#      equals the iOS floor.
#   3. Docs: in a fixed list of user-facing files, every "iOS N+", "macOS N+"
#      and "visionOS N+" *floor claim* names the floor's major version. The
#      list is explicit so feature-level availability notes in code comments
#      (`#available(iOS 17.0, *)`, "iOS 15+ only") and historical changelogs
#      are never read as floor claims.
#
# Usage:  bash .claude/scripts/check-ios-floor.sh        (exit 0 = aligned)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
ERRORS=0
fail() { printf "${RED}FAIL${NC} %s\n" "$1"; ERRORS=$((ERRORS + 1)); }

# ─── 1. Source of truth ─────────────────────────────────────────────────────
MANIFEST="SceneViewSwift/Package.swift"
floor_of() {
    # .iOS("18.0") → 18.0 ; .visionOS(.v2) → 2 ; .macOS(.v10_15) → 10.15
    local os="$1" file="$ROOT/$2" v
    v=$(grep -oE "\.${os}\(\"[0-9]+(\.[0-9]+)*\"\)" "$file" | head -1 | grep -oE '[0-9]+(\.[0-9]+)*' || true)
    if [ -z "$v" ]; then
        v=$(grep -oE "\.${os}\(\.v[0-9]+(_[0-9]+)?\)" "$file" | head -1 | grep -oE 'v[0-9]+(_[0-9]+)?' | tr -d 'v' | tr '_' '.' || true)
    fi
    printf '%s' "$v"
}
IOS=$(floor_of iOS "$MANIFEST");         IOS_MAJOR=${IOS%%.*}
MACOS=$(floor_of macOS "$MANIFEST");     MACOS_MAJOR=${MACOS%%.*}
VISION=$(floor_of visionOS "$MANIFEST"); VISION_MAJOR=${VISION%%.*}
for pair in "iOS:$IOS" "macOS:$MACOS" "visionOS:$VISION"; do
    if [ -z "${pair#*:}" ]; then
        printf "${RED}FATAL${NC} cannot read .%s(...) floor from %s\n" "${pair%%:*}" "$MANIFEST"
        exit 2
    fi
done
echo "Apple floor per $MANIFEST: iOS $IOS / macOS $MACOS / visionOS $VISION"

# ─── 2. Root Package.swift ──────────────────────────────────────────────────
# Only the major is compared: the sub-manifest spells `.visionOS("2.0")` while
# the root uses the `.v2` enum, and both mean the same floor.
for os in iOS macOS visionOS; do
    want=$(floor_of "$os" "$MANIFEST"); got=$(floor_of "$os" Package.swift)
    if [ "${got%%.*}" != "${want%%.*}" ]; then
        fail "Package.swift declares .$os(${got:-?}) but $MANIFEST says $want"
    fi
done

# ─── 3. Podspecs ────────────────────────────────────────────────────────────
for spec in SceneViewSwift.podspec \
            react-native/react-native-sceneview/react-native-sceneview.podspec \
            flutter/sceneview_flutter/ios/flutter_sceneview.podspec; do
    if [ ! -f "$ROOT/$spec" ]; then
        fail "$spec is missing (listed in the podspec table of this script)"
        continue
    fi
    got=$(grep -oE ":ios[[:space:]]*(,[[:space:]]*|=>[[:space:]]*)['\"][0-9]+(\.[0-9]+)*['\"]" "$ROOT/$spec" | grep -oE "[0-9]+(\.[0-9]+)*" | head -1 || true)
    if [ -z "$got" ]; then
        fail "$spec has no ':ios' platform line"
    elif [ "$got" != "$IOS" ]; then
        fail "$spec pins iOS $got but $MANIFEST says $IOS"
    fi
done

# ─── 4. Prose floor claims ──────────────────────────────────────────────────
# Only files whose "<OS> N+" is a *floor* statement. Add a file here when it
# starts stating the minimum; never widen this to a repo-wide grep (code
# comments and the changelog legitimately name older per-feature versions).
DOC_FILES=(
    README.md
    SceneViewSwift/README.md
    CONTRIBUTING.md
    llms.txt
    website-static/.well-known/llms.txt
    website-static/docs.html
    docs/docs/platforms.md
    docs/docs/quickstart-ios.md
    docs/docs/samples-ios.md
    docs/docs/structured-data.json
    .github/copilot-instructions.md
    mcp/src/guides.ts
    mcp/src/platform-setup.ts
    mcp/src/tools/handler.ts
    SceneViewSwift/Examples/SceneViewDemo/SceneViewDemo/Views/AboutView.swift
)
# Per-feature availability notes that are NOT floor claims — keep this list
# short and justified. Matched as fixed substrings of the offending line.
ALLOW=(
    "Safari iOS"            # WebXR browser support matrix in llms.txt
    "iOS 15+"               # ARKit per-feature notes (scene reconstruction, etc.)
)
CLAIM_RE="(iOS|macOS|visionOS) \(?[0-9]+(\.[0-9]+)?\+"
for f in "${DOC_FILES[@]}"; do
    if [ ! -f "$ROOT/$f" ]; then
        fail "$f is missing (listed in DOC_FILES of this script)"
        continue
    fi
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        line=${hit%%:*}; rest=${hit#*:}
        skip=0
        for a in "${ALLOW[@]}"; do
            case "$rest" in *"$a"*) skip=1 ;; esac
        done
        [ "$skip" = 1 ] && continue
        for claim in $(printf '%s' "$rest" | grep -oE "$CLAIM_RE" | tr ' ' '_'); do
            os=${claim%%_*}; num=${claim#*_}; num=${num#\(}; num=${num%+}; major=${num%%.*}
            case "$os" in
                iOS)      want=$IOS_MAJOR ;;
                macOS)    want=$MACOS_MAJOR ;;
                visionOS) want=$VISION_MAJOR ;;
            esac
            if [ "$major" != "$want" ]; then
                fail "$f:$line says '$(printf '%s' "$claim" | tr '_' ' ')' but the floor is $os $want+"
            fi
        done
    done <<EOT
$(grep -nE "$CLAIM_RE" "$ROOT/$f" || true)
EOT
done

if [ "$ERRORS" -eq 0 ]; then
    printf "${GREEN}Apple platform floor is consistent${NC} (iOS %s / macOS %s / visionOS %s across manifests, podspecs and docs)\n" "$IOS" "$MACOS" "$VISION"
    exit 0
fi
printf "${RED}%d drift(s)${NC} — the floor is declared once, in %s; fix the listed files to match it.\n" "$ERRORS" "$MANIFEST"
exit 1
