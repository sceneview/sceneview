#!/usr/bin/env bash
#
# test-store-sync.sh — self-test for .claude/scripts/store-sync/ (#2612 P2 Phase A).
#
# The store-sync scripts are the single code path for the Play listing sync
# (play-store.yml calls play_listing.py --apply) and the ASC drift diff; a
# regressed script that silently PASSes — or a workflow that silently stops
# calling it — is worse than none. This pins the contract WITHOUT any live
# store credential:
#
#   - both scripts byte-compile (py_compile) — a syntax error must not wait
#     for the next release's sync-listing job to surface;
#   - the pure helpers' unit tests pass (unittest discover, offline — the
#     lazy third-party imports mean no google-auth/PyJWT/requests needed);
#   - a credential-less run SKIPs honestly with exit 0 in every mode
#     (advisory-first doctrine — never a fake green, never a spurious red);
#   - asc_listing.py rejects unknown/apply-style flags with exit 2 (the write
#     path is Phase B; a silent no-op would fake an upload);
#   - play-store.yml still calls play_listing.py --apply (the workflow↔script
#     seam can't drift apart unnoticed).
#
# The LIVE API path (network + real secrets) is intentionally NOT covered —
# same stance as test-store-preflight.sh. Runs in ci.yml → repo-hygiene.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SYNC_DIR="$ROOT/.claude/scripts/store-sync"
PASS=0; FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL + 1)); }

# Hermetic python invocation: no inherited store credentials, throwaway HOME.
run_py() {
  set +e
  OUT="$(cd "$ROOT" && env -i PATH="$PATH" HOME="$TMP" python3 "$@" 2>&1)"; RC=$?
  set -e
}

echo "test-store-sync.sh"

# 1. Byte-compile both scripts.
for script in play_listing.py asc_listing.py; do
  if python3 -m py_compile "$SYNC_DIR/$script" 2>/dev/null; then
    ok "$script byte-compiles"
  else
    bad "$script does not byte-compile"
  fi
done

# 2. Offline unit tests for the pure helpers.
run_py -m unittest discover -s "$SYNC_DIR/test" -v
if [ "$RC" -eq 0 ]; then
  ok "unit tests pass ($(printf '%s' "$OUT" | grep -c '^test_' || true) cases)"
else
  bad "unit tests failed:"
  printf '%s\n' "$OUT" | tail -20
fi

# 3. Credential-less runs SKIP honestly (exit 0 + explicit [skip] line).
run_py "$SYNC_DIR/play_listing.py" --dry-run
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "play_listing.py --dry-run without creds → honest SKIP, exit 0"
else
  bad "play_listing.py --dry-run without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

run_py "$SYNC_DIR/play_listing.py" --apply
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "play_listing.py --apply without creds → honest SKIP, exit 0"
else
  bad "play_listing.py --apply without creds → rc=$RC"
fi

run_py "$SYNC_DIR/asc_listing.py" --dry-run
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "asc_listing.py --dry-run without creds → honest SKIP, exit 0"
else
  bad "asc_listing.py --dry-run without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

# 4. The write path SKIPs honestly without creds too — a run that uploaded
#    nothing must never look like a successful upload.
run_py "$SYNC_DIR/asc_listing.py" --apply-screenshots
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "asc_listing.py --apply-screenshots without creds → honest SKIP, exit 0"
else
  bad "asc_listing.py --apply-screenshots without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

# 4b. A near-miss flag must NOT reach the App Store write path. argparse
#     expands unambiguous prefixes unless allow_abbrev=False, so `--apply`
#     (play_listing.py's real flag — the obvious thing to type by habit)
#     would otherwise upload screenshots.
for near_miss in --apply --appl --apply-screenshot; do
  run_py "$SYNC_DIR/asc_listing.py" "$near_miss"
  if [ "$RC" -eq 2 ] && printf '%s' "$OUT" | grep -q 'Unknown option'; then
    ok "asc_listing.py $near_miss → rejected, exit 2 (no accidental upload)"
  else
    bad "asc_listing.py $near_miss → rc=$RC (want 2 + 'Unknown option')"
  fi
done

# 4c. Same hardening on the Play side, where --apply is the real write flag.
run_py "$SYNC_DIR/play_listing.py" --appl
if [ "$RC" -ne 0 ]; then
  ok "play_listing.py --appl → rejected (no accidental Play Console write)"
else
  bad "play_listing.py --appl → rc=0; abbreviation reached the write path"
fi

# 4d. Reading and writing are mutually exclusive — an ambiguous invocation
#     must fail rather than silently pick one.
run_py "$SYNC_DIR/asc_listing.py" --dry-run --apply-screenshots
if [ "$RC" -eq 2 ]; then
  ok "asc_listing.py --dry-run --apply-screenshots → refused, exit 2"
else
  bad "asc_listing.py --dry-run --apply-screenshots → rc=$RC (want 2)"
fi

# 5. Workflow↔script seam: play-store.yml must still call the script.
if grep -q 'store-sync/play_listing\.py --apply' "$ROOT/.github/workflows/play-store.yml"; then
  ok "play-store.yml sync-listing calls play_listing.py --apply"
else
  bad "play-store.yml no longer references store-sync/play_listing.py --apply"
fi

# 5b. Same seam on the iOS side: app-store.yml's sync-screenshots job is the
#     only CI caller of the upload path, and it must stay dispatch-gated (a
#     screenshot upload silently joining the tag/release flow is exactly the
#     coupling Phase B avoided).
if grep -q 'store-sync/asc_listing\.py --apply-screenshots' "$ROOT/.github/workflows/app-store.yml"; then
  ok "app-store.yml sync-screenshots calls asc_listing.py --apply-screenshots"
else
  bad "app-store.yml no longer references asc_listing.py --apply-screenshots"
fi

if grep -q "inputs.sync_screenshots == 'true'" "$ROOT/.github/workflows/app-store.yml"; then
  ok "sync-screenshots job is still dispatch-gated"
else
  bad "sync-screenshots job lost its workflow_dispatch gate"
fi

# 6. The extracted script must not have re-grown an inline-heredoc twin: the
#    sync-listing job must not contain its own PYEOF python anymore.
if sed -n '/sync-listing:/,$p' "$ROOT/.github/workflows/play-store.yml" | grep -q 'PYEOF'; then
  bad "sync-listing job still contains an inline PYEOF heredoc"
else
  ok "sync-listing job has no inline python heredoc left"
fi

echo
echo "test-store-sync.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
