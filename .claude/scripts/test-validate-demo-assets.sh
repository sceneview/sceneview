#!/usr/bin/env bash
# Self-test for validate-demo-assets.sh's could-not-run contract (#3192).
#
# A missing `curl` used to come back as HTTP 000 on every CDN URL, which the
# validator's transient classifier read as a rate limit: 23 s of backoff per
# URL, every reference reported "not checked (transient)", exit 0. The gate
# must instead refuse to run (exit 2) and name the tool — and `--no-cdn`,
# which never touches curl, must not trip that guard. curl is hidden by running
# the validator under a PATH that mirrors the real one minus `curl`, so the
# result does not depend on what this host has installed.
#
# Usage: bash .claude/scripts/test-validate-demo-assets.sh
set -uo pipefail

GATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/validate-demo-assets.sh"
[ -f "$GATE" ] || { echo "test-validate-demo-assets: gate not found at $GATE" >&2; exit 2; }

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; NC=$'\033[0m'
if [ ! -t 1 ]; then GREEN=""; RED=""; NC=""; fi
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ${GREEN}✓${NC} $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  ${RED}✗${NC} $1"; }

# ── a PATH with everything the real one has, except curl ─────────────────────
NOCURL="$(mktemp -d)"
trap 'rm -rf "$NOCURL"' EXIT
IFS=: read -r -a _dirs <<< "$PATH"
for _p in "${_dirs[@]}"; do
    [ -d "$_p" ] || continue
    for _f in "$_p"/*; do
        _b="$(basename "$_f")"
        [ "$_b" = "curl" ] && continue
        [ -e "$NOCURL/$_b" ] || ln -s "$_f" "$NOCURL/$_b" 2>/dev/null || true
    done
done
if env PATH="$NOCURL" bash -c 'command -v curl' >/dev/null 2>&1; then
    echo "test-validate-demo-assets: could not build a PATH without curl" >&2
    exit 2
fi

# `--rn` keeps the scan itself to the smallest platform; the guard under test
# fires before any scanning, so the platform only matters for the second case.
run_gate() { OUT="$(env PATH="$NOCURL" bash "$GATE" "$@" 2>&1)"; RC=$?; }

echo "test-validate-demo-assets"
echo ""

# ── 1. no curl, CDN checks on → could not run ────────────────────────────────
run_gate --rn
if [ "$RC" -eq 2 ] && printf '%s' "$OUT" | grep -q "CANNOT RUN: 'curl'"; then
    ok "without curl the validator refuses to run (exit 2) and names the tool"
else
    bad "without curl: expected exit 2 + CANNOT RUN line, got rc=$RC: $(printf '%s' "$OUT" | tail -3 | tr '\n' ' ')"
fi
if ! printf '%s' "$OUT" | grep -q 'CDN refs checked'; then
    ok "the refusal comes before any scan — no summary is printed"
else
    bad "a summary was printed by a run that could not check anything"
fi

# ── 2. no curl, --no-cdn → the guard stays out of the way ────────────────────
# rc is whatever the tree earns today; only the could-not-run outcome is
# asserted against.
run_gate --rn --no-cdn
if [ "$RC" -ne 2 ] && ! printf '%s' "$OUT" | grep -q 'CANNOT RUN'; then
    ok "--no-cdn does not need curl and is not refused (rc=$RC)"
else
    bad "--no-cdn was refused without curl (rc=$RC)"
fi

echo ""
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
