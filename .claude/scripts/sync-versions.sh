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

# react-native/react-native-sceneview/package-lock.json — the lockfile sibling
# of the RN library package.json checked just above. Same two-slot drift as the
# web-demo lock (it shipped 4.3.0 while package.json was 4.17.0): `npm install`
# rewrites root `.version` + `.packages[""].version` to match package.json, so a
# stale lock dirties the tree. Both slots checked so a partial drift is caught.
RN_LIB_LOCK="$REPO_ROOT/react-native/react-native-sceneview/package-lock.json"
if [ -f "$RN_LIB_LOCK" ]; then
    while IFS= read -r V; do
        [ -n "$V" ] && add_check "react-native/.../package-lock.json" "$V"
    done < <(python3 -c "
import json
d = json.load(open('$RN_LIB_LOCK'))
vs = [d['version']] if 'version' in d else []
pkg = d.get('packages', {}).get('', {})
if isinstance(pkg, dict) and 'version' in pkg: vs.append(pkg['version'])
print('\n'.join(sorted(set(vs))))
" 2>/dev/null)
fi

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
PODSPEC="$REPO_ROOT/flutter/sceneview_flutter/ios/flutter_sceneview.podspec"
if [ -f "$PODSPEC" ]; then
    V=$(grep "s\.version" "$PODSPEC" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "flutter/.../ios/flutter_sceneview.podspec" "$V"
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

    # Prose version label, e.g. "Maven artifacts (version X.Y.Z):". DISTINCT from
    # the artifact-coordinate check above — a stale prose label slipped through to
    # 4.15.0 while the coordinates were 4.16.10 (caught only by the doc-audit, PR
    # #2336), because nothing matched the parenthesised label. Only checked when
    # the label is present, so rewording it never breaks the gate.
    V=$(grep -m1 -oE '\(version [0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?\)' "$LLMS" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
    [ -n "$V" ] && add_check "llms.txt (prose version label)" "$V"
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

# Module.md files — Dokka-rendered module landing pages.
# Pre-#1848 this check grepped the first version number in the file, which
# happens to be the Maven coordinate, but the match was UNANCHORED so a
# future version literal elsewhere in the prose would silently shadow the
# coordinate. ERROR-level (not WARN) + anchored to `io.github.sceneview:NAME:`
# so the check fails loudly if the install snippet drifts (issue #1848).
for modmd in sceneview/Module.md arsceneview/Module.md; do
    F="$REPO_ROOT/$modmd"
    if [ -f "$F" ]; then
        V=$(grep -m1 'io\.github\.sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$modmd" "$V"
        fi
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

# docs/docs/llms.txt — build-generated, NOT committed. It is regenerated from
# root `llms.txt` at docs-build time and `.gitignore`d (issue #899 hardening),
# so it carries the same version as root `llms.txt` by construction and needs
# no check. The `[ -f ]` guard below skips it cleanly when it is absent (the
# committed state); if a local docs build has left a working copy, it is still
# checked WARN-only so a stale build artefact never fails the gate.
DOCS_LLMS="$REPO_ROOT/docs/docs/llms.txt"
if [ -f "$DOCS_LLMS" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_LLMS" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/llms.txt (build artefact)" "$V" "false"
    fi
fi

# ─── 5b. docs/docs/llms.txt is build-generated (issue #899) ─────────────
# `docs/docs/llms.txt` used to be a committed byte-for-byte mirror of root
# `llms.txt` that this section diffed and `--fix`-copied. As of the CI-drift
# hardening it is NO LONGER COMMITTED: it is `.gitignore`d and regenerated
# from root `llms.txt` at docs-build time by `.github/workflows/docs.yml`
# (the "Mirror root llms.txt into docs" step, before `mkdocs build`). Being a
# fresh copy on every build it is fresh-by-construction and cannot drift, so
# there is nothing to diff or fix here — the same "no committed generated
# artefact" decision as #1928 (`mcp/src/generated/llms-txt.ts`).
#
# `check-llms-drift.sh` (wired into `quality-gate.sh`) now enforces the
# structural invariant — that `docs/docs/llms.txt` stays untracked — so a
# committed copy can never silently reappear.

# ─── 5b-bis. MCP generated bundle (issue #1928) ───────────────────────────
# `mcp/src/generated/llms-txt.ts` used to be a committed artefact that this
# section regenerated from root `llms.txt`. As of issue #1928 it is no longer
# committed — it is generated at build time by `mcp/scripts/generate-llms-txt.js`
# (wired into the `prebuild` / `prepare` / `test` npm lifecycle scripts) and
# `.gitignore`d. Being fresh-by-construction on every build, publish and test
# run, it cannot drift from root `llms.txt`, so no check or `--fix` is needed.

# ─── 5c. Free-form docs version refs missed by the Maven-coordinate scans (#1693) ──
# The section-5 docfile loop only matches `io.github.sceneview:sceneview:X.Y.Z`
# lines. Several docs carry the release version in prose / HTML stats that the
# Maven scan never sees, so v4.10.0 shipped with these stale (#1693):
#   * docs/docs/index.md   — landing-page "Latest Release" stat <span> (was v4.4.0)
#   * docs/docs/llms-full.txt — "**SceneView:** X.Y.Z" prose line (was 4.0.9)
# Both are auto-fixed below in the fix-mode block.
DOCS_INDEX="$REPO_ROOT/docs/docs/index.md"
if [ -f "$DOCS_INDEX" ]; then
    V=$(grep -A1 'sv-stat-number' "$DOCS_INDEX" | grep -B1 'Latest Release' \
        | grep -oE 'v?[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/index.md (Latest Release stat)" "$V"
    fi
fi
DOCS_LLMS_FULL="$REPO_ROOT/docs/docs/llms-full.txt"
if [ -f "$DOCS_LLMS_FULL" ]; then
    V=$(grep -m1 -E '^\s*-\s*\*\*SceneView:\*\*' "$DOCS_LLMS_FULL" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/llms-full.txt (SceneView prose)" "$V"
    fi
fi
# docs/docs/faq.md — "SceneViewSwift (vX.Y.Z)" prose. No Maven coordinate and
# no SPM `from:` URL on the line, so every other scan misses it (#1693).
DOCS_FAQ="$REPO_ROOT/docs/docs/faq.md"
if [ -f "$DOCS_FAQ" ]; then
    V=$(grep -m1 -oE 'SceneViewSwift \(v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$DOCS_FAQ" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/faq.md (SceneViewSwift prose)" "$V"
    fi
fi
# iOS codelabs — the SPM "version rule … from `X.Y.Z`" instruction sits on a
# prose-backtick line separate from the package URL, so the section-10b SPM
# `from:` scan (which requires `sceneview/sceneview` on the same line) misses
# it. Match the backtick-quoted version after a `from` keyword instead (#1693).
for codelab in docs/docs/codelabs/codelab-3d-swiftui.md docs/docs/codelabs/codelab-ar-swiftui.md; do
    F="$REPO_ROOT/$codelab"
    if [ -f "$F" ]; then
        V=$(grep -m1 -E 'from .?`[0-9]+\.[0-9]+\.[0-9]+' "$F" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$codelab (SPM version rule)" "$V"
        fi
    fi
done

# Flutter snippet inside llms.txt (`flutter_sceneview: ^X.Y.Z`) — separate
# from the maven `sceneview:` line, so the existing -m1 check misses it.
# NOTE (#2735 rename): must grep the pub-form `flutter_sceneview:` line —
# the legacy `sceneview_flutter:` string still appears in llms.txt as the
# git-pin dependency key (versionless line), which would silently skip
# this check.
if [ -f "$LLMS" ]; then
    V=$(grep -m1 'flutter_sceneview:' "$LLMS" | grep -oE '\^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | sed 's/^\^//' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "llms.txt (flutter snippet)" "$V"
    fi
fi

# ─── 6. Android demo app ────────────────────────────────────────────────
# The Play Store demo app's `versionName` MUST track VERSION_NAME — it is the
# user-visible release version on Google Play. Checked CRITICAL so it can
# never silently drift again (it lagged 4 releases behind at 4.9.0 because it
# was only WARN-level before — see the CLAUDE.md Version Location Map).
# `versionCode` is intentionally NOT checked: CI injects it via -PversionCode.
echo -e "${CYAN}--- Sample Apps ---${NC}"
DEMO_GRADLE="$REPO_ROOT/samples/android-demo/build.gradle"
if [ -f "$DEMO_GRADLE" ]; then
    V=$(grep "versionName" "$DEMO_GRADLE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "samples/android-demo versionName" "$V"
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

# samples/ios-demo Xcode project — MARKETING_VERSION drives CFBundleShortVersionString,
# i.e. the marketing version the iOS AND macOS App Store records carry (the single
# SceneViewDemo target ships to both — SUPPORTED_PLATFORMS includes macosx). It was a
# release blind spot: the pipeline only bumped CURRENT_PROJECT_VERSION (the build
# number), so every build since v4.9.0 reported marketing version 4.9.0 to the App
# Store (issue #2085, surfaced by an App Store Connect audit). Bump this in lockstep
# with the Android demo versionName + AboutView.swift.
#
# The 3-component `[0-9]+\.[0-9]+\.[0-9]+` regex deliberately skips the test target's
# `MARKETING_VERSION = 1.0` (2 components) — test bundles are not shipped, so their
# placeholder 1.0 must NOT be flagged or rewritten. Every shipping (3-component)
# MARKETING_VERSION line is checked, not just the first, so a partial drift between
# the Debug and Release configs is still caught.
IOS_DEMO_PBXPROJ="$REPO_ROOT/samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj"
if [ -f "$IOS_DEMO_PBXPROJ" ]; then
    while IFS= read -r V; do
        [ -n "$V" ] && add_check "samples/ios-demo MARKETING_VERSION" "$V" "false"
    done < <(grep -oE 'MARKETING_VERSION = [0-9]+\.[0-9]+\.[0-9]+' "$IOS_DEMO_PBXPROJ" \
        | awk '{print $NF}' | sort -u)
fi

# ─── 9. Website (static) ────────────────────────────────────────────────
echo -e "${CYAN}--- Website Static ---${NC}"
WEBSITE_INDEX="$REPO_ROOT/website-static/index.html"
if [ -f "$WEBSITE_INDEX" ]; then
    V=$(grep 'softwareVersion' "$WEBSITE_INDEX" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    add_check "website-static/index.html (softwareVersion)" "$V"
fi

# Guard (#2562): a version string must NEVER appear inside SVG path data.
# The historical unescaped-dot regex sweep injected versions into arc
# commands (`a4 4 0` -> `a4.16.2 0`), and once corrupted, every later
# escaped literal replace faithfully re-bumped the corruption
# (4.16.2 -> 4.17.0 -> 4.18.0). Checking for the CURRENT version inside any
# `d="..."` attribute is deterministic (legit path data never contains it)
# and catches the whole class the moment it reappears.
SVG_CORRUPTED=$(grep -rlE "d=\"[^\"]*${SOURCE_VERSION//./\\.}" "$REPO_ROOT/website-static" --include="*.html" 2>/dev/null || true)
if [ -n "$SVG_CORRUPTED" ]; then
    add_check "website-static SVG path data (version inside d= in: $(echo "$SVG_CORRUPTED" | tr '\n' ' '))" "CORRUPTED"
else
    add_check "website-static SVG path data (no version inside d=)" "$SOURCE_VERSION"
fi

# Website surfaces that drifted in #2564 — now pinned so they can't rot again.
# iOS install-snippet comment on the homepage (`// Version: X.Y.Z`).
if [ -f "$WEBSITE_INDEX" ]; then
    V=$(grep -oE '// Version: [0-9]+\.[0-9]+\.[0-9]+' "$WEBSITE_INDEX" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
    add_check "website-static/index.html (iOS snippet // Version:)" "$V"
fi
# web.html JSON-LD softwareVersion (SEO structured data).
WEBSITE_WEB="$REPO_ROOT/website-static/web.html"
if [ -f "$WEBSITE_WEB" ]; then
    V=$(grep 'softwareVersion' "$WEBSITE_WEB" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
    add_check "website-static/web.html (softwareVersion)" "$V"
fi
# Playground AI-prompt coordinates (arsceneview + sceneview-web@).
WEBSITE_PLAYGROUND="$REPO_ROOT/website-static/playground.html"
if [ -f "$WEBSITE_PLAYGROUND" ]; then
    V=$(grep -oE 'arsceneview:[0-9]+\.[0-9]+\.[0-9]+' "$WEBSITE_PLAYGROUND" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
    add_check "website-static/playground.html (prompt arsceneview:)" "$V"
    V=$(grep -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+' "$WEBSITE_PLAYGROUND" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "NOT FOUND")
    add_check "website-static/playground.html (prompt sceneview-web@)" "$V"
fi
# `js/sceneview.js?v=X.Y.Z` cache-busters — a stale ?v= keeps serving the
# previous script from the HTTP cache after a deploy.
STALE_BUSTERS=$(grep -rhoE 'sceneview\.js\?v=[0-9]+\.[0-9]+\.[0-9]+' "$REPO_ROOT/website-static" --include="*.html" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u | grep -v "^${SOURCE_VERSION}$" || true)
if [ -n "$STALE_BUSTERS" ]; then
    add_check "website-static *.html (sceneview.js?v= cache-busters)" "$(echo "$STALE_BUSTERS" | head -1)"
else
    add_check "website-static *.html (sceneview.js?v= cache-busters)" "$SOURCE_VERSION"
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
# inline-JS path is the web demo's single runtime (the dead Kotlin/JS
# source set was removed in #1946) and it drives the auto-update snackbar,
# so it has to stay in sync with VERSION_NAME.
WEB_DEMO_INDEX="$REPO_ROOT/samples/web-demo/site/index.html"
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
    docs/docs/faq.md
    # iOS codelabs ship an SPM "version rule … from `X.Y.Z`" instruction in
    # prose-backtick form; #1693 found them 6 minors stale after v4.10.0.
    docs/docs/codelabs/codelab-3d-swiftui.md
    docs/docs/codelabs/codelab-ar-swiftui.md
    # NOTE: `docs/docs/llms.txt` is intentionally NOT listed — it is no longer
    # committed. It is regenerated from root `llms.txt` (already swept above)
    # at docs-build time and `.gitignore`d (issue #899 hardening), so its SPM
    # snippet is fresh by construction.
    website-static/.well-known/llms.txt
    website-static/playground.html
    .github/copilot-instructions.md
    gpt/system-prompt.md
    # AI-assistant prompt surfaces in .gitignore'd dirs — tracked files
    # that the release sweep kept missing (drift fixed by hand in #1217
    # and again in #1262). Listed explicitly so `--fix` keeps them in sync.
    pro/gpt-store/gpt-instructions.md
    marketing/stackoverflow/qa-drafts.md
    # Agent skills under agents/* — installed into `~/.android/cli/skills/xr/`
    # by `bash .claude/scripts/install-sceneview-*-skill.sh`. The Android skill
    # SPM tag prose is checked explicitly in section 14; the iOS skill ships a
    # canonical `.package(... from: "X.Y.Z")` snippet that fits cleanly into
    # this sweep (#1848).
    agents/sceneview-ios/SKILL.md
    agents/sceneview-ios/references/migration.md
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
               website-static/llms-full.txt \
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
# bundle. This is a public `@JsExport`-reachable code constant; web consumers
# querying the library version get whatever this literal says, so silent drift
# is a real defect (it lagged 2 majors before #1357). Hard MISMATCH check —
# never demote back to WARN-only.
SCENEVIEWJS_KT=$(find "$REPO_ROOT/sceneview-web" -name 'SceneViewJS.kt' 2>/dev/null | head -1 || true)
if [ -n "$SCENEVIEWJS_KT" ] && [ -f "$SCENEVIEWJS_KT" ]; then
    V=$(grep -E 'SCENEVIEW_VERSION' "$SCENEVIEWJS_KT" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"' | tr -d '"' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "sceneview-web SceneViewJS.kt SCENEVIEW_VERSION" "$V"
    fi
fi

# SceneViewVersionTest.kt — the jsTest regression pin for the constant above.
# `versionIsCurrent()` asserts `assertEquals("X.Y.Z", SCENEVIEW_VERSION)` with
# a HARDCODED literal. Before the CI-drift hardening NEITHER `SceneViewJS.kt`
# nor this test had an auto-`--fix` handler — they were check-only — so after
# a release bump the SDK shipped a stale `SCENEVIEW_VERSION` and the
# `:sceneview-web:jsTest` job went red on an otherwise-clean release PR. The
# `--fix` block below now rewrites BOTH the constant and this pin in lockstep
# with VERSION_NAME, closing the drift trap.
SCENEVIEWJS_VERSION_TEST=$(find "$REPO_ROOT/sceneview-web" -name 'SceneViewVersionTest.kt' 2>/dev/null | head -1 || true)
if [ -n "$SCENEVIEWJS_VERSION_TEST" ] && [ -f "$SCENEVIEWJS_VERSION_TEST" ]; then
    V=$(grep -E 'assertEquals\("[0-9]+\.[0-9]+\.[0-9]+' "$SCENEVIEWJS_VERSION_TEST" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"' | tr -d '"' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "sceneview-web SceneViewVersionTest.kt pin" "$V"
    fi
fi

# Kotlin toolchain version — gradle/libs.versions.toml `kotlin = "X.Y.Z"` is
# the source of truth; llms.txt + llms-full.txt quote it in prose and drift.
# Reported under a separate "Kotlin" banner since it's not VERSION_NAME.
# `docs/docs/llms.txt` is omitted — it is build-generated from root `llms.txt`
# (swept here) and `.gitignore`d (issue #899 hardening).
KOTLIN_TOML=$(grep -m1 '^kotlin = ' "$REPO_ROOT/gradle/libs.versions.toml" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
if [ -n "$KOTLIN_TOML" ]; then
    for kfile in llms.txt docs/docs/llms-full.txt; do
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

# ─── 13. Extra off-map refs surfaced by the v4.11.0 audit (#1755) ─────────
# More files that sat outside every previous scan and shipped stale through
# v4.11.0. Wired in here so a future release catches them via --fix.
echo -e "${CYAN}--- Off-map Version Refs (#1755 audit) ---${NC}"

# samples/web-demo/package.json — Playwright device-QA suite package.json.
# Distinct from the demo's own `site/index.html` BUILD_VERSION; this is the
# Node test harness manifest and must track VERSION_NAME so the QA suite
# advertises the same version as the demo it tests.
WEB_DEMO_TESTS_PKG="$REPO_ROOT/samples/web-demo/package.json"
if [ -f "$WEB_DEMO_TESTS_PKG" ]; then
    V=$(python3 -c "import json; print(json.load(open('$WEB_DEMO_TESTS_PKG'))['version'])" 2>/dev/null || echo "MISSING")
    add_check "samples/web-demo/package.json" "$V"
fi

# samples/web-demo/package-lock.json — the lockfile sibling of the package.json
# above. `npm install` rewrites the lock's two version slots (root `.version`
# and `.packages[""].version`) to match package.json, so a stale lock (it
# shipped 4.12.0 while package.json was 4.17.0) makes a plain `npm install`
# dirty the working tree in CI / device-QA. Track both slots so a partial drift
# between them is still caught, the same way MARKETING_VERSION is swept per
# occurrence above.
WEB_DEMO_TESTS_LOCK="$REPO_ROOT/samples/web-demo/package-lock.json"
if [ -f "$WEB_DEMO_TESTS_LOCK" ]; then
    while IFS= read -r V; do
        [ -n "$V" ] && add_check "samples/web-demo/package-lock.json" "$V"
    done < <(python3 -c "
import json
d = json.load(open('$WEB_DEMO_TESTS_LOCK'))
vs = [d['version']] if 'version' in d else []
pkg = d.get('packages', {}).get('', {})
if isinstance(pkg, dict) and 'version' in pkg: vs.append(pkg['version'])
print('\n'.join(sorted(set(vs))))
" 2>/dev/null)
fi

# website-static/.well-known/llms.txt — the "(version X.Y.Z)" prose label on
# the Maven artifacts line. Distinct from the artifact coordinate scan above
# which catches the next two lines (`io.github.sceneview:sceneview:X.Y.Z`).
# Also catches the `sceneview-web` prose `vX.Y.Z` references further down.
WELLKNOWN_LLMS="$REPO_ROOT/website-static/.well-known/llms.txt"
if [ -f "$WELLKNOWN_LLMS" ]; then
    V=$(grep -m1 -oE 'Maven artifacts \(version [0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?\)' "$WELLKNOWN_LLMS" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check ".well-known/llms.txt (Maven prose)" "$V"
    fi
    # The two `sceneview-web vX.Y.Z` prose refs further down the file.
    V=$(grep -m1 -oE 'sceneview-web` v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$WELLKNOWN_LLMS" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check ".well-known/llms.txt (sceneview-web prose)" "$V"
    fi
fi

# ROADMAP.md — "## Current: vX.Y.Z stable" header + the "GitHub Release"
# table row both encode the latest release version. Was 5 minors stale at
# the v4.11.0 audit despite the doc itself instructing "must be refreshed
# at every release".
ROADMAP_MD="$REPO_ROOT/ROADMAP.md"
if [ -f "$ROADMAP_MD" ]; then
    V=$(grep -m1 -oE '^## Current: v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)? ' "$ROADMAP_MD" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "ROADMAP.md (Current version)" "$V"
    fi
    V=$(grep -m1 -oE 'GitHub Release \| \*\*v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)? stable\*\*' "$ROADMAP_MD" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "ROADMAP.md (GitHub Release row)" "$V"
    fi
fi

# sceneview-web/README.md — the "sceneview.js vX.Y.Z" header + CDN URL
# `sceneview-web@X.Y.Z/sceneview.js`. The Section-12 CDN scan covers
# README.md and docs/docs/index.md but not this package-local README, which
# sat at 3.6.0 for ~6 minor releases until the v4.11.0 audit caught it.
SCENEVIEW_WEB_README="$REPO_ROOT/sceneview-web/README.md"
if [ -f "$SCENEVIEW_WEB_README" ]; then
    V=$(grep -m1 -oE 'sceneview\.js v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$SCENEVIEW_WEB_README" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "sceneview-web/README.md (sceneview.js header)" "$V"
    fi
    V=$(grep -m1 -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$SCENEVIEW_WEB_README" \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "sceneview-web/README.md (CDN @version)" "$V"
    fi
fi

# ─── 14. Derived doc surfaces missed by every previous scan (#1848) ────────
# Tier-2 Wave 4 DOCS review surfaced five doc surfaces sitting outside every
# previous scan and stale on `main` for multiple minors:
#   * docs/docs/manifest.json — PWA manifest `related_applications[].id`
#     Maven coordinate, was 4.4.0.
#   * docs/docs/structured-data.json — JSON-LD softwareVersion + releaseNotes
#     URL + two Maven coordinate prose mentions, was 3.6.2 / 4.4.0.
#   * agents/sceneview/SKILL.md + references/cheatsheet.md + references/migration.md
#     — Android skill artifact coordinates and SPM/npm refs, were 4.3.1.
#   * agents/sceneview-ios/SKILL.md + references/migration.md — SPM `from:`
#     and SPM-tag prose ref, were 4.3.5.
#   * agents/sceneview-web/SKILL.md + references/cheatsheet.md + references/recipes.md
#     — npm prose + jsdelivr CDN @version pin, were 4.3.5.
# AI agents / MCP / skill consumers reading these get broken install snippets
# pointing at a non-current Maven coordinate. ERROR-level checks below; future
# drift fails the gate, --fix sweep rewrites them.
echo -e "${CYAN}--- Derived Doc Surfaces (#1848) ---${NC}"

# docs/docs/manifest.json — PWA manifest's `related_applications[].id`.
DOCS_MANIFEST="$REPO_ROOT/docs/docs/manifest.json"
if [ -f "$DOCS_MANIFEST" ]; then
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_MANIFEST" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/manifest.json (related_applications id)" "$V"
    fi
fi

# docs/docs/structured-data.json — JSON-LD softwareVersion + releaseNotes URL
# + Maven coordinate prose. Multiple distinct version slots that all must
# track VERSION_NAME.
DOCS_STRUCTURED="$REPO_ROOT/docs/docs/structured-data.json"
if [ -f "$DOCS_STRUCTURED" ]; then
    V=$(grep -m1 '"softwareVersion"' "$DOCS_STRUCTURED" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/structured-data.json (softwareVersion)" "$V"
    fi
    V=$(grep -m1 '"releaseNotes"' "$DOCS_STRUCTURED" | grep -oE 'tag/v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/structured-data.json (releaseNotes tag)" "$V"
    fi
    V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_STRUCTURED" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "docs/docs/structured-data.json (Maven coordinate)" "$V"
    fi
fi

# agents/sceneview/* — Android skill: Maven artifact coordinates, SPM tag
# prose, npm sceneview-web prose, and @sceneview-sdk/react-native prose. All
# checked against VERSION_NAME.
for agentf in agents/sceneview/SKILL.md agents/sceneview/references/cheatsheet.md agents/sceneview/references/migration.md; do
    F="$REPO_ROOT/$agentf"
    if [ -f "$F" ]; then
        V=$(grep -m1 'io\.github\.sceneview:sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$agentf (Maven coordinate)" "$V"
        fi
    fi
done

# agents/sceneview/SKILL.md — additional npm + SPM tag prose lines that the
# Maven-coordinate scan doesn't reach.
AGENT_ANDROID_SKILL="$REPO_ROOT/agents/sceneview/SKILL.md"
if [ -f "$AGENT_ANDROID_SKILL" ]; then
    V=$(grep -m1 'sceneview-web@' "$AGENT_ANDROID_SKILL" | grep -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "agents/sceneview/SKILL.md (sceneview-web@)" "$V"
    fi
    V=$(grep -m1 '@sceneview-sdk/react-native@' "$AGENT_ANDROID_SKILL" | grep -oE '@sceneview-sdk/react-native@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "agents/sceneview/SKILL.md (@sceneview-sdk/react-native@)" "$V"
    fi
    V=$(grep -m1 -E 'tag `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_ANDROID_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "agents/sceneview/SKILL.md (SPM tag prose)" "$V"
    fi
fi

# agents/sceneview-ios/* — Apple skill: SPM `from:` is already covered by the
# section-10b SPM_FILES sweep IF we add the files there. Add them, plus a
# direct ERROR-level check on the prose "version tag (currently `X.Y.Z`)" line.
AGENT_IOS_SKILL="$REPO_ROOT/agents/sceneview-ios/SKILL.md"
if [ -f "$AGENT_IOS_SKILL" ]; then
    V=$(grep -m1 -E 'version tag \(currently `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_IOS_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "agents/sceneview-ios/SKILL.md (SPM tag prose)" "$V"
    fi
fi

# agents/sceneview-web/* — Web skill: jsdelivr CDN @version pin + "currently
# `X.Y.Z`" prose line.
for webskill in agents/sceneview-web/SKILL.md agents/sceneview-web/references/cheatsheet.md agents/sceneview-web/references/recipes.md; do
    F="$REPO_ROOT/$webskill"
    if [ -f "$F" ]; then
        V=$(grep -m1 'sceneview-web@' "$F" | grep -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
        if [ "$V" != "NOT FOUND" ]; then
            add_check "$webskill (CDN @version)" "$V"
        fi
    fi
done
AGENT_WEB_SKILL="$REPO_ROOT/agents/sceneview-web/SKILL.md"
if [ -f "$AGENT_WEB_SKILL" ]; then
    V=$(grep -m1 -E 'sceneview-web. \(currently `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_WEB_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "NOT FOUND")
    if [ "$V" != "NOT FOUND" ]; then
        add_check "agents/sceneview-web/SKILL.md (npm prose)" "$V"
    fi
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

    # Fix react-native/react-native-sceneview/package-lock.json — keep the RN
    # library lockfile's two version slots in lockstep with its package.json.
    # Byte-for-byte-npm round-trip (verified), same pattern as the web-demo lock
    # fix; only the version strings change, every other field round-trips.
    if [ -f "$RN_LIB_LOCK" ]; then
        CHANGED=$(python3 -c "
import json
p = '$RN_LIB_LOCK'
src = '$SOURCE_VERSION'
with open(p) as f:
    data = json.load(f)
old = ''
changed = False
if data.get('version') != src:
    old = data.get('version', ''); data['version'] = src; changed = True
pkg = data.get('packages', {}).get('')
if isinstance(pkg, dict) and pkg.get('version') != src:
    if not old: old = pkg.get('version', '')
    pkg['version'] = src; changed = True
if changed:
    with open(p, 'w') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print(old)
" 2>/dev/null || echo "")
        [ -n "$CHANGED" ] && echo -e "  Fixed: react-native/.../package-lock.json ($CHANGED -> $SOURCE_VERSION)"
    fi

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
            echo -e "  Fixed: flutter/.../ios/flutter_sceneview.podspec ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix Android demo app versionName (Play Store user-visible version).
    if [ -f "$DEMO_GRADLE" ]; then
        CURRENT=$(grep "versionName" "$DEMO_GRADLE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1)
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/versionName \"$CURRENT\"/versionName \"$SOURCE_VERSION\"/" "$DEMO_GRADLE"
            echo -e "  Fixed: samples/android-demo/build.gradle versionName ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # Fix samples/ios-demo MARKETING_VERSION (iOS + macOS App Store marketing
    # version — issue #2085). Rewrites every shipping (3-component) occurrence,
    # so both the Debug and Release configs of the SceneViewDemo target are
    # swept; the test target's 2-component `MARKETING_VERSION = 1.0` placeholder
    # is left untouched because the regex anchors on three components.
    if [ -f "$IOS_DEMO_PBXPROJ" ]; then
        for CURRENT in $(grep -oE 'MARKETING_VERSION = [0-9]+\.[0-9]+\.[0-9]+' "$IOS_DEMO_PBXPROJ" \
            | awk '{print $NF}' | sort -u); do
            if [ "$CURRENT" != "$SOURCE_VERSION" ]; then
                _sed_inplace "s/MARKETING_VERSION = $CURRENT;/MARKETING_VERSION = $SOURCE_VERSION;/g" "$IOS_DEMO_PBXPROJ"
                echo -e "  Fixed: samples/ios-demo MARKETING_VERSION ($CURRENT -> $SOURCE_VERSION)"
            fi
        done
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
        # Escape OLD_V's dots before using it as a regex (defense-in-depth):
        # an unescaped `4.3.0` is the BRE `4`+any+`3`+any+`0`, which can
        # wildcard-match unintended substrings. The replacement side stays
        # $SOURCE_VERSION (a plain semver, never used as a pattern).
        OLD_V_RE="${OLD_V//./\\.}"
        # Fix docs that contain Maven artifact version refs
        for docfile in llms.txt README.md CLAUDE.md docs/docs/index.md docs/docs/quickstart.md docs/docs/llms-full.txt docs/docs/cheatsheet.md docs/docs/platforms.md docs/docs/migration.md docs/docs/android-xr.md; do
            F="$REPO_ROOT/$docfile"
            if [ -f "$F" ] && grep -q "io\.github\.sceneview:.*$OLD_V_RE" "$F" 2>/dev/null; then
                _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$OLD_V_RE/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $docfile (artifact refs $OLD_V -> $SOURCE_VERSION)"
            fi
        done
    done

    # Fix free-form docs version refs missed by the Maven-coordinate sweep (#1693).
    # Each substitution is scoped to its exact surrounding text so an unrelated
    # version string elsewhere in the file is never touched.
    #   docs/docs/index.md — the "Latest Release" stat <span>
    if [ -f "$DOCS_INDEX" ]; then
        CURRENT=$(grep -A1 'sv-stat-number' "$DOCS_INDEX" | grep -B1 'Latest Release' \
            | grep -oE 'v?[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "v$SOURCE_VERSION" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s|<span class=\"sv-stat-number\">$CURRENT</span>|<span class=\"sv-stat-number\">v$SOURCE_VERSION</span>|" "$DOCS_INDEX"
            echo -e "  Fixed: docs/docs/index.md (Latest Release stat $CURRENT -> v$SOURCE_VERSION)"
        fi
    fi
    #   docs/docs/llms-full.txt — the "**SceneView:** X.Y.Z" prose line
    if [ -f "$DOCS_LLMS_FULL" ]; then
        CURRENT=$(grep -m1 -E '^\s*-\s*\*\*SceneView:\*\*' "$DOCS_LLMS_FULL" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/\(\*\*SceneView:\*\* \)$CURRENT/\1$SOURCE_VERSION/" "$DOCS_LLMS_FULL"
            echo -e "  Fixed: docs/docs/llms-full.txt (SceneView prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi
    #   docs/docs/faq.md — the "SceneViewSwift (vX.Y.Z)" prose ref
    if [ -f "$DOCS_FAQ" ]; then
        CURRENT=$(grep -m1 -oE 'SceneViewSwift \(v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$DOCS_FAQ" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/SceneViewSwift (v$CURRENT/SceneViewSwift (v$SOURCE_VERSION/g" "$DOCS_FAQ"
            echo -e "  Fixed: docs/docs/faq.md (SceneViewSwift prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi
    #   iOS codelabs — the SPM "version rule … from `X.Y.Z`" backtick instruction
    for codelab in docs/docs/codelabs/codelab-3d-swiftui.md docs/docs/codelabs/codelab-ar-swiftui.md; do
        F="$REPO_ROOT/$codelab"
        [ -f "$F" ] || continue
        CURRENT=$(grep -m1 -E 'from .?`[0-9]+\.[0-9]+\.[0-9]+' "$F" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/from \`$CURRENT\`/from \`$SOURCE_VERSION\`/g" "$F"
            echo -e "  Fixed: $codelab (SPM version rule $CURRENT -> $SOURCE_VERSION)"
        fi
    done

    # Fix website-static/index.html version refs. This is the ONLY unanchored
    # blanket version sweep, so OLD_V's dots MUST be escaped before it is used
    # as a regex by grep and sed: an unescaped `4.3.0` is the BRE `4`+any+`3`+
    # any+`0`, which matched the SVG-logo coordinate substring `4 390` and
    # rewrote `390,194 390,340` to `390,194.17.0,340`, corrupting the markup.
    # Escaping restricts the match to a literal version string.
    if [ -f "$WEBSITE_INDEX" ]; then
        for OLD_V in $OLD_VERSIONS; do
            [ "$OLD_V" = "$SOURCE_VERSION" ] && continue
            OLD_V_RE="${OLD_V//./\\.}"
            if grep -qE "$OLD_V_RE" "$WEBSITE_INDEX" 2>/dev/null; then
                _sed_inplace "s/$OLD_V_RE/$SOURCE_VERSION/g" "$WEBSITE_INDEX"
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

    # Fix sceneview-web SCENEVIEW_VERSION constant + its jsTest pin
    # (CI-drift hardening). The constant is a public `@JsExport`-reachable
    # value; the test `assertEquals("X.Y.Z", SCENEVIEW_VERSION)` pins it. Both
    # carried a hardcoded literal with no `--fix` handler, so a release bump
    # left the SDK shipping a stale version and reddened `:sceneview-web:jsTest`.
    # Each substitution is scoped to the exact surrounding text so no unrelated
    # version string in the file is touched.
    if [ -n "$SCENEVIEWJS_KT" ] && [ -f "$SCENEVIEWJS_KT" ]; then
        CURRENT=$(grep -E 'SCENEVIEW_VERSION' "$SCENEVIEWJS_KT" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"' | tr -d '"' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/SCENEVIEW_VERSION = \"$CURRENT\"/SCENEVIEW_VERSION = \"$SOURCE_VERSION\"/" "$SCENEVIEWJS_KT"
            echo -e "  Fixed: sceneview-web SceneViewJS.kt SCENEVIEW_VERSION ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi
    if [ -n "$SCENEVIEWJS_VERSION_TEST" ] && [ -f "$SCENEVIEWJS_VERSION_TEST" ]; then
        CURRENT=$(grep -E 'assertEquals\("[0-9]+\.[0-9]+\.[0-9]+' "$SCENEVIEWJS_VERSION_TEST" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?"' | tr -d '"' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/assertEquals(\"$CURRENT\", SCENEVIEW_VERSION)/assertEquals(\"$SOURCE_VERSION\", SCENEVIEW_VERSION)/" "$SCENEVIEWJS_VERSION_TEST"
            echo -e "  Fixed: sceneview-web SceneViewVersionTest.kt pin ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

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
        # Escape OLD_V's dots before regex use (see note in the docs-files sweep above).
        OLD_V_RE="${OLD_V//./\\.}"
        for f in docs/docs/ai-context.md docs/docs/android-xr-emulator.md \
                 docs/docs/nodes.md docs/docs/comparison.md docs/docs/quickstart-tv.md \
                 docs/docs/codelabs/codelab-3d-compose.md docs/docs/codelabs/codelab-ar-compose.md \
                 website-static/index.html website-static/web.html \
                 website-static/geometry-demo.html website-static/playground.html \
                 website-static/llms-full.txt \
                 website-static/.well-known/llms.txt; do
            F="$REPO_ROOT/$f"
            if [ -f "$F" ] && grep -q "io\.github\.sceneview:[^:]*:$OLD_V_RE" "$F" 2>/dev/null; then
                _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$OLD_V_RE/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $f (artifact refs $OLD_V -> $SOURCE_VERSION)"
            fi
        done
        # README + docs badge URLs and CDN @version pins.
        for f in README.md docs/docs/index.md website-static/web.html; do
            F="$REPO_ROOT/$f"
            [ -f "$F" ] || continue
            if grep -qE "(badge/(Flutter|React%20Native)-v$OLD_V_RE|sceneview(-web)?@v?$OLD_V_RE|sceneview\.js\?v=$OLD_V_RE)" "$F" 2>/dev/null; then
                _sed_inplace "s/-v$OLD_V_RE/-v$SOURCE_VERSION/g; s/@v$OLD_V_RE/@v$SOURCE_VERSION/g; s/@$OLD_V_RE/@$SOURCE_VERSION/g; s/?v=$OLD_V_RE/?v=$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $f (badge/CDN $OLD_V -> $SOURCE_VERSION)"
            fi
        done
    done

    # ─── Fixes for off-map refs surfaced by the v4.11.0 audit (#1755) ──────

    # samples/web-demo/package.json — Playwright test suite manifest.
    if [ -f "$WEB_DEMO_TESTS_PKG" ]; then
        CURRENT=$(python3 -c "import json; print(json.load(open('$WEB_DEMO_TESTS_PKG'))['version'])" 2>/dev/null || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            python3 -c "
import json
with open('$WEB_DEMO_TESTS_PKG', 'r') as f:
    data = json.load(f)
data['version'] = '$SOURCE_VERSION'
with open('$WEB_DEMO_TESTS_PKG', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"
            echo -e "  Fixed: samples/web-demo/package.json ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # samples/web-demo/package-lock.json — keep the lockfile's two version slots
    # (root + packages[""]) in lockstep with package.json. Mutated + re-dumped via
    # python with `ensure_ascii=False` so the output is byte-for-byte what `npm
    # install` writes (verified), leaving the QA tree clean. Only the version
    # strings change; every other field is round-tripped untouched. The guard
    # fires when EITHER slot drifts so a partial drift still converges.
    if [ -f "$WEB_DEMO_TESTS_LOCK" ]; then
        CHANGED=$(python3 -c "
import json
p = '$WEB_DEMO_TESTS_LOCK'
src = '$SOURCE_VERSION'
with open(p) as f:
    data = json.load(f)
old = ''
changed = False
if data.get('version') != src:
    old = data.get('version', ''); data['version'] = src; changed = True
pkg = data.get('packages', {}).get('')
if isinstance(pkg, dict) and pkg.get('version') != src:
    if not old: old = pkg.get('version', '')
    pkg['version'] = src; changed = True
if changed:
    with open(p, 'w') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print(old)
" 2>/dev/null || echo "")
        [ -n "$CHANGED" ] && echo -e "  Fixed: samples/web-demo/package-lock.json ($CHANGED -> $SOURCE_VERSION)"
    fi

    # website-static/.well-known/llms.txt — Maven prose label + sceneview-web prose.
    if [ -f "$WELLKNOWN_LLMS" ]; then
        # "Maven artifacts (version X.Y.Z)" prose label.
        CURRENT=$(grep -m1 -oE 'Maven artifacts \(version [0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?\)' "$WELLKNOWN_LLMS" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/Maven artifacts (version $CURRENT)/Maven artifacts (version $SOURCE_VERSION)/g" "$WELLKNOWN_LLMS"
            echo -e "  Fixed: .well-known/llms.txt (Maven prose $CURRENT -> $SOURCE_VERSION)"
        fi
        # All `sceneview-web vX.Y.Z` prose references.
        CURRENT=$(grep -m1 -oE 'sceneview-web` v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$WELLKNOWN_LLMS" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/sceneview-web\` v$CURRENT/sceneview-web\` v$SOURCE_VERSION/g" "$WELLKNOWN_LLMS"
            # Also catches "(sceneview-web vX.Y.Z)" form without the backtick.
            _sed_inplace "s/(sceneview-web v$CURRENT)/(sceneview-web v$SOURCE_VERSION)/g" "$WELLKNOWN_LLMS"
            echo -e "  Fixed: .well-known/llms.txt (sceneview-web prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # ROADMAP.md — Current version header + GitHub Release row.
    if [ -f "$ROADMAP_MD" ]; then
        CURRENT=$(grep -m1 -oE '^## Current: v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)? ' "$ROADMAP_MD" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/^## Current: v$CURRENT /## Current: v$SOURCE_VERSION /" "$ROADMAP_MD"
            echo -e "  Fixed: ROADMAP.md (Current $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 -oE 'GitHub Release \| \*\*v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)? stable\*\*' "$ROADMAP_MD" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/GitHub Release | \*\*v$CURRENT stable\*\*/GitHub Release | **v$SOURCE_VERSION stable**/" "$ROADMAP_MD"
            echo -e "  Fixed: ROADMAP.md (GitHub Release row $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # sceneview-web/README.md — JS API header + CDN @version pin.
    if [ -f "$SCENEVIEW_WEB_README" ]; then
        CURRENT=$(grep -m1 -oE 'sceneview\.js v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$SCENEVIEW_WEB_README" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/sceneview\.js v$CURRENT/sceneview.js v$SOURCE_VERSION/g" "$SCENEVIEW_WEB_README"
            echo -e "  Fixed: sceneview-web/README.md (JS header $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' "$SCENEVIEW_WEB_README" \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/sceneview-web@$CURRENT/sceneview-web@$SOURCE_VERSION/g" "$SCENEVIEW_WEB_README"
            echo -e "  Fixed: sceneview-web/README.md (CDN @version $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # ─── Fixes for derived doc surfaces surfaced by #1848 ────────────────────

    # arsceneview/Module.md + sceneview/Module.md — Dokka module landing pages.
    # Already touched by the section "Fix docs files" Maven-coordinate sweep
    # IF the check status was MISMATCH (caught by OLD_VERSIONS). But that
    # sweep only iterates a hard-coded docfile list — Module.md isn't in it.
    # Wire it in here.
    for OLD_V in $OLD_VERSIONS; do
        [ "$OLD_V" = "$SOURCE_VERSION" ] && continue
        # Escape OLD_V's dots before regex use (see note in the docs-files sweep above).
        OLD_V_RE="${OLD_V//./\\.}"
        for modmd in sceneview/Module.md arsceneview/Module.md; do
            F="$REPO_ROOT/$modmd"
            if [ -f "$F" ] && grep -q "io\.github\.sceneview:.*$OLD_V_RE" "$F" 2>/dev/null; then
                _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$OLD_V_RE/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
                echo -e "  Fixed: $modmd (artifact ref $OLD_V -> $SOURCE_VERSION)"
            fi
        done
    done

    # docs/docs/manifest.json — PWA `related_applications[].id` Maven coordinate.
    if [ -f "$DOCS_MANIFEST" ]; then
        CURRENT=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_MANIFEST" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$CURRENT/io.github.sceneview:\1:$SOURCE_VERSION/g" "$DOCS_MANIFEST"
            echo -e "  Fixed: docs/docs/manifest.json ($CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # docs/docs/structured-data.json — JSON-LD softwareVersion + releaseNotes
    # + Maven prose. Three distinct slots, each fixed with its own scoped sub.
    if [ -f "$DOCS_STRUCTURED" ]; then
        CURRENT=$(grep -m1 '"softwareVersion"' "$DOCS_STRUCTURED" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/\"softwareVersion\": \"$CURRENT\"/\"softwareVersion\": \"$SOURCE_VERSION\"/" "$DOCS_STRUCTURED"
            echo -e "  Fixed: docs/docs/structured-data.json (softwareVersion $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 '"releaseNotes"' "$DOCS_STRUCTURED" | grep -oE 'tag/v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s|releases/tag/v$CURRENT|releases/tag/v$SOURCE_VERSION|" "$DOCS_STRUCTURED"
            echo -e "  Fixed: docs/docs/structured-data.json (releaseNotes tag $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 'io\.github\.sceneview:sceneview:' "$DOCS_STRUCTURED" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$CURRENT/io.github.sceneview:\1:$SOURCE_VERSION/g" "$DOCS_STRUCTURED"
            echo -e "  Fixed: docs/docs/structured-data.json (Maven coordinate $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # agents/sceneview/* — Android skill files. Maven coordinate + sceneview-web
    # + @sceneview-sdk/react-native + SPM tag prose, each scoped to its line shape.
    for agentf in agents/sceneview/SKILL.md agents/sceneview/references/cheatsheet.md agents/sceneview/references/migration.md; do
        F="$REPO_ROOT/$agentf"
        [ -f "$F" ] || continue
        CURRENT=$(grep -m1 'io\.github\.sceneview:sceneview:' "$F" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/io\.github\.sceneview:\([^:]*\):$CURRENT/io.github.sceneview:\1:$SOURCE_VERSION/g" "$F"
            echo -e "  Fixed: $agentf (Maven coordinate $CURRENT -> $SOURCE_VERSION)"
        fi
    done
    if [ -f "$AGENT_ANDROID_SKILL" ]; then
        CURRENT=$(grep -m1 'sceneview-web@' "$AGENT_ANDROID_SKILL" | grep -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/sceneview-web@$CURRENT/sceneview-web@$SOURCE_VERSION/g" "$AGENT_ANDROID_SKILL"
            echo -e "  Fixed: agents/sceneview/SKILL.md (sceneview-web@ $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 '@sceneview-sdk/react-native@' "$AGENT_ANDROID_SKILL" | grep -oE '@sceneview-sdk/react-native@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/@sceneview-sdk\/react-native@$CURRENT/@sceneview-sdk\/react-native@$SOURCE_VERSION/g" "$AGENT_ANDROID_SKILL"
            echo -e "  Fixed: agents/sceneview/SKILL.md (@sceneview-sdk/react-native@ $CURRENT -> $SOURCE_VERSION)"
        fi
        CURRENT=$(grep -m1 -E 'tag `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_ANDROID_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/tag \`$CURRENT\`/tag \`$SOURCE_VERSION\`/" "$AGENT_ANDROID_SKILL"
            echo -e "  Fixed: agents/sceneview/SKILL.md (SPM tag prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # agents/sceneview-ios/SKILL.md — "version tag (currently `X.Y.Z`)" prose.
    if [ -f "$AGENT_IOS_SKILL" ]; then
        CURRENT=$(grep -m1 -E 'version tag \(currently `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_IOS_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/version tag (currently \`$CURRENT\`)/version tag (currently \`$SOURCE_VERSION\`)/" "$AGENT_IOS_SKILL"
            echo -e "  Fixed: agents/sceneview-ios/SKILL.md (SPM tag prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # agents/sceneview-web/* — CDN @version + "currently `X.Y.Z`" prose.
    for webskill in agents/sceneview-web/SKILL.md agents/sceneview-web/references/cheatsheet.md agents/sceneview-web/references/recipes.md; do
        F="$REPO_ROOT/$webskill"
        [ -f "$F" ] || continue
        CURRENT=$(grep -m1 'sceneview-web@' "$F" | grep -oE 'sceneview-web@[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/sceneview-web@$CURRENT/sceneview-web@$SOURCE_VERSION/g" "$F"
            echo -e "  Fixed: $webskill (CDN @version $CURRENT -> $SOURCE_VERSION)"
        fi
    done
    if [ -f "$AGENT_WEB_SKILL" ]; then
        CURRENT=$(grep -m1 -E 'sceneview-web. \(currently `[0-9]+\.[0-9]+\.[0-9]+' "$AGENT_WEB_SKILL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "")
        if [ -n "$CURRENT" ] && [ "$CURRENT" != "$SOURCE_VERSION" ]; then
            _sed_inplace "s/(currently \`$CURRENT\`)/(currently \`$SOURCE_VERSION\`)/" "$AGENT_WEB_SKILL"
            echo -e "  Fixed: agents/sceneview-web/SKILL.md (npm prose $CURRENT -> $SOURCE_VERSION)"
        fi
    fi

    # ── The former "7 manual residuals" (releases 4.19.0/4.20.0) — now automated.
    # Each is context-anchored to ANY stale semver (not just the previous version:
    # the 4.20.0 release found cache-busters still at 4.18.0). No blind global
    # find-replace (#2562 SVG-corruption class): every sed is scoped to its exact
    # line shape.
    SEMVER='[0-9]\{1,\}\.[0-9]\{1,\}\.[0-9]\{1,\}'

    # llms.txt — prose label + npm package prose + CDN snippet + flutter snippet.
    LLMS="$REPO_ROOT/llms.txt"
    if [ -f "$LLMS" ]; then
        if grep -qE "Maven artifacts \(version [0-9]+\.[0-9]+\.[0-9]+\)" "$LLMS" && \
           ! grep -q "Maven artifacts (version $SOURCE_VERSION)" "$LLMS"; then
            _sed_inplace "s/Maven artifacts (version $SEMVER)/Maven artifacts (version $SOURCE_VERSION)/" "$LLMS"
            echo -e "  Fixed: llms.txt (prose version label -> $SOURCE_VERSION)"
        fi
        if grep -qE "Package: .sceneview-web. v[0-9]+\.[0-9]+\.[0-9]+" "$LLMS" && \
           ! grep -q "Package: \`sceneview-web\` v$SOURCE_VERSION" "$LLMS"; then
            _sed_inplace "s/Package: \(.\)sceneview-web\(.\) v$SEMVER/Package: \1sceneview-web\2 v$SOURCE_VERSION/" "$LLMS"
            echo -e "  Fixed: llms.txt (sceneview-web package prose -> $SOURCE_VERSION)"
        fi
        if grep -q "sceneview-web@" "$LLMS" && ! grep -q "sceneview-web@$SOURCE_VERSION" "$LLMS"; then
            _sed_inplace "s|sceneview-web@$SEMVER|sceneview-web@$SOURCE_VERSION|g" "$LLMS"
            echo -e "  Fixed: llms.txt (sceneview-web@ CDN snippet -> $SOURCE_VERSION)"
        fi
        if grep -q "flutter_sceneview: ^" "$LLMS" && ! grep -q "flutter_sceneview: ^$SOURCE_VERSION" "$LLMS"; then
            _sed_inplace "s/flutter_sceneview: ^$SEMVER/flutter_sceneview: ^$SOURCE_VERSION/" "$LLMS"
            echo -e "  Fixed: llms.txt (flutter snippet -> $SOURCE_VERSION)"
        fi
    fi

    # samples/android-demo/build.gradle — versionName ternary default.
    DEMO_GRADLE="$REPO_ROOT/samples/android-demo/build.gradle"
    if [ -f "$DEMO_GRADLE" ] && ! grep -q "property('versionName') : \"$SOURCE_VERSION\"" "$DEMO_GRADLE"; then
        _sed_inplace "s/property('versionName') : \"$SEMVER\"/property('versionName') : \"$SOURCE_VERSION\"/" "$DEMO_GRADLE"
        echo -e "  Fixed: samples/android-demo/build.gradle (versionName ternary -> $SOURCE_VERSION)"
    fi

    # website-static/web.html — JSON-LD softwareVersion.
    if [ -f "$WEBSITE_WEB" ] && ! grep -q "\"softwareVersion\": \"$SOURCE_VERSION\"" "$WEBSITE_WEB"; then
        _sed_inplace "s/\"softwareVersion\": \"$SEMVER\"/\"softwareVersion\": \"$SOURCE_VERSION\"/" "$WEBSITE_WEB"
        echo -e "  Fixed: website-static/web.html (softwareVersion -> $SOURCE_VERSION)"
    fi

    # website-static/playground.html — the two AI-prompt version pins.
    if [ -f "$WEBSITE_PLAYGROUND" ]; then
        if ! grep -q "arsceneview:$SOURCE_VERSION" "$WEBSITE_PLAYGROUND"; then
            _sed_inplace "s|arsceneview:$SEMVER|arsceneview:$SOURCE_VERSION|g" "$WEBSITE_PLAYGROUND"
            echo -e "  Fixed: playground.html (prompt arsceneview: -> $SOURCE_VERSION)"
        fi
        if ! grep -q "sceneview-web@$SOURCE_VERSION" "$WEBSITE_PLAYGROUND"; then
            _sed_inplace "s|sceneview-web@$SEMVER|sceneview-web@$SOURCE_VERSION|g" "$WEBSITE_PLAYGROUND"
            echo -e "  Fixed: playground.html (prompt sceneview-web@ -> $SOURCE_VERSION)"
        fi
    fi

    # sceneview.js?v= cache-busters — EVERY html under website-static (incl.
    # embed/ and preview/, which went stale across two releases).
    find "$REPO_ROOT/website-static" -name '*.html' -print0 | while IFS= read -r -d '' HTMLF; do
        if grep -q "sceneview\.js?v=" "$HTMLF" && ! grep -q "sceneview\.js?v=$SOURCE_VERSION" "$HTMLF"; then
            _sed_inplace "s|sceneview\.js?v=$SEMVER|sceneview.js?v=$SOURCE_VERSION|g" "$HTMLF"
            echo -e "  Fixed: ${HTMLF#$REPO_ROOT/} (cache-buster -> $SOURCE_VERSION)"
        fi
    done

    # gpt/knowledge-*.md are GENERATED from llms.txt (tools/generate-gpt-knowledge.js,
    # #2724) and embed VERSION_NAME in their banners — regenerate them after the
    # llms.txt/version fixes above, otherwise a version bump leaves them stale and
    # red-fails the blocking repo-hygiene drift gate.
    if [ -f "$REPO_ROOT/tools/generate-gpt-knowledge.js" ] && command -v node >/dev/null 2>&1; then
        if node "$REPO_ROOT/tools/generate-gpt-knowledge.js" >/dev/null 2>&1; then
            echo -e "  Fixed: gpt/knowledge-*.md regenerated from llms.txt"
        else
            echo -e "  ${YELLOW}WARN: gpt/ regeneration failed — run 'node tools/generate-gpt-knowledge.js' manually${NC}"
        fi
    fi

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
