#!/usr/bin/env bash
# prod-status.sh — what is actually LIVE, surface by surface, versus VERSION_NAME.
#
# Read-only. Every line is measured on the public surface a user sees (the
# Play page, the App Store page, repo1.maven.org, the npm registry, pub.dev,
# the GitHub release list) — never on a workflow conclusion or a Play API
# track status. Both lied for three months in 2026: play-store.yml reported
# "success" on every tag from v4.20.0 to v4.33.0 while the public Play page
# stayed on v4.18.0 (2026-06-06), because the release commit succeeded but
# publication was held in the console (#3557).
#
# Usage:  bash .claude/scripts/prod-status.sh        # table, exit 1 if any lag
#         bash .claude/scripts/prod-status.sh --json # machine-readable
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
EXPECTED=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
JSON=${1:-}
lag=0
rows=()

row() { # surface live extra
  local surface=$1 live=$2 extra=${3:-}
  local status="ok"
  if [ "$live" != "$EXPECTED" ]; then status="LAG"; lag=1; fi
  rows+=("$surface|$live|$status|$extra")
}

maven() { curl -sf "https://repo1.maven.org/maven2/io/github/sceneview/$1/maven-metadata.xml" \
  | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'; }
for a in sceneview arsceneview sceneview-compose sceneview-core; do
  row "maven:$a" "$(maven "$a" || echo none)"
done

npmv() { curl -sf "https://registry.npmjs.org/$1/latest" | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])' 2>/dev/null || echo none; }
row "npm:sceneview-web" "$(npmv sceneview-web)"
row "npm:@sceneview-sdk/react-native" "$(npmv @sceneview-sdk/react-native)"

row "pub.dev:flutter_sceneview" "$(curl -sf https://pub.dev/api/packages/flutter_sceneview | python3 -c 'import sys,json;print(json.load(sys.stdin)["latest"]["version"])' 2>/dev/null || echo none)"

row "github:release" "$(gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null | sed 's/^v//')"

# Play: the public page. The version string Play serves is the release name of
# the PUBLISHED production release — exactly the thing a visitor sees.
PLAY=$(curl -sL "https://play.google.com/store/apps/details?id=io.github.sceneview.demo&hl=en&gl=US")
play_ver=$(printf '%s' "$PLAY" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?"' | head -1 | tr -d '"' | sed 's/-.*//')
play_date=$(printf '%s' "$PLAY" | grep -oE 'Updated on.{0,80}' | sed 's/<[^>]*>/ /g; s/Updated on *//' | awk '{print $1,$2,$3}')
row "play:io.github.sceneview.demo" "${play_ver:-none}" "updated ${play_date:-?}"

# App Store: the public page (iOS + macOS share the listing).
ASV=$(curl -sL "https://apps.apple.com/us/app/sceneview/id6761329763" | grep -oE 'Version [0-9]+\.[0-9]+\.[0-9]+' | head -1 | awk '{print $2}')
row "appstore:id6761329763" "${ASV:-none}"

if [ "$JSON" = "--json" ]; then
  printf '{"expected":"%s","surfaces":[' "$EXPECTED"
  first=1
  for r in "${rows[@]}"; do IFS='|' read -r s l st e <<<"$r"
    [ $first = 1 ] || printf ','; first=0
    printf '{"surface":"%s","live":"%s","status":"%s","note":"%s"}' "$s" "$l" "$st" "$e"
  done
  printf ']}\n'
else
  printf 'expected (gradle.properties VERSION_NAME): %s\n\n' "$EXPECTED"
  printf '%-34s %-10s %-4s %s\n' SURFACE LIVE STATE NOTE
  for r in "${rows[@]}"; do IFS='|' read -r s l st e <<<"$r"; printf '%-34s %-10s %-4s %s\n' "$s" "$l" "$st" "$e"; done
fi
exit $lag
