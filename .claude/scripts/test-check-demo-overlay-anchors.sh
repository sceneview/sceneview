#!/usr/bin/env bash
# Self-test for check-demo-overlay-anchors.py.
#
# Drives the gate's FAILING path on synthetic trees. A gate that only ever reports green
# on a repo that happens to be clean is indistinguishable from a gate that cannot fire —
# and this one hinges on a brace matcher, where "cannot fire" is one unbalanced comment
# away.
#
# The gate this replaces policed one edge of one directory, and every exemption it
# documented turned out to be a hiding place (#3237). So the cases below are organised by
# the thing that can go wrong rather than by feature: each edge, each directory, each
# rule, and the four lexing regressions that make a Kotlin brace matcher lie.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE_SRC="$SCRIPT_DIR/check-demo-overlay-anchors.py"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
pass=0; fail=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

APP_REL="samples/android-demo/src/main/java/io/github/sceneview/demo"
DEMOS_REL="$APP_REL/demos"
COMMON_REL="$APP_REL/common"
UI_REL="$APP_REL/ui"

# The gate locates the repo as parents[2] of its own path, so the copy must sit at
# <root>/.claude/scripts/ for the fixture's directories to be the ones it scans. All
# three must exist: a missing directory is exit 2, not exit 0 — a gate that reports
# "clean" because it could not find the code is the failure mode this whole file exists
# to make impossible.
new_fixture() {
    local root="$TMP_ROOT/$1"
    mkdir -p "$root/.claude/scripts" "$root/$DEMOS_REL" "$root/$COMMON_REL" "$root/$UI_REL"
    cp "$GATE_SRC" "$root/.claude/scripts/"
    echo "$root"
}

run_gate() {
    python3 "$1/.claude/scripts/check-demo-overlay-anchors.py" > "$1/out.txt" 2>&1
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

echo "check-demo-overlay-anchors self-test"

# ── Both shared slots pass ────────────────────────────────────────────────────────────────
root="$(new_fixture clean)"
cat > "$root/$DEMOS_REL/Clean.kt" <<'KT'
@Composable
fun CleanDemo(onBack: () -> Unit) {
    DemoScaffold(
        title = "Clean",
        onBack = onBack,
        topOverlay = {
            Surface { Text(statusText) }
        },
        bottomOverlay = {
            DemoStatusBanner(status, tone = DemoStatusTone.Progress)
            SceneActionBar(SceneAction("Drop", onClick = { drop() }))
        },
    ) {
        ARSceneView(modifier = Modifier.fillMaxSize())
    }
}
KT
assert_exit "a demo using both shared slots passes" "$root" 0

# ── A hand-anchored bottom banner fails ───────────────────────────────────────────────────
root="$(new_fixture handbottom)"
cat > "$root/$DEMOS_REL/Hand.kt" <<'KT'
@Composable
fun HandDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Hand", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(modifier = Modifier.fillMaxSize())
            Text(
                text = statusText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
KT
assert_exit "a hand-anchored bottom banner fails" "$root" 1
assert_reports "  and names the bottom slot as the fix" "$root" "bottomOverlay"

# ── A hand-anchored TOP banner fails — the edge the old gate ignored ──────────────────────
# THE REGRESSION THIS PINS: the previous gate's docstring said it "deliberately leaves
# alone: Alignment.Top*". 35 uncoordinated top anchors accumulated behind that sentence,
# in three different inset conventions, inside the same Box.
root="$(new_fixture handtop)"
cat > "$root/$DEMOS_REL/HandTop.kt" <<'KT'
@Composable
fun HandTopDemo(onBack: () -> Unit) {
    DemoScaffold(title = "HandTop", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(modifier = Modifier.fillMaxSize())
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) { Text(statusText) }
        }
    }
}
KT
assert_exit "a hand-anchored top banner fails" "$root" 1
assert_reports "  and names the top slot as the fix" "$root" "topOverlay"
assert_reports "  and an inset written by hand is not accepted instead" "$root" "HandTop.kt"

# ── A top anchor carrying its own windowInsetsPadding still fails in demos/ ───────────────
# Inside a DemoScaffold the answer is the slot, not a second inset frame: the scaffold body
# already consumed those insets, so a demo re-applying them lands in a different place from
# the demo next door that did not. That divergence is the root cause, not a symptom.
root="$(new_fixture insetted_demo)"
cat > "$root/$DEMOS_REL/InsetDemo.kt" <<'KT'
@Composable
fun InsetDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Inset", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) { Text(chip) }
        }
    }
}
KT
assert_exit "a demo top anchor is not excused by its own inset" "$root" 1

# ── SceneActionBar's BoxScope overload still fails ────────────────────────────────────────
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

# ── Centre anchoring is none of this gate's business ──────────────────────────────────────
root="$(new_fixture centre)"
cat > "$root/$DEMOS_REL/Centre.kt" <<'KT'
@Composable
fun CentreDemo(onBack: () -> Unit) {
    DemoScaffold(title = "Centre", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            Text(hud, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
KT
assert_exit "centre anchoring is not policed" "$root" 0

# ── A badge on a CARD is anchored to the card, not to the display ─────────────────────────
# THE FALSE POSITIVE THIS PINS: `Alignment.TopEnd` inside a 96 dp thumbnail is how every
# "NEW" badge in the app is drawn. A gate that demands a status-bar inset there is a gate
# authors learn to suppress, and a suppressed gate catches nothing at all.
root="$(new_fixture cardbadge)"
cat > "$root/$UI_REL/Card.kt" <<'KT'
@Composable
fun ModelCard(model: Model) {
    Box(modifier = Modifier.size(96.dp)) {
        Image(painter = model.thumb, contentDescription = null)
        Badge(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}
KT
assert_exit "a badge inside a non-full-screen Box is left alone" "$root" 0

# ── ui/ — a full-screen anchor with no inset fails ────────────────────────────────────────
root="$(new_fixture ui_noinset)"
cat > "$root/$UI_REL/Viewer.kt" <<'KT'
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
    }
}
KT
assert_exit "a full-screen ui/ anchor with no inset fails" "$root" 1
assert_reports "  and says what is missing" "$root" "no window inset"

# ── ui/ — the same anchor with an inset frame passes ──────────────────────────────────────
# `ui/` holds the screens that have no DemoScaffold to give them a slot — a tab host, a
# full-screen viewer. They keep their anchor; they do not get to keep it undeclared.
root="$(new_fixture ui_inset)"
cat > "$root/$UI_REL/Viewer.kt" <<'KT'
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    )
                )
                .padding(8.dp),
        ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
    }
}
KT
assert_exit "a full-screen ui/ anchor that declares its inset frame passes" "$root" 0

# ── ui/ — imePadding and consumeWindowInsets are NOT an inset frame ───────────────────────
# THE REGRESSION THIS PINS: both were in the accepted-modifier tuple when this gate first
# shipped (#3237 review caught it). Neither clears a system bar — `imePadding` pads for the
# keyboard, and `consumeWindowInsets` *removes* an inset from the children's frame — so an
# overlay whose only inset token was one of those passed the gate while still sitting under
# the status bar. That is the exact bug the gate exists to stop, spelled with a token that
# looks inset-shaped.
for fake in "imePadding()" "consumeWindowInsets(WindowInsets.systemBars)"; do
  root="$(new_fixture "ui_fake_${fake%%(*}")"
  cat > "$root/$UI_REL/Viewer.kt" <<KT
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .$fake
                .padding(8.dp),
        ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
    }
}
KT
  assert_exit "a ui/ anchor insetted only by .$fake fails" "$root" 1
  assert_reports "  and says the inset is what is missing" "$root" "no window inset"
done

# ── common/ — a BoxScope extension is judged by the inset rule, not the slot rule ─────────
# THE REGRESSION THIS PINS: a composable that anchors into a `BoxScope` it received is
# drawing into somebody else's body, and that body may belong to a tab host with no
# DemoScaffold at all. Demanding a slot there demands an API the caller does not have.
# The first version of this gate decided that file-at-a-time, so one unrelated `Box(`
# elsewhere in the file flipped the verdict.
root="$(new_fixture common_boxscope)"
cat > "$root/$COMMON_REL/Overlays.kt" <<'KT'
@Composable
fun SessionHost(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        ARSceneView(modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun BoxScope.StatusOverlays(state: State) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            ),
    ) { Text(state.label) }
}
KT
assert_exit "a common/ BoxScope extension passes on its inset frame alone" "$root" 0

# ── common/ — the same extension without an inset fails ───────────────────────────────────
root="$(new_fixture common_noinset)"
cat > "$root/$COMMON_REL/Overlays.kt" <<'KT'
@Composable
fun BoxScope.StatusOverlays(state: State) {
    Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
        Text(state.label)
    }
}
KT
assert_exit "a common/ BoxScope extension with a magic dp instead of an inset fails" "$root" 1

# ── common/ — an anchor in a Box the file itself opens IS held to the slot rule ───────────
root="$(new_fixture common_ownbox)"
cat > "$root/$COMMON_REL/Widget.kt" <<'KT'
@Composable
fun Widget() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(hint, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
KT
assert_exit "a common/ anchor in the file's own full-screen Box needs the slot" "$root" 1

# ── A missing directory is exit 2, never exit 0 ───────────────────────────────────────────
root="$(new_fixture missingdir)"
rm -rf "$root/$UI_REL"
assert_exit "a missing scanned directory is a hard error, not a green run" "$root" 2

# ── A brace inside a string template must not close a slot early ──────────────────────────
# THE REGRESSION THIS PINS: a slot's extent is found by counting braces. Kotlin string
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
        topOverlay = {
            Text("Anchors: ${anchors.size} ${if (busy) "{busy}" else ""}")
        },
        bottomOverlay = {
            DemoStatusBanner("Anchors: ${anchors.size} ${if (busy) "{busy}" else ""}")
            SceneActionBar(SceneAction("Drop", onClick = { drop() }))
        },
    ) {
        ARSceneView(modifier = Modifier.fillMaxSize())
    }
}
KT
assert_exit "a brace inside a string template does not close a slot" "$root" 0

# ── A mention in a comment or a string is not a call site ─────────────────────────────────
root="$(new_fixture prose)"
cat > "$root/$DEMOS_REL/Prose.kt" <<'KT'
/**
 * Historically this demo called SceneActionBar( … ) in the scene lambda and used
 * Modifier.align(Alignment.BottomCenter) for its pill, plus
 * Modifier.align(Alignment.TopCenter) for its status. All moved to the slots.
 */
@Composable
fun ProseDemo(onBack: () -> Unit) {
    // SceneActionBar( in a line comment is prose, not a call.
    val doc = "see SceneActionBar( for the migration"
    DemoScaffold(title = "Prose", onBack = onBack, bottomOverlay = { }) { }
}
KT
assert_exit "a mention in prose is not flagged as a call site" "$root" 0

# ── A slot named in prose must not become an exemption zone ───────────────────────────────
# THE REGRESSION THIS PINS: the slot scanner used to run over the raw source, so a comment
# naming the slot — the very thing this gate tells authors to write — was read as a real
# one. Its opening brace has no partner inside the comment, so the match ran on until the
# next real `}`, and the region between them became an exemption zone covering live code.
# A gate with a false negative is worse than no gate: it certifies the defect it exists to
# catch. Note the missing `}` in the comment — that asymmetry is the whole mechanism.
root="$(new_fixture prose_slot)"
cat > "$root/$DEMOS_REL/ProseSlot.kt" <<'KT'
@Composable
fun ProseSlotDemo(onBack: () -> Unit) {
    DemoScaffold(title = "ProseSlot", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(modifier = Modifier.fillMaxSize())
            // TODO(#3237): this pill belongs in topOverlay = {
            Text(status, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
KT
assert_exit "a slot named in a comment does not exempt real code" "$root" 1
assert_reports "  and the hand-anchored element is still named" "$root" "ProseSlot.kt"

# ── A char literal holding a brace or a quote must not derail the scan ────────────────────
# THE REGRESSION THIS PINS: `'{'`, `'}'` and `'"'` are legal Kotlin. An unconsumed `'{'`
# shifts every slot boundary after it; an unconsumed `'"'` makes the remainder of the file
# look like one string literal, so nothing at all is reported — silently, and green.
root="$(new_fixture charlit)"
cat > "$root/$DEMOS_REL/CharLit.kt" <<'KT'
@Composable
fun CharLitDemo(onBack: () -> Unit) {
    val open = '{'
    val quote = '"'
    val esc = '\''
    DemoScaffold(
        title = "CharLit",
        onBack = onBack,
        bottomOverlay = { DemoStatusBanner(status) },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(modifier = Modifier.fillMaxSize())
            Text(hud, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
KT
assert_exit "a char literal holding a brace or a quote does not blind the scan" "$root" 1
assert_reports "  and the element outside the slot is reported" "$root" "CharLit.kt"

echo
if [ "$fail" -gt 0 ]; then
    printf "${RED}%d passed, %d FAILED${NC}\n" "$pass" "$fail"
    exit 1
fi
printf "${GREEN}%d passed${NC}\n" "$pass"
