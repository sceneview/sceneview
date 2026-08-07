#!/usr/bin/env bash
# Self-hosted runner heartbeat — pings GitHub every 5 min to confirm this Mac
# is up AND the actions/runner service is online. Updates two repo variables:
#
#   SELF_HOSTED_MACOS_LAST_SEEN — ISO 8601 UTC timestamp, refreshed every run
#   SELF_HOSTED_MACOS_ONLINE    — "true" while runner.status == "online"
#
# Workflows pick the runner via a one-line expression in `runs-on`. It is NOT
# reproduced here: it also carries a fork-PR clause, and a copy in a comment is
# a copy that drifts. Take it verbatim from the `self-hosted-runner` skill, or
# from `.github/workflows/bridge-ios-compile.yml`, which holds the rationale.
#
# When this Mac is asleep / off the heartbeat stops; freshness of LAST_SEEN
# acts as the safety net (a stale ONLINE=true is overridden by an aged
# LAST_SEEN — see _pick-macos-runner.yml if you need timestamp arithmetic in a
# pre-job).
#
# Installed by .claude/scripts/setup-self-hosted-runner.sh as a launchd job.

set -euo pipefail

REPO="${GITHUB_REPO:-sceneview/sceneview}"
RUNNER_NAME="${RUNNER_NAME:-sceneview-mac-$(hostname -s)}"
NOW="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

if ! command -v gh >/dev/null 2>&1; then
  echo "[heartbeat] gh CLI not on PATH — skipping" >&2
  exit 0
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "[heartbeat] gh not authenticated — skipping" >&2
  exit 0
fi

# Probe runner liveness. If the actions/runner service is dead the heartbeat
# refuses to mark ONLINE=true — workflows fall back to macos-15.
STATUS="$(gh api "/repos/${REPO}/actions/runners" \
  --jq ".runners[] | select(.name==\"${RUNNER_NAME}\") | .status" \
  2>/dev/null || echo "")"

if [[ "${STATUS}" == "online" ]]; then
  gh variable set SELF_HOSTED_MACOS_ONLINE    --repo "${REPO}" --body "true"  >/dev/null
  gh variable set SELF_HOSTED_MACOS_LAST_SEEN --repo "${REPO}" --body "${NOW}" >/dev/null
  echo "[heartbeat] ${NOW} — online (runner=${RUNNER_NAME})"
else
  gh variable set SELF_HOSTED_MACOS_ONLINE    --repo "${REPO}" --body "false" >/dev/null
  echo "[heartbeat] ${NOW} — runner status='${STATUS:-unknown}', marked OFFLINE"
fi
