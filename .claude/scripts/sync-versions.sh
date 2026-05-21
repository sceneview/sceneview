#!/usr/bin/env bash
# sync-versions.sh — Scan ALL version declarations across the entire project, report mismatches, optionally fix them
#
# Usage:
#   ./sync-versions.sh          # Report only
#   ./sync-versions.sh --fix    # Report and fix mismatches
#
# Exit codes:
#   0 = all versions aligned
#   1 = mismatches found (or fixed if --fix)
#   2 = error

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIX_MODE="${1:-}"
ERRORS=0
WARNINGS=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=== SceneView Version Sync Check ===${NC}"
echo ""

# ─── Source of truth ───────────────────────────────────────────────────────
SOURCE_VERSION=$(grep '^VERSION_NAME=' "$REPO_ROOT/gradle.properties" | cut -d= -f2)
if [ -z "$SOURCE_VERSION" ]; then
    echo -e "${RED}FATAL: Cannot read VERSION_NAME from gradle.properties${NC}"
    exit 2
fi
echo -e "Source of truth (gradle.properties): ${GREEN}$SOURCE_VERSION${NC}"
echo ""

# ─── Helper: portable in-place sed (BSD vs GNU) ──────────────────────────
# BSD sed (macOS dev machines) requires `sed -i ''` — an explicit empty
# backup suffix. GNU sed (Linux CI runners) treats `''` as a script and
# errors. Detect which flavour is on PATH once and dispatch accordingly.
# Usage: _sed_inplace "s/foo/bar/" path/to/file   (#1226)
if sed --version >/dev/null 2>&1; then
    # GNU sed — `--version` is GNU-only; BSD sed errors on it.
    _sed_inplace() { sed -i "$@"; }
else
    # BSD/macOS sed — needs the empty backup-suffix argument.
    _sed_inplace() { local script="$1"; shift; sed -i '' "$script" "$@"; }
fi

# ─── Helper: check a version ─────────────────────────────────────────────
declare -a LOCATIONS=()
declare -a VERSIONS=()
declare -a STATUSES=()

add_check() {
    local location="$1"
    local version="$2"
    local critical="${3:-true}" # true = MISMATCH is error, false = WARN only

    LOCATIONS+=("$location")
    VERSIONS+=("$version")

    if [ "$version" = "$SOURCE_VERSION" ]; then
        STATUSES+=("OK")
    elif [ "$version" = "MISSING" ] || [ "$version" = "NOT FOUND" ] || [ "$version" = "" ]; then
        STATUSES+=("SKIP")
        WARNINGS=$((WARNINGS + 1))
    elif [ "$critical" = "true" ]; then
        STATUSES+=("MISMATCH")
        ERRORS=$((ERRORS + 1))
    else
        STATUSES+=("WARN")
        WARNINGS=$((WARNINGS + 1))
    fi
}

# ─── Helper: check a Flutter/RN plugin's CONSUMED SceneView dependency ────
# `io.github.sceneview:(ar)sceneview:X.Y.Z` in the plugin Gradle files is NOT
# the plugin's own version — it is a dependency on the *published* Maven Central
# artifact. It MUST lag to the last released version: it cannot point at the
# in-flight release (e.g. 4.7.0 is not on Maven Central until the release PR is
# published — pointing at it breaks the `Build flutter-demo APK` CI check). So
# this coordinate is checked REPORT-ONLY (WARN, never MISMATCH) and is
# deliberately excluded from every `--fix` sweep below. See issue #1494.
check_plugin_sdk_dep() {
    local label="$1" file="$2"
    [ -f "$file" ] || return 0
    local v
    v=$(grep -m1 -oE 'io\.github\.sceneview:sceneview:[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$file" 2>/dev/null \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    [ "$v" = "NOT FOUND" ] && return 0
    LOCATIONS+=("$label (consumed SDK dep — lags, not bumped)")
    VERSIONS+=("$v")
    # Never MISMATCH: this coordinate is INTENTIONALLY behind SOURCE_VERSION.
    if [ "$v" = "$SOURCE_VERSION" ]; then
        STATUSES+=("OK")
    else
        STATUSES+=("WARN")
        WARNINGS=$((WARNINGS + 1))
    fi
}

# ─── 1. Gradle properties (Android modules) ──────────────────────────────
echo -e "${CYAN}--- Gradle Modules ---${NC}"
for module in sceneview arsceneview sceneview-core; do
    PROPS="$REPO_ROOT/$module/gradle.properties"
    if [ -f "$PROPS" ]; then
        V=$(grep '^VERSION_NAME=' "$PROPS" 2>/dev/null | cut -d= -f2 || echo "MISSING")
        add_check "$module/gradle.properties" "$V"
    fi
done

# ─── 2. npm packages ────────────────────────────────────────────────────
echo -e "${CYAN}--- npm Packages ---${NC}"
# NOTE: mcp/package.json is EXCLUDED from this check entirely.
# sceneview-mcp (npm) has its own independent version track (e.g. 4.0.0-rc.5)
# separate from gradle.properties VERSION_NAME (Maven Central artifacts).
# Forcing them to match caused a regression where the sync agent downgraded
# mcp/package.json behind the published npm @next tag.
for pkg in sceneview-web react-native/react-native-sceneview; do
    PKG_JSON="$REPO_ROOT/$pkg/package.json"
    if [ -f "$PKG_JSON" ]; then
        V=$(python3 -c "import json; print(json.load(open('$PKG_JSON'))['version'])" 2>/dev/null || echo "MISSING")
        add_check "$pkg/package.json" "$V"
    fi
done

# React Native plugin's CONSUMED SceneView dependency coordinate — same rule as
# the Flutter plugin: `io.github.sceneview:(ar)sceneview:X.Y.Z` here is a
# dependency on the published Maven Central artifact and MUST lag to the last
# released version, never the in-flight one. REPORT-ONLY (WARN), never fixed.
check_plugin_sdk_dep "react-native/.../android/build.gradle.kts" \
    "$REPO_ROOT/react-native/react-native-sceneview/android/build.gradle.kts"

# ─── 3. Flutter ──────────────────────────────────────────────────────────
echo -e "${CYAN}--- Flutter ---${NC}"
# Main plugin
PUBSPEC="$REPO_ROOT/flutter/sceneview_flutter/pubspec.yaml"
if [ -f "$PUBSPEC" ]; then
    V=$(grep '^version:' "$PUBSPEC" | awk '{print $2}' || echo "MISSING")
    add_check "flutter/sceneview_flutter/pubspec.yaml" "$V"
fi

# Flutter Android build.gradle
FLUTTER_ANDROID_GRADLE="$REPO_ROOT/flutter/sceneview_flutter/android/build.gradle"
if [ -f "$FLUTTER_ANDROID_GRADLE" ]; then
    V=$(grep "^version " "$FLUTTER_ANDROID_GRADLE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "flutter/.../android/build.gradle" "$V"
fi

# Flutter plugin's CONSUMED SceneView dependency coordinate — see
# `check_plugin_sdk_dep` definition above. Checked WARN-only, never bumped.
check_plugin_sdk_dep "flutter/.../android/build.gradle" \
    "$REPO_ROOT/flutter/sceneview_flutter/android/build.gradle"

# Flutter iOS podspec
PODSPEC="$REPO_ROOT/flutter/sceneview_flutter/ios/sceneview_flutter.podspec"
if [ -f "$PODSPEC" ]; then
    V=$(grep "s\.version" "$PODSPEC" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "flutter/.../ios/sceneview_flutter.podspec" "$V"
fi

# Flutter example pubspec
FLUTTER_EXAMPLE="$REPO_ROOT/samples/flutter-demo/pubspec.yaml"
if [ -f "$FLUTTER_EXAMPLE" ]; then
    V=$(grep '^version:' "$FLUTTER_EXAMPLE" | awk '{print $2}' || echo "NOT FOUND")
    add_check "samples/flutter-demo/pubspec.yaml" "$V" "false"
fi

# Flutter CHANGELOG
FLUTTER_CL="$REPO_ROOT/flutter/sceneview_flutter/CHANGELOG.md"
if [ -f "$FLUTTER_CL" ]; then
    V=$(grep -m1 '^## ' "$FLUTTER_CL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "flutter/.../CHANGELOG.md" "$V" "false"
fi

# ─── 4. Documentation ───────────────────────────────────────────────────
echo -e "${CYAN}--- Documentation ---${NC}"

# llms.txt (root)
LLMS="$REPO_ROOT/llms.txt"
if [ -f "$LLMS" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$LLMS" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "MISSING")
    add_check "llms.txt" "$V"
fi

# CLAUDE.md (code examples section)
CLAUDE_MD="$REPO_ROOT/CLAUDE.md"
if [ -f "$CLAUDE_MD" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$CLAUDE_MD" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "MISSING")
    add_check "CLAUDE.md (code examples)" "$V"
fi

# README.md
README="$REPO_ROOT/README.md"
if [ -f "$README" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$README" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "README.md (install)" "$V"
fi

# CHANGELOG.md (top entry version)
CHANGELOG="$REPO_ROOT/CHANGELOG.md"
if [ -f "$CHANGELOG" ]; then
    V=$(grep -m1 '^## ' "$CHANGELOG" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "MISSING")
    add_check "CHANGELOG.md (latest entry)" "$V" "false"
fi

# Module.md files
for modmd in sceneview/Module.md arsceneview/Module.md; do
    F="$REPO_ROOT/$modmd"
    if [ -f "$F" ]; then
        V=$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$F" | head -1 || echo "NOT FOUND")
        add_check "$modmd" "$V" "false"
    fi
done

# ─── 5. Docs site (MkDocs) ──────────────────────────────────────────────
echo -e "${CYAN}--- Docs Site ---${NC}"
for docfile in docs/docs/index.md docs/docs/quickstart.md docs/docs/llms-full.txt docs/docs/cheatsheet.md docs/docs/platforms.md docs/docs/migration.md docs/docs/android-xr.md; do
    F="$REPO_ROOT/$docfile"
    if [ -f "$F" ]; then
        V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$docfile" "$V"
        fi
    fi
done

# docs/docs/llms.txt (separate from root llms.txt)
DOCS_LLMS="$REPO_ROOT/docs/docs/llms.txt"
if [ -f "$DOCS_LLMS" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_LLMS" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/llms.txt" "$V"
    fi
fi

# ─── 5b. llms.txt root↔docs full-content sync (issue #899) ──────────────
# `docs/docs/llms.txt` must be a byte-for-byte mirror of root `llms.txt` so
# mkdocs serves the same content as the raw GitHub URL that LLMs fetch. The
# `io.github.sceneview:sceneview:X.Y.Z` check above only catches ONE line —
# this catches any drift in prose/SPM/web/flutter snippets.
if [ -f "$LLMS" ] && [ -f "$DOCS_LLMS" ]; then
    if ! diff -q "$LLMS" "$DOCS_LLMS" >/dev/null 2>&1; then
        echo -e "${RED}MISMATCH: docs/docs/llms.txt has drifted from root llms.txt${NC}"
        if [ "$FIX_MODE" = "--fix" ]; then
            cp "$LLMS" "$DOCS_LLMS"
            echo -e "${GREEN}  Fixed: copied root llms.txt over docs/docs/llms.txt${NC}"
        else
            echo "  Diff (first 5 hunks):"
            diff -u "$LLMS" "$DOCS_LLMS" 2>&1 | head -25 | sed 's/^/    /'
            ERRORS=$((ERRORS + 1))
        fi
    fi
fi

# Flutter snippet inside llms.txt (`sceneview_flutter: ^X.Y.Z`) — separate
# from the maven `sceneview:` line, so the existing -m1 check misses it.
if [ -f "$LLMS" ]; then
    V=$(grep -m1 'sceneview_flutter:' "$LLMS" | grep -oE '\^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | sed 's/^\^//' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "llms.txt (flutter snippet)" "$V"
    fi
fi

# ─── 6. Android demo app ────────────────────────────────────────────────
echo -e "${CYAN}--- Sample Apps ---${NC}"
DEMO_GRADLE="$REPO_ROOT/samples/android-demo/build.gradle"
if [ -f "$DEMO_GRADLE" ]; then
    V=$(grep "versionName" "$DEMO_GRADLE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "samples/android-demo versionName" "$V" "false"
fi

# ─── 7. MCP source/dist version ─────────────────────────────────────────
# SKIPPED: MCP has its own version track independent of gradle.properties.
# mcp/src/index.ts PACKAGE_VERSION and mcp/package.json version must match
# each other, but NOT gradle.properties VERSION_NAME.
echo -e "${CYAN}--- MCP Source/Dist (independent track, not checked) ---${NC}"

# ─── 7b. Claude Code plugin ─────────────────────────────────────────────
# Plugins live in github.com/sceneview/claude-marketplace (separate repo).
# Run `bash scripts/sync-plugin-versions.sh` THERE, not here. Plugin versions
# track npm MCP versions, not gradle.properties.

# ─── 8. iOS demo ────────────────────────────────────────────────────────
IOS_ABOUT="$REPO_ROOT/SceneViewSwift/Examples/SceneViewDemo/SceneViewDemo/Views/AboutView.swift"
if [ -f "$IOS_ABOUT" ]; then
    V=$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$IOS_ABOUT" | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "SceneViewSwift/.../AboutView.swift" "$V" "false"
    fi
fi

# samples/ios-demo Xcode project — MARKETING_VERSION is the user-visible iOS demo
# version. Was a release blind spot until v4.0.8 (caught by an audit). Bump this
# in lockstep with the Android demo versionName + AboutView.swift.
IOS_DEMO_PBXPROJ="$REPO_ROOT/samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj"
if [ -f "$IOS_DEMO_PBXPROJ" ]; then
    V=$(grep -oE 'MARKETING_VERSION = [0-9]+\.[0-9]+\.[0-9]+' "$IOS_DEMO_PBXPROJ" | head -1 | awk '{print $NF}' || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "samples/ios-demo MARKETING_VERSION" "$V" "false"
    fi
fi

# ─── 9. Website (static) ────────────────────────────────────────────────
echo -e "${CYAN}--- Website Static ---${NC}"
WEBSITE_INDEX="$REPO_ROOT/website-static/index.html"
if [ -f "$WEBSITE_INDEX" ]; then
    V=$(grep 'softwareVersion' "$WEBSITE_INDEX" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "website-static/index.html (softwareVersion)" "$V"
fi

# ─── 9b. Auto-update artefacts (per-sample version polled at runtime) ────
# `website-static/version.json` is fetched by `samples/web-demo` on every
# `visibilitychange` to decide whether to surface the "Reload to update"
# snackbar — see plan `.claude/plans/bubbly-sauteeing-curry.md`. The
# `.version` field MUST match VERSION_NAME, otherwise users get spurious
# update prompts the moment the new build deploys but version.json hasn't
# caught up (or vice versa — they never see the prompt because the file
# claims they're already current).
VERSION_JSON="$REPO_ROOT/website-static/version.json"
if [ -f "$VERSION_JSON" ]; then
    V=$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['version'])" 2>/dev/null || echo "MISSING")
    add_check "website-static/version.json (.version)" "$V"
fi

# Web demo inline JS — `var BUILD_VERSION = 'X.Y.Z'` in index.html. The
# web demo is a static `index.html`; its inline-JS runtime drives the
# auto-update snackbar, so BUILD_VERSION must match VERSION_NAME /
# version.json.
WEB_DEMO_INDEX="$REPO_ROOT/samples/web-demo/index.html"
if [ -f "$WEB_DEMO_INDEX" ]; then
    V=$(grep -E "var BUILD_VERSION = '" "$WEB_DEMO_INDEX" | grep -oE "'[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?'" | tr -d "'" | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "samples/web-demo index.html BUILD_VERSION" "$V"
    fi
fi

# React Native demo — both package.json and src/App.tsx's `VERSION` literal.
RN_DEMO_PKG="$REPO_ROOT/samples/react-native-demo/package.json"
if [ -f "$RN_DEMO_PKG" ]; then
    V=$(python3 -c "import json; print(json.load(open('$RN_DEMO_PKG'))['version'])" 2>/dev/null || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "samples/react-native-demo/package.json" "$V"
    fi
fi
RN_DEMO_APP="$REPO_ROOT/samples/react-native-demo/src/App.tsx"
if [ -f "$RN_DEMO_APP" ]; then
    V=$(grep -E "const VERSION = '" "$RN_DEMO_APP" | grep -oE "'[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?'" | tr -d "'" | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "samples/react-native-demo App.tsx VERSION" "$V"
    fi
fi

# ─── 10. Website (deployed — separate repo) ─────────────────────────────
WEBSITE_DIR="$REPO_ROOT/../sceneview.github.io"
if [ -d "$WEBSITE_DIR" ]; then
    WEBSITE_DEPLOYED="$WEBSITE_DIR/index.html"
    if [ -f "$WEBSITE_DEPLOYED" ]; then
        V=$(grep 'softwareVersion' "$WEBSITE_DEPLOYED" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "sceneview.github.io/index.html" "$V"
        fi
    fi
fi

# ─── 10b. SwiftPM `from:` install snippets (#990) ───────────────────────
# Catches stale `.package(url: "…/sceneview-swift", from: "X.Y.Z")` clauses
# scattered across docs / READMEs / marketing surfaces. The release-time
# manual sweep that bumped 11 of these for v4.1.0 (#989) showed the cost
# of keeping these out of band; this section closes the gap so future
# bumps catch them via `--fix`.
echo -e "${CYAN}--- SwiftPM Install Snippets ---${NC}"
SPM_FILES=(
    llms.txt
    README.md
    CLAUDE.md
    .cursorrules
    Package.swift
    SceneViewSwift/README.md
    docs/docs/cheatsheet-ios.md
    docs/docs/index.md
    docs/docs/platforms.md
    docs/docs/samples-ios.md
    docs/docs/quickstart-ios.md
    docs/docs/llms.txt
    website-static/llms.txt
    website-static/.well-known/llms.txt
    website-static/playground.html
    .github/copilot-instructions.md
    gpt/system-prompt.md
    # AI-assistant prompt surfaces in .gitignore'd dirs — tracked files
    # that the release sweep kept missing (drift fixed by hand in #1217
    # and again in #1262). Listed explicitly so `--fix` keeps them in sync.
    pro/gpt-store/gpt-instructions.md
    marketing/stackoverflow/qa-drafts.md
)
for spm_file in "${SPM_FILES[@]}"; do
    F="$REPO_ROOT/$spm_file"
    [ -f "$F" ] || continue
    # Looser match: any line mentioning the canonical SPM URL (sceneview/sceneview
    # monorepo OR sceneview/sceneview-swift legacy mirror) AND a `from` version.
    # Covers the canonical quoted form `.package(url: "…/sceneview", from: "X.Y.Z")`
    # AND the comment-style `// SPM: …/sceneview-swift.git from: "X.Y.Z"` AND the
    # prose form in README.md — `(from: X.Y.Z)` / `(SPM, from X.Y.Z)` — which has
    # NO quotes and an optional colon, so the strict `from: "…"` regex missed it
    # entirely (issue #1544: README SPM snippets sat 4 minors stale).
    V=$(grep -E 'sceneview/sceneview(-swift)?' "$F" 2>/dev/null \
        | grep -oE 'from:? "?[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"?' \
        | head -1 \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' || true)
    if [ -n "$V" ]; then
        add_check "$spm_file (SPM from:)" "$V"
    fi
done

# ─── 11. Bug report template ────────────────────────────────────────────
BUG_TEMPLATE="$REPO_ROOT/.github/ISSUE_TEMPLATE/bug_report.yml"
if [ -f "$BUG_TEMPLATE" ]; then
    V=$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$BUG_TEMPLATE" | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check ".github/ISSUE_TEMPLATE/bug_report.yml" "$V" "false"
    fi
fi

# ─── 12. Off-map version refs surfaced by the v4.4.0 audit (#1356) ──────
# These locations carried stale strings through several releases because
# they sat outside the Version Location Map and no check covered them.
# Each is wired in below so a future release catches the drift via --fix.
echo -e "${CYAN}--- Off-map Version Refs (#1356) ---${NC}"

# website-static/js/package.json — the npm `sceneview-web` manifest shipped
# alongside the CDN-hosted JS. Same package as sceneview-web/package.json,
# so it must track VERSION_NAME (not an independent track).
WEBSITE_JS_PKG="$REPO_ROOT/website-static/js/package.json"
if [ -f "$WEBSITE_JS_PKG" ]; then
    V=$(python3 -c "import json; print(json.load(open('$WEBSITE_JS_PKG'))['version'])" 2>/dev/null || echo "MISSING")
    add_check "website-static/js/package.json" "$V"
fi

# docs/docs/ai-context.md — the AI quick-context block users paste into
# assistants. Pins `io.github.sceneview:sceneview:X.Y.Z`. Stale here means
# AI assistants actively generate code against the wrong artifact version.
AI_CONTEXT="$REPO_ROOT/docs/docs/ai-context.md"
if [ -f "$AI_CONTEXT" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$AI_CONTEXT" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/ai-context.md" "$V"
    fi
fi

# docs/docs/android-xr-emulator.md — Gradle install snippet.
XR_EMULATOR="$REPO_ROOT/docs/docs/android-xr-emulator.md"
if [ -f "$XR_EMULATOR" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$XR_EMULATOR" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/android-xr-emulator.md" "$V"
    fi
fi

# Extra docs-site pages carrying Maven artifact refs that section 5 misses.
for docfile in docs/docs/nodes.md docs/docs/comparison.md docs/docs/quickstart-tv.md \
               docs/docs/codelabs/codelab-3d-compose.md docs/docs/codelabs/codelab-ar-compose.md; do
    F="$REPO_ROOT/$docfile"
    if [ -f "$F" ]; then
        V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$docfile" "$V"
        fi
    fi
done

# Website static pages carrying Maven artifact refs (index.html, web.html,
# geometry-demo.html, playground.html) and the AI-context llms.txt mirrors.
for webfile in website-static/index.html website-static/web.html \
               website-static/geometry-demo.html website-static/playground.html \
               website-static/llms.txt website-static/llms-full.txt \
               website-static/.well-known/llms.txt; do
    F="$REPO_ROOT/$webfile"
    if [ -f "$F" ]; then
        V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$webfile (artifact refs)" "$V"
        fi
    fi
done

# README.md badge URLs — Flutter & React Native shields.io badges encode
# the version in the badge text (`badge/Flutter-vX.Y.Z-...`).
if [ -f "$README" ]; then
    V=$(grep -oE 'badge/(Flutter|React%20Native)-v[0-9]+\.[0-9]+\.[0-9]+' "$README" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "README.md (Flutter/RN badges)" "$V"
    fi
fi

# Web CDN refs — `cdn.jsdelivr.net/gh/sceneview/sceneview@vX.Y.Z` and
# `cdn.jsdelivr.net/npm/sceneview-web@X.Y.Z` pinned in README + docs.
for cdnfile in README.md docs/docs/index.md website-static/index.html website-static/web.html; do
    F="$REPO_ROOT/$cdnfile"
    if [ -f "$F" ]; then
        V=$(grep -oE 'sceneview(-web)?@v?[0-9]+\.[0-9]+\.[0-9]+' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$cdnfile (CDN @version)" "$V"
        fi
    fi
done

# SceneViewJS.kt — `SCENEVIEW_VERSION` constant stamped into the Kotlin/JS
# bundle. The .kt file lives in a library module other agents own, so this
# check is REPORT-ONLY (critical=false) and never auto-fixed here — bumping
# the constant is tracked separately (#1357).
SCENEVIEWJS_KT=$(find "$REPO_ROOT/sceneview-web" -name 'SceneViewJS.kt' 2>/dev/null | head -1 || true)
if [ -n "$SCENEVIEWJS_KT" ] && [ -f "$SCENEVIEWJS_KT" ]; then
    V=$(grep -E 'SCENEVIEW_VERSION' "$SCENEVIEWJS_KT" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"' | tr -d '"' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "sceneview-web SceneViewJS.kt SCENEVIEW_VERSION" "$V" "false"
    fi
fi

# Kotlin toolchain version — gradle/libs.versions.toml `kotlin = "X.Y.Z"` is
# the source of truth; llms.txt + llms-full.txt quote it in prose and drift.
# Reported under a separate "Kotlin" banner since it's not VERSION_NAME.
KOTLIN_TOML=$(grep -m1 '^kotlin = ' "$REPO_ROOT/gradle/libs.versions.toml" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
if [ -n "$KOTLIN_TOML" ]; then
    for kfile in llms.txt docs/docs/llms.txt docs/docs/llms-full.txt; do
        F="$REPO_ROOT/$kfile"
        [ -f "$F" ] || continue
        V=$(grep -oE 'Kotlin:\*\* [0-9]+\.[0-9]+\.[0-9]+' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "")
        if [ -n "$V" ]; then
            LOCATIONS+=("$kfile (Kotlin version)")
            VERSIONS+=("$V")
            if [ "$V" = "$KOTLIN_TOML" ]; then
                STATUSES+=("OK")
            else
                STATUSES+=("MISMATCH")
                ERRORS=$((ERRORS + 1))
            fi
        fi
    done
fi

# ─── Report ────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}=== Version Alignment Report ===${NC}"
echo ""
printf "  %-55s %-12s %-10s\n" "Location" "Version" "Status"
printf "  %-55s %-12s %-10s\n" "--------" "-------" "------"

for i in "${!LOCATIONS[@]}"; do
    LOC="${LOCATIONS[$i]}"
    V="${VERSIONS[$i]}"
    S="${STATUSES[$i]}"

    case "$S" in
        OK)       printf "  %-55s %-12s ${GREEN}%-10s${NC}\n" "$LOC" "$V" "OK" ;;
        MISMATCH) printf "  %-55s %-12s ${RED}%-10s${NC}\n" "$LOC" "$V" "MISMATCH" ;;
        WARN)     printf "  %-55s %-12s ${YELLOW}%-10s${NC}\n" "$LOC" "$V" "WARN" ;;
        SKIP)     printf "  %-55s %-12s ${YELLOW}%-10s${NC}\n" "$LOC" "$V" "SKIP" ;;
    esac
done

echo ""

# ─── Fix mode ──────────────────────────────────────────────────────────
if [ "$FIX_MODE" = "--fix" ] && [ "$ERRORS" -gt 0 ]; then
    echo -e "${YELLOW}Applying fixes...${NC}"

    # Fix module gradle.properties
    for module in sceneview arsceneview sceneview-core; do
        PROPS="$REPO_ROOT/$module/gradle.properties"
        if [ -f "$PROPS" ]; then
            CURRENT=$(grep '^VERSION_NAME=' "$PROPS" | cut -d= -f2)
            if [ "$CURRENT" != "$SOURCE_VERSION" ]; then
                _sed_inplace "s/^VERSION_NAME=.*/VERSION_NAME=$SOURCE_VERSION/" "$PROPS"
                echo -e "  Fixed: $module/gradle.properties ($CURRENT -> $SOURCE_VERSION)"
            fi
        fi
    done

    # Fix npm package.json files (skip mcp — it has its own version cycle)
    for pkg in sceneview-web react-native/react-native-sceneview; do
        PKG_JSON="$REPO_ROOT/$pkg/package.json"
        if [ -f "$PKG_JSON" ]; then
            CURRENT=$(python3 -c "import json; print(json.load(open('$PKG_JSON'))['version'])" 2>/dev/null)
            if [ "$CURRENT" != "$SOURCE_VERSION" ]; then
                python3 -c "
import json
with open('$PKG_JSON', 'r') as f:
    data = json.load(f)
data['version'] = '$SOURCE_VERSION'
with open('$PKG_JSON', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"
                echo -e "  Fixed: $pkg/package.json ($CURRENT -> $SOURCE_VERSION)"
            fi
        fi
    done

    # Fix Flutter pubspec.yaml
    PUBSPEC="$REPO_ROOT/flutter/sceneview_flutter/pubspec.yaml"
    if [ -f "$PUBSPEC" ]; then
        CURRENT=$(grep '^version:' "$PUBSPEC" | awk '{print $2}')
        if [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/^version: .*/version: $SOURCE_VERSION/" "$PUBSPEC"
            echo -e "  Fixed: flutter/sceneview_flutter/pubspec.yaml ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix Flutter Android build.gradle
    if [ -f "$FLUTTER_ANDROID_GRADLE" ]; then
        CURRENT=$(grep "^version " "$FLUTTER_ANDROID_GRADLE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1)
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/^version '$CURRENT'/version '$SOURCE_VERSION'/" "$FLUTTER_ANDROID_GRADLE"
            echo -e "  Fixed: flutter/.../android/build.gradle ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix Flutter iOS podspec
    if [ -f "$PODSPEC" ]; then
        CURRENT=$(grep "s\.version" "$PODSPEC" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1)
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/s\.version *= *'$CURRENT'/s.version          = '$SOURCE_VERSION'/" "$PODSPEC"
            echo -e "  Fixed: flutter/.../ios/sceneview_flutter.podspec ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix docs files (replace old version pattern in Maven artifact refs).
    # The trailing `|| true` is load-bearing under `set -euo pipefail`: when
    # the LAST loop iteration's `[ ... ]` test fails (status != MISMATCH), the
    # `&&` short-circuits to non-zero, propagates through the subshell, and
    # would otherwise abort the entire fix block (caught by 5-agent review of
    # PR #1040 — the SPM `--fix` deliverable for #990 silently no-op'd).
    OLD_VERSIONS=$( (for i in "${!LOCATIONS[@]}"; do
        [ "${STATUSES[$i]}" = "MISMATCH" ] && echo "${VERSIONS[$i]}"
    done) | sort -u || true)

    for OLD_V in $OLD_VERSIONS; do
        [ "$OLD_V" = "$SOURCE_VERSION" ] && continue
        # Fix docs that contain Maven artifact version refs
        for docfile in llms.txt README.md CLAUDE.md docs/docs/index.md docs/docs/quickstart.md docs/docs/llms-full.txt docs/docs/cheatsheet.md docs/docs/platforms.md docs/docs/migration.md docs/docs/android-xr.md; do
            F="$REPO_ROOT/$docfile"
            if [ -f "$F" ] && grep -q "io\.github\.sceneview:.*$OLD_V" "$F" 2>/dev/null; then
                _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$OLD_V/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $docfile (artifact refs $OLD_V -> $SOURCE_VERSION)"
            fi
        done
    done

    # Fix website-static/index.html version refs
    if [ -f "$WEBSITE_INDEX" ]; then
        for OLD_V in $OLD_VERSIONS; do
            [ "$OLD_V" = "$SOURCE_VERSION" ] && continue
            if grep -q "$OLD_V" "$WEBSITE_INDEX" 2>/dev/null; then
                _sed_inplace "s/$OLD_V/$SOURCE_VERSION/g" "$WEBSITE_INDEX"
                echo -e "  Fixed: website-static/index.html ($OLD_V -> $SOURCE_VERSION)"
            fi
        done
    fi

    # Fix website-static/version.json — the file served at
    # sceneview.github.io/version.json that all auto-update clients poll.
    # Re-write via python so the JSON stays valid; also stamp a fresh
    # `build` timestamp so the deploy commit isn't a content no-op.
    if [ -f "$VERSION_JSON" ]; then
        CURRENT=$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['version'])" 2>/dev/null || echo "")
        if [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            python3 -c "
import json, datetime
with open('$VERSION_JSON', 'r') as f:
    data = json.load(f)
data['version'] = '$SOURCE_VERSION'
data['build'] = datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ')
data['url'] = 'https://github.com/sceneview/sceneview/releases/tag/v$SOURCE_VERSION'
data['notes'] = 'https://github.com/sceneview/sceneview/blob/main/CHANGELOG.md'
with open('$VERSION_JSON', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"
            echo -e "  Fixed: website-static/version.json ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix samples/web-demo index.html inline JS `var BUILD_VERSION = 'X.Y.Z'`
    # AND the version pill (`v4.3.1`).
    if [ -f "$WEB_DEMO_INDEX" ]; then
        CURRENT=$(grep -E "var BUILD_VERSION = '" "$WEB_DEMO_INDEX" | grep -oE "'[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?'" | tr -d "'" | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/var BUILD_VERSION = '$CURRENT'/var BUILD_VERSION = '$SOURCE_VERSION'/" "$WEB_DEMO_INDEX"
            _sed_inplace "s/v$CURRENT/v$SOURCE_VERSION/g" "$WEB_DEMO_INDEX"
            echo -e "  Fixed: samples/web-demo index.html ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix samples/react-native-demo package.json + App.tsx VERSION literal.
    if [ -f "$RN_DEMO_PKG" ]; then
        CURRENT=$(python3 -c "import json; print(json.load(open('$RN_DEMO_PKG'))['version'])" 2>/dev/null || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            python3 -c "
import json
with open('$RN_DEMO_PKG', 'r') as f:
    data = json.load(f)
data['version'] = '$SOURCE_VERSION'
with open('$RN_DEMO_PKG', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"
            echo -e "  Fixed: samples/react-native-demo/package.json ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi
    if [ -f "$RN_DEMO_APP" ]; then
        CURRENT=$(grep -E "const VERSION = '" "$RN_DEMO_APP" | grep -oE "'[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?'" | tr -d "'" | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/const VERSION = '$CURRENT'/const VERSION = '$SOURCE_VERSION'/" "$RN_DEMO_APP"
            echo -e "  Fixed: samples/react-native-demo App.tsx VERSION ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix SwiftPM `from:` clauses (#990) — rewrites every stale snippet to
    # match $SOURCE_VERSION, scoped to lines that mention the canonical SPM
    # URL (monorepo or legacy mirror) so we never touch unrelated `from: "..."`
    # clauses (e.g. third-party deps).
    for spm_file in "${SPM_FILES[@]}"; do
        F="$REPO_ROOT/$spm_file"
        [ -f "$F" ] || continue
        # Use perl (not sed) for the in-line substitution: BSD sed is awkward
        # at constraining the replacement to lines matching a pattern. The
        # regex matches BOTH the quoted Package.swift form `from: "X.Y.Z"` and
        # the unquoted README prose form `from: X.Y.Z` / `from X.Y.Z` (#1544),
        # preserving whatever colon/quotes the original line used.
        if grep -qE 'sceneview/sceneview(-swift)?.*from:? "?[0-9]+\.[0-9]+\.[0-9]+' "$F" 2>/dev/null; then
            BEFORE=$(grep -E 'sceneview/sceneview(-swift)?.*from' "$F" | grep -oE 'from:? "?[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"?' | sort -u)
            perl -i -pe 'if (m{sceneview/sceneview(-swift)?}) { s/(from:? "?)[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?("?)/${1}'"$SOURCE_VERSION"'${3}/g }' "$F"
            AFTER=$(grep -E 'sceneview/sceneview(-swift)?.*from' "$F" | grep -oE 'from:? "?[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"?' | sort -u)
            if [ "$BEFORE" != "$AFTER" ]; then
                echo -e "  Fixed: $spm_file (SPM from: -> $SOURCE_VERSION)"
            fi
        fi
    done

    # Fix website-static/js/package.json (#1356)
    if [ -f "$WEBSITE_JS_PKG" ]; then
        CURRENT=$(python3 -c "import json; print(json.load(open('$WEBSITE_JS_PKG'))['version'])" 2>/dev/null || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            python3 -c "
import json
with open('$WEBSITE_JS_PKG', 'r') as f:
    data = json.load(f)
data['version'] = '$SOURCE_VERSION'
with open('$WEBSITE_JS_PKG', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"
            echo -e "  Fixed: website-static/js/package.json ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix off-map docs/website Maven artifact refs (#1356) — same per-old-version
    # sweep as section "Fix docs files", extended to the locations wired in
    # under section 12. The replacement is scoped to `io.github.sceneview:NAME:`
    # so it never touches unrelated version strings on the same line.
    for OLD_V in $OLD_VERSIONS; do
        [ "$OLD_V" = "$SOURCE_VERSION" ] && continue
        for f in docs/docs/ai-context.md docs/docs/android-xr-emulator.md \
                 docs/docs/nodes.md docs/docs/comparison.md docs/docs/quickstart-tv.md \
                 docs/docs/codelabs/codelab-3d-compose.md docs/docs/codelabs/codelab-ar-compose.md \
                 website-static/index.html website-static/web.html \
                 website-static/geometry-demo.html website-static/playground.html \
                 website-static/llms.txt website-static/llms-full.txt \
                 website-static/.well-known/llms.txt; do
            F="$REPO_ROOT/$f"
            if [ -f "$F" ] && grep -q "io\.github\.sceneview:[^:]*:$OLD_V" "$F" 2>/dev/null; then
                _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$OLD_V/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $f (artifact refs $OLD_V -> $SOURCE_VERSION)"
            fi
        done
        # README + docs badge URLs and CDN @version pins.
        for f in README.md docs/docs/index.md website-static/web.html; do
            F="$REPO_ROOT/$f"
            [ -f "$F" ] || continue
            if grep -qE "(badge/(Flutter|React%20Native)-v$OLD_V|sceneview(-web)?@v?$OLD_V|sceneview\.js\?v=$OLD_V)" "$F" 2>/dev/null; then
                _sed_inplace "s/-v$OLD_V/-v$SOURCE_VERSION/g; s/@v$OLD_V/@v$SOURCE_VERSION/g; s/@$OLD_V/@$SOURCE_VERSION/g; s/?v=$OLD_V/?v=$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $f (badge/CDN $OLD_V -> $SOURCE_VERSION)"
            fi
        done
    done

    echo ""
    echo -e "${GREEN}Fixes applied. Re-run without --fix to verify.${NC}"
fi

# ─── Summary ───────────────────────────────────────────────────────────
echo -e "${CYAN}=== Summary ===${NC}"
echo "  Checks: ${#LOCATIONS[@]}"
echo "  Errors (MISMATCH): $ERRORS"
echo "  Warnings: $WARNINGS"
echo ""

if [ "$ERRORS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
    echo -e "${GREEN}All versions are aligned to $SOURCE_VERSION${NC}"
    exit 0
elif [ "$ERRORS" -eq 0 ]; then
    echo -e "${YELLOW}All critical versions aligned. $WARNINGS warning(s) — review above.${NC}"
    exit 0
else
    echo -e "${RED}$ERRORS version mismatch(es) found.${NC}"
    if [ "$FIX_MODE" != "--fix" ]; then
        echo -e "Run with ${YELLOW}--fix${NC} to auto-fix where possible."
    fi
    exit 1
fi
