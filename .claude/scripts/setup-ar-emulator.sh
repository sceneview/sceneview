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
#   --release        Hand this session's pool RESERVATION back and exit, leaving
#                    the emulator running (#2862). Provisioning reserves the
#                    emulator for the session that will drive it — this is how
#                    you free it for a peer without killing the guest. Refuses
#                    to release another session's reservation unless you pass
#                    its EMU_LEASE_SESSION (or EMU_LEASE_TAKEOVER=1).
#   --window         Boot the emulator VISIBLE (windowed) so a developer can
#                    glance at it. OPT-IN, local only. Default is headless
#                    (-no-window), which is marginally lighter on the host (skips
#                    the skin-window draw + window-server compositing).
#                    Equivalent to EMU_VISIBLE=1. The guest VM cost is identical
#                    either way — only the host window draw differs. CI never
#                    sets this; the device-QA workflow stays headless.
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
# The pool AVD name lives in lib/emulator-select.sh (EMU_POOL_AVD) so this
# script and every QA consumer name the same device from one place.
AVD_NAME="$EMU_POOL_AVD"
# Pool identity guards (#2862). EMU_REQUIRE_AVD makes the lease helpers refuse
# any emulator on a pool port that is NOT this AVD — a stray hand-made AVD on
# 5554 must never be leased and driven as if it were the ARCore-ready Pixel_7a.
# EMU_LEASE_SESSION identifies the session that will USE the emulator after
# this script exits; a fresh token is minted when the caller has none, so two
# parallel provisioning runs can never resolve to the same owner.
#
# The AVD guard is LOCAL-POOL only: on CI the emulator is provided by
# ReactiveCircus/android-emulator-runner under the AVD name `test`, and
# refusing it would leave the runner with no leasable emulator at all. A CI
# runner is single-session, which is precisely the collision this prevents.
if [[ -z "${EMU_REQUIRE_AVD:-}" && "${GITHUB_ACTIONS:-}" != "true" ]]; then
  EMU_REQUIRE_AVD="$AVD_NAME"
fi
# Track whether WE minted the token (the caller passed none). Only a minted
# token is published to the handoff file at the end (#2862): a caller that
# exported its own token — device-qa.sh — already holds the reservation via that
# shared export and needs no handoff, and re-publishing it would widen the
# documented handoff-window leak (a concurrent session inheriting the token and
# taking the emulator) to that caller too.
EMU_LEASE_SESSION_MINTED=false
if [[ -z "${EMU_LEASE_SESSION:-}" ]]; then
  EMU_LEASE_SESSION="$(emu_lease_session_new)"
  EMU_LEASE_SESSION_MINTED=true
fi
export EMU_REQUIRE_AVD EMU_LEASE_SESSION
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
RELEASE_LEASE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)          CHECK_ONLY=true; shift ;;
    --clean)          CLEAN=true; shift ;;
    --no-boot)        NO_BOOT=true; shift ;;
    --stop)           STOP_AFTER=true; shift ;;
    --seed-snapshot)  SEED_SNAPSHOT=true; shift ;;
    --no-snapshot)    NO_SNAPSHOT=true; shift ;;
    --release)        RELEASE_LEASE=true; shift ;;
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
fi

log() { echo "[setup-ar] $*"; }

# --- --release: hand a sticky pool reservation back (#2862) -------------------
# A sticky lease deliberately outlives this script (that is the whole fix), so
# there has to be an explicit way to give it back without killing the emulator.
# Runs before any SDK/AVD work: releasing a reservation must never depend on a
# healthy SDK install.
if $RELEASE_LEASE; then
  emu_lease_session_inherit >/dev/null 2>&1 || true
  released=0; refused=0
  while IFS= read -r lease_serial; do
    [[ -n "$lease_serial" ]] || continue
    emu_lease_is_sticky "$lease_serial" || continue
    if emu_lease_mine "$lease_serial" || [[ "${EMU_LEASE_TAKEOVER:-0}" == "1" ]]; then
      rm -f "$EMU_LEASE_DIR/${lease_serial}.lease" 2>/dev/null || true
      log "released sticky reservation on $lease_serial"
      released=$(( released + 1 ))
    else
      log "$lease_serial is reserved by session '$(_emu_lease_field "$lease_serial" session)' — NOT released"
      refused=$(( refused + 1 ))
    fi
  done < <(emu_lease_serials)
  if [[ "$released" -eq 0 && "$refused" -eq 0 ]]; then
    log "no sticky reservation to release."
  elif [[ "$refused" -gt 0 ]]; then
    log "to release another session's reservation, re-run with EMU_LEASE_SESSION=<its token>"
    log "or force it with EMU_LEASE_TAKEOVER=1 (it may be driving a live QA sweep)."
  fi
  log "the emulator itself is untouched — stop it with: --stop"
  exit 0
fi

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

if $CLEAN && $CHECK_ONLY; then
  log "--check and --clean are mutually exclusive"; exit 2
fi

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

# `adb shell` can block FOREVER against a guest whose framework is wedged, while
# `adb devices` still reports `device`. Every guest probe therefore goes through
# bounded_adb — the diagnostics are what an operator reaches for AFTER a guest
# wedges, so an unbounded `--check` would hang exactly when it is needed.
# Prefers coreutils `timeout`/`gtimeout`, falls back to the portable perl alarm
# idiom (alarm survives exec), and runs unbounded only if neither exists.
ADB_TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

# bounded_adb <cap_seconds> <serial> <adb args…> — returns 124 when it hung.
bounded_adb() {
  local cap="$1" serial="$2"
  shift 2
  local rc=0
  if [[ -n "$ADB_TIMEOUT_BIN" ]]; then
    "$ADB_TIMEOUT_BIN" "$cap" "$ADB_BIN" -s "$serial" "$@" || rc=$?
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

# Camera-id topology of a RUNNING emulator (#2754). ARCore's device APK hard-requires
# the back camera at id "0"; arm64 AVDs enumerate back="10"/front="1" (never "0"), and
# ARCore ships no arm64 emulator build (its emulator APK has no arm64 libs) — so
# live-camera AR sessions can NEVER start on an arm64 AVD. Surfacing the topology here
# keeps that failure loud instead of masked behind a black viewport.
show_camera_topology() {
  # Optional $1: probe THIS serial. Default: the first running emulator.
  local serial="${1:-}"
  if [[ -z "$serial" ]]; then
    # Pool members only (see EMU_POOL_PORT_EXCLUDE_FROM).
    serial="$(emu_running_serials "$ADB_BIN" | head -n1)"
  fi
  if [[ -z "$serial" ]]; then
    log "camera topology: no running emulator to probe"
    return 0
  fi
  local dump ids
  dump="$(bounded_adb 320 "$serial" shell dumpsys -t 300 media.camera 2>/dev/null)" || true
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
    log "    real camera AR needs a physical device."
  fi
}

if $CHECK_ONLY; then
  show_config
  show_snapshot_status
  show_ram_and_emulator_status
  show_camera_topology
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
    if emu_lease_acquire "$free_serial" "$ADB_BIN"; then
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
      emu_lease_acquire "$serial" "$ADB_BIN" || true
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
    if emu_lease_acquire "$waited_serial" "$ADB_BIN"; then
      log "leased emulator $waited_serial after waiting for the pool"
      EMU_SERIAL="$waited_serial"
      return 0
    fi
  fi
  log "could not lease or boot a pool emulator — pool is full and no lease freed in time"
  return 1
}


# ensure_airplane_mode_disabled — probe + repair connectivity right after boot
# (#2959). A `qa-clean` snapshot cold-boots with whatever radio state it was
# seeded with; if that was airplane mode ON, every streamed-asset resolve on
# this run silently falls back to its bundled stand-in — same file in, same
# file out, so the `DisposableEffect` that reloads a changed `Model` never
# fires and a round-trip QA pass can report green while the streamed path
# never actually ran (measured closing #2942: two different Sketchfab slugs
# both rendered the same bundled helmet, `airplane_mode_on=1` was the cause).
# Best-effort: this only repairs the *emulator's* radio state (airplane
# mode), not a dead route with the radio on (captive portal, dead DNS, a
# dropped VPN) — device-qa.sh's connectivity-gated sub-legs
# (record_streamed_subleg, lib/qa-connectivity.sh's 3-signal probe) are the
# backstop for "key present but still no real route" (#2959 proposal 2).
ensure_airplane_mode_disabled() {
  local serial="$1"
  local mode
  mode="$("$ADB_BIN" -s "$serial" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r\n')"
  if [[ "$mode" == "1" ]]; then
    log "WARNING: $serial booted with airplane_mode_on=1 — streamed-asset legs would silently swap to their bundled fallback (#2959). Disabling."
    "$ADB_BIN" -s "$serial" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || \
      "$ADB_BIN" -s "$serial" shell settings put global airplane_mode_on 0 >/dev/null 2>&1
    "$ADB_BIN" -s "$serial" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
    sleep 2
    mode="$("$ADB_BIN" -s "$serial" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r\n')"
    if [[ "$mode" == "1" ]]; then
      log "WARNING: could not clear airplane mode on $serial — streamed-asset legs on this run should be treated as untested"
    else
      log "airplane mode disabled on $serial — re-seed the '$GOLDEN_SNAPSHOT' snapshot with it off so future cold-boots stop needing this repair (#2959 proposal 3)"
    fi
  else
    log "$serial: airplane_mode_on=$mode — connectivity radio state OK"
  fi
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
  ensure_airplane_mode_disabled "$serial"
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
  emu_lease_acquire "$EMU_SERIAL" "$ADB_BIN" || true
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
# emu_running_serials (NOT a raw `adb devices`) so only pool members are ever
# reported on here.
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
    log "  Real camera AR needs a physical device."
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
# Hand the lease forward (#2862). This script's job is to provision and RETURN;
# the emulator outlives it. A pid-scoped lease would be wiped by the EXIT trap
# below, leaving a live emulator that every peer session sees as free — which is
# exactly how two sessions ended up driving the same AVD. Converting to a sticky
# lease keeps the reservation attached to the emulator, and publishing the token
# lets the QA script this session runs next (a separate shell, no inherited env)
# adopt it once.
if ! $CHECK_ONLY && ! $STOP_AFTER && [[ -n "${serial:-}" ]]; then
  if emu_lease_mark_sticky "$serial" "setup-ar-emulator"; then
    # Publish the token for a QA script this session runs next in a SEPARATE
    # shell to inherit — ONLY when we minted it. If the caller exported its own
    # token it already shares the reservation, and publishing would let a
    # concurrent peer inherit the token first and steal the emulator (the
    # documented handoff-window leak). The sticky lease itself still reserves the
    # emulator against every peer regardless; the handoff is only the token relay.
    if [[ "$EMU_LEASE_SESSION_MINTED" == "true" ]]; then
      emu_lease_session_publish "$EMU_LEASE_SESSION"
    fi
  fi
fi

log "done."
if $STOP_AFTER; then
  log "emulator stopped. Re-run without --stop to leave it warm for QA."
else
  log "emulator left running for QA on serial ${serial:-emulator-5554}. next steps:"
  log "  # Export the token FIRST, in the shell that will drive QA — it makes THIS"
  log "  # session the owner. The sticky reservation already guards the emulator"
  log "  # against peers, but a token left only in the handoff file can be adopted"
  log "  # by the first QA script to start within ${EMU_LEASE_HANDOFF_WINDOW}s — including a"
  log "  # CONCURRENT session — which then drives the same emulator (#2862)."
  log "  export EMU_LEASE_SESSION=${EMU_LEASE_SESSION}"
  log "  export ANDROID_SERIAL=${serial:-emulator-5554}   # pin QA to this emulator"
  log "  source .claude/scripts/lib/android-cli.sh && android_cli_ensure"
  log "  android_cli_install_and_launch <apk> io.github.sceneview.demo/.MainActivity   # proves the install (#2990)"
  log "  stop it with: android emulator stop $AVD_NAME"
  log "  give the reservation back (emulator keeps running): --release"
fi
if ! $CHECK_ONLY && ! golden_snapshot_exists; then
  log "tip: seed a warm QA boot snapshot once with:"
  log "  bash .claude/scripts/setup-ar-emulator.sh --clean --seed-snapshot"
  log "  -> future runs cold-boot from '$GOLDEN_SNAPSHOT' (faster, deterministic, #1672)"
fi
