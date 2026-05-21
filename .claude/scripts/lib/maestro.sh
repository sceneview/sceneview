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
# Each helper falls back gracefully: if Maestro cannot be installed (offline,
# unsupported host) the functions return non-zero so callers can warn+skip
# rather than hard-fail.

set -o pipefail

# --- Pinned version --------------------------------------------------------
# Maestro is pinned so a CI run and a local run exercise byte-identical tool
# behaviour. Bump deliberately (and re-validate the flows) — never float.
# 1.39.0 is the release current at the time slice #1562 landed.
MAESTRO_VERSION="1.39.0"

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

# maestro_run <flow.yaml> [extra args...]
# Thin wrapper around `maestro test` that first ensures Maestro is installed.
# Extra args are forwarded verbatim (e.g. `--format junit --output result.xml`).
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
  # Bound the run: a flow that hangs (e.g. waiting on an element that never
  # appears) must fail fast instead of silently eating the CI job budget
  # (#1560). `timeout` exit 124 propagates as a normal non-zero failure.
  timeout "${MAESTRO_TEST_TIMEOUT:-900}" "$MAESTRO_BIN" test "$flow" "$@"
}
