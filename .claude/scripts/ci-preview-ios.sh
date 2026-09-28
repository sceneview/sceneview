#!/usr/bin/env bash
# Build the iOS demo for the Simulator, then capture it light + dark.
#
# Called by .github/workflows/preview.yml on a GitHub-hosted macOS runner. It
# never runs on the self-hosted Mac: the point of the preview workflow is to
# keep Xcode builds and simulators off the maintainer's machine.
#
# Usage: ci-preview-ios.sh <out-dir> [demo-ids]
#   demo-ids  comma- or space-separated ids from DemoDeepLinkRegistry, plus the
#             pseudo-id `home` (plain launch). Default: home,model-viewer
#
# Writes:
#   <out-dir>/SceneView-simulator.app.zip   the built app (`simctl install` it)
#   <out-dir>/screenshots/<theme>/<id>.png
#   <out-dir>/summary.md
#
# Keyless on purpose: the artifact is downloadable by anyone signed in to
# GitHub, so no store secret is baked into it. The Sketchfab-backed screens
# fall back to their bundled models, exactly as on a fork.
set -euo pipefail

OUT="${1:?usage: ci-preview-ios.sh <out-dir> [demo-ids]}"
IDS_RAW="${2:-home,model-viewer}"
BUNDLE_ID="io.github.sceneview.demo"
SETTLE="${PREVIEW_SETTLE_SECONDS:-20}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_DIR="$ROOT/samples/ios-demo"

mkdir -p "$OUT/screenshots"
OUT="$(cd "$OUT" && pwd)"
SUMMARY="$OUT/summary.md"
: > "$SUMMARY"

IDS=()
for id in $(printf '%s' "$IDS_RAW" | tr ',' ' '); do
  if [[ "$id" =~ ^[a-z0-9-]+$ ]]; then IDS+=("$id"); else echo "::warning::skipping invalid demo id '$id'"; fi
done
[ "${#IDS[@]}" -gt 0 ] || { echo "::error::no valid demo id in '$IDS_RAW'"; exit 1; }

# Same resolver as ios.yml / render-tests.yml (#3174): wait for CoreSimulator,
# pick the newest iPhone by UDID rather than pinning a model name.
# shellcheck source=lib/ios-simulator.sh
. "$ROOT/.claude/scripts/lib/ios-simulator.sh"
UDID="$(ios_simulator_udid | cut -f1)"
[ -n "$UDID" ] || { echo "::error::no iOS simulator available"; exit 1; }
echo "[preview] simulator $UDID"
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null

DERIVED="${PREVIEW_DERIVED_DATA:-$(mktemp -d)}"
mkdir -p "$DERIVED"
echo "[preview] building SceneViewDemo for the simulator"
set -o pipefail
xcodebuild build \
  -project "$DEMO_DIR/SceneViewDemo.xcodeproj" \
  -scheme SceneViewDemo \
  -destination "id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -skipPackagePluginValidation \
  -skipMacroValidation \
  CODE_SIGNING_ALLOWED=NO \
  | { command -v xcpretty >/dev/null 2>&1 && xcpretty || cat; }

APP="$(find "$DERIVED/Build/Products" -maxdepth 2 -name '*.app' -path '*iphonesimulator*' -print -quit)"
[ -n "$APP" ] || { echo "::error::build succeeded but no .app was produced"; exit 1; }
( cd "$(dirname "$APP")" && ditto -c -k --keepParent "$(basename "$APP")" "$OUT/SceneView-simulator.app.zip" )
echo "[preview] app: $APP"

xcrun simctl install "$UDID" "$APP"

launch() {
  xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
  if [ "$1" = home ]; then
    xcrun simctl launch "$UDID" "$BUNDLE_ID" >/dev/null
  else
    # Launch arguments, not `simctl openurl`: a URL raises SpringBoard's
    # "Open in 'SceneView'?" alert, which the first CI run captured instead
    # of the demo. `-demo <id>` routes on first frame (SceneViewDemoApp.swift,
    # same path as capture-appstore-screenshots.sh); `-qa_mode 1` freezes
    # auto-rotation, mirroring Android's `qa_mode` extra.
    xcrun simctl launch "$UDID" "$BUNDLE_ID" -demo "$1" -qa_mode 1 >/dev/null
  fi
}

# Warm-up pass, not captured: the first launch after install compiles Metal
# shaders and fills the model cache, and a capture taken then shows an empty
# Showcase and a loading spinner (run 36405954123, light theme).
for id in "${IDS[@]}"; do launch "$id"; sleep "$SETTLE"; done

captured=0
for theme in light dark; do
  xcrun simctl ui "$UDID" appearance "$theme"
  mkdir -p "$OUT/screenshots/$theme"
  for id in "${IDS[@]}"; do
    launch "$id"
    sleep "$SETTLE"
    if xcrun simctl io "$UDID" screenshot "$OUT/screenshots/$theme/$id.png" >/dev/null 2>&1; then
      captured=$((captured + 1))
      echo "[preview] captured $theme/$id"
    else
      echo "::warning::screenshot failed for $theme/$id"
    fi
    if ! xcrun simctl spawn "$UDID" launchctl list 2>/dev/null | grep -q "$BUNDLE_ID"; then
      echo "::warning::the app was not running after $theme/$id — possible crash"
      echo "- app not running after \`$theme/$id\` (possible crash)" >> "$SUMMARY"
    fi
  done
done
xcrun simctl ui "$UDID" appearance light || true
# Simulator app crashes land in the host's DiagnosticReports: ship them.
find "$HOME/Library/Logs/DiagnosticReports" -name 'SceneView*' -newer "$OUT/SceneView-simulator.app.zip" \
  -exec sh -c 'mkdir -p "$1/crashes" && cp "$2" "$1/crashes/"' _ "$OUT" {} \; 2>/dev/null || true
[ ! -d "$OUT/crashes" ] || echo "- crash reports: \`crashes/\` ($(ls "$OUT/crashes" | wc -l | tr -d ' '))" >> "$SUMMARY"
[ -n "${PREVIEW_DERIVED_DATA:-}" ] || rm -rf "$DERIVED"

echo "- captured: $captured screenshot(s) — ids: ${IDS[*]}, themes: light dark" >> "$SUMMARY"
cat "$SUMMARY"
[ "$captured" -gt 0 ] || { echo "::error::no screenshot captured"; exit 1; }
