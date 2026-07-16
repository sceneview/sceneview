#!/usr/bin/env bash
#
# test-store-preflight.sh — self-test for store-preflight.sh (#2612 P1).
#
# store-preflight.sh grades store-side release blockers; a regressed grader that
# silently PASSes is worse than none. This pins its contract WITHOUT any live
# App Store Connect credentials, using two deterministic seams:
#
#   - STORE_PREFLIGHT_FAKE=<json>  feeds pre-canned probe results, so the whole
#     grading / gate / JSON path is exercised offline.
#   - --selftest-jwt               signs a throwaway P-256 key and verifies the
#     signature, proving the openssl ES256 JWT builder round-trips.
#
# The LIVE ASC API path (network + real .p8) is intentionally NOT covered here —
# it needs the real secret and a reachable Apple endpoint; that half is
# calibrated once against the live account (see store-preflight.sh header).
#
# Same style as test-check-doc-drift.sh / test-validate-demo-assets.sh, and runs
# in repo-hygiene alongside them.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/store-preflight.sh"
PASS=0; FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL + 1)); }

# run_fake <fake-json> [args...] → sets OUT, RC, and JSON summary at $TMP/out.json
run_fake() {
  local fake="$1"; shift || true
  echo "$fake" > "$TMP/fake.json"
  set +e
  OUT="$(STORE_PREFLIGHT_FAKE="$TMP/fake.json" STORE_PREFLIGHT_JSON_OUT="$TMP/out.json" \
        bash "$SCRIPT" "$@" 2>&1)"; RC=$?
  set -e
}
verdict() { jq -r '.verdict' "$TMP/out.json" 2>/dev/null || echo "?"; }

echo "test-store-preflight.sh"

# 0. shellcheck-clean (guard the guard).
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck "$SCRIPT" >/dev/null 2>&1 \
    && ok "store-preflight.sh is shellcheck-clean" \
    || bad "store-preflight.sh has shellcheck findings"
else
  echo "  · shellcheck not installed — skipping lint assertion"
fi

# 1. --help exits 0.
set +e; bash "$SCRIPT" --help >/dev/null 2>&1; RC=$?; set -e
[ "$RC" -eq 0 ] && ok "--help exits 0" || bad "--help should exit 0 (rc=$RC)"

# 2. No creds → SKIP, exit 0, JSON verdict=skip (never a fake green).
set +e
OUT="$(env -u APP_STORE_CONNECT_KEY_ID -u APP_STORE_CONNECT_ISSUER_ID \
           -u APP_STORE_CONNECT_API_KEY -u APP_STORE_CONNECT_API_KEY_PATH \
           -u ASC_KEY_ID -u ASC_ISSUER_ID -u ASC_API_KEY -u ASC_API_KEY_PATH \
           STORE_PREFLIGHT_JSON_OUT="$TMP/nocreds.json" bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ [ "$RC" -eq 0 ] && [ "$(jq -r '.verdict' "$TMP/nocreds.json")" = "skip" ] && grep -q "\[SKIP\]" <<<"$OUT"; } \
  && ok "no creds → SKIP + exit 0 + verdict=skip" \
  || bad "no creds should SKIP honestly, exit 0 (rc=$RC)"

# 3. openssl ES256 JWT builder round-trips.
set +e; OUT="$(bash "$SCRIPT" --selftest-jwt 2>&1)"; RC=$?; set -e
{ [ "$RC" -eq 0 ] && grep -q "selftest-jwt: OK" <<<"$OUT"; } \
  && ok "openssl ES256 JWT signer verifies" \
  || bad "JWT self-test should pass (rc=$RC): $OUT"

# 4. All-healthy fake → verdict=pass, exit 0.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":200,"profileDays":150}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "pass" ]; } \
  && ok "all-healthy → verdict=pass, exit 0" \
  || bad "all-healthy should be pass/exit0 (rc=$RC verdict=$(verdict))"

# 5. Expired agreements → verdict=blocked, but ADVISORY (exit 0 without --hard).
run_fake '{"agreements":"missing","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":200,"profileDays":150}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "blocked" ] && grep -qi "agreements" <<<"$OUT"; } \
  && ok "expired agreements → blocked, advisory exit 0" \
  || bad "expired agreements should be blocked/exit0 (rc=$RC verdict=$(verdict))"

# 6. Expired agreements + --hard → exit 1 (opt-in hard gate).
run_fake '{"agreements":"missing","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":200,"profileDays":150}' --hard
{ [ "$RC" -eq 1 ] && [ "$(verdict)" = "blocked" ]; } \
  && ok "--hard promotes blocked → exit 1" \
  || bad "--hard should exit 1 on a blocker (rc=$RC verdict=$(verdict))"

# 7. REJECTED App Review → verdict=warn (loud but non-blocking), exit 0.
run_fake '{"agreements":"ok","reviewState":"REJECTED","reviewVersion":"4.21.0","certDays":200,"profileDays":150}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "warn" ] && grep -qi "reject" <<<"$OUT"; } \
  && ok "REJECTED review → warn, exit 0" \
  || bad "REJECTED review should warn/exit0 (rc=$RC verdict=$(verdict))"

# 8. Cert inside the warn window → verdict=warn; threshold is env-overridable.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":12,"profileDays":150}'
[ "$(verdict)" = "warn" ] \
  && ok "cert expiring in 12d (< 30d) → warn" \
  || bad "near-expiry cert should warn (verdict=$(verdict))"

# 9. Same cert, but CERT_EXPIRY_WARN_DAYS lowered to 5 → back to pass.
echo '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":12,"profileDays":150}' > "$TMP/fake.json"
set +e
CERT_EXPIRY_WARN_DAYS=5 STORE_PREFLIGHT_FAKE="$TMP/fake.json" STORE_PREFLIGHT_JSON_OUT="$TMP/out.json" \
  bash "$SCRIPT" >/dev/null 2>&1; RC=$?
set -e
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "pass" ]; } \
  && ok "CERT_EXPIRY_WARN_DAYS override lowers the threshold" \
  || bad "threshold override should flip 12d back to pass (verdict=$(verdict))"

# 10. JSON summary carries the expected schema keys.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":200,"profileDays":150}'
KEYS_OK=$(jq -e '.schemaVersion==1 and .source=="app-store-connect-api" and (.apple|has("agreements")) and (.apple|has("reviewState")) and (.thresholds|has("certExpiryWarnDays"))' "$TMP/out.json" >/dev/null 2>&1 && echo yes || echo no)
[ "$KEYS_OK" = "yes" ] \
  && ok "JSON summary has the expected schema keys" \
  || bad "JSON summary is missing expected keys"

# 11. --json with no path → usage error, exit 2 (the documented contract — under
# `set -euo pipefail` a bare `shift 2` would instead exit 1 silently).
set +e; bash "$SCRIPT" --json >/dev/null 2>&1; RC=$?; set -e
[ "$RC" -eq 2 ] \
  && ok "--json with no value → exit 2 (usage contract)" \
  || bad "--json with no value should exit 2, not a silent exit 1 (rc=$RC)"

# 12. Stale open reviewSubmissions (#2731) → verdict=warn even though the
# version probe says READY_FOR_SALE — this is the silent-submission detector.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.18.0","certDays":200,"profileDays":150,"reviewSubmissionsStale":4}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "warn" ] && grep -qi "never submitted" <<<"$OUT"; } \
  && ok "stale open reviewSubmissions → warn (#2731 detector)" \
  || bad "stale reviewSubmissions should warn (rc=$RC verdict=$(verdict))"

# 13. Healthy in-flight review (WAITING_FOR_REVIEW, 0 stale) → still pass, and
# the JSON carries the in-flight state.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.22.0","certDays":200,"profileDays":150,"reviewSubmissionsStale":0,"reviewSubmissionsInFlight":"WAITING_FOR_REVIEW"}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "pass" ] \
  && [ "$(jq -r '.apple.reviewSubmissionsInFlight' "$TMP/out.json")" = "WAITING_FOR_REVIEW" ]; } \
  && ok "in-flight WAITING_FOR_REVIEW → pass + JSON carries state" \
  || bad "healthy in-flight submission should pass (rc=$RC verdict=$(verdict))"

# 14. Pre-#2731 fixture (no reviewSubmissions keys) → benign default, still pass.
run_fake '{"agreements":"ok","reviewState":"READY_FOR_SALE","reviewVersion":"4.21.2","certDays":200,"profileDays":150}'
{ [ "$RC" -eq 0 ] && [ "$(verdict)" = "pass" ] \
  && [ "$(jq -r '.apple.openReviewSubmissionsStale' "$TMP/out.json")" = "0" ]; } \
  && ok "fixture without reviewSubmissions keys defaults benign (0 stale)" \
  || bad "legacy fixture should default to 0 stale / pass (rc=$RC verdict=$(verdict))"

echo ""
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
