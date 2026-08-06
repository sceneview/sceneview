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

echo ""
echo "test-gradle-run: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
