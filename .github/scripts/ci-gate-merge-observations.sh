#!/usr/bin/env bash
# ci-gate-merge-observations.sh — sticky observation ledger for `CI Gate`.
#
# WHY THIS EXISTS (#3018)
# ----------------------
# `ci-gate.yml`'s poll loop used to decide from a SINGLE instant's read of
# `GET /repos/{repo}/commits/{sha}/check-runs`. That read is not stable: while
# GitHub creates a new run attempt (`gh run rerun`, `Re-run failed jobs`), the
# whole affected check suite can be missing from one response and back in the
# next.
#
# On PR #3015 that window fell exactly on the gate's last poll. `Compile KMP
# core` had concluded `cancelled` (killed by its own `timeout-minutes`), a
# re-run of the CI run was requested, and the 18:09:33Z read returned only the
# `PR Review (agents)` suite. Every consequence of that partial read pushed the
# gate the same way — green:
#
#   - the 11 missing checks were no longer in `pending`, so the loop broke;
#   - they were no longer in `observed_names`, so the CORE-CHECK GUARD saw
#     zero core checks and disabled itself (that is its docs-only signature);
#   - they were no longer in `others`, so the `cancelled` never reached
#     `ci-gate-aggregate.sh`.
#
# The gate then aggregated ONE check run and printed
# `CI Gate passed — every CI check succeeded or was skipped.` — the single
# required check for branch protection, green over a job that carried no
# verdict at all. The one-line conclusions list in that log is not a display
# bug: the aggregation really had seen one check.
#
# THE RULE
# --------
# A check run that has been observed once for this SHA can never silently
# leave the set. It stays in the merged view as `status: vanished`, which the
# caller's `pending` selector treats as NOT completed — so the gate keeps
# waiting instead of concluding. Either the check comes back (the re-run
# registers, and its FRESH conclusion replaces the remembered one, which is
# what makes a legitimate `cancelled` → re-run → `success` still go green), or
# it never does and the gate times out red. Both outcomes are fail-closed.
#
# This is orthogonal to the #2492 latest-run-per-name collapse: that one picks
# between two check runs sharing a name WITHIN one read, this one carries names
# ACROSS reads. #2492's intent — never red-light the gate over a genuinely
# superseded `cancelled` — is untouched, because a superseded run and its
# replacement are both present in the same read and the collapse still resolves
# them before this script ever sees them.
#
# INPUT  (stdin): the current read, one compact JSON object per line, already
#                 filtered and collapsed by the caller:
#                 {"name":…, "status":…, "conclusion":…, "id":…}
# INPUT  (argv1): path to the ledger file — the previous invocation's stdout.
#                 May be missing or empty on the first iteration.
# OUTPUT (stdout): the merged view, one compact JSON object per line, sorted
#                  by name. The caller writes it back over the ledger file.
#
# MERGE SEMANTICS
#   - name present in the current read  → that record, verbatim. The live API
#     is always authoritative for a name it still reports, so a re-run that
#     turns `cancelled` into `success` is honoured on the next poll.
#   - name present only in the ledger   → the remembered record with
#     `status` rewritten to `vanished` and `conclusion` to `null`.
#     `vanished` is a sentinel this script invents; the Checks API never emits
#     it, so it cannot collide with a real status.
#
# Usage: … | bash .github/scripts/ci-gate-merge-observations.sh <ledger-file>

set -euo pipefail

ledger_file="${1:-}"

current=$(cat)
ledger=""
if [ -n "$ledger_file" ] && [ -f "$ledger_file" ]; then
  ledger=$(cat "$ledger_file")
fi

{
  printf '%s\n' "$ledger" | jq -c '. + {__src: "mem"}'
  printf '%s\n' "$current" | jq -c '. + {__src: "now"}'
} | jq -s -c '
    group_by(.name)
    | map(
        # MONOTONE IN `id`, not "live always wins".
        #
        # "The live read is authoritative for a name it still reports" is
        # false when a response drops the FRESH run while still listing the
        # SUPERSEDED one — the same instability #3018 is built on, and #2492
        # documents that both runs coexist at one head SHA in this repo.
        # Measured on the naive rule: a read carrying only the old
        # `completed/skipped` run overwrote a remembered `in_progress` fresh
        # run, `pending` emptied, and the gate passed green while the fresh
        # check had never concluded. The mirror case reddened a PR whose
        # checks had already been observed `success`.
        #
        # Check-run ids increase monotonically with creation — the caller
        # already relies on that for the #2492 collapse — so take the highest
        # id seen for this name from EITHER side. If it came from the live
        # read, use it; if the live read no longer carries it, it is vanished.
        #
        # The `.__src == "now"` sort key is the explicit tie-break: a re-run
        # REUSES its check-run id rather than creating a new one (measured on
        # #3015, id 92399415842 across both attempts), so `mem` and `now` can
        # hold the same id for the same name. On that tie the live record must
        # win, otherwise a check that came back would be reported vanished
        # forever. Written out rather than left to jq max_by/group_by ordering,
        # which happens to give the same answer but is not a documented
        # guarantee.
        (sort_by(.id, (if .__src == "now" then 1 else 0 end)) | last) as $win
        | (if $win.__src == "now" then
             $win
           else
             ($win | .status = "vanished" | .conclusion = null)
           end)
        | del(.__src)
      )
    | sort_by(.name)[]
  '
