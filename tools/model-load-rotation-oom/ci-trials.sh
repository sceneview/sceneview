#!/bin/bash
# usage: ci-trials.sh <apk-dir> "<versions>" <api> <heapMiB> <trials> <out-dir>
# Runs run-trial.sh <trials> times per version on the booted emulator and writes a markdown table
# to <out-dir>/results.md (and to $GITHUB_STEP_SUMMARY when set). An out-of-memory error is a result,
# not a failure: the script fails only if the harness itself breaks (no APK, adb gone).
set -u
DIR=$1; VERSIONS=$2; API=$3; HEAP=$4; N=$5; OUT=$6
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r' | sed 's/^/heapgrowthlimit=/' | tee "$OUT/props.txt"
adb shell getprop dalvik.vm.heapsize | tr -d '\r' | sed 's/^/heapsize=/' | tee -a "$OUT/props.txt"
{
  echo "### API $API, heap ${HEAP} MiB ($(tr '\n' ' ' < "$OUT/props.txt"))"
  echo
  echo "| API | heap | version | trial | OOM | first app frame | drain (ms) | model visible | peak heap (KiB) | rotations |"
  echo "|---|---|---|---|---|---|---|---|---|---|"
} > "$OUT/results.md"
for v in $VERSIONS; do
  apk="$DIR/mavlab-repro-$v.apk"
  if [ ! -f "$apk" ]; then
    echo "::warning::no APK for $v (build skipped or failed)"
    echo "| $API | $HEAP | $v | – | no APK | | | | | |" >> "$OUT/results.md"
    continue
  fi
  for t in $(seq 1 "$N"); do
    tsv="$OUT/$v-$t.tsv"; : > "$tsv"
    adb get-state >/dev/null 2>&1 || { echo "::error::emulator gone"; exit 1; }
    REPRO_TSV="$tsv" bash "$HERE/run-trial.sh" "$apk" 0 "$OUT/logcat-$v-$t.txt" 60
    IFS=$'\t' read -r _ oom frame vis drain peak rot < "$tsv" || true
    echo "| $API | $HEAP | $v | $t | ${oom:-?} | ${frame:-} | ${drain:-} | ${vis:-none} | ${peak:-} | ${rot:-} |" >> "$OUT/results.md"
  done
done
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$OUT/results.md" >> "$GITHUB_STEP_SUMMARY"
cat "$OUT/results.md"
