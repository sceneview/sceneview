#!/usr/bin/env bash
#
# play-vitals.sh — Android Vitals release-gate signal (#1691).
#
# WHY ───────────────────────────────────────────────────────────────────────
# The device-QA harness (#1560) and `release-checklist.sh` validate the demo
# app *before* release — on emulators. They have zero visibility into
# REAL-WORLD stability once the app is live on the Play Store. Google Play's
# Android Vitals exposes the actual crash rate and ANR rate across real user
# devices, and the Play Developer Reporting API makes those numbers queryable.
#
# WHAT IT DOES ───────────────────────────────────────────────────────────────
# Queries the **Play Developer Reporting API** (playdeveloperreporting.
# googleapis.com) for `io.github.sceneview.demo`:
#
#   - crashRateMetricSet  → userPerceivedCrashRate28dUserWeighted
#   - anrRateMetricSet    → userPerceivedAnrRate28dUserWeighted
#
# The 28-day user-weighted user-perceived rates are the SAME numbers Google
# uses for the Play Console "bad behaviour thresholds" badge, so they are the
# right release-gate signal. It then grades them:
#
#   crash rate  <  WARN_CRASH (default 1.09%)  → PASS
#               >= WARN_CRASH                  → WARN
#               >= FAIL_CRASH (default 2.50%)  → FAIL  (only when GATE_HARD=1)
#   anr  rate   <  WARN_ANR   (default 0.47%)  → PASS
#               >= WARN_ANR                    → WARN
#               >= FAIL_ANR   (default 1.00%)  → FAIL  (only when GATE_HARD=1)
#
# The 1.09% / 0.47% soft thresholds mirror Google Play's own "bad behaviour"
# thresholds; FAIL thresholds are deliberately looser headroom.
#
# ADVISORY-FIRST (#1691) ─────────────────────────────────────────────────────
# Until a baseline is established the gate is ADVISORY: it never returns a
# blocking exit code on its own. A breach above the FAIL threshold is only
# turned into a hard `exit 1` when `GATE_HARD=1` is passed — that promotion to
# blocking is a deliberate, separate decision once the numbers are trusted.
# Any data-access problem (missing secret, 403 lacking the "View app quality
# information" permission, API error, no data yet for a fresh app) degrades to
# a WARN + `exit 0` — a missing metric must NEVER freeze a release.
#
# OUTPUT ─────────────────────────────────────────────────────────────────────
#   - Human-readable lines on stdout.
#   - If `VITALS_JSON_OUT` is set, a machine-readable JSON summary is written
#     there (consumed by release-checklist.sh / the release summary).
#
# PERMISSION REQUIRED (manual, Play Console) ─────────────────────────────────
# The deploy service account (`PLAY_STORE_SERVICE_ACCOUNT_JSON`) needs the
# read-only **"View app quality information"** Play Console permission. No new
# WRITE scope is added. Until that permission is granted the API returns 403
# and this script degrades to WARN.
#
# Usage:
#   PLAY_STORE_SERVICE_ACCOUNT_JSON='<json>' bash .claude/scripts/play-vitals.sh
#   GATE_HARD=1 ... bash .claude/scripts/play-vitals.sh     # blocking mode
#
# Env overrides:
#   PLAY_STORE_SERVICE_ACCOUNT_JSON  service-account JSON (required for data)
#   PACKAGE_NAME                     default io.github.sceneview.demo
#   VITALS_WINDOW_DAYS               lookback window, default 14
#   WARN_CRASH / FAIL_CRASH          crash-rate thresholds (%), 1.09 / 2.50
#   WARN_ANR   / FAIL_ANR            anr-rate thresholds (%),  0.47 / 1.00
#   GATE_HARD                        1 = breach above FAIL → exit 1
#   VITALS_JSON_OUT                  path to write the JSON summary
#
# Exit codes:
#   0  data healthy, advisory WARN, or any data-access degradation
#   1  GATE_HARD=1 AND a metric breached its FAIL threshold
#
# Closes #1691.

set -u

PACKAGE_NAME="${PACKAGE_NAME:-io.github.sceneview.demo}"
VITALS_WINDOW_DAYS="${VITALS_WINDOW_DAYS:-14}"
WARN_CRASH="${WARN_CRASH:-1.09}"
FAIL_CRASH="${FAIL_CRASH:-2.50}"
WARN_ANR="${WARN_ANR:-0.47}"
FAIL_ANR="${FAIL_ANR:-1.00}"
GATE_HARD="${GATE_HARD:-0}"
VITALS_JSON_OUT="${VITALS_JSON_OUT:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

VERDICT="warn"          # pass | warn | blocked
CRASH_RATE=""
ANR_RATE=""
DETAIL=""

emit_json() {
  # Best-effort machine-readable summary for release-checklist.sh.
  [ -z "$VITALS_JSON_OUT" ] && return 0
  python3 - "$VITALS_JSON_OUT" "$VERDICT" "$CRASH_RATE" "$ANR_RATE" \
            "$WARN_CRASH" "$FAIL_CRASH" "$WARN_ANR" "$FAIL_ANR" \
            "$VITALS_WINDOW_DAYS" "$DETAIL" << 'PYEOF' 2>/dev/null || true
import json, sys
(out, verdict, crash, anr, wc, fc, wa, fa, win, detail) = sys.argv[1:11]
def num(x):
    try: return float(x)
    except Exception: return None
json.dump({
    "schemaVersion": 1,
    "source": "play-developer-reporting-api",
    "metricSet": ["crashRateMetricSet", "anrRateMetricSet"],
    "metric": "userPerceived*Rate28dUserWeighted",
    "windowDays": int(win),
    "verdict": verdict,
    "crashRatePct": num(crash),
    "anrRatePct": num(anr),
    "thresholds": {
        "warnCrashPct": num(wc), "failCrashPct": num(fc),
        "warnAnrPct": num(wa),   "failAnrPct": num(fa),
    },
    "detail": detail,
}, open(out, "w"), indent=2)
PYEOF
}

degrade() {
  # Data-access problem: WARN, never block. Always exit 0.
  VERDICT="warn"
  DETAIL="$1"
  echo -e "  ${YELLOW}[WARN]${NC} Android Vitals: $1"
  echo -e "  ${YELLOW}      ${NC} advisory — release not blocked by an unreadable metric."
  emit_json
  exit 0
}

echo -e "${CYAN}=== Android Vitals release-gate (#1691) ===${NC}"
echo -e "Package: ${GREEN}$PACKAGE_NAME${NC}  window: last ${VITALS_WINDOW_DAYS}d"

# ── Pre-flight ──────────────────────────────────────────────────────────────
if [ -z "${PLAY_STORE_SERVICE_ACCOUNT_JSON:-}" ]; then
  degrade "PLAY_STORE_SERVICE_ACCOUNT_JSON not set — cannot reach the Reporting API."
fi
if ! command -v python3 >/dev/null 2>&1; then
  degrade "python3 not available — cannot query the Reporting API."
fi

# ── Query the Play Developer Reporting API ──────────────────────────────────
# A single python helper authenticates with the service account and queries
# both metric sets. It prints one of:
#   OK <crashRatePct> <anrRatePct>
#   ERR <message>
RESULT=$(PACKAGE_NAME="$PACKAGE_NAME" VITALS_WINDOW_DAYS="$VITALS_WINDOW_DAYS" \
  python3 << 'PYEOF' 2>/dev/null
import json, os, sys, datetime

try:
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
except Exception as e:  # pragma: no cover - import guard
    print(f"ERR google-auth not installed ({e})")
    sys.exit(0)

PKG = os.environ["PACKAGE_NAME"]
WINDOW = int(os.environ.get("VITALS_WINDOW_DAYS", "14"))

try:
    info = json.loads(os.environ["PLAY_STORE_SERVICE_ACCOUNT_JSON"])
except Exception as e:
    print(f"ERR service-account JSON is not valid JSON ({e})")
    sys.exit(0)

try:
    creds = service_account.Credentials.from_service_account_info(
        info,
        scopes=["https://www.googleapis.com/auth/playdeveloperreporting"],
    )
    sess = AuthorizedSession(creds)
except Exception as e:
    print(f"ERR could not build credentials ({e})")
    sys.exit(0)

BASE = "https://playdeveloperreporting.googleapis.com/v1beta1"

# Reporting-API DAILY aggregation runs on PST and lags a couple of days; ask
# for a generous window and take the most recent row that has data.
today = datetime.date.today()
start = today - datetime.timedelta(days=WINDOW + 3)


def query(metric_set, metric):
    """Query one metric set, return the most-recent non-null metric value (a
    fraction 0..1) or None. Errors raise so the caller can report them."""
    body = {
        "timelineSpec": {
            "aggregationPeriod": "DAILY",
            "startTime": {"year": start.year, "month": start.month, "day": start.day},
            "endTime": {"year": today.year, "month": today.month, "day": today.day},
        },
        "metrics": [metric],
    }
    url = f"{BASE}/apps/{PKG}/{metric_set}:query"
    r = sess.post(url, json=body)
    if r.status_code == 403:
        raise PermissionError(
            "403 Forbidden — the service account lacks the 'View app quality "
            "information' Play Console permission"
        )
    r.raise_for_status()
    rows = r.json().get("rows", [])
    latest = None
    for row in rows:
        for mv in row.get("metrics", []):
            if mv.get("metric") != metric:
                continue
            dv = mv.get("decimalValue") or {}
            val = dv.get("value")
            if val is None:
                continue
            # Keep the last row that carries a value (rows are time-ordered).
            latest = float(val)
    return latest


try:
    crash = query("crashRateMetricSet", "crashRate28dUserWeighted")
    if crash is None:
        crash = query("crashRateMetricSet", "userPerceivedCrashRate28dUserWeighted")
    anr = query("anrRateMetricSet", "anrRate28dUserWeighted")
    if anr is None:
        anr = query("anrRateMetricSet", "userPerceivedAnrRate28dUserWeighted")
except PermissionError as e:
    print(f"ERR {e}")
    sys.exit(0)
except Exception as e:
    print(f"ERR Reporting API request failed ({e})")
    sys.exit(0)

if crash is None and anr is None:
    print("ERR no crash/ANR data yet — app may be too new or below the "
          "Play Console reporting threshold")
    sys.exit(0)

# The Reporting API returns rates as fractions (0..1). Convert to percent.
crash_pct = "" if crash is None else f"{crash * 100:.4f}"
anr_pct = "" if anr is None else f"{anr * 100:.4f}"
print(f"OK {crash_pct or 'NA'} {anr_pct or 'NA'}")
PYEOF
)

case "$RESULT" in
  "OK "*)
    CRASH_RATE=$(echo "$RESULT" | awk '{print $2}')
    ANR_RATE=$(echo "$RESULT" | awk '{print $3}')
    [ "$CRASH_RATE" = "NA" ] && CRASH_RATE=""
    [ "$ANR_RATE" = "NA" ] && ANR_RATE=""
    ;;
  "ERR "*)
    degrade "${RESULT#ERR }"
    ;;
  *)
    degrade "Reporting API helper produced no output."
    ;;
esac

# ── Grade ───────────────────────────────────────────────────────────────────
# `grade <name> <value> <warn> <fail>` echoes pass|warn|blocked.
grade() {
  local name="$1" val="$2" warn="$3" fail="$4"
  if [ -z "$val" ]; then
    echo -e "  ${YELLOW}[WARN]${NC} $name: no data in window" >&2
    echo "warn"
    return
  fi
  # Compare as floats via awk.
  if awk -v v="$val" -v f="$fail" 'BEGIN{exit !(v>=f)}'; then
    echo -e "  ${RED}[FAIL]${NC} $name: ${val}% (>= ${fail}% hard threshold)" >&2
    echo "blocked"
  elif awk -v v="$val" -v w="$warn" 'BEGIN{exit !(v>=w)}'; then
    echo -e "  ${YELLOW}[WARN]${NC} $name: ${val}% (>= ${warn}% soft threshold)" >&2
    echo "warn"
  else
    echo -e "  ${GREEN}[PASS]${NC} $name: ${val}% (< ${warn}% soft threshold)" >&2
    echo "pass"
  fi
}

CRASH_VERDICT=$(grade "crash rate" "$CRASH_RATE" "$WARN_CRASH" "$FAIL_CRASH")
ANR_VERDICT=$(grade "ANR rate"   "$ANR_RATE"   "$WARN_ANR"   "$FAIL_ANR")

# Worst verdict wins.
VERDICT="pass"
for v in "$CRASH_VERDICT" "$ANR_VERDICT"; do
  case "$v" in
    blocked) VERDICT="blocked" ;;
    warn) [ "$VERDICT" != "blocked" ] && VERDICT="warn" ;;
  esac
done

DETAIL="crash=${CRASH_RATE:-NA}% anr=${ANR_RATE:-NA}% (28d user-weighted, window ${VITALS_WINDOW_DAYS}d)"

echo ""
case "$VERDICT" in
  pass)
    echo -e "${GREEN}Android Vitals: prod stability healthy.${NC} $DETAIL"
    ;;
  warn)
    echo -e "${YELLOW}Android Vitals: WARN — review prod stability before tagging.${NC} $DETAIL"
    ;;
  blocked)
    echo -e "${RED}Android Vitals: a metric breached its hard threshold.${NC} $DETAIL"
    ;;
esac

emit_json

# ── Exit policy ─────────────────────────────────────────────────────────────
# Advisory by default (#1691): only GATE_HARD=1 promotes a hard-threshold
# breach to a blocking exit code. WARN never blocks.
if [ "$VERDICT" = "blocked" ] && [ "$GATE_HARD" = "1" ]; then
  echo -e "${RED}::error::Android Vitals gate FAILED (GATE_HARD=1) — fix prod stability before releasing.${NC}"
  exit 1
fi
exit 0
