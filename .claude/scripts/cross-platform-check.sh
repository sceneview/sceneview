#!/usr/bin/env bash
# cross-platform-check.sh — Compare Android vs iOS vs Web API surface, report gaps
#
# Extracts REAL public API from source files on each platform and compares them.
#
# With --with-apk, additionally builds (or reuses) the android-demo debug APK and
# inspects its manifest (via the `aapt2`-backed android_cli_describe helper) to
# verify the exposed activities / intent filters match expectations, and
# cross-checks the demo set declared in DemoRegistry against the iOS SamplesTab
# inventory (drift = a platform is missing a demo).
#
# Usage:
#   ./cross-platform-check.sh             # fast source-only check (default)
#   ./cross-platform-check.sh --with-apk  # also build + inspect the APK manifest

set -eo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ─── Args ───────────────────────────────────────────────────────────────
WITH_APK=0
for arg in "$@"; do
    case "$arg" in
        --with-apk) WITH_APK=1 ;;
        -h|--help)
            grep '^#' "$0" | sed -n '2,14p' | sed 's/^#//; s/^ //'
            exit 0 ;;
        *) echo "Unknown argument: $arg (try --help)" >&2; exit 2 ;;
    esac
done

# `grep -c` prints its count AND exits 1 on no match, so the guarded forms it
# needs (`|| true` then a first-line/default normalisation) live in one place —
# pinned in both directions by test-as-count.sh. Sourced rather than reinvented:
# the two counters below had each grown their own version, and only one of them
# was correct (#3115).
# shellcheck source=lib/as-count.sh
source "$SCRIPT_DIR/lib/as-count.sh"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=== SceneView Cross-Platform API Consistency Check ===${NC}"
echo ""

# ─── 1. Android Composable Functions ────────────────────────────────────
echo -e "${CYAN}--- Android Composable Functions (sceneview/ + arsceneview/) ---${NC}"
ANDROID_COMPOSABLES=()
if [ -d "$REPO_ROOT/sceneview/src" ] || [ -d "$REPO_ROOT/arsceneview/src" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && ANDROID_COMPOSABLES+=("$line")
    done < <(grep -rh "@Composable" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null \
        | grep "fun " | sed 's/.*fun //' | sed 's/(.*//' | sort -u || true)
    for c in "${ANDROID_COMPOSABLES[@]}"; do
        echo "  - $c"
    done
    echo "  Total: ${#ANDROID_COMPOSABLES[@]}"
fi
echo ""

# ─── 2. Android Node Types (classes) ───────────────────────────────────
echo -e "${CYAN}--- Android Node Classes ---${NC}"
ANDROID_NODES=()
while IFS= read -r line; do
    [ -n "$line" ] && ANDROID_NODES+=("$line")
done < <(grep -rh "^class\|^open class\|^abstract class\|^data class" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null \
    | grep -i "node\|scene" \
    | sed 's/class //' | sed 's/(.*//' | sed 's/ :.*//' | sed 's/open //' | sed 's/abstract //' | sed 's/data //' \
    | sort -u || true)
for n in "${ANDROID_NODES[@]}"; do
    echo "  - $n"
done
echo "  Total: ${#ANDROID_NODES[@]}"
echo ""

# ─── 3. Android Public Functions (non-composable) ──────────────────────
echo -e "${CYAN}--- Android Public remember* Functions ---${NC}"
ANDROID_REMEMBER=()
while IFS= read -r line; do
    [ -n "$line" ] && ANDROID_REMEMBER+=("$line")
done < <(grep -rh "^fun remember\|^suspend fun remember" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null \
    | sed 's/.*fun //' | sed 's/(.*//' | sort -u || true)
for r in "${ANDROID_REMEMBER[@]}"; do
    echo "  - $r"
done
echo "  Total: ${#ANDROID_REMEMBER[@]}"
echo ""

# ─── 4. iOS/Swift Public Types ──────────────────────────────────────────
echo -e "${CYAN}--- iOS Public Types (SceneViewSwift/) ---${NC}"
SWIFT_SRC="$REPO_ROOT/SceneViewSwift/Sources/SceneViewSwift"
SWIFT_TYPES=()
if [ -d "$SWIFT_SRC" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && SWIFT_TYPES+=("$line")
    done < <(grep -rh "^public class\|^public struct\|^open class\|^public enum\|^@Observable.*class" "$SWIFT_SRC" 2>/dev/null \
        | sed 's/public //' | sed 's/open //' | sed 's/class //' | sed 's/struct //' | sed 's/enum //' \
        | sed 's/(.*//' | sed 's/ :.*//' | sed 's/<.*//' | sed 's/{.*//' | sed 's/@Observable //' \
        | tr -d ' ' | sort -u || true)
    for t in "${SWIFT_TYPES[@]}"; do
        echo "  - $t"
    done
    echo "  Total: ${#SWIFT_TYPES[@]}"
else
    echo -e "  ${YELLOW}SceneViewSwift/Sources not found${NC}"
fi
echo ""

# ─── 5. iOS SwiftUI Views ──────────────────────────────────────────────
echo -e "${CYAN}--- iOS SwiftUI Views ---${NC}"
SWIFT_VIEWS=()
if [ -d "$SWIFT_SRC" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && SWIFT_VIEWS+=("$line")
    done < <(grep -rh "^public struct.*: View" "$SWIFT_SRC" 2>/dev/null \
        | sed 's/public struct //' | sed 's/ :.*//' | sed 's/<.*//' \
        | sort -u || true)
    for v in "${SWIFT_VIEWS[@]}"; do
        echo "  - $v"
    done
    echo "  Total: ${#SWIFT_VIEWS[@]}"
fi
echo ""

# ─── 6. Web API (sceneview-web/) ────────────────────────────────────────
echo -e "${CYAN}--- Web API (sceneview-web/) ---${NC}"
WEB_SRC="$REPO_ROOT/sceneview-web/src"
WEB_CLASSES=()
if [ -d "$WEB_SRC" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && WEB_CLASSES+=("$line")
    done < <(grep -rh "@JsExport\|^class\|^external class\|^object" "$WEB_SRC" 2>/dev/null \
        | grep -v "^import\|^//\|^package" \
        | sed 's/class //' | sed 's/object //' | sed 's/(.*//' | sed 's/ :.*//' | sed 's/external //' | sed 's/{.*//' \
        | tr -d ' ' | grep -v "^$" | sort -u || true)
    for n in "${WEB_CLASSES[@]}"; do
        echo "  - $n"
    done
    echo "  Total: ${#WEB_CLASSES[@]}"
else
    echo -e "  ${YELLOW}sceneview-web/src not found${NC}"
fi
echo ""

# ─── 7. KMP Core Types ──────────────────────────────────────────────────
echo -e "${CYAN}--- KMP Core Types (sceneview-core/commonMain) ---${NC}"
KMP_SRC="$REPO_ROOT/sceneview-core/src/commonMain"
KMP_TYPES=()
if [ -d "$KMP_SRC" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && KMP_TYPES+=("$line")
    done < <(grep -rh "^class\|^data class\|^object\|^interface\|^value class\|^sealed class\|^enum class" "$KMP_SRC" 2>/dev/null \
        | sed 's/data //' | sed 's/value //' | sed 's/sealed //' | sed 's/enum //' \
        | sed 's/class //' | sed 's/object //' | sed 's/interface //' \
        | sed 's/(.*//' | sed 's/ :.*//' | sed 's/{.*//' | sed 's/<.*//' \
        | tr -d ' ' | grep -v "^$" | sort -u || true)
    for n in "${KMP_TYPES[@]}"; do
        echo "  - $n"
    done
    echo "  Total: ${#KMP_TYPES[@]}"
else
    echo -e "  ${YELLOW}sceneview-core/src/commonMain not found${NC}"
fi
echo ""

# ─── 8. Cross-Platform Node Parity ──────────────────────────────────────
echo -e "${CYAN}=== Cross-Platform Node Parity ===${NC}"
echo ""

# Canonical node types expected
CANONICAL_NODES=(
    "ModelNode"
    "CameraNode"
    "LightNode"
    "AnchorNode"
    "ViewNode"
    "VideoNode"
    "CubeNode"
    "SphereNode"
    "CylinderNode"
    "PlaneNode"
)

printf "  %-20s %-10s %-10s %-10s\n" "Node Type" "Android" "iOS" "Web"
printf "  %-20s %-10s %-10s %-10s\n" "---------" "-------" "---" "---"

GAPS=0
for node in "${CANONICAL_NODES[@]}"; do
    ANDROID_HAS="NO"
    IOS_HAS="NO"
    WEB_HAS="NO"

    # Check Android (source code)
    if grep -rq "class $node\|fun $node" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null; then
        ANDROID_HAS="YES"
    fi

    # Check iOS (source code)
    if [ -d "$SWIFT_SRC" ] && grep -rq "$node" "$SWIFT_SRC" 2>/dev/null; then
        IOS_HAS="YES"
    fi

    # Check Web (source code)
    if [ -d "$WEB_SRC" ] && grep -rq "$node" "$WEB_SRC" 2>/dev/null; then
        WEB_HAS="YES"
    fi

    STATUS_A="${GREEN}YES${NC}"
    STATUS_I="${GREEN}YES${NC}"
    STATUS_W="${GREEN}YES${NC}"
    [ "$ANDROID_HAS" = "NO" ] && STATUS_A="${RED}NO${NC}" && GAPS=$((GAPS + 1))
    [ "$IOS_HAS" = "NO" ] && STATUS_I="${YELLOW}NO${NC}" && GAPS=$((GAPS + 1))
    [ "$WEB_HAS" = "NO" ] && STATUS_W="${YELLOW}NO${NC}" && GAPS=$((GAPS + 1))

    printf "  %-20s " "$node"
    echo -e "${STATUS_A}       ${STATUS_I}       ${STATUS_W}"
done

echo ""

# ─── 9. Composable Parity ───────────────────────────────────────────────
echo -e "${CYAN}=== Composable / View Parity ===${NC}"
echo ""

CANONICAL_VIEWS=(
    "Scene"
    "ARScene"
    "ModelNode"
    "LightNode"
    "CameraNode"
)

printf "  %-20s %-15s %-15s\n" "View/Composable" "Android" "iOS SwiftUI"
printf "  %-20s %-15s %-15s\n" "---------------" "-------" "-----------"

for view in "${CANONICAL_VIEWS[@]}"; do
    ANDROID_HAS="NO"
    IOS_HAS="NO"

    if grep -rq "@Composable.*\bfun $view\b\|@Composable.*\n.*fun $view\b" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null; then
        ANDROID_HAS="YES"
    fi
    # Also check class names for Android (some are class-based composables)
    if [ "$ANDROID_HAS" = "NO" ] && grep -rq "class $view\b" "$REPO_ROOT/sceneview/src/" "$REPO_ROOT/arsceneview/src/" 2>/dev/null; then
        ANDROID_HAS="YES"
    fi

    if [ -d "$SWIFT_SRC" ] && grep -rq "struct $view\|class $view" "$SWIFT_SRC" 2>/dev/null; then
        IOS_HAS="YES"
    fi

    STATUS_A="${GREEN}YES${NC}"
    STATUS_I="${GREEN}YES${NC}"
    [ "$ANDROID_HAS" = "NO" ] && STATUS_A="${RED}NO${NC}"
    [ "$IOS_HAS" = "NO" ] && STATUS_I="${YELLOW}NO${NC}"

    printf "  %-20s " "$view"
    echo -e "${STATUS_A}           ${STATUS_I}"
done

echo ""

# ─── 10. llms.txt Coverage ──────────────────────────────────────────────
echo -e "${CYAN}=== llms.txt API Coverage ===${NC}"
LLMS="$REPO_ROOT/llms.txt"
if [ -f "$LLMS" ]; then
    UNDOCUMENTED=0

    # Check Android nodes
    for n in "${ANDROID_NODES[@]}"; do
        if ! grep -q "\b$n\b" "$LLMS" 2>/dev/null; then
            echo -e "  ${YELLOW}UNDOCUMENTED in llms.txt: $n (Android)${NC}"
            UNDOCUMENTED=$((UNDOCUMENTED + 1))
        fi
    done

    # Check iOS types
    for t in "${SWIFT_TYPES[@]}"; do
        if ! grep -q "\b$t\b" "$LLMS" 2>/dev/null; then
            echo -e "  ${YELLOW}UNDOCUMENTED in llms.txt: $t (iOS)${NC}"
            UNDOCUMENTED=$((UNDOCUMENTED + 1))
        fi
    done

    if [ "$UNDOCUMENTED" -eq 0 ]; then
        echo -e "  ${GREEN}All platform types documented in llms.txt${NC}"
    else
        echo -e "  ${RED}$UNDOCUMENTED type(s) not found in llms.txt${NC}"
    fi
else
    echo -e "  ${YELLOW}llms.txt not found${NC}"
fi
echo ""

# ─── 11. APK Manifest Cross-Check (aapt2 via android_cli_describe) ──────
# Opt-in: needs a built APK so the fast source-only path stays the default.
#
# NOTE: Google's `android` CLI v0.7 `describe` subcommand analyzes a *project
# directory*, not a built artifact. The android_cli_describe helper in
# lib/android-cli.sh therefore inspects the artifact with `aapt2` —
# `aapt2 dump badging` for an `.apk`. This section parses that badging output.
APK_ISSUES=0
if [ "$WITH_APK" -eq 1 ]; then
    echo -e "${CYAN}=== APK Manifest Cross-Check (aapt2) ===${NC}"
    echo ""

    # shellcheck source=lib/android-cli.sh
    source "$SCRIPT_DIR/lib/android-cli.sh"

    DEMO_MODULE="$REPO_ROOT/samples/android-demo"
    # Canonical debug APK output of `:samples:android-demo:assembleDebug`.
    APK="$DEMO_MODULE/build/outputs/apk/debug/android-demo-debug.apk"

    if [ ! -f "$APK" ]; then
        echo -e "  ${YELLOW}APK not found — building :samples:android-demo:assembleDebug ...${NC}"
        if [ -x "$REPO_ROOT/gradlew" ]; then
            ( cd "$REPO_ROOT" && ./gradlew --quiet :samples:android-demo:assembleDebug ) \
                || echo -e "  ${RED}Gradle build failed${NC}"
        else
            echo -e "  ${YELLOW}gradlew not found — cannot build APK${NC}"
        fi
    else
        echo -e "  ${GREEN}Reusing cached APK:${NC} ${APK#"$REPO_ROOT"/}"
    fi

    if [ -f "$APK" ]; then
        DESCRIBE_OUT="$(mktemp -t sv-describe.XXXXXX)"
        # android_cli_describe <apk-or-aab> echoes `aapt2 dump badging` for an APK.
        if android_cli_describe "$APK" >"$DESCRIBE_OUT" 2>/dev/null; then
            echo ""
            echo -e "  ${CYAN}Manifest badging (aapt2):${NC}"

            # `aapt2 dump badging` reports the package line, the single
            # `launchable-activity:` entry and uses-permission/feature lines.
            # The android-demo is a single-Activity app whose extra demos are
            # reached via the `sceneview://` deep link, not separate activities.

            # 1. Package id matches the published demo app.
            #    badging line: package: name='io.github.sceneview.demo' ...
            PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$DESCRIBE_OUT" | head -1)"
            if [ "$PKG" = "io.github.sceneview.demo" ]; then
                echo -e "    ${GREEN}Package id io.github.sceneview.demo confirmed${NC}"
            else
                echo -e "    ${YELLOW}Unexpected package id: ${PKG:-<none>} (expected io.github.sceneview.demo)${NC}"
                APK_ISSUES=$((APK_ISSUES + 1))
            fi

            # 2. A launchable activity must be declared — the app must be
            #    launchable from the home screen.
            #    badging line: launchable-activity: name='...MainActivity' ...
            LAUNCH_ACTIVITY="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" "$DESCRIBE_OUT" | head -1)"
            if [ -n "$LAUNCH_ACTIVITY" ]; then
                echo -e "    ${GREEN}Launchable activity: ${LAUNCH_ACTIVITY}${NC}"
                if printf '%s' "$LAUNCH_ACTIVITY" | grep -qiE 'MainActivity'; then
                    echo -e "    ${GREEN}MainActivity is the launcher entry point${NC}"
                else
                    echo -e "    ${YELLOW}Launcher entry point is not MainActivity${NC}"
                    APK_ISSUES=$((APK_ISSUES + 1))
                fi
            else
                echo -e "    ${RED}No launchable-activity — app not launchable${NC}"
                APK_ISSUES=$((APK_ISSUES + 1))
            fi

            # 3. Deep-link scheme `sceneview://` — used by QR codes on the
            #    website / README / docs to land on a specific demo.
            #    `aapt2 dump badging` does NOT surface intent-filter data /
            #    deep-link schemes, so decode the manifest xmltree for this.
            DEEPLINK_OK=0
            if command -v aapt2 >/dev/null 2>&1; then
                XMLTREE_OUT="$(aapt2 dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)"
                if printf '%s' "$XMLTREE_OUT" | grep -qiE 'scheme.*sceneview|"sceneview"'; then
                    DEEPLINK_OK=1
                fi
            fi
            if [ "$DEEPLINK_OK" -eq 1 ]; then
                echo -e "    ${GREEN}sceneview:// deep-link scheme present${NC}"
            else
                echo -e "    ${YELLOW}sceneview:// deep-link scheme not detected in manifest${NC}"
                APK_ISSUES=$((APK_ISSUES + 1))
            fi
        else
            echo -e "  ${YELLOW}aapt2 unavailable or APK unreadable — manifest cross-check skipped${NC}"
        fi
        rm -f "$DESCRIBE_OUT" 2>/dev/null || true
    fi
    echo ""

    # ─── Demo-count parity: Android demo fragments vs iOS demo scenes ───
    # ADVISORY only, and deliberately narrow: `check-demo-id-parity.sh` is the
    # BLOCKING gate on demo-id drift (both directions, id by id, via
    # parity-manifest.yml). All this leg adds is "did one platform's inventory
    # vanish entirely" — an inequality between the two counts is legitimate
    # (android-only and ios-only rows both exist), so it is never flagged here.
    echo -e "  ${CYAN}Demo inventory parity:${NC}"
    IOS_SCENES_DIR="$REPO_ROOT/samples/ios-demo/SceneViewDemo/Views/Demos/Scenes"

    # Android demo ids come from the per-demo FRAGMENTS, not from DemoRegistry.kt:
    # #1797 moved every `DemoEntry(` instantiation into
    # `demo/fragments/*Fragment.kt` (collated into `GeneratedDemos`) and left
    # `DemoRegistry.kt` holding only the `data class DemoEntry(` declaration — which
    # the old probe's `^[[:space:]]*DemoEntry\(` cannot match, since that line starts
    # with `data`. So this counter had been a structural 0 ever since, the `-gt 0`
    # guard below could never be true, and the leg printed no drift because it
    # measured nothing. Same source as check-demo-id-parity.sh, so the two gates
    # cannot disagree about what an Android demo is.
    ANDROID_FRAG_DIR="$DEMO_MODULE/src/main/java/io/github/sceneview/demo/fragments"
    ANDROID_DEMO_COUNT=0
    if [ -d "$ANDROID_FRAG_DIR" ]; then
        ANDROID_DEMO_COUNT="$(as_count "$( (grep -hoE 'id[[:space:]]*=[[:space:]]*"[a-z0-9-]+"' "$ANDROID_FRAG_DIR"/*Fragment.kt 2>/dev/null || true) | sort -u | wc -l | tr -d ' ')")"
    fi
    # The iOS side counts `// @sceneId` directives under `Views/Demos/Scenes/` —
    # the directive `collate-ios-demos.sh` itself parses to build the registry the
    # Samples tab renders. It used to be `grep -ciE 'demo|sample'` on
    # `SamplesTab.swift`, which counted doc comments and `@State` declarations:
    # 33 on a file that declares no demo at all, and — since `struct SamplesTab`
    # matches `sample` — a count that cannot reach 0 while the file exists. So the
    # `-eq 0` guard below was unreachable from BOTH sides at once.
    IOS_DEMO_COUNT=0
    if [ -d "$IOS_SCENES_DIR" ]; then
        IOS_DEMO_COUNT="$(as_count "$( (grep -hoE '// @sceneId[[:space:]]+[a-z0-9-]+' "$IOS_SCENES_DIR"/*Scene.swift 2>/dev/null || true) | sort -u | wc -l | tr -d ' ')")"
    fi

    echo "    Android demo fragment ids:    $ANDROID_DEMO_COUNT"
    echo "    iOS demo scene ids:           $IOS_DEMO_COUNT"
    # A discovery that finds nothing must never read as "no drift" (#3050, #3068).
    # The repo has demos on both platforms; a zero here means the probe broke —
    # the sources moved again — and it says so instead of printing green.
    if [ "$ANDROID_DEMO_COUNT" -eq 0 ]; then
        echo -e "    ${RED}Android demo discovery found 0 ids — the probe is broken, not the tree${NC}"
        APK_ISSUES=$((APK_ISSUES + 1))
    fi
    if [ ! -d "$IOS_SCENES_DIR" ]; then
        echo -e "    ${YELLOW}iOS Demos/Scenes/ not found — iOS inventory NOT checked${NC}"
        APK_ISSUES=$((APK_ISSUES + 1))
    elif [ "$IOS_DEMO_COUNT" -eq 0 ]; then
        echo -e "    ${RED}iOS demo discovery found 0 ids — the probe is broken, not the tree${NC}"
        APK_ISSUES=$((APK_ISSUES + 1))
    fi
    echo ""

    if [ "$APK_ISSUES" -eq 0 ]; then
        echo -e "  ${GREEN}APK manifest matches expectations.${NC}"
    else
        echo -e "  ${YELLOW}$APK_ISSUES manifest cross-check finding(s).${NC}"
    fi
    echo ""
fi

# ─── Summary ───────────────────────────────────────────────────────────
echo -e "${CYAN}=== Summary ===${NC}"
echo "  Android composables:    ${#ANDROID_COMPOSABLES[@]}"
echo "  Android node classes:   ${#ANDROID_NODES[@]}"
echo "  Android remember* fns:  ${#ANDROID_REMEMBER[@]}"
echo "  iOS Swift types:        ${#SWIFT_TYPES[@]}"
echo "  iOS SwiftUI views:      ${#SWIFT_VIEWS[@]}"
echo "  Web classes:            ${#WEB_CLASSES[@]}"
echo "  KMP core types:         ${#KMP_TYPES[@]}"
echo "  Cross-platform gaps:    $GAPS"
if [ "$WITH_APK" -eq 1 ]; then
    echo "  APK manifest findings:  $APK_ISSUES"
fi
echo ""

if [ "$GAPS" -eq 0 ]; then
    echo -e "${GREEN}All canonical node types present on all platforms.${NC}"
else
    echo -e "${YELLOW}$GAPS gap(s) detected in cross-platform parity.${NC}"
    echo "  Note: Web and iOS are in alpha — gaps are expected."
fi
