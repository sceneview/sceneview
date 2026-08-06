#!/usr/bin/env bash
# gradle-run.sh — run a Gradle task while KEEPING its output, and tell an
# infrastructure failure apart from a real one.
#
# Why this exists
# ---------------
# The gate scripts used to invoke Gradle as:
#
#     if ./gradlew <task> --quiet 2>/dev/null; then ✓ else ✗ "<specific diagnosis>"
#
# That shape turns *every* non-zero exit into one hard-coded diagnosis, and
# `2>/dev/null` destroys the only evidence that could contradict it.
#
# Measured 2026-08-06: `pre-push-check.sh` step 5 reported
# "Android screenshot regression detected" when the real cause was
#
#     Gradle build daemon disappeared unexpectedly (it may have been killed or
#     may have crashed)
#
# — daemon contention on a Mac running several sessions in parallel. The same
# failure reproduced on a pristine clone of `main`, i.e. with no golden and no
# source change whatsoever. Re-running the task alone returned exit 0. No
# Roborazzi comparison report existed, because the comparison never ran.
#
# Two things follow, and both are enforced by callers of this helper:
#   1. Never discard Gradle's output — write it to a log and quote it.
#   2. Never assert a *specific* cause without the artefact that proves it.
#
# Usage from another script:
#     source "$(dirname "$0")/lib/gradle-run.sh"
#     if gradle_run /tmp/x.log :sceneview:compileReleaseKotlin; then ✓ else
#         reason=$(gradle_infra_reason /tmp/x.log $?)
#         [ -n "$reason" ] && … infra … || … real failure …
#         gradle_log_tail /tmp/x.log
#     fi
#
# Note on flags: `--console=plain` replaces the old `--quiet`. Quiet mode
# suppresses the `> Task :x:y UP-TO-DATE` lines, and those lines are the only
# way to see that a task was skipped rather than executed — which is exactly
# what the Roborazzi check in pre-push-check.sh now has to distinguish.

# Run a Gradle task, capturing stdout+stderr into $1. Returns Gradle's own
# exit code (never a pipe's — nothing is piped, see
# project_pipe_to_tail_masks_build_exit_code).
#
#   gradle_run <logfile> <gradle args...>
gradle_run() {
    local log="$1"
    shift
    ./gradlew "$@" --console=plain > "$log" 2>&1
}

# Echo a short human-readable reason when a failure is environmental — the
# build never got far enough to judge the code — and echo NOTHING otherwise.
# Always returns 0 so callers can use it in an assignment under `set -e`.
#
#   gradle_infra_reason <logfile> [exit-code]
#
# Patterns are deliberately narrow and quoted from real Gradle/JVM output. A
# pattern that is too broad would relabel genuine build failures as "infra",
# which is the same class of lie in the other direction.
gradle_infra_reason() {
    local log="$1"
    local code="${2:-1}"

    # Signal-terminated builds never produced a verdict either.
    case "$code" in
        130) echo "build interrupted (SIGINT)"; return 0 ;;
        137) echo "build process killed (SIGKILL — usually the OOM killer)"; return 0 ;;
        143) echo "build terminated (SIGTERM)"; return 0 ;;
    esac

    [ -f "$log" ] || { echo "no Gradle log was produced"; return 0; }

    # `<extended-regex> => <reason>`, first match wins. The separator is ` => `
    # and NOT `|`, because `|` is the alternation operator these patterns need:
    # splitting on it truncated `Could not resolve all (files|dependencies…)` at
    # its first alternative and the pattern silently stopped matching anything
    # (caught by test-gradle-run.sh, which is why that suite exists).
    local line pat reason
    while read -r line; do
        [ -n "$line" ] || continue
        pat="${line%% => *}"
        reason="${line#* => }"
        if grep -qE "$pat" "$log" 2>/dev/null; then
            echo "$reason"
            return 0
        fi
    done <<'PATTERNS'
daemon disappeared unexpectedly => the Gradle daemon disappeared (daemon contention — are other builds running?)
Unable to start the daemon process => the Gradle daemon could not be started
Timeout waiting to connect to the Gradle daemon => timed out connecting to the Gradle daemon
Timeout waiting to lock => a Gradle lock was held by another build (concurrent builds on this host)
java\.lang\.OutOfMemoryError => the build ran out of JVM memory
There is insufficient memory for the Java Runtime => the host ran out of memory for the JVM
No space left on device => the disk is full
error=24, Too many open files => the host hit its open-file limit
Could not resolve all (files|dependencies|artifacts) for configuration => dependency resolution failed (network or repository problem)
(UnknownHostException|Connection refused|Connection reset|Read timed out|Network is unreachable) => the network was unreachable during the build
Could not (GET|HEAD) ' => a dependency repository could not be reached
Received fatal alert: => a TLS error while contacting a repository
Could not open .* (generic class|proj\.dir) cache => the Gradle cache is corrupt (rm -rf the module build/ dirs)
The supplied javaHome seems to be invalid => org\.gradle\.java\.home points at an invalid JDK
PATTERNS

    return 0
}

# Print the tail of a Gradle log, indented, skipping the boilerplate footer
# ("Try:", "Get more help at", deprecation nags) that carries no diagnosis.
#
#   gradle_log_tail <logfile> [lines]
gradle_log_tail() {
    local log="$1"
    local lines="${2:-20}"
    [ -f "$log" ] || { echo "      (no log file at $log)"; return 0; }
    grep -vE "^(\* )?(Try:|> Run with|Get more help at|Deprecated Gradle features|You can use|See https://docs\.gradle\.org|BUILD FAILED in|[[:space:]]*$)" "$log" \
        | tail -n "$lines" | sed 's/^/      /'
}

# Did Gradle actually EXECUTE the given task, as opposed to skipping it as
# UP-TO-DATE / FROM-CACHE / NO-SOURCE? Requires `--console=plain` output (which
# gradle_run always uses). Returns 0 when the task ran.
#
#   gradle_task_executed <logfile> <task-path>
#
# `verifyRoborazziDebug` reports UP-TO-DATE whenever nothing it declares as an
# input changed — and the golden PNGs under src/test/snapshots/ are NOT declared
# inputs (measured 2026-08-06: a golden mutated by 8000 red pixels still gave
# `> Task :samples:android-demo:verifyRoborazziDebug UP-TO-DATE` and
# `BUILD SUCCESSFUL in 1s`). So "exit 0" alone does not mean "screenshots were
# compared".
gradle_task_executed() {
    local log="$1"
    local task="$2"
    [ -f "$log" ] || return 1
    grep -qE "^> Task ${task}\$" "$log"
}

# ── Roborazzi ───────────────────────────────────────────────────────────────
# The screenshot verdict must come from the comparison REPORT, never from the
# exit code. Echo how many goldens differ (changed + added) when the report is
# NEWER than <marker> — i.e. this run really produced it — and echo `unknown`
# when there is no fresh report, which means nothing was compared.
#
# Measured 2026-08-06 on samples/android-demo (roborazzi 1.70.0), all four
# cases:
#   real diff  -> exit 1, report REWRITTEN, changed:1
#   genuine ok -> exit 0, report REWRITTEN, changed:0
#   UP-TO-DATE -> exit 0, report UNTOUCHED  (old code printed "✓ match" here)
#   daemon died-> exit 1, report UNTOUCHED  (old code printed "regression" here)
#
#   roborazzi_fresh_diff_count <results-summary.json> <marker-file>
roborazzi_fresh_diff_count() {
    local summary="$1"
    local marker="$2"
    if [ -f "$summary" ] && [ -f "$marker" ] && [ "$summary" -nt "$marker" ]; then
        python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["summary"]; print(s.get("changed",0)+s.get("added",0))' \
            "$summary" 2>/dev/null || echo unknown
    else
        echo unknown
    fi
}
