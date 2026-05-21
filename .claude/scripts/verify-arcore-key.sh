#!/usr/bin/env bash
#
# verify-arcore-key.sh — Pre-release guard for ARCore Cloud key wiring (#1177).
#
# Runs on release tags + workflow_dispatch to fail-fast if the Cloud Anchors /
# Geospatial / Streetscape demos would silently ship `ERROR_NOT_AUTHORIZED` to
# Play Store users. Two checks:
#
#   1. The `ARCORE_API_KEY` environment variable (or GitHub secret) is set
#      and non-empty. CI workflows that build the demo all source this, so a
#      missing secret means the upload AAB ships with an empty manifest
#      placeholder → every Cloud-backed demo dies on launch.
#
#   2. The package-name manifest placeholder line is present in
#      `samples/android-demo/build.gradle` (regression guard — past edits have
#      dropped the `manifestPlaceholders["arcoreApiKey"] = …` injection).
#
# What this script CANNOT check (manual, Cloud Console only):
#   - Whether the Play App Signing key SHA-1 is whitelisted on the Cloud API
#     key restrictions. That's the actual root cause of #1177 and the runbook
#     lives in samples/android-demo/ARCORE_CLOUD_SETUP.md → "Play App Signing key".
#
# Exit codes: 0 = ok; 1 = missing key / missing wiring.

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

errors=0

echo "🔐 Verifying ARCore Cloud key wiring (#1177 guard)"

# Check 1 — secret/env var present
if [ -z "${ARCORE_API_KEY:-}" ]; then
  echo -e "${RED}❌ ARCORE_API_KEY env var is empty.${NC}"
  echo "   The CI build will ship an AAB with no Cloud key wired into the manifest."
  echo "   Cloud Anchors / Geospatial / Streetscape demos will return ERROR_NOT_AUTHORIZED at runtime."
  echo "   Fix: set the GitHub repository secret ARCORE_API_KEY before re-triggering the release."
  errors=$((errors + 1))
else
  # Don't log the key value, just confirm it's plausible (Google API keys are 39 chars).
  key_len=${#ARCORE_API_KEY}
  if [ "$key_len" -lt 20 ]; then
    echo -e "${YELLOW}⚠️  ARCORE_API_KEY is set but suspiciously short (${key_len} chars). Expected ~39.${NC}"
    errors=$((errors + 1))
  else
    echo -e "${GREEN}✅ ARCORE_API_KEY is set (${key_len} chars).${NC}"
  fi
fi

# Check 2 — build.gradle still wires the manifest placeholder
gradle_file="samples/android-demo/build.gradle"
if ! grep -q 'manifestPlaceholders\["arcoreApiKey"\]' "$gradle_file"; then
  echo -e "${RED}❌ ${gradle_file} no longer injects 'arcoreApiKey' as a manifest placeholder.${NC}"
  echo "   Even a non-empty ARCORE_API_KEY won't reach the on-device manifest."
  errors=$((errors + 1))
else
  echo -e "${GREEN}✅ ${gradle_file} wires manifestPlaceholders[arcoreApiKey].${NC}"
fi

# Check 3 — AndroidManifest.xml still has the placeholder slot
manifest_file="samples/android-demo/src/main/AndroidManifest.xml"
if ! grep -q 'arcoreApiKey' "$manifest_file"; then
  echo -e "${RED}❌ ${manifest_file} no longer references \${arcoreApiKey}.${NC}"
  echo "   The placeholder must reach the runtime manifest for Cloud demos to authorize."
  errors=$((errors + 1))
else
  echo -e "${GREEN}✅ ${manifest_file} references \${arcoreApiKey}.${NC}"
fi

# Reminder about the manual Cloud Console step
echo ""
echo -e "${YELLOW}ℹ️  Reminder: production Cloud Anchors / Geospatial errors with ERROR_NOT_AUTHORIZED${NC}"
echo -e "${YELLOW}   most often mean the Play App Signing key SHA-1 isn't whitelisted on the${NC}"
echo -e "${YELLOW}   Cloud API key. If the SHA-1 IS whitelisted and the error persists, walk the${NC}"
echo -e "${YELLOW}   5-step checklist (billing / ARCore API enabled / API restrictions / propagation /${NC}"
echo -e "${YELLOW}   project-id mismatch) in samples/android-demo/ARCORE_CLOUD_SETUP.md →${NC}"
echo -e "${YELLOW}   \"Troubleshooting — ERROR_NOT_AUTHORIZED persists after SHA-1 is whitelisted\".${NC}"

if [ "$errors" -gt 0 ]; then
  echo ""
  echo -e "${RED}❌ verify-arcore-key.sh: ${errors} error(s).${NC}"
  exit 1
fi

echo ""
echo -e "${GREEN}✅ verify-arcore-key.sh: ok.${NC}"
exit 0
