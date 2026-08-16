#!/usr/bin/env bash
# Every JS/TS package that carries tests must be reachable from a workflow.
#
# WHY THIS EXISTS
# ---------------
# `mcp-gateway/` held 17 test files and 200 passing cases that NO workflow ever
# ran (#3201). One of them had silently inverted — it asserted a tool count the
# dashboard had outgrown, so it was green while the page was wrong and only
# turned red once the page was corrected. A test that never runs cannot report
# that; it had been in that state for months.
#
# The gap survived because grepping `mcp-gateway` in `.github/workflows/` is
# not how you find it. Two workflows carry near-homonyms — `mcp/` and
# `telemetry-worker/` — so a reader looking for "is the gateway covered?" gets
# hits and stops. The blind spot was not that nobody looked; it was that
# looking returned a plausible answer.
#
# THE SHAPE THAT ACTUALLY HOLDS
# -----------------------------
# The starting set comes from the FILESYSTEM, never from a list in this file.
# A whitelist of packages fails the same way the original gap did: a package
# added tomorrow and forgotten in the list passes green without a word, which
# is precisely the failure being fixed. So this gate enumerates every versioned
# `*.test.*` / `*.spec.*` file — plus anything under a `__tests__/` directory,
# a jest default include that matches neither infix — folds each to its owning
# package, and then has to say something about each one. It can be wrong about
# a package, but it cannot be silent about one.
#
# The `__tests__/` arm matches nothing in this repo today. It is here because
# the enumeration is the one place where a miss is indistinguishable from a
# clean bill of health: a package whose tests stop matching the glob does not
# turn red, it leaves the report, and the total quietly drops by one.
#
# WHAT "REACHABLE" MEANS HERE
# ---------------------------
# Two conditions, because either one alone is worthless:
#   1. INVOKED  — some workflow step runs a test runner (npm test / vitest /
#      jest / playwright) against the package, via `working-directory:` or by
#      naming the directory in the command.
#   2. TRIGGERED — that same workflow fires when the package changes: it has no
#      `paths:` filter at all, or one whose glob covers the package. A workflow
#      that runs the tests but never triggers on them is as blind as no
#      workflow at all, and reads more convincingly.
#
# THREE VERDICTS, and the middle one is the point:
#   OK       invoked, triggered, runs on pull requests, and can fail the build.
#   ADVISORY invoked and triggered, but the run cannot block a merge — either
#            it cannot report red (`|| true`, `continue-on-error: true` in the
#            same step) or its workflow has no `pull_request` trigger, so it
#            only speaks after the merge. Not a failure here: this repo has
#            deliberately non-blocking legs. Named out loud so "it runs" is
#            never mistaken for "it can stop a bad merge".
#   MISSING  neither, or triggered-but-never-invoked. Fails the gate.
#
# BOUNDS — stated because an unstated bound reads as coverage
# ----------------------------------------------------------
# * JS/TS only. Kotlin and Swift suites are driven by Gradle and Xcode legs,
#   whose reachability is a different question with a different answer; this
#   gate says nothing about them and prints that it does not.
# * `dist/` and `build/` are excluded: this repo versions compiled `dist/`
#   output for three MCP packages, so their `*.test.js` copies would otherwise
#   be counted as separate suites that nothing runs — true, and useless.
# * A job-level `continue-on-error: true` is NOT detected, only a step-level
#   one; such a suite is reported OK when it is really advisory. Printed in the
#   footer rather than left for a reader to discover.
#
# Usage: check-test-suites-reachable.sh [repo-root]
set -uo pipefail

ROOT="${1:-.}"
cd "$ROOT" || { echo "check-test-suites-reachable: cannot enter $ROOT" >&2; exit 2; }

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; NC=$'\033[0m'
if [ ! -t 1 ]; then RED=""; GREEN=""; YELLOW=""; NC=""; fi

WF_DIR=".github/workflows"

# ── 1. Enumerate the suites from disk ────────────────────────────────────────
# `git ls-files` and not `find`: an untracked scratch test is not a suite the
# repo owes CI coverage for, and node_modules is excluded for free.
ALL_FILES="$(git ls-files 2>/dev/null)" || {
    echo "check-test-suites-reachable: not a git repository ($ROOT)" >&2; exit 2; }

TEST_FILES="$(printf '%s\n' "$ALL_FILES" \
    | grep -E '(\.(test|spec)\.(ts|tsx|js|jsx|mjs|cjs)$|(^|/)__tests__/.+\.(ts|tsx|js|jsx|mjs|cjs)$)' \
    | grep -vE '(^|/)(node_modules|dist|build|out)/' || true)"

# Every versioned package root, computed once. Note the herestrings below
# rather than `printf ... | grep -q`: under `pipefail`, `grep -q` exits at the
# first match, `printf` takes a SIGPIPE, and the PIPELINE reports 141 — so the
# lookup would answer "not found" precisely when it found something. It cost an
# hour here, and it is the same false-negative shape this gate is about.
PKG_DIRS="$(printf '%s\n' "$ALL_FILES" \
    | grep -E '(^|/)package\.json$' \
    | grep -vE '(^|/)node_modules/' \
    | sed 's|package\.json$||; s|/$||; s|^$|.|' \
    | sort -u)"

# Fold each test file to its owning package: the nearest ancestor directory
# holding a versioned package.json. A test file with no package.json above it
# is reported rather than dropped — silently ignoring it is how a suite goes
# missing, which is the bug this gate is about.
declare -a SUITES=()
declare -a ORPHANS=()
while IFS= read -r f; do
    [ -n "$f" ] || continue
    d="$(dirname "$f")"
    found=""
    while :; do
        if grep -qxF -- "$d" <<< "$PKG_DIRS"; then found="$d"; break; fi
        case "$d" in .|/) break ;; esac
        d="$(dirname "$d")"
    done
    if [ -n "$found" ]; then SUITES+=("$found"); else ORPHANS+=("$f"); fi
done <<< "$TEST_FILES"

# shellcheck disable=SC2207
SUITES=($(printf '%s\n' "${SUITES[@]:-}" | grep -v '^$' | sort -u))

# ── 2. Extract every test invocation from every workflow ─────────────────────
# Emitted as: workflow<TAB>working-directory<TAB>command<TAB>advisory(0|1)
#
# Comment lines are skipped, and that is load-bearing rather than tidy: a
# comment CLAIMING a suite is covered is exactly what this repo had — a
# `mcp-ts-check.yml` header stating that `npm test` "is run by ci.yml's Quality
# gate job" when ci.yml contains no `npm` command at all. Counting that line as
# an invocation would let the false claim satisfy the gate that exists to catch
# it.
scan_invocations() {
    for wf in "$WF_DIR"/*.yml "$WF_DIR"/*.yaml; do
        [ -f "$wf" ] || continue
        awk -v wf="$(basename "$wf")" '
            # A new list item ends the previous step, so its working-directory
            # must not leak forward onto the next one.
            /^[[:space:]]*-[[:space:]]/ { wd=""; ce=0 }
            {
                line = $0
                sub(/^[[:space:]]+/, "", line)
                if (line ~ /^#/) next
                # A step TITLED "Run vitest" is not a step that runs vitest.
                # Same failure direction as the comment above: a label that
                # merely describes coverage would be counted as coverage.
                if (line ~ /^-?[[:space:]]*name:[[:space:]]/) next
                if (line ~ /^-?[[:space:]]*working-directory:[[:space:]]*/) {
                    v = line
                    sub(/^-?[[:space:]]*working-directory:[[:space:]]*/, "", v)
                    gsub(/["\x27]/, "", v)
                    sub(/[[:space:]]+$/, "", v)
                    wd = v
                }
                if (line ~ /continue-on-error:[[:space:]]*true/) ce = 1
                if (line ~ /(npm[[:space:]]+(run[[:space:]]+)?test|vitest|jest|playwright[[:space:]]+test)/) {
                    adv = (ce == 1 || line ~ /\|\|[[:space:]]*true/) ? 1 : 0
                    printf "%s\t%s\t%s\t%d\n", wf, wd, line, adv
                }
            }
        ' "$wf"
    done
}

# ── 3. Extract each workflow's trigger paths ─────────────────────────────────
# A workflow with no `paths:` anywhere fires on every change: recorded as the
# literal `<none>`, which every suite matches.
trigger_prefixes() {
    local wf="$1"
    awk '
        /^[[:space:]]*paths(-ignore)?:[[:space:]]*$/ { inp = ($0 ~ /paths-ignore/) ? 0 : 1; next }
        inp && /^[[:space:]]*-[[:space:]]/ {
            v = $0
            sub(/^[[:space:]]*-[[:space:]]*/, "", v)
            gsub(/["\x27]/, "", v)
            sub(/[[:space:]]+.*$/, "", v)
            print v
            next
        }
        inp && !/^[[:space:]]*-/ { inp = 0 }
    ' "$wf"
}

# Does the workflow fire on a pull request?
#
# Without this, `release.yml` — which triggers ONLY on a `v*` tag — made `mcp`
# read as covered, because it runs `npm test` and carries no `paths:` filter,
# so the "is it triggered?" test passed vacuously. Tests that run at publish
# time are not tests that gate a merge: by then the code is already on `main`.
# That verdict was the gate's own false green, found by disbelieving its first
# clean line rather than by a failure.
fires_on_pr() {
    grep -qE '^[[:space:]]{1,4}pull_request(_target)?:' "$1"
}

# Does `glob` cover `suite`? Prefix in EITHER direction:
#   'mcp/**' covers 'mcp/packages/gaming'          (glob above the suite)
#   'react-native/pkg/__tests__/**' covers 'react-native/pkg'  (glob inside it)
# The second direction matters: a filter listing a package's subdirectories
# still fires when that package changes, and refusing it would push authors to
# widen filters for the gate's benefit rather than the CI's.
glob_covers() {
    local glob="$1" suite="$2" prefix
    prefix="${glob%%\**}"
    prefix="${prefix%/}"
    [ -z "$prefix" ] && return 0
    case "$suite/" in "$prefix"/*) return 0 ;; esac
    case "$prefix/" in "$suite"/*) return 0 ;; esac
    [ "$prefix" = "$suite" ] && return 0
    return 1
}

INVOCATIONS="$(scan_invocations)"

# ── 4. Verdict per suite ─────────────────────────────────────────────────────
FAIL=0
N_OK=0; N_ADV=0; N_MISSING=0
echo "Test-suite reachability — JS/TS packages carrying *.test.* / *.spec.*"
echo ""

for suite in "${SUITES[@]:-}"; do
    [ -n "$suite" ] || continue
    best_state="MISSING"; best_detail="no workflow step runs its tests"
    while IFS=$'\t' read -r wf wd cmd adv; do
        [ -n "${wf:-}" ] || continue
        # Candidate targets: the step's working-directory, plus any token in
        # the command that names a real directory (`vitest --root mcp/pkg/x`,
        # `cd mcp-gateway && ...`). Relative tokens resolve against wd.
        targets="$wd"
        for tok in $cmd; do
            tok="${tok%\"}"; tok="${tok#\"}"; tok="${tok%\'}"; tok="${tok#\'}"
            case "$tok" in
                -*|*=*) continue ;;
            esac
            if [ -d "$tok" ]; then targets="$targets"$'\n'"$tok"
            elif [ -n "$wd" ] && [ -d "$wd/$tok" ]; then targets="$targets"$'\n'"$wd/$tok"
            fi
        done
        hit=0
        while IFS= read -r t; do
            t="${t#./}"; t="${t%/}"
            [ -n "$t" ] || continue
            [ "$t" = "$suite" ] && hit=1
        done <<< "$targets"
        [ "$hit" -eq 1 ] || continue

        # Invoked. Now: does this workflow fire when the suite changes?
        globs="$(trigger_prefixes "$WF_DIR/$wf")"
        if [ -z "$globs" ]; then
            triggered=1
        else
            triggered=0
            while IFS= read -r g; do
                [ -n "$g" ] || continue
                if glob_covers "$g" "$suite"; then triggered=1; break; fi
            done <<< "$globs"
        fi
        if [ "$triggered" -eq 0 ]; then
            if [ "$best_state" = "MISSING" ]; then
                best_detail="$wf runs its tests but never triggers on it"
            fi
            continue
        fi
        why=""
        [ "$adv" = "1" ] && why="cannot fail the build"
        if ! fires_on_pr "$WF_DIR/$wf"; then
            [ -n "$why" ] && why="$why; " ; why="${why}does not run on pull requests"
        fi
        if [ -n "$why" ]; then
            if [ "$best_state" != "OK" ]; then
                best_state="ADVISORY"; best_detail="$wf — $why"
            fi
        else
            best_state="OK"; best_detail="$wf"
        fi
    done <<< "$INVOCATIONS"

    case "$best_state" in
        OK)       printf '  %s✓%s %-42s %s\n' "$GREEN" "$NC" "$suite" "$best_detail"; N_OK=$((N_OK + 1)) ;;
        ADVISORY) printf '  %s⚠%s %-42s %s\n' "$YELLOW" "$NC" "$suite" "$best_detail"; N_ADV=$((N_ADV + 1)) ;;
        *)        printf '  %s✗%s %-42s %s\n' "$RED" "$NC" "$suite" "$best_detail"; N_MISSING=$((N_MISSING + 1)); FAIL=1 ;;
    esac
done

for orphan in "${ORPHANS[@]:-}"; do
    [ -n "$orphan" ] || continue
    printf '  %s✗%s %-42s %s\n' "$RED" "$NC" "$orphan" "test file owned by no package.json"
    N_MISSING=$((N_MISSING + 1)); FAIL=1
done

echo ""
echo "  ${#SUITES[@]} suite(s): $N_OK blocking, $N_ADV advisory, $N_MISSING unreachable"
echo "  Bounds: JS/TS only (Kotlin/Swift suites run under Gradle/Xcode and are"
echo "  not assessed here); dist/ and build/ copies excluded; a job-level"
echo "  continue-on-error is not detected, only a step-level one."

if [ "$FAIL" -ne 0 ]; then
    echo ""
    echo "${RED}check-test-suites-reachable: FAILED${NC} — a suite above runs nowhere in CI."
    echo "Add it to a workflow, or delete the tests. A suite that never runs is"
    echo "not neutral: it reads as coverage, and it can invert without a sound."
    exit 1
fi
echo ""
echo "${GREEN}check-test-suites-reachable: OK${NC}"
exit 0
