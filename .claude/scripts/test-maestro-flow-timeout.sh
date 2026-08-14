#!/usr/bin/env bash
# test-maestro-flow-timeout.sh — self-test for the PER-FLOW Maestro budget and
# the `timeout` verdict it produces (#3141).
#
# What broke: `lib/maestro.sh` bounded ONE `maestro test` invocation at
# ${MAESTRO_TEST_TIMEOUT:-900}s, and `catalog.yaml` is an aggregator of seven
# (android) / eight (iOS) flows. Measured on the 4.30.0 release checkpoint:
# three of seven flows on android, one of eight on iOS, every executed step
# COMPLETED, and the leg still graded `failed` at rc=124. A full catalog pass
# could not reach a green verdict — and a real crash produced a byte-identical
# line to an expired clock, so the report could not tell them apart.
#
# What this pins, with a stubbed Maestro (no device, no network, no JDK):
#   1. An aggregator is split — each child flow gets its OWN budget, so a
#      catalog whose total exceeds one budget still passes.
#   2. A per-category flow (mapping-form `runFlow` + env) is NOT split: it
#      stays one invocation, keeping per-demo isolation.
#   3. An overrunning flow yields rc=124, NAMES the flow, and stops the run —
#      never a bare non-zero exit.
#   4. The real `.maestro/{android,ios}/catalog.yaml` classify as aggregators
#      and the real per-category flows do not.
#   5. The marker contract holds end to end: device-qa.sh's own
#      `timed_out_flow` parser reads the name out of the wrapper's output.
#   6. `timeout` is graded with the SAME weight as `failed` in device-qa.sh.
#      Measured before the fix: device-qa.sh's status `case` has no default
#      arm, so an unhandled status increments NO counter and the whole run
#      grades `passed` with a red leg in it. A finer verdict that grades looser
#      is worse than the coarse one it replaced.
#
# Usage: bash .claude/scripts/test-maestro-flow-timeout.sh
# Exit 0 = all assertions pass; exit 1 = a regression.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MAESTRO_LIB="$SCRIPT_DIR/lib/maestro.sh"
DEVICE_QA="$SCRIPT_DIR/device-qa.sh"

[ -f "$MAESTRO_LIB" ] || { echo "missing $MAESTRO_LIB" >&2; exit 1; }
[ -f "$DEVICE_QA" ]   || { echo "missing $DEVICE_QA" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail=0
ok()   { echo "  ok  : $1"; }
bad()  { echo "  FAIL: $1" >&2; fail=1; }
check() { # check <label> <condition-description> <actual> <expected>
  if [ "$3" = "$4" ]; then ok "$1 ($2=$3)"; else bad "$1 — $2 was '$3', expected '$4'"; fi
}

# --- Stub Maestro ----------------------------------------------------------
# Pinned version is read from the lib so a deliberate bump does not turn this
# test into a warning generator.
PINNED="$(sed -n 's/^MAESTRO_VERSION="\(.*\)"/\1/p' "$MAESTRO_LIB" | head -n1)"
mkdir -p "$TMP_DIR/bin" "$TMP_DIR/flows/sub"
cat > "$TMP_DIR/bin/maestro" <<EOF
#!/usr/bin/env bash
# Stub Maestro. Records every invocation, and sleeps SLOW_SECS on the flow
# named in SLOW_FLOW (empty = never slow).
if [ "\$1" = "--version" ]; then echo "$PINNED"; exit 0; fi
# The flow is the operand right after \`test\` — extra args (\`--udid …\`) follow it.
flow=""; seen_test=0
for a in "\$@"; do
  if [ "\$seen_test" = 1 ]; then flow="\$a"; break; fi
  [ "\$a" = "test" ] && seen_test=1
done
name="\$(basename "\$flow" .yaml)"
echo "\$name" >> "$TMP_DIR/invocations.txt"
echo "Run \$name..."
if [ "\$name" = "\${SLOW_FLOW:-}" ]; then sleep "\${SLOW_SECS:-30}"; else sleep 1; fi
echo "Run \$name... COMPLETED"
exit 0
EOF
chmod +x "$TMP_DIR/bin/maestro"

# `timeout` is absent on a stock macOS. Only shim it when neither the GNU
# binary nor `gtimeout` is on PATH, so wherever a real one exists this test
# exercises the real one.
if ! command -v timeout >/dev/null 2>&1 && ! command -v gtimeout >/dev/null 2>&1; then
  echo "note: no timeout(1)/gtimeout on PATH — using a bash shim for the budget"
  cat > "$TMP_DIR/bin/timeout" <<'EOF'
#!/usr/bin/env bash
secs="$1"; shift
"$@" & cmd_pid=$!
( sleep "$secs"; kill -TERM "$cmd_pid" 2>/dev/null ) & watch_pid=$!
wait "$cmd_pid" 2>/dev/null; rc=$?
kill "$watch_pid" 2>/dev/null; wait "$watch_pid" 2>/dev/null
[ "$rc" -ge 128 ] && exit 124
exit "$rc"
EOF
  chmod +x "$TMP_DIR/bin/timeout"
fi
export PATH="$TMP_DIR/bin:$PATH"

# --- Fixtures --------------------------------------------------------------
# An aggregator, in catalog.yaml's exact scalar `- runFlow: <sibling>.yaml`
# shape...
cat > "$TMP_DIR/flows/catalog.yaml" <<'EOF'
appId: io.example.demo
---
- runFlow: alpha.yaml
- runFlow: beta.yaml
- runFlow: gamma.yaml
EOF
for f in alpha beta gamma; do
  printf 'appId: io.example.demo\n---\n- launchApp\n' > "$TMP_DIR/flows/$f.yaml"
done
# ...and a per-category flow, in the mapping shape the real ones use.
cat > "$TMP_DIR/flows/category.yaml" <<'EOF'
appId: io.example.demo
---
- runFlow:
    file: sub/demo.yaml
    env:
      DEMO_ID: model-viewer
- runFlow:
    file: sub/demo.yaml
    env:
      DEMO_ID: geometry
EOF
printf 'appId: io.example.demo\n---\n- launchApp\n' > "$TMP_DIR/flows/sub/demo.yaml"
# A file that mixes a scalar runFlow with a real step: splitting it would drop
# the step, so it must NOT be treated as an aggregator.
cat > "$TMP_DIR/flows/mixed.yaml" <<'EOF'
appId: io.example.demo
---
- launchApp
- runFlow: alpha.yaml
EOF

run_flow() { # run_flow <flow> <budget> [SLOW_FLOW] [SLOW_SECS] -> prints rc, writes $TMP_DIR/out.txt
  local flow="$1" budget="$2" slow="${3:-}" secs="${4:-30}"
  : > "$TMP_DIR/invocations.txt"
  SLOW_FLOW="$slow" SLOW_SECS="$secs" MAESTRO_FLOW_TIMEOUT="$budget" \
  bash -c '
    set -u
    source "$1"
    maestro_run "$2" --udid STUB-UDID
    rc=$?
    echo "TIMEOUT_FLOW=${MAESTRO_TIMEOUT_FLOW:-}"
    exit $rc
  ' _ "$MAESTRO_LIB" "$flow" > "$TMP_DIR/out.txt" 2>&1
  echo $?
}
invocations() { grep -c . "$TMP_DIR/invocations.txt" 2>/dev/null || echo 0; }
timeout_flow() { sed -n 's/^TIMEOUT_FLOW=//p' "$TMP_DIR/out.txt" | tail -n1; }

echo "--- 1. an aggregator is split, each child under its own budget ---"
# 3 children x ~1 s each = ~3 s of work under a 2 s budget: red before #3141,
# green after, and that difference IS the bug.
RC="$(run_flow "$TMP_DIR/flows/catalog.yaml" 2)"
check "aggregator run" "rc" "$RC" "0"
check "aggregator run" "maestro invocations" "$(invocations)" "3"

echo "--- 2. a per-category flow stays ONE invocation ---"
RC="$(run_flow "$TMP_DIR/flows/category.yaml" 5)"
check "category flow" "rc" "$RC" "0"
check "category flow" "maestro invocations" "$(invocations)" "1"
RC="$(run_flow "$TMP_DIR/flows/mixed.yaml" 5)"
check "mixed scalar+step flow" "maestro invocations" "$(invocations)" "1"

echo "--- 3. an overrunning flow times out, is NAMED, and stops the run ---"
RC="$(run_flow "$TMP_DIR/flows/catalog.yaml" 2 beta 30)"
check "overrun" "rc" "$RC" "124"
check "overrun" "MAESTRO_TIMEOUT_FLOW" "$(timeout_flow)" "beta"
if grep -q '\[maestro\] TIMEOUT — flow=beta budget=2s' "$TMP_DIR/out.txt"; then
  ok "overrun (marker names the flow and its budget)"
else
  bad "overrun — no '[maestro] TIMEOUT — flow=beta budget=2s' marker in the output"
  sed 's/^/        /' "$TMP_DIR/out.txt" >&2
fi
# alpha + beta ran; gamma must not have — the remaining flows are not evidence.
check "overrun" "maestro invocations before the stop" "$(invocations)" "2"
if grep -q 'not a demo failure' "$TMP_DIR/out.txt"; then
  ok "overrun (the marker says a clock verdict is not a demo failure)"
else
  bad "overrun — the TIMEOUT marker no longer distinguishes itself from a demo failure"
fi

echo "--- 4. the REAL catalogs classify as aggregators, category flows do not ---"
for plat in android ios; do
  real="$REPO_ROOT/.maestro/$plat/catalog.yaml"
  if [ ! -f "$real" ]; then
    bad "$plat catalog — $real is missing"
    continue
  fi
  n="$(bash -c 'set -u; source "$1"; maestro_aggregator_flows "$2"' _ "$MAESTRO_LIB" "$real" | grep -c .)"
  if [ "${n:-0}" -ge 2 ]; then
    ok "$plat catalog.yaml expands to $n per-flow budgets"
  else
    bad "$plat catalog.yaml no longer expands (got ${n:-0} children) — the whole catalog is back on ONE budget"
  fi
  # Every child it names must exist, or the leg dies at rc=2 on a real run.
  while IFS= read -r child; do
    [ -n "$child" ] || continue
    [ -f "$child" ] || bad "$plat catalog.yaml references a missing flow: $child"
  done < <(bash -c 'set -u; source "$1"; maestro_aggregator_flows "$2"' _ "$MAESTRO_LIB" "$real")
done
for leaf in "$REPO_ROOT/.maestro/android/3d-basics.yaml" "$REPO_ROOT/.maestro/ios/ar.yaml"; do
  [ -f "$leaf" ] || continue
  if bash -c 'set -u; source "$1"; maestro_aggregator_flows "$2"' _ "$MAESTRO_LIB" "$leaf" >/dev/null 2>&1; then
    bad "$(basename "$leaf") is treated as an aggregator — its per-demo env would be lost"
  else
    ok "$(basename "$leaf") stays one invocation"
  fi
done

echo "--- 5. device-qa.sh reads the flow name out of the wrapper's output ---"
# Extract the real parser and feed it the real marker: the two live in
# different files, and this seam is exactly where a renamed token goes
# unnoticed until a release checkpoint reads "budget expired in ".
awk '/^timed_out_flow\(\) \{/,/^\}/' "$DEVICE_QA" > "$TMP_DIR/timed_out_flow.sh"
if [ ! -s "$TMP_DIR/timed_out_flow.sh" ]; then
  bad "device-qa.sh no longer defines timed_out_flow()"
else
  printf '[qa] TIMEOUT — flow=interaction exceeded its 900s budget; no demo failed, the clock did.\n' \
    > "$TMP_DIR/leg-output.txt"
  got="$(bash -c 'set -u; source "$1"; timed_out_flow "$2"' _ "$TMP_DIR/timed_out_flow.sh" "$TMP_DIR/leg-output.txt")"
  check "android marker" "parsed" "$got" "flow=interaction"
  printf '[ios-qa] TIMEOUT — flow=lighting exceeded its 900s budget; no demo failed, the clock did.\n' \
    > "$TMP_DIR/leg-output.txt"
  got="$(bash -c 'set -u; source "$1"; timed_out_flow "$2"' _ "$TMP_DIR/timed_out_flow.sh" "$TMP_DIR/leg-output.txt")"
  check "ios marker" "parsed" "$got" "flow=lighting"
  : > "$TMP_DIR/leg-output.txt"
  got="$(bash -c 'set -u; source "$1"; timed_out_flow "$2"' _ "$TMP_DIR/timed_out_flow.sh" "$TMP_DIR/leg-output.txt")"
  check "absent marker" "parsed" "$got" "an unnamed flow"
fi
# And the wrappers must still PRINT that marker on rc=124.
for wrapper in qa-android-demos.sh ios-device-qa.sh; do
  if grep -q 'TIMEOUT — flow=' "$SCRIPT_DIR/$wrapper"; then
    ok "$wrapper emits the TIMEOUT — flow= marker"
  else
    bad "$wrapper no longer emits the 'TIMEOUT — flow=' marker device-qa.sh parses"
  fi
done

echo "--- 6. device-qa.sh grades 'timeout' with the weight of 'failed' ---"
# A faithful replay of device-qa.sh's status loop, driven by the arm actually
# present in the script. Before #3141 that `case` had no default arm, so an
# unrecognised status incremented nothing and OVERALL stayed `passed`.
if ! grep -qE '^[[:space:]]*failed\|timeout\)' "$DEVICE_QA"; then
  bad "device-qa.sh no longer grades 'timeout' alongside 'failed' — a timed-out REQUIRED leg would grade 'passed'"
fi
if ! grep -q 'FAILED_STATUSES = ("failed", "timeout")' "$DEVICE_QA"; then
  bad "device-qa.sh releaseGate no longer counts 'timeout' as a blocking failure"
fi
if ! grep -q 'failed|timeout|skipped) tag=' "$DEVICE_QA"; then
  bad "device-qa.sh no longer flags a non-passing advisory 'timeout' leg in the printed report"
fi
if ! grep -q 'record android timeout ' "$DEVICE_QA" || ! grep -q 'record ios timeout ' "$DEVICE_QA"; then
  bad "device-qa.sh no longer records the android/ios timeout verdict (rc=124 would read as a demo failure again)"
fi
# The releaseGate half, end to end: a required leg on `timeout` must block.
gate="$(python3 - <<'PY'
platforms = [{"platform": "web", "status": "timeout", "advisory": False}]
FAILED_STATUSES = ("failed", "timeout")
blocking_failed = [p for p in platforms if p["status"] in FAILED_STATUSES and not p["advisory"]]
print("blocked" if blocking_failed else "not-blocked")
PY
)"
check "releaseGate" "required leg on timeout" "$gate" "blocked"

if [ "$fail" -eq 0 ]; then
  echo "ALL SELFTESTS PASSED"
else
  echo "SELFTEST FAILURES" >&2
  exit 1
fi
