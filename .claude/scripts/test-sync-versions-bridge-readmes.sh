#!/usr/bin/env bash
#
# test-sync-versions-bridge-readmes.sh — self-test for how sync-versions.sh
# treats the two bridge plugins' README install versions.
#
# Same blind spot as the Kotlin rewriter (see test-sync-versions-kotlin.sh):
# the RN handler only fires when the README prose has drifted, so the normal
# in-tree run — where everything is already at VERSION_NAME — never executes
# it. A broken sed would stay green until the next release bump, which is
# exactly how #2790/#2876 played out for the Kotlin handler.
#
# The two slots are deliberately asymmetric, and BOTH halves are pinned here:
#   * RN SwiftPM `- Version: \`X.Y.Z\`` IS swept to VERSION_NAME. SPM resolves
#     against a git tag the release itself creates, so racing ahead is safe.
#   * Flutter `flutter_sceneview: ^X.Y.Z` is NOT — it is a caret range against
#     a version that must already be live on pub.dev, which lags. Sweeping it
#     produced `^4.26.0` against a registry whose newest was 4.24.0, which
#     resolves to nothing and fails `flutter pub get`. There is no `--fix`
#     handler for it, and a regression guard asserts the absence of one.
#   * the DATED versions living in the same files are never touched — the
#     `v4.3.0` feature notes, and the "at tags `vX.Y.Z` and earlier the package
#     name was `sceneview_flutter`" fact about the #2735 rename. A sweep that
#     bumped that sentence would turn a true statement into a false one, which
#     is worse than the drift the handler exists to fix.
#
# Hermetic: copies sync-versions.sh into a scratch repo. The script derives
# REPO_ROOT from its own path, so the real tree is never touched.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/.claude/scripts/sync-versions.sh"
PASS=0; FAIL=0
ok()  { printf '  \xE2\x9C\x93 %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \xE2\x9C\x97 %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-sync-versions-bridge-readmes.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Fixture: only the files the two README check+fix paths read. Every other
# version surface is absent and thus SKIPped, so the sole possible mismatches
# are the two README prose slots. website-static/ exists to satisfy the fix
# block's unguarded cache-buster `find` (pre-existing, unrelated).
make_fixture() { # dir version rn_readme_version flutter_readme_version
    local dir="$1" version="$2" rn="$3" fl="$4"
    mkdir -p "$dir/.claude/scripts" "$dir/website-static" \
             "$dir/react-native/react-native-sceneview" \
             "$dir/flutter/sceneview_flutter"
    cp "$SRC" "$dir/.claude/scripts/"
    printf 'VERSION_NAME=%s\n' "$version" > "$dir/gradle.properties"

    cat > "$dir/react-native/react-native-sceneview/README.md" <<EOF
- URL: \`https://github.com/sceneview/sceneview\`
- Version: \`$rn\` (or *Up to Next Major*)

\`cameraControlMode\` \`'pan'\` is iOS-only in v4.3.0; on Android it falls back.

### AR recording (v4.3.0 — iOS)
EOF

    # The frozen rename sentence deliberately carries THE SAME version as the
    # drifted install snippet. That is the historical worst case — the real
    # file had `^4.23.0` next to "at tags v4.23.0 and earlier" — and it is the
    # only shape that can catch a de-anchored sed: a sweep keyed on the
    # snippet's own version would silently rewrite the sentence too. With two
    # different versions here the fixture cannot discriminate, and this test
    # passed against a deliberately broken global sed.
    cat > "$dir/flutter/sceneview_flutter/README.md" <<EOF
dependencies:
  flutter_sceneview: ^$fl

Or as a Git dependency (note: at tags \`v$fl\` and earlier the package name
was \`sceneview_flutter\` — the dependency key must match the name at the ref):

### Camera controls & content centring (v4.3.0)
EOF
}

# ── 1. The RN SwiftPM slot IS swept to VERSION_NAME; the Flutter pub snippet
#       is NOT. The asymmetry is the point: SPM resolves against a git tag the
#       release itself creates, so racing ahead is harmless, while the Flutter
#       caret range must name a version already live on pub.dev. ─────────────
# The RN slot's drifted version is deliberately 4.3.0 — the SAME number the
# file's dated feature notes carry (`v4.3.0`). That collision is what makes the
# "notes left alone" check below able to fire: with a non-colliding version
# (this test shipped 4.14.0 first) an anchored sed and a de-anchored
# `s/$CURRENT/$SOURCE_VERSION/g` produce byte-identical output, so the guard
# passed against a broken handler. Same treatment the Flutter fixture already
# had; the RN half had not received it.
D="$WORK/drift"
make_fixture "$D" "9.9.9" "4.3.0" "4.23.0"
set +e; bash "$D/.claude/scripts/sync-versions.sh" --fix >/dev/null 2>&1; set -e

RN_README="$D/react-native/react-native-sceneview/README.md"
FL_README="$D/flutter/sceneview_flutter/README.md"

grep -q '^- Version: `9.9.9` (or \*Up to Next Major\*)$' "$RN_README" \
    && ok "RN README: SwiftPM version 4.3.0 -> 9.9.9, '(or *Up to Next Major*)' intact" \
    || bad "RN README: SwiftPM version not rewritten or trailing prose clobbered"

grep -q '^- Version: `4.3.0`' "$RN_README" \
    && bad "stale RN SwiftPM version still on the Version: line after --fix" \
    || ok "stale RN SwiftPM version gone from the Version: line"

# THE regression guard for the bug this test's first version shipped: sweeping
# this slot to VERSION_NAME produced `^4.26.0` while pub.dev's newest was
# 4.24.0, turning a resolvable caret range into one that matches nothing and
# fails `flutter pub get`. It must stay exactly where the author left it.
grep -q 'flutter_sceneview: \^4.23.0' "$FL_README" \
    && ok "Flutter README: pub snippet NOT swept to VERSION_NAME (pub.dev lags)" \
    || bad "Flutter README: pub snippet was bumped past what pub.dev serves"

# ── 2. The DATED versions in the same files are untouched. This is the whole
#       reason the RN handler is anchored on its own line shape. ─────────────
# The frozen sentence carries the same version as the install snippet, so a
# de-anchored sed keyed on that version would rewrite it into "at tags v9.9.9
# and earlier" — a statement about the #2735 rename that is simply false.
grep -q 'at tags `v4.23.0` and earlier' "$FL_README" \
    && ok "Flutter README: frozen rename fact untouched" \
    || bad "Flutter README: the frozen rename fact was rewritten into a falsehood"

[ "$(grep -c 'v4\.3\.0' "$RN_README")" -eq 2 ] \
    && ok "RN README: both v4.3.0 feature notes left alone" \
    || bad "RN README: a v4.3.0 feature note was clobbered by the version sweep"

[ "$(grep -c 'v4\.3\.0' "$FL_README")" -eq 1 ] \
    && ok "Flutter README: v4.3.0 feature note left alone" \
    || bad "Flutter README: the v4.3.0 feature note was clobbered"

# ── 3. A re-run in check-only mode is clean. ────────────────────────────────
set +e; bash "$D/.claude/scripts/sync-versions.sh" >/dev/null 2>&1; RC=$?; set -e
[ "$RC" -eq 0 ] \
    && ok "re-run check-only exits 0 (no residual mismatch)" \
    || bad "re-run check-only still non-zero (rc=$RC)"

# ── 4. Already-aligned prose is a no-op even while the fix block runs. A
#       module VERSION_NAME mismatch forces ERRORS>0 so `--fix` executes; the
#       `!= $SOURCE_VERSION` guards must still skip both rewriters. ──────────
A="$WORK/aligned"
make_fixture "$A" "7.7.7" "7.7.7" "7.7.7"
mkdir -p "$A/sceneview"
printf 'VERSION_NAME=2.0.0\n' > "$A/sceneview/gradle.properties"   # forces a mismatch
set +e; ALIGN_OUT="$(bash "$A/.claude/scripts/sync-versions.sh" --fix 2>&1)"; set -e

# Anchored on the `Fixed:` prefix: the check table prints these labels on
# every run, so matching a label alone would flag a plain OK row as a rewrite
# (it did, while this test was being written).
if grep -qE '^ *Fixed:.*(react-native|flutter)/.*README.md' <<<"$ALIGN_OUT"; then
    bad "a README rewriter fired on already-aligned prose"
else
    ok "aligned prose → no spurious rewrite (guards hold while fix block runs)"
fi

grep -q '^- Version: `7.7.7` (or \*Up to Next Major\*)$' "$A/react-native/react-native-sceneview/README.md" \
    && ok "aligned RN README left byte-for-byte unchanged" \
    || bad "aligned RN README was mutated"
grep -q 'flutter_sceneview: \^7.7.7' "$A/flutter/sceneview_flutter/README.md" \
    && ok "aligned Flutter README left byte-for-byte unchanged" \
    || bad "aligned Flutter README was mutated"

echo ""
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
