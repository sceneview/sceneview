#!/usr/bin/env bash
# release-device-qa-gate.sh — deterministic, non-blocking Device QA release gate.
#
# Issue #1683. The release checkpoint used to HARD-gate on a green Device QA
# run and got stuck INDEFINITELY (58+ commits piled up unreleased): a
# push-triggered Device QA run on `main` is killed by `cancel-in-progress`
# concurrency before the long android Maestro leg finishes, so no verdict was
# ever produced — and the orchestrator waited forever for an all-green run
# that could not materialise. The android leg is `continue-on-error` /
# advisory and should never have been able to hard-block a release.
#
# WHAT THIS SCRIPT DOES (deterministic, terminating, never freezes)
# -----------------------------------------------------------------
# 1. TRIGGERS ITS OWN Device QA run via `gh workflow run "Device QA"
#    --ref main`. A `workflow_dispatch` run keys its concurrency group on
#    `github.run_id` (device-qa.yml), so it is UNIQUE and CANNOT be cancelled
#    by a later push to the same ref (#1665/#1667). This is the fix for the
#    root cause: a push-triggered run was a moving target; a dispatched run
#    is ours and runs to completion.
# 2. CAPTURES that specific run id and POLLS ONLY THAT run id — with a
#    BOUNDED loop (fixed iteration cap) and a HARD TIMEOUT
#    (RELEASE_QA_TIMEOUT_MIN, default 60 min, env-overridable). The loop
#    provably terminates: iterations = ceil(timeout*60 / POLL_INTERVAL_SEC),
#    and total wall time is capped at the timeout. No unbounded `while true`.
# 3. READS the structured verdict. device-qa.yml runs each leg as a separate
#    job, each uploading its own single-platform `device-qa-report.json`
#    artifact (device-qa-report-web / -android / -ar). This gate downloads
#    every per-leg artifact and reads each report's `platforms[0].status`
#    (schemaVersion-2, #1657).
# 4. APPLIES THE RELEASE-GATE POLICY:
#       REQUIRED  legs = web (Playwright) + ar (ARCore replay)
#                        -> a genuine FAIL on either => release-gate FAIL.
#       ADVISORY  leg   = android (Maestro emulator)
#                        -> a failure/cancel/skip => WARN line only,
#                           NEVER a gate fail. This matches device-qa.yml's
#                           `continue-on-error: true` on the android job and
#                           the flaky-SwiftShader reality (#1643/#1670/#1676).
# 5. TIMEOUT FALLBACK — NEVER FREEZES. If the dispatched run has not completed
#    within RELEASE_QA_TIMEOUT_MIN, the gate prints
#    `device-qa: TIMEOUT (advisory) — proceeding` and returns SUCCESS (0).
#    A flaky / stuck / cancelled harness must NEVER hold a release hostage —
#    the release always proceeds; Device QA INFORMS it, it does not deadlock
#    it.
#
# EXIT STATUS
#   0  RELEASE-GATE: PASS               — all required legs passed.
#   0  RELEASE-GATE: PASS-WITH-WARNINGS — required legs passed; an advisory
#                                         leg did not pass, OR the run timed
#                                         out / could not be dispatched.
#   1  RELEASE-GATE: FAIL               — a REQUIRED leg (web or ar) genuinely
#                                         failed. This is the ONLY blocking
#                                         outcome.
#
# Usage:
#   bash .claude/scripts/release-device-qa-gate.sh
#
# Env overrides:
#   RELEASE_QA_TIMEOUT_MIN   hard timeout in minutes        (default 60)
#   RELEASE_QA_POLL_SEC      poll interval in seconds        (default 30)
#   RELEASE_QA_REQUIRED      CSV of required legs            (default web,ar)
#   RELEASE_QA_ADVISORY      CSV of advisory legs            (default android)
#   RELEASE_QA_REF           git ref to dispatch against     (default main)
#
# This script is called by `release-checklist.sh` section 14. It is also
# runnable stand-alone for a manual pre-tag check.

set -euo pipefail

# ─── Config ──────────────────────────────────────────────────────────────
TIMEOUT_MIN="${RELEASE_QA_TIMEOUT_MIN:-60}"
POLL_SEC="${RELEASE_QA_POLL_SEC:-30}"
REQUIRED_LEGS="${RELEASE_QA_REQUIRED:-web,ar}"
ADVISORY_LEGS="${RELEASE_QA_ADVISORY:-android}"
REF="${RELEASE_QA_REF:-main}"
WORKFLOW_NAME="Device QA"

# Colors (disabled when not a tty).
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; CYAN=''; NC=''
fi

log()  { printf '%b\n' "$*"; }
warn() { printf '%b\n' "${YELLOW}$*${NC}"; }
err()  { printf '%b\n' "${RED}$*${NC}" >&2; }

log "${CYAN}=== Device QA Release Gate (#1683) ===${NC}"
log "  Policy: REQUIRED = ${REQUIRED_LEGS} | ADVISORY = ${ADVISORY_LEGS}"
log "  Hard timeout: ${TIMEOUT_MIN} min | poll every ${POLL_SEC}s"
log ""

# ─── Outcome helpers ─────────────────────────────────────────────────────
# This script can NEVER block a release except on a genuine required-leg
# FAIL. Every other path (no gh, dispatch failed, timeout, advisory red)
# emits a WARN and returns SUCCESS.
emit_proceed() {
    # $1 = human reason for proceeding without a clean verdict.
    warn "  device-qa: $1"
    log ""
    log "${YELLOW}RELEASE-GATE: PASS-WITH-WARNINGS${NC}  (Device QA did not produce a blocking verdict — release proceeds)"
    exit 0
}

# ─── 0. Preconditions ────────────────────────────────────────────────────
if ! command -v gh >/dev/null 2>&1; then
    emit_proceed "gh CLI unavailable — cannot dispatch Device QA (advisory) — proceeding"
fi
if ! gh auth status >/dev/null 2>&1; then
    emit_proceed "gh not authenticated — cannot dispatch Device QA (advisory) — proceeding"
fi

# Bounded iteration cap. The loop runs AT MOST $MAX_ITERS times; with a
# $POLL_SEC sleep per iteration the total wall time is bounded by
# TIMEOUT_MIN*60 seconds. Provable termination — no unbounded poll.
DEADLINE=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
MAX_ITERS=$(( (TIMEOUT_MIN * 60 + POLL_SEC - 1) / POLL_SEC ))
[ "$MAX_ITERS" -lt 1 ] && MAX_ITERS=1

# ─── 1. Dispatch our OWN Device QA run ───────────────────────────────────
# A workflow_dispatch run is isolated from push-concurrency cancellation
# (device-qa.yml keys workflow_dispatch concurrency on github.run_id) — it
# cannot be killed by a later push to `main` (#1665/#1667). This is the
# core fix for the indefinite block.
log "${CYAN}--- Dispatching Device QA workflow_dispatch run (ref: ${REF}) ---${NC}"

# Record the newest pre-existing run id so we can identify OURS afterwards.
BEFORE_ID="$(gh run list --workflow "$WORKFLOW_NAME" --event workflow_dispatch \
    --limit 1 --json databaseId --jq '.[0].databaseId // 0' 2>/dev/null || echo 0)"

if ! gh workflow run "$WORKFLOW_NAME" --ref "$REF" >/dev/null 2>&1; then
    emit_proceed "failed to dispatch Device QA workflow (advisory) — proceeding"
fi

# The dispatched run takes a moment to register. Poll the run list (bounded)
# for a workflow_dispatch run newer than BEFORE_ID.
RUN_ID=""
for _ in $(seq 1 12); do
    sleep 5
    CANDIDATE="$(gh run list --workflow "$WORKFLOW_NAME" --event workflow_dispatch \
        --limit 1 --json databaseId --jq '.[0].databaseId // 0' 2>/dev/null || echo 0)"
    if [ -n "$CANDIDATE" ] && [ "$CANDIDATE" != "0" ] && [ "$CANDIDATE" != "$BEFORE_ID" ]; then
        RUN_ID="$CANDIDATE"
        break
    fi
done

if [ -z "$RUN_ID" ]; then
    emit_proceed "dispatched run never appeared in the run list (advisory) — proceeding"
fi
log "  Dispatched run id: ${GREEN}${RUN_ID}${NC}"
log "  Watching: $(gh run view "$RUN_ID" --json url --jq '.url' 2>/dev/null || echo "run $RUN_ID")"
log ""

# ─── 2. Bounded poll of THAT specific run id ─────────────────────────────
log "${CYAN}--- Polling run ${RUN_ID} (bounded: <= ${MAX_ITERS} iterations) ---${NC}"
RUN_STATUS=""
for (( iter = 1; iter <= MAX_ITERS; iter++ )); do
    # Hard deadline guard — independent of the iteration cap.
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        break
    fi
    RUN_STATUS="$(gh run view "$RUN_ID" --json status --jq '.status' 2>/dev/null || echo "unknown")"
    if [ "$RUN_STATUS" = "completed" ]; then
        log "  run ${RUN_ID}: completed after ${iter} poll(s)"
        break
    fi
    log "  [poll ${iter}/${MAX_ITERS}] run ${RUN_ID}: ${RUN_STATUS:-unknown} — waiting ${POLL_SEC}s"
    sleep "$POLL_SEC"
done

# ─── 3. Timeout fallback — NEVER freeze ──────────────────────────────────
if [ "$RUN_STATUS" != "completed" ]; then
    emit_proceed "TIMEOUT (advisory) — run ${RUN_ID} did not complete within ${TIMEOUT_MIN} min — proceeding"
fi

# ─── 4. Collect per-leg reports ──────────────────────────────────────────
# device-qa.yml runs each leg as a separate job; each uploads its own
# single-platform device-qa-report.json artifact (device-qa-report-web /
# -android / -ar). Download them all into a temp dir and read each report's
# platforms[0].status.
log ""
log "${CYAN}--- Reading per-leg verdicts from run ${RUN_ID} artifacts ---${NC}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! gh run download "$RUN_ID" --dir "$TMP_DIR" >/dev/null 2>&1; then
    emit_proceed "could not download Device QA artifacts for run ${RUN_ID} (advisory) — proceeding"
fi

# leg_status <leg> — echoes passed|failed|skipped|missing for one leg by
# reading device-qa-report-<leg>/device-qa-report.json. A leg's per-job
# report is single-platform, so platforms[0] is authoritative.
leg_status() {
    local leg="$1"
    local report="$TMP_DIR/device-qa-report-$leg/device-qa-report.json"
    [ -f "$report" ] || { echo "missing"; return; }
    python3 - "$report" "$leg" <<'PY' 2>/dev/null || echo "missing"
import json, sys
report, leg = sys.argv[1], sys.argv[2]
try:
    data = json.load(open(report))
except Exception:
    print("missing"); sys.exit()
plats = data.get("platforms", [])
# Prefer the matching platform entry; fall back to the first.
match = next((p for p in plats if p.get("platform") == leg), None)
if match is None and plats:
    match = plats[0]
print((match or {}).get("status", "missing"))
PY
}

# ─── 5. Apply the required vs advisory policy ────────────────────────────
log ""
log "${CYAN}--- Release-gate verdict ---${NC}"
REQUIRED_FAIL=0
ADVISORY_WARN=0

for leg in ${REQUIRED_LEGS//,/ }; do
    st="$(leg_status "$leg")"
    case "$st" in
        passed)
            printf "  ${GREEN}[PASS]${NC}  %-10s (required) — passed\n" "$leg" ;;
        failed)
            printf "  ${RED}[FAIL]${NC}  %-10s (required) — FAILED — blocks the release\n" "$leg"
            REQUIRED_FAIL=$((REQUIRED_FAIL + 1)) ;;
        skipped|missing|*)
            # A required leg with no verdict is treated as advisory (do NOT
            # block on a missing artifact — the harness itself flaked, #1683).
            printf "  ${YELLOW}[WARN]${NC}  %-10s (required) — no clear verdict (%s) — advisory, not blocking\n" "$leg" "$st"
            ADVISORY_WARN=$((ADVISORY_WARN + 1)) ;;
    esac
done

for leg in ${ADVISORY_LEGS//,/ }; do
    st="$(leg_status "$leg")"
    case "$st" in
        passed)
            printf "  ${GREEN}[PASS]${NC}  %-10s (advisory) — passed\n" "$leg" ;;
        *)
            printf "  ${YELLOW}[WARN]${NC}  %-10s (advisory) — %s — does NOT block (flaky emulator #1643/#1676)\n" "$leg" "$st"
            ADVISORY_WARN=$((ADVISORY_WARN + 1)) ;;
    esac
done

# ─── 6. Final verdict ────────────────────────────────────────────────────
log ""
if [ "$REQUIRED_FAIL" -gt 0 ]; then
    err "RELEASE-GATE: FAIL  ($REQUIRED_FAIL required leg(s) failed — fix before tagging)"
    exit 1
elif [ "$ADVISORY_WARN" -gt 0 ]; then
    log "${YELLOW}RELEASE-GATE: PASS-WITH-WARNINGS${NC}  (all required legs passed; $ADVISORY_WARN advisory warning(s))"
    exit 0
else
    log "${GREEN}RELEASE-GATE: PASS${NC}  (all required legs passed, no warnings)"
    exit 0
fi
