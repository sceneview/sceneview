#!/usr/bin/env bash
# Every JS/TS package that carries tests must be reachable from a workflow.
#
# WHY THIS EXISTS
# ---------------
# `mcp-gateway/` held 17 test files and 187 passing cases that NO workflow ever
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
# * FOUR path segments are excluded, not two: `node_modules/`, `dist/`,
#   `build/` and `out/` — the `grep -vE` that builds `TEST_FILES`, named rather
#   than cited by line, because a pinned line number rots the first time
#   anything above it moves and then reads as authoritative. This repo
#   versions compiled `dist/` output
#   for three MCP packages, so their `*.test.js` copies would otherwise be
#   counted as separate suites that nothing runs — true, and useless; the other
#   three are the same case by convention. All four are named here because a
#   suite living under one of them leaves the report ENTIRELY — no MISSING, no
#   line at all — which is the silent-drop this gate exists to prevent. An
#   exclusion the footer does not name is indistinguishable from coverage.
# * A job-level `continue-on-error: true` is NOT detected, only a step-level
#   one; such a suite is reported OK when it is really advisory. Printed in the
#   footer rather than left for a reader to discover. (The step-level case is
#   order-independent: matches are buffered and graded at the end of the step,
#   so `continue-on-error:` written after its own `run:` still counts.)
# * `|| true` is matched anywhere on a `run:` line and is NOT anchored, unlike
#   `continue-on-error:`. It cannot be: `npm test || true` is the very shape the
#   check exists to catch, so there is no line position to anchor to. A `run: |`
#   block that merely PRINTS the string therefore marks its step advisory. The
#   direction is the safe one — OK becomes ADVISORY, never the reverse, so it
#   over-reports rather than passing a gap — and the cost is one spurious
#   warning, fixed by not echoing the string. Anchoring `continue-on-error:` was
#   worth it because that one IS a YAML key with a fixed position; this one is
#   not, and pretending otherwise would trade a visible bound for a hidden one.
# * A `paths:` filter naming only a SUBDIRECTORY of a package is accepted as
#   covering the package. That over-approximates triggering — a change to
#   `pkg/src/` would not fire a workflow filtered on `pkg/__tests__/**` — and it
#   is deliberate: refusing it would push authors to widen real filters for this
#   gate's benefit. It is the one accepted over-approximation here, so it is
#   named rather than buried.
# * A filter with no literal prefix (`**/*.ts`, `*.md`) is decided by matching
#   the pattern against the suite's own TEST files, not its sources. A filter
#   listing only source extensions — `**/*.tsx` over a suite whose tests are
#   `.ts` — is therefore reported as not covering it. That is fail-CLOSED, so
#   the failure is a spurious MISSING and never a missed gap, but it is a real
#   bound: the answer is "would a change to a test file fire this filter?", not
#   the wider "could this workflow ever run for this package?".
# * An invocation is seen only when the runner is named literally on a `run:`
#   line. A suite launched through a wrapper — `bash .claude/scripts/foo.sh`,
#   which runs vitest inside — is invisible, and the package would be reported
#   MISSING. That error points the safe way: fail-CLOSED, loud, and fixed by
#   naming the suite in an explicit step, which is what this gate wants anyway.
#   Following wrappers would mean interpreting shell. No JS/TS suite in this
#   repo is invoked through one today.
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
# Comment lines are skipped, and that is load-bearing rather than tidy. This
# repo has a header comment naming a runner — `mcp-ts-check.yml`'s "`npm test`
# (run by ci.yml's `Quality gate (full)` job)". Counting it as an invocation
# would let a SENTENCE satisfy the gate that exists to check whether the command
# runs. Whether the sentence happens to be true is beside the point: that one
# is (setup-mcp's `npm ci` makes the guard true), and it still must not count,
# because a prose claim and a `run:` line are not the same kind of evidence.
scan_invocations() {
    for wf in "$WF_DIR"/*.yml "$WF_DIR"/*.yaml; do
        [ -f "$wf" ] || continue
        awk -v wf="$(basename "$wf")" '
            # Matches are BUFFERED and emitted at the end of the step, not at
            # the line that matched. YAML mapping keys are unordered, so
            # `continue-on-error: true` and `working-directory:` are both legal
            # AFTER the `run:` that they govern. Deciding at match time read
            # those steps with whatever was known so far — an advisory step
            # reported as blocking, which is the direction that overstates
            # coverage. Buffering is what makes the verdict independent of key
            # order.
            function flush(   i, l) {
                for (i = 1; i <= np; i++) {
                    l = pend[i]
                    printf "%s\t%s\t%s\t%d\n", wf, wd, l,
                        (ce == 1 || l ~ /\|\|[[:space:]]*true/) ? 1 : 0
                }
                np = 0
            }
            # A new list item ends the previous step, so its working-directory
            # must not leak forward onto the next one.
            /^[[:space:]]*-[[:space:]]/ { flush(); wd=""; ce=0 }
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
                # Anchored to the start of the (indent-stripped) line so that shell
                # text inside a `run: |` block — `echo "continue-on-error: true"`
                # — is not read as the key. That direction only ever pushes
                # OK -> ADVISORY, so it could not hide a MISSING; anchoring is
                # one character and removes the noise anyway.
                if (line ~ /^-?[[:space:]]*continue-on-error:[[:space:]]*true/) ce = 1
                # The runner has to be a COMMAND WORD, not a substring. `vitest`
                # and `jest` occur inside ordinary filenames — `vitest.config.ts`,
                # `jest.setup.js` — so a bare substring match turns
                # `cat vitest.config.ts` or `rm -f jest.config.js` into an
                # invocation, and the package it happens to sit in reads as
                # covered by a step that runs no test at all. Fail-open, and the
                # cheapest kind to introduce by accident. The trailing
                # `([[:space:]]|$)` is what does the work: a filename continues
                # with `.`, a command does not.
                if (line ~ /(^|[^[:alnum:]._\/-])(vitest|jest)([[:space:]]|$)/ ||
                    line ~ /(^|[^[:alnum:]._\/-])npm[[:space:]]+(run[[:space:]]+)?test([[:space:]]|$)/ ||
                    line ~ /(^|[^[:alnum:]._\/-])playwright[[:space:]]+test([[:space:]]|$)/) {
                    pend[++np] = line
                }
            }
            END { flush() }
        ' "$wf"
    done
}

# ── 3. Extract the workflow's PULL-REQUEST trigger paths ─────────────────────
# A workflow whose `pull_request:` carries no `paths:` fires on every change:
# the list comes back empty, which the caller reads as `<none>` and every suite
# matches.
#
# ONLY the `pull_request:` trigger, and that scoping is the whole point.
# `paths:` is a per-trigger key. Collecting every `paths:` in the file merges
# `push.paths` into `pull_request.paths`, and a union is wider than either side:
# a workflow whose push filter covers a suite but whose pull_request filter
# excludes it would read as "triggered when this package changes" while never
# running on the pull request that gates the merge. That is the same
# runs-but-cannot-block failure the ADVISORY verdict exists to catch, one level
# down, inside this gate's own parser. No workflow here has divergent filters
# today — closed by reasoning and a fixture, not by a red run.
trigger_prefixes() {
    local wf="$1"
    awk '
        function ind(s) { match(s, /^ */); return RLENGTH }

        # A column-0 key ends the `on:` mapping — including `jobs:`, whose
        # `- name:` step lines would otherwise be read as path entries.
        /^[^ #]/ { on = ($0 ~ /^["\x27]?on["\x27]?:/); lvl = 0; trig = 0; inp = 0; next }
        !on { next }
        /^ *#/ { next }
        /^ *$/ { next }
        {
            # The first key under `on:` fixes the trigger indent level; only
            # keys at that exact level are triggers. `paths:` sits deeper, so it
            # can never be mistaken for one.
            i = ind($0)
            if (lvl == 0) lvl = i
            if (i == lvl && $0 ~ /^ *[A-Za-z_]+:/) {
                trig = ($0 ~ /^ *pull_request(_target)?:/) ? 1 : 0
                inp = 0
                next
            }
            if (!trig) next

            # `paths-ignore:` entries are emitted with a leading `!` and the
            # caller inverts them. Dropping them was fail-open: a trigger
            # carrying ONLY `paths-ignore` produced an empty list, and empty
            # means "no filter, fires on everything" — so a workflow that
            # explicitly excludes the suite path read as firing on it. The two
            # keys are mutually exclusive within one trigger (GitHub rejects
            # both), so the caller never has to merge a positive and a negative
            # list; it prefers the positive one if both somehow appear.
            #
            # Flow style — `paths: ["mcp/**", "x/**"]`. Handled for the same
            # reason: the block-sequence rule below never matches it, and the
            # empty list that results reads as no filter at all.
            if ($0 ~ /^ *paths(-ignore)?: *\[/) {
                neg = ($0 ~ /paths-ignore/) ? "!" : ""
                v = $0
                sub(/^[^[]*\[/, "", v)
                sub(/\].*$/, "", v)
                n = split(v, a, ",")
                for (k = 1; k <= n; k++) {
                    gsub(/["\x27]/, "", a[k])
                    gsub(/^ +| +$/, "", a[k])
                    if (a[k] != "") print neg a[k]
                }
                next
            }
            if ($0 ~ /^ *paths(-ignore)?: *$/) {
                inp = 1
                pneg = ($0 ~ /paths-ignore/) ? "!" : ""
                next
            }
            if (inp && $0 ~ /^ *- /) {
                v = $0
                sub(/^ *- */, "", v)
                gsub(/["\x27]/, "", v)
                sub(/ +.*$/, "", v)
                print pneg v
                next
            }
            if (inp && $0 !~ /^ *-/) { inp = 0; pneg = "" }
        }
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
# Scoped to the `on:` block, for the same reason `trigger_prefixes` is. This
# used to grep the WHOLE file for a `pull_request:` line at 1-4 spaces of
# indent, which a job KEY named `pull_request:` under `jobs:` satisfies — and
# that reads a tag-only workflow as gating merges. Fail-OPEN, in the one
# function whose answer decides whether a suite counts as blocking, so the
# scoping is not tidiness. It matches nothing in this repo today.
fires_on_pr() {
    awk '
        function ind(s) { match(s, /^ */); return RLENGTH }

        # A new top-level key ends the previous block. The inline-array form
        # lives on this same line: `on: [push, pull_request]`.
        /^[^ #]/ {
            on = ($0 ~ /^["\x27]?on["\x27]?:/)
            if (on && $0 ~ /:[[:space:]]*\[[^]]*pull_request/) found = 1
            lvl = 0
            next
        }
        !on { next }   # anything outside the `on:` block, `jobs:` included
        /^ *#/ { next }
        /^ *$/ { next }
        {
            i = ind($0)
            if (lvl == 0) lvl = i
            # Only at the trigger level: `pull_request:` nested deeper is a key
            # belonging to some other trigger, not a trigger itself.
            if (i == lvl && $0 ~ /^ *pull_request(_target)?:/) found = 1
        }
        END { exit(found ? 0 : 1) }
    ' "$1"
}

# Translate a GitHub path filter into an ERE. `*` never crosses `/` and `**`
# does; `**/` also matches ZERO directories, so `**/*.md` covers a root-level
# `a.md` — hence `(.*/)?` rather than `.*/`. Erring toward matching keeps the
# caller's empty-prefix branch from turning a real filter into a false MISSING.
glob_to_ere() {
    local g="$1" out="" i=0 c
    while [ "$i" -lt "${#g}" ]; do
        c="${g:i:1}"
        if [ "$c" = '*' ] && [ "${g:i+1:1}" = '*' ]; then
            if [ "${g:i+2:1}" = '/' ]; then out+="(.*/)?"; i=$((i + 3)); continue; fi
            out+=".*"; i=$((i + 2)); continue
        fi
        case "$c" in
            '*') out+="[^/]*" ;;
            '?') out+="[^/]" ;;
            '.'|'+'|'('|')'|'['|']'|'{'|'}'|'^'|'$'|'|'|'\\') out+="\\$c" ;;
            *) out+="$c" ;;
        esac
        i=$((i + 1))
    done
    printf '%s' "$out"
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
    # An EMPTY literal prefix means the glob starts with a wildcard, and the
    # prefix comparisons below have nothing to bite on. This used to return
    # "covers" for all of them, which is right for `**` and fail-OPEN for
    # `**/*.md`: `*` does not cross `/`, so a markdown-only filter never fires
    # on a JS package's `.test.ts` change — yet the suite read as gated by it.
    # Decide by matching the pattern against the suite's OWN test files: if a
    # change to one of them would not fire the filter, the filter does not
    # cover the suite. `**` still matches every one of them and still answers
    # yes, so the correct half of the old shortcut is preserved by measurement
    # rather than by assertion.
    if [ -z "$prefix" ]; then
        local re f
        re="^$(glob_to_ere "$glob")$"
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            if [ "$suite" != "." ]; then
                case "$f" in "$suite"/*) ;; *) continue ;; esac
            fi
            [[ "$f" =~ $re ]] && return 0
        done <<< "$TEST_FILES"
        return 1
    fi
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
        # `set -f` for the split: `$cmd` is unquoted for word-splitting, but
        # globbing is still on without it, so a `run:` line holding `*.test.ts`
        # or a bare `**` would be filename-expanded against the repo root and
        # inject directories nobody named. No workflow command contains such a
        # token today.
        set -f
        for tok in $cmd; do
            tok="${tok%\"}"; tok="${tok#\"}"; tok="${tok%\'}"; tok="${tok#\'}"
            case "$tok" in
                -*|*=*) continue ;;
            esac
            if [ -d "$tok" ]; then targets="$targets"$'\n'"$tok"
            elif [ -n "$wd" ] && [ -d "$wd/$tok" ]; then targets="$targets"$'\n'"$wd/$tok"
            fi
        done
        set +f
        hit=0
        while IFS= read -r t; do
            t="${t#./}"; t="${t%/}"
            [ -n "$t" ] || continue
            [ "$t" = "$suite" ] && hit=1
        done <<< "$targets"
        [ "$hit" -eq 1 ] || continue

        # Invoked. Now: does this workflow fire when the suite changes?
        # `paths:` entries arrive plain, `paths-ignore:` entries prefixed `!`.
        # GitHub rejects both keys on one trigger, so they never have to be
        # combined; if a file somehow carries both, the positive list wins,
        # which is the reading that can only UNDER-state triggering.
        globs="$(trigger_prefixes "$WF_DIR/$wf")"
        pos=""; neg=""
        while IFS= read -r g; do
            [ -n "$g" ] || continue
            case "$g" in "!"*) neg="$neg"$'\n'"${g#!}" ;; *) pos="$pos"$'\n'"$g" ;; esac
        done <<< "$globs"
        pos="${pos#$'\n'}"; neg="${neg#$'\n'}"
        if [ -n "$pos" ]; then
            triggered=0
            while IFS= read -r g; do
                [ -n "$g" ] || continue
                if glob_covers "$g" "$suite"; then triggered=1; break; fi
            done <<< "$pos"
        elif [ -n "$neg" ]; then
            # `paths-ignore` fires on everything EXCEPT what it names. Dropping
            # these entries produced an empty list, and empty reads as "no
            # filter at all" — so a trigger that explicitly excludes a suite's
            # path made it read as firing on that suite. Fail-open.
            triggered=1
            while IFS= read -r g; do
                [ -n "$g" ] || continue
                if glob_covers "$g" "$suite"; then triggered=0; break; fi
            done <<< "$neg"
        else
            triggered=1
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
echo "  not assessed here); node_modules/, dist/, build/ and out/ copies"
echo "  excluded; a job-level continue-on-error is not detected, only a"
echo "  step-level one. Full list in this script's BOUNDS footer."

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
