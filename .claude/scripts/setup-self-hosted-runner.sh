#!/usr/bin/env bash
# Self-hosted macOS GitHub Actions runner for SceneView.
#
# WHY
#   GitHub-hosted macos-15 runners cost ~10x ubuntu per-minute and have no KVM.
#   SceneView ships ~6 iOS/macOS jobs (ios.yml, bridge-ios-compile.yml,
#   rn-ios-compile.yml, app-store.yml × 2, render-tests.yml) plus a NIGHTLY-ONLY
#   iOS device-QA leg (#1601) that is too expensive to gate on every push.
#   A self-hosted runner on Thomas's M-series Mac runs the same workflows on
#   bare metal, faster, free — and when the Mac is asleep / off, workflows
#   fall back transparently to macos-15 via the heartbeat pattern.
#
# HOW IT WORKS
#   1. actions/runner downloads + registers with label `sceneview-mac`.
#   2. svc.sh installs it as a launchd service (auto-starts on login).
#   3. A second launchd job runs runner-heartbeat.sh every 300s to update
#      the repo variables SELF_HOSTED_MACOS_ONLINE and
#      SELF_HOSTED_MACOS_LAST_SEEN.
#   4. Workflows that opt in route via:
#        runs-on: ${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}
#
# USAGE
#   bash .claude/scripts/setup-self-hosted-runner.sh             # install
#   bash .claude/scripts/setup-self-hosted-runner.sh --check     # status
#   bash .claude/scripts/setup-self-hosted-runner.sh --uninstall # remove
#
# PREREQUISITES
#   - gh CLI installed + authenticated with `repo` + `Variables:write` scopes
#     on sceneview/sceneview (gh auth login --scopes "repo,workflow")
#   - Xcode + Xcode CLI tools installed (the runner just executes workflow
#     steps — Xcode is needed by ios.yml etc., not by this installer)

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
REPO="sceneview/sceneview"
RUNNER_VERSION="2.328.0"
RUNNER_LABEL="sceneview-mac"
RUNNER_HOME="${HOME}/Library/Application Support/sceneview-runner"
RUNNER_NAME="sceneview-mac-$(hostname -s)"

HEARTBEAT_PLIST="${HOME}/Library/LaunchAgents/io.github.sceneview.runner-heartbeat.plist"
HEARTBEAT_LABEL="io.github.sceneview.runner-heartbeat"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEARTBEAT_SCRIPT="${SCRIPT_DIR}/runner-heartbeat.sh"

ACTION="${1:-install}"

# ── --check ───────────────────────────────────────────────────────────────────
if [[ "${ACTION}" == "--check" ]]; then
  echo "Runner home:     ${RUNNER_HOME}"
  if [[ -f "${RUNNER_HOME}/.runner" ]]; then
    echo "  configured:    yes"
    cat "${RUNNER_HOME}/.runner" | sed 's/^/    /'
  else
    echo "  configured:    no"
  fi

  echo "Heartbeat plist: ${HEARTBEAT_PLIST}"
  if launchctl print "gui/$(id -u)/${HEARTBEAT_LABEL}" >/dev/null 2>&1; then
    echo "  loaded:        yes"
  else
    echo "  loaded:        no"
  fi

  if [[ -f /tmp/sceneview-runner-heartbeat.log ]]; then
    echo "Recent heartbeat log:"
    tail -3 /tmp/sceneview-runner-heartbeat.log | sed 's/^/  /'
  fi

  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "Repo variables (${REPO}):"
    for var in SELF_HOSTED_MACOS_ONLINE SELF_HOSTED_MACOS_LAST_SEEN; do
      val="$(gh variable get "${var}" --repo "${REPO}" 2>/dev/null || echo '<unset>')"
      echo "  ${var}=${val}"
    done
  fi
  exit 0
fi

# ── --uninstall ───────────────────────────────────────────────────────────────
if [[ "${ACTION}" == "--uninstall" ]]; then
  echo "Stopping heartbeat..."
  launchctl unload "${HEARTBEAT_PLIST}" 2>/dev/null || true
  rm -f "${HEARTBEAT_PLIST}"

  if [[ -f "${RUNNER_HOME}/svc.sh" ]]; then
    echo "Stopping runner service..."
    (cd "${RUNNER_HOME}" && sudo ./svc.sh stop 2>/dev/null || true)
    (cd "${RUNNER_HOME}" && sudo ./svc.sh uninstall 2>/dev/null || true)
  fi

  if [[ -f "${RUNNER_HOME}/config.sh" ]]; then
    echo "Removing runner registration from GitHub..."
    REM_TOKEN="$(gh api -X POST "/repos/${REPO}/actions/runners/remove-token" --jq .token 2>/dev/null || echo "")"
    if [[ -n "${REM_TOKEN}" ]]; then
      (cd "${RUNNER_HOME}" && ./config.sh remove --token "${REM_TOKEN}" 2>/dev/null || true)
    fi
  fi

  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh variable set SELF_HOSTED_MACOS_ONLINE --repo "${REPO}" --body "false" >/dev/null 2>&1 || true
  fi

  echo "Uninstalled. Runner files left in ${RUNNER_HOME} — remove manually if desired."
  exit 0
fi

# ── install ───────────────────────────────────────────────────────────────────
if [[ "${ACTION}" != "install" ]]; then
  echo "Unknown action: ${ACTION}"
  echo "Usage: bash $(basename "$0") [install|--check|--uninstall]"
  exit 1
fi

# Pre-flight checks
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: this installer is macOS-only." >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: gh CLI not found. Install with: brew install gh" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "ERROR: gh not authenticated. Run: gh auth login --scopes 'repo,workflow'" >&2
  exit 1
fi

ARCH="$(uname -m)"
case "${ARCH}" in
  arm64) RUNNER_ARCH="osx-arm64" ;;
  x86_64) RUNNER_ARCH="osx-x64" ;;
  *) echo "ERROR: unsupported arch: ${ARCH}" >&2; exit 1 ;;
esac

# Idempotency: skip the download if already extracted
mkdir -p "${RUNNER_HOME}"
cd "${RUNNER_HOME}"

if [[ ! -f "./run.sh" ]]; then
  TARBALL="actions-runner-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
  URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${TARBALL}"
  echo "Downloading ${TARBALL}..."
  curl -fsSL -o "${TARBALL}" "${URL}"
  tar xzf "${TARBALL}"
  rm "${TARBALL}"
fi

# Idempotency: skip registration if already configured
if [[ ! -f ".runner" ]]; then
  echo "Fetching runner registration token..."
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

# Install + start launchd service (idempotent via svc.sh)
if [[ ! -f "./svc.sh" ]]; then
  echo "ERROR: svc.sh missing — runner extraction failed" >&2
  exit 1
fi

# svc.sh status returns non-zero when not installed
if ! ./svc.sh status >/dev/null 2>&1; then
  echo "Installing runner as launchd service..."
  ./svc.sh install "${USER}"
  ./svc.sh start
else
  echo "Runner service already installed."
fi

# Install heartbeat launchd job
mkdir -p "$(dirname "${HEARTBEAT_PLIST}")"
PATH_ENTRIES="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

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
    <key>PATH</key><string>${PATH_ENTRIES}</string>
    <key>GITHUB_REPO</key><string>${REPO}</string>
    <key>RUNNER_NAME</key><string>${RUNNER_NAME}</string>
    <key>HOME</key><string>${HOME}</string>
  </dict>
  <key>StandardOutPath</key><string>/tmp/sceneview-runner-heartbeat.log</string>
  <key>StandardErrorPath</key><string>/tmp/sceneview-runner-heartbeat.log</string>
</dict>
</plist>
PLIST

launchctl unload "${HEARTBEAT_PLIST}" 2>/dev/null || true
launchctl load -w "${HEARTBEAT_PLIST}"

echo ""
echo "✅ Installed."
echo "   Runner home:  ${RUNNER_HOME}"
echo "   Runner name:  ${RUNNER_NAME}"
echo "   Label:        ${RUNNER_LABEL}"
echo "   Heartbeat:    every 300s → /tmp/sceneview-runner-heartbeat.log"
echo ""
echo "Verify:  bash $(basename "$0") --check"
echo "Opt in:  add to a workflow job: runs-on: \${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && '${RUNNER_LABEL}' || 'macos-15' }}"
