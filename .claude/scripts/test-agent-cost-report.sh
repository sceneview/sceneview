#!/usr/bin/env bash
# Hermetic self-test for agent-cost-report.sh.
#
# The measurement's whole value is that the numbers are RIGHT. The one way it
# silently goes wrong is deduplication: a transcript writes several records per
# API call carrying the same `usage`, so summing records instead of requests
# inflates output tokens (measured ~95% overstatement on a real session). An
# inflated number is worse than no number — it would drive the exact throttling
# decisions this script exists to inform.
#
# Fixtures only: a fake $HOME with synthetic .jsonl transcripts. No network,
# no real session data.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT="$SCRIPT_DIR/agent-cost-report.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
PROJ="$TMP/.claude/projects/fake-project"
mkdir -p "$PROJ"

# One API call reported twice (streaming + final), then a second, distinct call.
# Truth: 2 requests, 300 output tokens.
rec() { # requestId uuid output model branch sidechain ts
  printf '{"requestId":"%s","uuid":"%s","timestamp":"%s","gitBranch":"%s","isSidechain":%s,' \
    "$1" "$2" "$7" "$5" "$6"
  printf '"message":{"model":"%s","usage":{"output_tokens":%s,"input_tokens":5,' "$4" "$3"
  printf '"cache_creation_input_tokens":100,"cache_read_input_tokens":900}}}\n'
}

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  rec req_A uuid_1 100 claude-opus-5 main false "$NOW"
  rec req_A uuid_2 100 claude-opus-5 main false "$NOW"   # duplicate of req_A
  rec req_B uuid_3 200 claude-fable-5 main true  "$NOW"
} > "$PROJ/session-one.jsonl"

# A second session file, plus one record far in the past to exercise --days.
{
  rec req_C uuid_4 50 claude-opus-5 feature false "$NOW"
  rec req_OLD uuid_5 9999 claude-opus-5 old false "2020-01-01T00:00:00Z"
} > "$PROJ/session-two.jsonl"

run() { # extra args -> $OUT json
  OUT="$(HOME="$TMP" bash "$REPORT" --project fake-project --json "$@" 2>&1)"
  RC=$?
}

# Evaluate a python expression against the parsed JSON, which is bound to `d`.
jq_field() { printf '%s' "$OUT" | python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1" 2>/dev/null; }

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    echo "  ✓ $1"; PASS=$((PASS + 1))
  else
    echo "  ✗ $1 — expected $2, got $3"; FAIL=$((FAIL + 1))
  fi
}

echo "agent-cost-report.sh — measurement contract"

run --days 7
check "exit 0 on valid transcripts" 0 "$RC"
# req_A (once, not twice) + req_B + req_C = 3 requests, 100+200+50 = 350 output.
check "requests deduped by requestId" 3 "$(jq_field "d['totals']['requests']")"
check "output tokens deduped (not doubled)" 350 "$(jq_field "d['totals']['output']")"
check "duplicate record reported, not hidden" 1 "$(jq_field "d['duplicate_records_skipped']")"
check "sidechain requests counted" 1 "$(jq_field "d['totals']['subagent_requests']")"
check "old record excluded by --days 7" 3 "$(jq_field "d['totals']['requests']")"

run --days 0
check "--days 0 includes the 2020 record" 4 "$(jq_field "d['totals']['requests']")"
check "--days 0 output includes it" 10349 "$(jq_field "d['totals']['output']")"

run --days 7 --by model
check "--by model groups both models" 2 "$(jq_field "len(d['groups'])")"

run --days 7 --by branch
check "--by branch groups both branches" 2 "$(jq_field "len(d['groups'])")"

# Empty project must say so, not crash and not fabricate zeros as a "clean" run.
mkdir -p "$TMP/.claude/projects/empty-project"
OUT="$(HOME="$TMP" bash "$REPORT" --project empty-project --json 2>&1)"; RC=$?
check "empty project exits 0" 0 "$RC"
check "empty project reports 0 requests" 0 "$(jq_field "d['requests']")"

# ── Mutation test ────────────────────────────────────────────────────────────
# The mutation target is the dedup KEY, not the `if key in seen: continue`.
# Measured while writing this suite: neutering that `continue` does NOT change
# any total, because `seen` is a dict and the duplicate simply overwrites its
# own entry — the skip only feeds the "duplicates skipped" counter. What
# actually produces correct numbers is keying on `requestId`. Re-key it to the
# per-record `uuid` (the natural wrong choice, and the one that inflated the
# real session by ~95%) and the totals must move.
MUT="$TMP/report-mutant.sh"
sed 's|key = rec.get("requestId") or|key = rec.get("uuid") or|' "$REPORT" > "$MUT"
if ! grep -q 'key = rec.get("uuid") or' "$MUT"; then
  echo "  ✗ mutation could not be applied — the anchor line moved, fix this test"
  FAIL=$((FAIL + 1))
else
  mut="$(HOME="$TMP" bash "$MUT" --project fake-project --days 7 --json 2>&1)"
  mut_out="$(printf '%s' "$mut" | python3 -c "import json,sys;print(json.load(sys.stdin)['totals']['output'])" 2>/dev/null || echo ERR)"
  if [ "$mut_out" = "350" ]; then
    echo "  ✗ MUTATION SURVIVED — the requestId key is not what produces 350;"
    echo "    a regression to per-record keying would go unnoticed"
    FAIL=$((FAIL + 1))
  else
    echo "  ✓ mutation killed — keying on uuid inflates the total to $mut_out instead of 350"
    PASS=$((PASS + 1))
  fi
fi

echo
echo "agent-cost-report: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
