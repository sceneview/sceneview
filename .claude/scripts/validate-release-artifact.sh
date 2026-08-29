#!/usr/bin/env bash
#
# validate-release-artifact.sh — Pre-upload guard for the Play Store AAB/APK
# (#1301, #1416).
#
# `play-store.yml` hands the gradle-produced `.aab` straight to the Play Store
# upload step. A stale or wrong-variant artifact (wrong package, mismatched
# versionName, off-by-one versionCode) would only surface as a Play Console
# rejection — minutes of runner time after the build, and a polluted Edit.
#
# This script introspects the built artifact's manifest and asserts, *before*
# the upload step:
#
#   1. the artifact file exists,
#   2. `package` matches the expected applicationId,
#   3. `versionName` matches the expected value (the one gradle was told to
#      build with — i.e. play-store.yml's `Calculate version` output),
#   4. `versionCode` matches the expected value AND is a positive integer.
#
# Tooling note (#1416): the artifact play-store.yml produces is an Android App
# Bundle (`.aab`), NOT an APK. `aapt2` reads APKs — it CANNOT introspect an
# `.aab`; pointing it at a bundle yields `Zip: failed to read at offset 0`,
# which the previous version of this script mis-reported as "artifact corrupt"
# and used to hard-fail, blocking v4.6.0 and v4.6.1. The correct tool for a
# bundle is **`bundletool dump manifest`**. So:
#
#   * `.aab` → `bundletool dump manifest` (bundletool auto-downloaded if the
#              runner doesn't already provide it).
#   * `.apk` → `aapt2 dump badging` (via the `android_cli_describe` helper).
#
# ⛔ A pre-upload *validation guard* must NEVER block a release because of its
# OWN missing or limited tooling. So this script draws a hard line between two
# failure kinds:
#
#   * "the artifact is wrong"  → exit 1 (BLOCK the release — that is the point)
#   * "the validation tooling
#      is unavailable"          → exit 0 (WARN + SKIP — a release must not be
#                                 blocked because tooling couldn't be located)
#
# (play-store.yml also wraps this step in `continue-on-error: true` as a final
# belt-and-braces guarantee — even an unexpected crash here cannot veto a
# release. This script's own skip-vs-fail discipline keeps the signal clean.)
#
# Usage:
#   validate-release-artifact.sh <artifact> <expected-package> \
#       <expected-version-name> <expected-version-code>
#
# Exit codes:
#   0 = artifact OK, OR validation skipped because tooling is unavailable
#   1 = genuine mismatch (wrong package / versionName / versionCode) or bad args

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/android-cli.sh
source "$SCRIPT_DIR/lib/android-cli.sh"

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <artifact> <expected-package> <expected-version-name> <expected-version-code>" >&2
  exit 1
fi

ARTIFACT="$1"
EXPECT_PKG="$2"
EXPECT_NAME="$3"
EXPECT_CODE="$4"

fail() {
  # `::error::` so the message is surfaced as a GitHub Actions annotation.
  # Used ONLY for genuine artifact problems — these MUST block the release.
  echo "::error::$*" >&2
  exit 1
}

skip() {
  # `::warning::` so the message is surfaced as a GitHub Actions annotation
  # without failing the job. Used when the *validation tooling* is unavailable —
  # a guard must never block a release because of its own missing tooling.
  echo "::warning::$*" >&2
  echo "[validate-artifact] SKIPPED — validation tooling unavailable, not blocking the release." >&2
  exit 0
}

[[ -f "$ARTIFACT" ]] || fail "release artifact not found: $ARTIFACT"

# locate_bundletool — resolves a `bundletool` invocation and echoes it on
# stdout (e.g. `bundletool` or `java -jar /path/bundletool.jar`). Returns 0 on
# success, 1 if neither bundletool nor a downloadable jar can be obtained.
#
# Resolution order:
#   1. `bundletool` already on PATH (dev machines, some CI images).
#   2. A cached jar from a previous run under "$TMPDIR".
#   3. Download the official release jar from GitHub (needs `java` + a
#      downloader). The version is pinned for reproducibility.
BUNDLETOOL_VERSION="1.17.2"
locate_bundletool() {
  if command -v bundletool >/dev/null 2>&1; then
    echo "bundletool"
    return 0
  fi
  # bundletool is a Java jar — without a JRE it cannot run at all.
  command -v java >/dev/null 2>&1 || return 1

  local cache_dir="${TMPDIR:-/tmp}"
  local jar="$cache_dir/bundletool-all-${BUNDLETOOL_VERSION}.jar"
  if [[ -s "$jar" ]]; then
    echo "java -jar $jar"
    return 0
  fi

  local url="https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$jar" "$url" 2>/dev/null || { rm -f "$jar"; return 1; }
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$jar" "$url" 2>/dev/null || { rm -f "$jar"; return 1; }
  else
    return 1
  fi
  [[ -s "$jar" ]] || { rm -f "$jar"; return 1; }
  echo "java -jar $jar"
  return 0
}

echo "[validate-artifact] describing $ARTIFACT"

DESC=""
case "$ARTIFACT" in
  *.aab)
    # `.aab` → bundletool is the correct (and only) tool. `aapt2` cannot read
    # a bundle. If bundletool can't be obtained, that's a tooling gap → skip,
    # never block the release.
    BT=""
    if ! BT="$(locate_bundletool)"; then
      skip "AAB manifest validation skipped — 'bundletool' is unavailable" \
           "(not on PATH and could not be downloaded; needs 'java' + network)." \
           "This is a tooling gap, not an artifact problem; the upload proceeds" \
           "unvalidated."
    fi
    echo "[validate-artifact] using: $BT dump manifest"
    DESCRIBE_RC=0
    # `bundletool dump manifest` prints the base module's AndroidManifest.xml
    # as decoded XML to stdout. Word-splitting on $BT is intentional — it is
    # either `bundletool` or `java -jar <path>`.
    # shellcheck disable=SC2086
    DESC="$($BT dump manifest --bundle "$ARTIFACT" 2>/dev/null)" || DESCRIBE_RC=$?
    if [[ "$DESCRIBE_RC" -ne 0 || -z "$DESC" ]]; then
      fail "could not introspect $ARTIFACT with bundletool — the artifact" \
           "appears corrupt or is not a valid Android App Bundle. Refusing to" \
           "upload an unverifiable release."
    fi
    ;;
  *)
    # `.apk` (or anything else) → the `android_cli_describe` helper wraps
    # `aapt2 dump badging`. Exit codes: 0 = OK, 1 = artifact unreadable,
    # 2 = `aapt2` tooling unavailable.
    DESCRIBE_RC=0
    DESC="$(android_cli_describe "$ARTIFACT")" || DESCRIBE_RC=$?
    if [[ "$DESCRIBE_RC" -eq 2 ]]; then
      skip "APK manifest validation skipped — 'aapt2' could not be located" \
           "(not on PATH, not under \$ANDROID_SDK_ROOT/build-tools). This is a" \
           "tooling gap, not an artifact problem; the upload proceeds unvalidated."
    elif [[ "$DESCRIBE_RC" -ne 0 ]]; then
      fail "could not introspect $ARTIFACT — the artifact appears corrupt or is" \
           "an unsupported format. Refusing to upload an unverifiable release."
    fi
    ;;
esac

echo "$DESC"

# Parse package / versionName / versionCode out of whichever format the
# describing tool emitted. Three formats are possible:
#
#   `aapt2 dump badging` (.apk):
#     package: name='io.github.sceneview.demo' versionCode='4321' versionName='4.5.0' ...
#
#   `bundletool dump manifest` (.aab) — decoded XML:
#     <manifest ... package="io.github.sceneview.demo"
#               android:versionCode="4321" android:versionName="4.5.0" ...>
#
#   `aapt2 dump xmltree` (legacy .aab path, protobuf) — kept for safety:
#     A: package="io.github.sceneview.demo" (Raw: "io.github.sceneview.demo")
#     A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=(type 0x10)0x10e1
#     A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="4.5.0" (Raw: …)
#
# Any hex versionCode literal is parsed and normalised to decimal so it
# compares cleanly with the decimal value gradle was given.
# The manifest is parsed with python3 — see the skip-vs-fail discipline at the
# top of this file: missing validation tooling must WARN + SKIP, never block.
if ! command -v python3 >/dev/null 2>&1; then
  skip "manifest parsing skipped — 'python3' is not available. Without it the" \
       "parsed package/versionName/versionCode would all come back empty and" \
       "this guard would report 'could not parse … from artifact manifest' —" \
       "a tooling gap dressed as a corrupt artifact. The upload proceeds" \
       "unvalidated."
fi

read -r GOT_PKG GOT_NAME GOT_CODE < <(python3 - "$DESC" <<'PYEOF'
import re, sys
desc = sys.argv[1]

def first(*patterns):
    for pat in patterns:
        m = re.search(pat, desc)
        if m:
            return m.group(1)
    return ""

pkg = first(r"name='([^']+)'", r"package=\"([^\"]+)\"", r"package[ :=]+([A-Za-z0-9._]+)")
name = first(r"versionName='([^']+)'",
             r"versionName=\"([^\"]+)\"",
             r"versionName[^=]*=\"([^\"]+)\"",
             r"versionName[ :=]+([A-Za-z0-9._\-]+)")
code_raw = first(r"versionCode='([^']+)'",
                 r"versionCode=\"([^\"]+)\"",
                 r"versionCode[^=]*=\(type[^)]*\)(0x[0-9a-fA-F]+)",
                 r"versionCode[^=]*=([0-9]+)",
                 r"versionCode[ :=]+([0-9]+)")
code = ""
if code_raw:
    try:
        code = str(int(code_raw, 0))  # base 0 → handles "0x10e1" and "4321"
    except ValueError:
        code = ""

print(pkg or "-", name or "-", code or "-")
PYEOF
)
# Normalise the python "-" sentinels back to empty strings.
[[ "$GOT_PKG"  == "-" ]] && GOT_PKG=""
[[ "$GOT_NAME" == "-" ]] && GOT_NAME=""
[[ "$GOT_CODE" == "-" ]] && GOT_CODE=""

echo "[validate-artifact] parsed: package=$GOT_PKG versionName=$GOT_NAME versionCode=$GOT_CODE"
echo "[validate-artifact] expect: package=$EXPECT_PKG versionName=$EXPECT_NAME versionCode=$EXPECT_CODE"

[[ -n "$GOT_PKG"  ]] || fail "could not parse 'package' from artifact manifest"
[[ -n "$GOT_NAME" ]] || fail "could not parse 'versionName' from artifact manifest"
[[ -n "$GOT_CODE" ]] || fail "could not parse 'versionCode' from artifact manifest"

[[ "$GOT_PKG" == "$EXPECT_PKG" ]] \
  || fail "package mismatch — built '$GOT_PKG', expected '$EXPECT_PKG'. Wrong variant or applicationId?"
[[ "$GOT_NAME" == "$EXPECT_NAME" ]] \
  || fail "versionName mismatch — built '$GOT_NAME', expected '$EXPECT_NAME'. Stale artifact?"
[[ "$GOT_CODE" == "$EXPECT_CODE" ]] \
  || fail "versionCode mismatch — built '$GOT_CODE', expected '$EXPECT_CODE'. Stale artifact?"
[[ "$GOT_CODE" =~ ^[0-9]+$ ]] && [[ "$GOT_CODE" -gt 0 ]] \
  || fail "versionCode '$GOT_CODE' is not a positive integer"

echo "[validate-artifact] OK — $ARTIFACT is $GOT_PKG $GOT_NAME ($GOT_CODE)"
