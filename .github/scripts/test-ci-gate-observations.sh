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
QUALIFY="$SCRIPT_DIR/ci-gate-qualify-runs.sh"

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
# The suite→workflow map the qualifier reads (#3033). Two suites, so a name
# collision across workflows can be simulated: 1 → the core workflow, 2 → a
# second workflow that a fork PR could add.
SUITE_MAP=$(mktemp)
printf '%s\t%s\n' 1 '.github/workflows/ci.yml' 2 '.github/workflows/evil.yml' > "$SUITE_MAP"
trap 'rm -f "$LEDGER" "$SUITE_MAP"' EXIT

# qualify <payload-lines…> — run the REAL qualifier the workflow runs. Nothing
# here re-implements the normalisation or the #2492 collapse: a copy would keep
# passing against a workflow that dropped either one, which is the exact
# hollowness #3047 found in this file (a hand-copied `group_by(.name)` passed
# with the merge script fully neutered).
qualify() { printf '%s\n' "$@" | SELF_CHECK="CI Gate" bash "$QUALIFY" "$SUITE_MAP"; }

# The `pending` selector applied to the merged view. It is EXTRACTED from
# ci-gate.yml rather than copied, so this suite cannot keep passing against a
# selector the workflow no longer uses — a copy would drift silently, and the
# whole point of #3018 is that `vanished` must land in `pending`.
GATE_YML="$(cd "$SCRIPT_DIR/../.." && pwd)/.github/workflows/ci-gate.yml"
PENDING_JQ=$(awk '
  /pending=\$\(echo "\$others" \| jq -r/ { getline; sub(/^[[:space:]]*/, ""); sub(/\)$/, ""); print; exit }
' "$GATE_YML" | sed "s/^'//; s/'$//")
if [ -z "$PENDING_JQ" ]; then
  echo -e "  ${RED}FAIL${NC}  could not extract the pending selector from $GATE_YML"
  exit 1
fi
echo "  using pending selector from ci-gate.yml: $PENDING_JQ"
pending_of() { jq -r "$PENDING_JQ"; }

# Compact, order-independent rendering of a merged view: `name=status/conclusion`
# per line, sorted. Comparing raw JSON lines would tie the assertions to key
# order and to where a renamed check happens to sort.
summarise() { jq -r '"\(.name)=\(.status)/\(.conclusion // "null")"' | sort; }

# `ADVISORY_CHECKS` is extracted too, for the same reason as the selector and
# `REQUIRED_CHECKS`: a hardcoded copy keeps passing after the workflow's list
# changes, which is the drift this suite exists to prevent.
ADVISORY_FROM_YML=$(awk '
  /^ *ADVISORY_CHECKS: \|/ { grab = 1; next }
  grab {
    if ($0 !~ /^[[:space:]]{10,}[^[:space:]#]/) exit
    sub(/^[[:space:]]*/, "")
    print
  }
' "$GATE_YML")
if [ -z "$ADVISORY_FROM_YML" ]; then
  echo -e "  ${RED}FAIL${NC}  could not extract ADVISORY_CHECKS from $GATE_YML"
  exit 1
fi

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

# The CORE-CHECK GUARD builds `observed_names` off this same merged view and
# disarms itself when it sees ZERO core checks (`any_core_seen != true` →
# `missing_core=""` in ci-gate.yml) — the docs-only signature, and one of the
# three things that let #3015 through. This asserts only the ONE link this
# script controls: the name survives into `observed_names`. It does NOT drive
# the guard, the advisory filter or the break condition — those live in
# ci-gate.yml's poll loop and are not exercised by any test in this repo. See
# #3024; do not read a pass here as "the guard is proven armed".
assert_eq "a vanished CORE check survives into observed_names" \
"$CORE_CHECK" \
"$(printf '%s\n' "$merged" | jq -r '.name' | grep -xF -- "$CORE_CHECK" || true)"

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
  | ADVISORY_CHECKS="$ADVISORY_FROM_YML" bash "$SCRIPT_DIR/ci-gate-aggregate.sh" >/dev/null 2>&1
rc=$?
set -e
assert_eq "…and still fails the gate end-to-end (merge → aggregate)" "1" "$rc"

# ---------------------------------------------------------------------------
# 5. #2492 is untouched, and #3033 does not loosen it: two runs sharing a name
#    inside ONE workflow are collapsed to the freshest before the ledger sees
#    them, so a superseded `cancelled` never reaches the gated set.
#
#    This runs the qualifier the workflow runs. The previous version of this
#    case pasted `group_by(.name) | map(max_by(.id))[]` inline and asserted on
#    the paste — it passed with BOTH the collapse and the merge script
#    neutered, which is the false-green #3047 reported about this file.
# ---------------------------------------------------------------------------
: > "$LEDGER"
collapsed=$(qualify \
  '{"name":"Detect changed paths","status":"completed","conclusion":"cancelled","id":10,"suite":1,"app":15368}' \
  '{"name":"Detect changed paths","status":"completed","conclusion":"success","id":20,"suite":1,"app":15368}')
assert_eq "#2492: two runs of one name in one workflow collapse to the freshest" \
'{"name":"Detect changed paths","status":"completed","conclusion":"success","id":20,"workflow":".github/workflows/ci.yml"}' \
"$collapsed"

printf '%s\n' "$collapsed" | bash "$MERGE" "$LEDGER" > "$LEDGER".n
mv "$LEDGER".n "$LEDGER"

# Poll 2: the read regresses and lists ONLY the superseded `cancelled`. The
# ledger is monotone in `id`, so the fresh success is not overwritten by it —
# without that arm this case would pass against a merge script neutered to
# `cat`, and a green PR would go red on a run that was superseded minutes ago.
out=$(qualify \
  '{"name":"Detect changed paths","status":"completed","conclusion":"cancelled","id":10,"suite":1,"app":15368}' \
  | bash "$MERGE" "$LEDGER" | summarise)
assert_eq "#2492 collapse still wins: superseded cancelled is not resurrected" \
"Detect changed paths=vanished/null" "$out"

# #3033: the SAME two records under two DIFFERENT workflows are two distinct
# checks, and the failing one survives. Collapsing by name only (the pre-#3033
# behaviour) drops it, which is how a green run could hide a red one.
: > "$LEDGER"
kept=$(qualify \
  '{"name":"Repo hygiene checks","status":"completed","conclusion":"failure","id":10,"suite":1,"app":15368}' \
  '{"name":"Repo hygiene checks","status":"completed","conclusion":"success","id":20,"suite":2,"app":15368}' \
  | summarise)
assert_eq "#3033: a same-named run in another workflow cannot displace a failure" \
"$(printf '%s\n' 'Repo hygiene checks=completed/failure' 'Repo hygiene checks=completed/success')" \
"$kept"

# …and the ledger keys on the pair too, so the two survive the merge as two
# records rather than one silently overwriting the other.
merged_pair=$(printf '%s\n' \
  '{"name":"Repo hygiene checks","status":"completed","conclusion":"failure","id":10,"workflow":".github/workflows/ci.yml"}' \
  '{"name":"Repo hygiene checks","status":"completed","conclusion":"success","id":20,"workflow":".github/workflows/evil.yml"}' \
  | bash "$MERGE" "$LEDGER" | wc -l | tr -d ' ')
assert_eq "#3033: the ledger keys on (workflow, name), not on name alone" "2" "$merged_pair"

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

# ---------------------------------------------------------------------------
# 8b. A READ THAT REGRESSED TO AN OLDER RUN. "The live read is authoritative
#     for a name it still reports" is false when a response drops the FRESH
#     run while still listing the SUPERSEDED one — the same instability #3018
#     is built on, and #2492 documents that both coexist at one head SHA here.
#     Both directions were measured GREEN/RED-wrong before the merge was made
#     monotone in `id`.
# ---------------------------------------------------------------------------
: > "$LEDGER"
# Poll 1: old run (id 4, completed) and fresh run (id 24, in progress); the
# caller's collapse keeps id 24.
run "$LEDGER" '{"name":"Unit tests","status":"in_progress","conclusion":null,"id":24}' > "$LEDGER".n
mv "$LEDGER".n "$LEDGER"
# Poll 2: only the OLD run comes back.
regressed=$(run "$LEDGER" '{"name":"Unit tests","status":"completed","conclusion":"skipped","id":4}')
assert_eq "a read that regressed to an older run does not retire the fresh one" \
"Unit tests=vanished/null" "$(printf '%s\n' "$regressed" | summarise)"
assert_eq "…so it stays PENDING instead of concluding with no verdict" \
"Unit tests" "$(printf '%s\n' "$regressed" | pending_of)"

# Mirror case: the fresh run had already been observed SUCCESS. Regressing to
# the superseded `cancelled` must not redden a PR that was seen green.
: > "$LEDGER"
run "$LEDGER" '{"name":"Unit tests","status":"completed","conclusion":"success","id":21}' > "$LEDGER".n
mv "$LEDGER".n "$LEDGER"
regressed=$(run "$LEDGER" '{"name":"Unit tests","status":"completed","conclusion":"cancelled","id":1}')
assert_eq "regressing to a superseded cancelled does not redden an observed success" \
"Unit tests=vanished/null" "$(printf '%s\n' "$regressed" | summarise)"

# And the tie that keeps the real #3015 recovery green: a re-run REUSES its
# check-run id (measured, id 92399415842 across both attempts of #3015), so
# `mem` and `now` hold the same id. The live record must win that tie.
: > "$LEDGER"
run "$LEDGER" '{"name":"Compile KMP core","status":"completed","conclusion":"cancelled","id":92399415842}' > "$LEDGER".n
mv "$LEDGER".n "$LEDGER"
retried=$(run "$LEDGER" '{"name":"Compile KMP core","status":"completed","conclusion":"success","id":92399415842}')
assert_eq "a re-run reusing its check-run id still wins the tie (stays green)" \
"Compile KMP core=completed/success" "$(printf '%s\n' "$retried" | summarise)"
assert_eq "…and nothing is left pending" "" "$(printf '%s\n' "$retried" | pending_of)"

# ---------------------------------------------------------------------------
# 9. HOSTILE CHECK-RUN NAMES. A check-run name is fork-controlled: a fork PR
#    can add a workflow whose job `name:` contains a newline. Every downstream
#    consumer in ci-gate.yml treats names as newline-separated records, so one
#    such name splits into several fake ones. Measured on the pre-fix filter:
#      - `Evil\n::error title=CI Gate::forged` reached column 0 of the Actions
#        log, where the runner parses it as a workflow command;
#      - one check named `Evil2\nDetect changed paths\nRepo hygiene checks\n
#        Quality gate (full)` forged ALL THREE REQUIRED_CHECKS into
#        `observed_names`, disarming the CORE-CHECK GUARD.
#    ci-gate-qualify-runs.sh normalises `.name` once, where the records are
#    built, and the workflow runs that script — so this case EXECUTES the real
#    normalisation instead of a copy, and cannot pass against a qualifier that
#    dropped it.
# ---------------------------------------------------------------------------

# `printf %s` does NOT expand backslash escapes in its ARGUMENTS, so each `\n`
# below stays two characters — a valid JSON escape that jq decodes into a real
# newline inside `.name`, which is exactly the hostile input being simulated.
# (`echo` would expand them in some shells and corrupt the JSON; the workflow
# itself runs under `shell: bash`, where `echo` does not.)
hostile_out=$(qualify \
  '{"name":"Evil\n::error title=CI Gate::forged annotation","status":"completed","conclusion":"success","id":1,"suite":1,"app":15368}' \
  '{"name":"Evil2\nDetect changed paths\nRepo hygiene checks\nQuality gate (full)","status":"completed","conclusion":"success","id":2,"suite":1,"app":15368}' \
  '{"name":"Unit tests","status":"completed","conclusion":"failure","id":3,"suite":1,"app":15368}')

# One input record must yield exactly one name — no splitting.
assert_eq "a newline in a check name cannot forge extra records" "3" \
"$(printf '%s\n' "$hostile_out" | jq -r '.name' | wc -l | tr -d ' ')"

# The conclusions line prints `  - <name>: <conclusion>`; nothing may reach
# column 0, where the runner would parse it as a workflow command.
assert_eq "no line reaches column 0 of the Actions log" "" \
"$(printf '%s\n' "$hostile_out" | jq -r '"  - \(.name): \(.conclusion // .status)"' | grep -v '^  - ' || true)"

# And no REQUIRED_CHECK may be forged into observed_names by a hostile name.
assert_eq "a hostile name cannot forge a core check into observed_names" "" \
"$(printf '%s\n' "$hostile_out" | jq -r '.name' | grep -xF "$CORE_CHECK" || true)"

# ---------------------------------------------------------------------------
# 10. A fork-controlled name starting with `-` must not be parsed by grep as
#     an option. Without `--`, a vanished check named `-v` blanks the entire
#     "still running" diagnostic of the repo's only required check. The line
#     is EXTRACTED from ci-gate.yml and executed, so a dropped `--` fails here.
# ---------------------------------------------------------------------------
RUNNING_LINE=$(grep -E '^[[:space:]]*running=\$\(printf' "$GATE_YML" | sed 's/^[[:space:]]*//')
if [ -z "$RUNNING_LINE" ]; then
  echo -e "  ${RED}FAIL${NC}  could not extract the running= line from $GATE_YML"
  exit 1
fi
# shellcheck disable=SC2034  # both are read by the eval'd line below
pending=$(printf '%s\n' '-v' 'Unit tests' 'Lint')
# shellcheck disable=SC2034  # both are read by the eval'd line below
vanished=$(printf '%s\n' '-v')
running=""
eval "$RUNNING_LINE"
assert_eq "a vanished check named '-v' does not blank the running list" \
"$(printf '%s\n' 'Unit tests' 'Lint')" "$running"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo -e "${RED}$FAILED test(s) failed${NC}"
  exit 1
fi
echo -e "${GREEN}All CI Gate observation-ledger tests passed${NC}"
