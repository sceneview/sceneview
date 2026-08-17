#!/usr/bin/env bash
# Self-test for check-demo-bottom-overlay.py.
#
# Drives the gate's FAILING path on synthetic trees. A gate that only ever reports green
# on a repo that happens to be clean is indistinguishable from a gate that cannot fire —
# and this one hinges on a brace matcher, where "cannot fire" is one unbalanced comment
# away.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE_SRC="$SCRIPT_DIR/check-demo-bottom-overlay.py"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
pass=0; fail=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

DEMOS_REL="samples/android-demo/src/main/java/io/github/sceneview/demo/demos"

# The gate locates the repo as parents[2] of its own path, so the copy must sit at
# <root>/.claude/scripts/ for the fixture's demos/ to be the one it scans.
new_fixture() {
    local root="$TMP_ROOT/$1"
    mkdir -p "$root/.claude/scripts" "$root/$DEMOS_REL"
    cp "$GATE_SRC" "$root/.claude/scripts/"
    echo "$root"
}

run_gate() {
    python3 "$1/.claude/scripts/check-demo-bottom-overlay.py" > "$1/out.txt" 2>&1
}

assert_exit() {
    local name="$1" root="$2" want="$3"
    local got=0
    run_gate "$root" || got=$?
    if [ "$got" = "$want" ]; then
        printf "${GREEN}  ✓ %s${NC}\n" "$name"; pass=$((pass + 1))
    else
        printf "${RED}  ✗ %s — expected exit %s, got %s${NC}\n" "$name" "$want" "$got"
        sed 's/^/      /' "$root/out.txt"; fail=$((fail + 1))
    fi
}

assert_reports() {
    local name="$1" root="$2" needle="$3"
    if grep -qF "$needle" "$root/out.txt"; then
        printf "${GREEN}  ✓ %s${NC}\n" "$name"; pass=$((pass + 1))
    else
        printf "${RED}  ✗ %s — output never mentions %s${NC}\n" "$name" "$needle"
        sed 's/^/      /' "$root/out.txt"; fail=$((fail + 1))
    fi
}

echo "check-demo-bottom-overlay self-test"

# ── The shared slot passes ────────────────────────────────────────────────────────────────
root="$(new_fixture clean)"
cat > "$root/$DEMOS_REL/Clean.kt" <<'KT'
@Composable
fun CleanDemo(onBack: () -> Unit) {
    DemoScaffold(
        title = "Clean",
        onBack = onBack,
        bottomOverlay = {
            DemoStatusBanner(status, tone = DemoStatusTone.Progress)
            SceneActionBar(SceneAction("Drop", onClick = { drop() }))
        },
    ) {
        ARSceneView(modifier = Modifier.fillMaxSize())
    }
}
KT
assert_exit "a demo using the shared slot passes" "$root" 0

# ── A hand-anchored bottom banner fails ───────────────────────────────────────────────────
root="$(new_fixture handanchored)"
cat > "$root/$DEMOS_REL/Hand.kt" <<'KT'
@Composable
fun HandDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Hand", onBack = onBack) {
        ARSceneView(modifier = Modifier.fillMaxSize())
        Text(
            text = statusText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}
KT
assert_exit "a hand-anchored bottom banner fails" "$root" 1
assert_reports "  and names the shared slot as the fix" "$root" "bottomOverlay"

# ── The BoxScope SceneActionBar overload fails ────────────────────────────────────────────
root="$(new_fixture boxbar)"
cat > "$root/$DEMOS_REL/Bar.kt" <<'KT'
@Composable
fun BarDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Bar", onBack = onBack) {
        ARSceneView(modifier = Modifier.fillMaxSize())
        SceneActionBar(SceneAction("Drop here", onClick = onDrop))
    }
}
KT
assert_exit "a SceneActionBar in the scene lambda fails" "$root" 1
assert_reports "  and says why the BoxScope overload is the problem" "$root" "BoxScope overload"

# ── Top and centre anchoring are none of this gate's business ─────────────────────────────
root="$(new_fixture topalign)"
cat > "$root/$DEMOS_REL/Top.kt" <<'KT'
@Composable
fun TopDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Top", onBack = onBack) {
        Text(hint, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Text(hud, modifier = Modifier.align(Alignment.Center))
    }
}
KT
assert_exit "top / centre anchoring is not policed" "$root" 0

# ── A brace inside a string template must not close the slot early ────────────────────────
# THE REGRESSION THIS PINS: the slot's extent is found by counting braces. Kotlin string
# templates carry braces of their own, so a matcher that does not skip literals closes
# `bottomOverlay = { … }` at the wrong place — everything after it silently becomes
# "outside the slot", and a correctly-migrated demo starts failing. The inverse is worse:
# an unterminated construct swallows the file and the gate reports clean for life.
root="$(new_fixture braces)"
cat > "$root/$DEMOS_REL/Braces.kt" <<'KT'
@Composable
fun BracesDemo(onBack: () -> Unit) {
    DemoScaffold(
        title = "Braces",
        onBack = onBack,
        bottomOverlay = {
            // A label with a template, a nested string, and a brace in the text.
            DemoStatusBanner("Anchors: ${anchors.size} ${if (busy) "{busy}" else ""}")
            SceneActionBar(SceneAction("Drop", onClick = { drop() }))
        },
    ) {
        ARSceneView(modifier = Modifier.fillMaxSize())
    }
}
KT
assert_exit "a brace inside a string template does not close the slot" "$root" 0

# ── A mention in a comment or a string is not a call site ─────────────────────────────────
root="$(new_fixture prose)"
cat > "$root/$DEMOS_REL/Prose.kt" <<'KT'
/**
 * Historically this demo called SceneActionBar( … ) in the scene lambda and used
 * Modifier.align(Alignment.BottomCenter) for its pill. Both moved to the slot.
 */
@Composable
fun ProseDemo(onBack: () -> Unit) {
    // SceneActionBar( in a line comment is prose, not a call.
    val doc = "see SceneActionBar( for the migration"
    DemoScaffold(title = "Prose", onBack = onBack, bottomOverlay = { }) { }
}
KT
assert_exit "a mention in prose is not flagged as a call site" "$root" 0

echo
if [ "$fail" -gt 0 ]; then
    printf "${RED}%d passed, %d FAILED${NC}\n" "$pass" "$fail"
    exit 1
fi
printf "${GREEN}%d passed${NC}\n" "$pass"
