#!/usr/bin/env bash
# test-ci-gate-aggregation.sh — regression tests for ci-gate-aggregate.sh
#
# Focus: an ADVISORY check must NEVER affect the `CI Gate` verdict, for ANY
# conclusion — including the CANCELLED case from issue #1984 (PR #1889 sat
# blocked ~3h because a concurrency-cancelled `Coverage (advisory)` check
# red-lighted the gate).
#
# Usage: bash .github/scripts/test-ci-gate-aggregation.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGG="$SCRIPT_DIR/ci-gate-aggregate.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

FAILED=0
pass() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail() { echo -e "  ${RED}FAIL${NC}  $1"; FAILED=$((FAILED + 1)); }

# run_case <name> <expected-exit> <ADVISORY_CHECKS> <stdin-payload>
run_case() {
  local name="$1" expected="$2" advisory="$3" payload="$4"
  local out actual
  set +e
  out=$(ADVISORY_CHECKS="$advisory" bash "$AGG" <<< "$payload" 2>/dev/null)
  actual=$?
  set -e
  if [ "$actual" -eq "$expected" ]; then
    pass "$name (exit $actual)"
  else
    fail "$name — expected exit $expected, got $actual; failures=[$out]"
  fi
}

echo "== CI Gate aggregation — advisory exclusion (#1984) =="

# 1. The exact #1984 scenario: every blocking check green, the ONLY
#    non-success check is the advisory `Coverage (advisory)` CANCELLED.
#    The gate MUST pass.
run_case "CANCELLED advisory check does not fail the gate" 0 "Coverage" \
"$(printf 'success\tUnit tests\nsuccess\tQuality gate (full)\ncancelled\tCoverage (advisory)\n')"

# 2. Advisory check FAILED — still must not fail the gate.
run_case "FAILED advisory check does not fail the gate" 0 "Coverage" \
"$(printf 'success\tUnit tests\nfailure\tCoverage (advisory)\n')"

# 3. Advisory check SKIPPED — must not fail the gate (and never did, but
#    pin it so the exclusion stays conclusion-agnostic).
run_case "SKIPPED advisory check does not fail the gate" 0 "Coverage" \
"$(printf 'success\tUnit tests\nskipped\tCoverage (advisory)\n')"

# 4. A real BLOCKING check that fails MUST still fail the gate, even when
#    an advisory check is also cancelled alongside it.
run_case "blocking failure still fails the gate (advisory also cancelled)" 1 "Coverage" \
"$(printf 'failure\tUnit tests\ncancelled\tCoverage (advisory)\n')"

# 5. A blocking check that is CANCELLED still fails the gate.
run_case "blocking CANCELLED still fails the gate" 1 "Coverage" \
"$(printf 'cancelled\tUnit tests\nsuccess\tQuality gate (full)\n')"

# 6. All green, no advisory list — passes.
run_case "all checks green passes" 0 "" \
"$(printf 'success\tUnit tests\nsuccess\tBuild\n')"

# 7. skipped / neutral / stale on blocking checks all pass.
run_case "skipped + neutral + stale conclusions pass" 0 "Coverage" \
"$(printf 'skipped\tBuild\nneutral\tLint\nstale\tUnit tests\n')"

# 8. timed_out advisory excluded; timed_out blocking fails.
run_case "timed_out advisory excluded" 0 "Coverage" \
"$(printf 'success\tUnit tests\ntimed_out\tCoverage (advisory)\n')"
run_case "timed_out blocking fails" 1 "Coverage" \
"$(printf 'timed_out\tUnit tests\n')"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo -e "${RED}$FAILED test(s) failed${NC}"
  exit 1
fi
echo -e "${GREEN}All CI Gate aggregation tests passed${NC}"
