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

# ─── Summary ───────────────────────────────────────────────────────────────
echo ""
echo "test-check-demo-id-parity.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
