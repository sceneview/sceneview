#!/bin/bash
# Pre-push quality gate — run before every push to main
# Usage: bash .claude/scripts/pre-push-check.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/gradle-run.sh
source "$SCRIPT_DIR/lib/gradle-run.sh"
# shellcheck source=lib/log-dir.sh
source "$SCRIPT_DIR/lib/log-dir.sh"
# shellcheck source=lib/node-resolve.sh
source "$SCRIPT_DIR/lib/node-resolve.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0
# Steps that could NOT reach a verdict (Gradle died before judging the code,
# a report was never produced, …). These are neither ✓ nor ✗ — but a gate that
# did not run is not a gate that passed, so they still block the push.
INCOMPLETE=0
# Legs that never ran because a TOOL is absent from this host (node, gradlew,
# dash, shellcheck). Unlike INCOMPLETE these do not block the push — CI still
# gates them — but the final line may not claim "ALL CHECKS PASSED" while some
# of them were never attempted. A verdict that reads complete because the
# missing half printed a ⚠ nobody counted is the #2988 shape one level down.
NOT_COVERED=0
# Set by gradle_report_failure when the host toolchain is not configured, so
# the final summary repeats the fix instead of the (useless here) "re-run when
# the daemon is free" advice. See #3065.
SETUP_FIX=""

# Gradle output is KEPT, never sent to /dev/null: the swallowed stderr is
# exactly what would have shown that a "screenshot regression" was really a
# dead build daemon (measured 2026-08-06 — see step 5). Logs survive the run so
# they can be read afterwards.
# 0700, not the ambient umask: Gradle's own error output quotes the offending
# value, and the demo build injects ARCORE_API_KEY / SKETCHFAB_API_KEY into the
# manifest and BuildConfig — so a manifest-merger or javac failure can write a
# live key into one of these logs. With TMPDIR unset the fallback is a shared
# world-readable /tmp.
#
# The directory is PER WORKTREE, and that is not cosmetic: it used to be one
# path for the whole machine while this repo runs many worktrees in parallel,
# so concurrent runs overwrote each other's files — `Full log:` could name
# another session's failure, and a neighbour truncating leg 19's `selftests.txt`
# under this run's open descriptor printed a pass count over a loop that ran
# fewer (#3074/#3131/#3137). The rule and its tests live in lib/log-dir.sh.
CHECKOUT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$(pre_push_log_dir_create "$CHECKOUT_ROOT")"

# Report a failed Gradle step, distinguishing a HOST SETUP failure and an
# ENVIRONMENT failure from a real one. The specific diagnosis ($4) is only
# pronounced when the log carries neither signature. See
# lib/gradle-run.sh → gradle_report_failure.
#   gate_gradle_failure <label> <logfile> <exit-code> <real-diagnosis> [note]
gate_gradle_failure() {
    gradle_report_failure "$1" "$2" "$3" "$4" "" "${5:-}"
}

# Thin alias over lib/gradle-run.sh → script_report_failure, which carries the
# rule and the hermetic tests (test-gradle-run.sh).
#   gate_script_failure <label> <log> <code> <real-diagnosis> <proof-regex> [remedy]
gate_script_failure() {
    script_report_failure "$@"
}

# An `if [ -f <gate script> ]` with no else prints its banner and records
# nothing — the leg silently evaporates and the run still ends on "ALL CHECKS
# PASSED". These scripts are committed, so an absent one means a broken or
# partial checkout, not a legitimate skip: report it as INCOMPLETE, which
# keeps the gate from claiming a clean bill of health it did not earn.
missing_gate_script() {
    echo -e "${YELLOW}  ⚠ $1 not found — NOT checked here (CI still gates it)${NC}"
    INCOMPLETE=$((INCOMPLETE + 1))
}

# The summary must say what this run did NOT cover on every path, not only the
# green one. A red run is the run whose output gets pasted into a PR thread and
# argued over — omitting the not-covered legs there invites "the gate looked at
# everything and found one problem", when it looked at less than everything.
not_covered_recap() {
    [ "$NOT_COVERED" -gt 0 ] || return 0
    echo -e "${YELLOW}    (+ $NOT_COVERED leg(s) not covered on this host — see the ⚠ lines above; CI gates them)${NC}"
}

echo "═══════════════════════════════════════════"
echo "  SceneView Pre-Push Quality Gate"
echo "═══════════════════════════════════════════"

# 1. Android compilation
# A mass of `Unresolved reference` on symbols the diff never touched is very
# often a poisoned Gradle build cache (an empty FROM-CACHE entry), not a real
# break — `rm -rf <module>/build && ./gradlew … --no-build-cache` is the only
# measured remedy. That is why the log tail is printed instead of hidden.
echo -e "\n${YELLOW}[1/21] Compiling sceneview...${NC}"
if gradle_run "$LOG_DIR/compile-sceneview.log" :sceneview:compileReleaseKotlin; then
    echo -e "${GREEN}  ✓ sceneview compiles${NC}"
else
    gate_gradle_failure "sceneview compilation" "$LOG_DIR/compile-sceneview.log" $? \
        "sceneview FAILED to compile" "sceneview was never compiled"
fi

echo -e "${YELLOW}[2/21] Compiling arsceneview...${NC}"
if gradle_run "$LOG_DIR/compile-arsceneview.log" :arsceneview:compileReleaseKotlin; then
    echo -e "${GREEN}  ✓ arsceneview compiles${NC}"
else
    gate_gradle_failure "arsceneview compilation" "$LOG_DIR/compile-arsceneview.log" $? \
        "arsceneview FAILED to compile" "arsceneview was never compiled"
fi

# 2. Unit tests
echo -e "\n${YELLOW}[3/21] Running sceneview unit tests...${NC}"
if gradle_run "$LOG_DIR/test-sceneview.log" :sceneview:test; then
    echo -e "${GREEN}  ✓ sceneview tests pass${NC}"
else
    gate_gradle_failure "sceneview unit tests" "$LOG_DIR/test-sceneview.log" $? \
        "sceneview tests FAILED" "no sceneview test ever ran"
fi

# The other four tasks of ci.yml's `unit-test` job, in one Gradle invocation.
#
# Three of them ran in CI and nowhere here until 2026-08-16, and were named in
# neither the legs below nor the deliberately-not-covered list — a gap by the
# rule this file opens with, not a decision. `:samples:android-tv-demo:` had
# been wired into CI the day before by #3193, whose entire subject was a test
# suite no workflow invoked; the fix wired CI and left this gate one storey
# behind. `test-ci-parity-gradle-tasks.sh` (leg 19) now fails on any task CI
# runs that is neither run nor excluded here in writing, so the next task added
# to that job cannot repeat it silently.
echo -e "${YELLOW}[4/21] Running the remaining CI unit-test tasks...${NC}"
if gradle_run "$LOG_DIR/test-arsceneview.log" \
        :arsceneview:testDebugUnitTest \
        :sceneview-core:androidTest \
        :samples:common:testDebugUnitTest \
        :samples:android-tv-demo:testDebugUnitTest; then
    echo -e "${GREEN}  ✓ arsceneview, sceneview-core, samples:common and TV demo tests pass${NC}"
else
    gate_gradle_failure "arsceneview / sceneview-core / samples unit tests" \
        "$LOG_DIR/test-arsceneview.log" $? \
        "unit tests FAILED" "no unit test ever ran in this leg"
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
#     to happen, so (b) cannot recur. COST: `--rerun` binds to the task it
#     follows, so this re-executes that module's entire unit-test suite (349
#     tests) on every pre-push instead of the ~1 s UP-TO-DATE path — roughly a
#     minute more per run. That is the price of the check meaning anything;
#   - `results-summary.json` must be NEWER than a marker taken just before the
#     run, otherwise nothing was compared and neither ✓ nor "regression" may be
#     printed. Measured: the report is rewritten by `finalizeTestRoborazziDebug`
#     even when the test task FAILS (`changed:1` on a red run), and left
#     untouched on an UP-TO-DATE run — so its mtime discriminates all four
#     cases (pass / real diff / skipped / infra death).
echo -e "\n${YELLOW}[5/21] Verifying Android screenshot goldens...${NC}"
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
                # is something else in :samples:android-demo's test suite —
                # UNLESS the run never reached a verdict at all. This branch
                # used to print "tests FAILED" unconditionally, and did exactly
                # that on a run whose only error was `Timeout has been exceeded`
                # (the 25-min task timeout firing on a host down to 2 Gi of free
                # disk): a hardcoded diagnosis naming a culprit the log never
                # named. `gate_gradle_failure` is the same triage every other
                # Gradle leg already used; it downgrades to ⚠ INCOMPLETE, which
                # still keeps the gate red, so nothing is waved through.
                gate_gradle_failure ":samples:android-demo tests" "$RR_LOG" "$RR_EXIT" \
                    ":samples:android-demo tests FAILED — every screenshot matched its golden, so this is NOT a golden regression"
            fi
            ;;
        ''|*[!0-9]*)
            # No fresh report => nothing was compared. Name the real cause.
            RR_REASON="$(gradle_setup_reason "$RR_LOG")"
            RR_FIX=""
            if [ -n "$RR_REASON" ]; then
                RR_FIX="$(gradle_setup_fix "$RR_LOG")"
                [ -n "$SETUP_FIX" ] || SETUP_FIX="$RR_FIX"
            else
                RR_REASON="$(gradle_infra_reason "$RR_LOG" "$RR_EXIT")"
            fi
            [ -n "$RR_REASON" ] || RR_REASON="the run produced no Roborazzi comparison report"
            echo -e "${YELLOW}  ⚠ screenshots NOT verified — $RR_REASON${NC}"
            gradle_log_tail "$RR_LOG" 12
            [ -n "$RR_FIX" ] && echo -e "${YELLOW}      Fix: $RR_FIX${NC}"
            echo -e "      Full log: $RR_LOG"
            INCOMPLETE=$((INCOMPLETE + 1))
            ;;
        *)
            echo -e "${RED}  ✗ Android screenshot regression — $RR_DIFFS golden(s) differ${NC}"
            echo -e "      Diff images: samples/android-demo/build/outputs/roborazzi/*_compare.png"
            echo -e "      Intentional change? ./gradlew :samples:android-demo:recordRoborazziDebug"
            # The forced rerun executes the module's WHOLE unit-test suite, not
            # just the screenshot comparisons — so a run can carry both a golden
            # diff and an unrelated test failure. Point at the log either way.
            echo -e "      Full log (may also hold unrelated test failures): $RR_LOG"
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
echo -e "${YELLOW}[6/21] Verifying iOS screenshot goldens...${NC}"
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
echo -e "\n${YELLOW}[7/21] Checking version sync...${NC}"
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
echo -e "\n${YELLOW}[8/21] Validating website JS...${NC}"
NODE_CMD=$(resolve_node || echo "")
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
    echo -e "${YELLOW}  ⚠ node not found — JS validation NOT checked here (CI still gates it)${NC}"
    NOT_COVERED=$((NOT_COVERED + 1))
fi

# 7. Demo app asset references
# Scans every samples/* for broken bundled paths or dead CDN URLs so the
# class of bugs fixed in session 34 (TV demo pointing at non-existent
# models/*.glb, web-demo pointing at 404 CDN URLs) cannot come back.
echo -e "\n${YELLOW}[9/21] Validating demo app asset references...${NC}"
# --no-cdn to keep pre-push fast; CI runs the full check with CDN hits.
ASSETS_LOG="$LOG_DIR/validate-demo-assets.log"
if bash .claude/scripts/validate-demo-assets.sh --no-cdn > "$ASSETS_LOG" 2>&1; then
    echo -e "${GREEN}  ✓ All demo asset refs resolve${NC}"
else
    # The summary block is only reached when the scan completed, so it is the
    # cue that "assets are missing" is a finding and not a crash on the way.
    gate_script_failure "demo asset references" "$ASSETS_LOG" $? \
        "Demo apps reference assets that don't exist:" \
        "(Missing bundled|Broken CDN|Undeclared in catalog|Strict mode: stopping)"
fi

# 8. SceneView agent skill drift
# Catches the case where a library API was renamed/removed without updating
# `agents/sceneview/` (the published android-CLI agent skill). The same check
# runs in quality-gate.sh, ci.yml and daily via maintenance.yml — but
# the lighter pre-push gate skipped it, so a skill-only push could land drift
# without ever hitting quality-gate.sh. Invoke it directly here too.
echo -e "\n${YELLOW}[10/21] Checking agent skill drift...${NC}"
if [ -f .claude/scripts/check-sceneview-skill.sh ]; then
    SKILL_LOG="$LOG_DIR/skill-drift.log"
    if bash .claude/scripts/check-sceneview-skill.sh > "$SKILL_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ agents/sceneview/ in sync with library source${NC}"
    else
        gate_script_failure "agent skill drift" "$SKILL_LOG" $? \
            "Agent skill drift — agents/sceneview/ out of sync:" \
            "Skill drift check failed"
    fi
else
    echo -e "${YELLOW}  ⚠ check-sceneview-skill.sh not found — NOT checked here (CI still gates it)${NC}"
    NOT_COVERED=$((NOT_COVERED + 1))
fi

# 9. GPT knowledge base drift (#3026).
# `gpt/knowledge-*.md` is GENERATED from llms.txt and gated in ci.yml →
# repo-hygiene. Nothing ran it locally — not this script, not quality-gate.sh,
# not impact-check.sh — so editing llms.txt passed every local gate and only
# turned red on CI. That is exactly what happened to the PR that added this
# leg. Sub-second (a node regenerate-and-compare, no write).
echo -e "\n${YELLOW}[11/21] Checking GPT knowledge base drift...${NC}"
if [ -f tools/generate-gpt-knowledge.js ] && [ -n "${NODE_CMD:-$(resolve_node || true)}" ]; then
    GPT_LOG="$LOG_DIR/gpt-knowledge-drift.log"
    # Not `${NODE_CMD:-node}`: the bare word is exactly the lookup that fails on
    # an nvm-only host, so the fallback would reintroduce the bug it guards.
    if "${NODE_CMD:-$(resolve_node)}" tools/generate-gpt-knowledge.js --check > "$GPT_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ gpt/knowledge-*.md in sync with llms.txt${NC}"
    else
        # `DRIFT:` is printed per drifted file; the generator's OTHER exit-1
        # path (a bucket with zero sections, i.e. llms.txt restructured) is a
        # tooling failure and must not be reported as drift.
        gate_script_failure "gpt/knowledge-*.md" "$GPT_LOG" $? \
            "gpt/knowledge-*.md drifted from llms.txt:" \
            "^DRIFT: gpt/" \
            "Fix: node tools/generate-gpt-knowledge.js"
    fi
else
    echo -e "${YELLOW}  ⚠ node or tools/generate-gpt-knowledge.js not found — NOT checked here (CI still gates it)${NC}"
    NOT_COVERED=$((NOT_COVERED + 1))
fi

# Vendored-download hardening gate. Passes silently while nothing builds
# third_party/filament-kmp; fails the moment something does and its downloads
# are still unverified / its symlink extraction unvalidated.
echo -e "\n${YELLOW}[12/21] Checking the vendored download chain...${NC}"
if [ -f .claude/scripts/check-vendored-download-safety.sh ]; then
    VENDORED_LOG="$LOG_DIR/vendored-dl.log"
    if bash .claude/scripts/check-vendored-download-safety.sh > "$VENDORED_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ vendored download chain: not built, or hardened${NC}"
    else
        # BOTH of the checker's genuine-finding exits, not just the tallied
        # one: it also exits 1 with `FAIL  <downloads file> is missing, but
        # something builds …` before it ever reaches the "requirement(s)
        # unmet" tally. A cue covering only the tally would have filed that
        # real finding as "could not run" — the very mistake this gate is
        # being fixed for (#3065).
        gate_script_failure "vendored download chain" "$VENDORED_LOG" $? \
            "vendored download chain is BUILT but unsafe:" \
            "(requirement\(s\) unmet|is missing, but something builds)"
    fi
else
    missing_gate_script "check-vendored-download-safety.sh"
fi

# Self-hosted runner routing. Three workflows share one `runs-on` expression;
# it must keep sending fork PRs to a disposable hosted runner AND keep sending
# push / dispatch / schedule to the Mac we already own. Both halves fail
# silently — one leaks reach, the other just quietly spends money.
echo -e "\n${YELLOW}[13/21] Checking self-hosted runner routing...${NC}"
if [ -f .claude/scripts/check-self-hosted-runner-routing.py ]; then
    ROUTING_LOG="$LOG_DIR/runner-routing.log"
    if python3 .claude/scripts/check-self-hosted-runner-routing.py > "$ROUTING_LOG" 2>&1; then
        cat "$ROUTING_LOG"
    else
        # A python traceback also exits 1, and `.github/workflows not found`
        # (wrong directory) exits 1 too — neither is a routing verdict.
        gate_script_failure "self-hosted runner routing" "$ROUTING_LOG" $? \
            "self-hosted runner routing is wrong:" \
            "self-hosted runner routing is wrong" \
            "See the \`self-hosted-runner\` skill for the expression to copy."
    fi
else
    missing_gate_script "check-self-hosted-runner-routing.py"
fi

# Public-API ABI gate (#2723): the committed .api dumps are a BLOCKING CI
# check — catch an unintentional public-API change locally before CI does.
# Intentional changes: ./gradlew apiDump, review + commit the .api diff.
echo -e "\n${YELLOW}[14/21] Checking public-API ABI (apiCheck)...${NC}"
if [ -f gradlew ]; then
    if gradle_run "$LOG_DIR/api-check.log" apiCheck; then
        echo -e "${GREEN}  ✓ public API matches the committed .api dumps${NC}"
    else
        # "the public-API surface drifted" is a claim about the CODE — do not
        # make it when Gradle died before comparing anything, and above all do
        # not prescribe `apiDump` then: with a broken SDK it fails the same
        # way, and if it ever succeeded it would commit a bogus .api diff
        # (#3065).
        #
        # The proof cue is the binary-compatibility-validator's own comparison
        # failure. Both strings were read out of the plugin jar
        # (binary-compatibility-validator-0.18.1.jar) rather than guessed:
        # `API check failed for project <name>` when the dumps differ, and
        # `Expected file with API declarations '<path>'` when a module has no
        # committed dump at all. Anything else that turns apiCheck red — a
        # compile break, a dead worker — never reached the comparison.
        gradle_report_failure "apiCheck" "$LOG_DIR/api-check.log" $? \
            "apiCheck failed — public-API surface drifted:" \
            "Intentional change? Run ./gradlew apiDump and commit the .api diff." \
            "The public API was NOT compared — this is not a pass. Do NOT run apiDump." \
            "(API check failed for project|Expected file with API declarations)"
    fi
else
    echo -e "${YELLOW}  ⚠ gradlew not found — apiCheck NOT checked here (CI still gates it)${NC}"
    NOT_COVERED=$((NOT_COVERED + 1))
fi

# ═══════════════════════════════════════════════════════════════════════════
# CI-PARITY LEGS (#3103)
#
# Everything below mirrors a BLOCKING step of ci.yml → `Repo hygiene checks`
# or `Quality gate (full)` that had NO local counterpart. Measured on PR
# #3099: this script printed "ALL CHECKS PASSED — safe to push", the branch
# was pushed, and CI then failed on `Check Android <-> iOS demo id parity`,
# because the new `ar-measure` demo had neither an iOS registry entry nor a
# `parity-manifest.yml` row — exactly the #2769 silent-drift class that gate
# exists to catch. A local gate that blesses a push while a blocking CI gate
# is not represented in it teaches sessions to trust an incomplete verdict:
# the same failure shape as #2988.
#
# ── WHAT IS DELIBERATELY NOT COVERED HERE, AND WHY ─────────────────────────
# Read this list as the complement of the legs above. If a CI check appears
# in NEITHER, nobody has audited it — that is a gap, not a decision.
#
#   NETWORK (a pre-push gate has to work offline)
#     · validate-demo-assets.sh CDN half — leg 9 runs `--no-cdn`; CI's
#       `Validate demo app asset references` hits every CDN URL for real.
#     · MCP unit tests — quality-gate.sh runs them outside `--quick`; they
#       need `npm ci` under mcp/, i.e. a registry round-trip.
#     · mcp-ts-check.yml (Biome + tsc over mcp/, #3054) and rn-ts-check.yml —
#       same reason: the linter is a package-local binary, so both need an
#       `npm ci` in their package first. `cd mcp && npm run lint` locally.
#     · `flutter-demo` job's pub.dev publish preflight (`--dry-run` resolves
#       against pub.dev).
#
#   SLOW / GRADLE-BOUND (CI runs these on their own runners, in parallel)
#     · `lint` job — lintRelease + detekt, several minutes.
#     · `build` job — assembleDebug across every sample.
#     · `web-desktop`, `compile-kmp`, `flutter-demo` and the rn-* compile
#       workflows.
#     · `coverage` job — and it is `continue-on-error: true` in CI, so it
#       cannot block a merge in the first place.
#     · check-demo-class-refs.sh — 77 s measured on this host, AND advisory
#       (see below), so it buys nothing here. Its self-test IS in leg 19.
#
#   NEEDS A DEVICE OR A MACOS TOOLCHAIN
#     · `kmp-native-test` (iOS-simulator unit tests), device-qa.yml,
#       render-tests.yml, ios.yml. Leg 6 covers the one iOS check that works
#       headless, and only when a sim is already booted with the demo running.
#
#   NO LOCAL COUNTERPART BY CONSTRUCTION
#     · The `CI Gate` aggregator job: it polls the GitHub Checks API for the
#       PR head SHA, so there is nothing to read before the push exists. Its
#       DECISION LOGIC is still pinned locally — leg 19 runs
#       .github/scripts/test-ci-gate-aggregation.sh and
#       test-ci-gate-observations.sh, the suites that keep a cancelled
#       advisory check from red-lighting the gate (#1984) and a partial
#       Checks read from green-lighting a cancelled job (#3018).
#
#   ADVISORY IN CI — exits 0 on findings, so it can never block a merge
#     · check-doc-drift.sh, check-demo-class-refs.sh. Both surface findings
#       and return 0 by design (a heuristic false positive would erode
#       trust); the weekly doc-audit.yml agent does the semantic pass.
#
#   OWNED BY A DIFFERENT MANDATE
#     · impact-check.sh — CLAUDE.md requires it after ANY code/API/doc change,
#       independently of this script, and it answers a different question
#       (HEAD~1 diff impact, not "is the tree self-consistent"). Its
#       self-test IS in leg 19.
#
# Legs 7, 9 and 10 are re-run inside leg 20 (quality-gate.sh covers version
# sync, demo assets and skill drift too). That duplication is ~10 s and is
# kept on purpose: the dedicated legs carry far better diagnosis than
# quality-gate.sh's one-line [FAIL], and each one's comment records a
# measured failure mode that must not be lost.
# ═══════════════════════════════════════════════════════════════════════════

# Android <-> iOS demo-id parity (BLOCKING in ci.yml → repo-hygiene). Diffs
# Android's canonical demo ids against iOS's live registry and the committed
# parity-manifest.yml. Self-contained: it regenerates iOS's (gitignored)
# GeneratedScenes.swift itself, so it works in a fresh worktree.
echo -e "\n${YELLOW}[15/21] Checking Android <-> iOS demo id parity...${NC}"
if [ -f .claude/scripts/check-demo-id-parity.sh ]; then
    PARITY_LOG="$LOG_DIR/demo-id-parity.log"
    if bash .claude/scripts/check-demo-id-parity.sh > "$PARITY_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ every Android demo id is accounted for on iOS or in the manifest${NC}"
    else
        # The script's OTHER exit-1 paths are all "cannot run" and print
        # `Error: …` (fragments dir absent, collator output shape changed, a
        # DemoEntry with no id). Only the verdict block prints this line —
        # read out of the script, not guessed.
        gate_script_failure "demo id parity" "$PARITY_LOG" $? \
            "Android <-> iOS demo ids diverged:" \
            "check-demo-id-parity\.sh: FAILED" \
            "Fix: add the iOS registry entry, or a parity-manifest.yml row stating why the demo is Android-only."
    fi
else
    missing_gate_script "check-demo-id-parity.sh"
fi

# Asset credits (BLOCKING in ci.yml → repo-hygiene). assets/CREDITS.md is
# GENERATED from assets/catalog.json and discharges the attribution clause of
# every model's licence (CC-BY 4.0 §3a) — a drift here is a compliance gap in
# a published release, not a tidiness one. Five Khronos models once shipped
# uncredited. Deterministic regenerate-and-compare, no write, sub-second.
echo -e "\n${YELLOW}[16/21] Checking asset credits...${NC}"
if [ -f .claude/scripts/generate-credits.py ]; then
    CREDITS_LOG="$LOG_DIR/asset-credits.log"
    if python3 .claude/scripts/generate-credits.py --check > "$CREDITS_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ assets/CREDITS.md in sync with assets/catalog.json${NC}"
    else
        # `DRIFT:` is the comparison verdict; the generator's other exit-1
        # paths print lowercase `error: …` (catalog missing / malformed) and
        # are a tooling failure, not drift.
        gate_script_failure "assets/CREDITS.md" "$CREDITS_LOG" $? \
            "assets/CREDITS.md drifted from assets/catalog.json:" \
            "^DRIFT: assets/CREDITS\.md" \
            "Fix: python3 .claude/scripts/generate-credits.py"
    fi
else
    missing_gate_script "generate-credits.py"
fi

# Content gate (BLOCKING in ci.yml → repo-hygiene). Google's `android run` has
# a measured install no-op and has been rediscovered THREE times (#2796,
# #2854, #2990) because each fix landed in one call site while the docs went
# on recommending it. Documenting the trap is fine; recommending it is not.
echo -e "\n${YELLOW}[17/21] Checking no file teaches \`android run\` for installing...${NC}"
if [ -f .claude/scripts/check-android-run-not-taught.sh ]; then
    ANDROID_RUN_LOG="$LOG_DIR/android-run-not-taught.log"
    if bash .claude/scripts/check-android-run-not-taught.sh > "$ANDROID_RUN_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ no file recommends \`android run\` without naming the defect${NC}"
    else
        gate_script_failure "\`android run\` content gate" "$ANDROID_RUN_LOG" $? \
            "file(s) teach \`android run\` for installing:" \
            "check-android-run: FAIL" \
            "Fix: use .claude/scripts/lib/android-cli.sh, or cite #2796 / #2854 / #2990 next to the mention."
    fi
else
    missing_gate_script "check-android-run-not-taught.sh"
fi

# Workflow shell blocks (BLOCKING in ci.yml → repo-hygiene). `with.script:`
# blocks are exec'd line-by-line through `sh -c` = dash on Linux runners, so a
# bashism there only fails once it is on main. Needs dash + shellcheck, which
# ship on the CI runner but not on every Mac — hence the guard.
echo -e "\n${YELLOW}[18/21] Validating workflow shell blocks...${NC}"
if ! command -v dash >/dev/null 2>&1 || ! command -v shellcheck >/dev/null 2>&1; then
    echo -e "${YELLOW}  ⚠ dash or shellcheck missing — NOT checked here (CI still gates it)${NC}"
    echo -e "${YELLOW}      Arm it locally: brew install dash shellcheck${NC}"
    NOT_COVERED=$((NOT_COVERED + 1))
elif [ -f .claude/scripts/check-workflow-scripts.sh ]; then
    WF_LOG="$LOG_DIR/workflow-scripts.log"
    if bash .claude/scripts/check-workflow-scripts.sh > "$WF_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ workflow shell/JS blocks parse${NC}"
    else
        # The summary line is only printed once a real finding was recorded;
        # the "dash not on PATH" / "workflows dir not found" bail-outs print
        # `::error::` lines of their own and never reach it.
        gate_script_failure "workflow shell blocks" "$WF_LOG" $? \
            "workflow shell/JS block(s) are invalid:" \
            "::error::Workflow validation failed" \
            "The error lines above name the file and the offending construct."
    fi
else
    missing_gate_script "check-workflow-scripts.sh"
fi

# The repo-hygiene gate self-tests. Every one of them is hermetic (fixtures,
# stub adb, temp git repos — no network, no device, no secret) and the whole
# set measured ~25 s on this host, so there is no reason for CI to be the
# first place a regressed gate shows up.
#
# The list is DERIVED from ci.yml rather than copied into it: a hardcoded copy
# goes stale the moment repo-hygiene gains a self-test, which is the #2988
# shape this whole block exists to fix. And because the list comes from a
# regex, "found nothing" is a broken extractor, never a clean bill of health
# (#3050) — a count below the floor fails the leg instead of blessing it.
echo -e "\n${YELLOW}[19/21] Running the repo-hygiene gate self-tests...${NC}"
# 31 self-tests on 2026-08-11. The floor guards against a regex that silently
# degrades to a handful of matches; raise it if repo-hygiene ever legitimately
# shrinks, but never delete it.
SELFTEST_FLOOR=20
SELFTEST_LIST="$LOG_DIR/selftests.txt"
# The scrape itself lives in lib/extract-gate-selftests.sh, which validates
# every line it emits against an anchored `<bash|python3> <plain path>` shape
# and reports what it refused. It is a separate file for one reason: these
# strings get EXECUTED below, so the property that keeps that safe has to be
# testable — test-extract-gate-selftests.sh pins it against hostile workflow
# fixtures, and is itself one of the self-tests run here. Inline, the property
# was a character class in the middle of this script that no test could reach.
: > "$SELFTEST_LIST"
SELFTEST_SKIP=0
if [ ! -f .claude/scripts/lib/extract-gate-selftests.sh ]; then
    # An absent extractor and a degraded extractor both end at "found 0", and
    # the floor branch below would call that "a bug in THIS script's
    # extractor" — which sends the reader hunting a regex that is not there.
    # Every sibling leg tells "the gate script is missing" apart from "the gate
    # says no"; this one has to as well (#3105 review round 7). Both paths
    # block the push — this only changes which sentence the reader gets.
    missing_gate_script "lib/extract-gate-selftests.sh"
    SELFTEST_SKIP=1
else
    bash .claude/scripts/lib/extract-gate-selftests.sh .github/workflows/ci.yml repo-hygiene \
        > "$SELFTEST_LIST" 2> "$LOG_DIR/selftests-refused.txt" || true
    if [ -s "$LOG_DIR/selftests-refused.txt" ]; then
        sed 's/^/      ⚠ /' "$LOG_DIR/selftests-refused.txt"
    fi
fi
SELFTEST_COUNT=$(grep -c . "$SELFTEST_LIST" 2>/dev/null || true)
SELFTEST_COUNT=${SELFTEST_COUNT:-0}
# Second, INDEPENDENT count: how many steps of that job name themselves a
# self-test. The fixed floor below only catches a total collapse; a regex that
# degrades from 29 matches to 21 clears `>= 20` while eight self-tests quietly
# stop running here (raised on #3105). This one moves with the job, so the
# window closes to whatever gap is real — 31 discovered vs 29 named today,
# because two self-tests live in steps named after what they guard. Both
# checks are load-bearing: if the job window itself breaks, BOTH counts fall
# to zero together and only the absolute floor is left standing.
SELFTEST_NAMED=$(bash .claude/scripts/lib/extract-gate-selftests.sh --count-steps \
    .github/workflows/ci.yml repo-hygiene 2>/dev/null || true)
SELFTEST_NAMED=${SELFTEST_NAMED:-0}
# The two counts are not equal by construction and `discovered >= named` is the
# contract, so one edge stays open: two differently-named steps invoking the
# BYTE-IDENTICAL command collapse under the extractor's `sort -u` and would
# read as degradation. No such pair exists in ci.yml today, and a duplicated
# self-test command is itself worth looking at — flagged rather than special-cased.
if [ "$SELFTEST_SKIP" -eq 1 ]; then
    :
elif [ "$SELFTEST_COUNT" -lt "$SELFTEST_FLOOR" ]; then
    echo -e "${RED}  ✗ self-test discovery is broken — found $SELFTEST_COUNT command(s) in ci.yml → repo-hygiene, expected >= $SELFTEST_FLOOR${NC}"
    echo -e "      This is a bug in THIS script's extractor, not a clean tree."
    ERRORS=$((ERRORS + 1))
elif [ "$SELFTEST_COUNT" -lt "$SELFTEST_NAMED" ]; then
    echo -e "${RED}  ✗ self-test discovery degraded — found $SELFTEST_COUNT command(s) but repo-hygiene names $SELFTEST_NAMED self-test step(s)${NC}"
    echo -e "      Some self-tests are declared in ci.yml and never reached here."
    ERRORS=$((ERRORS + 1))
else
    SELFTEST_FAILED=0
    SELFTEST_UNRUN=0
    while IFS= read -r cmd; do
        [ -n "$cmd" ] || continue
        ST_INTERP="${cmd%% *}"
        script="${cmd#* }"
        # What comes out of the extractor is a STRING SCRAPED FROM A YAML FILE,
        # not a vetted path: `grep -oE` matches anywhere on a line, so a
        # comment inside the repo-hygiene block is as good a source as a `run:`
        # step. Whoever can write that line already owns CI, so this is not a
        # trust boundary — it is the difference between running the repo's own
        # self-tests and running whatever happens to be quoted nearby. The
        # operand is quoted (never word-split) and must be a plain path under
        # the repo: no leading `-` (an option, not a file) and no `..`
        # (anything outside the tree this gate is judging).
        case "$script" in
            -*|*..*|/*)
                echo -e "${YELLOW}      ⚠ refusing non-repo-relative operand '$script' from ci.yml${NC}"
                SELFTEST_UNRUN=$((SELFTEST_UNRUN + 1))
                continue
                ;;
        esac
        # The INTERPRETER is scraped too, and this is the line that execs it.
        # The extractor anchors it to bash|python3, but a guard that lives only
        # in the producer is the very "loosen the class in a hurry" failure the
        # extractor's own header warns about — so the consumer states it again.
        case "$ST_INTERP" in
            bash|python3) ;;
            *)
                echo -e "${YELLOW}      ⚠ refusing interpreter '$ST_INTERP' from ci.yml${NC}"
                SELFTEST_UNRUN=$((SELFTEST_UNRUN + 1))
                continue
                ;;
        esac
        if [ ! -f "$script" ]; then
            echo -e "${YELLOW}      ⚠ $script is referenced by ci.yml but absent here${NC}"
            SELFTEST_UNRUN=$((SELFTEST_UNRUN + 1))
            continue
        fi
        # Flatten the path into the log name: two self-tests in different
        # directories can share a basename, and a collision would silently
        # overwrite the tail printed for a failure.
        ST_LOG="$LOG_DIR/selftest-$(echo "$script" | tr '/' '_').log"
        # Capture the code explicitly: after a bare `if cmd; then …; fi` with
        # no else, `$?` is the IF's own 0, not the command's, and every
        # failure would be filed as exit 0.
        #
        # `< /dev/null` is load-bearing: this loop's stdin IS "$SELFTEST_LIST",
        # so a self-test that reads stdin would eat the remaining lines and end
        # the loop early — while the "$SELFTEST_COUNT self-test(s) pass" line
        # below still prints the full pre-computed count. A false green, in the
        # script whose purpose is to stop false greens.
        ST_CODE=0
        "$ST_INTERP" "$script" > "$ST_LOG" 2>&1 < /dev/null || ST_CODE=$?
        # No hardcoded diagnosis: a suite that exits non-zero is reported as
        # what was observed (the code and its own tail), never as "the gate
        # regressed" — the run may equally have died on the way there.
        if [ "$ST_CODE" -eq 0 ]; then
            :
        elif [ "$ST_CODE" -ge 126 ]; then
            echo -e "${YELLOW}      ⚠ $script could not be executed (exit $ST_CODE)${NC}"
            SELFTEST_UNRUN=$((SELFTEST_UNRUN + 1))
        else
            echo -e "${RED}      ✗ $script exited $ST_CODE${NC}"
            tail -12 "$ST_LOG" 2>/dev/null | sed 's/^/          /' || true
            SELFTEST_FAILED=$((SELFTEST_FAILED + 1))
        fi
    done < "$SELFTEST_LIST"

    if [ "$SELFTEST_FAILED" -eq 0 ] && [ "$SELFTEST_UNRUN" -eq 0 ]; then
        echo -e "${GREEN}  ✓ $SELFTEST_COUNT gate self-test(s) pass${NC}"
    else
        if [ "$SELFTEST_FAILED" -gt 0 ]; then
            echo -e "${RED}  ✗ $SELFTEST_FAILED of $SELFTEST_COUNT gate self-test(s) failed${NC}"
            ERRORS=$((ERRORS + SELFTEST_FAILED))
        fi
        if [ "$SELFTEST_UNRUN" -gt 0 ]; then
            echo -e "${YELLOW}  ⚠ $SELFTEST_UNRUN of $SELFTEST_COUNT gate self-test(s) could not run${NC}"
            INCOMPLETE=$((INCOMPLETE + SELFTEST_UNRUN))
        fi
    fi
fi

# The full quality gate — ci.yml's `Quality gate (full)` job runs this very
# script. Invoking it here rather than re-implementing its legs is what stops
# THIS file from drifting away from it again: a leg added to quality-gate.sh
# is picked up for free.
#
# `--quick` is the offline profile: it skips the Android build + unit tests
# (legs 1-4 already ran them, with better diagnosis) and the MCP tests, and
# passes `--no-cdn` to validate-demo-assets.sh. What it still covers, none of
# which existed anywhere in this script before: llms.txt structural drift, the
# collate-demos.sh llms.txt block, tracked-secret / staged-API-key scans,
# check-deprecated-api.sh, check-sceneview-swift-urls.sh, check-web-dts.sh,
# the .filamat ↔ Filament-runtime ABI invariant (GenerateFilamat.sh --check),
# the worktree-prune regression suite, and the website asset rules (no Google
# Fonts, no Three.js, no <model-viewer>). Measured ~50 s.
echo -e "\n${YELLOW}[20/21] Running the full quality gate (offline profile)...${NC}"
if [ -f .claude/scripts/quality-gate.sh ]; then
    QG_LOG="$LOG_DIR/quality-gate.log"
    if bash .claude/scripts/quality-gate.sh --quick > "$QG_LOG" 2>&1; then
        QG_WARNINGS=$(grep -c '\[WARN\]' "$QG_LOG" 2>/dev/null || true)
        QG_WARNINGS=${QG_WARNINGS:-0}
        if [ "$QG_WARNINGS" -gt 0 ]; then
            echo -e "${GREEN}  ✓ quality gate clear${NC} ${YELLOW}($QG_WARNINGS warning(s) — not blocking; see $QG_LOG)${NC}"
            grep '\[WARN\]' "$QG_LOG" 2>/dev/null | sed 's/^/      /' || true
        else
            echo -e "${GREEN}  ✓ quality gate clear${NC}"
        fi
    else
        # quality-gate.sh runs under `set -euo pipefail`, so an unexpected
        # crash exits non-zero too. Only its summary line proves it reached a
        # verdict; without it, nothing was judged.
        gate_script_failure "quality gate" "$QG_LOG" $? \
            "quality gate BLOCKED:" \
            "BLOCKED — [0-9]+ issue" \
            "The [FAIL] lines above name each blocker."
    fi
else
    missing_gate_script "quality-gate.sh"
fi

# Release-publisher contracts (BLOCKING in ci.yml → repo-hygiene). Static,
# offline, ~0.1 s: it reads .github/workflows/release.yml and nothing else, so
# there is no reason for CI to be the first place a broken publisher shows up.
# Its subject is the #3011 class — a publish job that looks correct and ships
# nothing — and release.yml is edited far more often than it is exercised: the
# next real run is the next release, which is the worst possible moment to
# learn that a verification step was dropped. Its mutation suite runs in leg 19
# (the suite proves the gate bites; this leg is the gate itself, on the tree
# being pushed).
echo -e "\n${YELLOW}[21/21] Checking the release publishers' contracts...${NC}"
if [ -f .claude/scripts/check-release-publish-verification.py ]; then
    RPV_LOG="$LOG_DIR/release-publish-verification.log"
    if python3 .claude/scripts/check-release-publish-verification.py > "$RPV_LOG" 2>&1; then
        echo -e "${GREEN}  ✓ every release publisher verifies against its registry${NC}"
    else
        # Exit 2 is "could not measure" (renamed job, unparseable YAML) and
        # must never read as a finding — script_report_failure keeps those two
        # apart, which is the whole reason the gate distinguishes them.
        gate_script_failure "release-publisher contracts" "$RPV_LOG" $? \
            "release.yml publisher contract(s) violated:" \
            "BROKEN: [0-9]+ release-publisher contract" \
            "The FAIL lines above name the job and the contract (#3011/#3021)."
    fi
else
    missing_gate_script "check-release-publish-verification.py"
fi

# Summary
#
# "could not run" is reported SEPARATELY from "failed" — conflating them is the
# whole bug this script was carrying. It still exits non-zero: an unrun gate is
# not a passed gate. Infrastructure failures on this host are usually daemon
# contention, so the remedy is to re-run once nothing else is building.
echo -e "\n═══════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ] && [ "$INCOMPLETE" -eq 0 ]; then
    if [ "$NOT_COVERED" -eq 0 ]; then
        echo -e "${GREEN}  ✓ ALL CHECKS PASSED — safe to push${NC}"
    else
        # Still exit 0: every one of these is gated in CI, and refusing the
        # push because a Mac lacks shellcheck would make the gate something
        # people work around. But the word ALL has to go.
        echo -e "${GREEN}  ✓ CHECKS PASSED — safe to push${NC}"
        echo -e "${YELLOW}      ($NOT_COVERED leg(s) not covered on this host — see the ⚠ lines above; CI gates them)${NC}"
    fi
    exit 0
elif [ "$ERRORS" -eq 0 ]; then
    echo -e "${YELLOW}  ⚠ $INCOMPLETE CHECK(S) COULD NOT RUN — no check failed, but the gate is INCOMPLETE${NC}"
    if [ -n "$SETUP_FIX" ]; then
        # Re-running fixes daemon contention; it does nothing for a toolchain
        # that is not configured. Lead with the fix that actually applies.
        echo -e "${YELLOW}    This host is not set up to build:${NC}"
        echo -e "${YELLOW}      $SETUP_FIX${NC}"
    else
        echo -e "${YELLOW}    Re-run when no other build is competing for the Gradle daemon:${NC}"
        echo -e "${YELLOW}      ./gradlew --stop && bash .claude/scripts/pre-push-check.sh${NC}"
    fi
    not_covered_recap
    echo -e "${YELLOW}    Logs: $LOG_DIR${NC}"
    exit 1
else
    echo -e "${RED}  ✗ $ERRORS CHECK(S) FAILED — DO NOT PUSH${NC}"
    if [ "$INCOMPLETE" -gt 0 ]; then
        echo -e "${YELLOW}  ⚠ plus $INCOMPLETE check(s) that could not run at all${NC}"
    fi
    not_covered_recap
    if [ -n "$SETUP_FIX" ]; then
        echo -e "${YELLOW}  ⚠ this host is not set up to build:${NC}"
        echo -e "${YELLOW}      $SETUP_FIX${NC}"
    fi
    echo -e "      Logs: $LOG_DIR"
    exit 1
fi
