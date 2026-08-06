#!/bin/bash
# Pre-push quality gate — run before every push to main
# Usage: bash .claude/scripts/pre-push-check.sh

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0

echo "═══════════════════════════════════════════"
echo "  SceneView Pre-Push Quality Gate"
echo "═══════════════════════════════════════════"

# 1. Android compilation
echo -e "\n${YELLOW}[1/12] Compiling sceneview...${NC}"
if ./gradlew :sceneview:compileReleaseKotlin --quiet 2>/dev/null; then
    echo -e "${GREEN}  ✓ sceneview compiles${NC}"
else
    echo -e "${RED}  ✗ sceneview FAILED to compile${NC}"
    ERRORS=$((ERRORS + 1))
fi

echo -e "${YELLOW}[2/12] Compiling arsceneview...${NC}"
if ./gradlew :arsceneview:compileReleaseKotlin --quiet 2>/dev/null; then
    echo -e "${GREEN}  ✓ arsceneview compiles${NC}"
else
    echo -e "${RED}  ✗ arsceneview FAILED to compile${NC}"
    ERRORS=$((ERRORS + 1))
fi

# 2. Unit tests
echo -e "\n${YELLOW}[3/12] Running sceneview unit tests...${NC}"
if ./gradlew :sceneview:test --quiet 2>/dev/null; then
    echo -e "${GREEN}  ✓ sceneview tests pass${NC}"
else
    echo -e "${RED}  ✗ sceneview tests FAILED${NC}"
    ERRORS=$((ERRORS + 1))
fi

echo -e "${YELLOW}[4/12] Running arsceneview unit tests...${NC}"
if ./gradlew :arsceneview:testDebugUnitTest --quiet 2>/dev/null; then
    echo -e "${GREEN}  ✓ arsceneview tests pass${NC}"
else
    echo -e "${RED}  ✗ arsceneview tests FAILED${NC}"
    ERRORS=$((ERRORS + 1))
fi

# 3. Screenshot tests (Roborazzi — Android, JVM, no emulator)
echo -e "\n${YELLOW}[5/12] Verifying Android screenshot goldens...${NC}"
SNAPSHOTS_DIR="samples/android-demo/src/test/snapshots"
if [ -d "$SNAPSHOTS_DIR" ] && [ "$(ls -A $SNAPSHOTS_DIR 2>/dev/null)" ]; then
    if ./gradlew :samples:android-demo:verifyRoborazziDebug --quiet 2>/dev/null; then
        echo -e "${GREEN}  ✓ Android screenshots match goldens${NC}"
    else
        echo -e "${RED}  ✗ Android screenshot regression detected — run recordRoborazziDebug if change is intentional${NC}"
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ No goldens yet — run: ./gradlew :samples:android-demo:recordRoborazziDebug${NC}"
fi

# 4. Screenshot tests iOS (Pillow pixel comparison against simulator goldens)
# Precondition-aware: generate-ios-goldens.py is a MANUAL harness — it
# screenshots whatever the sim currently shows (no install/launch/navigation).
# Verifying is only meaningful when the demo app is RUNNING on the booted sim;
# anything else (springboard, another app) false-reds against the golden — which
# is exactly how the stale explore_current golden blocked every pre-push.
# Golden target = a STATIC screen (About) — never a network-fed screen like
# Explore, whose remote gallery re-drifts a 1%-threshold pixel golden.
# NB: the golden is resolution-bound — capture and verify on the same sim model.
echo -e "${YELLOW}[6/12] Verifying iOS screenshot goldens...${NC}"
IOS_GOLDENS="samples/ios-demo/goldens"
IOS_GOLDEN_NAME="about_static"
IOS_BUNDLE_ID="io.github.sceneview.demo"
if ! xcrun simctl list devices 2>/dev/null | grep -q "Booted"; then
    echo -e "${YELLOW}  ⚠ No iOS simulator booted — skip${NC}"
elif ! xcrun simctl get_app_container booted "$IOS_BUNDLE_ID" >/dev/null 2>&1; then
    echo -e "${YELLOW}  ⚠ iOS demo app not installed on the booted sim — skip (install + launch it to arm this check)${NC}"
elif ! xcrun simctl spawn booted launchctl list 2>/dev/null | grep -q "UIKitApplication:${IOS_BUNDLE_ID}"; then
    echo -e "${YELLOW}  ⚠ iOS demo app not running on the booted sim — skip (launch it on the About tab to arm this check)${NC}"
elif [ ! -f "$IOS_GOLDENS/${IOS_GOLDEN_NAME}.png" ]; then
    echo -e "${YELLOW}  ⚠ No iOS golden yet — foreground the About tab, then: python3 .claude/scripts/generate-ios-goldens.py capture ${IOS_GOLDEN_NAME}${NC}"
elif python3 .claude/scripts/generate-ios-goldens.py verify "$IOS_GOLDEN_NAME" 2>/dev/null; then
    echo -e "${GREEN}  ✓ iOS screenshots match goldens${NC}"
else
    echo -e "${RED}  ✗ iOS screenshot regression — app foregrounded on About, same sim model as the golden? Intentional change: python3 .claude/scripts/generate-ios-goldens.py capture ${IOS_GOLDEN_NAME}${NC}"
    ERRORS=$((ERRORS + 1))
fi

# 5. Version sync
echo -e "\n${YELLOW}[7/12] Checking version sync...${NC}"
# Capture sync-versions.sh output and exit code separately, so a crash of the
# script is not swallowed by the pipeline (a piped crash would falsely report
# "0 mismatches"). `set -o pipefail` is deliberately NOT used globally — many
# grep-no-match pipes elsewhere in this script rely on the lenient default.
# sync-versions.sh exits 0 (aligned), 1 (mismatches found — expected), or 2+ (crash).
SYNC_OUTPUT=$(bash .claude/scripts/sync-versions.sh 2>/dev/null) && SYNC_EXIT=0 || SYNC_EXIT=$?
if [ "$SYNC_EXIT" -gt 1 ]; then
    echo -e "${RED}  ✗ sync-versions.sh crashed (exit $SYNC_EXIT) — cannot verify versions${NC}"
    ERRORS=$((ERRORS + 1))
else
    MISMATCHES=$(echo "$SYNC_OUTPUT" | grep "MISMATCH" | grep -v "migration.md" | grep -v "Errors" | wc -l | tr -d ' ')
    if [ "$MISMATCHES" = "0" ]; then
        echo -e "${GREEN}  ✓ All versions aligned${NC}"
    else
        echo -e "${RED}  ✗ $MISMATCHES version mismatch(es)${NC}"
        ERRORS=$((ERRORS + 1))
    fi
fi

# 6. Website JS syntax
echo -e "\n${YELLOW}[8/12] Validating website JS...${NC}"
NODE_CMD=$(which node 2>/dev/null || which /opt/homebrew/bin/node 2>/dev/null || which /usr/local/bin/node 2>/dev/null || echo "")
if [ -n "$NODE_CMD" ]; then
    if "$NODE_CMD" -c website-static/js/sceneview.js 2>/dev/null; then
        echo -e "${GREEN}  ✓ sceneview.js syntax OK${NC}"
    else
        echo -e "${RED}  ✗ sceneview.js has syntax errors${NC}"
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ node not found, skipping JS validation${NC}"
fi

# 7. Demo app asset references
# Scans every samples/* for broken bundled paths or dead CDN URLs so the
# class of bugs fixed in session 34 (TV demo pointing at non-existent
# models/*.glb, web-demo pointing at 404 CDN URLs) cannot come back.
echo -e "\n${YELLOW}[9/12] Validating demo app asset references...${NC}"
# --no-cdn to keep pre-push fast; CI runs the full check with CDN hits.
if bash .claude/scripts/validate-demo-assets.sh --no-cdn > /tmp/validate-demo-assets.log 2>&1; then
    echo -e "${GREEN}  ✓ All demo asset refs resolve${NC}"
else
    echo -e "${RED}  ✗ Demo apps reference assets that don't exist:${NC}"
    tail -30 /tmp/validate-demo-assets.log | sed 's/^/      /'
    ERRORS=$((ERRORS + 1))
fi

# 8. SceneView agent skill drift
# Catches the case where a library API was renamed/removed without updating
# `agents/sceneview/` (the published android-CLI agent skill). The same check
# runs in quality-gate.sh, pr-check.yml and daily via maintenance.yml — but
# the lighter pre-push gate skipped it, so a skill-only push could land drift
# without ever hitting quality-gate.sh. Invoke it directly here too.
echo -e "\n${YELLOW}[10/12] Checking agent skill drift...${NC}"
if [ -f .claude/scripts/check-sceneview-skill.sh ]; then
    if bash .claude/scripts/check-sceneview-skill.sh > /tmp/skill-drift.log 2>&1; then
        echo -e "${GREEN}  ✓ agents/sceneview/ in sync with library source${NC}"
    else
        echo -e "${RED}  ✗ Agent skill drift — agents/sceneview/ out of sync:${NC}"
        tail -30 /tmp/skill-drift.log | sed 's/^/      /'
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ check-sceneview-skill.sh not found, skipping${NC}"
fi

# 9. GPT knowledge base drift (#3026).
# `gpt/knowledge-*.md` is GENERATED from llms.txt and gated in ci.yml →
# repo-hygiene. Nothing ran it locally — not this script, not quality-gate.sh,
# not impact-check.sh — so editing llms.txt passed every local gate and only
# turned red on CI. That is exactly what happened to the PR that added this
# leg. Sub-second (a node regenerate-and-compare, no write).
echo -e "\n${YELLOW}[11/12] Checking GPT knowledge base drift...${NC}"
if [ -f tools/generate-gpt-knowledge.js ] && [ -n "${NODE_CMD:-$(which node 2>/dev/null)}" ]; then
    if "${NODE_CMD:-node}" tools/generate-gpt-knowledge.js --check > /tmp/gpt-knowledge-drift.log 2>&1; then
        echo -e "${GREEN}  ✓ gpt/knowledge-*.md in sync with llms.txt${NC}"
    else
        echo -e "${RED}  ✗ gpt/knowledge-*.md drifted from llms.txt:${NC}"
        tail -10 /tmp/gpt-knowledge-drift.log | sed 's/^/      /'
        echo -e "      Fix: node tools/generate-gpt-knowledge.js"
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ node or tools/generate-gpt-knowledge.js not found, skipping${NC}"
fi

# Vendored-download hardening gate. Passes silently while nothing builds
# third_party/filament-kmp; fails the moment something does and its downloads
# are still unverified / its symlink extraction unvalidated.
if [ -f .claude/scripts/check-vendored-download-safety.sh ]; then
    if bash .claude/scripts/check-vendored-download-safety.sh > /tmp/vendored-dl.log 2>&1; then
        echo -e "${GREEN}  ✓ vendored download chain: not built, or hardened${NC}"
    else
        echo -e "${RED}  ✗ vendored download chain is BUILT but not hardened:${NC}"
        tail -20 /tmp/vendored-dl.log | sed 's/^/      /'
        ERRORS=$((ERRORS + 1))
    fi
fi

# Public-API ABI gate (#2723): the committed .api dumps are a BLOCKING CI
# check — catch an unintentional public-API change locally before CI does.
# Intentional changes: ./gradlew apiDump, review + commit the .api diff.
echo -e "\n${YELLOW}[12/12] Checking public-API ABI (apiCheck)...${NC}"
if [ -f gradlew ]; then
    if ./gradlew -q apiCheck > /tmp/api-check.log 2>&1; then
        echo -e "${GREEN}  ✓ public API matches the committed .api dumps${NC}"
    else
        echo -e "${RED}  ✗ apiCheck failed — public-API surface drifted:${NC}"
        tail -20 /tmp/api-check.log | sed 's/^/      /'
        echo -e "      Intentional change? Run ./gradlew apiDump and commit the .api diff."
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ gradlew not found, skipping${NC}"
fi

# Summary
echo -e "\n═══════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}  ✓ ALL CHECKS PASSED — safe to push${NC}"
    exit 0
else
    echo -e "${RED}  ✗ $ERRORS CHECK(S) FAILED — DO NOT PUSH${NC}"
    exit 1
fi
