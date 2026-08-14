#!/usr/bin/env bash
# maestro.sh — shared helpers for Maestro (https://maestro.dev), the UI-test
# tool that drives `samples/android-demo` like a real user for device-QA.
#
# Maestro is the Android/iOS leg of the autonomous device-QA harness (umbrella
# #1560, slice #1562). It runs YAML flows under `.maestro/` that tap, swipe,
# scroll and navigate the demo apps, then assert no crash / no FATAL.
#
# This helper is the Maestro analogue of `android-cli.sh`: it auto-installs a
# **pinned** Maestro version to a user-local path on first use, never touches
# the shell rc, and is CI-safe ($CI honoured — CI installs Maestro as a prior
# workflow step rather than fetching binaries mid-job).
#
# Usage from another script:
#     SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#     source "$SCRIPT_DIR/lib/maestro.sh"
#     maestro_ensure                       # bootstraps install if missing
#     maestro_run .maestro/android/catalog.yaml [extra maestro args...]
#
# `maestro_run` budgets PER FLOW (MAESTRO_FLOW_TIMEOUT, default 900 s): an
# aggregator such as `catalog.yaml` is split into its per-category flows and
# each is run as its own `maestro test` under its own budget (#3141). On an
# expired budget it returns 124 and sets MAESTRO_TIMEOUT_FLOW to the flow name,
# so callers can report a `timeout` verdict instead of an anonymous failure.
#
# Each helper falls back gracefully: if Maestro cannot be installed (offline,
# unsupported host) the functions return non-zero so callers can warn+skip
# rather than hard-fail.

set -o pipefail

# --- Pinned version --------------------------------------------------------
# Maestro is pinned so a CI run and a local run exercise byte-identical tool
# behaviour. Bump deliberately (and re-validate the flows) — never float.
# KEEP IN SYNC with the CI install step in .github/workflows/device-qa.yml.
#
# 2.6.1 (2026-06): CI had been floating on latest all along (its installer
# never exported MAESTRO_VERSION), so android CI was already green on 2.6.x;
# the iOS catalog was re-validated on 2.6.1 locally (2026-07-09), and no
# .maestro/ flow uses runScript/evalScript, so the 2.x Rhino→GraalJS removal
# is a non-event for this repo. 2.5+ also brings the ~40% faster gRPC Android
# driver and reliable parallel iOS runs (qa-efficiency plan, 2026-07).
MAESTRO_VERSION="2.6.1"

# Maestro installs under ~/.maestro by its official installer; the binary is
# at ~/.maestro/bin/maestro. We keep that convention so a user who already has
# Maestro on PATH is reused as-is.
MAESTRO_HOME_DIR="${MAESTRO_HOME_DIR:-$HOME/.maestro}"

# Reset state so successive `source`s in long-lived shells don't inherit a
# stale binary path.
MAESTRO_BIN=""

# Resolve the `maestro` binary path. Returns 0 on success, sets MAESTRO_BIN.
maestro_locate() {
  MAESTRO_BIN=""
  if command -v maestro >/dev/null 2>&1; then
    MAESTRO_BIN="$(command -v maestro)"
    return 0
  fi
  if [[ -x "$MAESTRO_HOME_DIR/bin/maestro" ]]; then
    MAESTRO_BIN="$MAESTRO_HOME_DIR/bin/maestro"
    return 0
  fi
  return 1
}

# Echo the installed Maestro version (best-effort, empty string if unknown).
maestro_installed_version() {
  maestro_locate || return 1
  # `maestro --version` prints just the semver on a line.
  "$MAESTRO_BIN" --version 2>/dev/null | head -n1 | tr -d ' \r'
}

# Ensure Maestro is installed and on the pinned version. Auto-installs to
# ~/.maestro without touching the shell rc. Honours $CI: in CI we never
# auto-download — the workflow installs Maestro as a prior step.
#
# Returns 0 when a usable `maestro` binary is available (MAESTRO_BIN set),
# non-zero otherwise.
maestro_ensure() {
  if maestro_locate; then
    local have; have="$(maestro_installed_version)"
    if [[ "$have" != "$MAESTRO_VERSION" ]]; then
      echo "[maestro] found Maestro $have, flows are pinned to $MAESTRO_VERSION." >&2
      echo "[maestro] proceeding with $have — bump MAESTRO_VERSION in maestro.sh if intentional." >&2
    fi
    return 0
  fi
  if [[ -n "${CI:-}" ]]; then
    echo "[maestro] not installed and CI=1 — install Maestro via your workflow before calling this helper." >&2
    return 1
  fi
  # The official installer respects MAESTRO_VERSION when exported, so a pinned
  # install is reproducible. It writes to ~/.maestro/bin and prints a PATH hint
  # we deliberately ignore (we resolve the absolute path ourselves).
  echo "[maestro] installing Maestro $MAESTRO_VERSION to $MAESTRO_HOME_DIR" >&2
  if ! command -v curl >/dev/null 2>&1; then
    echo "[maestro] curl is required to install Maestro" >&2
    return 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "[maestro] WARNING: a JDK is required to run Maestro — install one before maestro_run" >&2
  fi
  # The canonical installer host is `get.maestro.mobile.dev` (308-redirects to
  # the GCS-hosted install.sh). `get.maestro.dev` does NOT resolve — it broke
  # the Device QA CI Maestro install (run 25979438767). `set -o pipefail` at
  # the top of this file makes a curl DNS failure propagate through the pipe.
  if ! MAESTRO_VERSION="$MAESTRO_VERSION" curl -fsSL "https://get.maestro.mobile.dev" | bash >&2; then
    echo "[maestro] install failed (offline or unsupported host?)" >&2
    return 1
  fi
  if ! maestro_locate; then
    echo "[maestro] installer ran but the binary was not found under $MAESTRO_HOME_DIR/bin" >&2
    return 1
  fi
  echo "[maestro] installed: $(maestro_installed_version)" >&2
}

# --- The budget is PER FLOW, never per invocation (#3141) ------------------
# MAESTRO_TIMEOUT_FLOW carries the name of the flow whose budget expired —
# read by qa-android-demos.sh / ios-device-qa.sh so their verdict can say
# WHICH flow ran out of clock. Empty whenever the last run did not time out.
# shellcheck disable=SC2034  # read by the qa wrappers that source this lib.
MAESTRO_TIMEOUT_FLOW=""

# Echo the per-flow budget in seconds. MAESTRO_FLOW_TIMEOUT is the name that
# means what it does; MAESTRO_TEST_TIMEOUT is honoured as the legacy alias.
maestro_flow_budget() {
  echo "${MAESTRO_FLOW_TIMEOUT:-${MAESTRO_TEST_TIMEOUT:-900}}"
}

# maestro_aggregator_flows <flow.yaml>
# Echo, one per line, the sibling flows of a PURE AGGREGATOR — a flow whose
# every step (after the `---` header separator) is the scalar form
#     - runFlow: <sibling>.yaml
# which is exactly how `.maestro/{android,ios}/catalog.yaml` chains the
# per-category flows. Returns 1 and echoes nothing for anything else.
#
# The per-category flows are deliberately NOT aggregators by this rule: their
# steps use the MAPPING form (`- runFlow:` + `file:` + `env:`) to pass per-demo
# variables, so they stay a single `maestro test` invocation and keep their
# per-demo isolation. Expansion is one level deep on purpose — the point is to
# split the catalog into the units a human already reasons about, not to
# re-implement Maestro's runner.
maestro_aggregator_flows() {
  local flow="$1" dir
  dir="$(dirname "$flow")"
  awk -v dir="$dir" '
    /^---[[:space:]]*$/ { body = 1; next }
    !body { next }
    /^-[[:space:]]/ {
      steps++
      if ($0 ~ /^-[[:space:]]+runFlow:[[:space:]]+[^[:space:]#]+\.yaml[[:space:]]*$/) {
        child = $0
        sub(/^-[[:space:]]+runFlow:[[:space:]]+/, "", child)
        sub(/[[:space:]]+$/, "", child)
        children[++n] = dir "/" child
      }
    }
    END {
      # Every step must be a scalar runFlow, or splitting the file would drop
      # the steps that are not — a partial run graded as a full one.
      if (n == 0 || n != steps) exit 1
      for (i = 1; i <= n; i++) print children[i]
    }
  ' "$flow"
}

# maestro_run_flow <flow.yaml> [extra args...]
# ONE `maestro test` invocation under ONE per-flow budget. Sets
# MAESTRO_TIMEOUT_FLOW and returns 124 when the budget expires.
maestro_run_flow() {
  local flow="$1"; shift || true
  # Standalone-safe: an unset MAESTRO_BIN under `set -u` aborts the caller, and
  # this file's own history is exactly that failure mode (see the bash 3.2 note
  # below). maestro_run always ensures first; this covers a direct call.
  if [[ -z "${MAESTRO_BIN:-}" ]]; then
    maestro_ensure || return 1
  fi
  local budget; budget="$(maestro_flow_budget)"
  local name; name="$(basename "$flow" .yaml)"
  # Pin Maestro to the leased device: maestro does NOT honor ANDROID_SERIAL,
  # and with several adb devices connected (e.g. a personal phone on wireless
  # debugging next to the QA emulator) it silently drives the wrong one — the
  # emulator-first rule must hold even on multi-device hosts.
  local -a device_args=()
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    device_args=(--device "$ANDROID_SERIAL")
  fi
  # Bound the run: a flow that hangs (e.g. waiting on an element that never
  # appears) must fail fast instead of silently eating the CI job budget
  # (#1560). `timeout` exit 124 is turned into a NAMED timeout below rather
  # than an anonymous non-zero failure (#3141).
  # On macOS, `timeout` is not available by default — use `gtimeout` (from
  # homebrew coreutils) if present, otherwise run unbounded (#2184).
  # ${device_args[@]+…}: macOS bash 3.2 + `set -u` rejects expanding an EMPTY
  # array — on the iOS path ANDROID_SERIAL is unset, device_args stays empty,
  # and the bare expansion aborted the whole harness here. Worse, measured on
  # bash 3.2: when the aborting call sits in a `||`-guarded list AND an EXIT
  # trap is set, the script dies with exit 0 (the `||` already reset `$?`), so
  # the ios leg graded PASSED without running a single Maestro step — which is
  # why run_ios in device-qa.sh also requires the positive PASS marker.
  local rc=0
  if command -v timeout >/dev/null 2>&1; then
    timeout "$budget" "$MAESTRO_BIN" ${device_args[@]+"${device_args[@]}"} test "$flow" "$@" || rc=$?
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$budget" "$MAESTRO_BIN" ${device_args[@]+"${device_args[@]}"} test "$flow" "$@" || rc=$?
  else
    "$MAESTRO_BIN" ${device_args[@]+"${device_args[@]}"} test "$flow" "$@" || rc=$?
  fi
  if [[ "$rc" -eq 124 ]]; then
    MAESTRO_TIMEOUT_FLOW="$name"
    # Machine-readable: device-qa.sh greps this exact `flow=` token to name the
    # flow in its verdict. A timeout is NOT a demo failure — say so here, once,
    # so no reader has to infer it from a bare exit code.
    echo "[maestro] TIMEOUT — flow=$name budget=${budget}s — the flow did not finish; this is a clock verdict, not a demo failure." >&2
  fi
  return "$rc"
}

# maestro_run <flow.yaml> [extra args...]
# Runs a flow under a per-flow time budget, first ensuring Maestro is
# installed. Extra args are forwarded verbatim to every invocation (e.g.
# `--udid …`, `--format junit --output result.xml`).
#
# An aggregator flow (catalog.yaml) is expanded and its children are run one
# `maestro test` at a time, each under its OWN budget. Before #3141 the whole
# catalog shared a single 900 s bound, so a full android or ios leg could only
# ever end at rc=124 — three of seven flows on android, one of eight on iOS,
# with every executed step COMPLETED. The bound now scales with the catalog
# while a single hung demo still fails fast, which is why the bound exists.
maestro_run() {
  local flow="$1"; shift || true
  if [[ -z "$flow" ]]; then
    echo "[maestro] maestro_run: a flow path is required" >&2
    return 2
  fi
  if [[ ! -e "$flow" ]]; then
    echo "[maestro] maestro_run: flow not found: $flow" >&2
    return 2
  fi
  maestro_ensure || return 1
  MAESTRO_TIMEOUT_FLOW=""

  local children_out=""
  if ! children_out="$(maestro_aggregator_flows "$flow")"; then
    children_out=""
  fi
  if [[ -z "$children_out" ]]; then
    maestro_run_flow "$flow" "$@"
    return $?
  fi

  local -a children=()
  local child count=0
  while IFS= read -r child; do
    [[ -n "$child" ]] || continue
    if [[ ! -e "$child" ]]; then
      echo "[maestro] maestro_run: aggregator $flow references a missing flow: $child" >&2
      return 2
    fi
    children[count]="$child"
    count=$((count + 1))
  done <<< "$children_out"

  echo "[maestro] $(basename "$flow") is an aggregator of $count flow(s) — each runs under its own $(maestro_flow_budget)s budget (#3141)" >&2
  local rc=0 i=0
  while [[ "$i" -lt "$count" ]]; do
    child="${children[$i]}"
    rc=0
    maestro_run_flow "$child" "$@" || rc=$?
    if [[ "$rc" -ne 0 ]]; then
      # Stop at the first red child, exactly as one `maestro test` over the
      # aggregator would: the remaining flows are not evidence either way.
      echo "[maestro] flow $(basename "$child" .yaml) exited rc=$rc — stopping the aggregator run ($((count - i - 1)) flow(s) not run)" >&2
      return "$rc"
    fi
    i=$((i + 1))
  done
  return 0
}
