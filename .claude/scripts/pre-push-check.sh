#!/bin/bash
# Pre-push quality gate — run before every push to main
# Usage: bash .claude/scripts/pre-push-check.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/gradle-run.sh
source "$SCRIPT_DIR/lib/gradle-run.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0
# Steps that could NOT reach a verdict (Gradle died before judging the code,
# a report was never produced, …). These are neither ✓ nor ✗ — but a gate that
# did not run is not a gate that passed, so they still block the push.
INCOMPLETE=0

# Gradle output is KEPT, never sent to /dev/null: the swallowed stderr is
# exactly what would have shown that a "screenshot regression" was really a
# dead build daemon (measured 2026-08-06 — see step 5). Logs survive the run so
# they can be read afterwards.
LOG_DIR="${TMPDIR:-/tmp}"
LOG_DIR="${LOG_DIR%/}/sceneview-pre-push"
mkdir -p "$LOG_DIR"

# Report a failed Gradle step, distinguishing an ENVIRONMENT failure from a
# real one. The specific diagnosis ($4) is only pronounced when the log does
# not carry an infrastructure signature.
#   gate_gradle_failure <label> <logfile> <exit-code> <real-diagnosis>
gate_gradle_failure() {
    local label="$1" log="$2" code="$3" diag="$4"
    local reason
    reason="$(gradle_infra_reason "$log" "$code")"
    if [ -n "$reason" ]; then
        echo -e "${YELLOW}  ⚠ $label did not run to a verdict — Gradle infrastructure failure: $reason${NC}"
        gradle_log_tail "$log" 12
        echo -e "      Full log: $log"
        INCOMPLETE=$((INCOMPLETE + 1))
    else
        echo -e "${RED}  ✗ $diag${NC}"
        gradle_log_tail "$log" 20
        echo -e "      Full log: $log"
        ERRORS=$((ERRORS + 1))
    fi
}

echo "═══════════════════════════════════════════"
echo "  SceneView Pre-Push Quality Gate"
echo "═══════════════════════════════════════════"

# 1. Android compilation
# A mass of `Unresolved reference` on symbols the diff never touched is very
# often a poisoned Gradle build cache (an empty FROM-CACHE entry), not a real
# break — `rm -rf <module>/build && ./gradlew … --no-build-cache` is the only
# measured remedy. That is why the log tail is printed instead of hidden.
echo -e "\n${YELLOW}[1/11] Compiling sceneview...${NC}"
if gradle_run "$LOG_DIR/compile-sceneview.log" :sceneview:compileReleaseKotlin; then
    echo -e "${GREEN}  ✓ sceneview compiles${NC}"
else
    gate_gradle_failure "sceneview compilation" "$LOG_DIR/compile-sceneview.log" $? \
        "sceneview FAILED to compile"
fi

echo -e "${YELLOW}[2/11] Compiling arsceneview...${NC}"
if gradle_run "$LOG_DIR/compile-arsceneview.log" :arsceneview:compileReleaseKotlin; then
    echo -e "${GREEN}  ✓ arsceneview compiles${NC}"
else
    gate_gradle_failure "arsceneview compilation" "$LOG_DIR/compile-arsceneview.log" $? \
        "arsceneview FAILED to compile"
fi

# 2. Unit tests
echo -e "\n${YELLOW}[3/11] Running sceneview unit tests...${NC}"
if gradle_run "$LOG_DIR/test-sceneview.log" :sceneview:test; then
    echo -e "${GREEN}  ✓ sceneview tests pass${NC}"
else
    gate_gradle_failure "sceneview unit tests" "$LOG_DIR/test-sceneview.log" $? \
        "sceneview tests FAILED"
fi

echo -e "${YELLOW}[4/11] Running arsceneview unit tests...${NC}"
if gradle_run "$LOG_DIR/test-arsceneview.log" :arsceneview:testDebugUnitTest; then
    echo -e "${GREEN}  ✓ arsceneview tests pass${NC}"
else
    gate_gradle_failure "arsceneview unit tests" "$LOG_DIR/test-arsceneview.log" $? \
        "arsceneview tests FAILED"
fi

# 3. Screenshot tests (Roborazzi — Android, JVM, no emulator)
#
# Two ways this step used to lie, BOTH measured 2026-08-06 on this repo:
#
#  (a) FALSE RED. `--quiet 2>/dev/null` plus "any non-zero exit == screenshot
#      regression" reported a regression when Gradle had in fact died with
#      `Gradle build daemon disappeared unexpectedly` (daemon contention on a
#      Mac running several builds). Reproduced identically on a pristine clone
#      of main — no golden and no source change involved — and re-running the
#      task alone gave BUILD SUCCESSFUL / exit 0. The swallowed stderr was the
#      only place that said so, and no Roborazzi report existed because the
#      comparison never ran.
#
#  (b) FALSE GREEN. The goldens under src/test/snapshots/ are not declared
#      inputs of any task, so re-running after mutating a golden by 8000 red
#      pixels gave `verifyRoborazziDebug UP-TO-DATE` / `BUILD SUCCESSFUL in 1s`
#      — and this step printed "✓ Android screenshots match goldens" having
#      compared nothing at all.
#
# Both are fixed by the same rule: the verdict comes from the COMPARISON
# REPORT, never from the exit code alone.
#   - `:samples:android-demo:testDebugUnitTest --rerun` forces the comparison
#     to happen, so (b) cannot recur;
#   - `results-summary.json` must be NEWER than a marker taken just before the
#     run, otherwise nothing was compared and neither ✓ nor "regression" may be
#     printed. Measured: the report is rewritten by `finalizeTestRoborazziDebug`
#     even when the test task FAILS (`changed:1` on a red run), and left
#     untouched on an UP-TO-DATE run — so its mtime discriminates all four
#     cases (pass / real diff / skipped / infra death).
echo -e "\n${YELLOW}[5/11] Verifying Android screenshot goldens...${NC}"
SNAPSHOTS_DIR="samples/android-demo/src/test/snapshots"
RR_SUMMARY="samples/android-demo/build/test-results/roborazzi/debug/results-summary.json"
RR_LOG="$LOG_DIR/roborazzi.log"
RR_MARKER="$LOG_DIR/roborazzi.marker"
if [ -d "$SNAPSHOTS_DIR" ] && [ "$(ls -A $SNAPSHOTS_DIR 2>/dev/null)" ]; then
    rm -f "$RR_MARKER"
    touch "$RR_MARKER"
    if gradle_run "$RR_LOG" :samples:android-demo:verifyRoborazziDebug \
                            :samples:android-demo:testDebugUnitTest --rerun; then
        RR_EXIT=0
    else
        RR_EXIT=$?
    fi

    # Positive proof that goldens were actually read and compared.
    RR_DIFFS="$(roborazzi_fresh_diff_count "$RR_SUMMARY" "$RR_MARKER")"

    case "$RR_DIFFS" in
        0)
            if [ "$RR_EXIT" -eq 0 ]; then
                echo -e "${GREEN}  ✓ Android screenshots match goldens${NC}"
            else
                # The comparison ran and every golden matched, so the red build
                # is something else in :samples:android-demo's test suite.
                echo -e "${RED}  ✗ :samples:android-demo tests FAILED — every screenshot matched its golden, so this is NOT a golden regression${NC}"
                gradle_log_tail "$RR_LOG" 20
                echo -e "      Full log: $RR_LOG"
                ERRORS=$((ERRORS + 1))
            fi
            ;;
        ''|*[!0-9]*)
            # No fresh report => nothing was compared. Name the real cause.
            RR_REASON="$(gradle_infra_reason "$RR_LOG" "$RR_EXIT")"
            [ -n "$RR_REASON" ] || RR_REASON="the run produced no Roborazzi comparison report"
            echo -e "${YELLOW}  ⚠ screenshots NOT verified — $RR_REASON${NC}"
            gradle_log_tail "$RR_LOG" 12
            echo -e "      Full log: $RR_LOG"
            INCOMPLETE=$((INCOMPLETE + 1))
            ;;
        *)
            echo -e "${RED}  ✗ Android screenshot regression — $RR_DIFFS golden(s) differ${NC}"
            echo -e "      Diff images: samples/android-demo/build/outputs/roborazzi/*_compare.png"
            echo -e "      Intentional change? ./gradlew :samples:android-demo:recordRoborazziDebug"
            ERRORS=$((ERRORS + 1))
            ;;
    esac
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
echo -e "${YELLOW}[6/11] Verifying iOS screenshot goldens...${NC}"
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
elif python3 .claude/scripts/generate-ios-goldens.py verify "$IOS_GOLDEN_NAME" > "$LOG_DIR/ios-goldens.log" 2>&1; then
    echo -e "${GREEN}  ✓ iOS screenshots match goldens${NC}"
# Same rule as step 5: the harness exits 1 both on a real pixel diff AND on any
# crash (Pillow/numpy missing, `simctl io screenshot` failing on a sim that just
# went away). Only the "REGRESSION" line it prints proves a comparison actually
# happened — without it, this is a tooling failure, not a visual one.
elif grep -q "REGRESSION" "$LOG_DIR/ios-goldens.log" 2>/dev/null; then
    echo -e "${RED}  ✗ iOS screenshot regression — app foregrounded on About, same sim model as the golden? Intentional change: python3 .claude/scripts/generate-ios-goldens.py capture ${IOS_GOLDEN_NAME}${NC}"
    sed 's/^/      /' "$LOG_DIR/ios-goldens.log"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${YELLOW}  ⚠ iOS goldens NOT verified — the comparison harness itself failed:${NC}"
    tail -12 "$LOG_DIR/ios-goldens.log" 2>/dev/null | sed 's/^/      /'
    INCOMPLETE=$((INCOMPLETE + 1))
fi

# 5. Version sync
echo -e "\n${YELLOW}[7/11] Checking version sync...${NC}"
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
echo -e "\n${YELLOW}[8/11] Validating website JS...${NC}"
NODE_CMD=$(which node 2>/dev/null || which /opt/homebrew/bin/node 2>/dev/null || which /usr/local/bin/node 2>/dev/null || echo "")
if [ -n "$NODE_CMD" ]; then
    if [ ! -f website-static/js/sceneview.js ]; then
        # "has syntax errors" would be a lie about a file that is not there.
        echo -e "${RED}  ✗ website-static/js/sceneview.js is MISSING${NC}"
        ERRORS=$((ERRORS + 1))
    elif "$NODE_CMD" -c website-static/js/sceneview.js > "$LOG_DIR/website-js.log" 2>&1; then
        echo -e "${GREEN}  ✓ sceneview.js syntax OK${NC}"
    else
        echo -e "${RED}  ✗ sceneview.js has syntax errors:${NC}"
        tail -10 "$LOG_DIR/website-js.log" | sed 's/^/      /'
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ node not found, skipping JS validation${NC}"
fi

# 7. Demo app asset references
# Scans every samples/* for broken bundled paths or dead CDN URLs so the
# class of bugs fixed in session 34 (TV demo pointing at non-existent
# models/*.glb, web-demo pointing at 404 CDN URLs) cannot come back.
echo -e "\n${YELLOW}[9/11] Validating demo app asset references...${NC}"
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
echo -e "\n${YELLOW}[10/11] Checking agent skill drift...${NC}"
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

# Public-API ABI gate (#2723): the committed .api dumps are a BLOCKING CI
# check — catch an unintentional public-API change locally before CI does.
# Intentional changes: ./gradlew apiDump, review + commit the .api diff.
echo -e "\n${YELLOW}[11/11] Checking public-API ABI (apiCheck)...${NC}"
if [ -f gradlew ]; then
    if gradle_run "$LOG_DIR/api-check.log" apiCheck; then
        echo -e "${GREEN}  ✓ public API matches the committed .api dumps${NC}"
    else
        # "the public-API surface drifted" is a claim about the CODE — do not
        # make it when Gradle died before comparing anything.
        API_EXIT=$?
        API_REASON="$(gradle_infra_reason "$LOG_DIR/api-check.log" "$API_EXIT")"
        if [ -n "$API_REASON" ]; then
            echo -e "${YELLOW}  ⚠ apiCheck did not run to a verdict — Gradle infrastructure failure: $API_REASON${NC}"
            gradle_log_tail "$LOG_DIR/api-check.log" 12
            INCOMPLETE=$((INCOMPLETE + 1))
        else
            echo -e "${RED}  ✗ apiCheck failed — public-API surface drifted:${NC}"
            gradle_log_tail "$LOG_DIR/api-check.log" 20
            echo -e "      Intentional change? Run ./gradlew apiDump and commit the .api diff."
            ERRORS=$((ERRORS + 1))
        fi
        echo -e "      Full log: $LOG_DIR/api-check.log"
    fi
else
    echo -e "${YELLOW}  ⚠ gradlew not found, skipping${NC}"
fi

# Summary
#
# "could not run" is reported SEPARATELY from "failed" — conflating them is the
# whole bug this script was carrying. It still exits non-zero: an unrun gate is
# not a passed gate. Infrastructure failures on this host are usually daemon
# contention, so the remedy is to re-run once nothing else is building.
echo -e "\n═══════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ] && [ "$INCOMPLETE" -eq 0 ]; then
    echo -e "${GREEN}  ✓ ALL CHECKS PASSED — safe to push${NC}"
    exit 0
elif [ "$ERRORS" -eq 0 ]; then
    echo -e "${YELLOW}  ⚠ $INCOMPLETE CHECK(S) COULD NOT RUN — no check failed, but the gate is INCOMPLETE${NC}"
    echo -e "${YELLOW}    Re-run when no other build is competing for the Gradle daemon:${NC}"
    echo -e "${YELLOW}      ./gradlew --stop && bash .claude/scripts/pre-push-check.sh${NC}"
    echo -e "${YELLOW}    Logs: $LOG_DIR${NC}"
    exit 1
else
    echo -e "${RED}  ✗ $ERRORS CHECK(S) FAILED — DO NOT PUSH${NC}"
    if [ "$INCOMPLETE" -gt 0 ]; then
        echo -e "${YELLOW}  ⚠ plus $INCOMPLETE check(s) that could not run at all${NC}"
    fi
    echo -e "      Logs: $LOG_DIR"
    exit 1
fi
