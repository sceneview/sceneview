#!/usr/bin/env bash
# setup-ar-emulator.sh — bootstrap a reusable ARCore-friendly Android emulator
#
# Why this exists:
#   SceneView QA had been routinely using a personal Pixel 9 over USB. That's risky
#   (it squats the user's phone, AR sessions can leak GPS/camera data, app installs
#   can crash and need manual recovery). #1241 mandates emulator-first QA.
#
# What it does:
#   1. Ensures the AVD `Pixel_7a` exists. The AVD *name* is kept for back-compat
#      with the QA scripts that hard-code it; the underlying device profile is
#      now pixel_8 (see DEVICE_PROFILE_PREFS below).
#   2. Builds it to an ANR-resistant QA spec: 6 CPU cores, 1 GB VM heap, a
#      free-disk-adaptive data partition, host-sized RAM (see below),
#      virtualscene back camera, emulated front camera (front cam is needed for
#      Augmented Faces demos), host GPU mode.
#   3. Boots the emulator headless by default — RAM-budgeted adaptive pool
#      (#1647 → #1654): leases a free already-running emulator, or — if the live
#      RAM-budgeted pool cap has room and free RAM clears the hard safety gate —
#      boots a new one on a distinct `-port`, or waits (bounded) for a lease to
#      free. Guest `-memory` is scaled to RAM headroom. The pool never exceeds
#      what live host RAM safely allows; the floor is always 1. Pass `--window`
#      (or set `EMU_VISIBLE=1`) to boot it VISIBLE for a local glance — opt-in,
#      local only; CI always stays headless.
#   4. Waits for `sys.boot_completed`.
#   5. Verifies Google Play Services for AR (com.google.ar.core) is installed;
#      if not, opens the Play Store to the listing (one-time manual click).
#
# Why the higher spec:
#   A SceneView 3D demo renders through Filament (host GPU) while QA runs
#   `adb shell screenrecord` at the same time — the recorder's H.264 encoder is
#   a software thread on the *guest*. On the stock 4-core / 4 GB AVD the guest
#   `system_server` gets CPU/memory-starved and ANRs ("System UI isn't
#   responding"), aborting the recording. More cores + RAM give it headroom.
#
#   RAM is sized to the *host*, not pinned at 8 GB: an 8 GB guest on a 16 GB Mac
#   that is also running the Gradle build + agent tooling gets SIGTERM'd by
#   macOS under memory pressure mid-QA — which aborts the recording just as
#   surely as an ANR. detect_target_ram_mb() reserves 10 GB for the host and
#   clamps the guest to [4 GB, 8 GB]: ~6 GB on a 16 GB Mac, the full 8 GB on a
#   24 GB+ Mac. The load-bearing ANR fix is the core count, not raw RAM.
#
# Flags:
#   --check   Read-only inspection: prints current AVD config + ARCore state +
#             pool state (running emulator count, RAM-budgeted cap, free RAM,
#             active leases). No mutation.
#   --clean   Wipe the AVD's userdata and recreate config from scratch.
#   --no-boot Skip the emulator boot (useful in CI where boot is deferred).
#   --window  Boot the emulator VISIBLE (windowed) so a developer can glance at
#             it. OPT-IN, local only. Default is headless (-no-window), which is
#             marginally lighter on the host (skips the skin-window draw +
#             window-server compositing). Equivalent to EMU_VISIBLE=1. The guest
#             VM cost is identical either way — only the host window draw differs.
#             CI never sets this; the device-QA workflow stays headless.
#   -h|--help Show this help.
#
# Idempotent: re-running with no flag will skip work that's already done. Leases a
# free already-running emulator when one exists. Stops nothing — caller closes the
# emulator (or it stays warm for follow-up scripts).
#
# Adaptive pool (#1647 → #1654): this script does NOT enforce a single emulator.
# It leases a free running emulator if one exists; otherwise, only when the live
# RAM-budgeted pool cap has room AND free RAM clears the hard safety gate, it
# boots a new emulator on a distinct `-port`; otherwise it waits for a lease to
# free. The pool never exceeds what host RAM safely allows; the floor is 1.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# RAM-budgeted adaptive emulator pool (#1647 → #1654): RAM monitoring, pool-cap
# computation, per-emulator lease registry, RAM-scaled `-memory`, multi-port
# boot, stale-lease reclaim.
# shellcheck source=lib/emulator-select.sh
source "$SCRIPT_DIR/lib/emulator-select.sh"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
EMULATOR_BIN="$SDK_ROOT/emulator/emulator"
ADB_BIN="$SDK_ROOT/platform-tools/adb"
AVDMANAGER_BIN="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
# Google's `android` CLI — the AI-agent front-end for emulator/sdk/screen ops.
# Used for the emulator lifecycle (start/stop) and SDK installs; the raw
# emulator/adb/sdkmanager binaries are the fallback when it is not installed.
ANDROID_CLI_BIN="$(command -v android 2>/dev/null || echo "$HOME/.local/bin/android")"
ANDROID_CLI=(--no-metrics)   # global flags: never phone home from this repo
AVD_NAME="Pixel_7a"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
AVD_CONFIG="$AVD_HOME/$AVD_NAME.avd/config.ini"
SYSTEM_IMAGE="system-images;android-36;google_apis_playstore;arm64-v8a"

# Detect arch for the system image
HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
  arm64|aarch64) IMG_ARCH="arm64-v8a" ;;
  x86_64|amd64)  IMG_ARCH="x86_64" ;;
  *) echo "[setup-ar] unsupported host arch $HOST_ARCH" >&2; exit 1 ;;
esac
SYSTEM_IMAGE="system-images;android-36;google_apis_playstore;$IMG_ARCH"

# --- ANR-resistant QA spec ----------------------------------------------------
# Guest RAM is sized to the host so macOS never SIGTERMs the emulator under
# memory pressure (that kills a QA recording exactly like an ANR does). Reserve
# 10 GB for the host (macOS + the Gradle build + the agent driving QA), clamp
# the guest to [4 GB, 8 GB]. 16 GB Mac -> ~6 GB guest; 24 GB+ Mac -> 8 GB.
detect_target_ram_mb() {
  local host_bytes host_mb ram
  host_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo '')"
  if [[ -z "$host_bytes" ]]; then
    echo 6144; return            # detection failed — safe middle value
  fi
  host_mb=$(( host_bytes / 1024 / 1024 ))
  ram=$(( host_mb - 10240 ))
  (( ram < 4096 )) && ram=4096
  (( ram > 8192 )) && ram=8192
  echo "$ram"
}
TARGET_RAM_MB="$(detect_target_ram_mb)"
TARGET_HEAP_MB=1024
TARGET_NCORE=6
# Data-partition size is bounded by FREE DISK: the emulator pre-allocates the
# whole partition + ~2.5 GB of working images when it first boots a fresh AVD,
# and refuses to start if that doesn't fit (`Not enough space to create
# userdata partition`). A fixed 12 GB therefore makes a recreate fail outright
# on a full disk. Size it to free space instead — reserve 5 GB for the
# emulator's overhead + a margin, clamp to [4 GB, 8 GB]. 8 GB is ample for QA;
# the storage-degradation issue (#... AVD fills after ~6 runs) is handled by the
# periodic `--clean` rebuild, not by an oversized partition.
detect_target_data_gb() {
  local free_gb
  free_gb="$(df -g "$AVD_HOME" 2>/dev/null | awk 'NR==2{print $4}')"
  [[ "$free_gb" =~ ^[0-9]+$ ]] || { echo 6; return; }   # detection failed
  local data=$(( free_gb - 5 ))
  (( data < 4 )) && data=4
  (( data > 8 )) && data=8
  echo "$data"
}
TARGET_DATA_GB="$(detect_target_data_gb)"
TARGET_DATA_PARTITION="${TARGET_DATA_GB}G"
# Most-capable recent Pixel profile actually installed in the SDK. Pixel 9 ships
# no `avdmanager` profile yet; pixel_8 has the same 1080x2400 @ 420dpi geometry
# as pixel_7a (so the screenshot-crop math in capture-play-store-screenshots.sh
# is unchanged) but is the newer reference device. Falls down the list if absent.
DEVICE_PROFILE_PREFS=(pixel_9_pro pixel_9 pixel_8 pixel_8a pixel_7a pixel_7)

CHECK_ONLY=false
CLEAN=false
NO_BOOT=false
STOP_AFTER=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)   CHECK_ONLY=true; shift ;;
    --clean)   CLEAN=true; shift ;;
    --no-boot) NO_BOOT=true; shift ;;
    --stop)    STOP_AFTER=true; shift ;;
    --window)  EMU_VISIBLE=1; shift ;;
    -h|--help)
      # Print the leading comment block (lines starting with #), stop at `set -`.
      # Skip the shebang (line 1).
      awk 'NR==1{next} /^set -/{exit} /^#/{sub(/^# ?/,""); print}' "$0"
      exit 0 ;;
    *) echo "[setup-ar] unknown flag: $1" >&2; exit 2 ;;
  esac
done

log() { echo "[setup-ar] $*"; }

# --- step 1: verify SDK basics -------------------------------------------------
[[ -x "$EMULATOR_BIN" ]] || { log "missing emulator at $EMULATOR_BIN"; exit 1; }
[[ -x "$ADB_BIN" ]] || { log "missing adb at $ADB_BIN"; exit 1; }
[[ -x "$AVDMANAGER_BIN" ]] || { log "missing avdmanager at $AVDMANAGER_BIN"; exit 1; }

# --- step 1b: emulator-version guard ------------------------------------------
# Android Emulator 36.x REGRESSED `adb shell screenrecord`: it no longer
# captures host-GPU-composited frames, so a Filament 3D scene records as a
# near-empty ~0.5 fps mp4 (verified: 36.5.11 -> 23 frames / 48 s; 35.6.11 ->
# 2541 frames). That silently breaks every QA screen recording while leaving
# the demo itself running fine — the worst kind of regression. 35.6.11 is the
# last version where screenrecord captures GPU content correctly, so the
# SceneView QA emulator is pinned there. If `sdkmanager` has since pulled a
# 36+ emulator, warn loudly with the one-shot re-pin recipe.
KNOWN_GOOD_EMULATOR_BUILD=13610412   # emulator 35.6.11
check_emulator_version() {
  local ver major
  ver="$("$EMULATOR_BIN" -version 2>/dev/null | sed -nE 's/.*version ([0-9]+)\..*/\1/p' | head -1)"
  major="${ver:-0}"
  if [[ "$major" -ge 36 ]]; then
    log "WARNING ────────────────────────────────────────────────────────────"
    log "  Emulator major version $major detected. Emulator 36.x breaks"
    log "  'adb shell screenrecord' capture of host-GPU content — SceneView QA"
    log "  recordings will be near-empty. Re-pin to 35.6.11 (build"
    log "  $KNOWN_GOOD_EMULATOR_BUILD), after killing any running emulator:"
    log "    curl -fsSL -o /tmp/emu.zip \\"
    log "      https://dl.google.com/android/repository/emulator-darwin_aarch64-${KNOWN_GOOD_EMULATOR_BUILD}.zip"
    log "    rm -rf '$SDK_ROOT/emulator' && unzip -q /tmp/emu.zip -d '$SDK_ROOT'"
    log "  (use emulator-linux_x64-${KNOWN_GOOD_EMULATOR_BUILD}.zip on Linux.)"
    log "─────────────────────────────────────────────────────────────────────"
  fi
}
check_emulator_version

# --- step 2: verify system image is installed ---------------------------------
SYS_IMAGE_DIR="$SDK_ROOT/system-images/android-36/google_apis_playstore/$IMG_ARCH"
if [[ ! -d "$SYS_IMAGE_DIR" ]]; then
  if $CHECK_ONLY; then
    log "MISSING: $SYS_IMAGE_DIR (install with: sdkmanager '$SYSTEM_IMAGE')"
    exit 1
  fi
  log "installing system image: $SYSTEM_IMAGE"
  if [[ -x "$ANDROID_CLI_BIN" ]] && "$ANDROID_CLI_BIN" "${ANDROID_CLI[@]}" sdk install "$SYSTEM_IMAGE"; then
    log "system image installed via android CLI"
  else
    log "android CLI unavailable/failed — falling back to sdkmanager"
    yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "$SYSTEM_IMAGE" || {
      log "sdkmanager failed; check ANDROID_SDK_ROOT and licenses"; exit 1
    }
  fi
fi

# --- step 3: create or recreate the AVD ---------------------------------------
recreate_avd() {
  # The emulator pre-allocates the data partition + ~2.5 GB of working images
  # the first time it boots a fresh AVD. Warn early if the disk can't fit it,
  # so the failure mode is a clear message here rather than a cryptic emulator
  # error five minutes into the boot.
  local free_gb need_gb
  free_gb="$(df -g "$AVD_HOME" 2>/dev/null | awk 'NR==2{print $4}')"
  need_gb=$(( TARGET_DATA_GB + 3 ))
  if [[ "$free_gb" =~ ^[0-9]+$ ]] && (( free_gb < need_gb )); then
    log "WARNING: only ${free_gb} GB free on the AVD volume; a fresh AVD needs"
    log "  ~${need_gb} GB — the emulator may refuse to create the userdata"
    log "  partition. Free disk space before this boot."
  fi
  # Pick the most-capable device profile this SDK actually has installed.
  local avail device_arg=""
  avail="$("$AVDMANAGER_BIN" list device 2>/dev/null || true)"
  for cand in "${DEVICE_PROFILE_PREFS[@]}"; do
    if grep -qE "id: [0-9]+ or \"$cand\"" <<<"$avail"; then
      device_arg="$cand"; break
    fi
  done
  [[ -z "$device_arg" ]] && device_arg="pixel_7"
  log "creating AVD $AVD_NAME (device=$device_arg, image=$SYSTEM_IMAGE)"
  echo "no" | "$AVDMANAGER_BIN" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$device_arg" \
    --force >/dev/null
}

# True if the AVD is absent or built on the old low-spec config, so a full run
# rebuilds it into the ANR-resistant spec. RAM + cores are the load-bearing keys
# (a fresh recreate also clears accumulated userdata cruft and the larger data
# partition only takes effect on a clean image).
avd_is_stale() {
  [[ -f "$AVD_CONFIG" ]] || return 0
  local ram ncore
  ram="$(awk -F'=' '/^hw\.ramSize/{gsub(/ /,"",$2);print $2}' "$AVD_CONFIG")"
  ncore="$(awk -F'=' '/^hw\.cpu\.ncore/{gsub(/ /,"",$2);print $2}' "$AVD_CONFIG")"
  [[ "${ram:-0}" -ge "$TARGET_RAM_MB" && "${ncore:-0}" -ge "$TARGET_NCORE" ]] && return 1
  return 0
}

if $CLEAN; then
  if $CHECK_ONLY; then
    log "--check and --clean are mutually exclusive"; exit 2
  fi
  log "removing existing AVD $AVD_NAME"
  "$AVDMANAGER_BIN" delete avd --name "$AVD_NAME" 2>/dev/null || true
fi

if $CHECK_ONLY; then
  if [[ ! -f "$AVD_CONFIG" ]]; then
    log "MISSING AVD $AVD_NAME (would create on full run)"
    exit 1
  fi
  avd_is_stale && log "AVD $AVD_NAME is below the ANR-resistant spec (RAM/cores) — a full run would rebuild it"
elif avd_is_stale; then
  if [[ -f "$AVD_CONFIG" ]]; then
    log "AVD $AVD_NAME is below the ANR-resistant spec — rebuilding"
    "$AVDMANAGER_BIN" delete avd --name "$AVD_NAME" 2>/dev/null || true
  fi
  recreate_avd
fi

# --- step 4: patch config.ini for ARCore --------------------------------------
# Pinned values: host-sized RAM / 1 GB heap / 6 cores / free-disk-adaptive data
# partition ([4G,8G], see detect_target_data_gb) — ANR-resistant QA —
# virtualscene back camera (ARCore can drive it), emulated front (Augmented
# Faces), host GPU mode.
patch_kv() {
  local key="$1"
  local value="$2"
  local file="$3"
  # Use [[:space:]] for portability (BSD sed in macOS doesn't honor \s)
  local key_escaped="${key//./\\.}"
  if grep -qE "^${key_escaped}[[:space:]]*=" "$file"; then
    # GNU/BSD sed compat: write to .bak then move
    sed -i.bak -E "s|^(${key_escaped})[[:space:]]*=.*|\1 = $value|" "$file" && rm -f "$file.bak"
  else
    echo "$key = $value" >> "$file"
  fi
}

apply_ar_config() {
  log "patching $AVD_CONFIG for ARCore + ANR-resistant QA"
  patch_kv "PlayStore.enabled" "yes" "$AVD_CONFIG"
  patch_kv "hw.camera.back"   "virtualscene" "$AVD_CONFIG"
  patch_kv "hw.camera.front"  "emulated"     "$AVD_CONFIG"
  patch_kv "hw.gpu.enabled"   "yes"          "$AVD_CONFIG"
  patch_kv "hw.gpu.mode"      "host"         "$AVD_CONFIG"
  # Headroom so a Filament render + screenrecord don't starve system_server.
  patch_kv "hw.ramSize"       "$TARGET_RAM_MB"         "$AVD_CONFIG"
  patch_kv "vm.heapSize"      "$TARGET_HEAP_MB"        "$AVD_CONFIG"
  patch_kv "hw.cpu.ncore"     "$TARGET_NCORE"          "$AVD_CONFIG"
  patch_kv "disk.dataPartition.size" "$TARGET_DATA_PARTITION" "$AVD_CONFIG"
  patch_kv "hw.sensors.orientation" "yes"    "$AVD_CONFIG"
  patch_kv "hw.sensors.proximity"   "yes"    "$AVD_CONFIG"
  patch_kv "hw.gps"           "yes"          "$AVD_CONFIG"
}

show_config() {
  log "current AVD config (QA-relevant keys):"
  grep -E "^(PlayStore|hw\.camera|hw\.gpu|hw\.ramSize|vm\.heapSize|hw\.cpu\.ncore|disk\.dataPartition\.size|hw\.device\.name|hw\.gps|image\.sysdir)" "$AVD_CONFIG" | sed 's/^/  /'
}

# Report pool state (#1654). Used by --check and before every boot decision:
# running emulator count, the live RAM-budgeted cap, free RAM, active leases.
show_ram_and_emulator_status() {
  emu_pool_reclaim_stale "$ADB_BIN"
  emu_pool_status "$ADB_BIN"
  local running cap
  running="$(emu_count_running "$ADB_BIN")"
  cap="$(emu_pool_cap)"
  local free_serial
  if free_serial="$(emu_lease_free_serial "$ADB_BIN")"; then
    log "an unleased running emulator is available ($free_serial) — would LEASE it (no boot)"
  elif [[ "$running" -ge "$cap" ]]; then
    log "pool is at its RAM-budgeted cap (running ${running} >= cap ${cap}) — would WAIT for a lease to free"
  elif emu_ram_allows_boot >/dev/null 2>&1; then
    log "pool has room (running ${running} < cap ${cap}) and free RAM clears the boot gate"
    log "would boot a new emulator on -port $(emu_next_free_port "$ADB_BIN") with -memory $(emu_recommended_memory_mb) MB"
  else
    log "pool has a free slot (running ${running} < cap ${cap}) but live free RAM is below the ${EMU_MIN_FREE_RAM_MB} MB gate"
    log "memory safety wins — would WAIT for a running emulator's lease to free instead of booting"
  fi
}

if $CHECK_ONLY; then
  show_config
  show_ram_and_emulator_status
else
  apply_ar_config
  show_config
fi

# --- step 5: lease or boot a pool emulator ------------------------------------
# RAM-budgeted adaptive pool (#1647 → #1654). The decision flow:
#   1. An unleased running emulator?  -> LEASE it, no boot.
#   2. Pool cap has room AND free RAM clears the hard gate?  -> boot a new
#      emulator on a distinct `-port` and lease it.
#   3. Otherwise the pool is at its RAM-budgeted cap  -> WAIT (bounded) for a
#      lease to free, then lease that emulator.
# The leased serial is exported as EMU_SERIAL for the rest of the script and
# any downstream QA caller.

# EMU_SERIAL holds the serial this run is responsible for. Lease bookkeeping is
# keyed by pid inside emulator-select.sh, so there is no separate state to track.
EMU_SERIAL=""

# Release every lease this process owns on exit, no matter how the script ends.
# emu_lease_release_all is a no-op for serials owned by other sessions.
trap 'emu_lease_release_all' EXIT

# boot_new_emulator <port> — boot a fresh emulator on a distinct console port so
# it coexists with pool peers. Echoes nothing; the serial is `emulator-<port>`.
#
# Headless by default (-no-window): skips the emulator skin-window draw and the
# host window-server compositing — marginally lighter on the host. Opt in to a
# VISIBLE (windowed) emulator with `--window` or `EMU_VISIBLE=1` for a local
# glance; the guest VM cost is identical, only the host window draw differs.
boot_new_emulator() {
  local port="$1"
  local mem; mem="$(emu_recommended_memory_mb)"
  # Window mode: headless unless EMU_VISIBLE is truthy (set by --window).
  local window_arg="-no-window" window_desc="headless"
  if [[ "${EMU_VISIBLE:-0}" == "1" || "${EMU_VISIBLE:-}" == "true" ]]; then
    window_arg=""
    window_desc="windowed (EMU_VISIBLE)"
  fi
  log "booting a new pool emulator $AVD_NAME on -port ${port} (${window_desc}, no snapshot, -memory ${mem} MB)"
  # `-read-only` lets multiple emulators share the one AVD's disk images without
  # clobbering each other's userdata; `-port` gives each a distinct serial.
  # nohup + & so the emulator survives this script; per-port log for debugging.
  local emu_log="/tmp/sceneview-emulator-${AVD_NAME}-${port}.log"
  # shellcheck disable=SC2086 # window_arg is intentionally word-split (empty = visible)
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" \
    -port "$port" \
    -read-only \
    -gpu host \
    -memory "$mem" \
    $window_arg \
    -no-snapshot-load \
    -no-audio \
    -no-boot-anim \
    -netdelay none -netspeed full \
    >"$emu_log" 2>&1 &
  log "emulator pid=$! serial=emulator-${port} log=$emu_log"
}

select_or_boot_emulator() {
  # Reclaim any leases left behind by crashed sessions before we decide.
  emu_pool_reclaim_stale "$ADB_BIN"

  # Step 1: lease a free already-running emulator if one exists.
  local free_serial
  if free_serial="$(emu_lease_free_serial "$ADB_BIN")"; then
    if emu_lease_acquire "$free_serial"; then
      log "leased already-running emulator $free_serial — no boot"
      EMU_SERIAL="$free_serial"
      return 0
    fi
    # Lost the lease race — fall through and re-evaluate.
  fi

  # Step 2: pool cap has room? Boot a new emulator on a distinct port.
  local running cap
  running="$(emu_count_running "$ADB_BIN")"
  cap="$(emu_pool_cap)"
  if [[ "$running" -lt "$cap" ]]; then
    # HARD memory-safety re-gate: re-read free RAM immediately before booting.
    # The cap is an estimate; this live check is the invariant that keeps the
    # host off the OOM cliff. Refuse the boot if RAM is below the threshold.
    if emu_ram_allows_boot; then
      local port; port="$(emu_next_free_port "$ADB_BIN")"
      local serial="emulator-${port}"
      boot_new_emulator "$port"
      # Lease the slot immediately so a racing peer counts us against the cap.
      emu_lease_acquire "$serial" || true
      EMU_SERIAL="$serial"
      return 0
    fi
    log "pool cap has room (running ${running} < cap ${cap}) but live free RAM is below ${EMU_MIN_FREE_RAM_MB} MB"
    log "memory safety wins — will WAIT for a running emulator's lease to free instead of booting"
  else
    log "pool is at its RAM-budgeted cap (running ${running}, cap ${cap}) — waiting for a lease to free"
  fi

  # Step 3: pool full (by cap or by the live RAM gate). Wait for a lease to free.
  local waited_serial
  if waited_serial="$(emu_lease_wait_for_free "$ADB_BIN" "${EMU_LEASE_WAIT_TIMEOUT:-300}")"; then
    if emu_lease_acquire "$waited_serial"; then
      log "leased emulator $waited_serial after waiting for the pool"
      EMU_SERIAL="$waited_serial"
      return 0
    fi
  fi
  log "could not lease or boot a pool emulator — pool is full and no lease freed in time"
  return 1
}

wait_for_boot() {
  # When we leased an already-running emulator, EMU_SERIAL is already booted.
  local serial="$EMU_SERIAL"
  if [[ -n "$serial" ]] && emu_serial_alive "$serial" "$ADB_BIN"; then
    log "leased emulator $serial already online"
  else
    log "waiting for $serial to come online (up to 180s)"
    local i=0
    while ! emu_serial_alive "$serial" "$ADB_BIN"; do
      i=$((i+1))
      if [[ $i -gt 90 ]]; then
        log "$serial did not appear after 180s — boot failed"
        return 1
      fi
      sleep 2
    done
  fi
  log "device $serial online, waiting for sys.boot_completed"
  "$ADB_BIN" -s "$serial" wait-for-device
  local i=0
  while [[ "$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" != "1" ]]; do
    i=$((i+1))
    if [[ $i -gt 90 ]]; then
      log "sys.boot_completed not set after 180s"
      return 1
    fi
    sleep 2
  done
  log "boot complete on $serial"
  export EMU_SERIAL="$serial"
  # Publish the leased serial to a file as a fallback for a parent QA
  # orchestrator. The primary channel is the `EMU_SERIAL=<serial>` stdout line
  # emitted in step 8. This script's own lease is dropped by the EXIT trap; the
  # parent re-acquires it for the QA run.
  _emu_lease_dir_init
  echo "$serial" > "$EMU_LEASE_DIR/last-booted.serial" 2>/dev/null || true
}

if ! $CHECK_ONLY && ! $NO_BOOT; then
  # select_or_boot_emulator returns non-zero only when the pool is full AND no
  # lease freed within the bounded wait (or RAM stayed too tight to boot). In
  # that case there is no emulator to wait for — fail fast with a clear exit so
  # the device-QA orchestrator records a `skipped`/`failed` leg instead of
  # hanging. The leased serial is exported as EMU_SERIAL for downstream callers.
  if select_or_boot_emulator; then
    wait_for_boot
  else
    log "could not obtain a pool emulator — see the reason above"
    exit 1
  fi
fi

# --- step 6: verify / install ARCore (Google Play Services for AR) ------------
# The emulator's bundled Play Store does not auto-provision ARCore. We fetch the
# arm64/x86 APK straight from the public google-ar SDK GitHub release — it carries
# native code for both ABIs and installs cleanly on the emulator.
install_arcore() {
  local serial="$1"
  local tmp_apk="/tmp/sceneview-arcore.apk"
  log "resolving latest ARCore APK from google-ar/arcore-android-sdk"
  local apk_url
  apk_url="$(curl -fsSL "https://api.github.com/repos/google-ar/arcore-android-sdk/releases/latest" 2>/dev/null \
    | python3 -c "import json,sys
d=json.load(sys.stdin)
arch='$IMG_ARCH'
# x86 emulator build for x86_64 hosts, generic (arm64+armv7) APK otherwise
for a in d.get('assets',[]):
    n=a['name']
    if arch=='x86_64' and 'x86_for_emulator' in n:
        print(a['browser_download_url']); break
else:
    for a in d.get('assets',[]):
        n=a['name']
        if n.startswith('Google_Play_Services_for_AR_') and n.endswith('.apk') and 'x86_for_emulator' not in n:
            print(a['browser_download_url']); break
" 2>/dev/null)"
  if [[ -z "$apk_url" ]]; then
    log "could not resolve ARCore APK URL — install manually via Play Store"
    "$ADB_BIN" -s "$serial" shell am start -a android.intent.action.VIEW \
      -d 'market://details?id=com.google.ar.core' >/dev/null 2>&1 || true
    return 1
  fi
  log "downloading $apk_url"
  curl -fsSL -o "$tmp_apk" "$apk_url" || { log "ARCore download failed"; return 1; }
  log "installing ARCore on $serial"
  # `wait_for_pm` (called before this step) is the primary guard against the
  # post-boot package-manager settling race. This short retry is a cheap
  # belt-and-braces backstop for the residual flake `pm path android` can't
  # fully rule out (e.g. a transient install-service hiccup).
  local attempt err
  for attempt in 1 2 3; do
    err="$("$ADB_BIN" -s "$serial" install -r "$tmp_apk" 2>&1)" && return 0
    log "ARCore install attempt $attempt/3 failed${err:+: ${err##*$'\n'}} — retrying in 3s"
    sleep 3
  done
  log "ARCore install failed after 3 attempts"
  return 1
}

# `android emulator start` hands back control at "ready", but the guest package
# manager can still be settling — `pm list`/`pm install` race right after boot
# (observed: ARCore install failed once, succeeded seconds later). Poll until pm
# answers before touching packages.
wait_for_pm() {
  local serial="$1" _
  for _ in $(seq 1 30); do
    "$ADB_BIN" -s "$serial" shell pm path android >/dev/null 2>&1 && return 0
    sleep 2
  done
  log "package manager not responding after 60s on $serial"
  return 1
}

# Target the leased serial (EMU_SERIAL) — never a hardcoded one. With an
# adaptive pool several emulators may be up; we must verify ARCore on ours.
serial="${EMU_SERIAL:-$("$ADB_BIN" devices | awk '/^emulator-[0-9]+/ && $2=="device" {print $1; exit}')}"
if [[ -n "$serial" ]] && ! $CHECK_ONLY && ! $NO_BOOT; then
  wait_for_pm "$serial" || true
fi
if [[ -n "$serial" ]]; then
  if "$ADB_BIN" -s "$serial" shell pm list packages 2>/dev/null | grep -q "com.google.ar.core"; then
    log "ARCore (com.google.ar.core) is installed on $serial"
  else
    log "ARCore NOT installed on $serial"
    if ! $CHECK_ONLY; then
      install_arcore "$serial" && log "ARCore installed OK on $serial"
    fi
  fi
else
  log "(no emulator running — skipped ARCore check)"
fi

# --- step 7: optionally stop the emulator -------------------------------------
# By default we leave the emulator warm so a QA script can run immediately after.
# --stop kills it for hygiene-conscious callers (CI, one-shot config runs).
if $STOP_AFTER && ! $CHECK_ONLY && [[ -n "$serial" ]]; then
  log "stopping emulator $serial (--stop)"
  if [[ -x "$ANDROID_CLI_BIN" ]]; then
    "$ANDROID_CLI_BIN" "${ANDROID_CLI[@]}" emulator stop "$AVD_NAME" >/dev/null 2>&1 \
      || "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
  else
    "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
fi

# --- step 8: summary ----------------------------------------------------------
# Emit the leased serial on stdout (machine-readable, last line) so a parent
# orchestrator capturing this script's stdout gets the exact pool emulator it
# obtained, without racing on the shared last-booted.serial file.
if ! $CHECK_ONLY && [[ -n "${serial:-}" ]]; then
  echo "EMU_SERIAL=${serial}"
fi
log "done."
if $STOP_AFTER; then
  log "emulator stopped. Re-run without --stop to leave it warm for QA."
else
  log "emulator left running for QA on serial ${serial:-emulator-5554}. next steps:"
  log "  export ANDROID_SERIAL=${serial:-emulator-5554}   # pin QA to the leased emulator"
  log "  source .claude/scripts/lib/android-cli.sh && android_cli_ensure"
  log "  android run --apks <apk> --activity io.github.sceneview.demo/.MainActivity"
  log "  stop it with: android emulator stop $AVD_NAME"
fi
