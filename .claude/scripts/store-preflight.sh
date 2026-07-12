#!/usr/bin/env bash
#
# store-preflight.sh — detect human-only store blockers BEFORE a deploy 403s (#2612 P1).
#
# WHY ───────────────────────────────────────────────────────────────────────
# SceneView's two deploy workflows (app-store.yml, play-store.yml) already sync
# listing text, graphics, and "what's new" as code. The 4.18–4.21 release pain
# was NOT a missing sync — it was the *undetected* class of blockers a human
# must clear in the ASC / Play Console web UI, which silently 403 the deploy:
#
#   - Apple Program License Agreement expiry  → every authenticated ASC API call
#     starts returning 403 FORBIDDEN with errorCode
#     REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED. This blocked deploys for days
#     before anyone realised the account, not the code, was the problem.
#   - App Review rejections (2.1 TrueDepth, 3.1.1 donation — #2534) discovered
#     by email archaeology instead of surfaced same-day.
#   - Distribution certificate / provisioning-profile expiry — the next
#     "deploy mysteriously 403s" class.
#
# This script is READ-ONLY detection. It never writes to either store and never
# clears a blocker (agreements, tax forms, Resolution Center replies are
# human-only, by policy). It detects, deep-links, and defers to a human.
#
# WHAT IT DOES (Apple / App Store Connect, P1) ───────────────────────────────
#   (a) Agreements canary — a cheap authenticated GET (/apps) that trips
#       REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED when the PLA lapses. There is NO
#       /agreements read endpoint — this error-code canary is the only key-auth
#       signal. We probe the single /apps endpoint (a param-free, always-valid
#       GET) and treat a REQUIRED_AGREEMENTS_* hit as a blocker.
#       TODO second canary candidate: /v1/financeReports — a different agreement
#       (Paid Apps / banking) can lapse without tripping /apps; that endpoint
#       needs a required `vendorNumber` param, so calibrate it live before wiring
#       it in (a malformed required-param probe would 400 before it could canary).
#   (b) App Review state — the latest appStoreVersion + its state (REJECTED /
#       WAITING_FOR_REVIEW / READY_FOR_SALE …) via the app's related-resource
#       URL. A REJECTED state is a loud WARN.
#   (c) Cert / profile expiry — /certificates + /profiles expirationDate; WARN
#       when the soonest expiry is inside CERT_EXPIRY_WARN_DAYS (default 30).
#
# ADVISORY-FIRST (mirrors play-vitals.sh #1691) ──────────────────────────────
# The gate never returns a blocking exit code on its own. Any data-access
# problem (no ASC key, a transient API error, a tool missing) degrades to a
# SKIP + exit 0 — a missing signal must NEVER freeze a release. A real blocker
# (expired agreement) is graded `blocked` but only promoted to a hard `exit 1`
# when GATE_HARD=1 is passed — a deliberate, separate decision once the probes
# are calibrated against the live account.
#
# AUTH — reuses app-store.yml's existing secrets, NO new scope ───────────────
# Builds an ES256 JWT (audience appstoreconnect-v1) from the same three secrets
# app-store.yml already consumes. No PyJWT / cryptography dependency: the JWT is
# signed with openssl only, so it runs identically in CI and on a laptop.
#
#   APP_STORE_CONNECT_KEY_ID     (aliases: APP_STORE_CONNECT_API_KEY_ID, ASC_KEY_ID)
#   APP_STORE_CONNECT_ISSUER_ID  (aliases: APP_STORE_CONNECT_API_ISSUER_ID, ASC_ISSUER_ID)
#   APP_STORE_CONNECT_API_KEY    the .p8 private-key CONTENT (alias: ASC_API_KEY)
#   APP_STORE_CONNECT_API_KEY_PATH  local convenience: path to the .p8 file
#
# When none are set (a local run without secrets) the whole Apple section SKIPs
# honestly — never a fake green.
#
# OUTPUT ─────────────────────────────────────────────────────────────────────
#   - Human-readable [PASS]/[WARN]/[SKIP]/[FAIL] lines on stdout, same format as
#     release-checklist.sh.
#   - If STORE_PREFLIGHT_JSON_OUT (or --json <path>) is set, a machine-readable
#     JSON summary is written there (consumed by release-checklist.sh §16).
#
# ENV OVERRIDES (every threshold is overridable) ─────────────────────────────
#   ASC_APP_ID                iOS/macOS app id (default 6761329763)
#   ASC_API_BASE              default https://api.appstoreconnect.apple.com/v1
#   CERT_EXPIRY_WARN_DAYS     warn under N days to expiry (default 30)
#   STORE_PREFLIGHT_CURL_TIMEOUT   per-request timeout seconds (default 25)
#   GATE_HARD                 1 = a `blocked` verdict → exit 1 (default 0)
#   STORE_PREFLIGHT_JSON_OUT  path to write the JSON summary
#
# EXIT CODES ─────────────────────────────────────────────────────────────────
#   0  healthy, advisory WARN, or any SKIP/degradation
#   1  GATE_HARD=1 AND a probe graded `blocked` (e.g. agreements expired)
#   2  bad usage (unknown flag)
#
# Part of #2612 P1 (store publishing as code — detection tier).

set -euo pipefail

# ── Config (all env-overridable) ─────────────────────────────────────────────
ASC_APP_ID="${ASC_APP_ID:-6761329763}"
ASC_API_BASE="${ASC_API_BASE:-https://api.appstoreconnect.apple.com/v1}"
CERT_EXPIRY_WARN_DAYS="${CERT_EXPIRY_WARN_DAYS:-30}"
CURL_TIMEOUT="${STORE_PREFLIGHT_CURL_TIMEOUT:-25}"
GATE_HARD="${GATE_HARD:-0}"
STORE_PREFLIGHT_JSON_OUT="${STORE_PREFLIGHT_JSON_OUT:-}"

# Secret env (accept the app-store.yml names + convenience aliases).
ASC_KEY_ID="${APP_STORE_CONNECT_KEY_ID:-${APP_STORE_CONNECT_API_KEY_ID:-${ASC_KEY_ID:-}}}"
ASC_ISSUER_ID="${APP_STORE_CONNECT_ISSUER_ID:-${APP_STORE_CONNECT_API_ISSUER_ID:-${ASC_ISSUER_ID:-}}}"
ASC_API_KEY="${APP_STORE_CONNECT_API_KEY:-${ASC_API_KEY:-}}"
ASC_API_KEY_PATH="${APP_STORE_CONNECT_API_KEY_PATH:-${ASC_API_KEY_PATH:-}}"

# Test-only seam (see test-store-preflight.sh): a JSON file of pre-canned probe
# results so the grading / gate / JSON logic is deterministically testable
# without live secrets. Never set in production.
STORE_PREFLIGHT_FAKE="${STORE_PREFLIGHT_FAKE:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── Accumulators ─────────────────────────────────────────────────────────────
VERDICT="skip"          # pass | warn | blocked | skip
DETAIL=""               # short human summary for the JSON / checklist line
AGREEMENTS_STATE="unknown"   # ok | missing | unknown
REVIEW_STATE=""              # e.g. REJECTED, READY_FOR_SALE
REVIEW_VERSION=""            # latest appStoreVersion string
CERT_MIN_DAYS=""             # soonest cert expiry (days), "" if unknown
PROFILE_MIN_DAYS=""          # soonest profile expiry (days), "" if unknown
declare -a NOTES=()          # per-probe machine notes for the JSON

# Rank helper: worst verdict wins. blocked > warn > skip > pass.
rank() { case "$1" in blocked) echo 3 ;; warn) echo 2 ;; skip) echo 1 ;; *) echo 0 ;; esac; }
bump() { # bump <new-verdict>
  local a b
  a=$(rank "$VERDICT"); b=$(rank "$1")
  [ "$b" -gt "$a" ] && VERDICT="$1"
  return 0
}

line() { # line <PASS|WARN|SKIP|FAIL> <name> <detail>
  local s="$1" name="$2" detail="${3:-}"
  case "$s" in
    PASS) printf "  ${GREEN}[PASS]${NC}  %-42s %s\n" "$name" "$detail" ;;
    FAIL) printf "  ${RED}[FAIL]${NC}  %-42s %s\n" "$name" "$detail" ;;
    WARN) printf "  ${YELLOW}[WARN]${NC}  %-42s %s\n" "$name" "$detail" ;;
    SKIP) printf "  ${CYAN}[SKIP]${NC}  %-42s %s\n" "$name" "$detail" ;;
  esac
  return 0
}

usage() {
  sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Usage:
  bash .claude/scripts/store-preflight.sh [--json <path>] [--hard]
  STORE_PREFLIGHT_JSON_OUT=out.json bash .claude/scripts/store-preflight.sh

Flags:
  --json <path>   write the machine-readable summary to <path>
  --hard          same as GATE_HARD=1 (a `blocked` verdict → exit 1)
  -h, --help      show this help
EOF
}

# ── Arg parse ────────────────────────────────────────────────────────────────
while [ "$#" -gt 0 ]; do
  case "$1" in
    --json) [ -n "${2:-}" ] || { echo "--json requires a path" >&2; usage >&2; exit 2; }; STORE_PREFLIGHT_JSON_OUT="$2"; shift 2 ;;
    --json=*) STORE_PREFLIGHT_JSON_OUT="${1#*=}"; shift ;;
    --hard) GATE_HARD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --selftest-jwt) SELFTEST_JWT=1; shift ;;   # test-only: sign+verify a throwaway key
    *) echo "store-preflight.sh: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

# ── ES256 JWT (openssl-only, no PyJWT) ───────────────────────────────────────
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# DER ECDSA signature (binary on stdin) → JOSE raw R||S (64 bytes) base64url, P-256.
der_to_jose() {
  local hex pos b rlen slen r s
  hex=$(xxd -p -c 512 | tr -d '\n')
  [ "${hex:0:2}" = "30" ] || { echo "der:bad-seq" >&2; return 1; }
  pos=2
  b="${hex:$pos:2}"; pos=$((pos + 2))
  [ "$b" = "81" ] && pos=$((pos + 2))     # long-form length byte
  [ "${hex:$pos:2}" = "02" ] || { echo "der:bad-r" >&2; return 1; }
  pos=$((pos + 2))
  rlen=$((16#${hex:$pos:2})); pos=$((pos + 2))
  r="${hex:$pos:$((rlen * 2))}"; pos=$((pos + rlen * 2))
  [ "${hex:$pos:2}" = "02" ] || { echo "der:bad-s" >&2; return 1; }
  pos=$((pos + 2))
  slen=$((16#${hex:$pos:2})); pos=$((pos + 2))
  s="${hex:$pos:$((slen * 2))}"
  [ "$rlen" -eq 33 ] && r="${r:2}"        # strip DER sign-padding byte
  [ "$slen" -eq 33 ] && s="${s:2}"
  while [ "${#r}" -lt 64 ]; do r="0$r"; done
  while [ "${#s}" -lt 64 ]; do s="0$s"; done
  printf '%s%s' "$r" "$s" | xxd -r -p | b64url
}

asc_jwt() { # asc_jwt <key-file> <kid> <iss>  → signed JWT on stdout
  local key_file="$1" kid="$2" iss="$3" now exp header payload signing sig
  now=$(date +%s); exp=$((now + 900))
  header=$(printf '{"alg":"ES256","kid":"%s","typ":"JWT"}' "$kid" | b64url)
  payload=$(printf '{"iss":"%s","iat":%d,"exp":%d,"aud":"appstoreconnect-v1"}' "$iss" "$now" "$exp" | b64url)
  signing="${header}.${payload}"
  sig=$(printf '%s' "$signing" | openssl dgst -sha256 -sign "$key_file" 2>/dev/null | der_to_jose) || return 1
  printf '%s.%s' "$signing" "$sig"
}

# Test-only: prove the signer round-trips against a throwaway P-256 key.
if [ "${SELFTEST_JWT:-0}" = "1" ]; then
  tmpd=$(mktemp -d)
  trap 'rm -rf "$tmpd"' EXIT
  openssl ecparam -genkey -name prime256v1 -noout 2>/dev/null \
    | openssl pkcs8 -topk8 -nocrypt -out "$tmpd/k.p8" 2>/dev/null
  jwt=$(asc_jwt "$tmpd/k.p8" KID ISS) || { echo "selftest-jwt: sign failed"; exit 1; }
  [ "$(printf '%s' "$jwt" | tr -cd '.' | wc -c | tr -d ' ')" = "2" ] \
    || { echo "selftest-jwt: not a 3-part JWT"; exit 1; }
  signing="${jwt%.*}"; josesig="${jwt##*.}"
  pad="$josesig"; while [ $(( ${#pad} % 4 )) -ne 0 ]; do pad="${pad}="; done
  rawhex=$(printf '%s' "$pad" | tr '_-' '/+' | openssl base64 -d -A | xxd -p -c 512 | tr -d '\n')
  rhex="${rawhex:0:64}"; shex="${rawhex:64:64}"
  derint() { local h="$1"; h="${h#"${h%%[!0]*}"}"; [ -z "$h" ] && h="00"; [ $(( ${#h} % 2 )) -ne 0 ] && h="0$h"; [ "$((16#${h:0:2}))" -ge 128 ] && h="00$h"; printf '02%02x%s' "$(( ${#h} / 2 ))" "$h"; }
  body="$(derint "$rhex")$(derint "$shex")"
  printf '30%02x%s' "$(( ${#body} / 2 ))" "$body" | xxd -r -p > "$tmpd/sig.der"
  openssl ec -in "$tmpd/k.p8" -pubout -out "$tmpd/pub.pem" 2>/dev/null
  if printf '%s' "$signing" | openssl dgst -sha256 -verify "$tmpd/pub.pem" -signature "$tmpd/sig.der" >/dev/null 2>&1; then
    echo "selftest-jwt: OK"; exit 0
  fi
  echo "selftest-jwt: signature did not verify"; exit 1
fi

# ── ASC API GET (logs HTTP status, never the token) ──────────────────────────
# Echoes:  <http_status>\t<body>   (body may be empty on network failure)
# ASC memory rule: use direct related-resource URLs, NEVER a /relationships/…
# form, and log the HTTP status of every call.
ASC_JWT_TOKEN=""
ASC_HDR_FILE=""   # 0600 temp file holding the Authorization header (kept out of argv)
asc_get() { # asc_get <path-or-absolute-url>
  local url="$1" resp status body
  case "$url" in http*) : ;; *) url="${ASC_API_BASE%/}/$url" ;; esac
  # The bearer token goes via -H @<file> (curl ≥ 7.55), NEVER as a literal argv
  # header — argv is world-readable in the process table on a shared host.
  resp=$(curl -sS --max-time "$CURL_TIMEOUT" \
      -H @"$ASC_HDR_FILE" \
      -H "Accept: application/json" \
      -w $'\n%{http_code}' "$url" 2>/dev/null) || resp=$'\n000'
  status="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  # Log the HTTP status of every call (ASC memory rule). No secrets appear in
  # ASC query strings, and the bearer token is never logged.
  printf "  ${CYAN}·${NC} GET %s → HTTP %s\n" "${url#"$ASC_API_BASE"/}" "$status" >&2
  printf '%s\t%s' "$status" "$body"
  return 0
}

# ── JSON emit (best-effort; jq preferred, python3 fallback) ──────────────────
emit_json() {
  [ -z "$STORE_PREFLIGHT_JSON_OUT" ] && return 0
  local notes_json="[]"
  if [ "${#NOTES[@]}" -gt 0 ]; then
    notes_json=$(printf '%s\n' "${NOTES[@]}" | jq -R . 2>/dev/null | jq -s . 2>/dev/null || echo "[]")
  fi
  jq -n \
    --arg verdict "$VERDICT" \
    --arg detail "$DETAIL" \
    --arg agreements "$AGREEMENTS_STATE" \
    --arg reviewState "$REVIEW_STATE" \
    --arg reviewVersion "$REVIEW_VERSION" \
    --arg certDays "$CERT_MIN_DAYS" \
    --arg profileDays "$PROFILE_MIN_DAYS" \
    --arg appId "$ASC_APP_ID" \
    --argjson certWarnDays "$CERT_EXPIRY_WARN_DAYS" \
    --argjson notes "$notes_json" \
    '{
      schemaVersion: 1,
      source: "app-store-connect-api",
      appId: $appId,
      verdict: $verdict,
      detail: $detail,
      apple: {
        agreements: $agreements,
        reviewState: (if $reviewState == "" then null else $reviewState end),
        reviewVersion: (if $reviewVersion == "" then null else $reviewVersion end),
        certMinDaysToExpiry: (if $certDays == "" then null else ($certDays|tonumber) end),
        profileMinDaysToExpiry: (if $profileDays == "" then null else ($profileDays|tonumber) end)
      },
      thresholds: { certExpiryWarnDays: $certWarnDays },
      notes: $notes
    }' > "$STORE_PREFLIGHT_JSON_OUT" 2>/dev/null || true
  return 0
}

skip_all() { # skip_all <reason>
  VERDICT="skip"; DETAIL="$1"
  line SKIP "App Store Connect preflight" "$1"
  echo -e "  ${CYAN}      ${NC} advisory — release not blocked by an unchecked store signal."
  emit_json
  exit 0
}

# ── Grading of pre-canned or live probe results ──────────────────────────────
grade_agreements() {
  case "$AGREEMENTS_STATE" in
    missing)
      NOTES+=("agreements: REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED — a human must accept the pending agreement at https://appstoreconnect.apple.com/agreements")
      line WARN "Apple agreements (canary)" "EXPIRED/MISSING — accept at appstoreconnect.apple.com/agreements"
      bump blocked ;;
    ok)
      line PASS "Apple agreements (canary)" "no REQUIRED_AGREEMENTS_* error on probe endpoints" ;;
    *)
      NOTES+=("agreements: could not determine (API unreachable / unexpected status)")
      line WARN "Apple agreements (canary)" "could not determine — API unreachable"
      bump warn ;;
  esac
}

grade_review() {
  local st="$REVIEW_STATE"
  case "$st" in
    "")
      NOTES+=("reviewState: no appStoreVersion returned")
      line WARN "App Review state" "no appStoreVersion returned"
      bump warn ;;
    *REJECT*|INVALID_BINARY)
      # *REJECT* already covers REJECTED / DEVELOPER_REJECTED / METADATA_REJECTED.
      NOTES+=("reviewState: $st on version ${REVIEW_VERSION:-?} — check ASC Resolution Center (#2534)")
      line WARN "App Review state" "${REVIEW_VERSION:-?}: $st — check Resolution Center"
      bump warn ;;
    *)
      line PASS "App Review state" "${REVIEW_VERSION:-?}: $st" ;;
  esac
}

grade_expiry() { # grade_expiry <label> <days> <notekey>
  local label="$1" days="$2" key="$3"
  if [ -z "$days" ]; then
    NOTES+=("$key: no expiry data returned")
    line WARN "$label expiry" "no data returned"
    bump warn
  elif [ "$days" -lt 0 ]; then
    NOTES+=("$key: EXPIRED ${days#-} day(s) ago")
    line WARN "$label expiry" "EXPIRED ${days#-}d ago"
    bump warn
  elif [ "$days" -lt "$CERT_EXPIRY_WARN_DAYS" ]; then
    NOTES+=("$key: expires in ${days}d (< ${CERT_EXPIRY_WARN_DAYS}d threshold)")
    line WARN "$label expiry" "expires in ${days}d (< ${CERT_EXPIRY_WARN_DAYS}d)"
    bump warn
  else
    line PASS "$label expiry" "expires in ${days}d"
  fi
}

# days_until <ISO8601-date> → integer days from now (may be negative). "" on parse fail.
days_until() {
  local d="$1" epoch now
  [ -z "$d" ] && { echo ""; return 0; }
  epoch=$(date -u -d "$d" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%S" "${d%%.*}" +%s 2>/dev/null || echo "")
  [ -z "$epoch" ] && { echo ""; return 0; }
  now=$(date -u +%s)
  echo $(( (epoch - now) / 86400 ))
}

echo -e "${CYAN}=== Store preflight — Apple / App Store Connect (#2612) ===${NC}"
echo -e "App id: ${GREEN}$ASC_APP_ID${NC}"

# ── Test seam: pre-canned probe results ──────────────────────────────────────
if [ -n "$STORE_PREFLIGHT_FAKE" ]; then
  [ -f "$STORE_PREFLIGHT_FAKE" ] || { echo "STORE_PREFLIGHT_FAKE file not found: $STORE_PREFLIGHT_FAKE" >&2; exit 2; }
  AGREEMENTS_STATE=$(jq -r '.agreements // "unknown"' "$STORE_PREFLIGHT_FAKE")
  REVIEW_STATE=$(jq -r '.reviewState // ""' "$STORE_PREFLIGHT_FAKE")
  REVIEW_VERSION=$(jq -r '.reviewVersion // ""' "$STORE_PREFLIGHT_FAKE")
  CERT_MIN_DAYS=$(jq -r '(.certDays // "") | tostring' "$STORE_PREFLIGHT_FAKE"); [ "$CERT_MIN_DAYS" = "null" ] && CERT_MIN_DAYS=""
  PROFILE_MIN_DAYS=$(jq -r '(.profileDays // "") | tostring' "$STORE_PREFLIGHT_FAKE"); [ "$PROFILE_MIN_DAYS" = "null" ] && PROFILE_MIN_DAYS=""
  VERDICT="pass"
  grade_agreements
  grade_review
  grade_expiry "Distribution certificate" "$CERT_MIN_DAYS" "certificate"
  grade_expiry "Provisioning profile" "$PROFILE_MIN_DAYS" "profile"
else
  # ── Preconditions ──────────────────────────────────────────────────────────
  for tool in openssl curl jq xxd; do
    command -v "$tool" >/dev/null 2>&1 || skip_all "$tool not available — cannot run the ASC preflight."
  done
  if [ -z "$ASC_KEY_ID" ] || [ -z "$ASC_ISSUER_ID" ] || { [ -z "$ASC_API_KEY" ] && [ -z "$ASC_API_KEY_PATH" ]; }; then
    skip_all "ASC API creds not set (APP_STORE_CONNECT_KEY_ID / _ISSUER_ID / _API_KEY) — no live secrets."
  fi

  # Materialise the .p8 and the bearer header into private (0600) temp files.
  # The header file keeps the JWT out of the curl argv (process table).
  KEY_FILE="$(mktemp)"; chmod 600 "$KEY_FILE"
  ASC_HDR_FILE="$(mktemp)"; chmod 600 "$ASC_HDR_FILE"
  trap 'rm -f "$KEY_FILE" "$ASC_HDR_FILE"' EXIT
  if [ -n "$ASC_API_KEY_PATH" ]; then
    [ -f "$ASC_API_KEY_PATH" ] || skip_all "APP_STORE_CONNECT_API_KEY_PATH points at a missing file."
    cat "$ASC_API_KEY_PATH" > "$KEY_FILE"
  else
    printf '%s\n' "$ASC_API_KEY" > "$KEY_FILE"
  fi

  ASC_JWT_TOKEN=$(asc_jwt "$KEY_FILE" "$ASC_KEY_ID" "$ASC_ISSUER_ID") \
    || skip_all "could not sign the ASC JWT (bad .p8 key?) — nothing probed."
  printf 'Authorization: Bearer %s\n' "$ASC_JWT_TOKEN" > "$ASC_HDR_FILE"

  VERDICT="pass"

  # (a) Agreements canary — a single param-free GET /apps. A
  # REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED error-code in the body = blocker.
  # (See the header TODO for /financeReports as a second canary — deferred
  # because its required vendorNumber param would 400 before it could canary.)
  AGREEMENTS_STATE="ok"
  r=$(asc_get "apps?limit=1"); st="${r%%$'\t'*}"; body="${r#*$'\t'}"
  if printf '%s' "$body" | grep -q "REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED"; then
    AGREEMENTS_STATE="missing"
  # A 401 means the JWT/key is wrong, not that agreements lapsed — don't mislabel.
  # 000 = network failure. Either way the canary is inconclusive → unknown, which
  # grade_agreements renders as a conservative WARN (never a fake PASS).
  elif [ "$st" = "401" ] || [ "$st" = "000" ]; then
    AGREEMENTS_STATE="unknown"
  fi
  grade_agreements

  # (b) App Review state — latest appStoreVersion via the app's related-resource URL.
  # Direct related-resource form (NO /relationships/…): /apps/{id}/appStoreVersions.
  # ASC's appStoreVersions collection has NO documented `sort` param (an unknown
  # query param is rejected 400 PARAMETER_ERROR), and versionString sorts
  # lexicographically ('4.9.0' > '4.21.0'), so we page the versions and pick the
  # newest by createdDate client-side — that is the one currently in review.
  r=$(asc_get "apps/${ASC_APP_ID}/appStoreVersions?limit=200&fields%5BappStoreVersions%5D=versionString,appStoreState,createdDate")
  body="${r#*$'\t'}"
  latest=$(printf '%s' "$body" | jq -c 'if (.data|type=="array") and ((.data|length)>0) then (.data|max_by(.attributes.createdDate)) else null end' 2>/dev/null || echo "null")
  REVIEW_VERSION=$(printf '%s' "$latest" | jq -r '.attributes.versionString // ""' 2>/dev/null || echo "")
  REVIEW_STATE=$(printf '%s' "$latest" | jq -r '.attributes.appStoreState // ""' 2>/dev/null || echo "")
  grade_review

  # (c) Cert + profile expiry.
  r=$(asc_get "certificates?limit=200&fields%5Bcertificates%5D=name,certificateType,expirationDate")
  body="${r#*$'\t'}"
  CERT_ISO=$(printf '%s' "$body" \
    | jq -r '[.data[]?.attributes | select(.certificateType|test("DISTRIBUTION|MAC_APP_DISTRIBUTION|MAC_INSTALLER_DISTRIBUTION")) | .expirationDate] | sort | .[0] // ""' 2>/dev/null || echo "")
  # Fall back to the soonest of ANY cert if no distribution cert matched.
  [ -z "$CERT_ISO" ] && CERT_ISO=$(printf '%s' "$body" | jq -r '[.data[]?.attributes.expirationDate] | sort | .[0] // ""' 2>/dev/null || echo "")
  CERT_MIN_DAYS=$(days_until "$CERT_ISO")
  grade_expiry "Distribution certificate" "$CERT_MIN_DAYS" "certificate"

  r=$(asc_get "profiles?limit=200&fields%5Bprofiles%5D=name,expirationDate,profileState")
  body="${r#*$'\t'}"
  PROFILE_ISO=$(printf '%s' "$body" | jq -r '[.data[]?.attributes | select(.profileState=="ACTIVE") | .expirationDate] | sort | .[0] // ""' 2>/dev/null || echo "")
  [ -z "$PROFILE_ISO" ] && PROFILE_ISO=$(printf '%s' "$body" | jq -r '[.data[]?.attributes.expirationDate] | sort | .[0] // ""' 2>/dev/null || echo "")
  PROFILE_MIN_DAYS=$(days_until "$PROFILE_ISO")
  grade_expiry "Provisioning profile" "$PROFILE_MIN_DAYS" "profile"
fi

# ── Summary + JSON + exit policy ─────────────────────────────────────────────
DETAIL="agreements=${AGREEMENTS_STATE} review=${REVIEW_VERSION:-?}:${REVIEW_STATE:-?} cert=${CERT_MIN_DAYS:-?}d profile=${PROFILE_MIN_DAYS:-?}d"
echo ""
case "$VERDICT" in
  pass)    echo -e "${GREEN}Store preflight: no store-side blockers detected.${NC} $DETAIL" ;;
  warn)    echo -e "${YELLOW}Store preflight: WARN — review before tagging.${NC} $DETAIL" ;;
  blocked) echo -e "${RED}Store preflight: a store-side blocker is present.${NC} $DETAIL" ;;
  skip)    echo -e "${CYAN}Store preflight: SKIPPED.${NC} $DETAIL" ;;
esac

emit_json

# Advisory-first: only GATE_HARD=1 promotes a `blocked` verdict to a hard exit.
if [ "$VERDICT" = "blocked" ] && [ "$GATE_HARD" = "1" ]; then
  echo -e "${RED}::error::Store preflight FAILED (GATE_HARD=1) — clear the store blocker before releasing.${NC}"
  exit 1
fi
exit 0
