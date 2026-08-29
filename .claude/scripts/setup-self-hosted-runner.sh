#!/usr/bin/env bash
# Self-hosted macOS GitHub Actions runner for SceneView (v3 — no spaces in path).
#
# WHY V3 (and not v2):
#   v2 installed to `~/Library/Application Support/sceneview-runner/`. macOS
#   convention, BUT that path contains a space. actions/runner generates step
#   scripts inside its `_work/_temp/` subtree and invokes them as
#   `/bin/bash -e <script-path>`. With a space in the path, bash splits the
#   argv and fails: `/bin/bash: /Users/.../Library/Application: No such file
#   or directory`. Every workflow step failed in 34 s. v3 puts the runner in
#   `~/sceneview-runner/` (no spaces, anywhere). Verified on the pilot
#   `bridge-ios-compile` PR #2204 run id 26418464635.
#
# WHY V2 (still relevant — kept from v2 history):
#   actions/runner's bundled svc.sh shells out to `launchctl load`, broken
#   on macOS 11+ for user-scoped LaunchAgents (`Load failed: 5: Input/output
#   error`). v2 bypassed svc.sh and uses the modern `launchctl bootstrap`
#   API. v3 keeps that, only changes the install path.
#
# MIGRATION FROM V2
#   The installer detects an existing v2 install at the old spaces-path,
#   de-registers it, removes its LaunchAgent, then installs fresh at the
#   new path. The old directory is left in place — remove manually with
#   `rm -rf ~/Library/Application\ Support/sceneview-runner` if desired.
#
# WHAT IT INSTALLS
#   ~/sceneview-runner/                                       actions/runner
#   ~/Library/LaunchAgents/io.github.sceneview.runner.plist          runner
#   ~/Library/LaunchAgents/io.github.sceneview.runner-heartbeat.plist heartbeat
#
# OPT IN A WORKFLOW (one line per job in any .github/workflows/*.yml)
#   The expression is deliberately NOT reproduced here — it also carries a
#   fork-PR clause, and a copy in a comment is a copy that drifts. Take it
#   verbatim from the `self-hosted-runner` skill, or from
#   .github/workflows/bridge-ios-compile.yml, which holds the rationale.
#
# MACHINE HYGIENE (#3051) — what this script does and does not isolate
#   The runner is a LaunchAgent in the login user's GUI domain, so a job runs
#   as that user and inherits its filesystem. What the installer isolates:
#     - GRADLE_USER_HOME points at ~/sceneview-runner/gradle-home, so CI and
#       interactive builds never share a cache (a poisoned-cache red is then
#       attributable to the run that wrote it).
#   What it deliberately does NOT do, because each is a machine-level decision
#   the maintainer takes once, outside any script:
#     - run the runner under a dedicated non-admin macOS user (the installer
#       needs `gh auth` to mint the registration token, but the *runner* does
#       not — register from the admin account, then move the LaunchAgent);
#     - keep `gh` logins and SSH keys out of that user's environment — a
#       runner never needs to push;
#     - audit what ~/sceneview-runner/ can read outside itself.
#   Fork PRs never reach this machine regardless: the workflow expression
#   routes them to a hosted runner, and the repository's fork-PR approval
#   policy is `all_external_contributors` (verified 2026-08-22 via
#   `gh api repos/sceneview/sceneview/actions/permissions/fork-pr-contributor-approval`).
#
# USAGE
#   bash .claude/scripts/setup-self-hosted-runner.sh             # install
#   bash .claude/scripts/setup-self-hosted-runner.sh --check     # status
#   bash .claude/scripts/setup-self-hosted-runner.sh --uninstall # remove

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
REPO="sceneview/sceneview"
RUNNER_VERSION="2.334.0"   # baseline; the runner auto-updates after first connect
RUNNER_LABEL="sceneview-mac"
RUNNER_HOME="${HOME}/sceneview-runner"
# CI gets its own Gradle home (#3051): the runner shares the login user's filesystem, and
# with the default ~/.gradle an interactive build and a CI build read and write the same
# dependency/distribution cache. A poisoned or half-written cache then produces a red run
# nothing in the PR explains. Costs one cold download per dependency, once.
RUNNER_GRADLE_HOME="${RUNNER_HOME}/gradle-home"
RUNNER_HOME_V2_LEGACY="${HOME}/Library/Application Support/sceneview-runner"
RUNNER_NAME="sceneview-mac-$(hostname -s)"

# Runner LaunchAgent (replaces broken svc.sh)
RUNNER_PLIST="${HOME}/Library/LaunchAgents/io.github.sceneview.runner.plist"
RUNNER_PLIST_LABEL="io.github.sceneview.runner"

# Heartbeat LaunchAgent (unchanged from v1)
HEARTBEAT_PLIST="${HOME}/Library/LaunchAgents/io.github.sceneview.runner-heartbeat.plist"
HEARTBEAT_LABEL="io.github.sceneview.runner-heartbeat"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEARTBEAT_SCRIPT="${SCRIPT_DIR}/runner-heartbeat.sh"

# Disk gate (#2816): the heartbeat refuses to mark the runner online below this
# many free GiB on DISK_MOUNT, so a near-full host produces a transparent
# fallback to macos-15 instead of an ENOSPC red mid-job. 15 GiB = a Flutter
# setup's ~4.5 GiB peak + the host's 6 GiB local-build gate + scratch headroom.
MIN_FREE_DISK_GB="${RUNNER_MIN_FREE_DISK_GB:-15}"
DISK_MOUNT="${RUNNER_DISK_MOUNT:-/}"

UID_NUM="$(id -u)"
GUI_DOMAIN="gui/${UID_NUM}"

ACTION="${1:-install}"

# ── Helpers ───────────────────────────────────────────────────────────────────
log() { printf '%s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }

# Reports whether a LaunchAgent is loaded in the user GUI domain.
print_service_status() {
  local label="$1"
  if launchctl print "${GUI_DOMAIN}/${label}" >/dev/null 2>&1; then
    log "  loaded: yes"
    launchctl print "${GUI_DOMAIN}/${label}" 2>/dev/null \
      | grep -E "state =|last exit code" | head -3 | sed 's/^/    /'
  else
    log "  loaded: no"
  fi
}

# ── --check ───────────────────────────────────────────────────────────────────
if [[ "${ACTION}" == "--check" ]]; then
  log "Runner home:     ${RUNNER_HOME}"
  if [[ -f "${RUNNER_HOME}/.runner" ]]; then
    log "  configured:    yes"
    sed 's/^/    /' "${RUNNER_HOME}/.runner" 2>/dev/null || true
  else
    log "  configured:    no"
  fi

  log ""
  log "Runner LaunchAgent: ${RUNNER_PLIST}"
  print_service_status "${RUNNER_PLIST_LABEL}"

  log ""
  log "Heartbeat LaunchAgent: ${HEARTBEAT_PLIST}"
  print_service_status "${HEARTBEAT_LABEL}"

  log ""
  log "Free disk (heartbeat gate, #2816):"
  free_mib_check="$(df -k "${DISK_MOUNT}" 2>/dev/null | awk 'NR==2 { print int($4 / 1024); exit }')"
  if [[ -n "${free_mib_check}" ]]; then
    free_gb_check="$(awk -v m="${free_mib_check}" 'BEGIN { printf "%.1f", m / 1024 }')"
    log "  ${DISK_MOUNT}: ${free_gb_check} GiB free (minimum ${MIN_FREE_DISK_GB} GiB)"
    if (( free_mib_check < MIN_FREE_DISK_GB * 1024 )); then
      log "  verdict:     BELOW threshold — heartbeat marks ONLINE=false, jobs use macos-15"
    else
      log "  verdict:     ok"
    fi
  else
    log "  ${DISK_MOUNT}: unreadable — heartbeat marks ONLINE=false"
  fi

  if [[ -f /tmp/sceneview-runner-heartbeat.log ]]; then
    log ""
    log "Recent heartbeat log:"
    tail -3 /tmp/sceneview-runner-heartbeat.log | sed 's/^/  /'
  fi

  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    log ""
    log "Runner status (GitHub API):"
    if ! gh api "/repos/${REPO}/actions/runners" \
        --jq ".runners[] | select(.name == \"${RUNNER_NAME}\") | \"  \\(.name): \\(.status)\"" \
        2>/dev/null; then
      log "  (API call failed)"
    fi

    log ""
    log "Repo variables (${REPO}):"
    for var in SELF_HOSTED_MACOS_ONLINE SELF_HOSTED_MACOS_LAST_SEEN; do
      val="$(gh variable get "${var}" --repo "${REPO}" 2>/dev/null || echo '<unset>')"
      log "  ${var}=${val}"
    done
  fi
  exit 0
fi

# ── --uninstall ───────────────────────────────────────────────────────────────
if [[ "${ACTION}" == "--uninstall" ]]; then
  log "Unloading runner LaunchAgent..."
  launchctl bootout "${GUI_DOMAIN}/${RUNNER_PLIST_LABEL}" 2>/dev/null || true
  rm -f "${RUNNER_PLIST}"

  log "Unloading heartbeat LaunchAgent..."
  launchctl bootout "${GUI_DOMAIN}/${HEARTBEAT_LABEL}" 2>/dev/null || true
  rm -f "${HEARTBEAT_PLIST}"

  # Kill any rogue run.sh started outside launchd (e.g., v1 nohup migration)
  pkill -f "${RUNNER_HOME}/run.sh" 2>/dev/null || true

  if [[ -f "${RUNNER_HOME}/config.sh" ]]; then
    log "De-registering runner from GitHub..."
    REM_TOKEN="$(gh api -X POST "/repos/${REPO}/actions/runners/remove-token" --jq .token 2>/dev/null || echo "")"
    if [[ -n "${REM_TOKEN}" ]]; then
      (cd "${RUNNER_HOME}" && ./config.sh remove --token "${REM_TOKEN}" 2>/dev/null || true)
    fi
  fi

  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh variable set SELF_HOSTED_MACOS_ONLINE --repo "${REPO}" --body "false" >/dev/null 2>&1 || true
  fi

  log "Uninstalled. Runner files left in ${RUNNER_HOME} — remove manually if desired."
  exit 0
fi

# ── install ───────────────────────────────────────────────────────────────────
if [[ "${ACTION}" != "install" ]]; then
  err "Unknown action: ${ACTION}"
  err "Usage: bash $(basename "$0") [install|--check|--uninstall]"
  exit 1
fi

# Pre-flight
if [[ "$(uname -s)" != "Darwin" ]]; then
  err "This installer is macOS-only."
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  err "gh CLI not found. Install with: brew install gh"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  err "gh not authenticated. Run: gh auth login --scopes 'repo,workflow'"
  exit 1
fi

# v2 -> v3 migration: if a v2 install exists at the spaces-path, unload its
# LaunchAgent, de-register the runner, leave its files in place for manual
# removal. The new install then proceeds at the v3 path.
if [[ -f "${RUNNER_HOME_V2_LEGACY}/.runner" ]]; then
  log "Detected v2 install at ${RUNNER_HOME_V2_LEGACY} — migrating to v3 path..."
  launchctl bootout "${GUI_DOMAIN}/${RUNNER_PLIST_LABEL}" 2>/dev/null || true
  launchctl bootout "${GUI_DOMAIN}/${HEARTBEAT_LABEL}" 2>/dev/null || true
  pkill -f "${RUNNER_HOME_V2_LEGACY}/run.sh" 2>/dev/null || true
  REM_TOKEN_V2="$(gh api -X POST "/repos/${REPO}/actions/runners/remove-token" --jq .token 2>/dev/null || echo "")"
  if [[ -n "${REM_TOKEN_V2}" ]]; then
    (cd "${RUNNER_HOME_V2_LEGACY}" && ./config.sh remove --token "${REM_TOKEN_V2}" 2>/dev/null || true)
  fi
  log "  v2 runner de-registered. Old files left at ${RUNNER_HOME_V2_LEGACY} — remove manually if desired."
fi

ARCH="$(uname -m)"
case "${ARCH}" in
  arm64)  RUNNER_ARCH="osx-arm64" ;;
  x86_64) RUNNER_ARCH="osx-x64"   ;;
  *) err "Unsupported arch: ${ARCH}"; exit 1 ;;
esac

mkdir -p "${RUNNER_HOME}"
cd "${RUNNER_HOME}"

# 1. Download runner if missing (the runner self-updates after first connect)
if [[ ! -f "./run.sh" ]]; then
  TARBALL="actions-runner-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
  URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${TARBALL}"
  log "Downloading ${TARBALL}..."
  curl -fsSL -o "${TARBALL}" "${URL}"
  tar xzf "${TARBALL}"
  rm "${TARBALL}"
fi

# 2. Register the runner if not already registered
if [[ ! -f ".runner" ]]; then
  log "Fetching runner registration token..."
  REG_TOKEN="$(gh api -X POST "/repos/${REPO}/actions/runners/registration-token" --jq .token)"
  ./config.sh \
    --url "https://github.com/${REPO}" \
    --token "${REG_TOKEN}" \
    --name "${RUNNER_NAME}" \
    --labels "${RUNNER_LABEL},self-hosted,macOS,${ARCH}" \
    --work _work \
    --unattended \
    --replace
fi

# 3. Kill any rogue run.sh outside launchd (e.g., v1 nohup migration)
#    so the launchd-managed instance can take over the registration cleanly.
pkill -f "${RUNNER_HOME}/run.sh" 2>/dev/null || true
sleep 1

# 4. Write the runner LaunchAgent plist (replaces broken svc.sh install)
mkdir -p "$(dirname "${RUNNER_PLIST}")"
cat > "${RUNNER_PLIST}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${RUNNER_PLIST_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${RUNNER_HOME}/run.sh</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${RUNNER_HOME}</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>${RUNNER_HOME}/runner.log</string>
  <key>StandardErrorPath</key><string>${RUNNER_HOME}/runner.log</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>HOME</key><string>${HOME}</string>
    <key>GRADLE_USER_HOME</key><string>${RUNNER_GRADLE_HOME}</string>
  </dict>
</dict>
</plist>
PLIST

# bootout the previous instance if any (idempotent), then bootstrap fresh.
# KeepAlive=true relaunches the runner after auto-update or crash. The runner
# manages its own update lifecycle (exit -> launchd restart -> new version).
launchctl bootout    "${GUI_DOMAIN}/${RUNNER_PLIST_LABEL}" 2>/dev/null || true
launchctl bootstrap  "${GUI_DOMAIN}" "${RUNNER_PLIST}"
launchctl enable     "${GUI_DOMAIN}/${RUNNER_PLIST_LABEL}"
launchctl kickstart  "${GUI_DOMAIN}/${RUNNER_PLIST_LABEL}"

# 5. Heartbeat LaunchAgent (same shape as v1, kept here for idempotency)
mkdir -p "$(dirname "${HEARTBEAT_PLIST}")"
cat > "${HEARTBEAT_PLIST}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${HEARTBEAT_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${HEARTBEAT_SCRIPT}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>StartInterval</key><integer>300</integer>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>GITHUB_REPO</key><string>${REPO}</string>
    <key>RUNNER_NAME</key><string>${RUNNER_NAME}</string>
    <key>RUNNER_MIN_FREE_DISK_GB</key><string>${MIN_FREE_DISK_GB}</string>
    <key>RUNNER_DISK_MOUNT</key><string>${DISK_MOUNT}</string>
    <key>HOME</key><string>${HOME}</string>
  </dict>
  <key>StandardOutPath</key><string>/tmp/sceneview-runner-heartbeat.log</string>
  <key>StandardErrorPath</key><string>/tmp/sceneview-runner-heartbeat.log</string>
</dict>
</plist>
PLIST

launchctl bootout    "${GUI_DOMAIN}/${HEARTBEAT_LABEL}" 2>/dev/null || true
launchctl bootstrap  "${GUI_DOMAIN}" "${HEARTBEAT_PLIST}"
launchctl enable     "${GUI_DOMAIN}/${HEARTBEAT_LABEL}"

log ""
log "Installed."
log "  Runner home:  ${RUNNER_HOME}"
log "  Runner name:  ${RUNNER_NAME}"
log "  Label:        ${RUNNER_LABEL}"
log "  LaunchAgent:  ${RUNNER_PLIST_LABEL} (KeepAlive, auto-restart on exit/update)"
log "  Heartbeat:    every 300s -> /tmp/sceneview-runner-heartbeat.log"
log "  Disk gate:    ONLINE only while ${DISK_MOUNT} has >= ${MIN_FREE_DISK_GB} GiB free (#2816)"
log ""
log "Verify with: bash $(basename "$0") --check"
