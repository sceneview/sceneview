#!/usr/bin/env bash
# Hermetic self-test for lib/gradle-run.sh.
#
# What is actually at stake: this library decides whether a red gate gets the
# diagnosis "your code is broken" or "the build never ran". Both directions are
# expensive to get wrong —
#   - too NARROW a pattern table and a dead Gradle daemon is announced as a
#     screenshot regression (the measured 2026-08-06 bug that created this lib);
#   - too BROAD a pattern table and a genuine compile break is waved through as
#     "infrastructure", which is strictly worse: the gate stops gating.
# So the fixtures below assert BOTH, and the suite mutation-tests its own
# pattern table so it cannot pass while checking nothing.
#
# Fixtures only: synthetic Gradle logs in a temp dir. No Gradle, no network.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SCRIPT_DIR/lib/gradle-run.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# shellcheck source=lib/gradle-run.sh
source "$LIB"

PASS=0
FAIL=0

ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# assert_infra <fixture> <description>  — reason must be NON-empty
assert_infra() {
    local reason
    reason="$(gradle_infra_reason "$1" "${3:-1}")"
    if [ -n "$reason" ]; then ok "$2 → infra ($reason)"; else bad "$2 → expected an infra reason, got none"; fi
}

# assert_real <fixture> <description>  — reason must be EMPTY
assert_real() {
    local reason
    reason="$(gradle_infra_reason "$1" "${3:-1}")"
    if [ -z "$reason" ]; then ok "$2 → real failure (no infra reason)"; else bad "$2 → expected NO infra reason, got '$reason'"; fi
}

# ── Fixtures ────────────────────────────────────────────────────────────────
# The daemon message is quoted verbatim from the run that produced the false
# "Android screenshot regression detected" on 2026-08-06.
cat > "$TMP/daemon.log" <<'LOG'
> Task :samples:android-demo:compileDebugKotlin UP-TO-DATE

FAILURE: Build failed with an exception.

* What went wrong:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 42s
LOG

cat > "$TMP/lock.log" <<'LOG'
FAILURE: Build failed with an exception.
* What went wrong:
Timeout waiting to lock journal cache (~/.gradle/caches/journal-1). It is currently in use by another Gradle instance.
BUILD FAILED in 121s
LOG

cat > "$TMP/oom.log" <<'LOG'
* What went wrong:
Execution failed for task ':sceneview:compileReleaseKotlin'.
> java.lang.OutOfMemoryError: Java heap space
BUILD FAILED in 88s
LOG

cat > "$TMP/network.log" <<'LOG'
* What went wrong:
Could not resolve all files for configuration ':sceneview:releaseCompileClasspath'.
> Could not resolve com.google.ar:core:1.51.0.
   > Could not GET 'https://repo.maven.apache.org/maven2/com/google/ar/core/1.51.0/core-1.51.0.pom'.
      > java.net.UnknownHostException: repo.maven.apache.org
BUILD FAILED in 9s
LOG

# A coordinate that does not exist — a COMMITTED-CODE defect (typo, unpublished
# version, yanked artifact) that Gradle announces with the SAME opening line as
# the network failure above. Only the absence of a transport error separates
# them. This fixture exists because a `Could not resolve all …` row in the
# pattern table classified it as infrastructure, which on the release path
# turned a blocker into a pass (found in review of PR #3030).
cat > "$TMP/missing-coord.log" <<'LOG'
* What went wrong:
Could not resolve all files for configuration ':sceneview:releaseCompileClasspath'.
> Could not find com.google.ar:core:9.99.0.
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/com/google/ar/core/9.99.0/core-9.99.0.pom
  Required by:
      project :sceneview
BUILD FAILED in 6s
LOG

cat > "$TMP/disk.log" <<'LOG'
* What went wrong:
Execution failed for task ':samples:android-demo:mergeDebugAssets'.
> java.io.IOException: No space left on device
BUILD FAILED in 14s
LOG

# A GENUINE compile break — the most important negative case.
cat > "$TMP/compile.log" <<'LOG'
> Task :sceneview:compileReleaseKotlin FAILED
e: file:///repo/sceneview/src/main/java/io/github/sceneview/Scene.kt:112:23 Unresolved reference 'materialLoader'

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':sceneview:compileReleaseKotlin'.
> Compilation error. See log for more details

BUILD FAILED in 31s
LOG

# A GENUINE test failure.
cat > "$TMP/testfail.log" <<'LOG'
> Task :samples:android-demo:testDebugUnitTest FAILED

ContactShadowControlsSnapshotTest > controls_default_state_lightMode FAILED
    java.lang.AssertionError: Roborazzi: image comparison failed

FAILURE: Build failed with an exception.
BUILD FAILED in 24s
LOG

echo "── gradle_infra_reason: environment failures ──"
assert_infra "$TMP/daemon.log"  "daemon disappeared (the measured false-red)"
assert_infra "$TMP/lock.log"    "lock contention between concurrent builds"
assert_infra "$TMP/oom.log"     "JVM out of memory"
assert_infra "$TMP/network.log" "dependency resolution / network"
assert_infra "$TMP/disk.log"    "disk full"
assert_infra "$TMP/nope.log"    "log file was never written"

echo "── gradle_infra_reason: REAL failures must stay real ──"
assert_real "$TMP/compile.log"       "unresolved reference (compile break)"
assert_real "$TMP/testfail.log"      "unit test assertion failure"
assert_real "$TMP/missing-coord.log" "nonexistent dependency coordinate"

echo "── gradle_infra_reason: signal-terminated builds ──"
for sig in 130 137 143; do
    assert_infra "$TMP/compile.log" "exit $sig overrides the log content" "$sig"
done

# ── Host setup class (#3065) ────────────────────────────────────────────────
# Quoted verbatim from the 2026-08-09 run in a fresh worktree: `local.properties`
# is gitignored, so it exists only in the main checkout. Every Gradle step died
# here, and the gate announced "public-API surface drifted" + `./gradlew apiDump`
# — a diagnosis it never established and a remedy that would have committed a
# bogus .api diff.
cat > "$TMP/sdk.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
Could not determine the dependencies of task ':arsceneview:compileReleaseJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '/repo/.claude/worktrees/x/local.properties'.

BUILD FAILED in 3s
LOG

# A GENUINE public-API drift — the ONLY case that may print the drift
# diagnosis. Copied from the real 2026-08-09 run: one member line deleted from
# the committed dump, `./gradlew :sceneview:apiCheck` red.
cat > "$TMP/apidrift.log" <<'LOG'
> Task :sceneview:apiBuild UP-TO-DATE
> Task :sceneview:apiCheck FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':sceneview:apiCheck'.
> API check failed for project sceneview.
  --- /repo/sceneview/api/sceneview.api
  +++ /repo/sceneview/build/api/sceneview.api
  @@ -1,4 +1,5 @@
   public final class io/github/sceneview/Aabb {
  +	public static final field $stable I

  You can run :sceneview:apiDump task to overwrite API declarations

BUILD FAILED in 41s
LOG

# apiCheck red WITHOUT any comparison: the ABI dump task itself died. Also
# copied from a real 2026-08-09 run on this host (a poisoned instrumented-
# transform cache; CI was green throughout). The public API is untouched, and
# saying "drifted" here is the same lie as #3065 with a different cause.
cat > "$TMP/apibuild-dead.log" <<'LOG'
> Task :sceneview:compileReleaseJavaWithJavac UP-TO-DATE
> Task :sceneview:apiBuild FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':sceneview:apiBuild'.
> A failure occurred while executing kotlinx.validation.AbiBuildWorker
   > kotlin/metadata/jvm/JvmMetadataUtil

BUILD FAILED in 1m 6s
LOG

echo "── gradle_setup_reason: host setup vs everything else ──"
SDK_REASON="$(gradle_setup_reason "$TMP/sdk.log")"
case "$SDK_REASON" in
    *"SDK location is not configured"*) ok "missing local.properties → setup class ($SDK_REASON)" ;;
    *) bad "the measured 'SDK location not found' log was NOT classified as setup (got '$SDK_REASON')" ;;
esac
SDK_FIX="$(gradle_setup_fix "$TMP/sdk.log")"
case "$SDK_FIX" in
    *"sdk.dir="*ANDROID_HOME*) ok "the setup fix names local.properties AND ANDROID_HOME" ;;
    *) bad "the setup fix does not tell the user what to actually do: '$SDK_FIX'" ;;
esac
for fixture in compile testfail missing-coord daemon apidrift; do
    if [ -z "$(gradle_setup_reason "$TMP/$fixture.log")" ]; then
        ok "$fixture is NOT mistaken for a setup failure"
    else
        bad "$fixture was classified as a host setup failure — the setup table is too broad"
    fi
done

echo "── gradle_report_failure: the branch that names the cause ──"
# Drive the real reporting helper the gates call, on the two logs that matter,
# and read what it PRINTS and what it COUNTS.
# Same arguments pre-push-check.sh step 14 passes, proof cue included.
API_PROOF="(API check failed for project|Expected file with API declarations)"
report() {  # <log> → prints report, echoes "ERRORS=<n> INCOMPLETE=<n>" last
    ERRORS=0; INCOMPLETE=0
    # Written by the helper on the setup branch, exactly as in
    # pre-push-check.sh — echoed below so the summary's "here is the fix"
    # path is covered too.
    SETUP_FIX=""
    gradle_report_failure "apiCheck" "$1" 1 \
        "apiCheck failed — public-API surface drifted:" \
        "Intentional change? Run ./gradlew apiDump and commit the .api diff." \
        "The public API was NOT compared — this is not a pass. Do NOT run apiDump." \
        "$API_PROOF"
    echo "ERRORS=$ERRORS INCOMPLETE=$INCOMPLETE SETUP_FIX=${SETUP_FIX:+set}"
}
SDK_REPORT="$(report "$TMP/sdk.log")"
case "$SDK_REPORT" in
    *"drifted"*) bad "the SDK log STILL produces the drift diagnosis — #3065 is back" ;;
    *) ok "the SDK log never says 'drifted'" ;;
esac
# The note deliberately CONTAINS the string "apiDump" ("Do NOT run apiDump"),
# so the assertion has to target the prescription, not the word.
case "$SDK_REPORT" in
    *"Run ./gradlew apiDump"*) bad "the SDK log still prescribes apiDump — the dangerous remedy" ;;
    *"Do NOT run apiDump"*) ok "the SDK log warns against apiDump instead of prescribing it" ;;
    *) bad "the SDK log neither prescribes nor warns about apiDump: $SDK_REPORT" ;;
esac
case "$SDK_REPORT" in
    *"was NOT compared"*"ERRORS=0 INCOMPLETE=1 SETUP_FIX=set") ok "the SDK log is an unrun gate, and hands the summary its fix" ;;
    *) bad "the SDK log did not produce an INCOMPLETE + 'not compared' verdict: $SDK_REPORT" ;;
esac
# apiBuild died: red, but nothing was ever compared.
DEAD_REPORT="$(report "$TMP/apibuild-dead.log")"
case "$DEAD_REPORT" in
    *"drifted"*) bad "a dead apiBuild is STILL reported as a public-API drift" ;;
    *"was NOT compared"*"ERRORS=0 INCOMPLETE=1 SETUP_FIX=") ok "a dead apiBuild is an unrun gate, not a drift — and NOT a setup problem" ;;
    *) bad "a dead apiBuild produced neither verdict: $DEAD_REPORT" ;;
esac

DRIFT_REPORT="$(report "$TMP/apidrift.log")"
case "$DRIFT_REPORT" in
    *"drifted"*"apiDump"*"ERRORS=1 INCOMPLETE=0 SETUP_FIX=") ok "a REAL drift still gets the drift diagnosis and the apiDump remedy" ;;
    *) bad "a real API drift lost its diagnosis: $DRIFT_REPORT" ;;
esac

# Mutation on the PROOF cue: strip the comparison line from the drift log and
# the same input must stop being called a drift. Without this, "requires proof"
# could be satisfied by a regex that matches every apiCheck log.
STRIPPED="$TMP/apidrift-nocue.log"
grep -v "API check failed for project" "$TMP/apidrift.log" > "$STRIPPED"
case "$(report "$STRIPPED")" in
    *"drifted"*) bad "the drift verdict does not actually depend on the comparison cue" ;;
    *) ok "removing the comparison cue removes the drift verdict" ;;
esac

echo "── mutation test: the setup table must be what decides ──"
SETUP_MUTANT="$TMP/gradle-run-setup-mutant.sh"
grep -v "^SDK location not found => " "$LIB" > "$SETUP_MUTANT"
MUTANT_OUT="$(bash -c "source '$SETUP_MUTANT'; ERRORS=0; INCOMPLETE=0; gradle_report_failure apiCheck '$TMP/sdk.log' 1 'apiCheck failed — public-API surface drifted:' 'Run apiDump' 'not compared'; echo ERRORS=\$ERRORS")"
case "$MUTANT_OUT" in
    *"drifted"*) ok "removing the SDK row brings the false 'drifted' verdict back (the assertions above are real)" ;;
    *) bad "mutant did NOT change the verdict — the setup assertions are hollow" ;;
esac

echo "── roborazzi_fresh_diff_count ──"
SUMMARY="$TMP/results-summary.json"
MARKER="$TMP/marker"
# Stale report (mtime older than the marker) = nothing was compared this run.
echo '{"summary":{"total":11,"recorded":0,"added":0,"changed":0,"unchanged":11}}' > "$SUMMARY"
sleep 1
touch "$MARKER"
[ "$(roborazzi_fresh_diff_count "$SUMMARY" "$MARKER")" = "unknown" ] \
    && ok "a STALE report yields 'unknown', never '0 diffs'" \
    || bad "a stale report was mistaken for a fresh clean comparison"

# Fresh + clean.
sleep 1
echo '{"summary":{"total":11,"recorded":0,"added":0,"changed":0,"unchanged":11}}' > "$SUMMARY"
[ "$(roborazzi_fresh_diff_count "$SUMMARY" "$MARKER")" = "0" ] \
    && ok "a FRESH clean report yields 0" \
    || bad "a fresh clean report did not yield 0"

# Fresh + a real regression (shape copied from the measured red run).
echo '{"summary":{"total":11,"recorded":0,"added":0,"changed":1,"unchanged":10}}' > "$SUMMARY"
[ "$(roborazzi_fresh_diff_count "$SUMMARY" "$MARKER")" = "1" ] \
    && ok "a FRESH report with changed:1 yields 1" \
    || bad "a fresh report with changed:1 did not yield 1"

# `added` counts too — a brand-new golden written by a verify run is a diff.
echo '{"summary":{"total":12,"recorded":0,"added":2,"changed":1,"unchanged":9}}' > "$SUMMARY"
[ "$(roborazzi_fresh_diff_count "$SUMMARY" "$MARKER")" = "3" ] \
    && ok "changed + added are summed" \
    || bad "changed + added were not summed"

# Corrupt report — must degrade to 'unknown', never to a silent 0.
echo 'not json at all' > "$SUMMARY"
[ "$(roborazzi_fresh_diff_count "$SUMMARY" "$MARKER")" = "unknown" ] \
    && ok "a CORRUPT report yields 'unknown'" \
    || bad "a corrupt report did not yield 'unknown'"

echo "── gradle_log_tail must never abort its caller ──"
# release-checklist.sh runs `set -euo pipefail`. `grep -v` exits 1 when nothing
# survives the boilerplate filter, and pipefail + errexit then killed the caller
# mid-run — right after a genuine build failure, swallowing every remaining
# section and the summary. Reproduced in review of PR #3030; pinned here.
: > "$TMP/empty.log"
printf '\n* Try:\n> Run with --stacktrace option to get the stack trace.\nBUILD FAILED in 3s\n' > "$TMP/allboiler.log"
for fixture in empty allboiler; do
    if bash -c "set -euo pipefail; source '$LIB'; gradle_log_tail '$TMP/$fixture.log' 10 >/dev/null; echo REACHED" 2>/dev/null | grep -q REACHED; then
        ok "a $fixture log does not abort a 'set -euo pipefail' caller"
    else
        bad "gradle_log_tail killed its caller on a $fixture log"
    fi
done

echo "── gradle_log_tail ──"
TAIL_OUT="$(gradle_log_tail "$TMP/compile.log" 20)"
case "$TAIL_OUT" in
    *"Unresolved reference"*) ok "the diagnosis line survives the tail" ;;
    *) bad "the tail dropped the only line that explains the failure" ;;
esac
case "$TAIL_OUT" in
    *"Run with --stacktrace"*) bad "boilerplate 'Try:' footer was not filtered" ;;
    *) ok "boilerplate footer is filtered out" ;;
esac
# Gradle prints the header as `* Try:`, not `Try:` — the first version of the
# filter missed the asterisk and leaked it into every failure report.
case "$TAIL_OUT" in
    *"Try:"*) bad "the '* Try:' header leaked through the filter" ;;
    *) ok "the '* Try:' header is filtered out" ;;
esac

# ── Mutation test ───────────────────────────────────────────────────────────
# A pattern table is exactly the kind of thing that can be emptied by a bad
# merge while every assertion above still passes for the wrong reason. Delete
# the daemon pattern from a COPY of the lib and require the daemon fixture to
# stop being classified as infra. If this does not flip, the assertions above
# are not reading the table at all.
echo "── mutation test (the suite must fail when the table is broken) ──"
MUTANT="$TMP/gradle-run-mutant.sh"
grep -v "^daemon disappeared unexpectedly => " "$LIB" > "$MUTANT"
MUTANT_REASON="$(bash -c "source '$MUTANT'; gradle_infra_reason '$TMP/daemon.log' 1")"
if [ -z "$MUTANT_REASON" ]; then
    ok "removing the daemon pattern makes the daemon fixture unclassified"
else
    bad "mutant STILL classified the daemon log as '$MUTANT_REASON' — the assertions above are hollow"
fi

# ── script_report_failure: the same rule for non-Gradle checkers ────────────
# Steps 9-13 of pre-push-check.sh run plain scripts. They failed the same way
# apiCheck did (#3065): exit 1 => print the finding, whatever killed the
# checker. Every cue below was READ from the checker it belongs to, not
# guessed — here, validate-demo-assets.sh's own "Missing bundled" line.
echo "── script_report_failure: a checker that died is not a checker that found something ──"
SCRIPT_PROOF='(Missing bundled|Broken CDN|Undeclared in catalog|Strict mode: stopping)'
echo "Checked 41 assets
✗ Missing bundled asset: models/DamagedHelmet.glb" > "$TMP/assets-real.log"
printf 'Traceback (most recent call last):\n  File "check.py", line 9\nModuleNotFoundError: No module named requests\n' > "$TMP/assets-crash.log"
echo "usage: validate-demo-assets.sh [--strict]" > "$TMP/assets-usage.log"

run_script_report() {  # <log> <code>
    bash -c "source '$LIB'; ERRORS=0; INCOMPLETE=0
             script_report_failure 'demo assets' '$1' $2 'demo asset validation FAILED' '$SCRIPT_PROOF' 'Fix: re-download the assets'
             echo \"COUNTS ERRORS=\$ERRORS INCOMPLETE=\$INCOMPLETE\""
}

OUT="$(run_script_report "$TMP/assets-real.log" 1)"
case "$OUT" in
    *"✗ demo asset validation FAILED"*"ERRORS=1 INCOMPLETE=0"*) ok "a checker that printed its own finding is a real failure" ;;
    *) bad "a real finding was not reported as a failure: $OUT" ;;
esac

OUT="$(run_script_report "$TMP/assets-crash.log" 1)"
case "$OUT" in
    *"NOT checked"*"ERRORS=0 INCOMPLETE=1"*) ok "a checker that crashed before its verdict is an unrun gate, not a finding" ;;
    *) bad "a crashed checker was reported as a finding: $OUT" ;;
esac
case "$OUT" in
    *"demo asset validation FAILED"*) bad "the crash log still prints the finding's name" ;;
    *) ok "the crash log never names the finding" ;;
esac

OUT="$(run_script_report "$TMP/assets-usage.log" 2)"
case "$OUT" in
    *"the checker itself could not run (exit 2)"*"ERRORS=0 INCOMPLETE=1"*) ok "exit >= 2 is 'could not run', quoted with its code" ;;
    *) bad "exit 2 was not classified as 'could not run': $OUT" ;;
esac

OUT="$(run_script_report "$TMP/assets-usage.log" 127)"
case "$OUT" in
    *"could not be executed (exit 127)"*) ok "127 says the checker was never executed at all" ;;
    *) bad "127 was not distinguished: $OUT" ;;
esac

# Mutation: with the proof cue gone, the crash must go back to being reported
# as a real finding. If it does not, the assertions above prove nothing.
OUT="$(bash -c "source '$LIB'; ERRORS=0; INCOMPLETE=0
                script_report_failure 'demo assets' '$TMP/assets-crash.log' 1 'demo asset validation FAILED' '.' ''
                echo \"COUNTS ERRORS=\$ERRORS\"")"
case "$OUT" in
    *"✗ demo asset validation FAILED"*) ok "a cue that matches anything brings the false finding back (the assertions above are real)" ;;
    *) bad "the proof cue is not what decides — assertions are hollow" ;;
esac

# A cue is only honest if it covers EVERY way its checker reports a finding.
# The vendored-download checker has two: a tally line, and an early exit 1 that
# prints only `FAIL  … is missing, but something builds …`. Both strings below
# are quoted from check-vendored-download-safety.sh, and the pattern is READ
# OUT of pre-push-check.sh — so this fails if either side drifts.
echo "── the shipped cues must cover every finding path of their checker ──"
GATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pre-push-check.sh"
VENDORED_CUE="$(grep -A2 '"vendored download chain is BUILT but unsafe:"' "$GATE" \
                | sed -n 's/^ *"\(.*\)"$/\1/p' | tail -1)"
if [ -z "$VENDORED_CUE" ]; then
    bad "could not read step 12's proof cue out of pre-push-check.sh"
else
    printf '%s\n' "3 requirement(s) unmet — build chain is wired, hardening is not." > "$TMP/vendored-tally.log"
    printf '%s\n' "FAIL  third_party/filament-kmp/downloads.gradle.kts is missing, but something builds third_party/filament-kmp." > "$TMP/vendored-early.log"
    for f in tally early; do
        if grep -qE "$VENDORED_CUE" "$TMP/vendored-$f.log"; then
            ok "step 12's cue matches the '$f' finding path"
        else
            bad "step 12's cue misses the '$f' finding path — that real finding would be filed as 'could not run'"
        fi
    done
fi

echo ""
echo "test-gradle-run: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
