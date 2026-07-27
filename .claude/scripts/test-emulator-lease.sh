#!/usr/bin/env bash
#
# test-emulator-lease.sh — self-test for the pool lease contract in
# lib/emulator-select.sh.
#
# #2862: a provisioned emulator ended up with NO lease. `setup-ar-emulator.sh`
# leases by pid and drops it in an EXIT trap, but its whole job is to provision
# and hand back — the emulator outlives the script. The moment it exited, the
# running emulator looked free to every peer session, which would then install
# APKs and inject gestures into an AVD another session was interpreting.
#
# The behaviours pinned here are the ones a regression would silently undo:
#   1. a sticky lease survives its taker's exit (the fix itself);
#   2. a peer session cannot take it, and says so;
#   3. the same session can;
#   4. an expired sticky lease is reclaimed (no permanent pool deadlock);
#   5. an emulator whose AVD is not the pool AVD is never leasable;
#   6. a plain pid lease still behaves exactly as before (no regression);
#   7. the handoff token is inherited AT MOST once;
#   8. a LIVE plain-pid lease blocks a peer acquire, and is takeable again once
#      its owner dies — the standalone qa-android-demos.sh / ar-replay-qa.sh
#      hold-the-lease fix (#2862 follow-up).
#
# Hermetic: a stub `adb` and a scratch EMU_LEASE_DIR. No emulator, no SDK, no
# host state touched — safe to run anywhere, including CI.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
LIB="$ROOT/.claude/scripts/lib/emulator-select.sh"
PASS=0; FAIL=0
ok()  { printf '  \xE2\x9C\x93 %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \xE2\x9C\x97 %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-emulator-lease.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Stub adb: reports one running emulator and an AVD name driven by STUB_AVD.
cat > "$WORK/bin/adb" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "devices" ]; then
  printf 'List of devices attached\n%s\tdevice\n' "${STUB_SERIAL:-emulator-5554}"
  exit 0
fi
if [ "${1:-}" = "-s" ] && [ "${3:-}" = "emu" ] && [ "${4:-}" = "avd" ]; then
  echo "${STUB_AVD:-Pixel_7a}"
  echo "OK"
  exit 0
fi
exit 0
STUB
chmod +x "$WORK/bin/adb"
ADB="$WORK/bin/adb"

export EMU_LEASE_DIR="$WORK/leases"
export STUB_AVD="Pixel_7a"
# shellcheck disable=SC1090
source "$LIB"

# Run a snippet in a subshell that exits — the provisioning-script lifecycle.
# Everything the real script does around the lease is reproduced: same EXIT
# trap, same lease dir, same session token.
provision_and_exit() { # session_token [extra_commands]
    EMU_LEASE_SESSION="$1" bash -c '
      export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"
      source "'"$LIB"'"
      trap "emu_lease_release_all" EXIT
      emu_lease_acquire emulator-5554 "'"$ADB"'" >/dev/null 2>&1
      emu_lease_mark_sticky emulator-5554 "setup-ar-emulator" >/dev/null 2>&1
      '"${2:-}"'
    ' >/dev/null 2>&1
}

# ── 1. The fix: a sticky lease survives the process that took it. ───────────
provision_and_exit "sv-test-A"
if [ -f "$EMU_LEASE_DIR/emulator-5554.lease" ]; then
    ok "sticky lease survives the provisioning script's EXIT trap"
else
    bad "sticky lease was wiped on exit — #2862 regression"
fi

# ── 2. A peer session (different token) must NOT be handed that emulator. ──
(
  export EMU_LEASE_SESSION="sv-test-B"
  if peer="$(emu_lease_free_serial "$ADB")"; then
      echo "FREE:$peer"
  else
      echo "RESERVED"
  fi
) > "$WORK/peer.out"
if grep -qx 'RESERVED' "$WORK/peer.out"; then
    ok "peer session is not offered the reserved emulator"
else
    bad "peer session was offered $(cat "$WORK/peer.out") — the collision #2862 describes"
fi

EMU_LEASE_SESSION="sv-test-B" emu_lease_acquire emulator-5554 "$ADB" >/dev/null 2>&1 \
    && bad "peer session could ACQUIRE the reserved lease" \
    || ok "peer session cannot acquire the reserved lease"

# ── 3. The owning session still gets its own emulator back. ────────────────
(
  export EMU_LEASE_SESSION="sv-test-A"
  emu_lease_free_serial "$ADB"
) > "$WORK/owner.out" 2>/dev/null || true
if grep -qx 'emulator-5554' "$WORK/owner.out"; then
    ok "owning session is handed its own reserved emulator"
else
    bad "owning session was NOT offered its reservation (workflow broken)"
fi

# ── 4. An expired sticky lease is reclaimed — no permanent pool deadlock. ──
(
  export EMU_LEASE_STICKY_TTL=1
  # Backdate the reservation well past the TTL.
  sed 's/^since=.*/since=1/' "$EMU_LEASE_DIR/emulator-5554.lease" > "$WORK/tmp.lease"
  mv "$WORK/tmp.lease" "$EMU_LEASE_DIR/emulator-5554.lease"
  emu_pool_reclaim_stale "$ADB" >/dev/null 2>&1
  [ -f "$EMU_LEASE_DIR/emulator-5554.lease" ] && echo "KEPT" || echo "RECLAIMED"
) > "$WORK/ttl.out"
if grep -qx 'RECLAIMED' "$WORK/ttl.out"; then
    ok "expired sticky lease is reclaimed (pool cannot deadlock forever)"
else
    bad "expired sticky lease survived — a dead session would wedge the pool"
fi

# ── 5. Identity guard: a non-pool AVD on a pool port is never leasable. ────
rm -f "$EMU_LEASE_DIR"/*.lease 2>/dev/null || true
(
  export EMU_REQUIRE_AVD="Pixel_7a" STUB_AVD="Tablet10_QA"
  if wrong="$(emu_lease_free_serial "$ADB" 2>/dev/null)"; then
      echo "OFFERED:$wrong"
  else
      echo "REFUSED"
  fi
) > "$WORK/avd.out"
if grep -qx 'REFUSED' "$WORK/avd.out"; then
    ok "a stray non-pool AVD on a pool port is refused"
else
    bad "leased $(cat "$WORK/avd.out") despite the wrong AVD — QA would test the wrong device"
fi

(
  export EMU_REQUIRE_AVD="Pixel_7a" STUB_AVD="Pixel_7a"
  emu_lease_free_serial "$ADB" 2>/dev/null
) > "$WORK/avd-ok.out" || true
if grep -qx 'emulator-5554' "$WORK/avd-ok.out"; then
    ok "the real pool AVD is still leasable with the guard on"
else
    bad "identity guard rejects the pool AVD itself"
fi

# ── 6. No regression: a plain pid lease still dies with its process. ───────
rm -f "$EMU_LEASE_DIR"/*.lease 2>/dev/null || true
bash -c '
  export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"
  source "'"$LIB"'"
  trap "emu_lease_release_all" EXIT
  emu_lease_acquire emulator-5554 "'"$ADB"'"
' >/dev/null 2>&1
if [ -f "$EMU_LEASE_DIR/emulator-5554.lease" ]; then
    bad "plain pid lease outlived its process (unintended stickiness)"
else
    ok "plain pid lease still released on exit (unchanged behaviour)"
fi

# ── 7. The handoff token is inherited at most once. ────────────────────────
rm -f "$EMU_LEASE_DIR"/*.lease "$EMU_LEASE_DIR/handoff.session" 2>/dev/null || true
emu_lease_session_publish "sv-handoff-1"
FIRST="$(EMU_LEASE_SESSION="" bash -c '
  export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"; source "'"$LIB"'"
  emu_lease_session_inherit 2>/dev/null' || true)"
SECOND="$(EMU_LEASE_SESSION="" bash -c '
  export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"; source "'"$LIB"'"
  emu_lease_session_inherit 2>/dev/null' || true)"
[ "$FIRST" = "sv-handoff-1" ] \
    && ok "published session token is inherited by the next QA script" \
    || bad "published token was not inherited (got '$FIRST') — provision-then-QA broken"
[ -z "$SECOND" ] \
    && ok "the token is consumed — a second caller does not inherit it" \
    || bad "token inherited twice (got '$SECOND') — reservation would leak to peers"

# An expired handoff is not inherited at all.
emu_lease_session_publish "sv-handoff-2"
THIRD="$(EMU_LEASE_SESSION="" EMU_LEASE_HANDOFF_WINDOW=0 bash -c '
  export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"; source "'"$LIB"'"
  emu_lease_session_inherit 2>/dev/null' || true)"
[ -z "$THIRD" ] \
    && ok "handoff window of 0 disables inheritance" \
    || bad "token inherited with the window disabled (got '$THIRD')"

# ── 8. A live pid lease on an un-leased emulator blocks a peer acquire. ───
# The standalone qa-android-demos.sh / ar-replay-qa.sh fix (#2862 follow-up)
# rests on this: selecting a free serial is not enough — the run ACQUIRES it,
# and a second standalone run racing for the same un-leased emulator must be refused.
rm -f "$EMU_LEASE_DIR"/*.lease 2>/dev/null || true
sleep 30 & HOLDER_PID=$!
# A live pid owns a plain (non-sticky) lease on the un-leased emulator.
printf '%s\nmode=pid\nsession=\navd=Pixel_7a\nsince=%s\nlabel=\n' \
  "$HOLDER_PID" "$(date +%s)" > "$EMU_LEASE_DIR/emulator-5554.lease"
if EMU_LEASE_SESSION="" bash -c '
     export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"; source "'"$LIB"'"
     emu_lease_acquire emulator-5554 "'"$ADB"'"' >/dev/null 2>&1; then
    bad "a peer acquired an un-leased emulator already held by a live pid lease (collision)"
else
    ok "a live plain-pid lease blocks a peer acquire (standalone qa hold-lease fix)"
fi
kill "$HOLDER_PID" 2>/dev/null || true
wait "$HOLDER_PID" 2>/dev/null || true
# Owner now dead → the ownerless lease is takeable again (no permanent wedge).
if EMU_LEASE_SESSION="" bash -c '
     export EMU_LEASE_DIR="'"$EMU_LEASE_DIR"'"; source "'"$LIB"'"
     emu_lease_acquire emulator-5554 "'"$ADB"'"' >/dev/null 2>&1; then
    ok "once the holder dies the ownerless pid lease is takeable again"
else
    bad "ownerless pid lease not reclaimable after the holder died"
fi

echo ""
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
