#!/usr/bin/env bash
# test-release-device-qa-gate.sh — self-test for the Device QA release gate's
# grading policy (#2433).
#
# Guards the CLAUDE.md "Release-gate policy for continue-on-error legs (#1651)"
# invariant against silent regression:
#   - web  is BLOCKING  -> a red web leg is a hard release-gate FAIL.
#   - android + ar are ADVISORY -> a red/skipped advisory leg is WARN only,
#     never a hard block (#1670/#2433). `ar` assumeTrue-SKIPs on CI when the
#     bundled recording / Play Services for AR is absent — an infra skip that
#     must read as WARN, not a crash.
#
# It re-derives the REQUIRED/ADVISORY default sets straight from
# release-device-qa-gate.sh (so the test tracks the source of truth) and
# replays the gate's section-5 grading loop + section-6 verdict against
# injected per-leg statuses. Bash 3.2-safe (macOS default shell — no
# associative arrays).
#
# Usage: bash .claude/scripts/test-release-device-qa-gate.sh
# Exit 0 = all assertions pass; exit 1 = a policy regression.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/release-device-qa-gate.sh"

[ -f "$GATE" ] || { echo "missing $GATE" >&2; exit 1; }

# Re-derive the graded sets from the gate's own defaults, so this test fails
# loudly if someone flips `ar` back to REQUIRED.
REQUIRED_LEGS="$(grep -E '^REQUIRED_LEGS=' "$GATE" | sed -E 's/.*:-([^}]*)\}.*/\1/')"
ADVISORY_LEGS="$(grep -E '^ADVISORY_LEGS=' "$GATE" | sed -E 's/.*:-([^}]*)\}.*/\1/')"
echo "gate defaults: REQUIRED=[$REQUIRED_LEGS] ADVISORY=[$ADVISORY_LEGS]"

# Assert the documented #1651 policy at the default level.
case ",$REQUIRED_LEGS," in *",web,"*) ;; *) echo "REGRESSION: web not in REQUIRED" >&2; exit 1 ;; esac
case ",$REQUIRED_LEGS," in *",ar,"*)  echo "REGRESSION: ar must NOT be REQUIRED (#2433)" >&2; exit 1 ;; esac
case ",$ADVISORY_LEGS," in *",ar,"*) ;; *) echo "REGRESSION: ar not in ADVISORY (#2433)" >&2; exit 1 ;; esac
case ",$ADVISORY_LEGS," in *",android,"*) ;; *) echo "REGRESSION: android not in ADVISORY" >&2; exit 1 ;; esac

# stat_of <leg> — read a status out of the comma-list scenario in $SCN.
stat_of() {
  local pair k v
  for pair in ${SCN//,/ }; do
    k="${pair%%=*}"; v="${pair#*=}"
    [ "$k" = "$1" ] && { echo "$v"; return; }
  done
  echo "missing"
}

# grade — a faithful mirror of release-device-qa-gate.sh section 5 + 6.
grade() {
  local REQUIRED_FAIL=0 ADVISORY_WARN=0 leg st
  for leg in ${REQUIRED_LEGS//,/ }; do
    st="$(stat_of "$leg")"
    case "$st" in
      passed) ;;
      failed) REQUIRED_FAIL=$((REQUIRED_FAIL + 1)) ;;
      skipped|missing|*) ADVISORY_WARN=$((ADVISORY_WARN + 1)) ;;  # no-verdict required leg -> advisory (#1683)
    esac
  done
  for leg in ${ADVISORY_LEGS//,/ }; do
    st="$(stat_of "$leg")"
    case "$st" in
      passed) ;;
      *) ADVISORY_WARN=$((ADVISORY_WARN + 1)) ;;
    esac
  done
  if   [ "$REQUIRED_FAIL" -gt 0 ]; then echo "FAIL"; return 1
  elif [ "$ADVISORY_WARN" -gt 0 ]; then echo "PASS-WITH-WARNINGS"; return 0
  else echo "PASS"; return 0; fi
}

fail=0
expect() { # $1=expected $2=scenario $3=label
  local got
  SCN="$2"; got="$(grade)" || true
  if [ "$got" = "$1" ]; then
    echo "  ok  : $3 -> $got"
  else
    echo "  FAIL: $3 -> got '$got', expected '$1'"; fail=1
  fi
}

echo "--- Grading scenarios ---"
expect "PASS-WITH-WARNINGS" "web=passed,ar=failed,android=passed"  "red ar leg (the #2433 bug) is WARN, not FAIL"
expect "FAIL"               "web=failed,ar=passed,android=passed"  "red web leg still hard-blocks the release"
expect "PASS"               "web=passed,ar=passed,android=passed"  "all legs green"
expect "PASS-WITH-WARNINGS" "web=passed,ar=skipped,android=failed" "ar assumeTrue-SKIP + android fail = advisory WARN"
expect "PASS-WITH-WARNINGS" "web=skipped,ar=passed,android=passed" "web no-verdict = advisory, not a block (#1683)"

if [ "$fail" -eq 0 ]; then
  echo "ALL SELFTESTS PASSED"
else
  echo "SELFTEST FAILURES" >&2
  exit 1
fi
