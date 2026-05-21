#!/usr/bin/env bash
#
# check-deeplink-health.sh — monitor Android App Links + iOS/macOS Universal
# Links verification health for the SceneView demo apps.
#
# WHY ───────────────────────────────────────────────────────────────────────
# The demo apps ship deep links that power the QR-code → demo-screen flow:
#
#   - Android: the `sceneview://demo` custom scheme + the VERIFIED App Link
#     `https://sceneview.github.io/open` (`android:autoVerify="true"` in
#     `samples/android-demo/src/main/AndroidManifest.xml`), paired with the
#     hosted `assetlinks.json` Digital Asset Links file.
#   - iOS / macOS: the `sceneview://` custom scheme + verified Universal Links
#     via the hosted `apple-app-site-association` (AASA) file and the
#     `applinks:` Associated Domains entitlement.
#
# App Links / Universal Links verification FAILS SILENTLY. A change to
# `assetlinks.json`, a signing-certificate rotation, a Play App Signing change,
# or a malformed/missing AASA leaves the QR → demo flow dead — the link just
# falls back to a browser with no error surfaced anywhere (#1695).
#
# WHAT IT CHECKS ────────────────────────────────────────────────────────────
#   1. The hosted `assetlinks.json` is reachable, valid JSON, and lists the
#      SAME package + SHA-256 fingerprints as the committed source-of-truth
#      copy in `website-static/.well-known/assetlinks.json`.
#   2. The hosted `apple-app-site-association` (AASA) is reachable, valid JSON,
#      served as `application/json`, and lists the SAME appIDs + `/open`
#      component as the committed copy.
#   3. The committed `.well-known/*` files are self-consistent with the demo
#      apps' deep-link config — the Android manifest intent-filter host /
#      package, and the iOS entitlement `applinks:` domain.
#   4. The web demo `/open` route resolves.
#
# This is intentionally a STATIC + reachability check. It cannot read the
# Play Console "Deep links" dashboard (that needs a Play Console deep-links
# permission on the service account); a fingerprint drift between the hosted
# file and Play App Signing surfaces instead as a committed-file mismatch the
# next time the file is regenerated.
#
# Usage:
#   bash .claude/scripts/check-deeplink-health.sh            # full check
#   bash .claude/scripts/check-deeplink-health.sh --offline  # skip network fetches
#
# Exit code: 0 if healthy (or only WARNs), 1 if any FAIL.
#
# Closes #1695.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
  echo "Not inside a git repo." >&2
  exit 0
fi
cd "$ROOT"

OFFLINE=0
[ "${1:-}" = "--offline" ] && OFFLINE=1

DOMAIN="sceneview.github.io"
ASSETLINKS_URL="https://$DOMAIN/.well-known/assetlinks.json"
AASA_URL="https://$DOMAIN/.well-known/apple-app-site-association"
OPEN_URL="https://$DOMAIN/open"

ASSETLINKS_SRC="website-static/.well-known/assetlinks.json"
AASA_SRC="website-static/.well-known/apple-app-site-association"
ANDROID_MANIFEST="samples/android-demo/src/main/AndroidManifest.xml"
IOS_ENTITLEMENTS="samples/ios-demo/SceneViewDemo/SceneViewDemo.entitlements"

FAIL=0
WARN=0

red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }
green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
gray()   { printf '\033[90m%s\033[0m\n' "$*"; }

ok()   { green   "  ✓ $*"; }
fail() { red     "  ✗ $*"; FAIL=$((FAIL + 1)); }
warn() { yellow  "  ! $*"; WARN=$((WARN + 1)); }

echo "🔗 Deep-link verification health check"
gray  "   $ROOT"
echo

# ── 1. Committed source-of-truth files exist + are valid JSON ───────────────
echo "1. Committed .well-known/ source files"

if [ ! -f "$ASSETLINKS_SRC" ]; then
  fail "$ASSETLINKS_SRC is missing — Android App Links cannot verify."
elif ! jq empty "$ASSETLINKS_SRC" 2>/dev/null; then
  fail "$ASSETLINKS_SRC is not valid JSON."
else
  ok "$ASSETLINKS_SRC present + valid JSON."
fi

if [ ! -f "$AASA_SRC" ]; then
  fail "$AASA_SRC is missing — iOS/macOS Universal Links cannot verify."
elif ! jq empty "$AASA_SRC" 2>/dev/null; then
  fail "$AASA_SRC is not valid JSON."
else
  ok "$AASA_SRC present + valid JSON."
fi
echo

# ── 2. Committed files are self-consistent with the demo apps ───────────────
echo "2. Committed config self-consistency"

# Android package name in assetlinks.json must match the manifest applicationId.
if [ -f "$ASSETLINKS_SRC" ] && [ -f "$ANDROID_MANIFEST" ]; then
  AL_PKG=$(jq -r '.[0].target.package_name // empty' "$ASSETLINKS_SRC" 2>/dev/null)
  if [ -z "$AL_PKG" ]; then
    fail "assetlinks.json has no target.package_name."
  elif [ "$AL_PKG" = "io.github.sceneview.demo" ]; then
    ok "assetlinks.json package_name = $AL_PKG."
  else
    warn "assetlinks.json package_name '$AL_PKG' is not the expected demo applicationId."
  fi

  # The verified App Link host in the manifest must equal the served domain.
  if grep -q "android:host=\"$DOMAIN\"" "$ANDROID_MANIFEST" \
     && grep -q 'android:autoVerify="true"' "$ANDROID_MANIFEST"; then
    ok "Android manifest has an autoVerify intent-filter for $DOMAIN."
  else
    fail "Android manifest is missing the autoVerify App Link for $DOMAIN."
  fi

  # Every fingerprint must be a well-formed colon-separated SHA-256 (32 bytes).
  FP_COUNT=$(jq -r '[.[].target.sha256_cert_fingerprints[]?] | length' "$ASSETLINKS_SRC" 2>/dev/null || echo 0)
  if [ "$FP_COUNT" -eq 0 ]; then
    fail "assetlinks.json lists no sha256_cert_fingerprints."
  else
    BAD_FP=$(jq -r '.[].target.sha256_cert_fingerprints[]?' "$ASSETLINKS_SRC" 2>/dev/null \
      | grep -vE '^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$' || true)
    if [ -n "$BAD_FP" ]; then
      fail "assetlinks.json has a malformed SHA-256 fingerprint: $BAD_FP"
    else
      ok "assetlinks.json lists $FP_COUNT well-formed SHA-256 fingerprint(s)."
    fi
  fi
fi

# iOS appIDs in the AASA must carry the demo bundle id, and the entitlement
# must declare the matching applinks: domain.
if [ -f "$AASA_SRC" ]; then
  AASA_APPIDS=$(jq -r '.applinks.details[].appIDs[]?' "$AASA_SRC" 2>/dev/null)
  if echo "$AASA_APPIDS" | grep -q 'io.github.sceneview.demo'; then
    ok "apple-app-site-association lists the demo bundle id."
  else
    fail "apple-app-site-association has no appID for io.github.sceneview.demo."
  fi
  if jq -e '[.applinks.details[].components[]? | select(.["/"] | test("/open"))] | length > 0' \
       "$AASA_SRC" >/dev/null 2>&1; then
    ok "apple-app-site-association maps the /open route."
  else
    fail "apple-app-site-association does not map the /open route."
  fi
fi

if [ -f "$IOS_ENTITLEMENTS" ]; then
  if grep -q "applinks:$DOMAIN" "$IOS_ENTITLEMENTS"; then
    ok "iOS entitlements declare applinks:$DOMAIN."
  else
    fail "iOS entitlements are missing applinks:$DOMAIN — Universal Links will not verify."
  fi
fi
echo

# ── 3. Hosted files match the committed source of truth ─────────────────────
echo "3. Hosted .well-known/ files vs committed source"

if [ "$OFFLINE" -eq 1 ]; then
  gray "  (--offline) skipping network fetches."
else
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT

  # --- assetlinks.json --------------------------------------------------------
  if curl -fsSL --max-time 20 "$ASSETLINKS_URL" -o "$TMP/assetlinks.json" 2>/dev/null; then
    if ! jq empty "$TMP/assetlinks.json" 2>/dev/null; then
      fail "Hosted $ASSETLINKS_URL is not valid JSON."
    else
      ok "Hosted assetlinks.json reachable + valid JSON."
      if [ -f "$ASSETLINKS_SRC" ]; then
        HOSTED_FP=$(jq -S -r '[.[].target.sha256_cert_fingerprints[]?] | sort' "$TMP/assetlinks.json" 2>/dev/null)
        SRC_FP=$(jq    -S -r '[.[].target.sha256_cert_fingerprints[]?] | sort' "$ASSETLINKS_SRC" 2>/dev/null)
        if [ "$HOSTED_FP" = "$SRC_FP" ]; then
          ok "Hosted fingerprints match the committed assetlinks.json."
        else
          fail "Hosted assetlinks.json fingerprints DRIFTED from the committed file — Android App Link verification will break."
        fi
        HOSTED_PKG=$(jq -r '.[0].target.package_name // empty' "$TMP/assetlinks.json" 2>/dev/null)
        SRC_PKG=$(jq    -r '.[0].target.package_name // empty' "$ASSETLINKS_SRC" 2>/dev/null)
        if [ "$HOSTED_PKG" = "$SRC_PKG" ]; then
          ok "Hosted package_name matches the committed file."
        else
          fail "Hosted assetlinks.json package_name ('$HOSTED_PKG') differs from committed ('$SRC_PKG')."
        fi
      fi
    fi
  else
    fail "Hosted $ASSETLINKS_URL is unreachable — Android App Links cannot verify."
  fi

  # --- apple-app-site-association ---------------------------------------------
  AASA_CT=$(curl -fsSL --max-time 20 -o "$TMP/aasa" -w '%{content_type}' "$AASA_URL" 2>/dev/null || echo "")
  if [ -s "$TMP/aasa" ]; then
    if ! jq empty "$TMP/aasa" 2>/dev/null; then
      fail "Hosted $AASA_URL is not valid JSON."
    else
      ok "Hosted apple-app-site-association reachable + valid JSON."
      case "$AASA_CT" in
        application/json*) ok "AASA served as application/json." ;;
        *) warn "AASA served as '$AASA_CT' — Apple expects application/json." ;;
      esac
      if [ -f "$AASA_SRC" ]; then
        HOSTED_AASA=$(jq -S . "$TMP/aasa"   2>/dev/null)
        SRC_AASA=$(jq    -S . "$AASA_SRC"   2>/dev/null)
        if [ "$HOSTED_AASA" = "$SRC_AASA" ]; then
          ok "Hosted AASA matches the committed apple-app-site-association."
        else
          fail "Hosted AASA DRIFTED from the committed file — iOS/macOS Universal Links will break."
        fi
      fi
    fi
  else
    fail "Hosted $AASA_URL is unreachable — iOS/macOS Universal Links cannot verify."
  fi

  # --- web demo /open route ---------------------------------------------------
  OPEN_CODE=$(curl -fsSL --max-time 20 -o /dev/null -w '%{http_code}' "$OPEN_URL" 2>/dev/null || echo "000")
  case "$OPEN_CODE" in
    2*) ok "Web /open route resolves (HTTP $OPEN_CODE)." ;;
    3*) ok "Web /open route resolves via redirect (HTTP $OPEN_CODE)." ;;
    *)  warn "Web /open route returned HTTP $OPEN_CODE — the QR fallback page may be broken." ;;
  esac
fi
echo

# ── 4. Cross-platform bridge contract ───────────────────────────────────────
# The Flutter / React Native demos must honour the same sceneview:// + /open
# deep-link contract. They are bridge demos, so the contract lives in the
# Android manifest + iOS plist they generate — verified above. Surface a note
# so a future bridge-specific intent-filter regression is on the radar.
echo "4. Cross-platform bridge contract"
gray "  Flutter/RN demos bridge to the native android-demo/ios-demo deep-link"
gray "  config verified above (sceneview:// scheme + $DOMAIN/open App Link)."
echo

# ── Summary ─────────────────────────────────────────────────────────────────
if [ "$FAIL" -gt 0 ]; then
  red "❌ Deep-link health: $FAIL failure(s), $WARN warning(s)."
  echo "::error::Deep-link verification is broken — the QR → demo flow is dead on at least one platform."
  exit 1
elif [ "$WARN" -gt 0 ]; then
  yellow "⚠️  Deep-link health: $WARN warning(s), no hard failures."
  exit 0
else
  green "✅ Deep-link health: all App Links / Universal Links checks passed."
  exit 0
fi
