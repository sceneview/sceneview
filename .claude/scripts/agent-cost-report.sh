#!/usr/bin/env bash
# Measure what the agents actually cost — from the local session transcripts.
#
# WHY
#   CLAUDE.md's step-3 bottleneck is "ensuring tokens are used efficiently as
#   usage increases", and the repo had NO instrumentation: no OTel export, no
#   analytics, no counter. Quota pressure was managed by feel. This reads the
#   ground truth Claude Code already writes to disk
#   (~/.claude/projects/<slug>/*.jsonl) and reports where the tokens went.
#
# WHY TOKENS AND NOT DOLLARS
#   Deliberate. This account is on a Claude Max subscription — a flat plan, not
#   per-token billing — so a dollar figure here would be an invented number
#   dressed up as a measurement. The quantity that actually binds is the quota,
#   and this reports a WEIGHTED cost using the published price ratios (cache
#   read x0.1, cache write x1.25-2, output x5) rather than raw token counts,
#   which are not comparable to each other.
#
#   ⚠️ THIS FILE USED TO SAY "OUTPUT TOKENS DOMINATE IT". THAT WAS NEVER MEASURED
#   AND IS FALSE. Measured 2026-08-03 over 7 days, all projects: cache_read was
#   61.5% of the weighted cost, cache_write 25.9%, output 12.6%. Optimising for
#   shorter answers targets an eighth of the bill. The real driver is context
#   size x number of turns: every turn re-reads the entire context, and the
#   average context measured 209k tokens per request.
#
# ⚠️ DEDUPLICATION IS NOT OPTIONAL
#   A transcript writes several records per API call (streaming + final), each
#   carrying the SAME `usage` block. Measured on one real session: 980 usage
#   records for 658 distinct `requestId`s — summing records instead of requests
#   overstates output tokens by ~95%. Everything below is keyed on `requestId`.
#
# USAGE
#   agent-cost-report.sh [--days N] [--by day|model|session|branch]
#                        [--project <slug>|--all] [--json] [--top N]
#
#   --days N      look back N days (default 7; 0 = everything on disk)
#   --by          grouping for the main table (default: day)
#   --project     project dir slug under ~/.claude/projects (default: this repo)
#   --all         every project, not just this one
#   --json        machine-readable output instead of the table
#   --top N       rows in the heaviest-sessions table (default 5)

set -uo pipefail

DAYS=7
GROUP_BY=day
PROJECT=""
ALL_PROJECTS=false
AS_JSON=false
TOP=5

while [ $# -gt 0 ]; do
  case "$1" in
    --days)    DAYS="${2:-7}"; shift 2 ;;
    --by)      GROUP_BY="${2:-day}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --all)     ALL_PROJECTS=true; shift ;;
    --json)    AS_JSON=true; shift ;;
    --top)     TOP="${2:-5}"; shift 2 ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    *)         echo "agent-cost-report.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

case "$GROUP_BY" in
  day|model|session|branch) ;;
  *) echo "agent-cost-report.sh: --by must be day|model|session|branch" >&2; exit 2 ;;
esac

ROOT="$HOME/.claude/projects"
if [ ! -d "$ROOT" ]; then
  echo "agent-cost-report.sh: no transcripts at $ROOT — nothing to measure." >&2
  exit 0
fi

# Default to the project this checkout belongs to. Claude Code slugifies the
# path by replacing every non-alphanumeric run with '-', so derive it the same
# way rather than hardcoding it.
if [ -z "$PROJECT" ] && [ "$ALL_PROJECTS" = false ]; then
  TOPLEVEL="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
  # A worktree under .claude/worktrees/* belongs to its parent project's
  # transcripts; strip the worktree suffix so a session in a worktree is not
  # reported as a separate, empty project.
  TOPLEVEL="${TOPLEVEL%%/.claude/worktrees/*}"
  PROJECT="$(printf '%s' "$TOPLEVEL" | sed 's/[^a-zA-Z0-9]/-/g')"
fi

ROOT="$ROOT" PROJECT="$PROJECT" ALL_PROJECTS="$ALL_PROJECTS" DAYS="$DAYS" \
GROUP_BY="$GROUP_BY" AS_JSON="$AS_JSON" TOP="$TOP" python3 <<'PY'
import json, os, sys, glob
from datetime import datetime, timedelta, timezone

root = os.environ["ROOT"]
project = os.environ["PROJECT"]
all_projects = os.environ["ALL_PROJECTS"] == "true"
days = int(os.environ["DAYS"] or 7)
group_by = os.environ["GROUP_BY"]
as_json = os.environ["AS_JSON"] == "true"
top_n = int(os.environ["TOP"] or 5)

# Subagents do NOT write into <slug>/*.jsonl — they get their own transcript at
# <slug>/<sessionId>/subagents/agent-*.jsonl. Globbing only the first pattern
# drops them entirely: measured 2026-08-03, 643 subagent transcripts existed and
# this report saw none of them, hiding 13.3% of the weighted cost.
if all_projects or not project:
    files = sorted(glob.glob(os.path.join(root, "*", "*.jsonl"))
                   + glob.glob(os.path.join(root, "*", "*", "subagents", "*.jsonl")))
    scope = "all projects"
else:
    files = sorted(glob.glob(os.path.join(root, project, "*.jsonl"))
                   + glob.glob(os.path.join(root, project, "*", "subagents", "*.jsonl")))
    scope = project

cutoff = None
if days > 0:
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)


def parse_ts(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


# requestId -> the one usage row we count for it. Seeing the same id again is
# the streaming duplicate, never a second call: skip it, never add it.
seen = {}
skipped_dupes = 0
undated = 0

for path in files:
    session = os.path.splitext(os.path.basename(path))[0]
    # A subagent transcript lives at <slug>/<sessionId>/subagents/agent-*.jsonl.
    # Attribute its cost to the OWNING session — an agent-<hash> row on its own
    # is unattributable, and the point of the report is to name what to change.
    parent = os.path.dirname(path)
    is_subagent = os.path.basename(parent) == "subagents"
    if is_subagent:
        session = os.path.basename(os.path.dirname(parent))
    try:
        fh = open(path, errors="replace")
    except OSError:
        continue
    with fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except (ValueError, TypeError):
                continue
            msg = rec.get("message")
            if not isinstance(msg, dict):
                continue
            usage = msg.get("usage")
            if not isinstance(usage, dict):
                continue

            ts = parse_ts(rec.get("timestamp"))
            if cutoff is not None:
                if ts is None:
                    undated += 1
                    continue
                if ts < cutoff:
                    continue

            # No requestId => fall back to the record uuid. That cannot dedupe,
            # so it is counted once and flagged rather than silently merged.
            key = rec.get("requestId") or ("uuid:" + str(rec.get("uuid")))
            if key in seen:
                skipped_dupes += 1
                continue

            # Weighted cost. Raw volumes are not comparable: an output token
            # costs 50x a cache-read token. Summing them answers no question.
            # Weights are Anthropic's published price ratios, base = input token.
            created = usage.get("cache_creation")
            cw_5m = cw_1h = 0
            if isinstance(created, dict):
                cw_5m = created.get("ephemeral_5m_input_tokens") or 0
                cw_1h = created.get("ephemeral_1h_input_tokens") or 0
            cache_write = usage.get("cache_creation_input_tokens") or 0
            if not (cw_5m or cw_1h):
                cw_5m = cache_write
            out_t = usage.get("output_tokens") or 0
            in_t = usage.get("input_tokens") or 0
            cr_t = usage.get("cache_read_input_tokens") or 0
            weighted = in_t + cw_5m * 1.25 + cw_1h * 2.0 + cr_t * 0.1 + out_t * 5.0

            seen[key] = {
                "day": ts.strftime("%Y-%m-%d") if ts else "(undated)",
                "model": msg.get("model") or "(unknown)",
                "session": session,
                "branch": rec.get("gitBranch") or "(none)",
                "sidechain": bool(rec.get("isSidechain")) or is_subagent,
                "output": out_t,
                "input": in_t,
                "cache_write": cache_write or (cw_5m + cw_1h),
                "cache_read": cr_t,
                "weighted": weighted,
                "context": in_t + cr_t + (cache_write or (cw_5m + cw_1h)),
            }

rows = list(seen.values())

if not rows:
    where = "%s, last %s day(s)" % (scope, days if days else "all")
    if as_json:
        print(json.dumps({"scope": scope, "days": days, "requests": 0, "rows": []}, indent=2))
    else:
        print("No agent activity found (%s)." % where)
        if not files:
            print("No transcripts under %s — is the project slug right? Try --all." % root)
    sys.exit(0)


def blank():
    return {"requests": 0, "output": 0, "input": 0, "cache_write": 0,
            "cache_read": 0, "subagent_requests": 0, "weighted": 0.0,
            "context": 0}


def accumulate(b, r):
    b["requests"] += 1
    for f in ("output", "input", "cache_write", "cache_read", "weighted", "context"):
        b[f] += r[f]
    if r["sidechain"]:
        b["subagent_requests"] += 1


def aggregate(key):
    out = {}
    for r in rows:
        accumulate(out.setdefault(r[key], blank()), r)
    return out


totals = blank()
for r in rows:
    accumulate(totals, r)

grouped = aggregate(group_by)
by_session = aggregate("session")
heaviest = sorted(by_session.items(), key=lambda kv: kv[1]["weighted"], reverse=True)[:top_n]

# Where the weighted cost actually sits. Reported as a breakdown rather than a
# single headline number, because the headline was wrong for months: this script
# named output "the quota-binding number" while output was 12.6% of the bill.
w_total = totals["weighted"] or 1.0
breakdown = [
    ("cache_read  x0.1", totals["cache_read"], totals["cache_read"] * 0.1),
    ("cache_write x1.25-2", totals["cache_write"], w_total - (
        totals["cache_read"] * 0.1 + totals["output"] * 5.0 + totals["input"])),
    ("output      x5", totals["output"], totals["output"] * 5.0),
    ("input       x1", totals["input"], float(totals["input"])),
]
avg_ctx = totals["context"] / totals["requests"] if totals["requests"] else 0

read_in = totals["cache_read"] + totals["cache_write"] + totals["input"]
cache_share = (100.0 * totals["cache_read"] / read_in) if read_in else 0.0

if as_json:
    print(json.dumps({
        "scope": scope,
        "days": days,
        "totals": totals,
        "weightedCostBreakdown": [
            {"component": c, "raw": raw, "weighted": round(w, 1),
             "pct": round(100.0 * w / w_total, 1)} for c, raw, w in breakdown],
        "avgContextPerRequest": round(avg_ctx),
        "cache_read_share_pct": round(cache_share, 1),
        "duplicate_records_skipped": skipped_dupes,
        "undated_records_skipped": undated,
        "groupBy": group_by,
        "groups": grouped,
        "heaviestSessions": [{"session": s, **v} for s, v in heaviest],
    }, indent=2, sort_keys=True))
    sys.exit(0)


def n(v):
    if v >= 1_000_000:
        return "%.1fM" % (v / 1_000_000)
    if v >= 1_000:
        return "%.1fk" % (v / 1_000)
    return str(v)


print("Agent cost — %s, last %s day(s)" % (scope, days if days else "all"))
print("=" * 72)
print("%-22s %8s %9s %9s %10s %10s"
      % (group_by, "requests", "weighted", "output", "cache-wr", "cache-rd"))
print("-" * 72)
for key in sorted(grouped, reverse=(group_by == "day")):
    g = grouped[key]
    label = key if len(key) <= 22 else key[:19] + "..."
    print("%-22s %8d %9s %9s %10s %10s"
          % (label, g["requests"], n(g["weighted"]), n(g["output"]),
             n(g["cache_write"]), n(g["cache_read"])))
print("-" * 72)
print("%-22s %8d %9s %9s %10s %10s"
      % ("TOTAL", totals["requests"], n(totals["weighted"]), n(totals["output"]),
         n(totals["cache_write"]), n(totals["cache_read"])))
print()
print("Weighted cost: %s units across %d API requests." % (n(totals["weighted"]),
                                                           totals["requests"]))
print("Where it goes (weights = published price ratios, base = 1 input token):")
for label, raw, w in sorted(breakdown, key=lambda x: -x[2]):
    print("  %-20s %8s raw -> %8s weighted  %5.1f%%"
          % (label, n(raw), n(w), 100.0 * w / w_total))
print()
print("Average context re-read per request: %s tokens. THIS is the lever — every"
      % n(int(avg_ctx)))
print("turn pays for the whole context again, so cost scales with context size")
print("times number of turns, not with how much text the agent writes.")
print("Cache reads are %.1f%% of all input volume — the cheap half; a low share "
      "means sessions are rebuilding context instead of reusing it."
      % cache_share)
if totals["subagent_requests"]:
    print("Subagent requests: %d of %d (%.0f%%) — includes the "
          "<session>/subagents/ transcripts."
          % (totals["subagent_requests"], totals["requests"],
             100.0 * totals["subagent_requests"] / totals["requests"]))
print()
print("Heaviest sessions by weighted cost")
print("-" * 72)
for s, v in heaviest:
    print("  %s  %8s weighted  %6d requests  ctx avg %s"
          % (s, n(v["weighted"]), v["requests"],
             n(int(v["context"] / v["requests"])) if v["requests"] else "-"))
print()
# Report what was dropped. A silently-filtered record is how a measurement
# turns into a comfortable number.
print("Deduplication: %d duplicate usage record(s) skipped (same requestId — "
      "streaming re-reports, not extra calls)." % skipped_dupes)
if undated:
    print("⚠️  %d record(s) had no parseable timestamp and were EXCLUDED by the "
          "--days window. Run with --days 0 to include them." % undated)
PY
