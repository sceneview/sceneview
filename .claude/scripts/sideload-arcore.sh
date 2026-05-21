#!/usr/bin/env bash
# sideload-arcore.sh — install Google Play Services for AR on a running emulator
#
# WHY THIS EXISTS
# ---------------
# The AR replay harness (ar-replay-qa.sh + ARReplayHarnessTest, slice #1565)
# needs the `com.google.ar.core` package. On a developer box `setup-ar-emulator.sh`
# handles this, but that script is AVD-lifecycle-heavy: it creates / patches /
# boots its own AVD and hard-exits when the emulator + avdmanager binaries are
# absent. In CI the `ar` job of device-qa.yml uses the ReactiveCircus
# android-emulator-runner action, which supplies its OWN already-booted AVD
# (a `google_apis` image — no Play Store, so ARCore is not preinstalled).
#
# This helper does ONLY the ARCore sideload, against whatever emulator is
# already on adb. It exists as a standalone file because the ReactiveCircus
# action runs each LINE of its `script:` input as a separate `sh -c` — a
# multi-line `if … fi` block there is a hard `Syntax error: end of file
# unexpected` (this is exactly what broke the AR leg's first CI run, job
# 76365347509). Keeping the logic in a real script sidesteps that entirely.
#
# It fetches the x86-emulator build of ARCore from the public
# google-ar/arcore-android-sdk GitHub release (the asset whose name carries the
# `x86_for_emulator` marker — that build has x86 native code), or the generic
# arm64/armv7 APK on a non-x86 host.
#
# EXIT CODES
#   0  ARCore is installed (freshly sideloaded, or was already present)
#   1  could not resolve / download / install the ARCore APK
#   2  no emulator on adb
#
# A non-zero exit is intentionally NON-fatal for the caller: device-qa.yml runs
# `sideload-arcore.sh || echo "…"` so the AR harness still runs and reports its
# own honest assumeTrue-skip when ARCore is genuinely unavailable.

set -euo pipefail

log() { echo "[sideload-arcore] $*"; }

# adb: prefer the SDK copy, fall back to PATH (CI runner has it on PATH).
ADB_BIN="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools/adb"
[[ -x "$ADB_BIN" ]] || ADB_BIN="adb"

# Pick the first fully-booted emulator on adb.
SERIAL="${EMU_SERIAL:-$("$ADB_BIN" devices 2>/dev/null \
  | awk '/^emulator-[0-9]+/ && $2=="device" {print $1; exit}')}"
if [[ -z "$SERIAL" ]]; then
  log "no emulator on adb — nothing to sideload onto"
  exit 2
fi

# Already installed? (Play Store image, or a warm re-run.) Nothing to do.
if "$ADB_BIN" -s "$SERIAL" shell pm list packages 2>/dev/null \
    | grep -q "com.google.ar.core"; then
  log "ARCore (com.google.ar.core) already installed on $SERIAL"
  exit 0
fi

# Map the emulator's CPU ABI to the right release asset.
ABI="$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.cpu.abi 2>/dev/null \
  | tr -d '\r\n')"
case "$ABI" in
  x86_64|x86) WANT_X86=1 ;;
  *)          WANT_X86=0 ;;
esac

log "resolving latest ARCore APK from google-ar/arcore-android-sdk (abi=$ABI)"
APK_URL="$(curl -fsSL "https://api.github.com/repos/google-ar/arcore-android-sdk/releases/latest" 2>/dev/null \
  | WANT_X86="$WANT_X86" python3 -c "import json,os,sys
d=json.load(sys.stdin)
want_x86=os.environ.get('WANT_X86')=='1'
assets=d.get('assets',[])
# x86 emulator build for x86 hosts; generic (arm64+armv7) APK otherwise.
for a in assets:
    n=a['name']
    if want_x86 and 'x86_for_emulator' in n:
        print(a['browser_download_url']); break
else:
    for a in assets:
        n=a['name']
        if n.startswith('Google_Play_Services_for_AR_') and n.endswith('.apk') and 'x86_for_emulator' not in n:
            print(a['browser_download_url']); break
" 2>/dev/null)"

if [[ -z "$APK_URL" ]]; then
  log "could not resolve an ARCore APK download URL"
  exit 1
fi

TMP_APK="/tmp/sceneview-arcore.apk"
log "downloading $APK_URL"
curl -fsSL -o "$TMP_APK" "$APK_URL" || { log "ARCore download failed"; exit 1; }

log "installing ARCore on $SERIAL"
"$ADB_BIN" -s "$SERIAL" install -r "$TMP_APK" || { log "ARCore install failed"; exit 1; }

log "ARCore installed OK on $SERIAL"
