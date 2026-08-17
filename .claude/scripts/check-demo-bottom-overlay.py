#!/usr/bin/env python3
"""Refuses a demo that anchors something to the bottom of its scene by hand.

Why this exists
---------------
Every demo screen has the same three tenants competing for the same strip of pixels
above the system bars:

- the Settings FAB + its peek chip, at bottom-end, owned by `DemoScaffold`;
- `SceneActionBar`, at bottom-start, holding the demo's primary action;
- the demo's own status banner, hand-placed at bottom-center.

`DemoScaffold` already owns a slot that lays all three out so they cannot collide —
`bottomOverlay`, a bottom-aligned Column. A survey of the demo directory found **4
files using it and 23 hand-placing a bottom overlay in the scene lambda instead**,
which produced fifteen confirmed collisions. Most were visible on first launch: on
`ARTerrainAnchorDemo`, the banner every user sees before configuring an ARCore Cloud
key ran under the "Drop here" button *and* under the Settings FAB at the same time.

`SceneActionBar`'s own KDoc had promised those pills "never collide with this
bottom-start bar", so the drift was not carelessness — it was documented. The doc is
now corrected, but a doc cannot be the enforcement: a banner's height follows its
string, its wrap and the font scale, so no clearance constant a demo author picks
survives contact with a longer sentence in a future locale.

What it refuses, inside `samples/android-demo/**/demos/`
--------------------------------------------------------
1. `.align(Alignment.Bottom…)` outside a `bottomOverlay = { … }` argument.
2. A `SceneActionBar(` call outside one — that is the `BoxScope` overload, which
   shares the band with whatever else is anchored there and cannot be told about it.

Both have the same fix: move the element into `DemoScaffold(bottomOverlay = { … })`,
where it stacks instead of overlapping, and where `settingsFabReservedSpace` is in
scope. A status pill should become `DemoStatusBanner(...)` on the way.

What it deliberately leaves alone: `Alignment.Top*`, `Alignment.Center*`, and
anything outside the demos directory — `DemoScaffold` and `SceneActionBar`
themselves have to write the anchoring this gate forbids everyone else.

Exit 0 clean, 1 with findings, 2 if it could not run.
"""
import re
import sys
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
DEMOS = REPO / "samples/android-demo/src/main/java/io/github/sceneview/demo/demos"

BOTTOM_ALIGN = re.compile(r"\.align\s*\(\s*Alignment\.Bottom[A-Za-z]*\s*\)")
ACTION_BAR = re.compile(r"\bSceneActionBar\s*\(")
SLOT_START = re.compile(r"\bbottomOverlay\s*=\s*\{")


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


def slot_ranges(src):
    """(start, end) index pairs covering every `bottomOverlay = { … }` body."""
    out = []
    for m in SLOT_START.finditer(src):
        i = m.end() - 1  # on the '{'
        depth, n = 0, len(src)
        while i < n:
            nxt = scan_forward(src, i)
            if nxt is not None:
                i = nxt
                continue
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    out.append((m.end(), i))
                    break
            i += 1
    return out


def line_of(src, index):
    return src.count("\n", 0, index) + 1


def find_hand_anchored(src):
    slots = slot_ranges(src)
    tokens = token_ranges(src)

    def is_code(pos):
        """True when `pos` is real code outside every `bottomOverlay = { … }` body."""
        if any(a <= pos < b for a, b in tokens):
            return False  # prose: a KDoc example, a comment, a string label
        return not any(a <= pos < b for a, b in slots)

    out = []
    for m in BOTTOM_ALIGN.finditer(src):
        if is_code(m.start()):
            out.append((line_of(src, m.start()), m.group(0).strip(),
                        "hand-anchored to the bottom of the scene"))
    for m in ACTION_BAR.finditer(src):
        if is_code(m.start()):
            out.append((line_of(src, m.start()), "SceneActionBar(",
                        "the BoxScope overload — it shares the bottom band blindly"))
    return sorted(out)


def main():
    if not DEMOS.is_dir():
        print(f"check-demo-bottom-overlay: {DEMOS} not found", file=sys.stderr)
        return 2

    findings = []
    for path in sorted(DEMOS.rglob("*.kt")):
        rel = path.relative_to(REPO).as_posix()
        if "/build/" in f"/{rel}":
            continue
        try:
            src = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            print(f"check-demo-bottom-overlay: cannot read {rel}: {exc}", file=sys.stderr)
            return 2
        for line, token, why in find_hand_anchored(src):
            findings.append((rel, line, token, why))

    if not findings:
        print("✓ demo bottom overlay: every bottom-anchored element is in the shared slot")
        return 0

    print(f"✗ demo bottom overlay: {len(findings)} element(s) anchored outside the slot\n")
    for rel, line, token, why in findings:
        print(f"  {rel}:{line}")
        print(f"      {token} — {why}")
    print(
        "\nMove it into DemoScaffold(bottomOverlay = { … }). That slot is a bottom-aligned\n"
        "Column, so a banner and an action bar stack instead of sharing pixels, and it is\n"
        "the only place `settingsFabReservedSpace` — the width the Settings FAB is taking\n"
        "in THIS demo — is in scope. A hand-placed status pill should become\n"
        "`DemoStatusBanner(text, tone = …)` on the way; it already carries that inset."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
