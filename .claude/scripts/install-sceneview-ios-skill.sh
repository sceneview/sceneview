#!/usr/bin/env bash
# install-sceneview-ios-skill.sh — copies the SceneViewSwift (iOS/macOS/visionOS)
# agent skill into the Android CLI skill registry so `android skills list`
# exposes it to AI agents.
#
# Usage:
#   bash .claude/scripts/install-sceneview-ios-skill.sh           # install (copy)
#   bash .claude/scripts/install-sceneview-ios-skill.sh --uninstall
#
# After install, run `android skills list` to verify `sceneview-ios` appears
# under the `xr` category. The android CLI v0.7 does NOT follow symlinks in the
# skills directory, so this script always copies. Re-run after pulling new
# commits from the repo to refresh the installed skill.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC="$REPO_ROOT/agents/sceneview-ios"
DST="$HOME/.android/cli/skills/xr/sceneview-ios"

# Source the helper so we can locate `android` even if it lives only at
# ~/.local/bin/android (the canonical install location) without being on PATH.
# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"

action="install"
for arg in "$@"; do
  case "$arg" in
    --uninstall) action="uninstall" ;;
    -h|--help)
      sed -n '2,15p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$SRC/SKILL.md" ]]; then
  echo "[install-sceneview-ios-skill] $SRC/SKILL.md not found — wrong worktree?" >&2
  exit 1
fi

case "$action" in
  install)
    mkdir -p "$(dirname "$DST")"
    if [[ -e "$DST" || -L "$DST" ]]; then
      rm -rf "$DST"
    fi
    cp -R "$SRC" "$DST"
    echo "[install-sceneview-ios-skill] copied $SRC → $DST"
    if [[ -f "$DST/SKILL.md" ]]; then
      echo "[install-sceneview-ios-skill] ✓ SKILL.md installed at $DST/SKILL.md"
    else
      echo "[install-sceneview-ios-skill] ✗ SKILL.md missing after copy?" >&2
      exit 1
    fi
    if android_cli_locate; then
      if "$ANDROID_CLI_BIN" --no-metrics skills list 2>/dev/null | grep -qw "sceneview-ios"; then
        echo "[install-sceneview-ios-skill] ✓ \`android skills list\` shows 'sceneview-ios'"
      else
        echo "[install-sceneview-ios-skill] note: 'sceneview-ios' not visible in \`android skills list\` output — run it manually to confirm"
      fi
    else
      echo "[install-sceneview-ios-skill] note: the \`android\` CLI is not installed yet — install it from https://developer.android.com/tools/agents/android-cli"
    fi
    ;;
  uninstall)
    if [[ -e "$DST" || -L "$DST" ]]; then
      rm -rf "$DST"
      echo "[install-sceneview-ios-skill] removed $DST"
    else
      echo "[install-sceneview-ios-skill] nothing to remove at $DST"
    fi
    ;;
esac
