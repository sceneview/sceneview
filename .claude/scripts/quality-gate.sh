#!/usr/bin/env bash
# quality-gate.sh — Comprehensive pre-push quality gate
#
# Runs ALL checks before pushing to the repository.
# Blocks push if any critical check fails.
#
# Usage:
#   ./quality-gate.sh              # Full check
#   ./quality-gate.sh --quick      # Skip build/test (for fast checks only)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

QUICK_MODE="${1:-}"
BLOCKERS=0
WARNINGS=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=== SceneView Quality Gate ===${NC}"
echo ""

check() {
    local name="$1"
    local status="$2"
    local detail="${3:-}"

    case "$status" in
        PASS) printf "  ${GREEN}[PASS]${NC}  %-50s %s\n" "$name" "$detail" ;;
        FAIL) printf "  ${RED}[FAIL]${NC}  %-50s %s\n" "$name" "$detail"; BLOCKERS=$((BLOCKERS + 1)) ;;
        WARN) printf "  ${YELLOW}[WARN]${NC}  %-50s %s\n" "$name" "$detail"; WARNINGS=$((WARNINGS + 1)) ;;
    esac
}

# ─── 1. Git State ──────────────────────────────────────────────────────
echo -e "${CYAN}--- Git State ---${NC}"

BRANCH=$(git branch --show-current)
check "Current branch" "PASS" "$BRANCH"

# Check for merge conflicts
CONFLICT_FILES=$(git diff --name-only --diff-filter=U 2>/dev/null | wc -l | tr -d ' ')
[ "$CONFLICT_FILES" -eq 0 ] && check "No merge conflicts" "PASS" "" || check "No merge conflicts" "FAIL" "$CONFLICT_FILES files"

# Check for large files being committed
# Note: the inner per-file checks must NOT propagate a non-zero exit when the
# file is under the threshold — pipefail + set -e would otherwise abort the
# whole quality-gate run on the common case (see issue #1914).
LARGE_FILES=$(git diff --cached --name-only 2>/dev/null | while read f; do
    if [ -f "$f" ]; then
        SIZE=$(wc -c < "$f" 2>/dev/null | tr -d ' ')
        if [ "${SIZE:-0}" -gt 10485760 ]; then
            echo "$f"
        fi
    fi
done | wc -l | tr -d ' ')
[ "$LARGE_FILES" -eq 0 ] && check "No large files (>10MB) staged" "PASS" "" || check "No large files staged" "WARN" "$LARGE_FILES files"

echo ""

# ─── 2. Version Sync ─────────────────────────────────────────────────
echo -e "${CYAN}--- Version Sync ---${NC}"

SOURCE_VERSION=$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)
check "Source version" "PASS" "$SOURCE_VERSION"

for module in sceneview arsceneview sceneview-core; do
    PROPS="$module/gradle.properties"
    if [ -f "$PROPS" ]; then
        V=$(grep '^VERSION_NAME=' "$PROPS" | cut -d= -f2 || echo "MISSING")
        [ "$V" = "$SOURCE_VERSION" ] && check "$PROPS" "PASS" "$V" || check "$PROPS" "FAIL" "Expected $SOURCE_VERSION, got $V"
    fi
done

LLMS_V=$(grep -m1 'io\.github\.sceneview:sceneview:' llms.txt | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "MISSING")
[ "$LLMS_V" = "$SOURCE_VERSION" ] && check "llms.txt version" "PASS" "$LLMS_V" || check "llms.txt version" "FAIL" "Expected $SOURCE_VERSION, got $LLMS_V"

README_V=$(grep -m1 'io\.github\.sceneview:sceneview:' README.md | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?' | head -1 || echo "MISSING")
[ "$README_V" = "$SOURCE_VERSION" ] && check "README.md version" "PASS" "$README_V" || check "README.md version" "FAIL" "Expected $SOURCE_VERSION, got $README_V"

# llms.txt mirror invariant (issue #1847, CI-drift hardening) — the two
# llms.txt mirrors are now BOTH build-generated and `.gitignore`d, so neither
# can drift from root `llms.txt`:
#   * `docs/docs/llms.txt` — regenerated from root `llms.txt` at docs-build
#     time by `.github/workflows/docs.yml` (issue #899 hardening).
#   * `mcp/src/generated/llms-txt.ts` — generated at build time (#1928).
# `check-llms-drift.sh` no longer diffs content (there is no committed copy to
# diff); it enforces the STRUCTURAL invariant — that `docs/docs/llms.txt`
# stays untracked — so a committed mirror can never silently reappear and
# reintroduce the drift class that reddened the gate on clean PRs (#1822).
if [ -x ".claude/scripts/check-llms-drift.sh" ]; then
    if bash .claude/scripts/check-llms-drift.sh > /tmp/check-llms-drift.log 2>&1; then
        check "llms.txt mirror in sync" "PASS" ""
    else
        DRIFT_COUNT=$(grep -c '^MISMATCH:' /tmp/check-llms-drift.log 2>/dev/null || echo "?")
        check "llms.txt mirror in sync" "FAIL" "$DRIFT_COUNT mirror(s) drifted; see /tmp/check-llms-drift.log"
    fi
fi

# samples/android-demo append-only demo-fragments collator drift (issue #1797).
# `GeneratedDemos.kt` is no longer committed — it is `.gitignore`d and
# regenerated before `:samples:android-demo` Kotlin compilation by the
# `generateDemoRegistry` Gradle task (issue #1976), so it is fresh by
# construction and cannot drift. Likewise `docs/docs/llms.txt` is no longer
# committed (issue #899 hardening). The only TRACKED collator output is the
# generated demos block inside root `llms.txt`: a new fragment added without
# re-running the collator would leave that block stale. `--check`
# bit-compares it against what the collator would emit, blocking the PR on drift.
COLLATOR="samples/android-demo/scripts/collate-demos.sh"
if [ -x "$COLLATOR" ]; then
    if bash "$COLLATOR" --check > /tmp/check-demos-drift.log 2>&1; then
        check "android-demo llms.txt demos block in sync" "PASS" ""
    else
        check "android-demo llms.txt demos block drift" "FAIL" "Run $COLLATOR — see /tmp/check-demos-drift.log"
    fi
fi

echo ""

# ─── 3. Security ──────────────────────────────────────────────────────
echo -e "${CYAN}--- Security ---${NC}"

# Check for secrets in tracked files
for pattern in ".env" "credentials.json" "keystore.jks" "google-services.json"; do
    TRACKED=$(git ls-files "$pattern" 2>/dev/null | wc -l | tr -d ' ')
    [ "$TRACKED" -gt 0 ] && check "No tracked $pattern" "FAIL" "SECURITY RISK" || true
done

# local.properties should not be tracked
LP_TRACKED=$(git ls-files "local.properties" 2>/dev/null | wc -l | tr -d ' ')
[ "$LP_TRACKED" -eq 0 ] && check "local.properties not tracked" "PASS" "" || check "local.properties tracked" "FAIL" "Contains local paths/secrets"

# Check for API keys in ADDED lines of staged changes.
# Patterns are anchored to each provider's real key shape — a bare `sk-`
# substring matched `disk-full`, `task-*`, `npm_install`, etc. (false positives,
# notably on large file deletions). Scan only added lines (`^+`) so removing a
# secret never fails the gate.
STAGED_KEYS=$(git diff --cached 2>/dev/null | grep '^+' | grep -cE 'AIza[A-Za-z0-9_-]{35}|sk-[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|ghp_[A-Za-z0-9]{36}|npm_[A-Za-z0-9]{36}|-----BEGIN [A-Z ]*PRIVATE KEY-----' 2>/dev/null || true)
STAGED_KEYS=$(echo "$STAGED_KEYS" | tr -d '[:space:]' | head -c 10)
[ -z "$STAGED_KEYS" ] && STAGED_KEYS=0
[ "$STAGED_KEYS" -eq 0 ] 2>/dev/null && check "No API keys in staged changes" "PASS" "" || check "API keys in staged changes" "FAIL" "$STAGED_KEYS matches"

echo ""

# ─── 4. Code Quality ────────────────────────────────────────────────
echo -e "${CYAN}--- Code Quality ---${NC}"

# Check for common issues in changed files
CHANGED_KT=$(git diff --name-only HEAD 2>/dev/null | grep '\.kt$' || true)
if [ -n "$CHANGED_KT" ]; then
    # Check for !! (force unwrap) in changed Kotlin files
    FORCE_UNWRAP=0
    while IFS= read -r f; do
        if [ -f "$f" ]; then
            COUNT=$(grep -c '!!' "$f" 2>/dev/null || echo "0")
            FORCE_UNWRAP=$((FORCE_UNWRAP + COUNT))
        fi
    done <<< "$CHANGED_KT"
    [ "$FORCE_UNWRAP" -eq 0 ] && check "No force unwrap (!!) in Kotlin" "PASS" "" || check "Force unwrap (!!) in Kotlin" "WARN" "$FORCE_UNWRAP occurrences"

    # Check for Dispatchers.IO with Filament calls
    BGTHREAD=$(git diff HEAD 2>/dev/null | grep "+.*Dispatchers\.IO" | grep -c "modelLoader\|materialLoader\|createModel\|createMaterial" || echo "0")
    [ "$BGTHREAD" -eq 0 ] && check "No Filament calls on background thread" "PASS" "" || check "Filament on background thread" "FAIL" "THREADING VIOLATION"
fi

# Check for TODO/FIXME in staged changes
TODOS=$(git diff --cached 2>/dev/null | grep "^+" | grep -c "TODO\|FIXME\|HACK\|XXX" 2>/dev/null || true)
TODOS=$(echo "$TODOS" | tr -d '[:space:]' | head -c 10)
[ -z "$TODOS" ] && TODOS=0
[ "$TODOS" -eq 0 ] 2>/dev/null && check "No new TODO/FIXME in staged changes" "PASS" "" || check "New TODO/FIXME" "WARN" "$TODOS occurrences"

# Check for deprecated Scene{}/ARScene{} composable references.
# Guards against AI sessions reintroducing the pre-v3.6 names, which the
# library still accepts as @Deprecated aliases but which every Claude/Cursor
# session would then copy-paste into fresh code.
if [ -x ".claude/scripts/check-deprecated-api.sh" ]; then
    if bash .claude/scripts/check-deprecated-api.sh --all > /tmp/check-deprecated-api.log 2>&1; then
        check "No deprecated Scene{} / ARScene{} refs" "PASS" ""
    else
        DEPRECATED_COUNT=$(grep -cE '^    [^ ].+:[0-9]+:' /tmp/check-deprecated-api.log 2>/dev/null || echo "?")
        check "Deprecated Scene{} / ARScene{} refs" "FAIL" "$DEPRECATED_COUNT line(s); see /tmp/check-deprecated-api.log"
    fi
fi

# Check for references to the archived `sceneview/sceneview-swift` SPM mirror.
# The mirror was retired in PR #1215 — SceneViewSwift resolves from the
# monorepo root Package.swift. A new mirror-URL reference points users at a
# dead resolution path (#1237).
if [ -x ".claude/scripts/check-sceneview-swift-urls.sh" ]; then
    if bash .claude/scripts/check-sceneview-swift-urls.sh > /tmp/check-sceneview-swift-urls.log 2>&1; then
        check "No archived sceneview-swift mirror URLs" "PASS" ""
    else
        SWIFT_URL_COUNT=$(grep -cE '^    [^ ]' /tmp/check-sceneview-swift-urls.log 2>/dev/null || echo "?")
        check "Archived sceneview-swift mirror URLs" "FAIL" "$SWIFT_URL_COUNT file(s); see /tmp/check-sceneview-swift-urls.log"
    fi
fi

echo ""

# ─── 4a. SceneView Agent Skill Drift ───────────────────────────────────
# Catches the kind of API hallucination that landed in the first cut of
# `agents/sceneview/SKILL.md` (PR #1072) — fake APIs like
# `rememberARSession` / `AnchorNode.image()` / `GeometryNode(geometry=...)`
# that would compile into broken code in any project the skill helps
# generate. The check runs in well under 1s.
echo -e "${CYAN}--- SceneView Agent Skill ---${NC}"
if [ -d "agents/sceneview" ] && [ -x ".claude/scripts/check-sceneview-skill.sh" ]; then
    if bash .claude/scripts/check-sceneview-skill.sh --quiet > /tmp/check-sceneview-skill.log 2>&1; then
        check "agents/sceneview/ in sync with source" "PASS" ""
    else
        DRIFT_COUNT=$(grep -cE '^    - ' /tmp/check-sceneview-skill.log 2>/dev/null || echo "?")
        check "agents/sceneview/ in sync with source" "FAIL" "$DRIFT_COUNT drift item(s); see /tmp/check-sceneview-skill.log"
    fi
elif [ -d "agents/sceneview" ]; then
    check "agents/sceneview/ drift check" "WARN" "check-sceneview-skill.sh missing"
fi

echo ""

# ─── 4b. Demo App Asset Integrity ──────────────────────────────────────
# Catches the class of bugs fixed in session 34 (TV demo bundled model
# typos, web-demo dead 404 CDN URLs). Fast enough to run in every gate.
echo -e "${CYAN}--- Demo App Assets ---${NC}"
if [ -x ".claude/scripts/validate-demo-assets.sh" ]; then
    # --no-cdn in --quick mode, full HTTP check otherwise
    EXTRA_ARGS=""
    [ "$QUICK_MODE" = "--quick" ] && EXTRA_ARGS="--no-cdn"
    if bash .claude/scripts/validate-demo-assets.sh $EXTRA_ARGS > /tmp/validate-demo-assets.log 2>&1; then
        # Summary line counts how many refs were verified
        SUMMARY=$(grep -oE '[0-9]+ bundled, [0-9]+ CDN' /tmp/validate-demo-assets.log | tail -1)
        check "Demo app asset refs resolve" "PASS" "$SUMMARY"
    else
        BAD=$(grep -cE '^  (MISS|DEAD)' /tmp/validate-demo-assets.log 2>/dev/null || echo "?")
        check "Demo app asset refs resolve" "FAIL" "$BAD broken reference(s) — see /tmp/validate-demo-assets.log"
    fi
else
    check "Demo app asset refs resolve" "WARN" "validate-demo-assets.sh missing"
fi

echo ""

# ─── 4c. Filament .mat → .filamat drift (issue #1912) ─────────────────
# The Filament runtime ↔ .filamat ABI invariant has caused a real shipping
# incident (v4.1.0 — 10 demos crashed at runtime because half the .filamat
# blobs were compiled with a different matc than the runtime expected).
# Each .filamat embeds the matc MATERIAL_VERSION + flag list in its header
# chunks (SREV_TAM and MRPC_TAM). `tools/GenerateFilamat.sh --check`
# regenerates every .filamat to a tmp dir with the pinned matc and the
# committed per-profile flag list, then byte-diffs against the committed
# blob. Exit 1 on drift blocks the PR.
#
# Failure modes (degraded gracefully):
#   - Sandbox without network → matc cannot be downloaded → WARN, advisory.
#   - matc version mismatch     → WARN, advisory.
# A clean tree on a host with matc cached produces a hard PASS.
echo -e "${CYAN}--- Filament .filamat blobs ---${NC}"
if [ -x "tools/GenerateFilamat.sh" ]; then
    if bash tools/GenerateFilamat.sh --check --ci-tolerant > /tmp/check-filamat-drift.log 2>&1; then
        # --ci-tolerant turns matc unavailability into exit-0; distinguish.
        if grep -q "All .* filamat blob" /tmp/check-filamat-drift.log; then
            BLOB_COUNT=$(grep -oE 'All [0-9]+ filamat' /tmp/check-filamat-drift.log | grep -oE '[0-9]+' | head -1)
            check "Filament .filamat blobs in sync" "PASS" "${BLOB_COUNT:-21} blob(s)"
        elif grep -q "matc unavailable" /tmp/check-filamat-drift.log; then
            check "Filament .filamat blobs" "WARN" "matc unavailable (no network?) — see /tmp/check-filamat-drift.log"
        else
            check "Filament .filamat blobs" "WARN" "see /tmp/check-filamat-drift.log"
        fi
    else
        DRIFT_COUNT=$(grep -cE '^\[FAIL\] \[' /tmp/check-filamat-drift.log 2>/dev/null || echo "?")
        check "Filament .filamat blobs in sync" "FAIL" "$DRIFT_COUNT blob(s) drifted — run tools/GenerateFilamat.sh"
    fi
else
    check "Filament .filamat blobs" "WARN" "tools/GenerateFilamat.sh missing"
fi

echo ""

# ─── 4c-bis. sceneview-web.d.ts ↔ Kotlin/JS surface drift (issue #2736) ─
# The npm typings are hand-written (#946) and consumed by real users and by
# AI codegen; check-web-dts.sh asserts the `sceneview` namespace registry
# (Main.kt) and the SceneViewJS/NodeHandle/SceneViewHaptic member surfaces
# match the d.ts in both directions. Deterministic (bash+awk, no toolchain)
# → hard FAIL on drift.
echo -e "${CYAN}--- sceneview-web.d.ts drift ---${NC}"
if [ -x ".claude/scripts/check-web-dts.sh" ]; then
    if bash .claude/scripts/check-web-dts.sh > /tmp/check-web-dts.log 2>&1; then
        check "sceneview-web.d.ts in sync with JS surface" "PASS"
    else
        DRIFT_COUNT=$(grep -c '^\[FAIL\]' /tmp/check-web-dts.log 2>/dev/null || echo "?")
        check "sceneview-web.d.ts in sync with JS surface" "FAIL" "$DRIFT_COUNT mismatch(es) — see /tmp/check-web-dts.log"
    fi
else
    check "sceneview-web.d.ts drift check" "WARN" ".claude/scripts/check-web-dts.sh missing"
fi

echo ""

# ─── 4d. Worktree-prune regression suite (advisory) ────────────────────
# Pins the safety contract of `.claude/scripts/worktree-auto-prune.sh`
# (locked-tree skip #1833, broad subprocess detect #1834, forensic log
# #1839). Advisory: a CI host without `lsof`/`fuser` can't fully exercise
# the active-session check — we surface a WARN instead of failing.
if [ -f ".claude/scripts/test-worktree-auto-prune.sh" ]; then
    if bash .claude/scripts/test-worktree-auto-prune.sh > /tmp/test-worktree-auto-prune.log 2>&1; then
        check "Worktree-prune regression suite" "PASS"
    else
        # Distinguish "host lacks the tool" from "real regression".
        if grep -q "neither lsof nor fuser" /tmp/test-worktree-auto-prune.log; then
            check "Worktree-prune regression suite" "WARN" "host lacks lsof/fuser — see /tmp/test-worktree-auto-prune.log"
        else
            check "Worktree-prune regression suite" "WARN" "failures — see /tmp/test-worktree-auto-prune.log"
        fi
    fi
else
    check "Worktree-prune regression suite" "WARN" "test-worktree-auto-prune.sh missing"
fi

echo ""

# ─── 5. Build & Test (skip in quick mode) ────────────────────────────
#
# Two skip flags:
#   --quick                          skips Android build, Android tests, MCP tests
#                                    (local fast path)
#   QUALITY_GATE_SKIP_ANDROID=1      skips Android build + tests but KEEPS MCP
#                                    tests + asset CDN checks. Used by the CI
#                                    workflow because ci.yml's `build` job
#                                    already runs assembleDebug + the same unit
#                                    test set + JaCoCo coverage. Running them
#                                    a second time here was pure duplication
#                                    (~5 min × every PR + push).
if [ "$QUICK_MODE" != "--quick" ]; then
    if [ "${QUALITY_GATE_SKIP_ANDROID:-0}" != "1" ]; then
        echo -e "${CYAN}--- Build ---${NC}"

        if [ -f "gradlew" ]; then
            echo -e "  Building Android debug..."
            ASSEMBLE_LOG=/tmp/qg-assemble-debug.log
            if ./gradlew assembleDebug --console=plain > "$ASSEMBLE_LOG" 2>&1; then
                check "Android assembleDebug" "PASS" ""
            else
                check "Android assembleDebug" "FAIL" "Build failed — see $ASSEMBLE_LOG"
                echo -e "${RED}--- assembleDebug tail ---${NC}"
                tail -60 "$ASSEMBLE_LOG"
                echo -e "${RED}--- end ---${NC}"
            fi
        fi

        echo ""
        echo -e "${CYAN}--- Tests ---${NC}"

        if [ -f "gradlew" ]; then
            echo -e "  Running unit tests..."
            UT_LOG=/tmp/qg-unit-tests.log
            # `:samples:android-demo:testDebugUnitTest` runs ARBundledRecordingsTest
            # (the ftyp + avc1 + mett contract suite added by #1060, moved to
            # `testDebug/` sourceSet by #1100). Without it the gate silently drops
            # the bundled-recording regression suite. See #1130.
            if ./gradlew :sceneview:testDebugUnitTest :arsceneview:testDebugUnitTest :samples:android-demo:testDebugUnitTest --console=plain > "$UT_LOG" 2>&1; then
                check "Android unit tests" "PASS" ""
            else
                check "Android unit tests" "FAIL" "Tests failed — see $UT_LOG"
                echo -e "${RED}--- unit tests tail ---${NC}"
                tail -60 "$UT_LOG"
                echo -e "${RED}--- end ---${NC}"
            fi
        fi
    else
        echo -e "${YELLOW}Skipping Android build + tests (QUALITY_GATE_SKIP_ANDROID=1 — covered by ci.yml)${NC}"
        echo ""
    fi

    if [ -d "mcp" ] && [ -f "mcp/package.json" ] && [ -d "mcp/node_modules" ]; then
        echo -e "  Running MCP tests..."
        if (cd mcp && npm test --silent 2>/dev/null); then
            check "MCP tests" "PASS" ""
        else
            check "MCP tests" "FAIL" ""
        fi
    fi

    echo ""
else
    echo -e "${YELLOW}Skipping build & test (--quick mode)${NC}"
    echo ""
fi

# ─── 6. Website asset rules ───────────────────────────────────────────
# Enforces the "never depend on an external CDN" rule.
# Distinguishes real CDN loads from legitimate mentions (code examples,
# comparison tables, comments, npm keywords).
echo -e "${CYAN}--- Website asset rules ---${NC}"

if [ -d "website-static" ]; then
    # Google Fonts / gstatic: no references at all — neither preconnect nor
    # stylesheet loads. The site uses self-hosted fonts under assets/fonts/.
    GFONTS=$( { grep -rn "fonts\.googleapis\.com\|fonts\.gstatic\.com" website-static/ 2>/dev/null || true; } | wc -l | tr -d ' ')
    if [ "$GFONTS" -eq 0 ]; then
        check "No Google Fonts references" "PASS" ""
    else
        check "Google Fonts references" "FAIL" "$GFONTS hit(s) — must use website-static/assets/fonts/fonts.css"
    fi

    # Skip compiled artifacts (source maps, bundled js) and embed readme which
    # references modelviewer.dev as a public Khronos-sample mirror.
    GREP_EXCLUDE=(--exclude='*.map' --exclude='sceneview.js' --exclude='sceneview-web.js' --exclude='filament*.js')

    # Actual three.js script loads — check for <script src> pointing at any
    # three.js CDN or import maps referencing 'three'. Comments and comparison
    # tables are ignored.
    THREEJS_LOADS=$( { grep -rnE "${GREP_EXCLUDE[@]}" '<script[^>]*src="[^"]*three[^"]*\.(js|mjs)"' website-static/ 2>/dev/null || true; } | wc -l | tr -d ' ')
    THREEJS_IMPORTS=$( { grep -rnE "${GREP_EXCLUDE[@]}" "import[^;]+from[[:space:]]*['\"]three['\"]" website-static/ 2>/dev/null || true; } | wc -l | tr -d ' ')
    THREEJS_TOTAL=$((THREEJS_LOADS + THREEJS_IMPORTS))
    if [ "$THREEJS_TOTAL" -eq 0 ]; then
        check "No Three.js loads in website-static" "PASS" ""
    else
        check "Three.js loads in website-static" "FAIL" "$THREEJS_TOTAL hit(s)"
    fi

    # Actual <model-viewer> element instantiations. The string "model-viewer"
    # in comments, keywords, titles, code examples, and compiled js bundles
    # is allowed (only true HTML tag loads are flagged).
    MV_TAGS=$( { grep -rnE "${GREP_EXCLUDE[@]}" '<model-viewer\b' website-static/ 2>/dev/null || true; } | wc -l | tr -d ' ')
    MV_IMPORTS=$( { grep -rnE "${GREP_EXCLUDE[@]}" '@google/model-viewer|import[^;]+model-viewer' website-static/ 2>/dev/null || true; } | wc -l | tr -d ' ')
    MV_TOTAL=$((MV_TAGS + MV_IMPORTS))
    if [ "$MV_TOTAL" -eq 0 ]; then
        check "No <model-viewer> loads in website-static" "PASS" ""
    else
        check "<model-viewer> loads in website-static" "FAIL" "$MV_TOTAL hit(s)"
    fi

    # Third-party CDN <link> / <script> loads that are not explicitly allowed.
    # Skipped-by-design: sceneview.github.io, github.com/sceneview and store
    # listing links (play.google.com, apps.apple.com, npmjs.com, central.sonatype).
    CDN_RAW=$( { grep -rnE '<(script|link)[^>]*(src|href)="https?://' website-static/ --include='*.html' 2>/dev/null || true; } )
    CDN_FILTERED=$(printf '%s\n' "$CDN_RAW" \
        | grep -v '^$' \
        | grep -vE 'sceneview\.github\.io|github\.com/sceneview|npmjs\.com/package|central\.sonatype\.com|play\.google\.com|apps\.apple\.com' \
        || true)
    CDN_HITS=$(printf '%s' "$CDN_FILTERED" | grep -c . || true)
    if [ "${CDN_HITS:-0}" -eq 0 ]; then
        check "No third-party CDN loads in HTML" "PASS" ""
    else
        check "Third-party CDN loads in HTML" "WARN" "$CDN_HITS hit(s) — review manually"
    fi
fi

echo ""

# ─── 7. Cross-platform consistency ────────────────────────────────────
echo -e "${CYAN}--- Cross-platform Consistency ---${NC}"

# Check that changed Android APIs have corresponding iOS/llms.txt updates
CHANGED_ANDROID=$(git diff --name-only HEAD 2>/dev/null | grep "^sceneview/src/\|^arsceneview/src/" || true)
if [ -n "$CHANGED_ANDROID" ]; then
    CHANGED_LLMS=$(git diff --name-only HEAD 2>/dev/null | grep "^llms.txt$" || true)
    if [ -z "$CHANGED_LLMS" ]; then
        # Check if the Android changes include new public APIs
        NEW_PUBLIC=$(git diff HEAD -- sceneview/src/ arsceneview/src/ 2>/dev/null | grep "^+.*fun \|^+.*class.*Node\|^+.*@Composable" | wc -l | tr -d ' ')
        if [ "$NEW_PUBLIC" -gt 0 ]; then
            check "llms.txt updated for new APIs" "WARN" "$NEW_PUBLIC new public API(s) — llms.txt not updated"
        else
            check "No new public APIs" "PASS" "llms.txt update not needed"
        fi
    else
        check "llms.txt updated with Android changes" "PASS" ""
    fi
fi

echo ""

# ─── Summary ───────────────────────────────────────────────────────────
echo -e "${CYAN}=== Quality Gate Summary ===${NC}"
echo ""

if [ "$BLOCKERS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
    echo -e "${GREEN}ALL CLEAR — safe to push${NC}"
    exit 0
elif [ "$BLOCKERS" -eq 0 ]; then
    echo -e "${YELLOW}PASS with $WARNINGS warning(s) — review before pushing${NC}"
    exit 0
else
    echo -e "${RED}BLOCKED — $BLOCKERS issue(s) must be fixed before pushing${NC}"
    exit 1
fi
