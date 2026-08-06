#!/usr/bin/env bash
# test-ci-gate-observations.sh — regression tests for the sticky observation
# ledger that `CI Gate` folds every Checks API read through (#3018).
#
# The defect being pinned: on PR #3015 the gate's final poll landed inside the
# window where GitHub was rebuilding a run attempt, so 11 of 12 check runs were
# missing from that one response. Because every decision was taken from that
# single read, the gate broke out of its wait loop, disarmed its own core-check
# guard, aggregated ONE check run, and printed
# `CI Gate passed — every CI check succeeded or was skipped.` over a
# `Compile KMP core` that had concluded `cancelled`.
#
# The ledger's contract, in one line: a check observed once for this SHA can
# never leave the gated set by disappearing — only by coming back with a fresh
# conclusion.
#
# Usage: bash .github/scripts/test-ci-gate-observations.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MERGE="$SCRIPT_DIR/ci-gate-merge-observations.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

FAILED=0
pass() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail() { echo -e "  ${RED}FAIL${NC}  $1"; FAILED=$((FAILED + 1)); }

# assert_eq <name> <expected> <actual>
assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    pass "$name"
  else
    fail "$name"
    echo "        expected: [$expected]"
    echo "        actual:   [$actual]"
  fi
}

run() { # run <ledger-file> <payload-lines…>
  local ledger="$1"; shift
  printf '%s\n' "$@" | bash "$MERGE" "$ledger"
}

LEDGER=$(mktemp)
trap 'rm -f "$LEDGER"' EXIT

# The `pending` selector applied to the merged view. It is EXTRACTED from
# ci-gate.yml rather than copied, so this suite cannot keep passing against a
# selector the workflow no longer uses — a copy would drift silently, and the
# whole point of #3018 is that `vanished` must land in `pending`.
GATE_YML="$(cd "$SCRIPT_DIR/../.." && pwd)/.github/workflows/ci-gate.yml"
PENDING_JQ=$(awk '
  /pending=\$\(echo "\$others" \| jq -r/ { getline; sub(/^[[:space:]]*/, ""); sub(/\)$/, ""); print; exit }
' "$GATE_YML" | sed "s/^'//; s/'$//")
if [ -z "$PENDING_JQ" ]; then
  echo "  ${RED}FAIL${NC}  could not extract the pending selector from $GATE_YML"
  exit 1
fi
echo "  using pending selector from ci-gate.yml: $PENDING_JQ"
pending_of() { jq -r "$PENDING_JQ"; }

# Compact, order-independent rendering of a merged view: `name=status/conclusion`
# per line, sorted. Comparing raw JSON lines would tie the assertions to key
# order and to where a renamed check happens to sort.
summarise() { jq -r '"\(.name)=\(.status)/\(.conclusion // "null")"' | sort; }

echo "== CI Gate observation ledger (#3018) =="

# ---------------------------------------------------------------------------
# 1. First read with an empty ledger is a pass-through.
# ---------------------------------------------------------------------------
: > "$LEDGER"
out=$(run "$LEDGER" \
  '{"name":"Lint","status":"in_progress","conclusion":null,"id":2}' \
  '{"name":"Unit tests","status":"completed","conclusion":"success","id":1}')
assert_eq "first read passes through unchanged (sorted by name)" \
"$(printf '%s\n' \
  '{"name":"Lint","status":"in_progress","conclusion":null,"id":2}' \
  '{"name":"Unit tests","status":"completed","conclusion":"success","id":1}')" \
"$out"

# ---------------------------------------------------------------------------
# 2. THE #3015 CASE. A check concludes `cancelled`, then the whole suite drops
#    out of the next read (run attempt being rebuilt). It must NOT leave the
#    set, and it must count as pending so the loop cannot break on it.
# ---------------------------------------------------------------------------
# The core check used below is read out of ci-gate.yml's REQUIRED_CHECKS, so
# this case keeps testing a name the CORE-CHECK GUARD actually enforces even
# if that list is edited.
CORE_CHECK=$(awk '
  /^ *REQUIRED_CHECKS: \|/ { grab = 1; next }
  grab { sub(/^[[:space:]]*/, ""); if ($0 == "" || $0 ~ /^#/) exit; print; exit }
' "$GATE_YML")
if [ -z "$CORE_CHECK" ]; then
  echo -e "  ${RED}FAIL${NC}  could not extract REQUIRED_CHECKS from $GATE_YML"
  exit 1
fi
echo "  using core check from ci-gate.yml REQUIRED_CHECKS: $CORE_CHECK"

: > "$LEDGER"
run "$LEDGER" \
  '{"name":"Compile KMP core","status":"completed","conclusion":"cancelled","id":1}' \
  "{\"name\":\"$CORE_CHECK\",\"status\":\"completed\",\"conclusion\":\"success\",\"id\":2}" \
  '{"name":"Agent review","status":"completed","conclusion":"failure","id":9}' > "$LEDGER".n
mv "$LEDGER".n "$LEDGER"

merged=$(run "$LEDGER" '{"name":"Agent review","status":"completed","conclusion":"failure","id":9}')

assert_eq "a vanished check is retained, marked 'vanished'" \
"$(printf '%s\n' \
  'Agent review=completed/failure' \
  'Compile KMP core=vanished/null' \
  "$CORE_CHECK=vanished/null" | sort)" \
"$(printf '%s\n' "$merged" | summarise)"

assert_eq "a vanished check is PENDING, so the wait loop cannot break" \
"$(printf '%s\n' 'Compile KMP core' "$CORE_CHECK" | sort)" \
"$(printf '%s\n' "$merged" | pending_of | sort)"

# The CORE-CHECK GUARD reads `observed_names` off this same merged view and
# disarms itself when it sees ZERO core checks (`any_core_seen != true` →
# `missing_core=""`, ci-gate.yml:351) — the docs-only signature, and one of
# the three things that let #3015 through. A vanished core check must stay
# visible so the guard stays armed.
assert_eq "a vanished CORE check stays in observed_names (guard stays armed)" \
"$CORE_CHECK" \
"$(printf '%s\n' "$merged" | jq -r '.name' | grep -xF "$CORE_CHECK" || true)"

# ---------------------------------------------------------------------------
# 3. The check comes back from the re-run: its FRESH record wins. This is what
#    keeps a legitimate cancelled → re-run → success PR green.
# ---------------------------------------------------------------------------
printf '%s\n' "$merged" > "$LEDGER"
back=$(run "$LEDGER" \
  '{"name":"Agent review","status":"completed","conclusion":"failure","id":9}' \
  '{"name":"Compile KMP core","status":"completed","conclusion":"success","id":1}' \
  "{\"name\":\"$CORE_CHECK\",\"status\":\"completed\",\"conclusion\":\"success\",\"id\":2}")
assert_eq "a returning check replaces its remembered conclusion" \
"$(printf '%s\n' \
  'Agent review=completed/failure' \
  'Compile KMP core=completed/success' \
  "$CORE_CHECK=completed/success" | sort)" \
"$(printf '%s\n' "$back" | summarise)"
assert_eq "nothing pending once every check is back and completed" \
"" "$(printf '%s\n' "$back" | pending_of)"

# ---------------------------------------------------------------------------
# 4. An UNSUPERSEDED cancelled that stays visible must reach the aggregator
#    untouched — the ledger must not launder it into something else.
#    (This is the case the #3018 scratch PR measured on real CI.)
# ---------------------------------------------------------------------------
: > "$LEDGER"
out=$(run "$LEDGER" '{"name":"Timeout probe","status":"completed","conclusion":"cancelled","id":7}')
assert_eq "an unsuperseded cancelled passes through as cancelled" \
'{"name":"Timeout probe","status":"completed","conclusion":"cancelled","id":7}' "$out"
set +e
printf '%s\n' "$out" | jq -r '[.conclusion // "", .name] | @tsv' \
  | ADVISORY_CHECKS="Coverage"$'\n'"Agent review" bash "$SCRIPT_DIR/ci-gate-aggregate.sh" >/dev/null 2>&1
rc=$?
set -e
assert_eq "…and still fails the gate end-to-end (merge → aggregate)" "1" "$rc"

# ---------------------------------------------------------------------------
# 5. #2492 is untouched: two runs sharing a name inside ONE read are collapsed
#    by the caller before the ledger sees them, and the ledger keys on name,
#    so a superseded `cancelled` never resurfaces once the fresh run exists.
# ---------------------------------------------------------------------------
: > "$LEDGER"
collapsed=$(printf '%s\n' \
  '{"name":"Detect changed paths","status":"completed","conclusion":"cancelled","id":10}' \
  '{"name":"Detect changed paths","status":"completed","conclusion":"success","id":20}' \
  | jq -s -c 'group_by(.name) | map(max_by(.id))[]')
out=$(printf '%s\n' "$collapsed" | bash "$MERGE" "$LEDGER")
assert_eq "#2492 collapse still wins: superseded cancelled is not resurrected" \
'{"name":"Detect changed paths","status":"completed","conclusion":"success","id":20}' "$out"

# ---------------------------------------------------------------------------
# 6. Empty current read with a non-empty ledger — everything vanishes, nothing
#    is lost, and the merged view is non-empty so the docs-only bypass (which
#    only fires while the set is COMPLETELY empty) cannot re-arm.
# ---------------------------------------------------------------------------
printf '%s\n' '{"name":"Unit tests","status":"completed","conclusion":"success","id":3}' > "$LEDGER"
out=$(printf '\n' | bash "$MERGE" "$LEDGER")
assert_eq "empty read keeps the ledger (docs-only bypass cannot re-arm)" \
'{"name":"Unit tests","status":"vanished","conclusion":null,"id":3}' "$out"

# ---------------------------------------------------------------------------
# 7. Empty read AND empty ledger — a genuinely docs-only PR stays empty.
# ---------------------------------------------------------------------------
: > "$LEDGER"
out=$(printf '\n' | bash "$MERGE" "$LEDGER")
assert_eq "empty read + empty ledger stays empty (docs-only PR unaffected)" "" "$out"

# ---------------------------------------------------------------------------
# 8. A missing ledger file (first iteration, before any write) is not an error.
# ---------------------------------------------------------------------------
out=$(printf '%s\n' '{"name":"Lint","status":"completed","conclusion":"success","id":1}' \
  | bash "$MERGE" "/nonexistent/ci-gate-ledger-$$")
assert_eq "a missing ledger file is treated as empty" \
'{"name":"Lint","status":"completed","conclusion":"success","id":1}' "$out"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo -e "${RED}$FAILED test(s) failed${NC}"
  exit 1
fi
echo -e "${GREEN}All CI Gate observation-ledger tests passed${NC}"
