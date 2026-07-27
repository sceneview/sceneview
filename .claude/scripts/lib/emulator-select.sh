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
# FIRST line is the owning pid, followed by optional `key=value` metadata lines
# (`session=`, `mode=`, `avd=`, `since=`, `label=`). mkdir of the parent + a
# `noclobber`-style atomic create give us a race-free pool registry shared by
# all sessions on the host. Readers must take the pid from line 1 only — never
# `cat` the whole file into a pid comparison.
EMU_LEASE_DIR="${EMU_LEASE_DIR:-${TMPDIR:-/tmp}/sceneview-device-qa-emu}"

# Bounded wait (seconds) for a lease to free when the pool is full.
EMU_LEASE_WAIT_TIMEOUT="${EMU_LEASE_WAIT_TIMEOUT:-300}"

# Session token owning a STICKY lease (#2862). A pid-scoped lease dies with the
# script that took it, but `setup-ar-emulator.sh` provisions an emulator and
# then HANDS IT BACK — the emulator outlives the script, and before this the
# EXIT trap dropped its lease, so the next session was handed a live emulator
# another session was interpreting. A sticky lease is keyed to this token
# instead of a pid, so it survives the provisioning script and is honoured by
# every later `device-qa.sh` / `qa-android-demos.sh` run that exports the same
# token. Empty means "no session identity": such a sticky lease still reserves
# the emulator against OTHER callers, and the provisioning script prints the
# exact `export` line to inherit it.
EMU_LEASE_SESSION="${EMU_LEASE_SESSION:-}"

# How long (seconds) a sticky lease reserves its emulator without being
# refreshed or released. Without an upper bound, a session that dies right
# after provisioning would reserve the emulator forever and a RAM-tight host
# (pool cap 1) could never run QA again. 4 h comfortably covers a QA sweep
# while keeping the deadlock finite; `--release` frees it immediately.
EMU_LEASE_STICKY_TTL="${EMU_LEASE_STICKY_TTL:-14400}"

# When non-empty, only an emulator whose AVD name matches is considered
# leasable (#2862). `emu_running_serials` filters by PORT alone, so any
# `emulator-NNNN` under the exclusion cutoff used to qualify — a stray
# `Tablet10_QA` on 5554 would be leased and driven as if it were the
# ARCore-ready `Pixel_7a`, and the QA verdict would describe a device nobody
# meant to test. Callers that care set this to their AVD name.
EMU_REQUIRE_AVD="${EMU_REQUIRE_AVD:-}"

# Canonical pool AVD name — the QA emulator `setup-ar-emulator.sh` provisions.
# Declared here so every pool script names the same device from ONE place
# (device-qa.sh guards on it without duplicating the literal).
EMU_POOL_AVD="${EMU_POOL_AVD:-Pixel_7a}"

# Escape hatch: take over another session's sticky lease anyway. Deliberately
# opt-in and logged — the whole point of a sticky lease is that a peer must not
# silently steal the emulator out from under a running QA sweep.
EMU_LEASE_TAKEOVER="${EMU_LEASE_TAKEOVER:-0}"

# Handoff window (seconds) for the documented two-step workflow — provision
# with `setup-ar-emulator.sh`, then run QA in a SEPARATE shell that inherited
# no environment. The provisioning script publishes its session token; the next
# QA script started within this window adopts it ONCE (the token file is
# consumed on read) and can therefore reuse the emulator it just provisioned.
#
# Known limit, stated honestly: a *different* session that starts a QA script
# inside this window, before the provisioning session does, inherits the token
# and gets the emulator. That is strictly narrower than the behaviour this
# replaces (where any peer took any running emulator, forever) but it is not
# perfect isolation. Export EMU_LEASE_SESSION explicitly in both scripts to
# close it completely, or set the window to 0 to disable inheritance.
EMU_LEASE_HANDOFF_WINDOW="${EMU_LEASE_HANDOFF_WINDOW:-900}"

# First emulator console port. Android emulator console ports are even and
# start at 5554; the adb serial is `emulator-<port>`. New pool members step by
# 2 (5554, 5556, 5558, …) so multiple emulators coexist.
EMU_BASE_PORT="${EMU_BASE_PORT:-5554}"

# Ports AT OR ABOVE this are OUTSIDE the QA pool — reserved for special rigs
# (e.g. the x86_64-under-Rosetta ARCore rig, #2758, which boots on port 5584).
# Excluded serials are invisible to the pool: never leased as a "free running
# emulator", never counted against the RAM-budgeted cap, never handed to a
# standard QA run (a Maestro sweep on a ~10x-slower TCG guest would time out
# everywhere and poison the QA verdict).
EMU_POOL_PORT_EXCLUDE_FROM="${EMU_POOL_PORT_EXCLUDE_FROM:-5584}"

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
# emu_running_serials [adb_bin] — newline-separated list of every POOL
# emulator-* device currently in "device" state. Empty output means none is up.
# Serials on ports >= EMU_POOL_PORT_EXCLUDE_FROM (special rigs, e.g. the
# Rosetta ARCore rig on 5584) are filtered out — they are not pool members.
emu_running_serials() {
  local adb_bin="${1:-adb}"
  "$adb_bin" devices 2>/dev/null \
    | awk -v cutoff="$EMU_POOL_PORT_EXCLUDE_FROM" \
        '/^emulator-[0-9]+/ && $2=="device" { if (substr($1,10)+0 < cutoff) print $1 }'
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

# emu_serial_avd_name <serial> [adb_bin] — echo the AVD name behind a running
# serial, or "" when the console does not answer. `adb emu avd name` prints the
# name then a trailing `OK` line; only the first line is the name.
emu_serial_avd_name() {
  local serial="$1" adb_bin="${2:-adb}"
  [[ -n "$serial" ]] || return 1
  "$adb_bin" -s "$serial" emu avd name 2>/dev/null \
    | head -n1 | tr -d '\r' | sed 's/[[:space:]]*$//'
}

# emu_serial_avd_matches <serial> [adb_bin] — 0 when EMU_REQUIRE_AVD is unset
# (no constraint) or when the serial's AVD equals it. An emulator whose console
# refuses to answer is treated as NOT matching: leasing an unidentified device
# is exactly the failure this guards against.
emu_serial_avd_matches() {
  local serial="$1" adb_bin="${2:-adb}"
  [[ -n "$EMU_REQUIRE_AVD" ]] || return 0
  local name; name="$(emu_serial_avd_name "$serial" "$adb_bin")"
  if [[ -z "$name" ]]; then
    _emu_log "$serial: AVD name unavailable — not leasable (expected $EMU_REQUIRE_AVD)"
    return 1
  fi
  [[ "$name" == "$EMU_REQUIRE_AVD" ]]
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
# The pid is LINE 1; metadata lines follow, so this must not `cat` the file.
emu_lease_owner() {
  local lf; lf="$(_emu_lease_path "$1")"
  [[ -f "$lf" ]] || return 0
  head -n1 "$lf" 2>/dev/null | tr -d '[:space:]'
}

# _emu_lease_field <serial> <key> — echo a `key=value` metadata line's value.
_emu_lease_field() {
  local lf; lf="$(_emu_lease_path "$1")"
  [[ -f "$lf" ]] || return 0
  sed -n "s/^${2}=//p" "$lf" 2>/dev/null | head -n1
}

# _emu_lease_write <serial> <pid> <mode> <session> <avd> <since> [label] —
# (re)write a lease file wholesale. Callers own the atomicity decision: the
# first create goes through `noclobber` in emu_lease_acquire, later rewrites
# only ever happen on a lease this process already owns.
_emu_lease_write() {
  local lf; lf="$(_emu_lease_path "$1")"
  {
    echo "$2"
    echo "mode=$3"
    echo "session=$4"
    echo "avd=$5"
    echo "since=$6"
    echo "label=${7:-}"
  } > "$lf" 2>/dev/null || return 1
}

# emu_lease_is_sticky <serial> — 0 when the lease outlives its taker's pid.
emu_lease_is_sticky() {
  [[ "$(_emu_lease_field "$1" mode)" == "sticky" ]]
}

# emu_lease_expired <serial> — 0 when a sticky lease is past EMU_LEASE_STICKY_TTL.
# A non-sticky lease never "expires": it is bounded by its owner pid's life.
emu_lease_expired() {
  emu_lease_is_sticky "$1" || return 1
  local since now
  since="$(_emu_lease_field "$1" since)"
  [[ "$since" =~ ^[0-9]+$ ]] || return 1   # unreadable stamp → treat as live
  now="$(date +%s)"
  [[ $(( now - since )) -ge "$EMU_LEASE_STICKY_TTL" ]]
}

# emu_lease_mine <serial> — 0 when THIS caller may use the emulator: it owns the
# lease by pid, or the lease is sticky and carries our (non-empty) session token.
emu_lease_mine() {
  local serial="$1"
  [[ -f "$(_emu_lease_path "$serial")" ]] || return 1
  [[ "$(emu_lease_owner "$serial")" == "$$" ]] && return 0
  local sess; sess="$(_emu_lease_field "$serial" session)"
  [[ -n "$EMU_LEASE_SESSION" && "$sess" == "$EMU_LEASE_SESSION" ]]
}

# emu_lease_mark_sticky <serial> [label] — convert a lease this process owns
# into one that survives its exit (#2862). Called by a provisioning script that
# leaves the emulator RUNNING for a later consumer: without it the EXIT trap
# drops the lease and the live emulator looks free to every peer session.
emu_lease_mark_sticky() {
  local serial="$1" label="${2:-}"
  [[ -n "$serial" ]] || return 1
  emu_lease_mine "$serial" || { _emu_log "cannot make $serial sticky — not our lease"; return 1; }
  local avd; avd="$(_emu_lease_field "$serial" avd)"
  _emu_lease_write "$serial" "$$" "sticky" "$EMU_LEASE_SESSION" "$avd" "$(date +%s)" "$label" || return 1
  _emu_log "lease for $serial is now sticky (session '${EMU_LEASE_SESSION:-none}', ttl ${EMU_LEASE_STICKY_TTL}s)"
}

# emu_lease_serials — newline-separated list of every serial that has a lease
# file, whatever its state. Callers filter (sticky, mine, expired) themselves.
emu_lease_serials() {
  _emu_lease_dir_init
  local lf
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    basename "$lf" .lease
  done
}

# --- Session identity + handoff ---------------------------------------------
# _emu_handoff_path — file holding the most recently published session token.
_emu_handoff_path() { echo "$EMU_LEASE_DIR/handoff.session"; }

# emu_lease_session_new — mint a session token for a caller that has none.
# Distinct per provisioning run, so two sessions never collide by construction.
emu_lease_session_new() { echo "sv-$(date +%s)-$$"; }

# emu_lease_session_publish <token> — offer <token> to the next QA script this
# session starts (see EMU_LEASE_HANDOFF_WINDOW).
emu_lease_session_publish() {
  local token="$1"
  [[ -n "$token" ]] || return 0
  _emu_lease_dir_init
  printf '%s\n%s\n' "$token" "$(date +%s)" > "$(_emu_handoff_path)" 2>/dev/null || true
}

# emu_lease_session_inherit — adopt a freshly published session token when this
# caller has none, and CONSUME it so it is inherited exactly once. Sets (and
# echoes) EMU_LEASE_SESSION on success. No-op when a token is already set, when
# none was published, when the window is disabled, or when it has expired.
emu_lease_session_inherit() {
  [[ -z "$EMU_LEASE_SESSION" ]] || return 1
  [[ "$EMU_LEASE_HANDOFF_WINDOW" -gt 0 ]] 2>/dev/null || return 1
  local hf; hf="$(_emu_handoff_path)"
  [[ -f "$hf" ]] || return 1
  local token stamp
  token="$(sed -n 1p "$hf" 2>/dev/null)"
  stamp="$(sed -n 2p "$hf" 2>/dev/null)"
  [[ -n "$token" && "$stamp" =~ ^[0-9]+$ ]] || { rm -f "$hf" 2>/dev/null; return 1; }
  if [[ $(( $(date +%s) - stamp )) -ge "$EMU_LEASE_HANDOFF_WINDOW" ]]; then
    _emu_log "published session token is older than ${EMU_LEASE_HANDOFF_WINDOW}s — not inherited"
    rm -f "$hf" 2>/dev/null || true
    return 1
  fi
  rm -f "$hf" 2>/dev/null || true   # consume: inherited exactly once
  EMU_LEASE_SESSION="$token"
  export EMU_LEASE_SESSION
  _emu_log "inherited session token '$token' from the provisioning run"
  echo "$token"
}

# emu_lease_release_sticky <serial> — explicitly hand a sticky lease back. Only
# releases a lease this caller owns (same pid, or same session token).
emu_lease_release_sticky() {
  local serial="$1"
  [[ -n "$serial" ]] || return 0
  local lf; lf="$(_emu_lease_path "$serial")"
  [[ -f "$lf" ]] || return 0
  if emu_lease_mine "$serial"; then
    rm -f "$lf" 2>/dev/null || true
    _emu_log "released sticky lease for $serial"
    return 0
  fi
  _emu_log "sticky lease for $serial belongs to another session — not released"
  return 1
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
    owner="$(emu_lease_owner "$serial")"
    # Owner process still alive → lease is live, keep it. Checked FIRST so a
    # lease taken microseconds ago (emulator not yet in `adb devices`) is never
    # reclaimed out from under the session that just booted it.
    if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then
      continue
    fi
    # Sticky lease past its TTL → the session that reserved it is gone for good
    # (it would have refreshed or released otherwise). Reclaim, loudly: an
    # unbounded sticky lease would wedge a pool-cap-1 host forever.
    if emu_lease_expired "$serial"; then
      _emu_log "reclaiming EXPIRED sticky lease for $serial (older than ${EMU_LEASE_STICKY_TTL}s)"
      rm -f "$lf" 2>/dev/null || true
      continue
    fi
    # Live sticky lease on a running emulator → a reservation that deliberately
    # outlives its taker's pid (#2862). Keep it; only its session may use it.
    if emu_lease_is_sticky "$serial" && emu_serial_alive "$serial" "$adb_bin"; then
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
  local serial="$1" adb_bin="${2:-adb}"
  [[ -n "$serial" ]] || { _emu_log "emu_lease_acquire: serial required"; return 1; }
  _emu_lease_dir_init
  local lf; lf="$(_emu_lease_path "$serial")"
  local avd; avd="$(emu_serial_avd_name "$serial" "$adb_bin" 2>/dev/null || true)"

  # Atomic create — `noclobber` makes `>` fail if the file already exists, so
  # exactly one of N racing sessions wins the lease. The pid goes in first so a
  # peer reading line 1 mid-write still sees a valid owner.
  if ( set -o noclobber; echo "$$" > "$lf" ) 2>/dev/null; then
    _emu_lease_write "$serial" "$$" "pid" "$EMU_LEASE_SESSION" "$avd" "$(date +%s)" ""
    _emu_log "leased emulator $serial (pid $$${avd:+, avd $avd})"
    return 0
  fi

  # Lease file exists — is it already ours (by pid, or by session token)?
  local owner; owner="$(emu_lease_owner "$serial")"
  if [[ "$owner" == "$$" ]]; then
    return 0   # already ours
  fi
  if emu_lease_mine "$serial"; then
    # Sticky lease carrying our session token — adopt it into this process so
    # the normal pid bookkeeping applies for the rest of the run.
    _emu_lease_write "$serial" "$$" "$(_emu_lease_field "$serial" mode)" \
      "$EMU_LEASE_SESSION" "${avd:-$(_emu_lease_field "$serial" avd)}" \
      "$(_emu_lease_field "$serial" since)" "$(_emu_lease_field "$serial" label)"
    _emu_log "adopted our session's lease for $serial (pid $$)"
    return 0
  fi
  if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then
    return 1   # held by a live peer
  fi
  # Ownerless pid-lease → free to take. A sticky lease is NOT ownerless: it
  # reserves the emulator for a session whose provisioning script has exited by
  # design (#2862), so only an explicit takeover may break it.
  if emu_lease_is_sticky "$serial" && ! emu_lease_expired "$serial"; then
    if [[ "$EMU_LEASE_TAKEOVER" != "1" ]]; then
      _emu_log "$serial is reserved by session '$(_emu_lease_field "$serial" session)' (sticky) — not taking it"
      _emu_log "  inherit it with EMU_LEASE_SESSION=<that token>, or force with EMU_LEASE_TAKEOVER=1"
      return 1
    fi
    _emu_log "TAKEOVER: breaking sticky lease on $serial (EMU_LEASE_TAKEOVER=1)"
  fi
  # Ownerless (or force-broken) lease — take it over (best-effort; another
  # reclaimer may race us).
  _emu_lease_write "$serial" "$$" "pid" "$EMU_LEASE_SESSION" "$avd" "$(date +%s)" ""
  if [[ "$(emu_lease_owner "$serial")" == "$$" ]]; then
    _emu_log "took over lease for $serial (pid $$)"
    return 0
  fi
  return 1
}

# emu_lease_release <serial> — release a lease IF this process owns it by pid.
# An explicit release drops a sticky lease too: the caller is saying "done with
# this emulator", which is exactly what a sticky reservation waits for.
emu_lease_release() {
  local serial="$1"
  [[ -n "$serial" ]] || return 0
  local lf; lf="$(_emu_lease_path "$serial")"
  [[ -f "$lf" ]] || return 0
  if [[ "$(emu_lease_owner "$serial")" == "$$" ]]; then
    rm -f "$lf" 2>/dev/null || true
    _emu_log "released lease for $serial"
  fi
}

# emu_lease_release_all — release every PID-SCOPED lease this process owns. Safe
# in an EXIT trap — a no-op for serials owned by other sessions.
#
# Sticky leases are deliberately SKIPPED (#2862): they exist precisely because
# the emulator outlives the script that took them. Dropping them here is the
# bug this function used to cause — `setup-ar-emulator.sh` exited, its trap
# wiped the lease, and the running emulator looked free to every peer session.
emu_lease_release_all() {
  _emu_lease_dir_init
  local lf serial
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    serial="$(basename "$lf" .lease)"
    [[ "$(emu_lease_owner "$serial")" == "$$" ]] || continue
    if emu_lease_is_sticky "$serial"; then
      _emu_log "keeping sticky lease for $serial (reserved beyond this process)"
      continue
    fi
    rm -f "$lf" 2>/dev/null || true
    _emu_log "released lease for $serial"
  done
}

# emu_lease_release_session — release every lease carrying THIS session's token,
# sticky ones included. For a consumer that minted its own token: nothing will
# ever come along to claim the reservation it leaves behind, so holding it until
# the TTL would idle the pool (a cap-1 host would make the next run wait).
emu_lease_release_session() {
  [[ -n "$EMU_LEASE_SESSION" ]] || return 0
  local serial
  while IFS= read -r serial; do
    [[ -n "$serial" ]] || continue
    [[ "$(_emu_lease_field "$serial" session)" == "$EMU_LEASE_SESSION" ]] || continue
    rm -f "$(_emu_lease_path "$serial")" 2>/dev/null || true
    _emu_log "released this session's lease for $serial"
  done < <(emu_lease_serials)
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
    # Wrong AVD on a pool port → never leasable (#2862). Checked before the
    # lease state: a stray `Tablet10_QA` on 5554 is not "a free pool emulator",
    # it is a device this harness must not drive at all.
    emu_serial_avd_matches "$serial" "$adb_bin" || continue
    # A live sticky lease reserves the emulator for its own session, even
    # though no process holds it — that is the whole point (#2862).
    if emu_lease_is_sticky "$serial" && ! emu_lease_expired "$serial"; then
      emu_lease_mine "$serial" || continue
      echo "$serial"
      return 0
    fi
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
    # Never allocate into the reserved special-rig range (>= cutoff). With
    # EMU_POOL_MAX=3 this is unreachable in practice; the guard keeps the
    # invariant explicit.
    if [[ "$port" -ge "$EMU_POOL_PORT_EXCLUDE_FROM" ]]; then
      _emu_log "no free pool port below the reserved rig range (>= $EMU_POOL_PORT_EXCLUDE_FROM)"
      return 1
    fi
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
  local lf serial owner alive mode sess since age
  local any=0
  for lf in "$EMU_LEASE_DIR"/*.lease; do
    [[ -e "$lf" ]] || continue
    any=1
    serial="$(basename "$lf" .lease)"
    owner="$(emu_lease_owner "$serial")"
    if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then alive="live"; else alive="ownerless"; fi
    mode="$(_emu_lease_field "$serial" mode)"
    if [[ "$mode" == "sticky" ]]; then
      sess="$(_emu_lease_field "$serial" session)"
      since="$(_emu_lease_field "$serial" since)"
      age="?"
      [[ "$since" =~ ^[0-9]+$ ]] && age="$(( ( $(date +%s) - since ) / 60 ))m"
      if emu_lease_expired "$serial"; then alive="EXPIRED — reclaimable"; fi
      _emu_log "  lease ${serial} -> pid ${owner:-?} (${alive}, STICKY session '${sess:-none}', age ${age}, ttl ${EMU_LEASE_STICKY_TTL}s)"
    else
      _emu_log "  lease ${serial} -> pid ${owner:-?} (${alive})"
    fi
  done
  [[ "$any" -eq 0 ]] && _emu_log "  no active leases"
}
