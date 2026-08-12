#!/usr/bin/env bash
# test-ci-gate-loop.sh — LOOP-LEVEL regression suite for `CI Gate`.
#
# WHY A LOOP-LEVEL HARNESS EXISTS AT ALL
# --------------------------------------
# `test-ci-gate-observations.sh` and `test-ci-gate-aggregation.sh` test the two
# helper SCRIPTS. Neither can see a defect in the poll loop that CALLS them,
# and every false-green this gate has shipped has lived in that loop:
#
#   #3015/#3018  a partial Checks API read broke the wait loop, disarmed the
#                core-check guard and aggregated one check run;
#   #3024        a restarted gate with an empty ledger did the same on its
#                FIRST read;
#   #3033        the latest-run-per-name collapse dropped a real `failure` in
#                favour of a same-named check run with a higher id;
#   #3047        the observation-ledger suite stayed 21/21 GREEN with the
#                ledger's own two wiring lines DELETED from ci-gate.yml —
#                a self-declared gate passing with its subject removed.
#
# So this suite does not test a script. It EXTRACTS the real `run:` block out
# of `.github/workflows/ci-gate.yml` — together with that step's real `env:` —
# and executes it against scripted API reads, with `gh`, `date` and `sleep`
# stubbed. Nothing is copied, so it cannot drift; and because it runs the
# actual loop, deleting any wiring inside that loop turns this suite red.
#
# Virtual time makes the 50-minute deadline and the 90-second docs-only grace
# period reachable in milliseconds: the stubbed `sleep` advances a fake clock
# that the stubbed `date +%s` reads back.
#
# HOW A CASE ASSERTS
# ------------------
# On the EXIT CODE **and** on a verdict STRING from the log. An exit code alone
# cannot tell "red for the right reason" from "red because the harness broke",
# and this repo has shipped a gate whose hard-coded diagnosis lied. Every case
# below names the exact line it requires.
#
# Usage: bash .github/scripts/test-ci-gate-loop.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GATE_YML="$REPO_ROOT/.github/workflows/ci-gate.yml"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

FAILED=0
pass() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail() { echo -e "  ${RED}FAIL${NC}  $1"; FAILED=$((FAILED + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# EXTRACT the loop and its env from ci-gate.yml. Both, or the suite would run
# the real code against invented settings — `REQUIRED_CHECKS`, `ADVISORY_CHECKS`
# and `CORE_WORKFLOW` all steer the branches under test, and a hand-copied list
# keeps passing after the workflow's own list changes.
# ---------------------------------------------------------------------------
python3 - "$GATE_YML" "$WORK" <<'PY'
import sys, yaml, json, os
gate_yml, work = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(gate_yml))
steps = doc["jobs"]["ci-gate"]["steps"]
matches = [s for s in steps if "check conclusions" in (s.get("name") or "")]
if len(matches) != 1:
    sys.exit(f"expected exactly 1 aggregation step in {gate_yml}, found {len(matches)}")
step = matches[0]
if not step.get("run"):
    sys.exit("the aggregation step has no `run:` block")
open(os.path.join(work, "loop.sh"), "w").write(step["run"])
env = step.get("env") or {}
# The env values that steer the loop, written as a sourceable file. Anything
# templated with ${{ }} is supplied by the harness instead.
keep = {}
for k, v in env.items():
    if isinstance(v, str) and "${{" not in v:
        keep[k] = v
missing = [k for k in ("REQUIRED_CHECKS", "ADVISORY_CHECKS", "SELF_CHECK", "CORE_WORKFLOW") if k not in keep]
if missing:
    sys.exit(f"ci-gate.yml's aggregation step no longer defines: {', '.join(missing)}")
with open(os.path.join(work, "gate-env.json"), "w") as fh:
    json.dump(keep, fh)
PY

# `CORE_WORKFLOW` must name a real workflow that really declares every core
# check. A rename on either side would otherwise disarm the #3024 guard in
# total silence — the class of bug this whole file exists to catch.
CORE_WORKFLOW=$(jq -r '.CORE_WORKFLOW' "$WORK/gate-env.json")
if [ ! -f "$REPO_ROOT/$CORE_WORKFLOW" ]; then
  fail "CORE_WORKFLOW points at a file that does not exist: $CORE_WORKFLOW"
else
  pass "CORE_WORKFLOW names a real workflow file ($CORE_WORKFLOW)"
fi
missing_names=""
while IFS= read -r rc; do
  [ -z "$rc" ] && continue
  grep -qF "name: $rc" "$REPO_ROOT/$CORE_WORKFLOW" || missing_names="${missing_names}${rc}; "
done <<EOF
$(jq -r '.REQUIRED_CHECKS' "$WORK/gate-env.json" | sed '/^[[:space:]]*$/d')
EOF
if [ -n "$missing_names" ]; then
  fail "REQUIRED_CHECKS names not declared by $CORE_WORKFLOW: $missing_names"
else
  pass "every REQUIRED_CHECKS name is a job in $CORE_WORKFLOW"
fi

# ---------------------------------------------------------------------------
# STUBS. `gh` serves scripted reads; `date`/`sleep` share a virtual clock.
# ---------------------------------------------------------------------------
mkdir -p "$WORK/stub"

cat > "$WORK/stub/gh" <<'STUB'
#!/usr/bin/env bash
# Serves the two endpoints the gate reads. `check-runs` advances a poll
# counter and emits `read-N.json`, repeating the last scripted read forever
# once they run out; `actions/runs` emits the scenario's `runs.json`, or
# nothing when the scenario has no such file (a docs-only PR).
for arg in "$@"; do
  case "$arg" in
    *actions/runs*)
      if [ -f "$SIM_READS/runs.json" ]; then
        jq -r '.[] | [(.check_suite_id | tostring), .path] | @tsv' "$SIM_READS/runs.json"
      fi
      exit 0
      ;;
    *check-runs*)
      n=$(cat "$SIM_STATE/poll" 2>/dev/null || echo 0)
      n=$((n + 1)); echo "$n" > "$SIM_STATE/poll"
      f="$SIM_READS/read-$n.json"
      if [ ! -f "$f" ]; then
        last=$(ls "$SIM_READS" | sed -n 's/^read-\([0-9]*\)\.json$/\1/p' | sort -n | tail -1)
        f="$SIM_READS/read-$last.json"
      fi
      echo "  [sim] check-runs poll #$n -> $(basename "$f")" >&2
      jq -c '.[] | {name: .name, status: .status, conclusion: .conclusion, id: .id, suite: .check_suite_id, app: (.app_id // 15368)}' "$f"
      exit 0
      ;;
  esac
done
echo "  [sim] unstubbed gh call: $*" >&2
exit 1
STUB

# `SIM_TIME_SCALE` compresses the wait, never the logic. Every deadline in the
# loop is an ABSOLUTE instant computed once from `date +%s`, so scaling the
# poll interval only changes how many polls it takes to reach one — the
# branches taken are identical. It exists because reaching the real 50-minute
# deadline at the real 20 s poll interval costs 150 iterations of the whole
# chain per scenario. Scenarios that assert on a SHORT deadline (the 90 s
# docs-only grace) run at scale 1, so that timing stays faithful.
cat > "$WORK/stub/sleep" <<'STUB'
#!/usr/bin/env bash
cur=$(cat "$SIM_STATE/clock" 2>/dev/null || echo 1000000)
echo $((cur + ${1:-0} * ${SIM_TIME_SCALE:-1})) > "$SIM_STATE/clock"
STUB

cat > "$WORK/stub/date" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "+%s" ]; then
  cat "$SIM_STATE/clock" 2>/dev/null || echo 1000000
  exit 0
fi
exec /bin/date "$@"
STUB

chmod +x "$WORK/stub/gh" "$WORK/stub/sleep" "$WORK/stub/date"

# run_scenario <fixture-dir> [time-scale] → log in $WORK/out, sets GATE_RC
run_scenario() {
  local fixture="$1" scale="${2:-1}"
  local state; state=$(mktemp -d)
  echo 1000000 > "$state/clock"
  set +e
  (
    cd "$REPO_ROOT" || exit 2
    export PATH="$WORK/stub:$PATH"
    export SIM_READS="$fixture" SIM_STATE="$state" SIM_TIME_SCALE="$scale"
    export HEAD_SHA=deadbeef REPO=sceneview/sceneview
    # Every non-templated env value comes from ci-gate.yml itself.
    eval "$(jq -r 'to_entries[] | "export \(.key)=\(.value | @sh)"' "$WORK/gate-env.json")"
    bash "$WORK/loop.sh"
  ) > "$WORK/out" 2>&1
  GATE_RC=$?
  set -e
  rm -rf "$state"
}

# assert_scenario <label> <fixture> <expected-rc> <expected-substring> [scale]
assert_scenario() {
  local label="$1" fixture="$2" want_rc="$3" want_str="$4" scale="${5:-1}"
  run_scenario "$fixture" "$scale"
  if [ "$GATE_RC" != "$want_rc" ]; then
    fail "$label — expected exit $want_rc, got $GATE_RC"
    sed 's/^/        /' "$WORK/out" | tail -25
    return
  fi
  if ! grep -qF -- "$want_str" "$WORK/out"; then
    fail "$label — exit $GATE_RC as expected, but the log never said: $want_str"
    sed 's/^/        /' "$WORK/out" | tail -25
    return
  fi
  pass "$label"
}

# ---------------------------------------------------------------------------
# FIXTURES. `runs.json` is the `actions/runs?head_sha=` response — which
# workflow files produced check suites for this SHA. `read-N.json` is the Nth
# `check-runs` response.
# ---------------------------------------------------------------------------
CI_SUITE=1001            # the real ci.yml run
REVIEW_SUITE=1002        # pr-review.yml
FORK_SUITE=1003          # a workflow the PR itself added

mk() { mkdir -p "$WORK/fx/$1"; }

full_runs_json() {
  cat <<JSON
[{"check_suite_id": $CI_SUITE, "path": "$CORE_WORKFLOW"},
 {"check_suite_id": $REVIEW_SUITE, "path": ".github/workflows/pr-review.yml"}]
JSON
}

# `core_checks <suite> <status> <conclusion>` — the three REQUIRED_CHECKS.
core_checks() {
  local suite="$1" status="$2" concl="$3" id=200 out=""
  while IFS= read -r rc; do
    [ -z "$rc" ] && continue
    id=$((id + 1))
    out="$out{\"name\":\"$rc\",\"status\":\"$status\",\"conclusion\":$concl,\"id\":$id,\"check_suite_id\":$suite},"
  done <<EOF
$(jq -r '.REQUIRED_CHECKS' "$WORK/gate-env.json" | sed '/^[[:space:]]*$/d')
EOF
  printf '%s' "$out"
}

echo "== CI Gate poll loop (#3015/#3018/#3024/#3033/#3047) =="

# --- F : #3024. Restarted gate, empty ledger, steady partial read. -----------
# The Checks API only ever returns the advisory `Agent review` — the run-attempt
# rebuild window — but a `ci.yml` run DOES exist for this SHA. The gate must not
# conclude over one advisory check.
mk F
full_runs_json > "$WORK/fx/F/runs.json"
cat > "$WORK/fx/F/read-1.json" <<JSON
[{"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
assert_scenario "#3024: a partial steady state cannot pass green over one advisory check" \
  "$WORK/fx/F" 1 "Expected core checks that never registered:" 20

# --- F-docs : the #2117 protection for the very same read. -------------------
# Byte-identical check-runs response, but NO ci.yml run at this SHA — the
# signature of a genuinely docs-only PR. It must still go green, and fast.
mk Fdocs
cat > "$WORK/fx/Fdocs/runs.json" <<JSON
[{"check_suite_id": $REVIEW_SUITE, "path": ".github/workflows/pr-review.yml"}]
JSON
cp "$WORK/fx/F/read-1.json" "$WORK/fx/Fdocs/read-1.json"
assert_scenario "#2117 not regressed: the same read with no ci.yml run passes green" \
  "$WORK/fx/Fdocs" 0 "CI Gate passed — every CI check succeeded or was skipped."

# --- G : #3033. A same-named check run displacing a real failure. ------------
# `Unit tests` fails in ci.yml; a job the PR added, in its own workflow file,
# registers later (higher id) under the same name and succeeds.
mk G
cat > "$WORK/fx/G/runs.json" <<JSON
[{"check_suite_id": $CI_SUITE, "path": "$CORE_WORKFLOW"},
 {"check_suite_id": $FORK_SUITE, "path": ".github/workflows/added-by-this-pr.yml"}]
JSON
cat > "$WORK/fx/G/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"completed","conclusion":"failure","id":300,"check_suite_id":$CI_SUITE},
 {"name":"Unit tests","status":"completed","conclusion":"success","id":999,"check_suite_id":$FORK_SUITE}]
JSON
assert_scenario "#3033: a same-named check run cannot displace a real failure" \
  "$WORK/fx/G" 1 "One or more required CI checks failed:"
if grep -qF "Unit tests (.github/workflows/added-by-this-pr.yml)" "$WORK/out"; then
  pass "#3033: the colliding name is annotated with its workflow in the conclusions log"
else
  fail "#3033: the conclusions log did not attribute the colliding name to its workflow"
  sed 's/^/        /' "$WORK/out" | tail -20
fi

# --- G2 : #2492 NOT regressed. Two runs of the SAME workflow still collapse. --
# `cancel-in-progress` supersedes an in-flight ci.yml run: the cancelled record
# and the fresh one share a workflow file, so the cancelled one is still noise.
mk G2
full_runs_json > "$WORK/fx/G2/runs.json"
cat > "$WORK/fx/G2/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"completed","conclusion":"cancelled","id":300,"check_suite_id":$CI_SUITE},
 {"name":"Unit tests","status":"completed","conclusion":"success","id":999,"check_suite_id":$CI_SUITE}]
JSON
assert_scenario "#2492 not regressed: a superseded cancelled from the same workflow is still collapsed" \
  "$WORK/fx/G2" 0 "CI Gate passed — every CI check succeeded or was skipped."

# --- A : #3018/#3047. The suite vanishes and never comes back. ---------------
# Poll 1 sees everything, with `Unit tests` still running so the loop keeps
# going; poll 2 returns only the advisory suite — the run-attempt rebuild
# window. The ledger must keep the vanished names gating, so the gate times out
# RED instead of concluding over the one check the API still reported.
# ⛔ This is the case that goes GREEN if the ledger wiring is deleted from
# ci-gate.yml — the #3047 mutation the observation-ledger suite could not see.
mk A
full_runs_json > "$WORK/fx/A/runs.json"
cat > "$WORK/fx/A/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"in_progress","conclusion":null,"id":300,"check_suite_id":$CI_SUITE},
 {"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
cat > "$WORK/fx/A/read-2.json" <<JSON
[{"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
assert_scenario "#3018: a suite that vanishes and never returns times out red, not green" \
  "$WORK/fx/A" 1 "Observed earlier but missing from the final Checks API read (#3018):" 20

# --- A2 : #3015 verbatim, and the sharpest pin on the ledger wiring. ---------
# Every CORE check stays visible and completed, so the core-check guard is
# satisfied and cannot help. Only the non-core `Unit tests` drops out of the
# read while still running. Without the ledger, `pending` empties, the loop
# breaks and the gate aggregates three successes: GREEN over a check that never
# concluded — #3015 exactly. With it, `Unit tests` is `vanished`, still gates,
# and the gate times out red.
# ⛔ Deleting the two ledger lines from ci-gate.yml flips THIS case 1 → 0.
mk A2
full_runs_json > "$WORK/fx/A2/runs.json"
cat > "$WORK/fx/A2/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"in_progress","conclusion":null,"id":300,"check_suite_id":$CI_SUITE}]
JSON
cat > "$WORK/fx/A2/read-2.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
assert_scenario "#3015: a single check vanishing mid-run cannot be concluded over" \
  "$WORK/fx/A2" 1 "Observed earlier but missing from the final Checks API read (#3018):" 20

# --- B : the legitimate recovery. cancelled → re-run → success. --------------
# Same opening as A, but the re-run comes back on poll 3 REUSING its check-run
# id (measured on #3015). The PR must go green.
mk B
full_runs_json > "$WORK/fx/B/runs.json"
cp "$WORK/fx/A/read-1.json" "$WORK/fx/B/read-1.json"
cp "$WORK/fx/A/read-2.json" "$WORK/fx/B/read-2.json"
cat > "$WORK/fx/B/read-3.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"completed","conclusion":"success","id":300,"check_suite_id":$CI_SUITE},
 {"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
assert_scenario "#3018: a re-run that comes back green (reusing its id) still passes" \
  "$WORK/fx/B" 0 "CI Gate passed — every CI check succeeded or was skipped."

# --- D : docs-only PR, no check runs at all. ---------------------------------
# The #2117 grace period must still fire — the gate cannot start hanging
# docs-only PRs for 50 minutes.
mk D
echo '[]' > "$WORK/fx/D/read-1.json"
assert_scenario "#2117 not regressed: a PR with no check runs at all passes on the grace period" \
  "$WORK/fx/D" 0 "CI Gate passed — docs-only PR, no CI workflows triggered."

# --- H : a real failure is still a real failure. -----------------------------
mk H
full_runs_json > "$WORK/fx/H/runs.json"
cat > "$WORK/fx/H/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Unit tests","status":"completed","conclusion":"failure","id":300,"check_suite_id":$CI_SUITE},
 {"name":"Agent review","status":"completed","conclusion":"success","id":10,"check_suite_id":$REVIEW_SUITE}]
JSON
assert_scenario "a plain failing check still fails the gate" \
  "$WORK/fx/H" 1 "One or more required CI checks failed:"

# --- I : #1984/#2013. An advisory check may fail, and is never waited on. ----
mk I
full_runs_json > "$WORK/fx/I/runs.json"
cat > "$WORK/fx/I/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"Agent review","status":"in_progress","conclusion":null,"id":10,"check_suite_id":$REVIEW_SUITE},
 {"name":"Coverage (advisory)","status":"completed","conclusion":"failure","id":11,"check_suite_id":$CI_SUITE}]
JSON
assert_scenario "#1984/#2013: a failing, still-running advisory check neither blocks nor reddens" \
  "$WORK/fx/I" 0 "CI Gate passed — every CI check succeeded or was skipped."

# --- J : #3047 finding 3. A fork-controlled name that `echo` would eat. ------
# A check named `-n` is the whole pending set. With `echo "$running"` the wait
# diagnostic of the repo's only required check prints an empty list; with
# `printf '%s\n'` it names the check.
mk J
full_runs_json > "$WORK/fx/J/runs.json"
cat > "$WORK/fx/J/read-1.json" <<JSON
[$(core_checks "$CI_SUITE" completed '"success"')
 {"name":"-n","status":"in_progress","conclusion":null,"id":300,"check_suite_id":$CI_SUITE}]
JSON
assert_scenario "#3047: a check named '-n' still times out red" \
  "$WORK/fx/J" 1 "Still running / awaiting approval:" 20
# The two display sites are asserted SEPARATELY, on their own indentation. A
# single loose `^ +- -n$` would be satisfied by either one, so reverting just
# one of them to `echo` would survive — measured, that is exactly what happened
# while writing this suite.
if grep -qxF '  - -n' "$WORK/out"; then
  pass "#3047: the WAIT block names the check called '-n' instead of blanking"
else
  fail "#3047: the wait block did not name the check called '-n' (echo ate it as an option)"
  sed 's/^/        /' "$WORK/out" | tail -20
fi
if grep -qxF '    - -n' "$WORK/out"; then
  pass "#3047: the TIMEOUT block names the check called '-n' instead of blanking"
else
  fail "#3047: the timeout block did not name the check called '-n' (echo ate it as an option)"
  sed 's/^/        /' "$WORK/out" | tail -20
fi

# --- K : #3023. A hostile newline in a name cannot forge a workflow command. --
mk K
full_runs_json > "$WORK/fx/K/runs.json"
python3 - "$WORK/fx/K/read-1.json" "$CI_SUITE" "$FORK_SUITE" <<'PY'
import json, sys
out, ci_suite, fork_suite = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
runs = [
    {"name": "Evil\n::error title=CI Gate::forged annotation",
     "status": "completed", "conclusion": "success", "id": 1,
     "check_suite_id": fork_suite},
    {"name": "Unit tests", "status": "completed", "conclusion": "failure",
     "id": 300, "check_suite_id": ci_suite},
]
json.dump(runs, open(out, "w"))
PY
# Re-add the core checks so the guard is satisfied and the loop reaches the
# conclusions log.
python3 - "$WORK/fx/K/read-1.json" "$CI_SUITE" "$WORK/gate-env.json" <<'PY'
import json, sys
out, ci_suite, envf = sys.argv[1], int(sys.argv[2]), sys.argv[3]
runs = json.load(open(out))
env = json.load(open(envf))
for i, rc in enumerate(l for l in env["REQUIRED_CHECKS"].splitlines() if l.strip()):
    runs.append({"name": rc.strip(), "status": "completed", "conclusion": "success",
                 "id": 400 + i, "check_suite_id": ci_suite})
json.dump(runs, open(out, "w"))
PY
assert_scenario "#3023: a hostile name still fails the gate on the real failure" \
  "$WORK/fx/K" 1 "One or more required CI checks failed:"
if grep -qE '^::error title=CI Gate::forged' "$WORK/out"; then
  fail "#3023: a forged workflow command reached column 0 of the log"
else
  pass "#3023: no forged workflow command reaches column 0 of the log"
fi

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo -e "${RED}$FAILED loop-level test(s) failed${NC}"
  exit 1
fi
echo -e "${GREEN}All CI Gate loop-level tests passed${NC}"
