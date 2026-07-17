#!/usr/bin/env bash
#
# test-check-web-dts.sh — self-test for check-web-dts.sh (#2736).
#
# A drift gate that silently PASSes is worse than none (same rationale as
# test-check-doc-drift.sh), so this pins the guard's contract by mutation:
# copy the real sceneview-web surface into a fixture, verify the guard
# PASSes on it, then verify each class of injected drift turns the guard
# red. Runs in repo-hygiene (ci.yml) and standalone; needs bash + awk only.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CHECK="$REPO_ROOT/.claude/scripts/check-web-dts.sh"
SRC_WEB="$REPO_ROOT/sceneview-web"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-web-dts-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
scenario() { # $1 = name, $2 = expected exit (0|1), then the guard runs on $TMP/fixture
    local name="$1" expected="$2" actual out
    out=$(CHECK_WEB_DTS_ROOT="$TMP/fixture" bash "$CHECK" 2>&1) && actual=0 || actual=$?
    if [ "$actual" -eq "$expected" ]; then
        echo "[PASS] $name"
    else
        echo "[FAIL] $name — expected exit $expected, got $actual"
        while IFS= read -r line; do echo "       | $line"; done <<< "$out"
        FAILURES=$((FAILURES + 1))
    fi
}

reset_fixture() {
    rm -rf "$TMP/fixture"
    mkdir -p "$TMP/fixture/sceneview-web"
    cp "$SRC_WEB/sceneview-web.d.ts" "$TMP/fixture/sceneview-web/"
    mkdir -p "$TMP/fixture/sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/haptic"
    for f in Main.kt SceneViewJS.kt NodeHandle.kt; do
        cp "$SRC_WEB/src/jsMain/kotlin/io/github/sceneview/web/$f" \
           "$TMP/fixture/sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/"
    done
    cp "$SRC_WEB/src/jsMain/kotlin/io/github/sceneview/web/haptic/SceneViewHaptic.kt" \
       "$TMP/fixture/sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/haptic/"
}
FIX_WEB="$TMP/fixture/sceneview-web"
FIX_JSMAIN="$FIX_WEB/src/jsMain/kotlin/io/github/sceneview/web"

# ── 1. Baseline: the real tree must be in sync ─────────────────────────
reset_fixture
scenario "baseline (real surface) passes" 0

# ── 2. New namespace registration without d.ts → red ───────────────────
reset_fixture
sed -i.bak 's/js("window")\["sceneview"\] = api/api["ghostFeature"] = ::jsCreateViewer\n    js("window")["sceneview"] = api/' \
    "$FIX_JSMAIN/Main.kt"
scenario "api[\"ghostFeature\"] without d.ts export fails" 1

# ── 3. d.ts export without registry entry → red ────────────────────────
reset_fixture
printf '\nexport function ghostExport(): void;\n' >> "$FIX_WEB/sceneview-web.d.ts"
scenario "d.ts export without api registration fails" 1

# ── 4. New public SceneViewJS method without d.ts member → red ─────────
reset_fixture
# Inject right after the class opening line, 4-space indent like real members.
awk '1; /^class SceneViewJS/ && !done { print "    fun ghostMethod() {}"; done = 1 }' \
    "$FIX_JSMAIN/SceneViewJS.kt" > "$FIX_JSMAIN/SceneViewJS.kt.new"
mv "$FIX_JSMAIN/SceneViewJS.kt.new" "$FIX_JSMAIN/SceneViewJS.kt"
scenario "new public SceneViewJS method without d.ts member fails" 1

# ── 5. Stale d.ts member (removed Kotlin-side) → red ───────────────────
reset_fixture
sed -i.bak 's/  fitToModels(): void;/  fitToModels(): void;\n  ghostMember(): void;/' \
    "$FIX_WEB/sceneview-web.d.ts"
scenario "stale d.ts SceneViewer member fails" 1

# ── 6. @JsName rename without d.ts update → red ────────────────────────
reset_fixture
sed -i.bak 's/@JsName("setVisible")/@JsName("setShown")/' "$FIX_JSMAIN/NodeHandle.kt"
scenario "@JsName rename without d.ts update fails" 1

echo ""
if [ "$FAILURES" -ne 0 ]; then
    echo "check-web-dts.sh self-test: $FAILURES scenario(s) FAILED"
    exit 1
fi
echo "check-web-dts.sh self-test: all 6 scenarios hold"
exit 0
