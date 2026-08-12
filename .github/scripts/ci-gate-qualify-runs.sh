#!/usr/bin/env bash
# ci-gate-qualify-runs.sh — turn one raw Checks API read into the gate's
# `others` set: hostile names neutralised, self/Device-QA runs dropped, every
# record attributed to the WORKFLOW FILE that produced it, and the #2492
# latest-run-per-name collapse applied within that workflow.
#
# WHY THE WORKFLOW ATTRIBUTION EXISTS (#3033)
# -------------------------------------------
# The #2492 collapse used to resolve check runs BY NAME ALONE:
#
#     jq -s -c 'group_by(.name) | map(max_by(.id))[]'
#
# A `pull_request` event builds its workflows from the merge ref, so a PR —
# including one from a fork — can add a workflow whose job carries any `name:`
# it likes. Measured against the real aggregation chain: a second check run
# named exactly `Unit tests`, concluding `success` with a higher check-run id
# than the genuine `Unit tests` `failure`, made `max_by(.id)` keep the fork's
# record and drop the real one. The failure never reached
# `ci-gate-aggregate.sh` and the verdict flipped GREEN. `CI Gate` is the only
# check branch protection on `main` requires, so that is a bypass of the
# repo's merge gate, not a display problem.
#
# The fix is NOT "collapse by check-suite id". #2492 exists because
# `cancel-in-progress` cancels an in-flight `ci.yml` run and starts a FRESH one
# at the same head SHA — and measured on this repo, each workflow run gets its
# OWN check suite (one `check_suite_id` per `workflow_run`). Keying the
# collapse on the suite would therefore stop collapsing the superseded
# `cancelled`, red-lighting the gate on pure concurrency noise: #2492 verbatim,
# which ping-ponged PR #2467 three times in one session.
#
# What actually separates "the same workflow re-run" from "a different workflow
# claiming the same name" is the WORKFLOW FILE PATH, and
# `GET /repos/{repo}/actions/runs?head_sha={sha}` publishes exactly that
# mapping. Measured on this repo at 88b6676916 (3 of the 5 runs at that SHA;
# every run carried a distinct `check_suite_id`):
#
#   check_suite_id  path
#   85717138330     .github/workflows/ci.yml
#   85717138531     .github/workflows/docs.yml
#   85717306255     .github/workflows/device-qa.yml
#
# So: collapse by `[workflow path, name]`.
#   - #2492's two `Detect changed paths` runs share `.github/workflows/ci.yml`
#     → still collapsed, superseded `cancelled` still discarded. Unchanged.
#   - #3033's fork job lives in a DIFFERENT workflow file → the two records no
#     longer collapse, both survive, and the genuine `failure` reaches
#     `ci-gate-aggregate.sh`. The gate goes red.
#
# ⛔ DEGRADATION IS TO TODAY'S BEHAVIOUR, NOT TO A SILENT PASS. If the suite
# map is missing, stale or empty — the runs API failed, or the check run comes
# from a non-Actions app that has no workflow file — the record falls back to
# `app:<app id>`, so every Actions check run shares one qualifier and the
# collapse degrades EXACTLY to the name-only behaviour it had before. That is
# the pre-#3033 state, never anything looser. The caller keeps the last
# successful map on disk so a single failed read does not reopen the hole.
#
# NEWLINE-NORMALISE THE NAME, ONCE, HERE (#3023)
# ----------------------------------------------
# A check-run name is attacker-influenceable: a fork PR can add a workflow whose
# job `name:` contains `\n`. Every downstream use treats names as
# newline-separated records — `observed_names` (core-check guard), the
# `pending`/`vanished` lists, and the conclusions line that prints straight to
# the Actions log. A name carrying a newline therefore splits into several fake
# records: measured, `Evil\n::error title=CI Gate::forged` reaches column 0 of
# the log and the runner parses it as a workflow command. Normalising at the
# single point where records are built closes every one of those sinks at once.
# Legitimate job names never contain newlines, so this is a no-op for real
# checks.
#
# Normalisation runs BEFORE the collapse, so `Unit\ntests` and `Unit tests`
# become the same name. Before #3033 that let a fork-added `Unit\ntests` with a
# higher id displace the real `Unit tests`; now the two live in different
# workflow files and no longer collapse, so the sink is closed rather than
# merely narrowed.
#
# INPUT  (argv1): suite-map file, `<check_suite_id>\t<workflow path>` per line.
#                 May be missing or empty — see the degradation note above.
# INPUT  (env SELF_CHECK): this gate's own check-run name, dropped from the set
#                 so the gate never waits on itself.
# INPUT  (stdin): the raw read, one compact JSON object per line:
#                 {"name":…,"status":…,"conclusion":…,"id":…,"suite":…,"app":…}
# OUTPUT (stdout): the `others` set, one compact JSON object per line, carrying
#                 `name`/`status`/`conclusion`/`id`/`workflow`.
#
# Usage: … | SELF_CHECK='CI Gate' bash .github/scripts/ci-gate-qualify-runs.sh <suite-map>

set -euo pipefail

suite_map_file="${1:-}"
self_check="${SELF_CHECK:-}"

# `<suite id>\t<path>` lines → a JSON object. `jq -R -s` so a malformed or
# empty file yields `{}` rather than an error: a broken map must degrade to the
# pre-#3033 collapse, never abort the gate.
suite_map='{}'
if [ -n "$suite_map_file" ] && [ -f "$suite_map_file" ]; then
  suite_map=$(jq -R -s '
      split("\n")
      | map(select(length > 0) | split("\t"))
      | map(select(length == 2 and (.[0] | length) > 0))
      | map({(.[0]): .[1]})
      | add // {}
    ' < "$suite_map_file")
fi

jq -c --arg self "$self_check" --argjson map "$suite_map" '
    .name |= gsub("[\r\n]"; " ")
    | select((.name != $self) and (.name | startswith("Device QA") | not))
    # Attribution, most specific first: the workflow file that owns this check
    # suite, else the app that published it (see the degradation note).
    | .workflow = ($map[(.suite // "" | tostring)] // ("app:" + ((.app // "unknown") | tostring)))
    | del(.suite, .app)
  ' \
  | jq -s -c '
    # LATEST-RUN-PER-NAME COLLAPSE (#2492), qualified by workflow file (#3033).
    # Check-run ids increase monotonically with creation, so the highest id for
    # a `[workflow, name]` pair is the current run of that pair.
    group_by([.workflow, .name]) | map(max_by(.id))[]
  '
