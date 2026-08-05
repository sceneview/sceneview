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

# ── Subagent transcripts ─────────────────────────────────────────────────────
# Subagents do not write into <slug>/*.jsonl. They get their own transcript at
# <slug>/<sessionId>/subagents/agent-*.jsonl, and for months this report globbed
# only the first pattern and reported "0 subagent requests" on a machine holding
# 643 subagent transcripts. A zero that means "not looked at" is the single most
# dangerous output this script can produce, so it is pinned here.
SUBPROJ="$TMP/.claude/projects/sub-project"
mkdir -p "$SUBPROJ/session-xyz/subagents"
rec req_MAIN uuid_m 10 claude-opus-5 main false "$NOW" > "$SUBPROJ/session-xyz.jsonl"
# isSidechain is deliberately false here: the record itself does not always
# carry the flag, so attribution must come from the PATH.
rec req_SUB uuid_s 20 claude-sonnet-5 main false "$NOW" \
  > "$SUBPROJ/session-xyz/subagents/agent-deadbeef.jsonl"

OUT="$(HOME="$TMP" bash "$REPORT" --project sub-project --days 7 --json 2>&1)"; RC=$?
check "subagent transcript is discovered" 2 "$(jq_field "d['totals']['requests']")"
check "subagent output is counted" 30 "$(jq_field "d['totals']['output']")"
check "subagent attributed by path, not by flag" 1 "$(jq_field "d['totals']['subagent_requests']")"
check "subagent cost folded into the OWNING session" 1 "$(jq_field "len(d['groups'])")"

# Mutation: drop the subagents glob and the count must fall back to 1.
MUT2="$TMP/report-noglob.sh"
python3 - "$REPORT" "$MUT2" <<'PYMUT'
import sys
src = open(sys.argv[1]).read()
needle = '+ glob.glob(os.path.join(root, project, "*", "subagents", "*.jsonl")))'
assert needle in src, "anchor moved — fix this test"
open(sys.argv[2], "w").write(src.replace(needle, ")"))
PYMUT
if [ $? -ne 0 ]; then
  echo "  ✗ subagent-glob mutation could not be applied — anchor moved"; FAIL=$((FAIL + 1))
else
  mut2="$(HOME="$TMP" bash "$MUT2" --project sub-project --days 7 --json 2>&1)"
  mut2_req="$(printf '%s' "$mut2" | python3 -c "import json,sys;print(json.load(sys.stdin)['totals']['requests'])" 2>/dev/null || echo ERR)"
  if [ "$mut2_req" = "2" ]; then
    echo "  ✗ MUTATION SURVIVED — subagents are found by something other than that glob"
    FAIL=$((FAIL + 1))
  else
    echo "  ✓ mutation killed — without the subagents glob only $mut2_req of 2 requests are seen"
    PASS=$((PASS + 1))
  fi
fi

# ── Weighted cost ────────────────────────────────────────────────────────────
# Raw token counts are not comparable (an output token costs 50x a cache-read
# token). The headline used to be raw output, which under-reported the driver by
# ~8x. The breakdown must exist and must account for the whole weighted total.
run --days 7
# req_A+req_B+req_C: input 15, cache_write 300 (x1.25), cache_read 2700 (x0.1),
# output 350 (x5) = 15 + 375 + 270 + 1750 = 2410.
check "weighted cost computed from price ratios" 2410 "$(jq_field "int(d['totals']['weighted'])")"
check "breakdown accounts for 100% of the weighted total" 100.0 \
  "$(jq_field "round(sum(c['pct'] for c in d['weightedCostBreakdown']),1)")"
# Per request: input 5 + cache_read 900 + cache_write 100 = 1005. It is an
# AVERAGE, not a sum — the number that matters is what one turn re-reads.
check "average context per request reported" 1005 "$(jq_field "d['avgContextPerRequest']")"

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
