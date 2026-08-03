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
#   and OUTPUT tokens dominate it. Cache reads are reported because they are the
#   bulk of the input volume and the cheapest lever (a session that re-reads a
#   warm cache costs far less than one that rebuilds it).
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

if all_projects or not project:
    files = sorted(glob.glob(os.path.join(root, "*", "*.jsonl")))
    scope = "all projects"
else:
    files = sorted(glob.glob(os.path.join(root, project, "*.jsonl")))
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

            seen[key] = {
                "day": ts.strftime("%Y-%m-%d") if ts else "(undated)",
                "model": msg.get("model") or "(unknown)",
                "session": session,
                "branch": rec.get("gitBranch") or "(none)",
                "sidechain": bool(rec.get("isSidechain")),
                "output": usage.get("output_tokens") or 0,
                "input": usage.get("input_tokens") or 0,
                "cache_write": usage.get("cache_creation_input_tokens") or 0,
                "cache_read": usage.get("cache_read_input_tokens") or 0,
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
            "cache_read": 0, "subagent_requests": 0}


def aggregate(key):
    out = {}
    for r in rows:
        b = out.setdefault(r[key], blank())
        b["requests"] += 1
        b["output"] += r["output"]
        b["input"] += r["input"]
        b["cache_write"] += r["cache_write"]
        b["cache_read"] += r["cache_read"]
        if r["sidechain"]:
            b["subagent_requests"] += 1
    return out


totals = blank()
for r in rows:
    totals["requests"] += 1
    totals["output"] += r["output"]
    totals["input"] += r["input"]
    totals["cache_write"] += r["cache_write"]
    totals["cache_read"] += r["cache_read"]
    if r["sidechain"]:
        totals["subagent_requests"] += 1

grouped = aggregate(group_by)
by_session = aggregate("session")
heaviest = sorted(by_session.items(), key=lambda kv: kv[1]["output"], reverse=True)[:top_n]

read_in = totals["cache_read"] + totals["cache_write"] + totals["input"]
cache_share = (100.0 * totals["cache_read"] / read_in) if read_in else 0.0

if as_json:
    print(json.dumps({
        "scope": scope,
        "days": days,
        "totals": totals,
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
print("%-22s %8s %10s %10s %11s" % (group_by, "requests", "output", "cache-wr", "cache-rd"))
print("-" * 72)
for key in sorted(grouped, reverse=(group_by == "day")):
    g = grouped[key]
    label = key if len(key) <= 22 else key[:19] + "..."
    print("%-22s %8d %10s %10s %11s"
          % (label, g["requests"], n(g["output"]), n(g["cache_write"]), n(g["cache_read"])))
print("-" * 72)
print("%-22s %8d %10s %10s %11s"
      % ("TOTAL", totals["requests"], n(totals["output"]),
         n(totals["cache_write"]), n(totals["cache_read"])))
print()
print("Output tokens (the quota-binding number): %s across %d API requests."
      % (n(totals["output"]), totals["requests"]))
print("Cache reads are %.1f%% of all input volume — the cheap half; a low share "
      "means sessions are rebuilding context instead of reusing it."
      % cache_share)
if totals["subagent_requests"]:
    print("Subagent (sidechain) requests: %d of %d (%.0f%%)."
          % (totals["subagent_requests"], totals["requests"],
             100.0 * totals["subagent_requests"] / totals["requests"]))
print()
print("Heaviest sessions by output tokens")
print("-" * 72)
for s, v in heaviest:
    print("  %s  %8s output  %6d requests" % (s, n(v["output"]), v["requests"]))
print()
# Report what was dropped. A silently-filtered record is how a measurement
# turns into a comfortable number.
print("Deduplication: %d duplicate usage record(s) skipped (same requestId — "
      "streaming re-reports, not extra calls)." % skipped_dupes)
if undated:
    print("⚠️  %d record(s) had no parseable timestamp and were EXCLUDED by the "
          "--days window. Run with --days 0 to include them." % undated)
PY
