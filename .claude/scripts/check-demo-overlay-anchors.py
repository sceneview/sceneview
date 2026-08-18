#!/usr/bin/env python3
"""Refuses a screen-edge overlay that is anchored by hand instead of by a scaffold.

Why this exists
---------------
Every demo screen has the same tenants competing for the same strips of pixels at
the top and bottom of the viewport:

- the Settings FAB + its peek chip, at bottom-end, owned by `DemoScaffold`;
- `SceneActionBar`, at bottom-start, holding the demo's primary action;
- the asset-source chip, at top-end, also owned by `DemoScaffold`;
- the demo's own status banners, hand-placed at top-center and bottom-center.

`DemoScaffold` owns two slots that lay these out so they cannot collide —
`bottomOverlay` and `topOverlay`, both aligned Columns that apply the window
insets once for everybody.

This gate used to police the BOTTOM edge of the `demos/` directory only, and its
own docstring said so out loud: *"What it deliberately leaves alone: `Alignment.
Top*` … and anything outside the demos directory."* Both exemptions turned out to
be load-bearing. While the bottom edge was clean, the top edge of the same files
accumulated 35 uncoordinated `.align(Alignment.Top*)` calls across three mutually
incompatible inset conventions — some writing `windowInsetsPadding(systemBars)`,
some writing `padding(top = 8.dp)`, some writing nothing — inside the same Box.
`safeDrawing` appeared zero times in the entire repository. And the app-wide
update banner — the one overlay every screen in the app renders under — lives in
`MainActivity.kt`, in the package root, outside the scanned tree.

A guard that polices one edge of one folder is what let all this through. The
first draft of this file answered that with a longer allowlist, three directories
instead of one; #3237 review pointed out that three out of eleven is the same
mistake with a bigger number. So there is no directory allowlist: **every** Kotlin
file under the demo app is scanned, both edges, and the two files that have to
write the anchoring everyone else is forbidden are named individually in `EXEMPT`.
What varies by location is only how strict the rule is, per the two sections below.

What it refuses
---------------
**In `demos/` and `common/`** — the demo screens and the shared session
composables they are built from — a `.align(Alignment.Top…)` or
`.align(Alignment.Bottom…)` at a screen edge, outside the matching scaffold slot,
and a `SceneActionBar(` call outside `bottomOverlay` (that is the `BoxScope`
overload, which shares the band with whatever else is anchored there and cannot
be told about it). `common/` is included because a helper that draws chrome for
several screens gets those screens wrong all at once.

**Everywhere else** — `ui/`, the package root (`MainActivity`, `DemoListScreen`),
`update/`, `whatsnew/`, `sketchfab/`, `sources/`, `feedback/`, `ai/`, `fragments/`,
`theme/`: hosts, viewers and banners that have no `DemoScaffold` to hand them a
slot — the same anchors are allowed, but only when the modifier
chain carrying them also declares an inset frame (`windowInsetsPadding`,
`safeDrawingPadding`, `systemBarsPadding`, `statusBarsPadding`,
`navigationBarsPadding`, `displayCutoutPadding`, `safeContentPadding` or
`safeGesturesPadding`). A back arrow floating over a full-screen viewer is
legitimate; a back arrow floating over a full-screen viewer *under the status
bar* is the bug this gate exists to stop.

`common/` is held to the strict `demos/` rule except where a composable is a
`BoxScope` extension with no enclosing Box in its own file — those are shared by
both a scaffolded and an unscaffolded host, so they are judged by the weaker rule
instead.

The weaker rule is not a loophole: it is what would have caught the update banner
had someone ever dropped its `windowInsetsPadding(statusBars)`. Removing that one
line is a two-character diff that no test renders and no reviewer is likely to
question, and before this change the gate returned 0 on it.

What it deliberately leaves alone
---------------------------------
- `Alignment.Center*` — the middle of the screen has no system bar to hide under.
- An anchor inside a Box that is **not** full-screen. A badge pinned to the
  top-end of a *card* is anchored to the card, not to the display, and demanding
  a window inset there would be nonsense. The gate resolves the innermost
  enclosing `Box` and only fires when that Box fills the screen (or when the
  anchor sits in a `BoxScope` receiver with no Box in the file at all, which in
  this app means a scaffold body).
- `DemoScaffold.kt` and `common/SceneActionBar.kt` themselves: they have to write
  the anchoring this gate forbids everyone else. Both are now inside the scanned
  tree, so they are named in `EXEMPT` rather than excluded by geography — which
  means adding a third exemption is a visible line in a diff.

Exit 0 clean, 1 with findings, 2 if it could not run.
"""
import re
import sys
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
DEMO_APP = REPO / "samples/android-demo/src/main/java/io/github/sceneview/demo"

# Every Kotlin file in the demo app is scanned. The first version of this gate
# listed three directories — `demos/`, `common/`, `ui/` — and #3237 review found
# the hole immediately: the app-wide update banner that motivated the whole PR
# lives in `MainActivity.kt`, in the package ROOT, and the package root was not
# one of the three. Neither were `update/`, `whatsnew/`, `sketchfab/`, `sources/`,
# `feedback/`, `ai/` or `fragments/`. A guard that polices three directories out
# of eleven reproduces the defect it was written to end, so the allowlist is gone:
# the default is "scanned", and the two exceptions below are named by path.
#
# `strict` means "must be in the scaffold slot". It applies only where a scaffold
# is actually in scope — the demo screens and the shared session composables they
# are built from. Everywhere else there is no slot to move into, so the rule is
# the weaker, still sufficient one: declare an inset frame.
STRICT_SUBTREES = ("demos", "common")

# A component's own definition cannot be held to the rule that names it. These two
# files ARE the shared slot machinery: `DemoScaffold` has to anchor the overlay
# bands it hands out, and the `BoxScope` overload of `SceneActionBar` is precisely
# the thing every demo is forbidden to call. Listed by path rather than skipped by
# a heuristic, so a third exemption has to be a deliberate edit that shows up in a
# diff — an allowlist that grows quietly is how the previous version of this gate
# ended up covering three directories out of eleven.
EXEMPT = {
    "samples/android-demo/src/main/java/io/github/sceneview/demo/common/SceneActionBar.kt",
    "samples/android-demo/src/main/java/io/github/sceneview/demo/DemoScaffold.kt",
}

EDGES = {
    "Top": "topOverlay",
    "Bottom": "bottomOverlay",
}
EDGE_ALIGN = re.compile(r"\.align\s*\(\s*Alignment\.(Top|Bottom)[A-Za-z]*\s*\)")
ACTION_BAR = re.compile(r"\bSceneActionBar\s*\(")
SLOT_START = re.compile(r"\b(topOverlay|bottomOverlay)\s*=\s*\{")
BOX_CALL = re.compile(r"\bBox\s*\(")
CHAIN_START = re.compile(r"\b[Mm]odifier\b")
CHAIN_LINK = re.compile(r"\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(")
# Only modifiers that actually push content clear of a system bar or a cutout
# count. `imePadding` pads for the keyboard and `consumeWindowInsets` *removes*
# an inset from the children's frame — an overlay whose sole inset token was one
# of those would satisfy the gate while still sitting under the status bar, which
# is precisely the bug this file exists to stop. They were in this tuple until
# #3237 review caught it; nothing relied on them.
INSET_MODIFIERS = (
    "windowInsetsPadding",
    "safeDrawingPadding",
    "safeContentPadding",
    "safeGesturesPadding",
    "systemBarsPadding",
    "statusBarsPadding",
    "navigationBarsPadding",
    "displayCutoutPadding",
)


def scan_forward(src, i):
    """Index just past the token starting at src[i] if it is a string or comment.

    Returns None when src[i] starts neither, so the caller can treat it as an
    ordinary character. Kotlin string templates carry braces (`${if (a) "{" else ""}`)
    and comments carry unbalanced ones, so a brace matcher that does not skip both
    will close a block in the wrong place and silently exempt half a file.
    """
    n = len(src)
    if src.startswith('"""', i):
        j = src.find('"""', i + 3)
        return n if j < 0 else j + 3
    if src[i] == '"':
        j = i + 1
        while j < n:
            if src[j] == "\\":
                j += 2
                continue
            if src[j] == "$" and j + 1 < n and src[j + 1] == "{":
                # A template expression: recurse, because it can nest strings.
                depth, j = 0, j + 1
                while j < n:
                    nxt = scan_forward(src, j)
                    if nxt is not None:
                        j = nxt
                        continue
                    if src[j] == "{":
                        depth += 1
                    elif src[j] == "}":
                        depth -= 1
                        if depth == 0:
                            j += 1
                            break
                    j += 1
                continue
            if src[j] == '"':
                return j + 1
            j += 1
        return n
    if src[i] == "'":
        # A char literal. `'{'`, `'}'` and `'"'` are all legal Kotlin, and each one
        # derails a matcher that does not consume it: an unpaired brace shifts every
        # slot boundary after it, and a lone quote makes the rest of the file look
        # like one enormous string literal — which reads as "no findings", for life.
        j = i + 1
        while j < n:
            if src[j] == "\\":
                j += 2
                continue
            if src[j] == "'":
                return j + 1
            if src[j] == "\n":
                break  # not a char literal after all; treat the quote as ordinary
            j += 1
        return i + 1
    if src.startswith("//", i):
        j = src.find("\n", i)
        return n if j < 0 else j
    if src.startswith("/*", i):
        j = src.find("*/", i + 2)
        return n if j < 0 else j + 2
    return None


def token_ranges(src):
    """(start, end) index pairs covering every string literal and comment in `src`.

    Computed by one linear scan of the whole file, not by probing around a match: a
    KDoc block naming `SceneActionBar(` opens lines earlier than the mention, so a
    walk-back that only looks at the current line reports the file's own
    documentation as a call site.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        nxt = scan_forward(src, i)
        if nxt is not None:
            out.append((i, nxt))
            i = nxt
            continue
        i += 1
    return out


def match_bracket(src, i, opening, closing):
    """Index of the bracket closing the one at `src[i]`, or None if unbalanced.

    Token-aware throughout — see `scan_forward` for why that is not optional.
    """
    depth, n = 0, len(src)
    while i < n:
        nxt = scan_forward(src, i)
        if nxt is not None:
            i = nxt
            continue
        if src[i] == opening:
            depth += 1
        elif src[i] == closing:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def slot_ranges(src, tokens):
    """(start, end, edge) triples covering every real scaffold slot body.

    `tokens` gates which matches count. A `bottomOverlay = {` written inside a KDoc
    example — this file's own migration guidance does exactly that — is prose, and
    treating it as a slot carves out an exemption zone spanning whatever braces
    happen to balance after it. Everything hand-anchored inside that zone then
    passes silently, which is the one failure mode a gate must not have.
    """
    out = []
    for m in SLOT_START.finditer(src):
        if any(a <= m.start() < b for a, b in tokens):
            continue
        close = match_bracket(src, m.end() - 1, "{", "}")
        if close is not None:
            out.append((m.end(), close, "Top" if m.group(1) == "topOverlay" else "Bottom"))
    return out


def box_ranges(src, tokens):
    """(body_start, body_end, fills_screen) for every `Box(…) { … }` in the file.

    `fills_screen` is what decides whether an `Alignment.Top*` inside the body is
    anchored to the *display* or merely to a card: the same call spelled the same
    way is a defect in one and correct in the other, and the difference is not
    visible at the `align` itself. Anything more precise than "the Box says
    fillMaxSize" needs a layout pass, which a source gate does not get.
    """
    out = []
    for m in BOX_CALL.finditer(src):
        if any(a <= m.start() < b for a, b in tokens):
            continue
        args_close = match_bracket(src, m.end() - 1, "(", ")")
        if args_close is None:
            continue
        fills = "fillMaxSize" in src[m.end():args_close]
        i = args_close + 1
        n = len(src)
        while i < n:
            nxt = scan_forward(src, i)
            if nxt is not None:
                i = nxt
                continue
            if src[i].isspace():
                i += 1
                continue
            break
        if i < n and src[i] == "{":
            body_close = match_bracket(src, i, "{", "}")
            if body_close is not None:
                out.append((i + 1, body_close, fills))
    return out


def chain_ranges(src, tokens):
    """(start, end) for every `Modifier.a().b()` / `modifier.a().b()` chain.

    Used to answer one question: does the chain that carries this `align` also
    declare an inset frame? Built by walking forward over balanced `.name(…)`
    links rather than backwards from the `align`, because a chain spans newlines
    and comments and a backwards scan through balanced parens gets both wrong.
    """
    out = []
    for m in CHAIN_START.finditer(src):
        if any(a <= m.start() < b for a, b in tokens):
            continue
        i, n = m.end(), len(src)
        while True:
            j = i
            while j < n:
                nxt = scan_forward(src, j)
                if nxt is not None:
                    j = nxt
                    continue
                if src[j].isspace():
                    j += 1
                    continue
                break
            if j >= n or src[j] != ".":
                break
            link = CHAIN_LINK.match(src, j)
            if not link:
                break
            close = match_bracket(src, link.end() - 1, "(", ")")
            if close is None:
                break
            i = close + 1
        if i > m.end():
            out.append((m.start(), i))
    return out


def innermost(ranges, pos):
    """The narrowest range containing `pos`, or None."""
    best = None
    for r in ranges:
        if r[0] <= pos < r[1] and (best is None or (r[1] - r[0]) < (best[1] - best[0])):
            best = r
    return best


def line_of(src, index):
    return src.count("\n", 0, index) + 1


def find_hand_anchored(src, strict):
    tokens = token_ranges(src)
    slots = slot_ranges(src, tokens)
    boxes = box_ranges(src, tokens)
    chains = chain_ranges(src, tokens)

    def is_prose(pos):
        return any(a <= pos < b for a, b in tokens)

    def at_screen_edge(pos):
        box = innermost(boxes, pos)
        if box is None:
            # No enclosing Box in this file. In this app that means a `BoxScope`
            # extension — the receiver is somebody else's full-screen body.
            return True
        return box[2]

    out = []
    for m in EDGE_ALIGN.finditer(src):
        if is_prose(m.start()) or not at_screen_edge(m.start()):
            continue
        edge = m.group(1)
        slot = innermost([(a, b) for a, b, e in slots if e == edge], m.start())
        if slot is not None:
            continue
        chain = innermost(chains, m.start())
        insetted = chain is not None and any(
            mod in src[chain[0]:chain[1]] for mod in INSET_MODIFIERS
        )
        # Decided per anchor, not per file. A composable whose anchor sits in a
        # `BoxScope` it received rather than a Box it opened is drawing into
        # somebody else's body, and that somebody may be a tab host with no
        # DemoScaffold at all — `TapToPlaceStatusOverlays` is rendered by both
        # `ARPlacementDemo` (scaffolded) and `ArViewTab` (not). Requiring a slot
        # there would demand an API the caller may not have; requiring an inset
        # frame is the strongest rule that holds for both. Deciding this
        # file-at-a-time instead let a single unrelated `Box(` elsewhere in the
        # file flip the verdict.
        require_slot = strict and innermost(boxes, m.start()) is not None
        if require_slot:
            out.append((
                line_of(src, m.start()),
                m.group(0).strip(),
                f"hand-anchored to the {edge.lower()} of the screen — "
                f"move it into DemoScaffold({EDGES[edge]} = {{ … }})",
            ))
        elif not insetted:
            out.append((
                line_of(src, m.start()),
                m.group(0).strip(),
                f"anchored to the {edge.lower()} edge with no window inset on its "
                "modifier chain — it will sit under a system bar or a cutout",
            ))
    for m in ACTION_BAR.finditer(src):
        if is_prose(m.start()):
            continue
        if innermost([(a, b) for a, b, e in slots if e == "Bottom"], m.start()) is not None:
            continue
        if not strict:
            continue
        out.append((
            line_of(src, m.start()),
            "SceneActionBar(",
            "the BoxScope overload — it shares the bottom band blindly",
        ))
    return sorted(out)


def is_strict(path):
    """True when `path` sits under a subtree where a DemoScaffold slot is in scope."""
    rel = path.relative_to(DEMO_APP).parts
    return len(rel) > 1 and rel[0] in STRICT_SUBTREES


def main():
    findings = []
    if not DEMO_APP.is_dir():
        print(f"check-demo-overlay-anchors: {DEMO_APP} not found", file=sys.stderr)
        return 2
    scanned = 0
    for path in sorted(DEMO_APP.rglob("*.kt")):
        rel = path.relative_to(REPO).as_posix()
        if "/build/" in f"/{rel}" or rel in EXEMPT:
            continue
        try:
            src = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            print(f"check-demo-overlay-anchors: cannot read {rel}: {exc}", file=sys.stderr)
            return 2
        scanned += 1
        for line, token, why in find_hand_anchored(src, is_strict(path)):
            findings.append((rel, line, token, why))

    # A gate that silently scans nothing is the failure mode this whole PR is
    # about. If the package moves, say so instead of printing a green tick.
    if scanned == 0:
        print(
            f"check-demo-overlay-anchors: no .kt files under {DEMO_APP} — "
            "the package moved and this gate is checking nothing",
            file=sys.stderr,
        )
        return 2

    if not findings:
        print("✓ demo overlay anchors: every screen-edge overlay is in a slot or insetted")
        return 0

    print(f"✗ demo overlay anchors: {len(findings)} element(s) anchored by hand\n")
    for rel, line, token, why in findings:
        print(f"  {rel}:{line}")
        print(f"      {token} — {why}")
    print(
        "\nDemoScaffold owns all four edges. `topOverlay` and `bottomOverlay` are aligned\n"
        "Columns, so banners and action bars stack instead of sharing pixels, and each\n"
        "applies WindowInsets.safeDrawing ONCE for everything inside it — which is why an\n"
        "element that moves into a slot must drop its own windowInsetsPadding and its\n"
        "padding(top = N.dp) anchor. `settingsFabReservedSpace` (bottom) and\n"
        "`assetSourceChipReservedSpace` (top) are in scope there and nowhere else.\n"
        "\nA screen with no DemoScaffold — a tab host, a full-screen viewer — keeps its own\n"
        "anchor, but must spell the inset frame out: WindowInsets.safeDrawing.only(\n"
        "WindowInsetsSides.Horizontal + WindowInsetsSides.Top), not a magic dp."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
