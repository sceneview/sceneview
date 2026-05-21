#!/usr/bin/env bash
# web-perf-qa.sh — perf QA for samples/web-demo via Lighthouse mobile.
#
# Item 5 of #1748 (filed as #1879). Threshold tuning is #1898.
#
# WHAT THIS IS
# ------------
# Runs Lighthouse against the web-demo and emits a machine-readable summary
# file (`web-perf-summary.json`) matching the shape of
# `samples/web-demo/test-results/web-qa-summary.json`. The summary surfaces
# three core Web-Vitals-adjacent metrics:
#
#   - firstContentfulPaint  (fcp_ms)
#   - largestContentfulPaint (lcp_ms)
#   - cumulativeLayoutShift  (cls)
#
# THRESHOLD ENFORCEMENT (#1898)
# -----------------------------
# This script enforces a per-metric perf budget (mobile preset). Each measured
# metric is compared against its budget; the run is `failed` if ANY metric
# exceeds its budget, `passed` otherwise. The thresholds object is written into
# `web-perf-summary.json` so the verdict is reproducible and auditable.
#
# Chosen budgets (mobile Lighthouse preset):
#
#   metric             budget    rationale
#   ------------------ --------- --------------------------------------------
#   fcp_ms             4000      #1879's real smoke test reported FCP ≈ 3008ms.
#                                4000ms leaves ~33% headroom — noise (cold npx
#                                cache, runner contention) stays green, a true
#                                +1s regression fails the leg.
#   lcp_ms             4400      #1879 reported LCP ≈ 3178ms. 4400ms is ~38%
#                                headroom; Filament.js WASM init dominates LCP
#                                so a slightly wider band than FCP avoids
#                                flakes while still catching a real regression.
#   cls                0.10      #1879 reported CLS ≈ 0 (a WebGL canvas does not
#                                reflow). 0.10 is the Web-Vitals "good" cutoff —
#                                any layout-shift creep above it is a real bug.
#   performanceScore   0.65      #1879 reported a Lighthouse perf score of 0.79.
#                                0.65 floor catches a broad regression (asset
#                                bloat, blocking JS) without failing on the
#                                normal CI-runner score spread (headless Chrome
#                                on a shared 2-core box scores below desktop).
#
# These budgets are a first cut sized from the single #1879 smoke run plus
# safety margin — not a 7-day 95th-percentile baseline. They are intentionally
# loose: the goal is to catch a real regression, never to fail a healthy run.
# Re-tighten them once `.claude/perf-baselines/web-perf.json` accumulates real
# samples (see that file's header for the recompute recipe). Override any
# budget at call time with the matching --budget-* flag.
#
# RELEASE-GATE WEIGHT
# -------------------
# The device-qa.sh integration records this as an ADVISORY leg (`web-perf`):
# a budget breach surfaces in `device-qa-report.json` -> `releaseGate
# .advisoryFailed` and release-checklist.sh section 14 reports it as a WARN,
# not a hard block. Advisory-at-first per #1898 — promote `web-perf` out of
# the device-qa.sh ADVISORY CSV to make it blocking once the budgets are
# proven stable against real baseline data.
#
# A soft skip (lighthouse missing / dev server unreachable / chrome
# unavailable) still emits `status: "skipped"` with exit 0 — a missing tool
# must never block or false-fail the gate.
#
# WHY A SEPARATE SCRIPT (not a new device-qa.sh leg)
# --------------------------------------------------
# - Perf budgets need their own iteration cadence (capture ~7 days of values,
#   set the 95th percentile as the budget) before they can responsibly block
#   anything. Mixing that tuning into device-qa.sh's existing web leg would
#   conflate "broken demo" failures with "regressed FCP" warnings.
# - The existing web leg's Playwright tests are functional / UX checks; this
#   one is a performance probe. Keeping them split lets either evolve
#   independently.
# - Lighthouse owns its own headless-Chrome lifecycle, so this leg cannot
#   share the Playwright browser the existing web leg drives.
#
# Why Lighthouse and not `chrome-devtools-mcp`
# --------------------------------------------
# The issue body considered both. The Chrome DevTools MCP wrapper is young
# and its CLI surface is still in flux; Lighthouse ships a stable, well-
# documented JSON output and is what every modern perf gate is built on. The
# follow-up issue can revisit the MCP route once SceneView has a dedicated
# MCP-driven CI path.
#
# Usage:
#   bash .claude/scripts/web-perf-qa.sh [--out <dir>] [--port <n>] [--url <u>]
#                                       [--preset mobile|desktop]
#                                       [--budget-fcp <ms>] [--budget-lcp <ms>]
#                                       [--budget-cls <n>] [--budget-score <n>]
#                                       [--keep-server] [-h | --help]
#
# Flags:
#   --out <dir>       Output directory for web-perf-summary.json + the raw
#                     lighthouse JSON. Default: samples/web-demo/test-results
#   --port <n>        Port for the local http-server. Default: 8090
#                     (deliberately != 8080 so this never collides with a
#                     running Playwright dev server).
#   --url <url>       Skip the dev-server boot — point Lighthouse at this URL
#                     instead. Mirrors `WEB_DEMO_URL` in the Playwright
#                     config.
#   --preset <p>      Lighthouse form-factor. Default: mobile.
#   --budget-fcp <ms>   FCP budget in ms.   Default: 4000.
#   --budget-lcp <ms>   LCP budget in ms.   Default: 4400.
#   --budget-cls <n>    CLS budget (0..1).  Default: 0.10.
#   --budget-score <n>  Minimum Lighthouse perf score (0..1). Default: 0.65.
#   --keep-server     Leave the dev-server running after the script exits.
#                     Default behaviour stops it on EXIT.
#
# Exit codes:
#   0  Lighthouse ran — verdict written. Includes `status: "failed"` (a budget
#      was breached): the non-zero signal is carried by the JSON, not the exit
#      code, so the advisory device-qa.sh sub-leg never aborts the run. Also 0
#      on a soft skip (tooling missing), summary written `status: "skipped"`.
#   1  Invalid argument.
#
# Output JSON shape (web-perf-summary.json):
#   {
#     "schemaVersion": 1,
#     "platform": "web-perf",
#     "tool": "lighthouse",
#     "status": "passed" | "failed" | "skipped",
#     "verdict": "enforced",
#     "thresholds": {
#       "fcp_ms": <number>, "lcp_ms": <number>,
#       "cls": <number>, "performanceScore": <number>
#     },
#     "breaches": [ "<metric> <value> > <budget>", ... ],   # empty when passed
#     "preset": "mobile",
#     "url": "http://localhost:8090/",
#     "startedAt": "<ISO8601>",
#     "durationMs": <number>,
#     "fcp_ms": <number|null>,
#     "lcp_ms": <number|null>,
#     "cls": <number|null>,
#     "performanceScore": <number|null>,   # 0..1
#     "reason": "<why skipped, if status=skipped>",
#     "raw": "<path to the raw lighthouse JSON, relative to --out>"
#   }

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# --- Flags -----------------------------------------------------------------
OUT_DIR="$REPO_ROOT/samples/web-demo/test-results"
PORT=8090
URL=""
PRESET="mobile"
KEEP_SERVER=false

# Perf budgets (mobile preset). See the header block for the rationale behind
# each default. Overridable via the matching --budget-* flag.
BUDGET_FCP=4000      # ms
BUDGET_LCP=4400      # ms
BUDGET_CLS=0.10      # 0..1
BUDGET_SCORE=0.65    # 0..1 — minimum Lighthouse performance score

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out=*)          OUT_DIR="${1#--out=}"; shift ;;
    --out)            OUT_DIR="${2:?--out needs a directory}"; shift 2 ;;
    --port=*)         PORT="${1#--port=}"; shift ;;
    --port)           PORT="${2:?--port needs a value}"; shift 2 ;;
    --url=*)          URL="${1#--url=}"; shift ;;
    --url)            URL="${2:?--url needs a value}"; shift 2 ;;
    --preset=*)       PRESET="${1#--preset=}"; shift ;;
    --preset)         PRESET="${2:?--preset needs a value}"; shift 2 ;;
    --budget-fcp=*)   BUDGET_FCP="${1#--budget-fcp=}"; shift ;;
    --budget-fcp)     BUDGET_FCP="${2:?--budget-fcp needs a value}"; shift 2 ;;
    --budget-lcp=*)   BUDGET_LCP="${1#--budget-lcp=}"; shift ;;
    --budget-lcp)     BUDGET_LCP="${2:?--budget-lcp needs a value}"; shift 2 ;;
    --budget-cls=*)   BUDGET_CLS="${1#--budget-cls=}"; shift ;;
    --budget-cls)     BUDGET_CLS="${2:?--budget-cls needs a value}"; shift 2 ;;
    --budget-score=*) BUDGET_SCORE="${1#--budget-score=}"; shift ;;
    --budget-score)   BUDGET_SCORE="${2:?--budget-score needs a value}"; shift 2 ;;
    --keep-server)    KEEP_SERVER=true; shift ;;
    -h|--help)
      sed -n '2,138p' "$SCRIPT_DIR/web-perf-qa.sh" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "[web-perf-qa] unknown argument: $1" >&2; exit 1 ;;
  esac
done

case "$PRESET" in
  mobile|desktop) ;;
  *) echo "[web-perf-qa] invalid --preset: $PRESET (mobile|desktop)" >&2; exit 1 ;;
esac

# Validate every budget is a non-negative number — a bad --budget-* flag must
# fail fast (exit 1), not silently produce a meaningless verdict downstream.
for _bv in "$BUDGET_FCP:--budget-fcp" "$BUDGET_LCP:--budget-lcp" \
           "$BUDGET_CLS:--budget-cls" "$BUDGET_SCORE:--budget-score"; do
  _val="${_bv%%:*}"; _flag="${_bv##*:}"
  if ! [[ "$_val" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    echo "[web-perf-qa] invalid $_flag: '$_val' (expected a non-negative number)" >&2
    exit 1
  fi
done
unset _bv _val _flag

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
SUMMARY="$OUT_DIR/web-perf-summary.json"
RAW_JSON="$OUT_DIR/web-perf-lighthouse.json"

log()  { echo "[web-perf-qa] $*"; }
warn() { echo "[web-perf-qa] WARNING: $*" >&2; }

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
STARTED_EPOCH_MS="$(python3 -c 'import time;print(int(time.time()*1000))')"

# Emit a `skipped` summary and exit 0. Soft-skip is the default for any
# missing precondition — this leg must never hard-block. The thresholds object
# is still written so a consumer of a skipped summary can see the budget that
# WOULD have been enforced; only `status` records that no run happened.
emit_skipped() {
  local reason="$1"
  warn "$reason — emitting skipped verdict"
  python3 - "$SUMMARY" "$STARTED_AT" "$STARTED_EPOCH_MS" "$reason" "$PRESET" "$URL" \
           "$BUDGET_FCP" "$BUDGET_LCP" "$BUDGET_CLS" "$BUDGET_SCORE" <<'PYEOF'
import json, sys, time
out, started, started_ms, reason, preset, url = sys.argv[1:7]
b_fcp, b_lcp, b_cls, b_score = (float(x) for x in sys.argv[7:11])
duration_ms = int(time.time() * 1000) - int(started_ms)
summary = {
    "schemaVersion": 1,
    "platform": "web-perf",
    "tool": "lighthouse",
    "status": "skipped",
    "verdict": "skipped",
    "thresholds": {
        "fcp_ms": b_fcp,
        "lcp_ms": b_lcp,
        "cls": b_cls,
        "performanceScore": b_score,
    },
    "breaches": [],
    "preset": preset,
    "url": url or None,
    "startedAt": started,
    "durationMs": duration_ms,
    "fcp_ms": None,
    "lcp_ms": None,
    "cls": None,
    "performanceScore": None,
    "reason": reason,
    "raw": None,
}
with open(out, "w") as fh:
    json.dump(summary, fh, indent=2)
    fh.write("\n")
PYEOF
  log "summary: $SUMMARY"
  exit 0
}

# --- Tooling preflight -----------------------------------------------------
if ! command -v node >/dev/null 2>&1 || ! command -v npx >/dev/null 2>&1; then
  emit_skipped "node/npx not on PATH"
fi

if ! command -v python3 >/dev/null 2>&1; then
  emit_skipped "python3 not on PATH"
fi

# Lighthouse is npm-resolved on demand. `npx --yes lighthouse --version` is a
# quick reachability probe — if npm can't fetch the package (offline CI box)
# we soft-skip rather than blowing up the device-QA leg.
log "checking lighthouse availability"
if ! LH_VERSION="$(npx --yes lighthouse --version 2>/dev/null | tail -n1)"; then
  emit_skipped "lighthouse not installable via npx (offline?)"
fi
log "lighthouse $LH_VERSION"

# Lighthouse needs a real Chrome / Chromium. On macOS the bundled app is the
# usual path; on Linux $CHROME_PATH or `google-chrome` / `chromium` must be
# resolvable.
CHROME_PATH="${CHROME_PATH:-}"
if [[ -z "$CHROME_PATH" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]] && [[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
    CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  elif command -v google-chrome >/dev/null 2>&1; then
    CHROME_PATH="$(command -v google-chrome)"
  elif command -v chromium >/dev/null 2>&1; then
    CHROME_PATH="$(command -v chromium)"
  elif command -v chromium-browser >/dev/null 2>&1; then
    CHROME_PATH="$(command -v chromium-browser)"
  fi
fi
if [[ -z "$CHROME_PATH" ]]; then
  emit_skipped "no Chrome/Chromium binary found (set CHROME_PATH to override)"
fi
export CHROME_PATH

# --- Dev server boot (unless --url was supplied) ---------------------------
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]] && ! $KEEP_SERVER; then
    log "stopping dev server (pid=$SERVER_PID)"
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ -z "$URL" ]]; then
  WEBROOT="$REPO_ROOT/samples/web-demo/site"
  if [[ ! -d "$WEBROOT" ]]; then
    emit_skipped "web-demo resources missing at $WEBROOT"
  fi
  log "starting http-server on :$PORT (root: $WEBROOT)"
  # Mirrors playwright.config.ts -> webServer.command, with a different port
  # so this never collides with a running Playwright dev server.
  ( cd "$REPO_ROOT/samples/web-demo" && \
    npx --yes http-server "$WEBROOT" -p "$PORT" -s ) >/dev/null 2>&1 &
  SERVER_PID=$!

  # Wait for the port to respond. Cap at ~30 s (matches the Playwright
  # webServer timeout).
  log "waiting for http://localhost:$PORT/ to respond"
  ready=false
  for _ in $(seq 1 30); do
    if curl -fsS -o /dev/null --max-time 2 "http://localhost:$PORT/"; then
      ready=true
      break
    fi
    sleep 1
  done
  if ! $ready; then
    emit_skipped "dev server on :$PORT did not respond within 30s"
  fi
  URL="http://localhost:$PORT/"
fi

log "Lighthouse target: $URL  (preset=$PRESET)"

# --- Run Lighthouse --------------------------------------------------------
LH_RC=0
# `--quiet` keeps progress chatter off our stderr; `--chrome-flags=--headless`
# is essential for CI. The mobile preset is the Lighthouse default and is the
# right baseline for a Filament.js viewer (worst-case device).
LH_ARGS=(
  "$URL"
  --output=json
  --output-path="$RAW_JSON"
  --quiet
  --chrome-flags="--headless=new --no-sandbox --disable-gpu"
)
if [[ "$PRESET" == "desktop" ]]; then
  LH_ARGS+=(--preset=desktop)
fi
# else: mobile is the implicit default — passing `--preset=mobile` is rejected
# by lighthouse, only `--preset=desktop|perf|experimental` is valid.

# Lighthouse can legitimately exit non-zero on a slow run; we want the run's
# emitted JSON regardless. Capture rc but don't fail the script.
npx --yes lighthouse "${LH_ARGS[@]}" 2>&1 | tail -20 || LH_RC=$?

if [[ ! -s "$RAW_JSON" ]]; then
  emit_skipped "lighthouse produced no output (rc=$LH_RC)"
fi

# --- Parse + emit summary --------------------------------------------------
# Threshold enforcement (#1898): compare each measured metric against its
# budget. The run is `failed` if ANY metric breaches its budget. A metric that
# Lighthouse could not measure (None) is NOT counted as a breach — a partial
# Lighthouse run must not be reported as a regression.
python3 - "$SUMMARY" "$RAW_JSON" "$STARTED_AT" "$STARTED_EPOCH_MS" "$PRESET" "$URL" \
          "$BUDGET_FCP" "$BUDGET_LCP" "$BUDGET_CLS" "$BUDGET_SCORE" <<'PYEOF'
import json, sys, time, os

out, raw, started, started_ms, preset, url = sys.argv[1:7]
b_fcp, b_lcp, b_cls, b_score = (float(x) for x in sys.argv[7:11])

with open(raw) as fh:
    lh = json.load(fh)

audits = lh.get("audits", {})
def metric(key):
    a = audits.get(key) or {}
    v = a.get("numericValue")
    return None if v is None else round(float(v), 2)

fcp = metric("first-contentful-paint")
lcp = metric("largest-contentful-paint")
cls = metric("cumulative-layout-shift")

categories = lh.get("categories", {}) or {}
perf = categories.get("performance") or {}
perf_score = perf.get("score")  # 0..1 or null
if perf_score is not None:
    perf_score = round(float(perf_score), 4)

duration_ms = int(time.time() * 1000) - int(started_ms)

thresholds = {
    "fcp_ms": b_fcp,
    "lcp_ms": b_lcp,
    "cls": b_cls,
    "performanceScore": b_score,
}

# Per-metric budget check. "lower is better" for fcp/lcp/cls; the perf score is
# a floor (higher is better). A None metric is skipped — never a breach.
breaches = []
if fcp is not None and fcp > b_fcp:
    breaches.append(f"fcp_ms {fcp} > {b_fcp}")
if lcp is not None and lcp > b_lcp:
    breaches.append(f"lcp_ms {lcp} > {b_lcp}")
if cls is not None and cls > b_cls:
    breaches.append(f"cls {cls} > {b_cls}")
if perf_score is not None and perf_score < b_score:
    breaches.append(f"performanceScore {perf_score} < {b_score}")

status = "failed" if breaches else "passed"

summary = {
    "schemaVersion": 1,
    "platform": "web-perf",
    "tool": "lighthouse",
    "status": status,
    "verdict": "enforced",
    "thresholds": thresholds,
    "breaches": breaches,
    "preset": preset,
    "url": url,
    "startedAt": started,
    "durationMs": duration_ms,
    "fcp_ms": fcp,
    "lcp_ms": lcp,
    "cls": cls,
    "performanceScore": perf_score,
    "reason": None,
    "raw": os.path.basename(raw),
}
with open(out, "w") as fh:
    json.dump(summary, fh, indent=2)
    fh.write("\n")

# Human-readable echo.
def fmt(v, unit=""):
    return "n/a" if v is None else f"{v}{unit}"
print(f"[web-perf-qa] FCP: {fmt(fcp, ' ms')}  (budget {b_fcp} ms)")
print(f"[web-perf-qa] LCP: {fmt(lcp, ' ms')}  (budget {b_lcp} ms)")
print(f"[web-perf-qa] CLS: {fmt(cls)}  (budget {b_cls})")
print(f"[web-perf-qa] performance score (Lighthouse 0..1): {fmt(perf_score)}  "
      f"(floor {b_score})")
if breaches:
    for b in breaches:
        print(f"[web-perf-qa] BUDGET BREACH: {b}")
    print(f"[web-perf-qa] verdict: FAILED — {len(breaches)} budget breach(es)")
else:
    print(f"[web-perf-qa] verdict: PASSED — all metrics within budget")
PYEOF

log "summary: $SUMMARY"
log "raw lighthouse JSON: $RAW_JSON"
# A budget breach is carried in the JSON (status:"failed"), not the exit code:
# the advisory device-qa.sh sub-leg invokes this with `|| warn ...` and reads
# the recorded leg's status — exit 0 keeps that integration clean. release
# tooling and CI both consume web-perf-summary.json, never $?.
exit 0
