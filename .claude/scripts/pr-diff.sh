#!/usr/bin/env bash
# Compute the diff the PR reviewers read — the PR's diff, or none at all.
#
# WHY THIS IS A SCRIPT AND NOT FOUR LINES INLINE IN `pr-review.yml`
#   It used to be four lines inline, and those four lines shipped the exact
#   defect the reviewers exist to catch: a confident verdict about data that
#   did not establish it.
#
#   Measured 2026-08-10, run 31370116851 on PR #3073. The job checks out at
#   `fetch-depth: 0`, but its own self-modification step then ran
#   `git fetch origin main --depth=1`, which grafts a `.git/shallow` onto that
#   complete clone and truncates `origin/main`'s ancestry. `origin/main...HEAD`
#   stopped resolving, and the old code fell back to the TWO-dot form —
#   `git diff origin/main HEAD` — which also shows everything the BASE gained
#   since the branch point, reversed. `717b352cd` (#3063) had landed on main
#   in that window, so four reviewers read its revert as this PR's work: 2162
#   diff lines instead of 797, two "confirmed errors" naming
#   `flutter/…/README.md` and `react-native/…/README.md`, and a blocking
#   DO_NOT_MERGE on a PR that touches neither. The fallback was written as a
#   safety net; what it actually did was convert "I cannot compute the diff"
#   into "the author broke two READMEs".
#
# THE TWO RULES THIS KEEPS
#   1. A shallow graft is REPAIRED (`--unshallow`, then `--deepen`), never
#      worked around. No `--depth` fetch belongs in that job at all — see the
#      wiring assertion in `test-pr-diff.sh`.
#   2. If the merge base still cannot be resolved, the review does NOT happen.
#      A diff that is not the PR's diff is worse than no diff: no diff is an
#      incomplete review, the wrong diff is a wrong answer with four reviewers'
#      authority behind it. Exiting non-zero leaves the grader's fail-closed
#      REVIEW_INCOMPLETE standing, which is the honest verdict.
#
#   Same lesson as #3065, one layer up: classify the failure before naming it.
#
# Usage: pr-diff.sh --base <branch> --out <file>
# Exit:  0 wrote the diff (and printed `base_sha=<sha>`)
#        2 bad usage
#        3 the base ref or the merge base could not be resolved — NO review
#        4 the diff is empty
set -uo pipefail

BASE=""
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --base) BASE="${2:-}"; shift 2 ;;
        --out)  OUT="${2:-}";  shift 2 ;;
        *) echo "pr-diff: unknown argument '$1'" >&2; exit 2 ;;
    esac
done
if [ -z "$BASE" ] || [ -z "$OUT" ]; then
    echo "usage: pr-diff.sh --base <branch> --out <file>" >&2
    exit 2
fi

# `actions/checkout` only sets up the remote-tracking ref for the branch it
# checked out, so `origin/$BASE` is not guaranteed to exist even at
# fetch-depth 0. Fetch it — with no `--depth`, which is the whole point.
git fetch origin "$BASE" --quiet 2>/dev/null || true

if ! git rev-parse --verify --quiet "origin/$BASE" >/dev/null 2>&1; then
    echo "::error title=Base ref unresolved::Could not resolve origin/${BASE}, so the PR diff cannot be computed. No review is possible — this is a CI problem, not a finding about the PR."
    exit 3
fi

if ! git merge-base "origin/$BASE" HEAD >/dev/null 2>&1; then
    echo "::warning title=Truncated history::origin/${BASE} and HEAD have no visible merge base — the clone is shallow. Deepening it; the reviewers must read a three-dot diff or none."
    # `--unshallow` is an error on a complete repository, and `--deepen` is an
    # error on a shallow one older git refuses to deepen; try both, guard both,
    # and let the merge-base re-check below be what decides.
    git fetch --unshallow origin "$BASE" --quiet 2>/dev/null \
        || git fetch --deepen=1000 origin "$BASE" --quiet 2>/dev/null \
        || true
fi

if ! git merge-base "origin/$BASE" HEAD >/dev/null 2>&1; then
    echo "::error title=No merge base::origin/${BASE}...HEAD still does not resolve after deepening, so what this PR changed cannot be determined. Refusing to hand the reviewers a two-dot diff: it would show everything the base gained since the branch point, reversed, and they would report it as this PR's work (measured on run 31370116851). This is a CI problem, not a finding about the PR."
    exit 3
fi

BASE_SHA="$(git rev-parse "origin/$BASE")"

if ! git diff "origin/$BASE...HEAD" > "$OUT" 2>/dev/null; then
    echo "::error title=Diff failed::git diff origin/${BASE}...HEAD failed despite a resolvable merge base. No review is possible — this is a CI problem, not a finding about the PR."
    exit 3
fi

# An empty diff is a FALSE GREEN: four reviewers read nothing, find nothing,
# and the PR collects a clean verdict it never earned. Refuse loudly.
if [ ! -s "$OUT" ]; then
    echo "::error title=Nothing to review::The diff against origin/${BASE} is empty, and a review of an empty diff would report a PASS the PR never earned. If this is a dispatch on an already-merged PR, that is expected — read the merge commit instead."
    exit 4
fi

# The caller pins this SHA for the end-of-job clean-tree assertion; see
# `pr-review.yml` for why it must not re-resolve the branch after the fan-out.
echo "base_sha=$BASE_SHA"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "base_sha=$BASE_SHA" >> "$GITHUB_OUTPUT"
fi
echo "Diff for the reviewers: $(wc -l < "$OUT") lines, $(wc -c < "$OUT") bytes."
