#!/usr/bin/env bash
#
# play-reviews-ingest.sh — ingest Play Store ratings & reviews, surface
# user-reported bugs (#1692).
#
# WHY ───────────────────────────────────────────────────────────────────────
# The empire dashboard already aggregates npm downloads and GitHub stars, but
# the Play Store side of the demo app (rating, written reviews) is invisible
# to any automation. User reviews in particular are a free, high-signal bug
# feed that nobody is reading systematically.
#
# WHAT IT DOES ───────────────────────────────────────────────────────────────
#   1. Pulls recent written reviews for `io.github.sceneview.demo` via the
#      Android Publisher API `reviews.list` endpoint (the SAME service
#      account + auth pattern play-store.yml already uses to publish).
#   2. Computes the average star rating across the fetched reviews and a
#      star-distribution histogram → a lightweight metrics snapshot the
#      maintenance digest / empire dashboard can sit next to npm + GitHub.
#   3. For any review whose text matches a crash/bug signal (`crash`,
#      `freeze`, `black screen`, `won't open`, …) OR is a 1-star review with
#      a non-trivial comment, emits a triage record with the review text,
#      device, app version, and star rating.
#
# The CALLER (the maintenance workflow) is responsible for opening a
# de-duplicated GitHub issue per flagged review — this script only produces
# the data. De-dup is by Play `reviewId`, which is stable.
#
# API REALITY / DOCUMENTED GAPS (#1692) ──────────────────────────────────────
#   - `reviews.list` only returns reviews **modified in roughly the last
#     week** — this is a hard Google API limitation, not a bug here. The
#     daily cadence of the maintenance workflow covers that window with
#     overlap; de-dup by reviewId makes the overlap harmless.
#   - The Play **Developer Reporting API does NOT expose installs / acquisitions
#     / lifetime average rating** as a queryable metric set — those only come
#     from the bulk CSV "statistics" reports dropped into a Cloud Storage
#     bucket, which needs a separate GCS-bucket grant and a different auth
#     path. Rather than fake an install count, this script reports the
#     average rating it CAN compute from the fetched review window and
#     documents the install gap. Vitals (crash/ANR) IS programmatic and is
#     handled by `play-vitals.sh` (#1691).
#
# PERMISSION REQUIRED (manual, Play Console) ─────────────────────────────────
# The deploy service account needs the read-only **"Reply to reviews"** /
# **"View app information and download bulk reports"** Play Console
# permission. `reviews.list` is read-only — this script never replies to a
# review (replying is public content and implies a support commitment, see
# #1692). No new WRITE scope is added.
#
# OUTPUT ─────────────────────────────────────────────────────────────────────
# Writes a JSON document to the path given as $1 (default: play-reviews.json):
#   {
#     "schemaVersion": 1,
#     "fetchedAt": "...", "reviewCount": N,
#     "averageRating": 4.2, "starHistogram": {"1": .., ... "5": ..},
#     "flagged": [ {reviewId, starRating, device, appVersion, text, signal} ]
#   }
# Also prints a human-readable summary to stdout.
#
# Usage:
#   PLAY_STORE_SERVICE_ACCOUNT_JSON='<json>' \
#     bash .claude/scripts/play-reviews-ingest.sh [output.json]
#
# Env overrides:
#   PLAY_STORE_SERVICE_ACCOUNT_JSON  service-account JSON (required for data)
#   PACKAGE_NAME                     default io.github.sceneview.demo
#   REVIEWS_MAX                      max reviews to fetch, default 100
#
# Exit codes: always 0 — a monitor must never fail the workflow. A data-access
# problem produces an empty/degraded JSON + a WARN line.
#
# Closes #1692.

set -u

OUT="${1:-play-reviews.json}"
PACKAGE_NAME="${PACKAGE_NAME:-io.github.sceneview.demo}"
REVIEWS_MAX="${REVIEWS_MAX:-100}"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

write_degraded() {
  # Emit a valid-but-empty JSON so the caller never chokes on a missing file.
  python3 - "$OUT" "$1" << 'PYEOF' 2>/dev/null || echo '{"schemaVersion":1,"reviewCount":0,"flagged":[]}' > "$OUT"
import json, sys, datetime
out, reason = sys.argv[1], sys.argv[2]
json.dump({
    "schemaVersion": 1,
    "fetchedAt": datetime.datetime.utcnow().isoformat() + "Z",
    "reviewCount": 0,
    "averageRating": None,
    "starHistogram": {},
    "flagged": [],
    "degraded": reason,
}, open(out, "w"), indent=2)
PYEOF
  echo -e "  ${YELLOW}[WARN]${NC} Play reviews ingest degraded: $1"
}

echo -e "${CYAN}=== Play Store reviews & ratings ingest (#1692) ===${NC}"
echo -e "Package: ${GREEN}$PACKAGE_NAME${NC}"

if [ -z "${PLAY_STORE_SERVICE_ACCOUNT_JSON:-}" ]; then
  write_degraded "PLAY_STORE_SERVICE_ACCOUNT_JSON not set — cannot reach the Play API."
  exit 0
fi
if ! command -v python3 >/dev/null 2>&1; then
  write_degraded "python3 not available."
  exit 0
fi

PACKAGE_NAME="$PACKAGE_NAME" REVIEWS_MAX="$REVIEWS_MAX" OUT="$OUT" python3 << 'PYEOF'
import json, os, re, sys, datetime

try:
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
except Exception as e:  # pragma: no cover
    print(f"::warning::google-auth not installed ({e})")
    json.dump({"schemaVersion": 1, "reviewCount": 0, "flagged": [],
               "degraded": f"google-auth missing ({e})"},
              open(os.environ["OUT"], "w"), indent=2)
    sys.exit(0)

PKG = os.environ["PACKAGE_NAME"]
OUT = os.environ["OUT"]
MAX = int(os.environ.get("REVIEWS_MAX", "100"))

# Crash / bug signal patterns. A review hit by any of these — OR a 1-star
# review with a non-trivial comment — becomes a triage candidate.
BUG_SIGNALS = [
    ("crash",         r"\bcrash(?:e[sd]|ing)?\b"),
    ("freeze",        r"\bfreez(?:e[sd]?|ing)\b|\bfrozen\b|\bhang(?:s|ing|ed)?\b"),
    ("black screen",  r"\bblack screen\b|\bblank screen\b|\bwhite screen\b"),
    ("won't open",    r"\b(?:wo?n'?t|does ?n'?t|can'?t) (?:open|launch|start|load)\b"),
    ("force close",   r"\bforce[ -]?close[sd]?\b|\bkeeps? (?:closing|stopping)\b"),
    ("not working",   r"\bnot working\b|\bdoesn'?t work\b|\bbroken\b"),
    ("error",         r"\berror\b"),
]
COMPILED = [(name, re.compile(pat, re.I)) for name, pat in BUG_SIGNALS]

try:
    info = json.loads(os.environ["PLAY_STORE_SERVICE_ACCOUNT_JSON"])
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    sess = AuthorizedSession(creds)
except Exception as e:
    print(f"::warning::could not build Play API credentials ({e})")
    json.dump({"schemaVersion": 1, "reviewCount": 0, "flagged": [],
               "degraded": f"auth failed ({e})"}, open(OUT, "w"), indent=2)
    sys.exit(0)

BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"

reviews = []
token = None
try:
    while len(reviews) < MAX:
        params = {"maxResults": min(100, MAX - len(reviews))}
        if token:
            params["token"] = token
        r = sess.get(f"{BASE}/reviews", params=params)
        if r.status_code == 403:
            print("::warning::Play reviews API 403 Forbidden — the service "
                  "account lacks the 'Reply to reviews' / review-read "
                  "permission. No new write scope needed; grant the read-only "
                  "permission in the Play Console (#1692).")
            json.dump({"schemaVersion": 1, "reviewCount": 0, "flagged": [],
                       "degraded": "403 — missing review-read permission"},
                      open(OUT, "w"), indent=2)
            sys.exit(0)
        r.raise_for_status()
        data = r.json()
        batch = data.get("reviews", [])
        reviews.extend(batch)
        token = data.get("tokenPagination", {}).get("nextPageToken")
        if not token or not batch:
            break
except Exception as e:
    print(f"::warning::Play reviews fetch failed ({e})")
    json.dump({"schemaVersion": 1, "reviewCount": 0, "flagged": [],
               "degraded": f"fetch failed ({e})"}, open(OUT, "w"), indent=2)
    sys.exit(0)

histogram = {str(i): 0 for i in range(1, 6)}
stars_total = 0
stars_count = 0
flagged = []

for rev in reviews:
    review_id = rev.get("reviewId", "")
    author = rev.get("authorName", "") or "(anonymous)"
    # The newest user comment carries the review text + metadata.
    user_comment = None
    for c in rev.get("comments", []):
        if "userComment" in c:
            user_comment = c["userComment"]
            break
    if not user_comment:
        continue

    star = user_comment.get("starRating")
    text = (user_comment.get("text") or "").strip()
    if isinstance(star, int) and 1 <= star <= 5:
        histogram[str(star)] += 1
        stars_total += star
        stars_count += 1

    device_meta = user_comment.get("deviceMetadata", {}) or {}
    device = (device_meta.get("productName")
              or device_meta.get("manufacturer")
              or user_comment.get("device") or "unknown")
    app_version = user_comment.get("appVersionName") or (
        str(user_comment.get("appVersionCode"))
        if user_comment.get("appVersionCode") else "unknown")
    android = user_comment.get("androidOsVersion")

    # Bug-signal match, OR a 1-star review with a real comment (>= 12 chars).
    signal = None
    for name, rx in COMPILED:
        if text and rx.search(text):
            signal = name
            break
    if not signal and star == 1 and len(text) >= 12:
        signal = "1-star"

    if signal:
        flagged.append({
            "reviewId": review_id,
            "author": author,
            "starRating": star,
            "device": device,
            "androidOsVersion": android,
            "appVersion": app_version,
            "language": user_comment.get("reviewerLanguage", ""),
            "text": text[:1500],
            "signal": signal,
        })

avg = round(stars_total / stars_count, 2) if stars_count else None
result = {
    "schemaVersion": 1,
    "source": "android-publisher-api/reviews.list",
    "fetchedAt": datetime.datetime.utcnow().isoformat() + "Z",
    "reviewCount": len(reviews),
    "ratedReviewCount": stars_count,
    "averageRating": avg,
    "starHistogram": histogram,
    "flagged": flagged,
    # Honest documentation of the API gap (#1692).
    "notes": (
        "averageRating is computed over the reviews.list window only "
        "(~last 7 days, a Google API limitation), NOT the lifetime Play "
        "Console rating. Install base / acquisitions are not exposed by any "
        "queryable Play API metric set — they require the bulk CSV reports "
        "in a Cloud Storage bucket (separate grant), so they are not "
        "ingested here."
    ),
}
json.dump(result, open(OUT, "w"), indent=2)

print(f"Fetched {len(reviews)} review(s); {stars_count} carry a star rating.")
if avg is not None:
    print(f"Average rating (fetched window): {avg} / 5")
print(f"Star histogram: " + "  ".join(f"{k}*={v}" for k, v in histogram.items()))
print(f"Flagged for triage: {len(flagged)}")
PYEOF

echo -e "${GREEN}Reviews ingest written to ${OUT}.${NC}"
exit 0
