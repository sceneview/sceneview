#!/usr/bin/env bash
# ci-gate-aggregate.sh — pure aggregation core for the `CI Gate` workflow.
#
# Given the set of *completed* non-self check runs for a PR head SHA, decide
# whether the gate passes or fails. This is the exact failure-decision logic
# used by `.github/workflows/ci-gate.yml`'s "Aggregate all PR check
# conclusions" step, factored out so it can be unit-tested in isolation
# (see test-ci-gate-aggregation.sh).
#
# WHY A SEPARATE SCRIPT
# --------------------
# Issue #1984: an advisory check (`Coverage (advisory)`) that was
# concurrency-CANCELLED red-lighted the gate, blocking an otherwise-mergeable
# PR (#1889). Advisory checks must NEVER affect the verdict — for ANY
# conclusion, including CANCELLED / SKIPPED / FAILURE. That rule needs a
# regression test, and a test needs a callable, side-effect-free function.
#
# INPUT  (stdin): one check run per line, `<conclusion>\t<name>`.
#   conclusion ∈ success | failure | cancelled | timed_out | skipped |
#                neutral | stale | action_required | "" (still running)
# INPUT  (env):  ADVISORY_CHECKS — newline-separated substrings; any check
#                whose name CONTAINS one of these is advisory.
# OUTPUT (stdout): the names of the checks that fail the gate, one per line
#                  (empty when the gate passes).
# EXIT:  0 if the gate passes (no real failures), 1 if it fails.
#
# DECISION RULES (kept identical to ci-gate.yml):
#   - `failure` / `cancelled` / `timed_out` → candidate failures.
#   - `success` / `skipped` / `neutral` / `stale` / `action_required` → pass.
#   - A candidate failure whose name CONTAINS an ADVISORY_CHECKS substring is
#     dropped from the verdict regardless of its conclusion (#1984).

set -euo pipefail

advisory_checks=$(printf '%s\n' "${ADVISORY_CHECKS:-}" | sed '/^[[:space:]]*$/d')

# Collect candidate failures: conclusion in the red set.
failed=""
while IFS=$'\t' read -r conclusion name; do
  [ -z "${name:-}" ] && continue
  case "$conclusion" in
    failure | cancelled | timed_out)
      failed="${failed}${name}"$'\n'
      ;;
  esac
done

# ADVISORY EXCLUSION (#1984): drop any candidate whose name contains an
# advisory substring — for EVERY conclusion.
if [ -n "$advisory_checks" ] && [ -n "$failed" ]; then
  filtered=""
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    is_advisory=false
    while IFS= read -r adv; do
      [ -z "$adv" ] && continue
      case "$name" in
        *"$adv"*) is_advisory=true; break ;;
      esac
    done <<< "$advisory_checks"
    if [ "$is_advisory" = true ]; then
      echo "Ignoring advisory check (does not gate): $name" >&2
    else
      filtered="${filtered}${name}"$'\n'
    fi
  done <<< "$failed"
  failed=$(printf '%s\n' "$filtered" | sed '/^[[:space:]]*$/d')
fi

failed=$(printf '%s\n' "$failed" | sed '/^[[:space:]]*$/d' | sort -u)

if [ -n "$failed" ]; then
  printf '%s\n' "$failed"
  exit 1
fi

exit 0
