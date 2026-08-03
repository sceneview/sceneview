#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
#  SceneView Demo — one-click installer
#
#  Usage:
#    ./try-demo              # Build & install the demo app
#    ./try-demo --download   # Download latest APK from GitHub Releases
#    ./try-demo --sample ar-model-viewer   # Build & install a specific sample
#
#  Requirements:
#    - Android device connected via USB/Wi-Fi with USB debugging ON
#    - Java 17+ (for local build)
#    - `adb` on PATH (comes with Android SDK platform-tools) — required.
#    - Google's `android` CLI is OPTIONAL, and used only for JSON layouts and
#      LF/CRLF-safe screenshots. NOT for installing: its `android run` has a
#      measured install no-op — it prints success and leaves the previous build
#      on the device (#2796, #2854, #2990).
#      Install: https://developer.android.com/tools/agents/android-cli
#
#  Installing always goes through `android_cli_install_and_launch`, which proves
#  the install landed against the device's `lastUpdateTime` and falls back to
#  `adb install -r` + `am start`. The device-count check requires `adb`.
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail

# Source the shared helper if available — it provides
# `android_cli_install_and_launch`, which proves the install landed instead of
# trusting an exit code (#2990). We DON'T auto-install the android CLI from this script
# (that would surprise an end-user running `./try-demo` for the first time).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_CLI_LIB="$SCRIPT_DIR/../.claude/scripts/lib/android-cli.sh"
if [[ -f "$ANDROID_CLI_LIB" ]]; then
  # shellcheck source=/dev/null
  source "$ANDROID_CLI_LIB"
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
DEMO_MODULE=":samples:sceneview-demo"
DEMO_PKG="io.github.sceneview.demo"
GITHUB_REPO="SceneView/sceneview"

banner() {
  echo ""
  echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════╗${RESET}"
  echo -e "${CYAN}${BOLD}║         SceneView Demo Installer         ║${RESET}"
  echo -e "${CYAN}${BOLD}║   3D & AR for Jetpack Compose — try it   ║${RESET}"
  echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${RESET}"
  echo ""
}

check_device() {
  # We need at least one of `android` or `adb` on PATH. The `android` CLI ships
  # its own bundled adb and is the preferred entry point for agent workflows;
  # `adb` standalone is the legacy fallback. Either is fine for this script.
  local has_android has_adb
  command -v android &>/dev/null && has_android=1 || has_android=0
  [[ "$has_android" -eq 0 ]] && [[ -x "$HOME/.local/bin/android" ]] && has_android=1
  command -v adb &>/dev/null && has_adb=1 || has_adb=0

  if [[ "$has_android" -eq 0 ]] && [[ "$has_adb" -eq 0 ]]; then
    echo -e "${RED}Error: neither \`android\` CLI nor \`adb\` found.${RESET}"
    echo "  Install Google's android CLI (preferred):"
    echo "    https://developer.android.com/tools/agents/android-cli"
    echo "  Or install Android SDK platform-tools (provides adb):"
    echo "    brew install android-platform-tools   # macOS"
    echo "    sudo apt install adb                  # Linux"
    exit 1
  fi

  # Device count — uses adb because `android` v0.7 has no `devices` subcommand.
  # If `adb` is not present but `android` is, the bundled adb is still callable
  # via the android CLI's own helpers, but the bare `adb` shim suffices here.
  if [[ "$has_adb" -eq 0 ]]; then
    echo -e "${RED}Error: adb not on PATH (required to count connected devices).${RESET}"
    echo "  The android CLI bundles adb but does not expose a top-level devices subcommand in v0.7."
    exit 1
  fi
  local devices
  devices=$(adb devices | grep -c 'device$' || true)
  if [[ "$devices" -eq 0 ]]; then
    echo -e "${RED}No Android device detected.${RESET}"
    echo ""
    echo "  1. Connect your phone via USB"
    echo "  2. Enable USB debugging in Developer Options"
    echo "  3. Accept the USB debugging prompt on your phone"
    echo ""
    echo -e "  Or use Wi-Fi: ${CYAN}adb connect <phone-ip>:5555${RESET}"
    exit 1
  fi
  echo -e "${GREEN}✓${RESET} Device connected ($devices device(s))"
}

download_and_install() {
  echo -e "${CYAN}Downloading latest demo APK from GitHub Releases...${RESET}"

  local url
  if command -v gh &>/dev/null; then
    url=$(gh release view --repo "$GITHUB_REPO" --json assets \
      --jq '.assets[] | select(.name == "sceneview-demo.apk") | .url' 2>/dev/null || true)
  fi

  if [[ -z "${url:-}" ]]; then
    url="https://github.com/$GITHUB_REPO/releases/latest/download/sceneview-demo.apk"
  fi

  local tmp_apk="${TMPDIR:-/tmp}/sceneview-demo.apk"
  curl -fSL --progress-bar -o "$tmp_apk" "$url"
  echo -e "${GREEN}✓${RESET} Downloaded"

  echo -e "${CYAN}Installing on device...${RESET}"
  # The helper installs and launches, and PROVES the install landed rather than
  # trusting an exit code (#2990 — `android run` printed success and installed
  # nothing three times in this repo). It returns non-zero when it cannot prove
  # it; `set -euo pipefail` at the top of this script is what stops the
  # "✓ Installed" below from printing in that case. Do not remove that, and do
  # not wrap this call in `|| true`.
  if type android_cli_install_and_launch >/dev/null 2>&1 \
     && { command -v android >/dev/null 2>&1 || [[ -x "$HOME/.local/bin/android" ]]; }; then
    android_cli_install_and_launch "$tmp_apk" "${DEMO_PKG}/.MainActivity"
  else
    adb install -r "$tmp_apk"
    launch_app
  fi
  echo -e "${GREEN}✓${RESET} Installed"
}

build_and_install() {
  local module="${1:-$DEMO_MODULE}"
  local pkg="${2:-$DEMO_PKG}"

  echo -e "${CYAN}Building ${BOLD}${module}${RESET}${CYAN}...${RESET}"
  cd "$REPO_ROOT"

  if [[ ! -f gradlew ]]; then
    echo -e "${RED}Error: gradlew not found. Are you in the SceneView repo root?${RESET}"
    exit 1
  fi

  chmod +x gradlew
  ./gradlew "${module}:assembleDebug" --console=plain -q

  local apk
  apk=$(find "$REPO_ROOT" -path "*${module##*:}*/build/outputs/apk/debug/*.apk" | head -1)
  if [[ -z "$apk" ]]; then
    # Fallback: search by module name converted to path
    local module_path="${module//:///}"
    apk=$(find "$REPO_ROOT${module_path}/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
  fi

  if [[ -z "$apk" ]]; then
    echo -e "${RED}Error: APK not found after build.${RESET}"
    exit 1
  fi

  echo -e "${GREEN}✓${RESET} Built: $(basename "$apk")"
  echo -e "${CYAN}Installing on device...${RESET}"
  # Same contract as install_prebuilt above: the helper proves the install and
  # returns non-zero when it cannot. `set -e` turns that into an abort before
  # the success line (#2990).
  if type android_cli_install_and_launch >/dev/null 2>&1 \
     && { command -v android >/dev/null 2>&1 || [[ -x "$HOME/.local/bin/android" ]]; }; then
    android_cli_install_and_launch "$apk" "${pkg}/.MainActivity"
  else
    adb install -r "$apk"
    launch_app "$pkg"
  fi
  echo -e "${GREEN}✓${RESET} Installed"
}

launch_app() {
  local pkg="${1:-$DEMO_PKG}"
  echo -e "${CYAN}Launching...${RESET}"
  adb shell am start -n "${pkg}/.MainActivity" 2>/dev/null \
    || adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 2>/dev/null \
    || true
  echo ""
  echo -e "${GREEN}${BOLD}🎉 SceneView Demo is running on your device!${RESET}"
  echo -e "   Explore 3D models, switch environments, try animations."
  echo ""
}

# ── Sample name → Gradle module mapping ──
sample_to_module() {
  local sample="$1"
  case "$sample" in
    demo|sceneview-demo)       echo ":samples:sceneview-demo" ;;
    model-viewer)              echo ":samples:model-viewer" ;;
    ar-model-viewer)           echo ":samples:ar-model-viewer" ;;
    ar-augmented-image)        echo ":samples:ar-augmented-image" ;;
    ar-cloud-anchor)           echo ":samples:ar-cloud-anchor" ;;
    ar-point-cloud)            echo ":samples:ar-point-cloud" ;;
    camera-manipulator)        echo ":samples:camera-manipulator" ;;
    gltf-camera)               echo ":samples:gltf-camera" ;;
    autopilot-demo)            echo ":samples:autopilot-demo" ;;
    physics-demo)              echo ":samples:physics-demo" ;;
    dynamic-sky)               echo ":samples:dynamic-sky" ;;
    line-path)                 echo ":samples:line-path" ;;
    text-labels)               echo ":samples:text-labels" ;;
    reflection-probe)          echo ":samples:reflection-probe" ;;
    post-processing)           echo ":samples:post-processing" ;;
    *)
      echo -e "${RED}Unknown sample: $sample${RESET}" >&2
      echo "Available: demo, model-viewer, ar-model-viewer, camera-manipulator," >&2
      echo "  gltf-camera, autopilot-demo, physics-demo, dynamic-sky, line-path," >&2
      echo "  text-labels, reflection-probe, post-processing, ar-augmented-image," >&2
      echo "  ar-cloud-anchor, ar-point-cloud" >&2
      exit 1
      ;;
  esac
}

# ── Main ──
banner

MODE="build"
SAMPLE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --download|-d)  MODE="download"; shift ;;
    --sample|-s)    SAMPLE="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: ./try-demo [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --download, -d          Download latest APK from GitHub Releases"
      echo "  --sample, -s NAME       Build a specific sample (e.g. ar-model-viewer)"
      echo "  --help, -h              Show this help"
      echo ""
      echo "Examples:"
      echo "  ./try-demo                          # Build & install the demo app"
      echo "  ./try-demo --download               # Download pre-built APK"
      echo "  ./try-demo --sample physics-demo    # Try the physics sample"
      exit 0
      ;;
    *) echo -e "${RED}Unknown option: $1${RESET}"; exit 1 ;;
  esac
done

check_device

if [[ "$MODE" == "download" ]]; then
  download_and_install
elif [[ -n "$SAMPLE" ]]; then
  module=$(sample_to_module "$SAMPLE")
  # Derive package from sample name
  sample_pkg="io.github.sceneview.sample.${SAMPLE//-/.}"
  build_and_install "$module" "$sample_pkg"
else
  build_and_install
fi
