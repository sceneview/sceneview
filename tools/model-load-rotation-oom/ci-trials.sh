#!/bin/bash
# usage: ci-trials.sh <apk-dir> "<versions>" <api> <heapMiB> <trials> <out-dir>
# Sets the app heap limit to <heapMiB>, runs run-trial.sh <trials> times per version on the booted
# emulator and writes a markdown table to <out-dir>/results.md (and to $GITHUB_STEP_SUMMARY when set).
# An out-of-memory error is a result, not a failure: the script fails only if the harness itself
# breaks (heap limit not applied, emulator gone).
set -u
DIR=$1; VERSIONS=$2; API=$3; HEAP=$4; N=$5; OUT=$6
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
step() { echo "[$(date -u +%H:%M:%S)] $*"; }
prop() { timeout 20 adb shell getprop "$1" | tr -d '\r'; }
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$OUT/results.md" >> "$GITHUB_STEP_SUMMARY"; cat "$OUT/results.md"; }
diag() {
  step "diagnostics: $*"; timeout 10 adb devices -l
  pgrep -af qemu-system >/dev/null && pgrep -af qemu-system | cut -c1-160 || echo "no emulator process"
  free -m; sudo -n dmesg 2>/dev/null | grep -iE 'oom|killed process|qemu|segfault|trap' | tail -10
  ls -t /tmp/android-*/emu-crash-*.db 2>/dev/null | head -3; ls -t "$HOME"/.android/emu-crash* 2>/dev/null | head -3
}

# The emulator runner's heap-size input writes hw.heapSize, which the emulator ignores (the
# images boot with dalvik.vm.heapsize=512m and no growth limit). Set the per-app limit
# (dalvik.vm.heapgrowthlimit, what Runtime.maxMemory() reports for an app without largeHeap)
# as root, then restart the framework so zygote forks apps with it. system_server clears its
# own growth limit, so the system itself keeps its 512 MiB.
step "heap: dalvik.vm.heapgrowthlimit=${HEAP}m"
timeout 60 adb root; sleep 3; timeout 60 adb wait-for-device
timeout 20 adb shell setprop dalvik.vm.heapgrowthlimit "${HEAP}m"
timeout 20 adb shell stop
timeout 20 adb shell setprop sys.boot_completed 0
timeout 20 adb shell start
for i in $(seq 1 150); do [ "$(prop sys.boot_completed)" = "1" ] && break; sleep 2; done
step "framework restarted (boot_completed=$(prop sys.boot_completed))"
{ echo "heapgrowthlimit=$(prop dalvik.vm.heapgrowthlimit)"; echo "heapsize=$(prop dalvik.vm.heapsize)"; } | tee "$OUT/props.txt"
{
  echo "### API $API, heap ${HEAP} MiB ($(tr '\n' ' ' < "$OUT/props.txt"))"
  echo
  echo "| API | heap | version | trial | app max heap (MiB) | OOM | first app frame | drain (ms) | model visible | peak heap (KiB) | rotations |"
  echo "|---|---|---|---|---|---|---|---|---|---|---|"
} > "$OUT/results.md"
if [ "$(prop dalvik.vm.heapgrowthlimit)" != "${HEAP}m" ] || [ "$(prop sys.boot_completed)" != "1" ]; then
  echo "| $API | $HEAP | – | – | – | heap limit not applied, no trial | | | | | |" >> "$OUT/results.md"
  diag "heap limit not applied"; summary; echo "::error::heap limit ${HEAP}m not applied"; exit 1
fi
for v in $VERSIONS; do
  apk="$DIR/mavlab-repro-$v.apk"
  if [ ! -f "$apk" ]; then
    echo "::warning::no APK for $v (build skipped or failed)"
    echo "| $API | $HEAP | $v | – | | no APK | | | | | |" >> "$OUT/results.md"
    continue
  fi
  for t in $(seq 1 "$N"); do
    tsv="$OUT/$v-$t.tsv"; : > "$tsv"
    if [ "$(timeout 10 adb get-state 2>/dev/null)" != "device" ]; then
      echo "| $API | $HEAP | $v | $t | | emulator gone | | | | | |" >> "$OUT/results.md"
      diag "emulator gone before $v trial $t"; summary; echo "::error::emulator gone"; exit 1
    fi
    step "trial $v #$t"
    REPRO_TSV="$tsv" timeout 300 bash "$HERE/run-trial.sh" "$apk" 0 "$OUT/logcat-$v-$t.txt" 60 || diag "trial $v #$t ended with rc=$?"
    IFS=$'\t' read -r _ oom frame vis drain peak rot max < "$tsv" || true
    echo "| $API | $HEAP | $v | $t | ${max:-?} | ${oom:-?} | ${frame:-} | ${drain:-} | ${vis:-none} | ${peak:-} | ${rot:-} |" >> "$OUT/results.md"
  done
done
summary
