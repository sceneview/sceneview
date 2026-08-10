#!/usr/bin/env bash
#
# test-sync-versions-kotlin.sh — self-test for the Kotlin-toolchain `--fix`
# rewriter in sync-versions.sh.
#
# The rewriter fires only on a Kotlin toolchain bump — gradle/libs.versions.toml
# `kotlin = "X.Y.Z"` drifting from the `**Kotlin:** X.Y.Z` prose in llms.txt +
# docs/docs/llms-full.txt. That is rare, so quality-gate's normal in-tree run
# almost never exercises it: #2790 bumped the toml with no `--fix` handler,
# reddened pre-push-check leg 7, and forced a hand-edit of both files + a manual
# gpt/ regen (relived on #2876, 2026-07-23). This pins the handler's contract so
# a regression can't silently reintroduce that hand-edit trap.
#
# Hermetic: copies sync-versions.sh into a scratch repo. The script derives
# REPO_ROOT from its own path ($(dirname $0)/../..), so a copy under
# <scratch>/.claude/scripts/ makes the scratch the repo — the real tree is
# never touched.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/.claude/scripts/sync-versions.sh"
PASS=0; FAIL=0
ok()  { printf '  \xE2\x9C\x93 %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \xE2\x9C\x97 %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-sync-versions-kotlin.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Build a minimal fixture repo under $1 with a given (toml, prose) Kotlin pair.
# Only the files the Kotlin check+fix path reads are created; every other version
# surface is absent and thus SKIPped, so the sole possible mismatch is Kotlin.
# All three $KOTLIN_PROSE_FILES surfaces are present: the root pair plus the
# website-static sibling that sat outside the original list and rotted to
# 2.3.20 (#2886 follow-up). `website-static/llms-full.txt` is deliberately
# ABSENT — it was a hand-maintained duplicate of docs/docs/llms-full.txt, is no
# longer committed, and `check-llms-drift.sh` fails if it returns.
# website-static/ also satisfies the fix block's
# cache-buster `find` (unrelated, pre-existing), which is not guarded against
# a missing dir.
make_fixture() { # dir toml_kotlin prose_kotlin
    local dir="$1" toml="$2" prose="$3"
    mkdir -p "$dir/.claude/scripts" "$dir/gradle" "$dir/docs/docs" "$dir/website-static/.well-known"
    cp "$SRC" "$dir/.claude/scripts/"
    printf 'VERSION_NAME=1.2.3\n' > "$dir/gradle.properties"
    printf 'kotlin = "%s"\n' "$toml" > "$dir/gradle/libs.versions.toml"
    printf '**Min SDK:** 24 | **Target SDK:** 36 | **Kotlin:** %s | **Compose BOM compatible**\n' \
        "$prose" > "$dir/llms.txt"
    printf -- '- **Kotlin:** %s with Compose 1.10.5\n' "$prose" > "$dir/docs/docs/llms-full.txt"
    # Same shape as the real website-static file: .well-known mirrors the
    # root llms.txt pipe line.
    printf '**Min SDK:** 24 | **Target SDK:** 36 | **Kotlin:** %s | **Compose BOM compatible**\n' \
        "$prose" > "$dir/website-static/.well-known/llms.txt"
}

# ── 1. Drifted Kotlin prose is rewritten to the toml value in BOTH files, and
#       ONLY the number moves — the neighbouring Compose refs are untouched. ──
D="$WORK/drift"
make_fixture "$D" "9.9.9" "8.8.7"
set +e; bash "$D/.claude/scripts/sync-versions.sh" --fix >/dev/null 2>&1; set -e

grep -q '\*\*Kotlin:\*\* 9.9.9 | \*\*Compose BOM compatible\*\*' "$D/llms.txt" \
    && ok "llms.txt: Kotlin 8.8.7 -> 9.9.9, '**Compose BOM compatible**' label intact" \
    || bad "llms.txt: Kotlin not rewritten or Compose label clobbered"

grep -q '\*\*Kotlin:\*\* 9.9.9 with Compose 1.10.5' "$D/docs/docs/llms-full.txt" \
    && ok "llms-full.txt: Kotlin -> 9.9.9, neighbouring 'Compose 1.10.5' untouched" \
    || bad "llms-full.txt: Kotlin not rewritten or Compose version clobbered"

grep -q '\*\*Kotlin:\*\* 9.9.9 | \*\*Compose BOM compatible\*\*' "$D/website-static/.well-known/llms.txt" \
    && ok "website-static/.well-known/llms.txt: Kotlin -> 9.9.9, Compose label intact" \
    || bad "website-static/.well-known/llms.txt: Kotlin not rewritten (the pre-#2886-follow-up gap)"

if grep -q '8.8.7' "$D/llms.txt" "$D/docs/docs/llms-full.txt" \
        "$D/website-static/.well-known/llms.txt"; then
    bad "stale Kotlin 8.8.7 still present after --fix"
else
    ok "old Kotlin version 8.8.7 fully gone from all three files"
fi

# ── 2. A re-run in check-only mode is clean (Kotlin mismatch resolved). ─────
set +e; bash "$D/.claude/scripts/sync-versions.sh" >/dev/null 2>&1; RC=$?; set -e
[ "$RC" -eq 0 ] \
    && ok "re-run check-only exits 0 (no residual mismatch)" \
    || bad "re-run check-only still non-zero (rc=$RC)"

# ── 3. Already-aligned Kotlin prose is a no-op even when the fix block runs.
#       A module VERSION_NAME mismatch forces ERRORS>0 so the `--fix` block
#       executes; the rewriter's `!= $KOTLIN_TOML` guard must still skip it. ──
A="$WORK/aligned"
make_fixture "$A" "7.7.7" "7.7.7"
mkdir -p "$A/sceneview"
printf 'VERSION_NAME=2.0.0\n' > "$A/sceneview/gradle.properties"   # forces a mismatch
set +e; ALIGN_OUT="$(bash "$A/.claude/scripts/sync-versions.sh" --fix 2>&1)"; set -e

if grep -qi 'Kotlin toolchain' <<<"$ALIGN_OUT"; then
    bad "rewriter fired on already-aligned Kotlin prose"
else
    ok "aligned Kotlin prose → no spurious rewrite (guard holds while fix block runs)"
fi
grep -q '\*\*Kotlin:\*\* 7.7.7 ' "$A/llms.txt" \
    && ok "aligned llms.txt left byte-for-byte unchanged" \
    || bad "aligned llms.txt was mutated"
grep -q '\*\*Kotlin:\*\* 7.7.7 ' "$A/website-static/.well-known/llms.txt" \
    && ok "aligned website-static/.well-known/llms.txt left unchanged" \
    || bad "aligned website-static/.well-known/llms.txt was mutated"

echo ""
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
