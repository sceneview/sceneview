#!/bin/bash
# usage: run-trial.sh <apk> <budgetMiB> <out.txt> [wait=40]
# One trial: launch, rotate to landscape once the first engine exists, wait, then read logcat.
# budgetMiB 0 = no ballast (the emulator's own heap limit does the work, e.g. an AVD with vm.heapSize=16).
# Prints one summary line. With REPRO_TSV set, also appends a tab-separated row to that file:
#   apk  oom  oom_frame  visible  drain_ms  peak_kib  rotations
A="adb -s ${ANDROID_SERIAL:-emulator-5554}"; APK=$1; B=$2; OUT=$3; W=${4:-40}
$A install -r -t "$APK" >/dev/null
$A shell settings put system accelerometer_rotation 0
$A shell settings put system user_rotation 0
$A shell am force-stop repro.mavlab; sleep 1
$A logcat -c
$A shell am start -n repro.mavlab/repro.MainActivity --ei budgetMiB "$B" >/dev/null
PID=""; for i in $(seq 1 50); do PID=$($A shell pidof repro.mavlab | tr -d "\r"); [ -n "$PID" ] && break; sleep 0.1; done
timeout 30 $A logcat --pid="$PID" -e "FEngine \(64 bits\) created" -m 1 >/dev/null
$A shell settings put system user_rotation 1
sleep "$W"
$A logcat -d -v time Filament:I Sceneview:* REPRO:* REPRO_HEAP:* AndroidRuntime:E ModelLoader:* rememberModelInstance:* '*:S' > "$OUT"
oom=$(grep -c "OutOfMemoryError" "$OUT"); vis=$(grep "MODEL VISIBLE" "$OUT" | sed 's/.*activity#\([0-9]\).*t=\([0-9]*\) ms/#\1@\2ms/' | tr '\n' ' ')
drain=$(grep -o "drained ([0-9]* ms" "$OUT" | head -1); at=$(grep -A6 "OutOfMemoryError" "$OUT" | grep -o "at [a-zA-Z0-9_.\$]*(" | grep -v -e dalvik. -e java.nio. | head -1)
peak=$(grep REPRO_HEAP "$OUT" | grep -o "peakNoBallast=[0-9]*" | tail -1)
rot=$(grep -c "activity onDestroy" "$OUT"); echo "rotations=$rot $(basename "$APK") budget=$B oom=$oom $at visible=[$vis] $drain $peak"
if [ -n "$REPRO_TSV" ]; then
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(basename "$APK")" "$oom" "${at#at }" "${vis% }" "$(echo "$drain" | grep -o '[0-9]*')" "${peak#peakNoBallast=}" "$rot" >> "$REPRO_TSV"
fi
$A shell settings put system user_rotation 0
