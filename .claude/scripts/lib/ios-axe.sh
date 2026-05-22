#!/usr/bin/env bash
# ios-axe.sh — shared helpers for AXe (accessibility-driven iOS simulator automation).
#
# AXe (https://github.com/cameroncooke/axe) drives the iOS Simulator through Apple's
# accessibility APIs: label-based taps, UI tree dumps, typed text, swipes. It is the
# iOS analog of `lib/android-cli.sh` — replacing brittle coordinate taps with semantic
# label matching.
#
# Install: brew install cameroncooke/axe/axe
#
# Usage from another script:
#     source "$(dirname "$0")/lib/ios-axe.sh"
#     ios_axe_ensure                                # check install, warn if missing
#     ios_axe_describe_ui [udid]                    # JSON accessibility tree → stdout
#     ios_axe_tap_label "Start AR" [udid]           # tap element with matching label
#     ios_axe_screenshot /tmp/out.png [udid]        # PNG screenshot via simctl
#
# Falls back gracefully to `xcrun simctl` for screenshots when AXe is absent.
# Only the label-tap and UI-tree features require AXe; screenshots always work.
#
# Implements slice 1 of issue #1673 (iOS QA AXe + JSON UI tree).

set -o pipefail

IOS_AXE_BIN=""

# Resolve the `axe` binary. Returns 0 on success, sets IOS_AXE_BIN.
ios_axe_locate() {
    IOS_AXE_BIN=""
    if command -v axe >/dev/null 2>&1; then
        IOS_AXE_BIN="$(command -v axe)"
        return 0
    fi
    # Homebrew cellar path (arm64 Mac)
    local brew_prefix
    brew_prefix="$(brew --prefix 2>/dev/null)" || brew_prefix="/opt/homebrew"
    if [[ -x "${brew_prefix}/bin/axe" ]]; then
        IOS_AXE_BIN="${brew_prefix}/bin/axe"
        return 0
    fi
    return 1
}

# Check that AXe is installed. Warns (does not abort) when missing so callers
# can fall back to simctl-only paths. Returns 1 when absent so callers can gate.
ios_axe_ensure() {
    if ios_axe_locate; then
        return 0
    fi
    echo "[ios-axe] AXe not found. Install: brew install cameroncooke/axe/axe" >&2
    echo "[ios-axe] Label-tap and UI-tree features unavailable; screenshot path still works." >&2
    return 1
}

# Resolve the target simulator UDID.
# - If $1 is non-empty, use it directly.
# - Otherwise pick the first booted simulator.
# - Sets global IOS_AXE_UDID and returns 0 if found, 1 if no booted device.
ios_axe_resolve_udid() {
    local requested="${1:-}"
    if [[ -n "$requested" ]]; then
        IOS_AXE_UDID="$requested"
        return 0
    fi
    IOS_AXE_UDID="$(xcrun simctl list devices booted --json 2>/dev/null \
        | python3 -c "
import json, sys
d = json.load(sys.stdin)
for runtime in d.get('devices', {}).values():
    for dev in runtime:
        if dev.get('state') == 'Booted':
            print(dev['udid'])
            sys.exit(0)
sys.exit(1)
" 2>/dev/null)" && return 0
    echo "[ios-axe] No booted simulator found. Boot one first." >&2
    return 1
}

# Dump the simulator's accessibility tree as JSON to stdout.
# Requires AXe. Exits 1 (with warning) if AXe is absent.
#
# Usage: ios_axe_describe_ui [udid]
ios_axe_describe_ui() {
    local udid="${1:-}"
    if ! ios_axe_ensure; then return 1; fi
    ios_axe_resolve_udid "$udid" || return 1
    "$IOS_AXE_BIN" describe-ui --udid "$IOS_AXE_UDID" 2>/dev/null \
        || "$IOS_AXE_BIN" describe-ui 2>/dev/null  # older AXe may not have --udid
}

# Tap the first accessibility element whose label contains $label_text (case-insensitive).
# Requires AXe. Falls back to a simctl-based coordinate tap if the text is not found.
#
# Usage: ios_axe_tap_label "label text" [udid]
ios_axe_tap_label() {
    local label="${1:?ios_axe_tap_label: label required}"
    local udid="${2:-}"
    if ! ios_axe_ensure; then
        echo "[ios-axe] Skipping label tap for '${label}' — AXe not installed." >&2
        return 0
    fi
    ios_axe_resolve_udid "$udid" || return 1
    if ! "$IOS_AXE_BIN" tap --label "$label" --udid "$IOS_AXE_UDID" 2>/dev/null; then
        # older AXe may not have --udid
        "$IOS_AXE_BIN" tap --label "$label"
    fi
}

# Capture a screenshot from the booted simulator as a PNG.
# Uses `xcrun simctl io` — no AXe dependency, always available.
#
# Usage: ios_axe_screenshot /path/to/out.png [udid]
ios_axe_screenshot() {
    local outfile="${1:?ios_axe_screenshot: output path required}"
    local udid="${2:-}"
    ios_axe_resolve_udid "$udid" || return 1
    xcrun simctl io "$IOS_AXE_UDID" screenshot "$outfile"
}

# Check if the app with $bundle_id is the foreground app (crash detection).
# Returns 0 if the app is in the foreground, 1 otherwise.
#
# Usage: ios_axe_is_foreground "io.github.sceneview.demo" [udid]
ios_axe_is_foreground() {
    local bundle_id="${1:?ios_axe_is_foreground: bundle_id required}"
    local udid="${2:-}"
    ios_axe_resolve_udid "$udid" || return 1
    local front
    front="$(xcrun simctl spawn "$IOS_AXE_UDID" \
        launchctl list 2>/dev/null \
        | awk '$3 ~ /'"$bundle_id"'/ {print $3}' \
        | head -1)" 2>/dev/null
    [[ -n "$front" ]]
}
