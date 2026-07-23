#!/usr/bin/env bash
# release-checklist.sh — Pre-release validation across ALL platforms
#
# Checks everything that must be true before tagging a release.
# Exit code 0 = ready to release, non-zero = blockers found.
#
# Usage:
#   ./release-checklist.sh [version]
#   Example: ./release-checklist.sh 3.6.0

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

TARGET_VERSION="${1:-$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)}"
BLOCKERS=0
WARNINGS=0

echo -e "${CYAN}=== SceneView Release Checklist ===${NC}"
echo -e "Target version: ${GREEN}$TARGET_VERSION${NC}"
echo ""

check() {
    local name="$1"
    local status="$2" # PASS, FAIL, WARN
    local detail="$3"

    case "$status" in
        PASS) printf "  ${GREEN}[PASS]${NC}  %-50s %s\n" "$name" "$detail" ;;
        FAIL) printf "  ${RED}[FAIL]${NC}  %-50s %s\n" "$name" "$detail"; BLOCKERS=$((BLOCKERS + 1)) ;;
        WARN) printf "  ${YELLOW}[WARN]${NC}  %-50s %s\n" "$name" "$detail"; WARNINGS=$((WARNINGS + 1)) ;;
    esac
}

# ─── 1. Version alignment ─────────────────────────────────────────────────
echo -e "${CYAN}--- Version Alignment (Gradle) ---${NC}"

ROOT_V=$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)
[ "$ROOT_V" = "$TARGET_VERSION" ] && check "gradle.properties (root)" "PASS" "$ROOT_V" || check "gradle.properties (root)" "FAIL" "Expected $TARGET_VERSION, got $ROOT_V"

for module in sceneview arsceneview sceneview-core; do
    PROPS="$module/gradle.properties"
    if [ -f "$PROPS" ]; then
        V=$(grep '^VERSION_NAME=' "$PROPS" | cut -d= -f2 || echo "MISSING")
        [ "$V" = "$TARGET_VERSION" ] && check "$PROPS" "PASS" "$V" || check "$PROPS" "FAIL" "Expected $TARGET_VERSION, got $V"
    fi
done
echo ""

# ─── 2. npm packages ────────────────────────────────────────────────��───
echo -e "${CYAN}--- npm Packages ---${NC}"

MCP_V=$(python3 -c "import json; print(json.load(open('mcp/package.json'))['version'])" 2>/dev/null || echo "MISSING")
check "mcp/package.json" "WARN" "v$MCP_V (MCP may have own version cycle)"

if [ -f "sceneview-web/package.json" ]; then
    WEB_V=$(python3 -c "import json; print(json.load(open('sceneview-web/package.json'))['version'])" 2>/dev/null || echo "MISSING")
    [ "$WEB_V" = "$TARGET_VERSION" ] && check "sceneview-web/package.json" "PASS" "$WEB_V" || check "sceneview-web/package.json" "WARN" "Expected $TARGET_VERSION, got $WEB_V"
fi

if [ -f "react-native/react-native-sceneview/package.json" ]; then
    RN_V=$(python3 -c "import json; print(json.load(open('react-native/react-native-sceneview/package.json'))['version'])" 2>/dev/null || echo "MISSING")
    [ "$RN_V" = "$TARGET_VERSION" ] && check "react-native package.json" "PASS" "$RN_V" || check "react-native package.json" "WARN" "Got $RN_V"
fi
echo ""

# ─── 3. Flutter ──────────────────────────────────────────────────────────
echo -e "${CYAN}--- Flutter ---${NC}"

if [ -f "flutter/sceneview_flutter/pubspec.yaml" ]; then
    FL_V=$(grep '^version:' flutter/sceneview_flutter/pubspec.yaml | awk '{print $2}')
    [ "$FL_V" = "$TARGET_VERSION" ] && check "flutter pubspec.yaml" "PASS" "$FL_V" || check "flutter pubspec.yaml" "WARN" "Got $FL_V"
fi

if [ -f "flutter/sceneview_flutter/android/build.gradle" ]; then
    FLA_V=$(grep "^version " flutter/sceneview_flutter/android/build.gradle | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "?")
    [ "$FLA_V" = "$TARGET_VERSION" ] && check "flutter android build.gradle" "PASS" "$FLA_V" || check "flutter android build.gradle" "WARN" "Got $FLA_V"
fi

if [ -f "flutter/sceneview_flutter/ios/flutter_sceneview.podspec" ]; then
    FLI_V=$(grep "s\.version" flutter/sceneview_flutter/ios/flutter_sceneview.podspec | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "?")
    [ "$FLI_V" = "$TARGET_VERSION" ] && check "flutter podspec" "PASS" "$FLI_V" || check "flutter podspec" "WARN" "Got $FLI_V"
fi
echo ""

# ─── 4. Documentation ───────────────────────────────────────────────────
echo -e "${CYAN}--- Documentation ---${NC}"

LLMS_V=$(grep -m1 'io\.github\.sceneview:sceneview:' llms.txt | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "MISSING")
[ "$LLMS_V" = "$TARGET_VERSION" ] && check "llms.txt" "PASS" "$LLMS_V" || check "llms.txt" "FAIL" "Expected $TARGET_VERSION, got $LLMS_V"

README_V=$(grep -m1 'io\.github\.sceneview:sceneview:' README.md | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "MISSING")
[ "$README_V" = "$TARGET_VERSION" ] && check "README.md" "PASS" "$README_V" || check "README.md" "FAIL" "Expected $TARGET_VERSION, got $README_V"

CLAUDE_V=$(grep -m1 'io\.github\.sceneview:sceneview:' CLAUDE.md | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "MISSING")
[ "$CLAUDE_V" = "$TARGET_VERSION" ] && check "CLAUDE.md" "PASS" "$CLAUDE_V" || check "CLAUDE.md" "FAIL" "Expected $TARGET_VERSION, got $CLAUDE_V"

# Docs site files
for docfile in docs/docs/index.md docs/docs/quickstart.md docs/docs/llms-full.txt docs/docs/cheatsheet.md docs/docs/platforms.md; do
    if [ -f "$docfile" ]; then
        DV=$(grep -m1 'io\.github\.sceneview:sceneview:' "$docfile" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "N/A")
        if [ "$DV" != "N/A" ]; then
            [ "$DV" = "$TARGET_VERSION" ] && check "$docfile" "PASS" "$DV" || check "$docfile" "WARN" "Got $DV"
        fi
    fi
done
echo ""

# ─── 5. Website ─────────────────────────────────────────────────────────
echo -e "${CYAN}--- Website ---${NC}"

if [ -f "website-static/index.html" ]; then
    WV=$(grep 'softwareVersion' website-static/index.html | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "N/A")
    [ "$WV" = "$TARGET_VERSION" ] && check "website-static/index.html" "PASS" "$WV" || check "website-static/index.html" "WARN" "Got $WV"
fi
echo ""

# ─── 6. CHANGELOG ───────────────────────────────────────────────────────
echo -e "${CYAN}--- CHANGELOG ---${NC}"

if [ -f "CHANGELOG.md" ]; then
    # First versioned section (## vX.Y.Z ...), skipping the ## Unreleased placeholder.
    CL_V=$(grep -m1 '^## v[0-9]' CHANGELOG.md | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo "MISSING")
    [ "$CL_V" = "$TARGET_VERSION" ] && check "CHANGELOG entry" "PASS" "" || check "CHANGELOG entry" "FAIL" "Latest entry is $CL_V — run collate-changelog.sh $TARGET_VERSION"
else
    check "CHANGELOG.md exists" "FAIL" "File not found"
fi

# changelog.d/ fragments must be collated into CHANGELOG.md before tagging.
if [ -d "changelog.d" ]; then
    PENDING=$(find changelog.d -maxdepth 1 -name '*.md' ! -name 'README.md' 2>/dev/null | wc -l | tr -d ' ')
    [ "$PENDING" -eq 0 ] && check "changelog.d/ fragments collated" "PASS" "" || check "changelog.d/ fragments collated" "FAIL" "$PENDING pending — run collate-changelog.sh $TARGET_VERSION"
fi
echo ""

# ─── 7. Git state ───────────────────────────────────────────────────────
echo -e "${CYAN}--- Git State ---${NC}"

DIRTY=$(git status --porcelain | { grep -v '??' || true; } | wc -l | tr -d ' ')
[ "$DIRTY" -eq 0 ] && check "Working tree clean" "PASS" "" || check "Working tree clean" "FAIL" "$DIRTY uncommitted changes"

BRANCH=$(git branch --show-current)
check "Current branch" "PASS" "$BRANCH"

TAG_EXISTS=$(git tag -l "v$TARGET_VERSION" | wc -l | tr -d ' ')
[ "$TAG_EXISTS" -eq 0 ] && check "Tag v$TARGET_VERSION not yet created" "PASS" "" || check "Tag already exists" "WARN" "v$TARGET_VERSION"
echo ""

# ─── 8. Build check ────────────────────────────────────────────────────
echo -e "${CYAN}--- Build Check ---${NC}"

if [ -f "gradlew" ]; then
    echo -e "  Running: ./gradlew assembleDebug (this may take a few minutes)..."
    if ./gradlew assembleDebug --quiet 2>/dev/null; then
        check "Android assembleDebug" "PASS" ""
    else
        check "Android assembleDebug" "FAIL" "Build failed"
    fi
else
    check "Gradle wrapper" "FAIL" "gradlew not found"
fi
echo ""

# ─── 9. Tests ──────────────────────────────────────────────────────────
echo -e "${CYAN}--- Tests ---${NC}"

if [ -d "mcp" ] && [ -f "mcp/package.json" ]; then
    echo -e "  Running: MCP tests..."
    if (cd mcp && npm test --silent 2>/dev/null); then
        check "MCP tests" "PASS" ""
    else
        check "MCP tests" "FAIL" "Tests failed"
    fi
fi
echo ""

# ─── 10. Security ─────────────────────────────────────────────────────
echo -e "${CYAN}--- Security ---${NC}"

SECRETS_FOUND=0
for pattern in ".env" "credentials.json" "keystore.jks" "google-services.json" "local.properties"; do
    TRACKED=$(git ls-files "$pattern" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$TRACKED" -gt 0 ]; then
        check "No tracked $pattern" "FAIL" "Found in git index!"
        SECRETS_FOUND=$((SECRETS_FOUND + 1))
    fi
done
[ "$SECRETS_FOUND" -eq 0 ] && check "No secrets in tracked files" "PASS" ""

# Check for API keys in source.
# Match the actual key SHAPES, not bare prefixes — the old `sk-` substring flagged
# innocuous words ("disk-full", "desk-side", "risk-", "task-") and the embedded
# llms.txt prose, failing every release on a false positive (verified during v4.17.0).
API_KEY_HITS=$({ grep -rnE "AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|npm_[A-Za-z0-9]{36}" --include="*.kt" --include="*.swift" --include="*.ts" --include="*.js" \
    sceneview/ arsceneview/ SceneViewSwift/ mcp/src/ 2>/dev/null || true; } | { grep -v "node_modules\|\.test\." || true; } | wc -l | tr -d ' ')
[ "$API_KEY_HITS" -eq 0 ] && check "No hardcoded API keys" "PASS" "" || check "No hardcoded API keys" "FAIL" "$API_KEY_HITS hit(s)"
echo ""

# ─── 11. MCP dist not committed ────────────────────────────────────────
# `mcp/dist/` is a build artefact (`tsc` output) regenerated by the
# `prepare` script on every `npm install` / `npm publish`. It must NOT be
# committed — a committed copy silently drifts from `src/` (issue #2047).
echo -e "${CYAN}--- MCP Dist Not Committed ---${NC}"

TRACKED_DIST=$(git ls-files mcp/dist 2>/dev/null | wc -l | tr -d ' ')
[ "$TRACKED_DIST" -eq 0 ] && check "MCP dist not committed" "PASS" "build artefact, regenerated by 'npm run prepare'" || check "MCP dist not committed" "FAIL" "$TRACKED_DIST file(s) tracked under mcp/dist/ — run 'git rm -r --cached mcp/dist/' (issue #2047)"
echo ""

# ─── 12. CI Health ──────────────────────────────────────────────────────
echo -e "${CYAN}--- CI Health ---${NC}"

if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
    LAST_CI=$(gh run list --workflow=ci.yml --limit 1 --json conclusion --jq '.[0].conclusion' 2>/dev/null || echo "unknown")
    [ "$LAST_CI" = "success" ] && check "Last CI run" "PASS" "$LAST_CI" || check "Last CI run" "WARN" "$LAST_CI"
else
    check "CI status" "WARN" "gh CLI not available or not authenticated"
fi
echo ""

# ─── 13. Essential files exist ──────────────────────────────────────────
echo -e "${CYAN}--- Essential Files ---${NC}"

for f in llms.txt README.md CLAUDE.md CHANGELOG.md LICENSE CONTRIBUTING.md SECURITY.md; do
    # GitHub also looks under .github/ for community-health files (SECURITY.md,
    # CONTRIBUTING.md, CODE_OF_CONDUCT.md) — treat that location as equivalent.
    if [ -f "$f" ] || [ -f ".github/$f" ]; then
        check "$f exists" "PASS" ""
    else
        check "$f exists" "FAIL" "Missing"
    fi
done
echo ""

# ─── 14. Device-QA gate ─────────────────────────────────────────────────
# The autonomous cross-platform device-QA harness (umbrella #1560, slice
# #1566) informs the release — but it must NEVER be able to block it
# indefinitely.
#
# RELEASE-GATE POLICY (#1683 — deterministic, non-blocking)
# ---------------------------------------------------------
# History: the gate used to HARD-block on a pre-existing green
# `device-qa-report.json`. In practice that froze the release for 58+
# commits — a push-triggered Device QA run on `main` is killed by
# `cancel-in-progress` concurrency before the long android Maestro leg
# finishes, so no verdict was ever produced, and the orchestrator waited
# forever. The android leg is `continue-on-error` / advisory and should
# never have been able to block anything.
#
# The gate is therefore DETERMINISTIC and NON-BLOCKING:
#   - It triggers its OWN Device QA run via `gh workflow run "Device QA"`.
#     A `workflow_dispatch` run is isolated from push-concurrency
#     cancellation (#1665/#1667) — it cannot be killed by a later push.
#   - It polls THAT specific run id with a BOUNDED loop and a HARD TIMEOUT
#     (RELEASE_QA_TIMEOUT_MIN, default 60 min). No unbounded poll.
#   - BLOCKING leg  = web (Playwright): a genuine FAIL => release-gate FAIL
#     (the ONLY blocking outcome).
#   - ADVISORY legs = android (Maestro emulator) + ar (ARCore replay): a
#     failure/cancel/skip is a WARN line only, never a block — matches
#     device-qa.yml's `continue-on-error: true` on the android AND ar jobs
#     and CLAUDE.md #1651. The `ar` leg assumeTrue-SKIPs on CI (no bundled
#     recording / Play Services for AR), which must read as WARN not a hard
#     FAIL (#2433).
#   - TIMEOUT FALLBACK: if the run does not complete within the timeout the
#     gate emits `device-qa: TIMEOUT (advisory) — proceeding` and returns
#     SUCCESS. A flaky / stuck / cancelled harness can NEVER freeze a
#     release. The release always proceeds; Device QA INFORMS it.
#
# The full logic lives in `.claude/scripts/release-device-qa-gate.sh`.
# Env overrides: RELEASE_QA_TIMEOUT_MIN, RELEASE_QA_POLL_SEC,
# RELEASE_QA_REQUIRED, RELEASE_QA_ADVISORY, RELEASE_QA_REF.
#
# Two modes:
#   - If a `device-qa-report.json` is already present (e.g. a CI artifact
#     downloaded into the workspace, or a fresh local `device-qa.sh` run),
#     this section reads it directly — the fast path, no dispatch.
#     `DEVICE_QA_REPORT=<path>` overrides the location. A schemaVersion-1
#     report without `releaseGate` falls back to the legacy `status`.
#   - Otherwise it delegates to `release-device-qa-gate.sh`, which
#     dispatches + waits + grades, and can never block on a stuck harness.
echo -e "${CYAN}--- Device-QA Gate ---${NC}"

DQ_REPORT="${DEVICE_QA_REPORT:-device-qa-report.json}"
GATE_SCRIPT="$REPO_ROOT/.claude/scripts/release-device-qa-gate.sh"
if [ ! -f "$DQ_REPORT" ] && [ -x "$GATE_SCRIPT" ]; then
    # No local report — run the deterministic, non-blocking gate. It
    # dispatches its own uncancellable Device QA run, waits with a hard
    # timeout, and grades web as blocking / android+ar as advisory (#1651,
    # #2433). It exits 1 ONLY on a genuine blocking-leg (web) FAIL; timeout /
    # advisory red / dispatch failure all proceed-with-warning.
    echo -e "  No local device-qa-report.json — invoking release-device-qa-gate.sh"
    if bash "$GATE_SCRIPT"; then
        check "device-qa gate" "PASS" "deterministic gate passed (blocking leg green or proceed-with-warning)"
    else
        check "device-qa gate" "FAIL" "the blocking device-QA leg (web) failed — fix before tagging"
    fi
elif [ -f "$DQ_REPORT" ]; then
    DQ_STATUS=$(python3 -c "import json; print(json.load(open('$DQ_REPORT')).get('status','?'))" 2>/dev/null || echo "?")
    DQ_FAILED=$(python3 -c "import json; print(json.load(open('$DQ_REPORT')).get('totals',{}).get('failed','?'))" 2>/dev/null || echo "?")
    DQ_SKIPPED=$(python3 -c "import json; print(json.load(open('$DQ_REPORT')).get('totals',{}).get('skipped','?'))" 2>/dev/null || echo "?")
    # releaseGate is present from schemaVersion 2 (#1651); empty on older reports.
    DQ_GATE=$(python3 -c "import json; print(json.load(open('$DQ_REPORT')).get('releaseGate',{}).get('verdict',''))" 2>/dev/null || echo "")
    DQ_BLOCKING=$(python3 -c "import json; print(','.join(json.load(open('$DQ_REPORT')).get('releaseGate',{}).get('blockingFailed',[])))" 2>/dev/null || echo "")
    DQ_ADVISORY=$(python3 -c "import json; print(','.join(json.load(open('$DQ_REPORT')).get('releaseGate',{}).get('advisoryFailed',[])))" 2>/dev/null || echo "")

    if [ -n "$DQ_GATE" ]; then
        # schemaVersion >= 2 — graded gate.
        case "$DQ_GATE" in
            clear)
                if [ "$DQ_SKIPPED" = "0" ]; then
                    check "device-qa-report.json" "PASS" "all platforms green"
                else
                    check "device-qa-report.json" "WARN" "green but $DQ_SKIPPED platform(s) skipped — re-run device-qa.sh --ci"
                fi
                ;;
            warn)
                # Advisory leg(s) red — never silent, never a hard block (#1651).
                check "device-qa-report.json" "WARN" "advisory leg(s) did not pass: $DQ_ADVISORY — review before tagging (non-blocking, flaky emulator #1643)"
                ;;
            blocked)
                check "device-qa-report.json" "FAIL" "blocking leg(s) failed: $DQ_BLOCKING — fix before tagging"
                ;;
            *)
                check "device-qa-report.json" "FAIL" "unreadable releaseGate verdict ($DQ_GATE)"
                ;;
        esac
    else
        # schemaVersion 1 — legacy all-or-nothing reading.
        case "$DQ_STATUS" in
            passed)
                if [ "$DQ_SKIPPED" = "0" ]; then
                    check "device-qa-report.json" "PASS" "all platforms green (legacy report — no releaseGate)"
                else
                    check "device-qa-report.json" "WARN" "green but $DQ_SKIPPED platform(s) skipped — re-run device-qa.sh --ci"
                fi
                ;;
            failed)
                check "device-qa-report.json" "FAIL" "$DQ_FAILED platform(s) failed — fix before tagging"
                ;;
            *)
                check "device-qa-report.json" "FAIL" "unreadable status ($DQ_STATUS)"
                ;;
        esac
    fi
else
    # No local report AND release-device-qa-gate.sh is not available — the
    # deterministic gate path is unreachable. Surface a WARN, not a hard
    # block: a missing harness must never freeze the release (#1683).
    check "device-qa gate" "WARN" "no report and release-device-qa-gate.sh missing — run: bash .claude/scripts/device-qa.sh --platform=all"
fi
echo ""

# ─── 15. Android Vitals (prod stability) gate ───────────────────────────────
# Device-QA (section 14) validates the demo app on EMULATORS before release.
# Android Vitals is the complementary signal: the REAL crash & ANR rate across
# live Play Store users, queried from the Play Developer Reporting API
# (#1691). `play-vitals.sh` grades the 28-day user-perceived rates against
# Google Play's own bad-behaviour thresholds.
#
# ADVISORY-FIRST: the gate is non-blocking until a baseline is trusted. It
# only hard-blocks (FAIL → release blocker) when invoked with `GATE_HARD=1`.
# Any data-access problem — missing `PLAY_STORE_SERVICE_ACCOUNT_JSON`, the 403
# you get before the read-only "View app quality information" Play Console
# permission is granted, a fresh app with no data — degrades to WARN and never
# freezes a release. Set `PLAY_VITALS_HARD=1` to promote a hard-threshold
# breach to a release blocker once the numbers are trusted.
echo -e "${CYAN}--- Android Vitals (prod stability) ---${NC}"

VITALS_SCRIPT="$REPO_ROOT/.claude/scripts/play-vitals.sh"
if [ -x "$VITALS_SCRIPT" ]; then
    VITALS_JSON="$(mktemp 2>/dev/null || echo /tmp/play-vitals-$$.json)"
    VITALS_LOG="$(mktemp 2>/dev/null || echo /tmp/play-vitals-$$.log)"
    # GATE_HARD is opt-in via PLAY_VITALS_HARD so the default checklist run
    # stays advisory (#1691).
    if GATE_HARD="${PLAY_VITALS_HARD:-0}" VITALS_JSON_OUT="$VITALS_JSON" \
        bash "$VITALS_SCRIPT" > "$VITALS_LOG" 2>&1; then
        VITALS_VERDICT=$(python3 -c "import json; print(json.load(open('$VITALS_JSON')).get('verdict','?'))" 2>/dev/null || echo "?")
        VITALS_DETAIL=$(python3 -c "import json; print(json.load(open('$VITALS_JSON')).get('detail','') or '')" 2>/dev/null || echo "")
        case "$VITALS_VERDICT" in
            pass)  check "Android Vitals (crash/ANR)" "PASS" "$VITALS_DETAIL" ;;
            warn)  check "Android Vitals (crash/ANR)" "WARN" "${VITALS_DETAIL:-advisory — review prod stability}" ;;
            *)     check "Android Vitals (crash/ANR)" "WARN" "advisory — verdict '$VITALS_VERDICT' (see log)" ;;
        esac
    else
        # Non-zero exit only happens under PLAY_VITALS_HARD=1 on a real breach.
        VITALS_DETAIL=$(python3 -c "import json; print(json.load(open('$VITALS_JSON')).get('detail','') or '')" 2>/dev/null || echo "")
        check "Android Vitals (crash/ANR)" "FAIL" "hard-threshold breach — ${VITALS_DETAIL:-see play-vitals.sh output}"
    fi
    rm -f "$VITALS_JSON" "$VITALS_LOG"
else
    check "Android Vitals (crash/ANR)" "WARN" "play-vitals.sh missing — prod stability not checked"
fi
echo ""

# ─── 16. Store preflight (Apple agreements / App Review / cert expiry) ───────
# Sections 14 (device-QA) and 15 (vitals) validate the *build*. Section 16
# validates the *account*: the human-only store blockers that silently 403 a
# deploy — an expired Apple Program License Agreement (REQUIRED_AGREEMENTS_
# MISSING_OR_EXPIRED), an App Review rejection (2.1 / 3.1.1 #2534), or a
# distribution cert / profile about to lapse. These are what stalled 4.18–4.21
# for days, undetected, at deploy minute 40 — this surfaces them BEFORE the tag
# (#2612 P1).
#
# ADVISORY-FIRST, exactly like §14/§15: a real blocker is graded but only
# hard-blocks under STORE_PREFLIGHT_HARD=1. No creds locally (the usual case)
# → the script SKIPs honestly and this reads WARN, never a fake PASS and never
# a FAIL. Reuses app-store.yml's ASC secrets — no new scope.
echo -e "${CYAN}--- Store preflight (Apple agreements / review / certs) ---${NC}"

PREFLIGHT_SCRIPT="$REPO_ROOT/.claude/scripts/store-preflight.sh"
if [ -x "$PREFLIGHT_SCRIPT" ]; then
    PREFLIGHT_JSON="$(mktemp 2>/dev/null || echo /tmp/store-preflight-$$.json)"
    PREFLIGHT_LOG="$(mktemp 2>/dev/null || echo /tmp/store-preflight-$$.log)"
    # GATE_HARD is opt-in via STORE_PREFLIGHT_HARD so the default run stays
    # advisory. The script exits non-zero ONLY under GATE_HARD on a blocker.
    if GATE_HARD="${STORE_PREFLIGHT_HARD:-0}" STORE_PREFLIGHT_JSON_OUT="$PREFLIGHT_JSON" \
        bash "$PREFLIGHT_SCRIPT" > "$PREFLIGHT_LOG" 2>&1; then
        PF_VERDICT=$(python3 -c "import json; print(json.load(open('$PREFLIGHT_JSON')).get('verdict','?'))" 2>/dev/null || echo "?")
        PF_DETAIL=$(python3 -c "import json; print(json.load(open('$PREFLIGHT_JSON')).get('detail','') or '')" 2>/dev/null || echo "")
        case "$PF_VERDICT" in
            pass)    check "Store preflight (ASC)" "PASS" "$PF_DETAIL" ;;
            warn)    check "Store preflight (ASC)" "WARN" "${PF_DETAIL:-review store state before tagging}" ;;
            blocked) check "Store preflight (ASC)" "WARN" "store blocker present (advisory) — ${PF_DETAIL:-see store-preflight.sh}" ;;
            skip)    check "Store preflight (ASC)" "WARN" "skipped — ${PF_DETAIL:-no ASC creds available}" ;;
            *)       check "Store preflight (ASC)" "WARN" "advisory — verdict '$PF_VERDICT' (see log)" ;;
        esac
    else
        # A non-zero exit is EXPECTED only under STORE_PREFLIGHT_HARD=1 on a real
        # `blocked` verdict — that alone is a release FAIL. ANY other non-zero
        # (the advisory script itself crashed, jq/openssl missing, a bug) must
        # NOT freeze the release: the preflight is advisory-first, so a broken
        # preflight reads WARN ("store blockers unchecked"), never a hard block.
        PF_VERDICT=$(python3 -c "import json; print(json.load(open('$PREFLIGHT_JSON')).get('verdict','?'))" 2>/dev/null || echo "?")
        PF_DETAIL=$(python3 -c "import json; print(json.load(open('$PREFLIGHT_JSON')).get('detail','') or '')" 2>/dev/null || echo "")
        if [ "${STORE_PREFLIGHT_HARD:-0}" = "1" ] && [ "$PF_VERDICT" = "blocked" ]; then
            check "Store preflight (ASC)" "FAIL" "store blocker — ${PF_DETAIL:-see store-preflight.sh output}"
        else
            check "Store preflight (ASC)" "WARN" "preflight errored — store blockers unchecked (advisory)"
        fi
    fi
    rm -f "$PREFLIGHT_JSON" "$PREFLIGHT_LOG"
else
    check "Store preflight (ASC)" "WARN" "store-preflight.sh missing — store blockers not checked"
fi
echo ""

# ─── 17. Store listing drift (Play + App Store, read-only) ──────────────────
# Phase C of #2612 (store-as-code). maintenance.yml already runs this read-only
# diff daily, but into a step summary nobody reads at release time; §17 brings
# the same live-vs-repo check into the release gate, so a store that has
# silently drifted from the repo is surfaced BEFORE the tag rather than after
# the next blind sync overwrites it (the #2794 failure mode).
#
# ADVISORY-FIRST, exactly like §16: drift is a WARN, never a hard block. An
# advisory WARN is NOT the blocking "release gate" the daily job's own comment
# reserves for a CONFIRMED checksum verdict — promoting §17 to blocking is a
# deliberate later step, once the screenshot-checksum convention is hardened
# past the n=1 console sample #2612 Phase C validated. `--dry-run
# --fail-on-drift` opens a throwaway read, writes NOTHING, and exits 3 only on
# drift; no store creds locally (the usual case) → the script SKIPs (exit 0 +
# [skip]) and this reads WARN "not measured", never a fake PASS. Reuses the
# maintenance.yml secrets — no new scope.
echo -e "${CYAN}--- Store listing drift (Play + App Store, read-only) ---${NC}"

listing_drift_check() {
    # $1 = check label · $2 = script basename under store-sync/. A credential-
    # less run SKIPs before the lazy third-party imports, so a plain python3
    # call (no venv, no deps) is enough for the usual local path.
    local label="$1" script="$REPO_ROOT/.claude/scripts/store-sync/$2" log rc
    if [ ! -f "$script" ]; then
        check "$label" "WARN" "$2 missing — listing drift not checked"
        return
    fi
    log="$(mktemp 2>/dev/null || echo "/tmp/listing-drift-$$-$2.log")"
    set +e
    python3 "$script" --dry-run --fail-on-drift > "$log" 2>&1
    rc=$?
    set -e
    if [ "$rc" -eq 3 ]; then
        check "$label" "WARN" "drift vs live store — reconcile before tagging ($2 --dry-run)"
    elif [ "$rc" -eq 0 ] && grep -q '^\[skip\]' "$log"; then
        check "$label" "WARN" "skipped — drift not measured (no creds, or nothing to diff)"
    elif [ "$rc" -eq 0 ]; then
        check "$label" "PASS" "live listing matches the repo"
    else
        # Exit is neither 0 (clean/skip) nor 3 (drift): the diff itself broke.
        # Advisory — a broken check is "unchecked", never a silent PASS.
        check "$label" "WARN" "drift check errored (exit $rc) — listing unchecked (advisory)"
    fi
    rm -f "$log"
}

listing_drift_check "Play listing drift"      "play_listing.py"
listing_drift_check "App Store listing drift" "asc_listing.py"
echo ""

# ─── Summary ───────────────────────────────────────────────────────────
echo -e "${CYAN}=== Release Readiness Summary ===${NC}"
echo ""

if [ "$BLOCKERS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
    echo -e "${GREEN}READY TO RELEASE v$TARGET_VERSION${NC}"
    echo "  Next steps:"
    echo "    git tag v$TARGET_VERSION"
    echo "    git push origin main --tags"
    exit 0
elif [ "$BLOCKERS" -eq 0 ]; then
    echo -e "${YELLOW}RELEASE POSSIBLE with $WARNINGS warning(s)${NC}"
    echo "  Review warnings above before proceeding."
    exit 0
else
    echo -e "${RED}NOT READY — $BLOCKERS blocker(s), $WARNINGS warning(s)${NC}"
    echo "  Fix all FAIL items before releasing."
    exit 1
fi
