#!/usr/bin/env bash
# measure-demo-cold-start.sh — cold-start timings of the Android demo app.
#
# For each run: force-stop the app, `am start -W` it, record LaunchState and
# TotalTime/WaitTime (launch request -> first frame drawn), then read the
# `SVStartup` logcat markers the home hero logs (`StartupMarker`, ms since process
# start): `first_model_frame` (first presented frame with the helmet in the scene)
# and `model_textured_frame` (first frame after its textures finished uploading).
# Prints one TSV row per run, then median / p90 / min / max per column.
#
# Usage: bash tools/measure-demo-cold-start.sh [runs=10] [serial=emulator-5554]
# The demo must already be installed. Use the emulator, never a personal device;
# a debuggable (debug) build overstates every number — measure a release build.
set -euo pipefail

RUNS="${1:-10}"
SERIAL="${2:-emulator-5554}"
PKG="io.github.sceneview.demo"
ACTIVITY="$PKG/.MainActivity"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
adb() { "$ADB" -s "$SERIAL" "$@"; }

marker() { # $1 = logcat dump, $2 = event name -> ms or "-"
  printf '%s\n' "$1" | sed -n "s/.*SVStartup: $2 \([0-9]*\)ms.*/\1/p" | head -1 | grep . || echo "-"
}

printf 'run\tstate\ttotal\twait\tfirst_model\tmodel_textured\n'
rows=""
for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop "$PKG"
  sleep 2
  adb logcat -c
  out="$(adb shell am start -W -n "$ACTIVITY")"
  total="$(printf '%s\n' "$out" | sed -n 's/^TotalTime: \([0-9]*\).*/\1/p')"
  wait_ms="$(printf '%s\n' "$out" | sed -n 's/^WaitTime: \([0-9]*\).*/\1/p')"
  state="$(printf '%s\n' "$out" | sed -n 's/^LaunchState: \([A-Z]*\).*/\1/p')"
  log=""
  for _ in $(seq 1 30); do # up to ~15 s for the textured frame
    log="$(adb logcat -d -s SVStartup:I)"
    printf '%s\n' "$log" | grep -q "model_textured_frame" && break
    sleep 0.5
  done
  row="$i	${state:--}	${total:--}	${wait_ms:--}	$(marker "$log" first_model_frame)	$(marker "$log" model_textured_frame)"
  printf '%s\n' "$row"
  rows+="$row"$'\n'
done
adb shell am force-stop "$PKG"

# Median and p90 (nearest rank) per column, ignoring missing values.
printf '%s' "$rows" | awk -F'\t' '
  function stats(col, name,   n, i, j, t, v, med, p90) {
    n = 0
    for (i = 1; i <= NR; i++) if (cell[i, col] != "-") v[++n] = cell[i, col] + 0
    if (n == 0) { printf "%-15s n=0\n", name; return }
    for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (v[j] < v[i]) { t = v[i]; v[i] = v[j]; v[j] = t }
    med = (n % 2) ? v[(n + 1) / 2] : (v[n / 2] + v[n / 2 + 1]) / 2
    p90 = v[int(0.9 * n + 0.999999)]
    printf "%-15s n=%d median=%dms p90=%dms min=%dms max=%dms\n", name, n, med, p90, v[1], v[n]
  }
  { for (c = 1; c <= NF; c++) cell[NR, c] = $c }
  END {
    print ""
    stats(3, "total"); stats(4, "wait"); stats(5, "first_model"); stats(6, "model_textured")
  }'
