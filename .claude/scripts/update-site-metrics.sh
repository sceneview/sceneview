#!/usr/bin/env bash
# Updates the community-metrics values embedded in website-static/index.html
# (the `data-metric="…"` anchors under the "Community & Metrics" section).
#
# Used by .github/workflows/site-metrics.yml (weekly cron) — this replaces
# the retired Claude scheduled routine that used to refresh these numbers by
# hand, at zero AI quota cost.
#
# In scope: stars, contributors, releases, web-downloads (sceneview-web npm
# package). `forks` and `mcp-downloads` (sceneview-mcp npm package) are
# deliberately NOT touched here — out of scope for this automation.
#
# Formatting rule mirrors the existing hand-authored values: a value >= 1000
# renders as "X.YK" (one decimal), anything below renders as a plain integer
# (e.g. current file has stars=1.2K, web-downloads=4.7K, contributors=43,
# releases=123).
#
# Usage:
#   update-site-metrics.sh --stars N --contributors N --releases N --web-downloads N [--file PATH]
#
# Exits 0 whether or not the file actually changed — the caller (the
# workflow) checks `git diff` to decide whether a commit is needed. Exits
# non-zero only on bad input or a missing/changed anchor.

set -euo pipefail

FILE="website-static/index.html"
STARS=""
CONTRIBUTORS=""
RELEASES=""
WEB_DOWNLOADS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --stars) STARS="$2"; shift 2 ;;
    --contributors) CONTRIBUTORS="$2"; shift 2 ;;
    --releases) RELEASES="$2"; shift 2 ;;
    --web-downloads) WEB_DOWNLOADS="$2"; shift 2 ;;
    --file) FILE="$2"; shift 2 ;;
    *) echo "::error::unknown argument: $1" >&2; exit 1 ;;
  esac
done

for name_val in "stars:$STARS" "contributors:$CONTRIBUTORS" "releases:$RELEASES" "web-downloads:$WEB_DOWNLOADS"; do
  name="${name_val%%:*}"
  val="${name_val#*:}"
  if [ -z "$val" ]; then
    echo "::error::missing required value for --$name" >&2
    exit 1
  fi
  case "$val" in
    ''|*[!0-9]*) echo "::error::--$name must be a plain non-negative integer, got '$val'" >&2; exit 1 ;;
  esac
done

if [ ! -f "$FILE" ]; then
  echo "::error::$FILE not found" >&2
  exit 1
fi

format_metric() {
  awk -v n="$1" 'BEGIN {
    if (n >= 1000) { printf "%.1fK", n / 1000 }
    else { printf "%d", n }
  }'
}

STARS_FMT="$(format_metric "$STARS")"
CONTRIBUTORS_FMT="$(format_metric "$CONTRIBUTORS")"
RELEASES_FMT="$(format_metric "$RELEASES")"
WEB_DOWNLOADS_FMT="$(format_metric "$WEB_DOWNLOADS")"

set_metric() {
  key="$1"
  value="$2"
  # Each data-metric="<key>" anchor appears exactly once in the file; anchor
  # the replacement on that exact attribute so unrelated markup can never
  # match, and fail loudly if the anchor has moved/been renamed.
  if ! grep -q "data-metric=\"$key\">" "$FILE"; then
    echo "::error::anchor data-metric=\"$key\" not found in $FILE — website markup changed, update this script" >&2
    exit 1
  fi
  sed -i.bak -E "s|(data-metric=\"$key\">)[^<]*(<)|\\1${value}\\2|" "$FILE"
  rm -f "$FILE.bak"
}

set_metric "stars" "$STARS_FMT"
set_metric "contributors" "$CONTRIBUTORS_FMT"
set_metric "releases" "$RELEASES_FMT"
set_metric "web-downloads" "$WEB_DOWNLOADS_FMT"

echo "Updated $FILE: stars=$STARS_FMT contributors=$CONTRIBUTORS_FMT releases=$RELEASES_FMT web-downloads=$WEB_DOWNLOADS_FMT"
