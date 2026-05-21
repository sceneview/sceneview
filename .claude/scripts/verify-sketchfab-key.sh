#!/usr/bin/env bash
#
# verify-sketchfab-key.sh — Pre-release guard for Sketchfab API key wiring (#1909).
#
# Mirrors `verify-arcore-key.sh` (#1177). Runs on release tags + workflow_dispatch
# to fail-fast if the Explore tab's Sketchfab carousels + search would silently
# ship "no key" on Play Store / TestFlight. Two checks:
#
#   1. The `SKETCHFAB_API_KEY` env var (from the GitHub Secret) is set,
#      non-empty, and not just whitespace. The root cause of #1909 was the
#      secret being empty/blank at the org or repo level — the build still
#      passed and shipped with `BuildConfig.SKETCHFAB_API_KEY=""`, then
#      `SketchfabConfig.apiKey` resolved to `null` on every user device.
#
#   2. (Optional) The key actually works against the Sketchfab API. A cheap
#      `GET /v3/me` (one HTTP round-trip, no model download) catches revoked
#      tokens before the AAB / IPA ships. Set `SKIP_SKETCHFAB_LIVE_CHECK=1`
#      to skip the live API call when the runner has no outbound network
#      (rare on GitHub-hosted runners, but the option exists).
#
# What this script does NOT check:
#   - Whether the demo's `BuildConfig.SKETCHFAB_API_KEY` field actually ends
#     up populated in the built APK. That's a Gradle-side concern and the
#     `samples/android-demo/build.gradle` wiring is covered by Android Lint
#     + the demo's instrumented tests.
#
# Exit codes: 0 = ok; 1 = missing/blank key; 2 = key set but Sketchfab rejects.

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

errors=0

echo "🔐 Verifying Sketchfab API key wiring (#1909 guard)"

# Check 1 — env var present and non-blank.
#
# Use parameter expansion to strip whitespace so a secret that's literally
# " " or "\n" (the actual hit pattern of #1909) trips the guard. `-z` alone
# would let a single space pass.
raw_key="${SKETCHFAB_API_KEY:-}"
stripped_key="${raw_key// /}"
stripped_key="${stripped_key//$'\t'/}"
stripped_key="${stripped_key//$'\n'/}"
stripped_key="${stripped_key//$'\r'/}"

if [ -z "$stripped_key" ]; then
  echo -e "${RED}❌ SKETCHFAB_API_KEY is empty or blank.${NC}"
  echo "   The release artifact will ship with BuildConfig.SKETCHFAB_API_KEY=\"\","
  echo "   the Explore tab's Sketchfab carousels will be invisible, and the search"
  echo "   bar will silently swallow every query."
  echo ""
  echo "   Fix: rotate the SKETCHFAB_API_KEY GitHub Secret with a verified-valid"
  echo "   token at:"
  echo "     https://github.com/sceneview/sceneview/settings/secrets/actions"
  echo "   (Sketchfab → Settings → Password & API → API Token)"
  errors=$((errors + 1))
else
  key_len=${#stripped_key}
  # Sketchfab tokens are 32 hex chars. Anything < 20 is almost certainly a
  # truncated paste or a placeholder.
  if [ "$key_len" -lt 20 ]; then
    echo -e "${YELLOW}⚠️  SKETCHFAB_API_KEY is set but suspiciously short (${key_len} chars). Expected ~32.${NC}"
    errors=$((errors + 1))
  else
    echo -e "${GREEN}✅ SKETCHFAB_API_KEY is set (${key_len} chars).${NC}"
  fi
fi

# Check 2 — live API probe. Skip if the previous check already failed (no
# point hitting Sketchfab with a blank key) or if explicitly opted out.
if [ "$errors" -eq 0 ] && [ -z "${SKIP_SKETCHFAB_LIVE_CHECK:-}" ]; then
  if ! command -v curl >/dev/null 2>&1; then
    echo -e "${YELLOW}⚠️  curl not available — skipping Sketchfab live probe.${NC}"
  else
    # NOTE: no `-f`. With `curl -f`, curl exits non-zero on any 4xx AND
    # prints nothing, so `%{http_code}` is lost and `|| echo "000"` would
    # override the real status — making the 401|403 "token revoked" branch
    # below unreachable (a revoked key would silently pass as transient).
    # Without `-f`, curl exits 0 for any HTTP response and `%{http_code}`
    # carries the real status (4xx included) into the case statement; the
    # `|| echo "000"` then only triggers on a genuine connection failure
    # (DNS / connect timeout / no network).
    http_status=$(curl -sS -o /dev/null -w "%{http_code}" \
      --connect-timeout 10 --max-time 20 \
      -H "Authorization: Token ${stripped_key}" \
      "https://api.sketchfab.com/v3/me" || echo "000")

    case "$http_status" in
      200)
        echo -e "${GREEN}✅ Sketchfab /v3/me returns HTTP 200 — token is valid.${NC}"
        ;;
      401|403)
        echo -e "${RED}❌ Sketchfab rejected SKETCHFAB_API_KEY (HTTP ${http_status}).${NC}"
        echo "   The token is set but revoked / wrong / expired. Rotate the secret."
        errors=$((errors + 1))
        ;;
      000)
        echo -e "${YELLOW}⚠️  Could not reach api.sketchfab.com (connect timeout / DNS).${NC}"
        echo "   Skipping live validation; the secret-presence check above still applies."
        ;;
      *)
        echo -e "${YELLOW}⚠️  Sketchfab /v3/me returned unexpected HTTP ${http_status}.${NC}"
        echo "   Treating as transient — but if you see this on multiple runs, rotate the secret."
        ;;
    esac
  fi
fi

if [ "$errors" -gt 0 ]; then
  echo ""
  echo -e "${RED}❌ verify-sketchfab-key.sh: ${errors} error(s).${NC}"
  echo "   See https://github.com/sceneview/sceneview/issues/1909 for context."
  # 2 reserved for "key set but Sketchfab rejected"; everything else is 1.
  exit 1
fi

echo ""
echo -e "${GREEN}✅ verify-sketchfab-key.sh: ok.${NC}"
exit 0
