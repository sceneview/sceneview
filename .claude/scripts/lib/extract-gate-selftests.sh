#!/usr/bin/env bash
#
# extract-gate-selftests.sh — list the gate self-test commands a workflow job
# runs, as a validated allowlist.
#
# `pre-push-check.sh` leg 19 runs the repo-hygiene gate self-tests. It DERIVES
# that list from `.github/workflows/ci.yml` instead of carrying a copy, because
# a copy goes stale the first time repo-hygiene gains a self-test — the #2988
# shape the leg exists to close.
#
# Deriving it means EXECUTING strings scraped out of a YAML file, and the only
# thing that made that safe was the character class inside a `grep -oE` buried
# in the middle of a 750-line script. That is a real property, but an implicit
# one: loosen the class by one character in a hurry and the injectability comes
# back with nothing to notice it. Reviewed on #3105.
#
# So the property is stated once, here, as an explicit gate on the OUTPUT:
#
#   every emitted line matches ^(bash|python3) <plain repo-relative path>$
#
# and `test-extract-gate-selftests.sh` pins it with hostile fixtures. Anything
# that does not match is dropped and REPORTED on stderr — never dropped
# silently, which would turn "I refused to run this" into "there was nothing
# to run".
#
# Usage:
#   bash .claude/scripts/lib/extract-gate-selftests.sh <workflow.yml> [job-name]
#   bash .claude/scripts/lib/extract-gate-selftests.sh --count-steps <workflow.yml> [job-name]
#
# `--count-steps` prints how many steps in the job NAME themselves a self-test.
# It exists so the caller can cross-check the scrape against a signal the
# scrape does not share: a fixed floor only catches a total collapse, and a
# regex that degrades from 29 matches to 21 sails past `>= 20` while eight
# self-tests quietly stop running (raised on #3105). The two counts are not
# equal by construction — a couple of self-tests run inside steps named after
# what they guard — so the contract is `discovered >= named`, and both checks
# are needed: if the job window itself stops matching, BOTH counts fall to
# zero together and only the absolute floor notices.
#
# stdout: one `<interpreter> <script>` command per line, sorted, deduplicated.
# stderr: one `REJECT: <line>` per scraped command that failed validation.
# Exit code: 0 always if the workflow file is readable — an empty list is a
# verdict the CALLER must judge (leg 19 has a floor for exactly that), not an
# error this script can decide on its own.

set -u

COUNT_STEPS=0
if [ "${1:-}" = "--count-steps" ]; then
    COUNT_STEPS=1
    shift
fi

WORKFLOW="${1:-}"
JOB="${2:-repo-hygiene}"

if [ -z "$WORKFLOW" ] || [ ! -f "$WORKFLOW" ]; then
    echo "usage: extract-gate-selftests.sh [--count-steps] <workflow.yml> [job-name]" >&2
    exit 64
fi

job_window() {
    awk -v job="  $JOB:" '
        $0 == job {f = 1; next}
        /^  [a-z][a-z0-9_-]*:[[:space:]]*$/ {f = 0}
        f
    ' "$WORKFLOW"
}

if [ "$COUNT_STEPS" -eq 1 ]; then
    job_window | grep -cE '^[[:space:]]+- name:.*[Ss]elf-test' || true
    exit 0
fi

# A YAML comment is prose, not a step: `sed` strips it before the scrape so a
# commented-out or merely-mentioned command is never executed. The awk window
# closes on the next top-level job key, so only the requested job is scraped.
job_window \
    | sed 's/[[:space:]]#.*$//' \
    | grep -oE '(bash|python3) ([a-zA-Z0-9_.][a-zA-Z0-9_./-]*)?test-[a-zA-Z0-9_.-]+\.(sh|py)' \
    | sort -u \
    | while IFS= read -r cmd; do
        # The explicit statement of the property. Deliberately NOT the same
        # expression as the scrape above: this one anchors both ends, so a
        # scrape that starts matching mid-token, or admits a metacharacter,
        # fails here even though it "matched" up there.
        if printf '%s' "$cmd" | grep -qE '^(bash|python3) [A-Za-z0-9_.][A-Za-z0-9_./-]*$'; then
            case "$cmd" in
                # A path that climbs out of the tree, or an absolute one, is
                # syntactically plain and still not this repo's self-test.
                *..*|*\ /*) echo "REJECT: $cmd" >&2 ;;
                *) echo "$cmd" ;;
            esac
        else
            echo "REJECT: $cmd" >&2
        fi
    done
