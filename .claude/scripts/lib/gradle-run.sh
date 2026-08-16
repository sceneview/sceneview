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
    local code=0
    shift
    ./gradlew "$@" --console=plain > "$log" 2>&1 || code=$?

    # A log that describes another checkout cannot be graded — in EITHER
    # direction, which is why the exit code is not consulted here:
    #   exit 0 → the pass is unearned;
    #   exit ≠0 → the errors quoted back may be the other build's, so a specific
    #             diagnosis ("public-API surface drifted") would name this tree
    #             for a foreign failure.
    # Report the distinguished code so a caller that has never heard of the
    # problem still takes its failure branch and lands in COULD NOT RUN — which
    # exits non-zero, so downgrading a genuine failure to it never opens a push.
    if [ -n "$(gradle_foreign_tree_paths "$log")" ]; then
        return "$GRADLE_RUN_FOREIGN_TREE"
    fi
    return "$code"
}

# Exit code meaning "Gradle exited 0, but its log is about a different tree".
# 199 is outside Gradle's own range and outside 128+signal.
GRADLE_RUN_FOREIGN_TREE=199

# Echo the source paths in <log> that belong to a checkout OTHER than this one,
# nothing when the log is clean. Always returns 0 (assignment-safe under `set -e`).
#
#   gradle_foreign_tree_paths <logfile> [project-dir]
#
# Why (#3159, measured 2026-08-14): with several clones building at once, the
# shared Gradle daemon interleaves another project's console output into this
# invocation's log. A gate run in a worktree produced an api-check.log full of
#
#     w: file:///private/tmp/sv-3136/sceneview-core/src/commonMain/.../Earcut.kt:391:22
#
# — a different clone entirely. That run ended in the correct refusal, but only
# because the competing build happened to fail; nothing in the gate compared the
# log to the tree being pushed. Two consecutive runs on the same unchanged tree
# also disagreed (`2 CHECK(S) FAILED`, then `0 failed, 1 could not run`), and a
# verdict that depends on what else is running is not a verdict.
#
# ⛔ What this does and does not establish. It detects CONTAMINATION — evidence
# that the log describes foreign code — and that is all. It does NOT prove the
# verdict covered this tree; no log inspection can. So its only correct use is
# to withhold a pass (COULD NOT RUN), never to grant one.
#
# The match is deliberately narrow: only absolute paths containing `/src/`, so
# `~/.gradle/caches/...` jars, JDK and toolchain paths cannot trip it. Gradle
# prints module-relative paths for the local tree, which is what makes the
# narrowness safe — and also what made the missing anchor dangerous, since those
# same relative paths are the ones that used to be misread as foreign (#3195).
# The "0 hits across all 60 logs" this comment used to claim was measured before
# the gate wrote `roborazzi.log`; it is asserted now instead of remembered, by
# the clean-run case in `test-gradle-run.sh` that reads the real artefacts.
gradle_foreign_tree_paths() {
    local log="$1"
    local proj="${2:-$PWD}"
    [ -f "$log" ] || return 0

    # -P resolves symlinks: the log carries real paths, and on macOS /tmp is a
    # symlink to /private/tmp, so an unresolved comparison would call the
    # project's own files foreign.
    proj="$(cd "$proj" 2>/dev/null && pwd -P)" || return 0

    # The leading `/` must actually BEGIN a path. Without that anchor the pattern
    # also matches the tail of a RELATIVE one, and the gate's own `roborazzi.log`
    # carries `samples/android-demo/src/main/.../GeneratedDemos.kt already in
    # sync`: grep started at the slash after `samples` and yielded
    # `/android-demo/src/…`, a tree that exists on no host. This repository's own
    # clean run was graded foreign and the gate refused every push (#3195).
    # A path begins at start-of-line or after a delimiter — which is then
    # stripped back off, before `file://`, so the two sed passes cannot collide.
    local bound='(^|[[:space:]:="'"'"'(])'
    grep -oE "${bound}(file://)?/[^ :\"']*/src/[^ :\"']*" "$log" 2>/dev/null \
    | sed -E 's|^[[:space:]:="'"'"'(]||' \
    | sed 's|^file://||' \
    | while IFS= read -r p; do
        # Same /private normalisation for the candidate, which may name a
        # directory that does not exist on this host and so cannot be resolved.
        case "$p" in /private/*) p_norm="${p#/private}" ;; *) p_norm="$p" ;; esac
        case "$proj" in /private/*) proj_norm="${proj#/private}" ;; *) proj_norm="$proj" ;; esac
        case "$p_norm" in "$proj_norm"/*) ;; *) printf '%s\n' "$p" ;; esac
    done | sort -u
    return 0
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
#
# ⚠️ Do NOT add a bare `Could not resolve all (files|dependencies|artifacts) for
# configuration` row, however tempting. Gradle prints that same first line both
# when the repository is unreachable AND when a committed coordinate simply does
# not exist (a typo, an unpublished version, a yanked artifact) — a code defect.
# Only the transport rows below discriminate: a nonexistent coordinate produces
# `Could not find <coord>` with no transport error, so it correctly falls
# through to "real failure". That row was here and was removed in review.
gradle_infra_reason() {
    local log="$1"
    local code="${2:-1}"

    # Signal-terminated builds never produced a verdict either.
    case "$code" in
        130) echo "build interrupted (SIGINT)"; return 0 ;;
        137) echo "build process killed (SIGKILL — usually the OOM killer)"; return 0 ;;
        143) echo "build terminated (SIGTERM)"; return 0 ;;
        199) echo "the build log describes a DIFFERENT checkout — a concurrent build in another clone bled into it, so this run cannot be graded (#3159); re-run when no other build is competing for the Gradle daemon"; return 0 ;;
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
(UnknownHostException|Connection refused|Connection reset|Read timed out|Network is unreachable) => the network was unreachable during the build
Could not (GET|HEAD) ' => a dependency repository could not be reached
Received fatal alert: => a TLS error while contacting a repository
Could not open .* (generic class|proj\.dir) cache => the Gradle cache is corrupt (rm -rf the module build/ dirs)
Timeout has been exceeded => the task hit its Gradle `timeout` and was KILLED before rendering any verdict (host load, or a genuinely hung test — the log carries a thread dump naming the stuck thread)
(Could not load compiled classes for script '[^']*init\.d/|not found in class loader .*init\.d/) => a HOST init script in ~/.gradle/init.d/ changed under a stale compiled-script cache (the self-hosted runner rewrites those files mid-run, killing every concurrent local build) — wait for the runner to go idle, or build with an isolated GRADLE_USER_HOME
PATTERNS

    return 0
}

# ── Host setup failures ─────────────────────────────────────────────────────
# A THIRD class, distinct from both "infrastructure" and "the code is broken":
# the toolchain the build needs is not configured on this machine. Like an
# infra failure it means the gate never judged the code, so no check may claim
# a verdict — but unlike daemon contention, re-running changes nothing. There
# is an exact fix, and the caller must print it.
#
# Measured 2026-08-09 (#3065): a fresh worktree has no `local.properties`
# (gitignored, .gitignore:66), so every Gradle step died with `SDK location not
# found` — and pre-push-check.sh announced "public-API surface drifted" and
# prescribed `./gradlew apiDump`, a command that would have failed the same way
# or committed a bogus .api diff. Adding one `sdk.dir=` line flipped the SAME
# commit to 14/14 green.
#
# Measured 2026-08-14 (#3175): the last three rows are one family — a file the
# host lost while the metadata that indexes it survived. Gradle then blames the
# thing the metadata describes (a dependency, an API, a plugin service) instead
# of the absence, so all three read as "your code is wrong" and cost a full
# diagnostic cycle each. They belong HERE and not in the infra table because
# re-running changes nothing: the file stays missing until someone deletes the
# stale index. Note the remedies point at three DIFFERENT directories — one
# shared, one shared-but-per-artefact, one worktree-local — and the third must
# never be treated as a reason to clear ~/.gradle, which 9 sessions share.
#
# Same discipline as the table above: narrow, quoted from real AGP/Gradle
# output. Row shape is `<extended-regex> => <reason> => <fix>`; the fix is
# printed with `printf %b`, so `\n` breaks a line. Single quotes on the
# here-doc are load-bearing — `$HOME` must reach the user's terminal literally,
# as part of a command they can paste.
_gradle_setup_row() {
    local log="$1" line pat
    [ -f "$log" ] || return 0
    while read -r line; do
        [ -n "$line" ] || continue
        pat="${line%% => *}"
        if grep -qE "$pat" "$log" 2>/dev/null; then
            echo "$line"
            return 0
        fi
    done <<'PATTERNS'
SDK location not found => the Android SDK location is not configured => Point the build at your Android SDK, then re-run this gate:\n        echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties\n        (or: export ANDROID_HOME="$HOME/Library/Android/sdk")\n      local.properties is gitignored, so EVERY new worktree needs its own copy.
(License for package .* not accepted|Failed to install the following (Android SDK packages|SDK components)) => a required Android SDK package is missing, or its licence was never accepted => Install it and accept the licences, then re-run:\n        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
Failed to find (target with hash string|Build Tools revision) => the Android platform / build-tools this project compiles against are not installed => Install the version quoted above with sdkmanager, then re-run:\n        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-<N>" "build-tools;<V>"
(No version of NDK matched the requested version|NDK not configured) => the NDK version this project requests is not installed => Install the version quoted above, then re-run:\n        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;<version>"
(The supplied javaHome seems to be invalid|Value '.*' given for org\.gradle\.java\.home) => the JDK this build points at is not usable => Fix org.gradle.java.home in gradle.properties (or JAVA_HOME) so it points at an installed JDK.
Could not determine implementation class for service => the Gradle distribution cannot load its OWN service classes — the jar named in the error is absent or unreadable, not something this checkout can affect => Force the wrapper to fetch the distribution again; it will not do so on its own, because it launched from that directory:\n        rm -rf "$HOME/.gradle/wrapper/dists/gradle-<version>-bin"\n        ./gradlew --version\n      Delete ONLY that one dist directory: ~/.gradle is shared by every worktree on this host.
(caches/modules-2.*(No such file or directory|does not exist)|(No such file or directory|does not exist).*caches/modules-2) => an artefact was evicted from the shared dependency cache while its METADATA survived, so Gradle reports a dependency or API problem instead of a missing file => Re-fetch the artefact, then re-run this gate:\n        ./gradlew --refresh-dependencies <the task that failed>\n      Nothing in this checkout caused this — it is host state, not a code change.
(Could not read workspace metadata from .*/transforms/|A pending instrumentation exception prevented loading a class) => THIS worktree's configuration cache memoised a class whose instrumented-transform output has since been evicted from the shared ~/.gradle => Delete this worktree's own configuration cache — the fault is LOCAL, never touch ~/.gradle for it:\n        rm -rf .gradle/configuration-cache\n      ./gradlew --stop does NOT fix this (measured 2026-08-14, #3176: 4 legs red before, 4 after).
PATTERNS

    return 0
}

# Echo a short reason when the failure is a HOST SETUP problem, nothing
# otherwise. Always returns 0 (assignment-safe under `set -e`).
#
#   gradle_setup_reason <logfile>
gradle_setup_reason() {
    local row
    row="$(_gradle_setup_row "$1")"
    [ -n "$row" ] || return 0
    row="${row#* => }"
    echo "${row%% => *}"
}

# Echo the actionable fix for that setup problem, nothing otherwise.
#
#   gradle_setup_fix <logfile>
gradle_setup_fix() {
    local row
    row="$(_gradle_setup_row "$1")"
    [ -n "$row" ] || return 0
    row="${row#* => }"
    printf '%b\n' "${row#* => }"
}

# Print the tail of a Gradle log, indented, skipping the boilerplate footer
# ("Try:", "Get more help at", deprecation nags) that carries no diagnosis.
#
#   gradle_log_tail <logfile> [lines]
gradle_log_tail() {
    local log="$1"
    local lines="${2:-20}"
    [ -f "$log" ] || { echo "      (no log file at $log)"; return 0; }
    # The trailing `|| true` is load-bearing, and a `return 0` on the next line
    # is NOT a substitute for it. `grep -v` exits 1 when NOTHING survives the
    # filter (an empty log, or one that is all boilerplate). Under
    # `set -euo pipefail` — which release-checklist.sh uses — pipefail promotes
    # that to the pipeline's status and errexit kills the caller AT THE
    # PIPELINE, before any later statement in this function can run: mid-report,
    # right after a genuine build failure, swallowing every remaining section
    # and the summary. Putting the pipeline in a `||` list exempts it from
    # errexit. A helper that only prints must never be able to end its caller.
    grep -vE "^(\* )?(Try:|> Run with|Get more help at|Deprecated Gradle features|You can use|See https://docs\.gradle\.org|BUILD FAILED in|[[:space:]]*$)" "$log" \
        | tail -n "$lines" | sed 's/^/      /' || true
    return 0
}

# ── Reporting ───────────────────────────────────────────────────────────────
# Report ONE failed Gradle step, naming only the cause the log actually
# supports. Lives here, next to the classifier, so a test can drive the real
# branching with real Gradle logs instead of a re-implementation of it.
#
#   gradle_report_failure <label> <log> <code> <real-diagnosis> \
#                         [real-remedy] [never-ran-note] [proof-regex]
#
# Four outcomes, in this order:
#   1. host setup    → ⚠, the exact fix, INCOMPLETE++ (re-running changes nothing)
#   2. infrastructure→ ⚠, INCOMPLETE++ (re-running may well work)
#   3. no proof      → ⚠, INCOMPLETE++ (Gradle died before this check compared
#                      anything — see <proof-regex> below)
#   4. real failure  → ✗ <real-diagnosis> [+ <real-remedy>], ERRORS++
#
# <never-ran-note> is printed in cases 1-3 only: it is where a caller says what
# was NOT established (e.g. "the public API was never compared"), so that an
# unrun check can never be read as a passed one.
#
# <proof-regex>, when given, is the POSITIVE cue the tool prints when it truly
# reached its verdict. Without it, "real failure" degrades to "anything Gradle
# did not fail for a reason we recognise" — and every task that dies UPSTREAM
# of the check inherits the check's diagnosis. Measured 2026-08-09 on this host
# while fixing #3065: `apiCheck` died in `:sceneview:apiBuild`
# (`kotlinx.validation.AbiBuildWorker` → `kotlin/metadata/jvm/JvmMetadataUtil`,
# a poisoned instrumented-transform cache — CI was green throughout), so the
# ABI was never compared, and both the old code AND the first cut of this fix
# still called it "public-API surface drifted".
#
# Mutates the caller's ERRORS / INCOMPLETE counters, and records the first
# setup fix in SETUP_FIX so the caller's summary can repeat it.
gradle_report_failure() {
    local label="$1" log="$2" code="$3" diag="$4" remedy="${5:-}" note="${6:-}" proof="${7:-}"
    local red="${RED:-$'\033[0;31m'}" yellow="${YELLOW:-$'\033[1;33m'}" nc="${NC:-$'\033[0m'}"
    local reason fix

    # First, because a contaminated log makes every OTHER classifier below
    # unreliable: the setup and infra tables would happily match the competing
    # build's "SDK location not found" and print this host a fix it does not
    # need. When we cannot trust the log, we say so and grade nothing (#3159).
    local foreign
    foreign="$(gradle_foreign_tree_paths "$log")"
    if [ -n "$foreign" ]; then
        echo -e "${yellow}  ⚠ $label did not run to a verdict — its log describes a DIFFERENT checkout${nc}"
        echo -e "${yellow}      A concurrent build in another clone bled into this run's output, so nothing${nc}"
        echo -e "${yellow}      in this log can be attributed to the tree you are pushing (#3159).${nc}"
        printf '%s\n' "$foreign" | head -3 | while IFS= read -r p; do
            printf '        %s\n' "$p"
        done
        echo -e "${yellow}      Re-run when no other build is competing for the Gradle daemon:${nc}"
        echo -e "${yellow}        ./gradlew --stop && bash .claude/scripts/pre-push-check.sh${nc}"
        echo -e "      Full log: $log"
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
        return 0
    fi

    reason="$(gradle_setup_reason "$log")"
    if [ -n "$reason" ]; then
        echo -e "${yellow}  ⚠ $label did NOT run — $reason${nc}"
        [ -n "$note" ] && echo -e "${yellow}      $note${nc}"
        gradle_log_tail "$log" 12
        fix="$(gradle_setup_fix "$log")"
        echo -e "${yellow}      Fix: $fix${nc}"
        [ -n "${SETUP_FIX:-}" ] || SETUP_FIX="$fix"
        echo -e "      Full log: $log"
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
        return 0
    fi

    reason="$(gradle_infra_reason "$log" "$code")"
    if [ -n "$reason" ]; then
        echo -e "${yellow}  ⚠ $label did not run to a verdict — Gradle infrastructure failure: $reason${nc}"
        [ -n "$note" ] && echo -e "${yellow}      $note${nc}"
        gradle_log_tail "$log" 12
        echo -e "      Full log: $log"
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
        return 0
    fi

    if [ -n "$proof" ] && ! grep -qE "$proof" "$log" 2>/dev/null; then
        echo -e "${yellow}  ⚠ $label did not run to a verdict — Gradle failed before $label compared anything${nc}"
        [ -n "$note" ] && echo -e "${yellow}      $note${nc}"
        gradle_log_tail "$log" 20
        echo -e "      Full log: $log"
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
        return 0
    fi

    echo -e "${red}  ✗ $diag${nc}"
    gradle_log_tail "$log" 20
    [ -n "$remedy" ] && echo -e "      $remedy"
    echo -e "      Full log: $log"
    ERRORS=$((${ERRORS:-0} + 1))
    return 0
}

# Report a failed NON-Gradle checker, on the same rule as above: a checker
# that could not run has not found anything, so it may not be given a finding's
# name. Same counters, same contract.
#
#   script_report_failure <label> <log> <code> <real-diagnosis> <proof-regex> [remedy]
#
# <proof-regex> is the POSITIVE cue the checker prints when it truly reached a
# verdict (its own "✗ …" line). Requiring it is what separates "found a
# problem" from "died on the way there": the repo's checkers all run under
# `set -euo pipefail` (or `sys.exit(main())`), where an unexpected crash or a
# Python traceback also exits 1 — so the exit code alone cannot carry the
# distinction. Exit >= 2 is their documented "cannot run" code (bad args,
# missing python3, wrong directory); 126/127 is the shell failing to execute
# them at all.
# Each `tail | sed` below carries the same `|| true` as gradle_log_tail, for
# the same measured reason: `tail` on an absent log exits 1, and under
# `set -euo pipefail` — which release-checklist.sh uses — pipefail promotes
# that to the pipeline and errexit kills the caller AT THE PIPELINE, mid-report.
script_report_failure() {
    local label="$1" log="$2" code="$3" diag="$4" proof="$5" remedy="${6:-}"
    local red="${RED:-$'\033[0;31m'}" yellow="${YELLOW:-$'\033[1;33m'}" nc="${NC:-$'\033[0m'}"
    if [ "$code" -ge 2 ]; then
        case "$code" in
            126|127) echo -e "${yellow}  ⚠ $label NOT checked — the checker could not be executed (exit $code)${nc}" ;;
            *)       echo -e "${yellow}  ⚠ $label NOT checked — the checker itself could not run (exit $code)${nc}" ;;
        esac
        tail -20 "$log" 2>/dev/null | sed 's/^/      /' || true
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
    elif ! grep -qE "$proof" "$log" 2>/dev/null; then
        echo -e "${yellow}  ⚠ $label NOT checked — the checker exited $code without reaching its verdict${nc}"
        tail -20 "$log" 2>/dev/null | sed 's/^/      /' || true
        INCOMPLETE=$((${INCOMPLETE:-0} + 1))
    else
        echo -e "${red}  ✗ $diag${nc}"
        tail -30 "$log" 2>/dev/null | sed 's/^/      /' || true
        [ -n "$remedy" ] && echo -e "      $remedy"
        ERRORS=$((${ERRORS:-0} + 1))
    fi
    return 0
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
