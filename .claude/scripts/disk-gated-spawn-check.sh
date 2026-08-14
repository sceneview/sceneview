#!/usr/bin/env bash
# disk-gated-spawn-check.sh — pre-flight safety gate before spawning agents
#
# Why: 2026-05-14 multi-agent orchestrator drove free disk down to 8.2 GB.
# The 15 GB alert threshold used to live in a memory note; that note is gone,
# so this script IS the rule now, not a copy of one:
# source it in any agent-spawn loop and bail before piling on more
# worktrees / Gradle daemons / emulator AVDs.
#
# Recommended usage:
#
#   if bash .claude/scripts/disk-gated-spawn-check.sh; then
#       # spawn the next batch
#   else
#       # run gradle-cache-cleanup.sh + worktree-auto-prune.sh first
#   fi
#
# Or capture the numeric value for logging:
#
#   eval "$(bash .claude/scripts/disk-gated-spawn-check.sh || true)"
#   echo "free=$DISK_FREE_GB"
#
# Usage:
#   bash .claude/scripts/disk-gated-spawn-check.sh                  # default 15 GB
#   bash .claude/scripts/disk-gated-spawn-check.sh --min-gb=20      # custom
#   bash .claude/scripts/disk-gated-spawn-check.sh --min-gb 20      # space form
#   bash .claude/scripts/disk-gated-spawn-check.sh --quiet          # only emit DISK_FREE_GB= line
#
# Tracking: https://github.com/sceneview/sceneview/issues/1242
#
# Exit codes:
#   0 = free space >= threshold (safe to spawn)
#   1 = below threshold (do NOT spawn; cleanup first)

set -euo pipefail

MIN_GB=15
QUIET=false

while [ $# -gt 0 ]; do
    case "$1" in
        --min-gb=*) MIN_GB="${1#--min-gb=}"; shift ;;
        --min-gb)   MIN_GB="${2:-15}"; shift 2 ;;
        --quiet)    QUIET=true; shift ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown flag: $1" >&2
            exit 2
            ;;
    esac
done

# Validate MIN_GB is a positive number (integer or float)
case "$MIN_GB" in
    ''|*[!0-9.]*)
        echo "Invalid --min-gb value: $MIN_GB" >&2
        exit 2
        ;;
esac

# Portable disk-free: df -k on macOS and Linux both yield 1K blocks in
# column 4. Pulling from `/` covers the typical case where worktrees,
# Gradle caches, and emulators all share the same volume on dev macs.
FREE_GB=$(df -k / | awk 'NR==2 { printf "%.2f", $4 / 1024 / 1024 }')

# stdout: machine-readable line (always present for easy capture)
echo "DISK_FREE_GB=$FREE_GB"

# awk again for portable float comparison (no bc, no [[ )
if awk -v f="$FREE_GB" -v t="$MIN_GB" 'BEGIN { exit !(f >= t) }'; then
    [ "$QUIET" = "true" ] || echo "OK: $FREE_GB GB free >= $MIN_GB GB threshold"
    exit 0
else
    {
        echo ""
        echo "WARNING: disk free is ${FREE_GB} GB — below ${MIN_GB} GB threshold."
        echo "Run cleanup BEFORE spawning more agents:"
        echo "  bash .claude/scripts/gradle-cache-cleanup.sh"
        echo "  bash .claude/scripts/worktree-auto-prune.sh --yes --keep \"\$(git rev-parse --show-toplevel)\""
    } >&2
    exit 1
fi
