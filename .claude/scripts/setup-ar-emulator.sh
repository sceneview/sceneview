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
#   A SceneView 3D demo renders through Filament (host GPU) while QA drives the
#   demo catalog through Maestro. On the stock 4-core / 4 GB AVD the guest
#   `system_server` gets CPU/memory-starved and ANRs ("System UI isn't
#   responding"), aborting the run. More cores + RAM give it headroom.
#   (Screen recording is now host-side — `adb emu screenrecord`, #1671 — so it
#   no longer adds a guest-side H.264 encoder thread to that budget; the spec
#   headroom is kept for the Filament render itself.)
#
#   RAM is sized to the *host*, not pinned at 8 GB: an 8 GB guest on a 16 GB Mac
#   that is also running the Gradle build + agent tooling gets SIGTERM'd by
#   macOS under memory pressure mid-QA. detect_target_ram_mb() reserves 10 GB
#   for the host and clamps the guest to [4 GB, 8 GB]: ~6 GB on a 16 GB Mac, the
#   full 8 GB on a 24 GB+ Mac. The load-bearing ANR fix is the core count, not
#   raw RAM.
#
# Emulator snapshots (#1672 — fixes the storage-degradation bug):
#   The QA AVD's userdata partition fills up after ~6 QA runs and Filament
#   viewports turn black (a known issue tracked in memory). The fix is a clean
#   *boot snapshot* seeded ONCE right after ARCore is installed, then cold-booted
#   from on every subsequent run with `-no-snapshot-save` — so QA runs LOAD the
#   warm golden image but never WRITE back to it. Every run therefore starts from
#   the identical post-install state: deterministic, and far faster than a cold
#   boot (snapshot restore is seconds vs. a 60-90s cold boot).
#     --seed-snapshot   Boot, install ARCore, then save the golden snapshot
#                       `qa-clean` and exit. Run this once after `--clean`, or
#                       whenever the golden image should be refreshed.
#     --no-snapshot     Force a cold boot even when a golden snapshot exists
#                       (escape hatch for debugging a stale snapshot).
#   When a `qa-clean` snapshot exists a normal run boots from it automatically
#   with `-no-snapshot-save`; otherwise it cold-boots as before. Snapshot
#   restore only applies to the FIRST (port 5554) pool emulator — `-read-only`
#   pool peers cannot safely share a snapshot, so they always cold-boot.
#
# Flags:
#   --check          Read-only inspection: prints current AVD config + ARCore
#                    state + pool state (running emulator count, RAM-budgeted
#                    cap, free RAM, active leases) + golden-snapshot presence
#                    + camera-id topology of the running emulator (#2754 —
#                    no HAL id 0 means ARCore live-camera AR cannot start).
#                    No mutation.
#   --clean          Wipe the AVD's userdata and recreate config from scratch.
#                    Also drops the golden `qa-clean` snapshot — re-seed it with
#                    `--seed-snapshot` afterwards.
#   --no-boot        Skip the emulator boot (useful in CI where boot is deferred).
#   --seed-snapshot  Boot, install ARCore, save the golden `qa-clean` snapshot,
#                    then exit. Run once to seed/refresh the warm QA image.
#   --no-snapshot    Cold-boot even if a golden snapshot exists (debug escape).
#   --window         Boot the emulator VISIBLE (windowed) so a developer can
#                    glance at it. OPT-IN, local only. Default is headless
#                    (-no-window), which is marginally lighter on the host (skips
#                    the skin-window draw + window-server compositing).
#                    Equivalent to EMU_VISIBLE=1. The guest VM cost is identical
#                    either way — only the host window draw differs. CI never
#                    sets this; the device-QA workflow stays headless.
#   --rosetta        Provision/boot the SEPARATE x86_64-under-Rosetta AR rig
#                    (#2758): AVD `Pixel_7a_x86` on reserved port 5584 — Intel
#                    emulator bundle + x86_64 system image + the
#                    `_x86_for_emulator` ARCore APK. Was believed to be the only path to
#                    LIVE-CAMERA ARCore sessions on Apple Silicon (#2754:
#                    ARCore ships no arm64 emulator build; arm64 AVDs never
#                    expose camera HAL id 0). ~9 GB one-time payload,
#                    disk-gated up front. The x86 guest runs under
#                    pure-software TCG (Apple Silicon cannot hardware-
#                    accelerate an x86 guest — HVF is arm64-only), so expect a
#                    20-90 min first boot and ~5-10x-slower interaction: built
#                    for unattended QA probes, not interactive use. The rig is
#                    INVISIBLE to the QA emulator pool (reserved port range, see
#                    lib/emulator-select.sh EMU_POOL_PORT_EXCLUDE_FROM) so a
#                    standard QA run can never lease it by accident. Combines
#                    with: --check (read-only rig report), --clean (recreate
#                    the x86 AVD ONLY — never touches the arm64 AVD or its
#                    snapshot), --no-boot (provision without booting),
#                    --window. Incompatible with --seed-snapshot.
#   -h|--help        Show this help.
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
# Golden boot-snapshot name (#1672). A clean post-ARCore-install snapshot seeded
# once with --seed-snapshot; subsequent QA runs cold-boot from it (`-snapshot
# qa-clean -no-snapshot-save`) so every run starts from the identical warm state
# and the userdata partition never accumulates run-to-run cruft.
GOLDEN_SNAPSHOT="qa-clean"
SNAPSHOTS_DIR="$AVD_HOME/$AVD_NAME.avd/snapshots"
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
SEED_SNAPSHOT=false
NO_SNAPSHOT=false
ROSETTA=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)          CHECK_ONLY=true; shift ;;
    --clean)          CLEAN=true; shift ;;
    --no-boot)        NO_BOOT=true; shift ;;
    --stop)           STOP_AFTER=true; shift ;;
    --seed-snapshot)  SEED_SNAPSHOT=true; shift ;;
    --no-snapshot)    NO_SNAPSHOT=true; shift ;;
    --rosetta)        ROSETTA=true; shift ;;
    --window)         EMU_VISIBLE=1; shift ;;
    -h|--help)
      # Print the leading comment block (lines starting with #), stop at `set -`.
      # Skip the shebang (line 1).
      awk 'NR==1{next} /^set -/{exit} /^#/{sub(/^# ?/,""); print}' "$0"
      exit 0 ;;
    *) echo "[setup-ar] unknown flag: $1" >&2; exit 2 ;;
  esac
done

# --seed-snapshot needs a freshly booted emulator — reject the contradictory
# combinations up front (#1672) rather than failing late after side effects.
if $SEED_SNAPSHOT; then
  if $CHECK_ONLY; then
    echo "[setup-ar] --seed-snapshot and --check are mutually exclusive" >&2; exit 2
  fi
  if $NO_BOOT; then
    echo "[setup-ar] --seed-snapshot needs a boot — incompatible with --no-boot" >&2; exit 2
  fi
  if $ROSETTA; then
    # The Rosetta rig has no golden-snapshot support (phase 1: prove the rig
    # works at all; snapshots can come later if TCG restore proves reliable).
    echo "[setup-ar] --seed-snapshot is not supported with --rosetta" >&2; exit 2
  fi
fi

log() { echo "[setup-ar] $*"; }

# --- snapshot helpers (#1672) -------------------------------------------------
# A "golden" boot snapshot is a clean post-ARCore-install image. Once seeded,
# QA runs cold-boot from it with `-no-snapshot-save` — load the warm state,
# never write back — so every run is deterministic and the userdata partition
# never accumulates the cruft that turns Filament viewports black after ~6 runs.
#
# golden_snapshot_exists — 0 if the AVD has a saved `qa-clean` snapshot. The
# emulator stores each snapshot as a directory under <avd>/snapshots/<name>.
golden_snapshot_exists() {
  [[ -d "$SNAPSHOTS_DIR/$GOLDEN_SNAPSHOT" ]]
}

# save_golden_snapshot <serial> — persist the running emulator's state as the
# golden snapshot via the emulator console `avd snapshot save`. The `android`
# CLI has no snapshot subcommand in v0.7, and `adb emu` IS the documented
# emulator-console transport (not raw `adb shell`), so this is the correct tool.
save_golden_snapshot() {
  local serial="$1"
  log "saving golden snapshot '$GOLDEN_SNAPSHOT' on $serial (clean post-install state)"
  if "$ADB_BIN" -s "$serial" emu avd snapshot save "$GOLDEN_SNAPSHOT" >/dev/null 2>&1; then
    if golden_snapshot_exists; then
      log "golden snapshot '$GOLDEN_SNAPSHOT' saved — future runs cold-boot from it"
      return 0
    fi
  fi
  log "WARNING: could not save golden snapshot '$GOLDEN_SNAPSHOT' — runs will cold-boot"
  return 1
}

# --- step 1: verify SDK basics -------------------------------------------------
[[ -x "$EMULATOR_BIN" ]] || { log "missing emulator at $EMULATOR_BIN"; exit 1; }
[[ -x "$ADB_BIN" ]] || { log "missing adb at $ADB_BIN"; exit 1; }
[[ -x "$AVDMANAGER_BIN" ]] || { log "missing avdmanager at $AVDMANAGER_BIN"; exit 1; }

# --- step 1b: emulator version ------------------------------------------------
# No version pin. The SceneView QA emulator used to be pinned to 35.6.11
# because Android Emulator 36.x regressed the *guest-side* `adb shell
# screenrecord` — with `-gpu host` (gfxstream) a Filament 3D scene composites
# host-side and the guest virtual display almost never receives frames, so a
# recording came back near-empty (verified: 36.5.11 -> 23 frames / 48 s;
# 35.6.11 -> 2541 frames).
#
# That pin is no longer needed: QA screen recording moved to the *host-side*
# `adb emu screenrecord` emulator-console path (see android_cli_screenrecord_*
# in lib/android-cli.sh, issue #1671). Host-side recording captures the
# emulator's presented framebuffer directly and is immune to the gfxstream
# composition-path change, so the QA emulator now runs whatever `sdkmanager`
# ships — latest is fine.

# --- step 2: verify system image is installed ---------------------------------
# (arm64 flow only — the Rosetta rig manages its own x86_64 image below.)
SYS_IMAGE_DIR="$SDK_ROOT/system-images/android-36/google_apis_playstore/$IMG_ARCH"
if ! $ROSETTA && [[ ! -d "$SYS_IMAGE_DIR" ]]; then
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

if $CLEAN && $CHECK_ONLY; then
  log "--check and --clean are mutually exclusive"; exit 2
fi

# arm64-AVD create/clean/stale-rebuild — SKIPPED entirely in --rosetta mode:
# the rig must never touch the arm64 AVD or its golden snapshot (#2758).
if ! $ROSETTA; then
  if $CLEAN; then
    log "removing existing AVD $AVD_NAME"
    "$AVDMANAGER_BIN" delete avd --name "$AVD_NAME" 2>/dev/null || true
    # `avdmanager delete` removes the .avd directory and its snapshots with it,
    # but be explicit so a stale golden snapshot can never survive a --clean
    # (#1672) — re-seed it afterwards with --seed-snapshot.
    rm -rf "$SNAPSHOTS_DIR" 2>/dev/null || true
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
  # Headroom so a Filament render + the Maestro QA drive don't starve
  # system_server (screen recording is host-side now — #1671 — and off-budget).
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

# Report golden-snapshot state (#1672) — used by --check and before booting.
show_snapshot_status() {
  if golden_snapshot_exists; then
    log "golden snapshot '$GOLDEN_SNAPSHOT' present — runs cold-boot from it (-no-snapshot-save)"
  else
    log "no golden snapshot '$GOLDEN_SNAPSHOT' — runs cold-boot; seed one with --seed-snapshot"
  fi
}

# Camera-id topology of a RUNNING emulator (#2754). ARCore's device APK hard-requires
# the back camera at id "0"; arm64 AVDs enumerate back="10"/front="1" (never "0"), and
# ARCore ships no arm64 emulator build (`_x86_for_emulator` has no arm64 libs) — so
# live-camera AR sessions can NEVER start on an arm64 AVD. Surfacing the topology here
# keeps that failure loud instead of masked behind a black viewport.
show_camera_topology() {
  # Optional $1: probe THIS serial (used by the Rosetta rig, which lives on a
  # reserved port). Default: the first running emulator, as before.
  local serial="${1:-}"
  if [[ -z "$serial" ]]; then
    # Pool members only — never the reserved rig serial (see EMU_POOL_PORT_EXCLUDE_FROM).
    serial="$(emu_running_serials "$ADB_BIN" | head -n1)"
  fi
  if [[ -z "$serial" ]]; then
    log "camera topology: no running emulator to probe"
    return 0
  fi
  local dump ids
  dump="$(bounded_adb 30 "$serial" shell dumpsys media.camera 2>/dev/null)" || true
  ids="$(printf '%s\n' "$dump" | grep -oE 'Device [0-9]+ maps to "[0-9]+"' | grep -oE '"[0-9]+"' | tr -d '"' | tr '\n' ' ' || true)"
  if [[ -z "$ids" ]]; then
    log "camera topology: probe failed on $serial (dumpsys media.camera empty)"
    return 0
  fi
  log "camera topology on $serial: HAL ids = ${ids% }"
  if printf '%s' " $ids " | grep -q ' 0 '; then
    log "  back camera at id 0 present — ARCore live-camera sessions can start"
  else
    log "  ⚠ NO camera id 0 — ARCore live-camera AR CANNOT start on this AVD (#2754)."
    log "    arm64 AVDs never expose id 0 and ARCore has no arm64 emulator build."
    log "    Camera-frame demos need the qa_mode fallback (PR #2753 pattern);"
    log "    real camera AR needs an x86_64-under-Rosetta AVD or a physical device."
  fi
}

# =============================================================================
# --- Rosetta x86_64 AR rig (#2758) -------------------------------------------
# =============================================================================
# ARCore ships NO arm64 emulator build (#2754): the device APK hard-requires the
# back camera at HAL id "0" (arm64 AVDs enumerate back="10"), and the
# `_x86_for_emulator` APK carries x86/x86_64 native libs only. The ONLY path to
# a live-camera ARCore session on an Apple Silicon host is therefore an x86_64
# guest — which HVF cannot accelerate (it virtualizes same-arch only), so the
# guest runs under QEMU's pure-software TCG, with the Intel darwin_x64 emulator
# binary itself translated by Rosetta 2.
#
# Hard-won facts from the 2026-05 investigations (memory
# feedback_arcore_emulator_mac_dead_end) baked in here:
#   - The arm64 SDK emulator bundle ships NO qemu-system-x86_64 — a separate
#     Intel (darwin_x64) bundle is required. Its binary runs fine via Rosetta.
#   - `-sysdir` must be passed explicitly or the Intel binary can't locate the
#     system image installed in the arm64 SDK tree.
#   - TCG boot is SLOW (double emulation). The 2026-05 attempts never reached
#     `sys.boot_completed=1` — but both ran on a nearly-full disk with a
#     concurrent arm64 emulator competing for CPU. This path re-tries under
#     controlled conditions, with an honest generous timeout and a loud failure
#     verdict instead of a silent hang. Expect 20-90 min for a first boot.
#   - The boot is detached as a SUBSHELL-ORPHAN `( nohup … & )` — a plain
#     `nohup … &` child gets SIGTERM'd by agent-tool cleanup on some runners.
#   - The rig lives on reserved port 5584, OUTSIDE the QA pool's allocation range
#     (the pool allocates upward from 5554), so a pool session can never lease a
#     ~10x-slower TCG guest for a standard QA run (see
#     EMU_POOL_PORT_EXCLUDE_FROM in lib/emulator-select.sh). 5584 is the LAST
#     console port inside adb's supported range: the emulator warns
#     "Requested adb port (N+1) is outside the recommended range [5555,5586].
#     ADB may not function properly" for anything higher, and the first rig
#     attempts on 5600 did see `adb shell` wedge mid-boot.

ROSETTA_AVD_NAME="Pixel_7a_x86"
ROSETTA_AVD_CONFIG="$AVD_HOME/$ROSETTA_AVD_NAME.avd/config.ini"
ROSETTA_PORT="${EMU_ROSETTA_PORT:-5584}"
ROSETTA_SERIAL="emulator-${ROSETTA_PORT}"
# google_apis (NOT google_apis_playstore): ARCore and the demo app are both
# side-loaded, so the Play Store would add nothing but background services that
# burn scarce TCG cycles. android-34 = mature x86_64 image; demo minSdk 28 OK.
ROSETTA_IMAGE="${EMU_ROSETTA_IMAGE:-system-images;android-34;google_apis;x86_64}"
ROSETTA_IMG_DIR="$SDK_ROOT/${ROSETTA_IMAGE//;//}"
# Intel darwin_x64 emulator bundle — 36.6.11, the stable-channel build in
# dl.google.com repository2-3.xml (channel-0) at the time of writing. Lives
# OUTSIDE the SDK tree so `sdkmanager` updates can never clobber it with an
# arm64 build.
ROSETTA_EMU_BUILD="${EMU_ROSETTA_EMULATOR_BUILD:-15507667}"
ROSETTA_EMU_ROOT="${EMU_ROSETTA_SDK_DIR:-$HOME/Library/Android/sdk-intel-emulator}"
ROSETTA_EMU_BIN="$ROSETTA_EMU_ROOT/emulator/emulator"
ROSETTA_BOOT_TIMEOUT_S="${EMU_ROSETTA_BOOT_TIMEOUT_S:-5400}"
ROSETTA_RAM_MB="${EMU_ROSETTA_MEMORY_MB:-3072}"
# GPU backend. `-gpu host` (gfxstream) marshals guest GL to the host GPU — but
# under TCG there is no working host-GL bridge, so the renderer crash-loops with
# "Failed to find ColorBuffer" and SurfaceFlinger never composites, so
# `sys.boot_completed` never fires (evidence: run 3 on this host, 2026-07-20 —
# OS booted, adb connected, bootanim crash-looped for 66 min past first stop).
# `swiftshader_indirect` renders GL in-guest with SwiftShader (pure software) and
# hands the host only a finished framebuffer — no host-GL bridge to fail. Slower,
# but the only mode with a chance of completing boot under double emulation.
ROSETTA_GPU="${EMU_ROSETTA_GPU:-swiftshader_indirect}"
# 4 GB data partition: the rig runs ONE side-loaded demo + ARCore (~500 MB of
# APKs) — it never absorbs the multi-run QA churn the arm64 AVD does, and every
# GB here is a GB of first-boot headroom on a chronically-full host.
ROSETTA_DATA_GB="${EMU_ROSETTA_DATA_GB:-4}"
# Pinned ARCore emulator APK — the GitHub release tag carries NO leading "v".
ROSETTA_ARCORE_APK_URL="${EMU_ROSETTA_ARCORE_APK_URL:-https://github.com/google-ar/arcore-android-sdk/releases/download/1.54.0/Google_Play_Services_for_AR_1.54.0_x86_for_emulator.apk}"
# How long `adb shell` may stay unresponsive before the guest is declared wedged.
# See rosetta_probe / rosetta_wait_boot.
ROSETTA_WEDGE_TIMEOUT_S="${EMU_ROSETTA_WEDGE_TIMEOUT_S:-420}"
# Cap for the ARCore `pm install`. The APK is ~86 MB and dex2oat runs IN the
# guest, so a native x86 emulator takes ~30-60s and this rig should be scaled by
# the 5-10x TCG slowdown its own docs quote. 900s is that estimate, NOT a
# measurement — no install has completed on this rig yet.
ROSETTA_INSTALL_CAP_S="${EMU_ROSETTA_INSTALL_CAP_S:-900}"
# Boot can take over an hour, and macOS clears /tmp on reboot — point this at a
# durable directory when the log has to survive a host restart to be useful.
ROSETTA_LOG="${EMU_ROSETTA_LOG:-/tmp/sceneview-emulator-${ROSETTA_AVD_NAME}-${ROSETTA_PORT}.log}"
ROSETTA_PIDFILE="/tmp/sceneview-emulator-${ROSETTA_AVD_NAME}-${ROSETTA_PORT}.pid"

rlog() { echo "[setup-ar:rosetta] $*"; }

# `adb shell` can block FOREVER against a guest whose framework is wedged, while
# `adb devices` still reports `device` — observed on this rig, where it froze the
# wait loop so far past its own timeout that only a host reboot ended the run.
# EVERY guest call therefore goes through bounded_adb: not just the boot poll,
# but the diagnostics too. Those are the ones an operator reaches for AFTER the
# guest wedges, so an unbounded `--check` would hang exactly when it is needed.
# Prefers coreutils `timeout`/`gtimeout`, falls back to the portable perl alarm
# idiom (alarm survives exec), and runs unbounded only if neither exists.
ROSETTA_TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

# bounded_adb <cap_seconds> <serial> <adb args…> — returns 124 when it hung.
bounded_adb() {
  local cap="$1" serial="$2"
  shift 2
  local rc=0
  if [[ -n "$ROSETTA_TIMEOUT_BIN" ]]; then
    "$ROSETTA_TIMEOUT_BIN" "$cap" "$ADB_BIN" -s "$serial" "$@" || rc=$?
  elif command -v perl >/dev/null 2>&1; then
    perl -e 'alarm shift; exec @ARGV' "$cap" "$ADB_BIN" -s "$serial" "$@" || rc=$?
  else
    "$ADB_BIN" -s "$serial" "$@" || rc=$?
  fi
  # 124 = coreutils timeout; 142 = killed by SIGALRM (the perl idiom).
  case "$rc" in
    124|142) return 124 ;;
  esac
  return "$rc"
}

# Same, aimed at the rig serial.
rosetta_adb() {
  local cap="$1"
  shift
  bounded_adb "$cap" "$ROSETTA_SERIAL" "$@"
}

# One bounded getprop. Echoes the value; returns 124 when the probe itself hung,
# which is never confusable with a legitimately empty property.
rosetta_probe() {
  local prop="$1" cap="${2:-10}" out rc=0
  out="$(rosetta_adb "$cap" shell getprop "$prop" 2>/dev/null)" || rc=$?
  (( rc == 124 )) && return 124
  echo "${out//[$'\r\n']/}"
}

# adb state of the rig serial: "device", "offline", or "" (not registered).
rosetta_serial_state() {
  "$ADB_BIN" devices 2>/dev/null | awk -v s="$ROSETTA_SERIAL" '$1==s {print $2}'
}

# 0 if the qemu pid recorded at boot time is still alive. The `emulator`
# launcher execs the engine, so the recorded pid stays valid. This is one
# signal among several — failure verdicts always cross-check the adb state.
rosetta_pid_alive() {
  local pid
  pid="$(cat "$ROSETTA_PIDFILE" 2>/dev/null || true)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

rosetta_check_prereqs() {
  if [[ "$(uname -s)" != "Darwin" || "$HOST_ARCH" != "arm64" ]]; then
    rlog "--rosetta targets Apple Silicon Macs only (host: $(uname -s)/$HOST_ARCH)."
    rlog "On an x86_64 host the STANDARD flow already boots an ARCore-capable x86_64 AVD."
    exit 2
  fi
  if ! /usr/bin/arch -x86_64 /usr/bin/true 2>/dev/null; then
    rlog "Rosetta 2 is not installed. Install it once with:"
    rlog "  softwareupdate --install-rosetta --agree-to-license"
    exit 1
  fi
}

# Disk gate (#2758): the payload is ~9 GB on a chronically-full host. Charge
# only what is actually missing; a fresh AVD additionally needs ~7.4 GB
# AVAILABLE at first boot (the emulator refuses below that — 2026-05 evidence).
rosetta_disk_gate() {
  local free_gb need_gb=0 missing=()
  # $AVD_HOME, not $HOME: ANDROID_AVD_HOME may point at another volume.
  free_gb="$(df -g "$AVD_HOME" 2>/dev/null | awk 'NR==2{print $4}')"
  [[ "$free_gb" =~ ^[0-9]+$ ]] || { rlog "WARNING: cannot read free disk — proceeding without the gate"; return 0; }
  if [[ ! -x "$ROSETTA_EMU_BIN" ]]; then need_gb=$((need_gb+2)); missing+=("Intel emulator bundle ~1.5G"); fi
  if [[ ! -d "$ROSETTA_IMG_DIR" ]]; then need_gb=$((need_gb+4)); missing+=("x86_64 system image ~3.5G"); fi
  # First-boot headroom only counts when this run will actually BOOT: the
  # emulator pre-allocates the data partition + working images and refuses to
  # start without the headroom AVAILABLE (2026-05: "Available: 4100 MB, need
  # 7372 MB"). `--no-boot` provisioning (downloads + AVD create) costs ~none
  # beyond the payloads charged above.
  if ! $NO_BOOT; then
    # `--clean` deletes the AVD later, inside rosetta_create_avd — so an existing
    # userdata image is about to disappear and must NOT discount this run, or the
    # gate passes on 5 GB and the emulator then refuses with "Not enough space to
    # create userdata partition" a minute later, AVD already destroyed.
    if $CLEAN || [[ ! -d "$AVD_HOME/$ROSETTA_AVD_NAME.avd/snapshots" && ! -f "$AVD_HOME/$ROSETTA_AVD_NAME.avd/userdata-qemu.img" ]]; then
      need_gb=$((need_gb + ROSETTA_DATA_GB + 3)); missing+=("fresh-AVD first boot (~$((ROSETTA_DATA_GB + 3))G must be available)")
    else
      need_gb=$((need_gb+2))
    fi
  fi
  if (( free_gb < need_gb )); then
    rlog "DISK GATE: ${free_gb} GB free < ${need_gb} GB needed (${missing[*]:-boot headroom})"
    rlog "Free disk first — safe purges (memory ar-model-viewer disk-cleanup-2026-07):"
    rlog "  bash .claude/scripts/worktree-auto-prune.sh --yes    # merged worktrees"
    rlog "  stale /tmp/sv-* review clones of already-MERGED PRs"
    rlog "  xcrun simctl runtime delete <never-booted runtimes>"
    exit 1
  fi
  rlog "disk gate OK: ${free_gb} GB free >= ${need_gb} GB needed"
}

rosetta_install_emulator() {
  if [[ -x "$ROSETTA_EMU_BIN" ]]; then
    rlog "Intel emulator bundle present (build $(cat "$ROSETTA_EMU_ROOT/.sceneview-build" 2>/dev/null || echo '?')) at $ROSETTA_EMU_ROOT"
    return 0
  fi
  local zip_url="https://dl.google.com/android/repository/emulator-darwin_x64-${ROSETTA_EMU_BUILD}.zip"
  local tmp_zip="${TMPDIR:-/tmp}/emulator-darwin_x64-${ROSETTA_EMU_BUILD}.zip"
  rlog "downloading Intel emulator bundle: $zip_url"
  curl -fSL --retry 3 -o "$tmp_zip" "$zip_url" || { rlog "download failed"; exit 1; }
  mkdir -p "$ROSETTA_EMU_ROOT"
  rlog "extracting to $ROSETTA_EMU_ROOT"
  unzip -qo "$tmp_zip" -d "$ROSETTA_EMU_ROOT" || { rm -f "$tmp_zip"; rlog "unzip failed"; exit 1; }
  rm -f "$tmp_zip"
  echo "$ROSETTA_EMU_BUILD" > "$ROSETTA_EMU_ROOT/.sceneview-build"
  [[ -x "$ROSETTA_EMU_BIN" ]] || { rlog "bundle extracted but $ROSETTA_EMU_BIN is missing"; exit 1; }
  rlog "Intel emulator installed: $(/usr/bin/arch -x86_64 "$ROSETTA_EMU_BIN" -version 2>/dev/null | head -1 || echo 'version probe failed (non-fatal)')"
}

rosetta_install_image() {
  if [[ -d "$ROSETTA_IMG_DIR" ]]; then
    rlog "x86_64 system image present: $ROSETTA_IMAGE"
    return 0
  fi
  rlog "installing system image: $ROSETTA_IMAGE"
  if [[ -x "$ANDROID_CLI_BIN" ]] && "$ANDROID_CLI_BIN" "${ANDROID_CLI[@]}" sdk install "$ROSETTA_IMAGE"; then
    rlog "system image installed via android CLI"
  else
    rlog "android CLI unavailable/failed — falling back to sdkmanager"
    yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "$ROSETTA_IMAGE" || {
      rlog "sdkmanager failed; check ANDROID_SDK_ROOT and licenses"; exit 1
    }
  fi
  [[ -d "$ROSETTA_IMG_DIR" ]] || { rlog "install reported OK but $ROSETTA_IMG_DIR is missing"; exit 1; }
}

rosetta_create_avd() {
  if $CLEAN; then
    rlog "removing rig AVD $ROSETTA_AVD_NAME (--clean — the arm64 AVD is untouched)"
    "$AVDMANAGER_BIN" delete avd --name "$ROSETTA_AVD_NAME" 2>/dev/null || true
    rm -rf "$AVD_HOME/$ROSETTA_AVD_NAME.avd" "$AVD_HOME/$ROSETTA_AVD_NAME.ini" 2>/dev/null || true
  fi
  if [[ ! -f "$ROSETTA_AVD_CONFIG" ]]; then
    rlog "creating rig AVD $ROSETTA_AVD_NAME (device=pixel_7a, image=$ROSETTA_IMAGE)"
    echo "no" | "$AVDMANAGER_BIN" create avd \
      --name "$ROSETTA_AVD_NAME" \
      --package "$ROSETTA_IMAGE" \
      --device pixel_7a \
      --force >/dev/null
  fi
  rlog "patching $ROSETTA_AVD_CONFIG for the AR rig"
  patch_kv "PlayStore.enabled" "no"             "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.camera.back"    "virtualscene"   "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.camera.front"   "emulated"       "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.gpu.enabled"    "yes"            "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.gpu.mode"       "$ROSETTA_GPU"   "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.ramSize"        "$ROSETTA_RAM_MB" "$ROSETTA_AVD_CONFIG"
  patch_kv "vm.heapSize"       "512"            "$ROSETTA_AVD_CONFIG"
  # 4 vCPUs: one MTTCG translation thread each — leaves host cores for the
  # arm64 pool emulator + the agent. More vCPUs does NOT mean faster under TCG.
  patch_kv "hw.cpu.ncore"      "4"              "$ROSETTA_AVD_CONFIG"
  patch_kv "disk.dataPartition.size" "${ROSETTA_DATA_GB}G" "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.sensors.orientation" "yes"       "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.sensors.proximity"   "yes"       "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.gps"            "yes"            "$ROSETTA_AVD_CONFIG"
  patch_kv "hw.keyboard"       "yes"            "$ROSETTA_AVD_CONFIG"
}

# The pool-invisibility guarantee is the whole reason a standard QA run can never
# lease this ~10x-slower guest. It holds only while the rig's port sits at or above
# the pool's exclusion threshold — two independent knobs that must move together.
# Checked on EVERY rosetta path (including --check and --no-boot, which name the
# rig serial too), not just before a boot.
rosetta_assert_port_reserved() {
  if (( ROSETTA_PORT < EMU_POOL_PORT_EXCLUDE_FROM )); then
    rlog "FAIL: EMU_ROSETTA_PORT=$ROSETTA_PORT is below EMU_POOL_PORT_EXCLUDE_FROM=$EMU_POOL_PORT_EXCLUDE_FROM"
    rlog "the rig would become leasable by a standard QA run — raise the port, or lower the threshold"
    exit 1
  fi
}

rosetta_boot() {
  local state; state="$(rosetta_serial_state)"
  if [[ "$state" == "device" ]]; then
    rlog "rig already running on $ROSETTA_SERIAL — reusing it"
    return 0
  fi
  if rosetta_pid_alive; then
    rlog "rig is mid-boot (adb: ${state:-not-registered}) — waiting for it below"
    return 0
  fi
  if [[ -n "$state" ]]; then
    # A serial with no live qemu behind it is a stale adb transport, not a boot
    # in progress. Treating it as "mid-boot" returned without starting anything
    # and cost a full boot timeout on a verdict that named the wrong cause.
    # `adb disconnect` only applies to TCP/IP devices; an emulator serial comes from
    # adb's console-port scanner, so there is nothing to disconnect. Say what is
    # actually done — boot anyway — rather than claiming a repair that never runs.
    rlog "stale adb transport for $ROSETTA_SERIAL (state: $state) with no live qemu — ignoring it and booting"
    "$ADB_BIN" -s "$ROSETTA_SERIAL" reconnect >/dev/null 2>&1 || true
  fi
  # RAM gate: reuse the pool's hard safety threshold. The rig is outside the
  # pool but the OOM cliff is host-wide.
  if ! emu_ram_allows_boot; then
    rlog "not enough free RAM to boot the rig (threshold ${EMU_MIN_FREE_RAM_MB} MB)"
    rlog "free RAM (or stop an idle pool emulator) first — pool state: --check"
    exit 1
  fi
  local window_arg="-no-window" window_desc="headless"
  if [[ "${EMU_VISIBLE:-0}" == "1" || "${EMU_VISIBLE:-}" == "true" ]]; then
    window_arg=""; window_desc="windowed (EMU_VISIBLE)"
  fi
  rlog "booting rig $ROSETTA_AVD_NAME on -port $ROSETTA_PORT (${window_desc}, TCG -no-accel, -gpu ${ROSETTA_GPU}, -memory ${ROSETTA_RAM_MB} MB)"
  rlog "log: $ROSETTA_LOG — expect a LONG first boot (20-90 min under TCG)"
  # Subshell-orphan detach (2026-05 lesson): a direct `nohup … &` child gets
  # SIGTERM'd by agent-tool cleanup; the subshell orphan survives. The pid is
  # captured from inside the subshell.
  # shellcheck disable=SC2086 # window_arg is intentionally word-split (empty = visible)
  ( ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_AVD_HOME="$AVD_HOME" \
      nohup /usr/bin/arch -x86_64 "$ROSETTA_EMU_BIN" \
        -avd "$ROSETTA_AVD_NAME" \
        -port "$ROSETTA_PORT" \
        -sysdir "$ROSETTA_IMG_DIR" \
        -no-accel \
        -gpu "$ROSETTA_GPU" \
        -memory "$ROSETTA_RAM_MB" \
        -no-snapshot \
        $window_arg \
        -no-audio \
        -no-boot-anim \
        -netdelay none -netspeed full \
        >"$ROSETTA_LOG" 2>&1 &
    echo $! > "$ROSETTA_PIDFILE" )
  rlog "rig qemu pid=$(cat "$ROSETTA_PIDFILE" 2>/dev/null || echo '?') serial=$ROSETTA_SERIAL"
}

rosetta_wait_boot() {
  # Elapsed time comes from SECONDS, never from counting `sleep` intervals: a hung
  # probe costs its own cap ON TOP of the interval, so an interval-counter drifts
  # ~40% behind reality and every verdict would quote a number that is not the
  # elapsed time — in a code path whose entire purpose is an honest verdict.
  local start=$SECONDS waited=0 interval=15 state boot am ss rss rc
  local probe_cap=10 hung_since=-1 hung=0 dead_seen=0 last_report=0
  rlog "waiting for $ROSETTA_SERIAL: sys.boot_completed=1, or a registered ActivityManager"
  rlog "  (timeout ${ROSETTA_BOOT_TIMEOUT_S}s)"
  while true; do
    rc=0
    boot="$(rosetta_probe sys.boot_completed "$probe_cap")" || rc=$?
    waited=$(( SECONDS - start ))
    if (( rc == 124 )); then
      # Stamp the START of the hang, not its detection: the probe had already
      # burned its full cap by the time it returned, so anchoring at `waited`
      # under-reports the unresponsive window by one cap every time.
      if (( hung_since < 0 )); then hung_since=$(( waited - probe_cap )); fi
      hung=$(( waited - hung_since ))
      boot="<probe hung>"
    else
      hung_since=-1
      hung=0
    fi
    if [[ "$boot" == "1" ]]; then
      rlog "BOOT COMPLETE on $ROSETTA_SERIAL after ${waited}s"
      return 0
    fi
    state="$(rosetta_serial_state)"
    # A guest that answers nothing for minutes on end is wedged, not slow: adbd
    # is still connected (adb devices says `device`) but every shell blocks. The
    # adb state is read BEFORE the verdict so the message reports what was
    # actually observed rather than asserting it.
    if (( hung >= ROSETTA_WEDGE_TIMEOUT_S )); then
      rlog "FAIL: guest wedged — 'adb shell' unresponsive for ${hung}s (adb state: ${state:-none})"
      rlog "'adb reboot' does NOT resurrect a wedged qemu HAL — kill qemu and re-run:"
      rlog "  kill \$(cat $ROSETTA_PIDFILE) && bash .claude/scripts/setup-ar-emulator.sh --rosetta"
      tail -n 20 "$ROSETTA_LOG" 2>/dev/null | sed 's/^/  | /' || true
      return 1
    fi
    # A dead qemu is decisive on the second consecutive sample. Requiring the
    # serial to also vanish from `adb devices` was wrong: adb routinely leaves a
    # dead emulator's transport `offline`, so the death went unnoticed and the
    # loop burned the full 90-minute timeout before blaming "TCG too slow".
    # A pidfile that names no live process is only decisive when the guest is not
    # answering either. adb still saying `device` (or a probe that just answered)
    # means the rig is alive and the pidfile was lost — declaring "qemu died" then
    # would be self-contradictory AND would orphan a running emulator.
    if ! rosetta_pid_alive && [[ "$state" != "device" ]] && (( rc == 124 || rc == 0 )); then
      if (( dead_seen )); then
        rlog "FAIL: rig qemu died after ${waited}s (adb state: ${state:-gone}) — last log lines:"
        tail -n 30 "$ROSETTA_LOG" 2>/dev/null | sed 's/^/  | /' || true
        return 1
      fi
      dead_seen=1
    else
      dead_seen=0
    fi
    if (( waited >= ROSETTA_BOOT_TIMEOUT_S )); then
      rlog "FAIL: sys.boot_completed never reached 1 within ${ROSETTA_BOOT_TIMEOUT_S}s (TCG too slow or wedged)"
      rlog "qemu is LEFT RUNNING — re-run '--rosetta' to keep waiting, or kill it:"
      rlog "  kill \$(cat $ROSETTA_PIDFILE)"
      tail -n 15 "$ROSETTA_LOG" 2>/dev/null | sed 's/^/  | /' || true
      return 1
    fi
    if (( waited - last_report >= 120 )); then
      last_report=$waited
      # Deliberately NOT `init.svc.bootanim`: rosetta_boot passes `-no-boot-anim`,
      # which sets debug.sf.nobootanimation=1, so the animation never runs and the
      # property reads `stopped` for the whole boot. Printing it as progress is
      # self-contradicting — this harness disables the thing it then reports on —
      # and reading `bootanim: stopped` as "booted to ~90%" is exactly what sent
      # the 2026-05 investigation to a dead-end verdict. system_server's pid and
      # the ActivityManager registration are signals that actually move.
      ss="$(rosetta_adb 20 shell 'pidof system_server || echo -' 2>/dev/null | tr -d '\r\n')" || ss='<hung>'
      am="$(rosetta_adb 30 shell 'service check activity' 2>/dev/null | tr -d '\r\n')" || am='<hung>'
      rss="$(ps -o rss= -p "$(cat "$ROSETTA_PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null | awk '{printf "%d MB", $1/1024}' || true)"
      # Falling RSS on a still-"booting" guest means the host is swapping it out —
      # the documented signal to stop and retry on a quiet host (CLAUDE.md).
      rlog "still booting… ${waited}s (adb: ${state:-none}, system_server: ${ss:-?}, ${am:-?}, qemu rss: ${rss:-?})"
      # `sys.boot_completed` can stay unset long after the guest is usable — measured
      # on the rig: ActivityManager registered at ~42 min with the property still
      # empty. A registered ActivityManager is what the caller actually needs (it is
      # what makes `pm install` and `am start` work), so accept it as success too and
      # say which signal fired. Match "not found" FIRST — it contains "found".
      case "$am" in
        *"not found"*) : ;;
        *found*)
          rlog "SYSTEM USABLE on $ROSETTA_SERIAL after ${waited}s (ActivityManager registered; sys.boot_completed=${boot:-unset})"
          return 0 ;;
      esac
    fi
    sleep "$interval"
  done
}

rosetta_install_arcore() {
  if rosetta_adb 20 shell pm list packages 2>/dev/null | grep -q "com.google.ar.core"; then
    rlog "ARCore already installed on $ROSETTA_SERIAL"
    return 0
  fi
  # Post-boot package-manager settling (same race as the arm64 flow) — inline
  # poll because the shared wait_for_pm helper is defined later in this script.
  local _i
  for _i in $(seq 1 30); do
    rosetta_adb 20 shell pm path android >/dev/null 2>&1 && break
    sleep 4
  done
  local tmp_apk="${TMPDIR:-/tmp}/sceneview-arcore-x86.apk"
  if [[ ! -s "$tmp_apk" ]]; then
    rlog "downloading ARCore x86 emulator APK: $ROSETTA_ARCORE_APK_URL"
    curl -fSL --retry 3 -o "$tmp_apk" "$ROSETTA_ARCORE_APK_URL" || { rlog "ARCore APK download failed"; return 1; }
  fi
  local attempt err
  for attempt in 1 2 3; do
    err="$(rosetta_adb "$ROSETTA_INSTALL_CAP_S" install -r "$tmp_apk" 2>&1)" && { rlog "ARCore installed on $ROSETTA_SERIAL"; return 0; }
    rlog "ARCore install attempt $attempt/3 failed${err:+: ${err##*$'\n'}} — retrying in 5s"
    sleep 5
  done
  rlog "ARCore install failed after 3 attempts"
  return 1
}

# Read-only rig report (`--check --rosetta`).
rosetta_report() {
  rlog "=== Rosetta x86_64 AR rig report (#2758) ==="
  if [[ -x "$ROSETTA_EMU_BIN" ]]; then
    rlog "Intel emulator bundle: present (build $(cat "$ROSETTA_EMU_ROOT/.sceneview-build" 2>/dev/null || echo '?')) at $ROSETTA_EMU_ROOT"
  else
    rlog "Intel emulator bundle: MISSING (a full run downloads build $ROSETTA_EMU_BUILD)"
  fi
  if [[ -d "$ROSETTA_IMG_DIR" ]]; then
    rlog "x86_64 system image: present ($ROSETTA_IMAGE)"
  else
    rlog "x86_64 system image: MISSING ($ROSETTA_IMAGE)"
  fi
  if [[ -f "$ROSETTA_AVD_CONFIG" ]]; then
    rlog "rig AVD $ROSETTA_AVD_NAME: present"
    grep -E "^(abi\.type|hw\.camera|hw\.gpu\.mode|hw\.ramSize|hw\.cpu\.ncore|disk\.dataPartition\.size)" "$ROSETTA_AVD_CONFIG" 2>/dev/null | sed 's/^/  /' || true
  else
    rlog "rig AVD $ROSETTA_AVD_NAME: MISSING (a full --rosetta run creates it)"
  fi
  local state; state="$(rosetta_serial_state)"
  if [[ "$state" == "device" ]]; then
    rlog "rig emulator: RUNNING on $ROSETTA_SERIAL"
    if rosetta_adb 20 shell pm list packages 2>/dev/null | grep -q "com.google.ar.core"; then
      rlog "ARCore (com.google.ar.core): installed"
    else
      rlog "ARCore (com.google.ar.core): NOT installed"
    fi
    show_camera_topology "$ROSETTA_SERIAL"
  elif [[ -n "$state" ]]; then
    rlog "rig emulator: adb state '$state' (mid-boot or wedged — log: $ROSETTA_LOG)"
  elif rosetta_pid_alive; then
    rlog "rig emulator: qemu alive (pid $(cat "$ROSETTA_PIDFILE" 2>/dev/null)) but not yet registered with adb (early TCG boot)"
  else
    rlog "rig emulator: not running"
  fi
}

# One-liner rig visibility for the STANDARD --check (full report: --check --rosetta).
rosetta_status_line() {
  if [[ "$(rosetta_serial_state)" == "device" ]]; then
    log "rosetta rig: RUNNING on $ROSETTA_SERIAL (full report: --check --rosetta)"
  elif [[ -f "$ROSETTA_AVD_CONFIG" ]]; then
    log "rosetta rig: provisioned, not running (boot: --rosetta ; report: --check --rosetta)"
  else
    log "rosetta rig: not provisioned (live-camera AR on Apple Silicon: --rosetta, #2758)"
  fi
}

rosetta_main() {
  rosetta_assert_port_reserved
  if $CHECK_ONLY; then
    rosetta_report
    exit 0
  fi
  rosetta_check_prereqs
  rosetta_disk_gate
  rosetta_install_emulator
  rosetta_install_image
  rosetta_create_avd
  if $NO_BOOT; then
    rlog "rig provisioned (--no-boot). Boot it later with: bash $0 --rosetta"
    exit 0
  fi
  rosetta_boot
  if ! rosetta_wait_boot; then
    rlog "RIG BOOT FAILED — see above. Before concluding the 2026-05 dead-end stands,"
    rlog "check host contention (CPU/RAM/disk) and document the evidence on #2758."
    exit 1
  fi
  if ! rosetta_install_arcore; then
    rlog "rig booted but the ARCore install failed — fix and re-run (the boot is kept warm)"
    exit 1
  fi
  show_camera_topology "$ROSETTA_SERIAL"
  # Verdict: camera HAL id 0 is the whole point of the rig (#2754 probe, PR #2755).
  local ids dump probe_rc=0
  dump="$(rosetta_adb 30 shell dumpsys media.camera 2>/dev/null)" || probe_rc=$?
  ids="$(printf '%s' "$dump" | grep -oE 'Device [0-9]+ maps to "[0-9]+"' | grep -oE '"[0-9]+"' | tr -d '"' | tr '\n' ' ' || true)"
  if $STOP_AFTER; then
    rlog "stopping rig $ROSETTA_SERIAL (--stop)"
    rosetta_adb 20 emu kill >/dev/null 2>&1 || true
  fi
  if printf '%s' " $ids " | grep -q ' 0 '; then
    rlog "SUCCESS: camera HAL id 0 present — ARCore live-camera sessions can start."
    rlog "Complete the proof by running a live AR demo (NO qa_mode) and checking logcat:"
    rlog "  adb -s $ROSETTA_SERIAL logcat -d | grep -Ei 'arcore|unknown device'"
    rlog "  (expect: no 'unknown device 0', ARCore session created, tracking TRACKING)"
    echo "EMU_SERIAL=${ROSETTA_SERIAL}"
    exit 0
  fi
  # An empty probe is NOT a measurement. Reporting it as "no camera id 0" would
  # silently confirm #2754 on no evidence — the same inversion the --check path
  # was blocked on. Say inconclusive and exit distinctly.
  if (( probe_rc != 0 )) || [[ -z "$dump" ]]; then
    rlog "INCONCLUSIVE: the camera probe did not answer (dumpsys hung or empty) — verdict NOT established."
    rlog "  re-run '--check --rosetta' once the guest settles."
    exit 2
  fi
  rlog "FAIL: no camera HAL id 0 on the rig (HAL ids: ${ids:-none}) — ARCore cannot start (#2754)."
  exit 1
}

if $ROSETTA; then
  rosetta_main
  exit 0   # unreachable — rosetta_main always exits — belt-and-braces
fi

if $CHECK_ONLY; then
  show_config
  show_snapshot_status
  show_ram_and_emulator_status
  show_camera_topology
  rosetta_status_line
else
  apply_ar_config
  show_config
  show_snapshot_status
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

  # Snapshot policy (#1672):
  #   - The base-port emulator (5554) RESTORES the golden `qa-clean` snapshot
  #     when one exists and --no-snapshot was not passed: `-snapshot qa-clean
  #     -no-snapshot-save` loads the warm post-install state but never writes
  #     back, so every run starts identical and the userdata never degrades.
  #     `-snapshot` is incompatible with `-read-only`, so the snapshot-loading
  #     emulator drops `-read-only` (it is the sole writer of its own RAM image;
  #     `-no-snapshot-save` still prevents disk mutation).
  #   - Pool peers (port != 5554) always cold-boot `-read-only -no-snapshot`:
  #     `-read-only` emulators share one AVD and cannot safely own a snapshot.
  #   - With --seed-snapshot the base emulator boots WRITABLE (no -read-only)
  #     and WITHOUT loading a stale snapshot (`-no-snapshot-load`), so the
  #     subsequent `emu avd snapshot save` can persist the fresh golden image
  #     to disk. `-no-snapshot-save` is omitted here on purpose — the explicit
  #     console save IS the write we want.
  local snapshot_args=("-read-only" "-no-snapshot")
  local boot_desc="cold boot, -read-only"
  if [[ "$port" == "$EMU_BASE_PORT" ]] && $SEED_SNAPSHOT; then
    # Seed run: writable, no auto-load of an old snapshot, manual save later.
    snapshot_args=("-no-snapshot-load")
    boot_desc="seed boot (writable, -no-snapshot-load)"
  elif [[ "$port" == "$EMU_BASE_PORT" ]] && ! $NO_SNAPSHOT && golden_snapshot_exists; then
    # Restore the golden snapshot; no -read-only (incompatible with -snapshot).
    snapshot_args=("-snapshot" "$GOLDEN_SNAPSHOT" "-no-snapshot-save")
    boot_desc="restore '$GOLDEN_SNAPSHOT' snapshot, -no-snapshot-save"
  fi

  log "booting a new pool emulator $AVD_NAME on -port ${port} (${window_desc}, ${boot_desc}, -memory ${mem} MB)"
  # `-port` gives each emulator a distinct serial; nohup + & so the emulator
  # survives this script; per-port log for debugging.
  local emu_log="/tmp/sceneview-emulator-${AVD_NAME}-${port}.log"
  # shellcheck disable=SC2086 # window_arg is intentionally word-split (empty = visible)
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" \
    -port "$port" \
    "${snapshot_args[@]}" \
    -gpu host \
    -memory "$mem" \
    $window_arg \
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
      # A failed allocation must not degrade into `-port ""` (which boots a junk
      # serial and leaves an `emulator-.lease` in the shared registry). errexit is
      # suppressed here — this function runs inside an `if` — so guard explicitly.
      local port
      if ! port="$(emu_next_free_port "$ADB_BIN")" || [[ -z "$port" ]]; then
        log "no free console port below the reserved range — not booting"
        return 1
      fi
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

if ! $CHECK_ONLY && ! $NO_BOOT && $SEED_SNAPSHOT; then
  # --seed-snapshot (#1672) bypasses the pool lease logic: it must boot a FRESH,
  # WRITABLE base-port emulator (a leased running one or a -read-only peer could
  # not be snapshotted). The base port 5554 must be free for this.
  if emu_serial_alive "emulator-$EMU_BASE_PORT" "$ADB_BIN"; then
    log "--seed-snapshot needs the base port $EMU_BASE_PORT free, but emulator-$EMU_BASE_PORT is running"
    log "  stop it first: android emulator stop $AVD_NAME  (or: adb -s emulator-$EMU_BASE_PORT emu kill)"
    exit 1
  fi
  if ! emu_ram_allows_boot; then
    log "--seed-snapshot: not enough free RAM to boot the seed emulator"
    exit 1
  fi
  boot_new_emulator "$EMU_BASE_PORT"
  EMU_SERIAL="emulator-$EMU_BASE_PORT"
  emu_lease_acquire "$EMU_SERIAL" || true
  wait_for_boot || { log "--seed-snapshot: seed emulator failed to boot"; exit 1; }
elif ! $CHECK_ONLY && ! $NO_BOOT; then
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
# emu_running_serials (NOT a raw `adb devices`) so the reserved rig serial is
# filtered out: it is not a pool member, and reporting on it here would print an
# arm64 verdict about an x86 guest — inverting the #2754 finding this very probe
# exists to keep loud.
serial="${EMU_SERIAL:-$(emu_running_serials "$ADB_BIN" | head -n1)}"
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
  # Honest arm64 limitation notice (#2754): the device APK installed above runs, but
  # it requires the back camera at HAL id "0", which arm64 AVDs never expose — and
  # ARCore ships no arm64 emulator build. Say it loudly here instead of letting AR
  # demos fail later with a silent black viewport and no tracking.
  if [[ "$IMG_ARCH" == "arm64-v8a" ]]; then
    log "⚠ arm64 AVD: live-camera AR sessions CANNOT start (ARCore has no arm64"
    log "  emulator build — #2754). 3D demos, UI/state QA and dataset-replay QA are"
    log "  fine; camera-frame demos rely on their qa_mode fallback (PR #2753 pattern)."
    log "  Real camera AR: x86_64-under-Rosetta AVD or a physical device."
  fi
else
  log "(no emulator running — skipped ARCore check)"
fi

# --- step 6b: seed the golden snapshot (#1672) --------------------------------
# With --seed-snapshot the emulator was cold-booted and ARCore installed above;
# now persist that clean state as the golden `qa-clean` snapshot and exit. Every
# subsequent run cold-boots from this snapshot with `-no-snapshot-save`, fixing
# the storage-degradation bug (userdata never accumulates run-to-run cruft) and
# slashing boot time (snapshot restore is seconds vs. a 60-90s cold boot).
if $SEED_SNAPSHOT; then
  # Flag conflicts (--check / --no-boot) were already rejected up front.
  if [[ -z "${serial:-}" ]]; then
    log "--seed-snapshot: no emulator to snapshot — boot failed earlier"
    exit 1
  fi
  if save_golden_snapshot "$serial"; then
    log "golden snapshot seeded. Stopping the emulator so the snapshot is consistent."
    "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
    log "done — future 'setup-ar-emulator.sh' runs will restore '$GOLDEN_SNAPSHOT'."
    exit 0
  else
    log "--seed-snapshot failed — emulator left running, fix the issue and retry."
    exit 1
  fi
fi

# --- step 7: optionally stop the emulator -------------------------------------
# By default we leave the emulator warm so a QA script can run immediately after.
# --stop kills it for hygiene-conscious callers (CI, one-shot config runs).
#
# ⚠️ `--stop` means "run the whole flow, THEN stop" — it is not a kill switch.
# Passing it against an already-running rig re-enters the boot wait first and can
# sit there for the full ROSETTA_BOOT_TIMEOUT_S before anything is stopped (hit
# while closing out #2758). To just kill a running rig:
#     kill "$(cat "${TMPDIR:-/tmp}/sceneview-rosetta-rig.pid")"
# or `kill $(pgrep -f "qemu-system-x86_64.*Pixel_7a_x86")` if the pidfile is gone.
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
if ! $CHECK_ONLY && ! golden_snapshot_exists; then
  log "tip: seed a warm QA boot snapshot once with:"
  log "  bash .claude/scripts/setup-ar-emulator.sh --clean --seed-snapshot"
  log "  -> future runs cold-boot from '$GOLDEN_SNAPSHOT' (faster, deterministic, #1672)"
fi
