#!/usr/bin/env bash
#
# claim-gate.sh — the Evidence-Stamped Claim Gate (ESCG, issue #2346).
#
# WHY ──────────────────────────────────────────────────────────────────────────
# The AI repeatedly told the maintainer "it works / QA complete / live" when it
# did NOT (iOS stuck on 4.0.3 while CI was green; demo QA reported complete on
# KEYLESS builds where Sketchfab/ARCore were never exercised). This gate makes a
# false success-claim physically unable to reach the remote: verifying tools
# stamp EVIDENCE on disk (device-qa.sh → device-qa-report.json, /store-status →
# .claude/data/last-store-probe.json), and this gate — wired onto the existing
# `git push` PreToolUse hook — blocks the push when the canonical STATE.md
# asserts a SUCCESS-CLAIM that lacks fresh, AGREEING evidence.
#
# It is deliberately NARROW. It fires ONLY on affirmative success-assertions
# ("QA complete ✅", "verified live", "deployed ✅"), NEVER on honest factual
# statements ("iOS LIVE=4.0.3 (4.17.0 in review)", "MERGED to main"). A bare
# "live" mention is not a claim — a claim token ADJACENT to a ✅ / "complete" /
# "all live" is. When in doubt about a referenced evidence file, it FAILS CLOSED
# (blocks) rather than waving a claim through on an unreadable file.
#
# EXIT ─────────────────────────────────────────────────────────────────────────
#   0  — no success-claim found, OR the claim is backed by fresh agreeing evidence.
#        (Silent on pass — a hook that chatters on every push gets ignored.)
#   1  — a success-claim is present but its evidence is missing / stale / disagrees.
#        One precise line on stderr names the file + the disagreement + the fix.
#
# Override (escape hatch for a genuine false-positive): set ESCG_BYPASS=1.
#
# macOS-safe: no flock, no GNU-only flags; deps are grep/awk/date/python3 (json).

set -uo pipefail

# Honour an explicit human override (documented escape hatch — a real claim that
# the gate mis-reads as unbacked can still ship after the human eyeballs it).
if [ "${ESCG_BYPASS:-0}" = "1" ]; then
  exit 0
fi

# ── Resolve the canonical (main-worktree) .claude/STATE.md ──────────────────────
# Same idiom as claim.sh: --git-common-dir points at the shared .git, whose parent
# is the main worktree root. STATE.md lives only there (it is gitignored and shared
# across every worktree on the host), so a push from any worktree reads the one
# canonical claim surface. Degrade to the toplevel / CWD if git is unavailable.
common_git="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "$common_git" ]; then
  MAIN_ROOT="$(dirname "$common_git")"
else
  MAIN_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
STATE="$MAIN_ROOT/.claude/STATE.md"

# No STATE.md → no persisted claim to gate. Nothing to do.
[ -f "$STATE" ] || exit 0

# ── FIRST LINE OF DEFENCE: only fire on an AFFIRMATIVE SUCCESS-CLAIM ────────────
# If STATE.md contains no success-assertion at all, exit 0 immediately. This keeps
# the gate silent for the overwhelming-common case (honest factual NOW bullets) and
# means a broadened pattern can never block a push that makes no claim.
#
# Precision rules baked into the patterns below — learned from real, honest
# STATE.md content that MUST NOT fire (e.g. "DEEP KEYED ANDROID QA done … NOT yet
# done", "live on Maven Central", "iOS LIVE=4.0.3"):
#   • A claim FIRES only when a success token sits ADJACENT to a ✅ stamp, OR is one
#     of the few unambiguous strong phrases ("verified live", "100% tested",
#     "all demos green/passed", "all live ✅"). The ✅ is the maintainer's success
#     stamp ("no 'works' without attached evidence") — honest WIP lines carry none.
#   • A BARE "QA done" / "is live" / "works" / "verified" is NOT a claim. Those
#     recur in honest lines ("QA done … NOT yet done", "I verified empirically",
#     'no "works" without evidence', "live on Maven Central"). We never match them
#     bare — only ✅-stamped or in the strong-phrase whitelist.
#   • Negated affirmations ("NOT live ✅"-style, "not yet tested") are stripped by a
#     precise, space-delimited negation guard that cannot match mid-word.

# QA-completion success-claims → must be backed by device-qa-report.json.
# Each alternative is ✅-anchored or an unambiguous completion phrase.
QA_PATTERN='(QA[[:space:]]*(complete|completed|done|passed|green)?[[:space:]]*✅)|(✅[[:space:]]*QA)|(QA[[:space:]]+(complete|done|passed|green)[[:space:]]*✅)|(100%[[:space:]]+(tested|passing|pass|green))|((all[[:space:]]+)?demos?[[:space:]]+(tested|pass(ed|ing)?|green)[[:space:]]*✅)|(all[[:space:]]+(demos?[[:space:]]+)?(pass(ed)?|green|tested))|((device[- ]?qa|emulator[[:space:]]+qa)[[:space:]]*[:=-]?[[:space:]]*(pass(ed)?|complete|green|clear)?[[:space:]]*✅)|(tested[[:space:]]*✅)|(✅[[:space:]]*tested)|(fully[[:space:]]+tested[[:space:]]*✅)'

# Store / live success-claims → must be backed by .claude/data/last-store-probe.json.
# ✅-anchored, or one of the strong "all live"/"verified live"/"deploys succeeded ✅"
# phrases. A bare "is live" (no ✅) is NOT matched.
STORE_PATTERN='(verified[[:space:]]+live)|(✅[[:space:]]*(live|deployed|shipped|published|in[[:space:]]+production))|((live|deployed|shipped|published)[[:space:]]*✅)|((is|now|fully|are[[:space:]]+all|already)[[:space:]]+live[[:space:]]*✅)|(all[[:space:]]+(live|deploys?[[:space:]]+green)[[:space:]]*✅?)|(all[[:space:]]+systems[[:space:]]+go)|(store[[:space:]]+deploys?[[:space:]]+(succeeded|green|live)[[:space:]]*✅?)|(everything[[:space:]]+(is[[:space:]]+)?live[[:space:]]*✅)'

# Strip negated lines so "NOT live", "never deployed", "not yet tested" can never
# match. Tokens are SPACE/PUNCT-delimited so they only match whole words — the
# earlier `n.t` wildcard wrongly nuked "on the emulator" (the `.` ate the space).
# A negated line is by definition not an affirmative success-claim, so blanking the
# whole physical line is safe and conservative.
NEG='(^|[^[:alnum:]])(not|never|without|awaiting|pending|isn.t|aren.t|wasn.t|weren.t|doesn.t|don.t|won.t|todo|fixme)([^[:alnum:]]|$)|(no[[:space:]]+(live|longer))|(not[[:space:]]+yet)|(still[[:space:]]+(in|awaiting|pending))|(in[[:space:]]+(app[[:space:]]+)?review)'

# Build the de-negated text once. Prefer the NOW section; if STATE.md has no NOW
# header, fall back to the entire file so a claim is never missed.
SCOPED="$(awk '
  BEGIN{innow=0; hasnow=0}
  /^##[[:space:]]+NOW/{innow=1; hasnow=1; next}
  /^##[[:space:]]/{ if(innow){innow=0} }
  { if(innow) print }
  END{ if(!hasnow) exit 3 }
' "$STATE" 2>/dev/null)"
if [ -z "$SCOPED" ]; then
  # No '## NOW' section (or it was empty) → scan the whole file.
  SCOPED="$(cat "$STATE")"
fi

# Drop negated lines (case-insensitive) so affirmations only remain.
DENEG="$(printf '%s\n' "$SCOPED" | grep -viE "$NEG" || true)"

has_qa_claim=0
has_store_claim=0
printf '%s\n' "$DENEG" | grep -qiE "$QA_PATTERN"    && has_qa_claim=1
printf '%s\n' "$DENEG" | grep -qiE "$STORE_PATTERN" && has_store_claim=1

# No affirmative success-claim anywhere → nothing to gate. Silent pass.
if [ "$has_qa_claim" -eq 0 ] && [ "$has_store_claim" -eq 0 ]; then
  exit 0
fi

# ── Session freshness reference ─────────────────────────────────────────────────
# "Fresh" = produced recently enough to be THIS session's. Without a portable,
# dependency-free way to pin the exact session start, we use a generous wall-clock
# window: evidence must be NEWER than (now − ceiling). Same-session evidence written
# minutes ago always passes; the multi-day-old staleness that caused the incidents
# (iOS report 3 weeks old, CI green) never does.
NOW_EPOCH="$(date -u +%s)"
# Session window ceiling: evidence older than this many seconds is "stale" even if
# we cannot prove the exact session start. 12h is comfortably longer than any real
# session yet far shorter than the multi-day staleness that caused the incidents.
STALE_CEILING_SEC="${ESCG_STALE_CEILING_SEC:-43200}"   # 12h
SESSION_FLOOR_EPOCH=$(( NOW_EPOCH - STALE_CEILING_SEC ))

# Portable mtime (epoch seconds) for a file. macOS `stat -f %m`, GNU `stat -c %Y`.
file_mtime_epoch() {
  local f="$1"
  if stat -f %m "$f" >/dev/null 2>&1; then
    stat -f %m "$f" 2>/dev/null
  else
    stat -c %Y "$f" 2>/dev/null
  fi
}

# Parse an ISO-8601-ish timestamp to epoch seconds, portably. Returns empty on fail.
iso_to_epoch() {
  local ts="$1"
  [ -n "$ts" ] || return 0
  python3 - "$ts" <<'PY' 2>/dev/null || true
import sys, datetime
s = sys.argv[1].strip().replace('Z', '+00:00')
try:
    dt = datetime.datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    print(int(dt.timestamp()))
except Exception:
    pass
PY
}

fail() {
  # One precise line: file + disagreement + fix. Non-zero exit blocks the push.
  echo "ESCG BLOCK: $1" >&2
  echo "  → Fix the evidence or restate the claim honestly, then push again (override: ESCG_BYPASS=1 git push …)." >&2
  exit 1
}

# ── QA-completion claim → device-qa-report.json must agree ──────────────────────
if [ "$has_qa_claim" -eq 1 ]; then
  REPORT="$MAIN_ROOT/device-qa-report.json"
  if [ ! -f "$REPORT" ]; then
    fail "STATE.md asserts a QA-complete success-claim, but $REPORT is MISSING (no device-QA evidence). Run: bash .claude/scripts/device-qa.sh --platform=all"
  fi

  # Freshness: prefer the report's own startedAt; fall back to file mtime. Either
  # must be newer than the session floor. An UNPARSEABLE report fails closed.
  started_iso="$(python3 - "$REPORT" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(7)
print(d.get("startedAt") or "")
PY
)"
  parse_rc=$?
  if [ "$parse_rc" -ne 0 ]; then
    fail "STATE.md asserts a QA-complete claim, but $REPORT is UNPARSEABLE (not valid JSON) — failing closed. Re-run device-qa.sh to regenerate it."
  fi

  started_epoch="$(iso_to_epoch "$started_iso")"
  [ -z "$started_epoch" ] && started_epoch="$(file_mtime_epoch "$REPORT")"
  if [ -z "$started_epoch" ]; then
    fail "STATE.md asserts a QA-complete claim, but $REPORT has no readable timestamp (startedAt absent and mtime unreadable) — failing closed."
  fi
  if [ "$started_epoch" -lt "$SESSION_FLOOR_EPOCH" ]; then
    age_h=$(( (NOW_EPOCH - started_epoch) / 3600 ))
    fail "STATE.md asserts a QA-complete claim, but $REPORT is STALE (device-QA ran ~${age_h}h ago, older than this session). Re-run: bash .claude/scripts/device-qa.sh --platform=all"
  fi

  # Agreement: any KEY-GATED sub-leg (sketchfab / arcore-cloud) with status
  # 'skipped' means that path was NOT exercised — a keyless/degraded build is
  # never a complete QA (#2343). Also surface a still-skipped/failed blocking leg.
  skipped_keyleg="$(python3 - "$REPORT" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(7)
KEY = {"sketchfab", "arcore-cloud"}
hits = []
for p in d.get("platforms", []) or []:
    name = p.get("platform")
    if name in KEY and p.get("status") == "skipped":
        reason = (p.get("reason") or "").strip()
        hits.append(f"{name} (skipped: {reason or 'key missing — path NOT tested'})")
print(" ; ".join(hits))
PY
)"
  parse_rc=$?
  if [ "$parse_rc" -ne 0 ]; then
    fail "STATE.md asserts a QA-complete claim, but $REPORT became UNPARSEABLE while checking key-gated legs — failing closed."
  fi
  if [ -n "$skipped_keyleg" ]; then
    fail "STATE.md claims QA complete, but $REPORT shows key-gated sub-leg(s) SKIPPED → that path was NOT tested: $skipped_keyleg. Re-run KEYED (SKETCHFAB_API_KEY / ARCORE_API_KEY → repo-root local.properties): bash .claude/scripts/device-qa.sh --platform=all"
  fi

  # A claimed-complete QA whose overall verdict is 'blocked' (a blocking leg
  # failed) is not complete either. 'warn'/'clear' are acceptable (advisory legs
  # are allowed to be non-green per the release-gate policy).
  gate_verdict="$(python3 - "$REPORT" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(7)
print((d.get("releaseGate") or {}).get("verdict") or "")
PY
)"
  if [ "$gate_verdict" = "blocked" ]; then
    fail "STATE.md claims QA complete, but $REPORT releaseGate.verdict=blocked (a BLOCKING leg, e.g. web, failed). Fix the failing leg before claiming complete."
  fi
fi

# ── Store / live claim → last-store-probe.json must agree ───────────────────────
if [ "$has_store_claim" -eq 1 ]; then
  PROBE="$MAIN_ROOT/.claude/data/last-store-probe.json"
  if [ ! -f "$PROBE" ]; then
    fail "STATE.md asserts an 'all-live / deployed ✅' success-claim, but $PROBE is MISSING (no live-store evidence). Run /store-status to probe iTunes + Maven + npm, or restate the claim honestly."
  fi

  probe_fields="$(python3 - "$PROBE" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(7)
verdict  = d.get("verdict")  or ""
expected = d.get("expected") or ""
ioslive  = d.get("iosLive")
ts       = d.get("ts") or ""
print("\t".join([str(verdict), str(expected), "" if ioslive is None else str(ioslive), str(ts)]))
PY
)"
  parse_rc=$?
  if [ "$parse_rc" -ne 0 ]; then
    fail "STATE.md asserts an 'all-live' claim, but $PROBE is UNPARSEABLE (not valid JSON) — failing closed. Re-run /store-status to regenerate it."
  fi

  P_VERDICT="$(printf '%s' "$probe_fields" | awk -F'\t' '{print $1}')"
  P_EXPECTED="$(printf '%s' "$probe_fields" | awk -F'\t' '{print $2}')"
  P_IOSLIVE="$(printf '%s' "$probe_fields" | awk -F'\t' '{print $3}')"
  P_TS="$(printf '%s' "$probe_fields" | awk -F'\t' '{print $4}')"

  # Freshness: probe ts (preferred) or file mtime must be newer than the session floor.
  probe_epoch="$(iso_to_epoch "$P_TS")"
  [ -z "$probe_epoch" ] && probe_epoch="$(file_mtime_epoch "$PROBE")"
  if [ -z "$probe_epoch" ]; then
    fail "STATE.md asserts an 'all-live' claim, but $PROBE has no readable timestamp (ts absent and mtime unreadable) — failing closed."
  fi
  if [ "$probe_epoch" -lt "$SESSION_FLOOR_EPOCH" ]; then
    age_h=$(( (NOW_EPOCH - probe_epoch) / 3600 ))
    fail "STATE.md asserts an 'all-live' claim, but $PROBE is STALE (store probed ~${age_h}h ago, older than this session). Re-run /store-status."
  fi

  # Agreement: the probe verdict must be ALL_LIVE. A MISMATCH verdict means a
  # surface (typically iOS) is NOT at the expected version — the exact CI-green
  # trap (iOS stuck on 4.0.3) this gate exists to catch.
  if [ "$P_VERDICT" != "ALL_LIVE" ]; then
    detail="verdict=${P_VERDICT:-<empty>}"
    if [ -n "$P_IOSLIVE" ] && [ -n "$P_EXPECTED" ] && [ "$P_IOSLIVE" != "$P_EXPECTED" ]; then
      detail="$detail; iOS live=$P_IOSLIVE != expected=$P_EXPECTED"
    fi
    fail "STATE.md claims all-live / deployed ✅, but $PROBE disagrees ($detail). CI upload-green is not live — run /store-status and only claim live when verdict=ALL_LIVE, or restate the claim honestly (e.g. 'iOS LIVE=$P_IOSLIVE, $P_EXPECTED in review')."
  fi
fi

# All asserted success-claims are backed by fresh, agreeing evidence. Silent pass.
exit 0
