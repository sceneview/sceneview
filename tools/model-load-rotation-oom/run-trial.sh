#!/bin/bash
# usage: run-trial.sh <apk> <budgetMiB> <out.txt> [wait=40]
# One trial: launch, rotate to landscape once the first engine exists, wait, then read logcat.
# budgetMiB 0 = no ballast (the emulator's own heap limit does the work, e.g. dalvik.vm.heapgrowthlimit=16m).
# Prints one summary line. With REPRO_TSV set, also appends a row to that file, fields separated by
# the ASCII unit separator (0x1f) so that empty fields survive `read`:
#   apk  oom  oom_frame  visible  drain_ms  peak_kib  rotations  max_mib
# Env: ROTATE_DELAY seconds to wait after the first engine before rotating (default 0: right away;
# the MAVLab retest rotated 7-12 s in), EXTRA_MIB ballast on top of the app (default 0).
# Every adb call is bounded: a device that drops off must end the trial, not hang the job.
SERIAL=${ANDROID_SERIAL:-emulator-5554}; APK=$1; B=$2; OUT=$3; W=${4:-40}
a() { local t=$1; shift; timeout "$t" adb -s "$SERIAL" "$@"; }
step() { echo "[$(date -u +%H:%M:%S)] $*" >&2; }
: > "$OUT"
step "install $(basename "$APK")"
a 180 install -r -t "$APK" >/dev/null || step "install failed or timed out (rc=$?)"
a 30 shell settings put system accelerometer_rotation 0
a 30 shell settings put system user_rotation 0
a 30 shell am force-stop repro.mavlab; sleep 1
a 30 logcat -c || step "logcat -c failed (rc=$?)"
step "start"
a 30 shell run-as repro.mavlab rm -f files/oom.hprof
a 30 shell am start -n repro.mavlab/repro.MainActivity --ei budgetMiB "$B" --ei extraMiB "${EXTRA_MIB:-0}" >/dev/null
PID=""; for i in $(seq 1 50); do PID=$(a 10 shell pidof repro.mavlab | tr -d "\r"); [ -n "$PID" ] && break; sleep 0.1; done
step "pid=${PID:-none}, waiting for the first engine"
[ -n "$PID" ] && a 30 logcat --pid="$PID" -e "FEngine \(64 bits\) created" -m 1 >/dev/null
sleep "${ROTATE_DELAY:-0}"
step "rotate (${ROTATE_DELAY:-0} s after the first engine)"
a 30 shell settings put system user_rotation 1
sleep "$W"
step "read logcat"
a 60 logcat -d -v time Filament:I Sceneview:* REPRO:* REPRO_HEAP:* REPRO_THREADS:* AndroidRuntime:E ModelLoader:* rememberModelInstance:* '*:S' > "$OUT" \
  || step "logcat -d failed or timed out (rc=$?): $(timeout 10 adb devices | tr '\n' ' ')"
oom=$(grep -c "java.lang.OutOfMemoryError" "$OUT")
if [ "$oom" -gt 0 ]; then a 120 exec-out run-as repro.mavlab cat files/oom.hprof > "${OUT%.txt}.hprof" 2>/dev/null; [ -s "${OUT%.txt}.hprof" ] || rm -f "${OUT%.txt}.hprof"; fi
 vis=$(grep "MODEL VISIBLE" "$OUT" | sed 's/.*activity#\([0-9]\).*t=\([0-9]*\) ms/#\1@\2ms/' | tr '\n' ' ')
drain=$(grep -o "drained ([0-9]* ms" "$OUT" | head -1); at=$(grep -A6 "OutOfMemoryError" "$OUT" | grep -o "at [a-zA-Z0-9_.\$]*(" | grep -v -e dalvik. -e java.nio. | head -1)
peak=$(grep REPRO_HEAP "$OUT" | grep -o "peakNoBallast=[0-9]*" | tail -1)
max=$(grep -o "maxMemory=[0-9]*" "$OUT" | head -1)
rot=$(grep -c "activity onDestroy" "$OUT"); echo "rotations=$rot $(basename "$APK") budget=$B oom=$oom $at visible=[$vis] $drain $peak $max"
if [ -n "$REPRO_TSV" ]; then
  printf '%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\n' "$(basename "$APK")" "$oom" "${at#at }" "${vis% }" "$(echo "$drain" | grep -o '[0-9]*')" "${peak#peakNoBallast=}" "$rot" "${max#maxMemory=}" >> "$REPRO_TSV"
fi
# Stop the app before turning back to portrait, so that rotation never shows up in the next trial.
a 30 shell am force-stop repro.mavlab
a 30 shell settings put system user_rotation 0
