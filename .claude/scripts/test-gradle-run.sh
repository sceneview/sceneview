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

# The per-task `timeout` from the root build.gradle (#2692) firing. Gradle kills
# the task, so the run rendered NO verdict — neither pass nor fail. Measured on
# 2026-08-09: pre-push printed ":samples:android-demo tests FAILED — every
# screenshot matched its golden" off a log whose ONLY error was this line, on a
# host down to 2 Gi of free disk. Re-running the same task on a healthy host:
# BUILD SUCCESSFUL in 18s.
cat > "$TMP/task-timeout.log" <<'LOG'
> Task :samples:android-demo:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':samples:android-demo:testDebugUnitTest'.
> Timeout has been exceeded
BUILD FAILED in 25m 3s
LOG

# The self-hosted runner and every local session share one ~/.gradle. When a CI
# job starts it REWRITES ~/.gradle/init.d/gradle-actions.*, and any concurrent
# local build whose compiled-script cache still points at the previous copy dies
# in initialization — before a single source file is read. Measured 2026-08-10:
# all four Gradle legs of pre-push printed "FAILED to compile" while a CI worker
# for this very PR was running on the same Mac. Two spellings, depending on
# whether the configuration cache was reused.
cat > "$TMP/init-script-cache.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred evaluating initialization script.
> Could not load compiled classes for script '/Users/x/.gradle/init.d/gradle-actions.build-result-capture-service.plugin.groovy' from cache.

BUILD FAILED in 1s
LOG

cat > "$TMP/init-script-confcache.log" <<'LOG'
Reusing configuration cache.
FAILURE: Build failed with an exception.
* What went wrong:
Class 'BuildResultsRecorder' not found in class loader 'VisitableURLClassLoader(ClassLoaderScopeIdentifier.Id{coreAndPlugins:script-file:/Users/x/.gradle/init.d/gradle-actions.build-result-capture-service.plugin.groovy:groovy-dsl:...})' of type 'org.gradle.internal.classloader.VisitableURLClassLoader'.
CONFIGURE FAILED in 336ms
LOG

# The same shape, but the offending script is one the REPO owns and passes with
# --init-script. That is committed code: a typo in it is a real failure, and
# classifying it as host state would wave a broken build through. Only the
# ~/.gradle/init.d/ path may be treated as infrastructure.
cat > "$TMP/init-script-repo.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred evaluating initialization script.
> Could not load compiled classes for script '/repo/buildSrc/ci-init.gradle' from cache.

BUILD FAILED in 1s
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
assert_infra "$TMP/task-timeout.log" "per-task timeout killed the run before any verdict"
assert_infra "$TMP/init-script-cache.log"    "host init.d/ script changed under a stale script cache"
assert_infra "$TMP/init-script-confcache.log" "same, surfacing through a reused configuration cache"

echo "── gradle_infra_reason: REAL failures must stay real ──"
assert_real "$TMP/compile.log"       "unresolved reference (compile break)"
assert_real "$TMP/testfail.log"      "unit test assertion failure"
assert_real "$TMP/missing-coord.log" "nonexistent dependency coordinate"
assert_real "$TMP/init-script-repo.log" "a REPO-owned init script that fails to compile"

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

# ── The lost-file family (#3175) ────────────────────────────────────────────
# Three shapes of ONE event: the host lost a file while the metadata indexing it
# survived, so Gradle blames what the metadata describes instead of the absence.
# All three read as "your code is wrong" and each cost a full diagnostic cycle
# on 2026-08-14 before they were recognised as the same thing.

# Verbatim from #3175. The jar is not corrupt — `ls` on its directory returns
# nothing at all.
#
# ⚠️ The issue's code block is WRAPPED BY HAND, so the message and the
# `wrapper/dists/` path it names land on different lines. `grep -E` in the
# pattern table is line-based, and the first version of this row anchored on
# both — it could never match, and this fixture is what said so. The raw log is
# almost certainly one long line, but "almost certainly" is not a thing to build
# a classifier on: the row now keys on the message alone, and BOTH renderings
# are fixtures, so the pattern no longer depends on a guess about wrapping.
cat > "$TMP/hollow-wrapper.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
Could not determine implementation class for service
'org.gradle.initialization.buildsrc.BuildSrcProjectConfigurationAction' specified in resource
'jar:file:/Users/x/.gradle/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a/gradle-8.14.5/lib/plugins/gradle-plugin-development-8.14.5.jar!/META-INF/services/x'

BUILD FAILED in 2s
LOG

# The same failure as Gradle actually emits it: one unwrapped line.
cat > "$TMP/hollow-wrapper-oneline.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
Could not determine implementation class for service 'org.gradle.initialization.buildsrc.BuildSrcProjectConfigurationAction' specified in resource 'jar:file:/Users/x/.gradle/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a/gradle-8.14.5/lib/plugins/gradle-plugin-development-8.14.5.jar!/META-INF/services/x'

BUILD FAILED in 2s
LOG

# The artefact-eviction shape. #3175 describes this one from memory rather than
# quoting it, so the exact wording was NOT captured — hence two fixtures, one
# per phrasing Gradle uses for the same condition. The row keys on the
# `caches/modules-2` path, which is what makes it unambiguous: a missing file
# under the dependency cache is never the checkout's fault. `missing-coord.log`
# above is the negative that keeps this honest — a genuinely nonexistent
# coordinate carries no such path and must stay a REAL failure.
cat > "$TMP/evicted-artifact.log" <<'LOG'
* What went wrong:
Could not resolve all files for configuration ':sceneview:debugUnitTestRuntimeClasspath'.
> Failed to transform dynamicanimation-1.0.0.aar (androidx.dynamicanimation:dynamicanimation:1.0.0)
   > java.io.FileNotFoundException: /Users/x/.gradle/caches/modules-2/files-2.1/androidx.dynamicanimation/dynamicanimation/1.0.0/abc/dynamicanimation-1.0.0.aar (No such file or directory)
BUILD FAILED in 9s
LOG
cat > "$TMP/evicted-artifact-alt.log" <<'LOG'
* What went wrong:
Could not resolve all files for configuration ':sceneview:releaseCompileClasspath'.
   > Could not read '/Users/x/.gradle/caches/modules-2/files-2.1/androidx.core/core-ktx/1.13.1/def/core-ktx-1.13.1.aar' as it does not exist.
BUILD FAILED in 7s
LOG

# Verbatim from the 2026-08-14 measurement on this host (#3176): a diff of two
# prose files, zero .kt, and four Gradle legs reporting `FAILED to compile`.
# The remedy is the ONLY one in this table that is worktree-local, and the one
# most likely to be mis-generalised into clearing the shared ~/.gradle.
cat > "$TMP/confcache-poison.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
Could not read workspace metadata from /Users/x/.gradle/caches/8.14.5/transforms/9f2a1c/metadata.bin
> Could not isolate parameters MergeInstrumentationAnalysisTransform (and 158 more failures)
   > A pending instrumentation exception prevented loading a class
     com.android.build.gradle.internal.utils.AnalyticsEnabledValueSource

BUILD FAILED in 12s
LOG

assert_setup() {  # <log> <reason substring> <fix substring> <label>
    local reason fix
    reason="$(gradle_setup_reason "$1")"
    fix="$(gradle_setup_fix "$1")"
    case "$reason" in
        *"$2"*) ok "$4 → setup class" ;;
        *) bad "$4 was NOT classified as setup (reason: '${reason:-<none>}')"; return ;;
    esac
    case "$fix" in
        *"$3"*) ok "$4 → the fix names '$3'" ;;
        *) bad "$4's fix does not tell the user what to do: '$fix'" ;;
    esac
}

echo "── gradle_setup_reason: the lost-file family (#3175) ──"
assert_setup "$TMP/hollow-wrapper.log" "cannot load its OWN service classes" \
    'rm -rf "$HOME/.gradle/wrapper/dists' "an emptied wrapper distribution (as #3175 wraps it)"
assert_setup "$TMP/hollow-wrapper-oneline.log" "cannot load its OWN service classes" \
    'rm -rf "$HOME/.gradle/wrapper/dists' "the same, unwrapped as Gradle emits it"
assert_setup "$TMP/evicted-artifact.log" "evicted from the shared dependency cache" \
    "--refresh-dependencies" "an artefact evicted from caches/modules-2"
assert_setup "$TMP/evicted-artifact-alt.log" "evicted from the shared dependency cache" \
    "--refresh-dependencies" "the same, phrased 'as it does not exist'"
assert_setup "$TMP/confcache-poison.log" "worktree's configuration cache" \
    "rm -rf .gradle/configuration-cache" "a poisoned worktree-local configuration cache"

# The remedies point at three DIFFERENT directories, and confusing them is the
# expensive mistake: ~/.gradle is shared by every worktree on this host, so a
# fix that says to clear it for a fault that is local would break the other
# sessions. Assert the local one stays local.
#
# Grade the COMMAND lines, not the prose. The first version of this check
# grepped the whole fix string and failed on the words "never touch ~/.gradle"
# — a checker that cannot tell a warning from the defect makes documenting the
# defect impossible, and the warning is the most useful sentence in that fix.
# Herestring on the second grep, NOT a pipe into `grep -q`: this file runs under
# `set -o pipefail` (line 16), and `grep -q` exits on its first match, so a
# producer still writing takes EPIPE and fails the pipeline *because the match
# succeeded* (#3180).
fix_rm_lines="$(gradle_setup_fix "$TMP/confcache-poison.log" | grep -E 'rm -rf' || true)"
if grep -qE '(\$HOME|~)/\.gradle' <<< "$fix_rm_lines"; then
    bad "the worktree-local remedy has the user DELETE something under the shared ~/.gradle"
else
    ok "the worktree-local remedy deletes nothing under the shared ~/.gradle"
fi

# …and the reverse direction: these must not swallow a real failure. A build
# that merely MENTIONS the cache path while failing on the code is still a code
# failure.
for fixture in compile testfail missing-coord apidrift init-script-repo; do
    if [ -z "$(gradle_setup_reason "$TMP/$fixture.log")" ]; then
        ok "$fixture survives the lost-file rows as a real failure"
    else
        bad "$fixture is now classified as host setup — a #3175 row is too broad"
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

# Same control for each #3175 row. Without it, a row that silently stopped
# matching — the exact way the `Could not resolve all (files|dependencies…)`
# pattern died once, and the way the first draft of the wrapper row was born —
# would leave these fixtures green, because "unclassified" is what the table
# returns for a log it has never recognised. Each mutant deletes ONE row and
# the fixture that row exists for must go unclassified.
for mut in "Could not determine implementation class for service => " \
           "(caches/modules-2" \
           "(Could not read workspace metadata"; do
    case "$mut" in
        "Could not determine"*) fixture="hollow-wrapper-oneline" ;;
        "(caches/modules-2"*)   fixture="evicted-artifact" ;;
        *)                      fixture="confcache-poison" ;;
    esac
    ROW_MUTANT="$TMP/gradle-run-row-mutant.sh"
    grep -vF "$mut" "$LIB" > "$ROW_MUTANT"
    if [ "$(grep -cF "$mut" "$LIB")" -eq 0 ]; then
        bad "mutation control is vacuous — no row matches '$mut', so nothing was removed"
        continue
    fi
    MUT_REASON="$(bash -c "source '$ROW_MUTANT'; gradle_setup_reason '$TMP/$fixture.log'")"
    if [ -z "$MUT_REASON" ]; then
        ok "removing the row for $fixture leaves it unclassified (its assertions are real)"
    else
        bad "$fixture is still classified after its row was deleted — another row matches it, so the assertions above prove nothing (got '$MUT_REASON')"
    fi
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

# The report helpers must survive an ABSENT log under `set -euo pipefail` —
# tail exits 1 there, and errexit kills the caller at the pipeline, mid-report.
# Same trap gradle_log_tail documents; script_report_failure inherited it.
for fn in script_report_failure gradle_report_failure; do
    if bash -c "set -euo pipefail; source '$LIB'; ERRORS=0; INCOMPLETE=0
                $fn 'demo assets' '$TMP/does-not-exist.log' 1 'diag' 'cue' ''
                echo SURVIVED" 2>/dev/null | grep -q SURVIVED; then
        ok "$fn on a MISSING log does not abort a 'set -euo pipefail' caller"
    else
        bad "$fn on a missing log killed its caller mid-report"
    fi
done

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

# ── Foreign-tree contamination (#3159) ──────────────────────────────────────
# Both directions matter as much as they do for the pattern table above: miss
# the contamination and a verdict is issued about code that is not being pushed;
# fire on a clean log and every gate run on this repo turns into COULD NOT RUN.
echo ""
echo "foreign-tree detection (#3159):"


FT_PROJ="$TMP/proj/sceneview"
mkdir -p "$FT_PROJ"

# A RELATIVE source path is not another checkout. Gradle prints them constantly
# ("samples/android-demo/src/main/java/X.kt"), and the detector used to match
# from the first `/` INSIDE such a path, yielding `/android-demo/src/...` — an
# absolute-looking fragment under no project root, graded as ANOTHER CHECKOUT.
# A healthy leg came back "describes a DIFFERENT checkout" (#3189). The clean-run
# baseline below only catches this when a log on disk happens to carry the
# shape, so it is asserted deterministically here.
FT_REL="$TMP/relative.log"
printf 'OK: samples/android-demo/src/main/java/X.kt\n' > "$FT_REL"
if [ -z "$(gradle_foreign_tree_paths "$FT_REL" "$FT_PROJ")" ]; then
    ok "a relative src/ path is not a foreign checkout"
else
    bad "a relative src/ path was flagged foreign — every gate run naming one would be COULD NOT RUN"
fi


# Verbatim from the api-check.log measured on 2026-08-14: this worktree's gate
# run, carrying another clone's compiler diagnostics.
cat > "$TMP/foreign.log" <<'LOG'
> Task :sceneview-core:compileKotlinJs
w: file:///private/tmp/sv-3136/sceneview-core/src/commonMain/kotlin/io/github/sceneview/triangulation/Earcut.kt:391:22 warning: variable is never used
BUILD SUCCESSFUL in 2m 4s
LOG

# The local tree's OWN diagnostics, same shape, same `/src/` — must NOT trip.
{
    printf '> Task :sceneview:compileReleaseKotlin\n'
    printf 'w: file://%s/sceneview/src/main/kotlin/io/github/sceneview/Scene.kt:12:5 warning: unused\n' "$FT_PROJ"
    printf 'BUILD SUCCESSFUL in 31s\n'
} > "$TMP/local.log"

# A dependency cache path — absolute, foreign, and utterly normal. The pattern
# must be narrow enough to ignore it, or the gate never passes on any host.
cat > "$TMP/cachepaths.log" <<'LOG'
> Task :sceneview:compileReleaseKotlin
Resolving /Users/someone/.gradle/caches/modules-2/files-2.1/androidx.core/core/1.13.0/abc/core-1.13.0.aar
BUILD SUCCESSFUL in 12s
LOG

ft() { gradle_foreign_tree_paths "$1" "$FT_PROJ"; }

if [ -n "$(ft "$TMP/foreign.log")" ]; then
    ok "another clone's source path is detected"
else
    bad "another clone's source path went UNDETECTED — a verdict could be issued about the wrong tree"
fi
for f in local cachepaths; do
    if [ -z "$(ft "$TMP/$f.log")" ]; then
        ok "$f.log is not flagged"
    else
        bad "$f.log was flagged as foreign — every gate run on this host would become COULD NOT RUN"
    fi
done

# The measured clean-run baseline: 0 hits across every log the gate writes. If
# this repo's real logs ever trip the check, the gate is unusable, so assert it
# against the actual artefacts when a previous run left them behind.
REAL_LOGS="$(ls -td "${TMPDIR:-/tmp}"/sceneview-pre-push/*/ 2>/dev/null | head -1)"
if [ -n "$REAL_LOGS" ] && [ -d "$REAL_LOGS" ]; then
    ft_hits=0
    for f in "$REAL_LOGS"*.log; do
        [ -f "$f" ] || continue
        [ -z "$(gradle_foreign_tree_paths "$f" "$SCRIPT_DIR/../..")" ] || ft_hits=$((ft_hits + 1))
    done
    if [ "$ft_hits" -eq 0 ]; then
        ok "no real gate log in $REAL_LOGS trips the check"
    else
        bad "$ft_hits real gate log(s) trip the check — it would block this repo's own clean runs"
    fi
fi

# `gradle_report_failure` must route contamination to INCOMPLETE, never to
# ERRORS — including when a proof cue for a REAL finding is present in the
# (untrustworthy) log. That combination is the whole point: the cue may be the
# other build's.
printf 'API check failed for project sceneview\n' >> "$TMP/foreign.log"
# gradle_report_failure resolves "this tree" from $PWD, and it mutates the
# caller's counters — so it has to run in THIS shell, not a subshell.
ERRORS=0; INCOMPLETE=0; SETUP_FIX=""
cd "$FT_PROJ" || exit 1
gradle_report_failure "apiCheck" "$TMP/foreign.log" 0 \
    "apiCheck failed — public-API surface drifted:" "" "" \
    "(API check failed for project|Expected file with API declarations)" > "$TMP/report.out" 2>&1
cd "$SCRIPT_DIR" || exit 1
if [ "$ERRORS" -eq 0 ] && [ "$INCOMPLETE" -eq 1 ]; then
    ok "a contaminated log is graded COULD NOT RUN, not a public-API finding"
else
    bad "contaminated log gave ERRORS=$ERRORS INCOMPLETE=$INCOMPLETE — expected 0/1"
fi
if grep -q 'DIFFERENT checkout' "$TMP/report.out"; then
    ok "the report names the real cause"
else
    bad "the report does not name the contamination"
fi

if [ -n "$(gradle_foreign_tree_paths /dev/null "$FT_PROJ")" ]; then
    bad "an empty log reported foreign paths"
else
    ok "an empty log reports nothing"
fi

# Negative control, same discipline as the pattern-table mutation above: break
# each half of the detector in a copy of the library and require the cases to
# notice. Without this, the three assertions above would still pass against a
# detector that had quietly stopped detecting.
#
#   A — drop the `/src/` narrowing: the dependency-cache path gets flagged, and
#       the gate stops passing on any host.
#   B — drop the project-dir exclusion: nothing is ever foreign, and the bug
#       #3159 describes is back with the tests still green.
ft_mutant() { # name sed-expr fixture expectation(detect|silent)
    local name="$1" expr="$2" fixture="$3" want="$4"
    local m="$TMP/mutant-gradle-run.sh"
    sed "$expr" "$LIB" > "$m"
    if cmp -s "$m" "$LIB"; then
        bad "mutant \"$name\" changed nothing — it no longer applies to the lib"
        return
    fi
    local got
    got="$(bash -c 'source "$1" >/dev/null 2>&1; gradle_foreign_tree_paths "$2" "$3"' _ "$m" "$TMP/$fixture.log" "$FT_PROJ")"
    case "$want" in
        detect) [ -n "$got" ] && ok "mutant \"$name\" caught ($fixture now flagged)" \
                              || bad "mutant \"$name\" SURVIVED — $fixture stayed clean" ;;
        silent) [ -z "$got" ] && ok "mutant \"$name\" caught ($fixture no longer detected)" \
                              || bad "mutant \"$name\" SURVIVED — $fixture still detected" ;;
    esac
}

ft_mutant 'match any absolute path, not just /src/' \
    's|/src/\[\^ :"|/[^ :"|' cachepaths detect
ft_mutant 'never exclude the project dir' \
    's|case "\$p_norm" in "\$proj_norm"/\*) ;; \*) printf|case "$p_norm" in "$proj_norm"/*) ;; "") printf|' \
    foreign silent

echo ""
echo "test-gradle-run: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
