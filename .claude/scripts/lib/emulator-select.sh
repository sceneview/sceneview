#!/usr/bin/env bash
# emulator-select.sh — RAM-budgeted adaptive emulator pool for the device-QA harness.
#
# Why this exists (#1647 → #1654):
#   The autonomous device-QA harness boots an Android emulator for its android
#   and ar legs. When several Claude Code sessions / parallel agents run
#   device-QA on the same Mac, each naïvely tries to boot its own emulator →
#   RAM exhaustion, boot failures, cross-session conflicts.
#
#   #1647 first solved this with a STRICT single shared emulator + one global
#   advisory lock. That removed the contention but also serialised every
#   parallel session behind a rigid one-emulator barrier — wasteful on a host
#   that has RAM to spare.
#
#   #1654 evolves it into a RAM-BUDGETED ADAPTIVE POOL — never a rigid barrier,
#   never more emulators than live host RAM safely allows, floor of 1:
#     1. RAM budget → cap  — max_emulators is derived from live free RAM, so a
#                            tight host resolves to 1 naturally (just physics).
#     2. Lease-based pool  — per-emulator lease files; a session leases a free
#                            running emulator, boots a new one if the cap has
#                            room, or waits (bounded) for a lease to free.
#     3. Live RAM re-gate  — free RAM is re-read immediately before EVERY boot;
#                            a boot below EMU_MIN_FREE_RAM_MB is refused even if
#                            the cap said there was room. Memory safety is the
#                            hard invariant — the pool never OOMs the host.
#     4. Stale-lease reclaim — a lease whose owner pid is dead AND whose serial
#                            is gone from `adb devices` is reclaimed.
#     5. Multi-port boots  — each new emulator gets a distinct `-port` so they
#                            coexist; the leased serial is plumbed downstream.
#
# Usage from another script:
#     source "$(dirname "$0")/lib/emulator-select.sh"
#     emu_host_free_ram_mb               # echoes available host RAM in MB
#     emu_pool_cap                       # echoes the live RAM-budgeted cap [1..MAX]
#     emu_ram_allows_boot                # 0 if RAM allows a fresh boot, else 1
#     emu_recommended_memory_mb          # echoes a RAM-scaled `-memory` value
#     emu_running_serials [adb]          # newline list of booted emulator serials
#     emu_count_running [adb]            # count of booted emulators
#     emu_lease_acquire <serial>         # claim a lease for an already-up serial
#     emu_lease_release <serial>         # drop a lease this process owns
#     emu_lease_free_serial [adb]        # echo a running, unleased serial (or "")
#     emu_pool_reclaim_stale [adb]       # GC dead leases
#     emu_next_free_port [adb]           # echo a free emulator console port
#     emu_pool_status [adb]              # human-readable pool snapshot to stderr
#
# This lib is sourced, not executed — do NOT `set -e` here (it would leak into
# the caller). Helpers report via return codes / stdout.

# --- Tunables ---------------------------------------------------------------
# Minimum host RAM (MB) that must be FREE before we will boot a fresh emulator.
# This is the HARD memory-safety gate: re-checked live immediately before every
# boot, even when the pool cap says there is room. Below this we refuse to boot.
EMU_MIN_FREE_RAM_MB="${EMU_MIN_FREE_RAM_MB:-3072}"

# Emulator `-memory` floor / ceiling. The AVD config.ini pins hw.ramSize=4096,
# but on a tight host we pass a smaller `-memory` at boot time to avoid OOM.
EMU_MEMORY_FLOOR_MB="${EMU_MEMORY_FLOOR_MB:-2048}"
EMU_MEMORY_CEILING_MB="${EMU_MEMORY_CEILING_MB:-4096}"

# Headroom (MB) the host always keeps for itself — subtracted from free RAM
# both when sizing guest `-memory` and when computing the pool cap.
EMU_HOST_HEADROOM_MB="${EMU_HOST_HEADROOM_MB:-2048}"

# RAM budget charged to EACH emulator when computing the pool cap. The live
# free-RAM minus headroom is divided by this to get how many emulators fit.
# ~3 GB matches a right-sized guest plus its qemu/host overhead.
EMU_RAM_BUDGET_PER_EMU_MB="${EMU_RAM_BUDGET_PER_EMU_MB:-3072}"

# Hard ceiling on the pool size regardless of how much RAM the host has — keeps
# a 128 GB workstation from spawning a dozen emulators and saturating CPU/GPU.
EMU_POOL_MAX="${EMU_POOL_MAX:-3}"

# Per-emulator lease directory. Each lease is a file `<serial>.lease` whose
# contents is the owning pid. mkdir of the parent + a `noclobber`-style atomic
# create give us a race-free pool registry shared by all sessions on the host.
EMU_LEASE_DIR="${EMU_LEASE_DIR:-${TMPDIR:-/tmp}/sceneview-device-qa-emu}"

# Bounded wait (seconds) for a lease to free when the pool is full.
EMU_LEASE_WAIT_TIMEOUT="${EMU_LEASE_WAIT_TIMEOUT:-300}"

# First emulator console port. Android emulator console ports are even and
# start at 5554; the adb serial is `emulator-<port>`. New pool members step by
# 2 (5554, 5556, 5558, …) so multiple emulators coexist.
EMU_BASE_PORT="${EMU_BASE_PORT:-5554}"

_emu_log() { echo "[emu-select] $*" >&2; }

# --- RAM monitoring ---------------------------------------------------------
# emu_host_total_ram_mb — total physical RAM in MB.
emu_host_total_ram_mb() {
  case "$(uname -s)" in
    Darwin)
      local bytes
      bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
      echo $(( bytes / 1024 / 1024 ))
      ;;
    Linux)
      awk '/^MemTotal:/ { printf "%d", $2 / 1024 }' /proc/meminfo 2>/dev/null || echo 0
      ;;
    *) echo 0 ;;
  esac
}

# emu_host_free_ram_mb — *available* RAM in MB (free + reclaimable).
# macOS: vm_stat reports pages — available ≈ (free + inactive) * page_size.
#        inactive pages are reclaimable, so counting them matches what the OS
#        will actually hand a new process.
# Linux: MemAvailable already accounts for reclaimable cache.
emu_host_free_ram_mb() {
  case "$(uname -s)" in
    Darwin)
      vm_stat 2>/dev/null | awk '
        /page size of/      { for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) ps=$i }
        /Pages free/        { gsub(/\./,"",$NF); free=$NF }
        /Pages inactive/    { gsub(/\./,"",$NF); inactive=$NF }
        END {
          if (ps == "") ps = 4096
          printf "%d", (free + inactive) * ps / 1024 / 1024
        }'
      ;;
    Linux)
      awk '/^MemAvailable:/ { printf "%d", $2 / 1024 }' /proc/meminfo 2>/dev/null || echo 0
      ;;
    *) echo 0 ;;
  esac
}

# emu_pool_cap — echo the live RAM-budgeted cap on the number of emulators.
#   max_emulators = floor((free_RAM - EMU_HOST_HEADROOM_MB) / EMU_RAM_BUDGET_PER_EMU_MB)
#   clamped to [1, EMU_POOL_MAX].
# On a RAM-tight host this resolves to 1 naturally — no rigid barrier, just
# physics. If RAM cannot be measured we conservatively return 1.
emu_pool_cap() {
  local free cap
  free="$(emu_host_free_ram_mb)"
  if [[ "$free" -le 0 ]]; then
    echo 1
    return 0
  fi
  cap=$(( (free - EMU_HOST_HEADROOM_MB) / EMU_RAM_BUDGET_PER_EMU_MB ))
  [[ "$cap" -lt 1 ]] && cap=1
  [[ "$cap" -gt "$EMU_POOL_MAX" ]] && cap="$EMU_POOL_MAX"
  echo "$cap"
}

# emu_ram_allows_boot — HARD memory-safety gate. Return 0 if there is enough
# free RAM RIGHT NOW to safely boot one more emulator, 1 otherwise. Callers MUST
# invoke this immediately before every boot — the pool cap is an estimate, this
# is the live truth and it is the invariant that keeps the host off the OOM cliff.
emu_ram_allows_boot() {
  local free; free="$(emu_host_free_ram_mb)"
  if [[ "$free" -le 0 ]]; then
    # Could not measure (unknown OS) — don't block the harness, but say so.
    _emu_log "could not measure host RAM — proceeding without a RAM gate"
    return 0
  fi
  if [[ "$free" -lt "$EMU_MIN_FREE_RAM_MB" ]]; then
    _emu_log "host free RAM ${free} MB is below the ${EMU_MIN_FREE_RAM_MB} MB boot threshold"
    _emu_log "refusing to boot another emulator — lease a running one or free RAM first"
    return 1
  fi
  _emu_log "host free RAM ${free} MB — OK to boot (threshold ${EMU_MIN_FREE_RAM_MB} MB)"
  return 0
}

# emu_recommended_memory_mb — echo a RAM-scaled `-memory` value (MB) for a
# fresh boot. Scales to current headroom, clamped to [floor, ceiling].
emu_recommended_memory_mb() {
  local free mem
  free="$(emu_host_free_ram_mb)"
  if [[ "$free" -le 0 ]]; then
    # Unknown host — use the floor, the safe choice.
    echo "$EMU_MEMORY_FLOOR_MB"
    return 0
  fi
  # Leave the host its headroom; give the rest (capped at the ceiling) to guest.
  mem=$(( free - EMU_HOST_HEADROOM_MB ))
  [[ "$mem" -gt "$EMU_MEMORY_CEILING_MB" ]] && mem="$EMU_MEMORY_CEILING_MB"
  [[ "$mem" -lt "$EMU_MEMORY_FLOOR_MB" ]]   && mem="$EMU_MEMORY_FLOOR_MB"
  echo "$mem"
}

# --- Running-emulator detection ---------------------------------------------
# emu_running_serials [adb_bin] — newline-separated list of every emulator-*
# device currently in "device" state. Empty output means none is up.
emu_running_serials() {
  local adb_bin="${1:-adb}"
  "$adb_bin" devices 2>/dev/null \
    | awk '/^emulator-[0-9]+/ && $2=="device" { print $1 }'
}

# emu_running_serial [adb_bin] — back-compat single-serial accessor: echoes the
# FIRST booted emulator serial (or nothing, return 1).
emu_running_serial() {
  local serial
  serial="$(emu_running_serials "${1:-adb}" | head -n1)"
  if [[ -n "$serial" ]]; then
    echo "$serial"
    return 0
  fi
  return 1
}

# emu_count_running [adb_bin] — number of emulator-* devices in "device" state.
emu_count_running() {
  emu_running_serials "${1:-adb}" | grep -c . || true
}

# emu_serial_alive <serial> [adb_bin] — 0 if <serial> is a booted device.
emu_serial_alive() {
  local serial="$1" adb_bin="${2:-adb}"
  emu_running_serials "$adb_bin" | grep -qxF "$serial"
}

# --- Lease-based pool -------------------------------------------------------
# Each pool member is tracked by a lease file `${EMU_LEASE_DIR}/<serial>.lease`
# containing the owning pid. Creating a lease atomically (via `set -o noclobber`
# in a subshell) lets N racing sessions cooperate without a global lock — each
# emulator can be leased by exactly one session at a time.

# _emu_lease_dir_init — ensure the lease directory exists.
_emu_lease_dir_init() {
  mkdir -p "$EMU_LEASE_DIR" 2>/dev/null || true
}

# _emu_lease_path <serial> — echo the lease file path for a serial.
_emu_lease_path() { echo "$EMU_LEASE_DIR/${1}.lease"; }

# emu_lease_owner <serial> — echo the pid recorded in a serial's lease, or "".
emu_lease_owner() {
  local lf; lf="$(_emu_lease_path "$1")"
  [[ -f "$lf" ]] && cat "$lf" 2>/dev/null || true
}

# emu_pool_reclaim_stale [adb_bin] — GC stale leases. A lease is stale when its
# owner pid is dead AND its emulator serial is no longer a booted device. Such a
# lease can never be released by its (gone) owner, so we reclaim it here.
emu_pool_reclaim_stale() {
  local adb_bin="${1:-adb}"
  _emu_lease_dir_init
  local lf serial owner
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    serial="$(basename "$lf" .lease)"
    owner="$(cat "$lf" 2>/dev/null || true)"
    # Owner process still alive → lease is live, keep it.
    if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then
      continue
    fi
    # Owner gone but the emulator is still up → another session may be about to
    # lease it; leave the (now ownerless) lease for emu_lease_acquire to take.
    if emu_serial_alive "$serial" "$adb_bin"; then
      continue
    fi
    # Owner dead AND emulator gone → genuinely stale, reclaim.
    _emu_log "reclaiming stale lease for $serial (owner ${owner:-?} dead, emulator gone)"
    rm -f "$lf" 2>/dev/null || true
  done
}

# emu_lease_acquire <serial> — atomically claim the lease for an already-running
# emulator <serial>. Returns 0 if THIS process now owns the lease, 1 if another
# live owner holds it. An ownerless lease (owner pid dead) is taken over.
emu_lease_acquire() {
  local serial="$1"
  [[ -n "$serial" ]] || { _emu_log "emu_lease_acquire: serial required"; return 1; }
  _emu_lease_dir_init
  local lf; lf="$(_emu_lease_path "$serial")"

  # Atomic create — `noclobber` makes `>` fail if the file already exists, so
  # exactly one of N racing sessions wins the lease.
  if ( set -o noclobber; echo "$$" > "$lf" ) 2>/dev/null; then
    _emu_log "leased emulator $serial (pid $$)"
    return 0
  fi

  # Lease file exists — is its owner still alive?
  local owner; owner="$(cat "$lf" 2>/dev/null || true)"
  if [[ "$owner" == "$$" ]]; then
    return 0   # already ours
  fi
  if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then
    return 1   # held by a live peer
  fi
  # Ownerless lease — take it over (best-effort; another reclaimer may race us).
  echo "$$" > "$lf" 2>/dev/null || true
  if [[ "$(cat "$lf" 2>/dev/null || true)" == "$$" ]]; then
    _emu_log "took over ownerless lease for $serial (pid $$)"
    return 0
  fi
  return 1
}

# emu_lease_release <serial> — release a lease IF this process owns it.
emu_lease_release() {
  local serial="$1"
  [[ -n "$serial" ]] || return 0
  local lf; lf="$(_emu_lease_path "$serial")"
  [[ -f "$lf" ]] || return 0
  if [[ "$(cat "$lf" 2>/dev/null || true)" == "$$" ]]; then
    rm -f "$lf" 2>/dev/null || true
    _emu_log "released lease for $serial"
  fi
}

# emu_lease_release_all — release every lease this process owns. Safe in an
# EXIT trap — a no-op for serials owned by other sessions.
emu_lease_release_all() {
  _emu_lease_dir_init
  local lf
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    if [[ "$(cat "$lf" 2>/dev/null || true)" == "$$" ]]; then
      local serial; serial="$(basename "$lf" .lease)"
      rm -f "$lf" 2>/dev/null || true
      _emu_log "released lease for $serial"
    fi
  done
}

# emu_lease_free_serial [adb_bin] — echo the serial of a RUNNING emulator that
# currently has no live lease holder (so this caller could lease it). Empty
# output means every running emulator is already leased (or none is running).
emu_lease_free_serial() {
  local adb_bin="${1:-adb}"
  _emu_lease_dir_init
  local serial owner
  while IFS= read -r serial; do
    [[ -n "$serial" ]] || continue
    owner="$(emu_lease_owner "$serial")"
    if [[ -z "$owner" ]] || ! kill -0 "$owner" 2>/dev/null; then
      echo "$serial"
      return 0
    fi
  done < <(emu_running_serials "$adb_bin")
  return 1
}

# emu_next_free_port [adb_bin] — echo the lowest even console port not already
# used by a running emulator. New pool members boot on this `-port` so they get
# a distinct `emulator-<port>` serial and coexist with their peers.
emu_next_free_port() {
  local adb_bin="${1:-adb}"
  local used port
  used="$(emu_running_serials "$adb_bin" | sed 's/^emulator-//')"
  port="$EMU_BASE_PORT"
  while echo "$used" | grep -qx "$port"; do
    port=$(( port + 2 ))
  done
  echo "$port"
}

# emu_lease_wait_for_free [adb_bin] [timeout_sec] — block (bounded) until a
# running emulator's lease frees, then echo that serial. Used when the pool is
# at its RAM cap and the caller cannot boot another. Returns 1 on timeout.
emu_lease_wait_for_free() {
  local adb_bin="${1:-adb}"
  local timeout="${2:-$EMU_LEASE_WAIT_TIMEOUT}"
  local waited=0 serial
  while true; do
    emu_pool_reclaim_stale "$adb_bin"
    if serial="$(emu_lease_free_serial "$adb_bin")"; then
      echo "$serial"
      return 0
    fi
    if [[ "$waited" -ge "$timeout" ]]; then
      _emu_log "timed out after ${timeout}s waiting for a pool lease to free"
      return 1
    fi
    sleep 3
    waited=$(( waited + 3 ))
  done
}

# emu_pool_status [adb_bin] — print a human-readable pool snapshot to stderr:
# running emulator count, the live RAM-budgeted cap, free RAM, and active leases.
emu_pool_status() {
  local adb_bin="${1:-adb}"
  _emu_lease_dir_init
  local free total cap running
  free="$(emu_host_free_ram_mb)"
  total="$(emu_host_total_ram_mb)"
  cap="$(emu_pool_cap)"
  running="$(emu_count_running "$adb_bin")"
  _emu_log "pool: running=${running} cap=${cap} (RAM ${free}/${total} MB free, headroom ${EMU_HOST_HEADROOM_MB} MB, budget ${EMU_RAM_BUDGET_PER_EMU_MB} MB/emu, max ${EMU_POOL_MAX})"
  local lf serial owner alive
  local any=0
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    any=1
    serial="$(basename "$lf" .lease)"
    owner="$(cat "$lf" 2>/dev/null || true)"
    if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then alive="live"; else alive="ownerless"; fi
    _emu_log "  lease ${serial} -> pid ${owner:-?} (${alive})"
  done
  [[ "$any" -eq 0 ]] && _emu_log "  no active leases"
}
