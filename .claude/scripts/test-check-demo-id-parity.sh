#!/usr/bin/env bash
#
# test-check-demo-id-parity.sh — self-test for check-demo-id-parity.sh (#2801).
#
# A regressed parity gate that silently PASSes is worse than none — it gives
# a false sense of coverage on the exact drift class (#2769) it exists to
# prevent. This pins the gate's contract in a fully isolated scratch
# directory (never the real repo tree) via the PARITY_* env-var injection
# seams, same self-test-first discipline as test-check-doc-drift.sh /
# test-web-dts.sh / test-store-preflight.sh in `repo-hygiene`.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/check-demo-id-parity.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-check-demo-id-parity.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

FRAG_DIR="$SCRATCH/fragments"
mkdir -p "$FRAG_DIR"

# Minimal fixtures matching only the fields check-demo-id-parity.sh itself
# greps for — not real, compilable Kotlin/Swift (PARITY_SKIP_*_COLLATE=1
# means the real collate-*.sh scripts never run against these).
write_fragment() { # id -> writes a *Fragment.kt with that id
    local id="$1" name="$2"
    cat > "$FRAG_DIR/${name}Fragment.kt" <<EOF
object ${name}Fragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "$id",
        category = DemoCategory.BASICS_3D,
    )
}
EOF
}

write_ios_generated() { # writes a GeneratedScenes.swift-shaped fixture
    # $1 = space-separated "real" ids (in both allowedIds and the switch)
    # $2 = space-separated "coming-soon" ids (in allowedIds, NOT in the switch)
    local real_ids="$1" comingsoon_ids="$2"
    {
        echo "enum GeneratedScenes {"
        echo "    static let allowedIds: Set<String> = ["
        for id in $real_ids $comingsoon_ids; do echo "        \"$id\","; done
        echo "    ]"
        echo "    static func destination(for id: String) -> AnyView? {"
        echo "        switch id {"
        for id in $real_ids; do echo "        case \"$id\": return SomeScene.destination"; done
        echo "        default: return nil"
        echo "        }"
        echo "    }"
        echo "}"
    } > "$SCRATCH/GeneratedScenes.swift"
}

write_ios_registry() { # writes a DemoDeepLinkRegistry.swift-shaped fixture
    # $1 = space-separated "key=value" alias pairs (may be empty)
    # $2 = space-separated residual ids (may be empty)
    local aliases="$1" residual="$2"
    {
        echo "enum DemoDeepLinkRegistry {"
        echo "    static let legacyAliases: [String: String] = ["
        for pair in $aliases; do
            key="${pair%%=*}"; val="${pair#*=}"
            echo "        \"$key\": \"$val\","
        done
        echo "    ]"
        echo "    static let residualIds: Set<String> = ["
        for id in $residual; do echo "        \"$id\","; done
        echo "    ]"
        echo "}"
    } > "$SCRATCH/DemoDeepLinkRegistry.swift"
}

write_manifest() { # writes parity-manifest.yml from stdin (heredoc body)
    cat > "$SCRATCH/parity-manifest.yml"
}

run() { # -> sets OUT, RC
    set +e
    OUT="$(
        PARITY_ANDROID_FRAG_DIR="$FRAG_DIR" \
        PARITY_IOS_REGISTRY="$SCRATCH/DemoDeepLinkRegistry.swift" \
        PARITY_IOS_GENERATED="$SCRATCH/GeneratedScenes.swift" \
        PARITY_MANIFEST="$SCRATCH/parity-manifest.yml" \
        PARITY_SKIP_ANDROID_COLLATE=1 \
        PARITY_SKIP_IOS_COLLATE=1 \
        bash "$SCRIPT" 2>&1
    )"; RC=$?
    set -e
}

# ─── 1. Happy path: one working id, correctly declared → PASS ────────────
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_ios_generated "model-viewer" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "matching working id → PASS" \
    || bad "matching working id should PASS (rc=$RC): $OUT"

# ─── 2. The #2769 drift class: new Android id, no iOS entry, no manifest row ──
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "brand-new-demo" "BrandNew"
write_ios_generated "model-viewer" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "NEITHER an iOS registry entry NOR a parity-manifest.yml row" <<<"$OUT" \
    && grep -q "brand-new-demo" <<<"$OUT"; } \
    && ok "undeclared new Android id → FAIL (the #2769 drift class)" \
    || bad "undeclared new Android id should FAIL (rc=$RC): $OUT"

# ─── 3. Android id present in iOS registry, but manifest doesn't mention it ──
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "ported-demo" "Ported"
write_ios_generated "model-viewer ported-demo" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "no parity-manifest.yml row" <<<"$OUT" && grep -q "ported-demo" <<<"$OUT"; } \
    && ok "id in iOS registry but missing manifest row → FAIL" \
    || bad "id in iOS registry but missing manifest row should FAIL (rc=$RC): $OUT"

# ─── 4. Manifest overclaims 'working' for a coming-soon (non-real) id ────
rm -f "$FRAG_DIR"/*.kt
write_fragment "stub-demo" "Stub"
write_ios_generated "" "stub-demo"
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: stub-demo
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "does not actually resolve it to a real destination" <<<"$OUT"; } \
    && ok "manifest overclaims 'working' for a placeholder id → FAIL" \
    || bad "overclaimed 'working' should FAIL (rc=$RC): $OUT"

# ─── 5. Manifest correctly declares 'stub' for a coming-soon id → PASS ───
rm -f "$FRAG_DIR"/*.kt
write_fragment "stub-demo" "Stub"
write_ios_generated "" "stub-demo"
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: stub-demo
    androidStatus: Working
    iosStatus: stub
    reason: "not yet ported"
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "correctly-declared stub id → PASS" \
    || bad "correctly-declared stub id should PASS (rc=$RC): $OUT"

# ─── 6. Manifest says 'android-only' but the id IS in iOS's allowedIds (stale) ──
rm -f "$FRAG_DIR"/*.kt
write_fragment "now-ported" "NowPorted"
write_ios_generated "now-ported" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: now-ported
    androidStatus: Working
    iosStatus: android-only
    reason: "not yet ported"
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "IS present in iOS's allowedIds today" <<<"$OUT"; } \
    && ok "stale 'android-only' row (id now in iOS registry) → FAIL" \
    || bad "stale 'android-only' row should FAIL (rc=$RC): $OUT"

# ─── 7. Manifest row for an id that's no longer a live Android id ────────
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_ios_generated "model-viewer" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
  - id: renamed-away-demo
    androidStatus: Working
    iosStatus: android-only
    reason: "stale"
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "is not a current Android canonical id" <<<"$OUT" \
    && grep -q "renamed-away-demo" <<<"$OUT"; } \
    && ok "stale manifest row (id no longer on Android) → FAIL" \
    || bad "stale manifest row should FAIL (rc=$RC): $OUT"

# ─── 8. Non-working row missing a 'reason' → FAIL ─────────────────────────
rm -f "$FRAG_DIR"/*.kt
write_fragment "stub-demo" "Stub"
write_ios_generated "" "stub-demo"
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  - id: stub-demo
    androidStatus: Working
    iosStatus: stub
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "no 'reason'" <<<"$OUT"; } \
    && ok "non-working row without a reason → FAIL" \
    || bad "non-working row without a reason should FAIL (rc=$RC): $OUT"

# ─── 9. A legacy-alias id inherits its canonical target's realness → PASS ──
# (exercises the alias-resolution branch of ios_is_real, not just direct ids)
rm -f "$FRAG_DIR"/*.kt
write_fragment "canonical-demo" "Canonical"
write_ios_generated "canonical-demo" ""
write_ios_registry "retired-alias=canonical-demo" ""
write_manifest <<'EOF'
demos:
  - id: canonical-demo
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "id resolved as working (alias-target realness path exercised) → PASS" \
    || bad "alias-target realness path should still PASS (rc=$RC): $OUT"

# ─── 10. Collapsed single-line residualIds (`= []`) must not leak ─────────
# Regression for the awk block-extractor leak (reviewer catch on PR #2830):
# a `residualIds` written on ONE line as `= []` used to leave the awk state
# machine "open", slurping quoted ids from an UNRELATED array further down
# the registry into the residual set — inflating allowedIds and
# false-failing the BLOCKING gate. L0.6 (#2804) shrinks residualIds toward
# [] as it ports each id, so this MUST be correct before then. The
# write_ios_registry helper only ever emits multi-line arrays (the gap that
# let the bug through), so this case writes the registry by hand: a collapsed
# residualIds followed by a decoy array whose id (`future-demo`) leaks into
# allowedIds WITHOUT the fix and there collides with its `android-only`
# manifest row. Asserts PASS; without the fix this fixture FAILS.
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "future-demo" "Future"
write_ios_generated "model-viewer" ""
cat > "$SCRATCH/DemoDeepLinkRegistry.swift" <<'EOF'
enum DemoDeepLinkRegistry {
    static let legacyAliases: [String: String] = [
    ]
    static let residualIds: Set<String> = []
    static let unrelatedIds: Set<String> = [
        "future-demo",
    ]
}
EOF
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
  - id: future-demo
    androidStatus: Working
    iosStatus: android-only
    reason: "not yet ported"
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "collapsed single-line residualIds (= []) does not leak an unrelated id → PASS" \
    || bad "collapsed single-line residualIds must not leak (rc=$RC): $OUT"

# ─── 11. Collapsed single-line legacyAliases (`= [:]`) must not leak ──────
# Same class as case 10, but for the DICTIONARY extractor — and it also pins
# the trickiest part of the fix: `legacyAliases`'s `[String: String]` TYPE
# annotation itself contains a `]`, so a naive "does the opening line contain
# a `]`" guard would misclassify the NORMAL multi-line opening as single-line
# and break every multi-line alias table (see case 9). The fix strips the
# line up to the assignment `= [` first, so only a `]` AFTER the opener counts.
# Here legacyAliases is collapsed to `= [:]`, followed by a decoy DICTIONARY
# whose two-quotes-on-a-line entry would be slurped as a spurious alias key
# (`spoof-key`) WITHOUT the fix, leaking it into allowedIds and colliding with
# its `android-only` manifest row. Asserts PASS; without the fix this FAILS.
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "spoof-key" "Spoof"
write_ios_generated "model-viewer" ""
cat > "$SCRATCH/DemoDeepLinkRegistry.swift" <<'EOF'
enum DemoDeepLinkRegistry {
    static let legacyAliases: [String: String] = [:]
    static let someOtherMap: [String: String] = [
        "spoof-key": "spoof-target",
    ]
    static let residualIds: Set<String> = [
    ]
}
EOF
write_manifest <<'EOF'
demos:
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
  - id: spoof-key
    androidStatus: Working
    iosStatus: android-only
    reason: "not yet ported"
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "collapsed single-line legacyAliases (= [:]) does not leak, multi-line still works → PASS" \
    || bad "collapsed single-line legacyAliases must not leak (rc=$RC): $OUT"

# ─── 12. A section banner whose count disagrees with the rows → FAIL ──────
# The Wave-A drift class (#2798): the `# ─── working (N) ───` banners are
# COMMENTS, so yaml.safe_load never sees them and a stale N used to pass every
# other check in the script. Two rows are `working`, the banner claims one.
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "animation-physics" "AnimationPhysics"
write_ios_generated "model-viewer animation-physics" ""
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  # ─── working (1) — iOS has a real, non-placeholder destination ─────────
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
  - id: animation-physics
    androidStatus: Working
    iosStatus: working
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "section banner" <<<"$OUT" \
    && grep -q "says (1) but 2 row(s)" <<<"$OUT"; } \
    && ok "stale section-banner tally → FAIL (the Wave-A drift class)" \
    || bad "stale section-banner tally should FAIL (rc=$RC): $OUT"

# ─── 13. Correct banners → PASS (the gate does not fire on an honest file) ──
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "animation-physics" "AnimationPhysics"
write_ios_generated "model-viewer" "animation-physics"
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  # ─── working (1) — iOS has a real, non-placeholder destination ─────────
  - id: model-viewer
    androidStatus: Working
    iosStatus: working

  # ─── stub (1) — iOS registry has the id, but it falls to the placeholder ──
  - id: animation-physics
    androidStatus: Working
    iosStatus: stub
    reason: "Scene file exists but @available false"
EOF
run
{ [[ $RC -eq 0 ]] && grep -q "check-demo-id-parity.sh: OK" <<<"$OUT"; } \
    && ok "accurate section banners → PASS" \
    || bad "accurate section banners should PASS (rc=$RC): $OUT"

# ─── 14. Deleting a stale banner must not dodge the gate → FAIL ───────────
# Once ANY banner exists, every non-empty status needs one — otherwise the
# cheapest way to "fix" a failing tally is to delete the banner, which throws
# the information away instead of correcting it.
rm -f "$FRAG_DIR"/*.kt
write_fragment "model-viewer" "ModelViewer"
write_fragment "animation-physics" "AnimationPhysics"
write_ios_generated "model-viewer" "animation-physics"
write_ios_registry "" ""
write_manifest <<'EOF'
demos:
  # ─── working (1) — iOS has a real, non-placeholder destination ─────────
  - id: model-viewer
    androidStatus: Working
    iosStatus: working
  - id: animation-physics
    androidStatus: Working
    iosStatus: stub
    reason: "Scene file exists but @available false"
EOF
run
{ [[ $RC -ne 0 ]] && grep -q "banners but none for 'stub'" <<<"$OUT"; } \
    && ok "deleting a banner to dodge the tally gate → FAIL" \
    || bad "missing banner for a non-empty status should FAIL (rc=$RC): $OUT"

# ─── Summary ───────────────────────────────────────────────────────────────
echo ""
echo "test-check-demo-id-parity.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
