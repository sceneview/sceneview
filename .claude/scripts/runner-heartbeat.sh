#!/usr/bin/env bash
# Self-hosted runner heartbeat — pings GitHub every 5 min to confirm this Mac
# is up, has room to work, AND that the actions/runner service is online.
# Updates two repo variables:
#
#   SELF_HOSTED_MACOS_LAST_SEEN — ISO 8601 UTC timestamp, refreshed while usable
#   SELF_HOSTED_MACOS_ONLINE    — "true" while runner.status == "online"
#                                 AND free disk >= RUNNER_MIN_FREE_DISK_GB
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
# WHY THE DISK GATE (#2816)
#   The promise of this pilot is transparent fallback when the Mac cannot take
#   a job. Asleep, off and runner-process-dead were covered; "disk full" was
#   not. On PR #2766 a Flutter iOS job routed here with ~4.6 GiB free, the
#   2.1 GiB SDK download plus extraction hit ENOSPC, and the job died at
#   exit 50 with a *truncated* Flutter.xcframework left in the runner tool
#   cache — a poisoned cache that would have silently corrupted every later
#   self-hosted Flutter job. A resource-starved host must produce a fallback,
#   never a red that reads like a code failure.
#
#   Default threshold: 15 GiB. It is the sum of the two knowns, not a round
#   number picked for looks — a Flutter setup alone peaks near 4.5 GiB, and the
#   host's 6 GiB local-build gate must stay clear so an interactive session is
#   never starved by CI; the remainder is headroom for Xcode/Gradle scratch.
#   Override per host with RUNNER_MIN_FREE_DISK_GB.
#
#   The gate runs BEFORE the GitHub API probe: below threshold there is nothing
#   the runner's status could say that would make this host usable.
#
# Installed by .claude/scripts/setup-self-hosted-runner.sh as a launchd job.

set -euo pipefail

REPO="${GITHUB_REPO:-sceneview/sceneview}"
RUNNER_NAME="${RUNNER_NAME:-sceneview-mac-$(hostname -s)}"
MIN_FREE_DISK_GB="${RUNNER_MIN_FREE_DISK_GB:-15}"
DISK_MOUNT="${RUNNER_DISK_MOUNT:-/}"
NOW="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

if ! command -v gh >/dev/null 2>&1; then
  echo "[heartbeat] gh CLI not on PATH — skipping" >&2
  exit 0
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "[heartbeat] gh not authenticated — skipping" >&2
  exit 0
fi

mark_offline() {
  gh variable set SELF_HOSTED_MACOS_ONLINE --repo "${REPO}" --body "false" >/dev/null
}

# Free space on the runner volume, in MiB (integer). `df -k` is POSIX and gives
# enough resolution to report a sane decimal; `df -g` truncates to whole GiB.
free_mib() {
  df -k "${DISK_MOUNT}" 2>/dev/null | awk 'NR==2 { print int($4 / 1024); exit }'
}

FREE_MIB="$(free_mib || echo "")"

if [[ -z "${FREE_MIB}" ]]; then
  # Unreadable df is treated as unusable: the whole point of the gate is that a
  # host we cannot vouch for must not take a job.
  mark_offline
  echo "[heartbeat] ${NOW} — could not read free disk on ${DISK_MOUNT}, marked OFFLINE"
  exit 0
fi

FREE_GB_LABEL="$(awk -v m="${FREE_MIB}" 'BEGIN { printf "%.1f", m / 1024 }')"
MIN_MIB=$(( MIN_FREE_DISK_GB * 1024 ))

if (( FREE_MIB < MIN_MIB )); then
  mark_offline
  echo "[heartbeat] ${NOW} — free disk ${FREE_GB_LABEL} GiB on ${DISK_MOUNT} < ${MIN_FREE_DISK_GB} GiB minimum, marked OFFLINE (jobs fall back to macos-15; raise with RUNNER_MIN_FREE_DISK_GB or free space)"
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
  echo "[heartbeat] ${NOW} — online (runner=${RUNNER_NAME}, free=${FREE_GB_LABEL} GiB)"
else
  mark_offline
  echo "[heartbeat] ${NOW} — runner status='${STATUS:-unknown}', marked OFFLINE"
fi
