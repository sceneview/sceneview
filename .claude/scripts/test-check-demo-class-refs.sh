#!/usr/bin/env bash
#
# test-check-demo-class-refs.sh — self-test for check-demo-class-refs.sh.
#
# A regressed guard that silently PASSes is worse than none — it gives a false
# sense of coverage. This pins the guard's contract (catches stale refs, ignores
# legitimately-current ones, advisory by default, --fail gates) so `repo-hygiene`
# can trust the advisory step. Same pattern as test-check-doc-drift.sh /
# test-validate-demo-assets.sh.
#
# The guard scans real prose surfaces; the tests inject a temp fixture via the
# DEMO_REF_FILES env override so they never depend on the working tree's docs.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/check-demo-class-refs.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

TMPDIR_T="$(mktemp -d)"
trap 'rm -f "$TMPDIR_T"/*; rmdir "$TMPDIR_T" 2>/dev/null || true' EXIT

# Run the guard against an injected fixture file; capture output + exit code.
run() { # fixture-content extra-args... -> sets OUT, RC
    local content="$1"; shift || true
    local fixture="$TMPDIR_T/fixture.md"
    printf '%s\n' "$content" > "$fixture"
    set +e
    OUT="$(cd "$ROOT" && DEMO_REF_FILES="$fixture" bash "$SCRIPT" "$@" 2>&1)"; RC=$?
    set -e
}

echo "test-check-demo-class-refs.sh"

# 1. A live Android demo class → clean, exit 0.
run 'Use the `ModelViewerDemo` and `AnimationPhysicsDemo` composables.'
{ [[ $RC -eq 0 ]] && grep -q "No stale demo" <<<"$OUT"; } \
    && ok "live demo classes → no finding" \
    || bad "live demo classes should be clean (rc=$RC): $OUT"

# 2. A deleted demo class → flagged, advisory exit 0.
run 'The old `GhostlyDeletedDemo` is gone.'
{ [[ $RC -eq 0 ]] && grep -q "stale demo class" <<<"$OUT"; } \
    && ok "deleted demo class → STALE finding, advisory exit 0" \
    || bad "deleted demo class should be flagged but not fail (rc=$RC): $OUT"

# 3. --fail promotes a finding to a non-zero exit.
run 'The old `GhostlyDeletedDemo` is gone.' --fail
[[ $RC -ne 0 ]] \
    && ok "--fail turns a finding into exit!=0" \
    || bad "--fail should exit non-zero on a finding (rc=$RC)"

# 4. An EXISTING iOS .swift demo ref → NOT flagged (precision: iOS demos are
#    legitimately separate even where Android consolidated them).
if [ -f "$ROOT/samples/ios-demo/SceneViewDemo/Views/Demos/AnimationDemo.swift" ]; then
    run 'See [`AnimationDemo.swift`](.../Demos/AnimationDemo.swift) — the iOS animation demo.'
    { [[ $RC -eq 0 ]] && grep -q "No stale demo" <<<"$OUT"; } \
        && ok "existing iOS .swift ref → not flagged" \
        || bad "existing iOS .swift ref must not be flagged (rc=$RC): $OUT"
else
    printf '  ⊝ iOS demo not checked out — skipping .swift-precision case\n'
fi

# 5. A MISSING .swift / .kt demo source ref → flagged.
run 'Broken link to `NoSuchDemo.swift` and `AlsoGoneDemo.kt`.'
{ grep -q "dead demo source reference 'NoSuchDemo.swift'" <<<"$OUT" \
    && grep -q "dead demo source reference 'AlsoGoneDemo.kt'" <<<"$OUT"; } \
    && ok "missing .swift/.kt source ref → flagged" \
    || bad "missing source refs should be flagged: $OUT"

# 6. A retired demo id in the sceneview://demo/ deep-link form → flagged.
run 'The old deep link `sceneview://demo/gesture-editing` no longer routes.'
grep -q "retired demo id 'sceneview://demo/gesture-editing'" <<<"$OUT" \
    && ok "retired demo id in deep-link form → flagged" \
    || bad "retired demo id should be flagged: $OUT"

# 7. PRECISION: a retired id as a plain prose phrase (NOT the deep-link form)
#    must NOT be flagged — e.g. "the gesture-editing API". A false positive here
#    would erode trust in the advisory.
run 'SceneViews gesture-editing API replaces Sceneforms TransformableNode.'
{ [[ $RC -eq 0 ]] && grep -q "No stale demo" <<<"$OUT"; } \
    && ok "retired id as prose phrase (no deep link) → not flagged" \
    || bad "plain prose phrase must not false-positive (rc=$RC): $OUT"

# 8. PRECISION: a live deep-link id is fine.
LIVE_LINK="sceneview://demo/model-viewer"
run "Open it with \`$LIVE_LINK\`."
{ [[ $RC -eq 0 ]] && grep -q "No stale demo" <<<"$OUT"; } \
    && ok "live deep-link id → not flagged" \
    || bad "live deep-link id must not be flagged (rc=$RC): $OUT"

# 9. PRECISION: a bare `demo/...` substring inside a source PATH must NOT be
#    flagged (only the full sceneview://demo/ scheme is an id reference).
run 'Edit `samples/android-demo/src/main/.../demo/demos/Foo.kt` to add a demo.'
{ [[ $RC -eq 0 ]] && grep -q "No stale demo" <<<"$OUT"; } \
    && ok "bare demo/ path substring → not flagged" \
    || bad "source path with demo/ must not false-positive (rc=$RC): $OUT"

# 10. --list runs and prints the live sets.
set +e; OUT="$(cd "$ROOT" && bash "$SCRIPT" --list 2>&1)"; RC=$?; set -e
{ [[ $RC -eq 0 ]] && grep -q "Live demo classes" <<<"$OUT"; } \
    && ok "--list prints the live sets and exits 0" \
    || bad "--list should print the live sets (rc=$RC)"

echo
printf 'PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
