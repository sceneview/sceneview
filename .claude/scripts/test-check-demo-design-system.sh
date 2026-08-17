#!/usr/bin/env bash
# Self-test for check-demo-design-system.py.
#
# Drives the gate's FAILING path on synthetic trees. Without this, a loosened probe reports
# green on a repo that merely happens to be clean — the gate would then pass for life while
# the drift it exists to catch walks straight in.
#
# Each fixture is a throwaway repo root with its own `samples/` tree, so nothing here reads
# or touches the real one.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE_SRC="$SCRIPT_DIR/check-demo-design-system.py"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
pass=0; fail=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

# Builds a fixture repo and echoes its samples dir.
# The gate locates the repo as parents[2] of its own path, so the copy must sit at
# <root>/.claude/scripts/ for the fixture's samples/ to be the one it scans.
new_fixture() {
    local name="$1"
    local root="$TMP_ROOT/$name"
    mkdir -p "$root/.claude/scripts" "$root/samples/demo/src/main"
    mkdir -p "$root/samples/common/src/main/java/io/github/sceneview/sample/ui"
    cp "$GATE_SRC" "$root/.claude/scripts/"
    echo "$root"
}

run_gate() {
    python3 "$1/.claude/scripts/check-demo-design-system.py" > "$1/out.txt" 2>&1
}

assert_exit() {
    local name="$1" root="$2" want="$3"
    local got=0
    run_gate "$root" || got=$?
    if [ "$got" = "$want" ]; then
        printf "${GREEN}  ✓ %s${NC}\n" "$name"
        pass=$((pass + 1))
    else
        printf "${RED}  ✗ %s — expected exit %s, got %s${NC}\n" "$name" "$want" "$got"
        sed 's/^/      /' "$root/out.txt"
        fail=$((fail + 1))
    fi
}

assert_reports() {
    local name="$1" root="$2" needle="$3"
    if grep -qF "$needle" "$root/out.txt"; then
        printf "${GREEN}  ✓ %s${NC}\n" "$name"
        pass=$((pass + 1))
    else
        printf "${RED}  ✗ %s — output never mentions %s${NC}\n" "$name" "$needle"
        sed 's/^/      /' "$root/out.txt"
        fail=$((fail + 1))
    fi
}

echo "check-demo-design-system self-test"

# ── A clean tree passes ───────────────────────────────────────────────────────────────────
root="$(new_fixture clean)"
cat > "$root/samples/demo/src/main/Clean.kt" <<'KT'
@Composable
fun Panel(v: Float, on: (Float) -> Unit) {
    LabeledSlider(label = "Density", value = v, onValueChange = on, valueRange = 0f..1f)
}
KT
assert_exit "a tree using the shared control passes" "$root" 0

# ── A hand-rolled Text + Slider fails ─────────────────────────────────────────────────────
root="$(new_fixture handrolled)"
cat > "$root/samples/demo/src/main/HandRolled.kt" <<'KT'
@Composable
fun Panel(v: Float, on: (Float) -> Unit) {
    Text(
        text = "Density: ${"%.2f".format(Locale.US, v)}",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = v,
        onValueChange = on,
        valueRange = 0f..1f,
    )
}
KT
assert_exit "a Text(...) above a bare Slider( fails" "$root" 1
assert_reports "  and names LabeledSlider as the fix" "$root" "LabeledSlider"

# The label routinely carries quotes and parens inside a string template. A flat regex ends
# the Text call at the FIRST inner quote and never sees the Slider below — this fixture is
# the one that would go silently green.
assert_reports "  even when the label nests quotes in a template" "$root" "HandRolled.kt"

# ── A Slider with no label above is left alone ────────────────────────────────────────────
root="$(new_fixture barelslider)"
cat > "$root/samples/demo/src/main/Bare.kt" <<'KT'
@Composable
fun Row(v: Float, on: (Float) -> Unit) {
    Slider(value = v, onValueChange = on, valueRange = 0f..1f)
}
KT
assert_exit "a Slider with no adjacent label is not flagged" "$root" 0

# ── A second category -> colour table fails ───────────────────────────────────────────────
root="$(new_fixture palette)"
cat > "$root/samples/demo/src/main/Copy.kt" <<'KT'
private fun accent(c: String) = when (c) {
    "3D Basics" -> Color(0xFF6446CD)
    "Content" -> Color(0xFF42A5F5)
    "Advanced" -> Color(0xFF26A69A)
    else -> Color(0xFF000000)
}
KT
assert_exit "a copied category->colour table fails" "$root" 1
assert_reports "  and names DemoCategoryAccent as the fix" "$root" "DemoCategoryAccent"

# ── The palette's own home is exempt ──────────────────────────────────────────────────────
root="$(new_fixture home)"
cat > "$root/samples/common/src/main/java/io/github/sceneview/sample/ui/DemoCategoryAccent.kt" <<'KT'
object DemoCategoryAccent {
    private val light = mapOf(
        "3D Basics" to Color(0xFF6446CD),
        "Content" to Color(0xFF42A5F5),
        "Advanced" to Color(0xFF26A69A),
    )
}
KT
assert_exit "the palette's own file is exempt" "$root" 0

# ── A brand colour that shares a hex with an accent is NOT a palette ──────────────────────
# The regression this pins: keying the rule on hex literals flagged Theme.kt for defining
# its own brand tertiary, which happens to be the same number as the "3D Basics" accent.
root="$(new_fixture brandhex)"
cat > "$root/samples/common/src/main/java/io/github/sceneview/sample/ui/Theme.kt" <<'KT'
object Colors {
    val LightTertiary = Color(0xFF6446CD)
    val DarkTertiary = Color(0xFFD2A8FF)
}
KT
assert_exit "a brand colour sharing an accent's hex is not a palette" "$root" 0

# ── A test source is out of scope ─────────────────────────────────────────────────────────
root="$(new_fixture testsrc)"
mkdir -p "$root/samples/demo/src/test"
cat > "$root/samples/demo/src/test/PanelTest.kt" <<'KT'
@Composable
fun Fixture(v: Float, on: (Float) -> Unit) {
    Text("Density: $v")
    Slider(value = v, onValueChange = on, valueRange = 0f..1f)
}
KT
assert_exit "a test source is not policed" "$root" 0

echo
if [ "$fail" -gt 0 ]; then
    printf "${RED}%d passed, %d FAILED${NC}\n" "$pass" "$fail"
    exit 1
fi
printf "${GREEN}%d passed${NC}\n" "$pass"
