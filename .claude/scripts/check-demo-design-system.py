#!/usr/bin/env python3
"""Refuses a demo that re-invents a control the shared design system already defines.

Why this exists
---------------
The sample apps had no shared UI vocabulary, and the cost was not abstract: 37 sliders
hand-rolled across 21 files, five separate chip implementations, and the per-category accent
palette copied into three screens — one of those copies carrying a "keep these in sync by
hand" comment, and one having no dark variant at all, so in dark mode it rendered the light
hues the dark palette exists to avoid.

Nobody introduced that by being careless. Each demo was written on its own, and a control
typed inline is always the shortest path. The only thing that makes coherence hold without a
human re-reading every demo is a check that fails when a demo re-types one.

What it refuses
---------------
1. A `Text(...)` sitting directly above a bare `Slider(...)` — that is `LabeledSlider`.
2. A category accent hex literal outside `DemoCategoryAccent.kt` — that is the shared palette.

Exit 0 clean, 1 with findings, 2 if it could not run.
"""
import re
import sys
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
SAMPLES = REPO / "samples"

SHARED_UI = "samples/common/src/main/java/io/github/sceneview/sample/ui"

# The palette's own home, plus this checker, are the only files allowed to name these.
PALETTE_EXEMPT = (
    f"{SHARED_UI}/DemoCategoryAccent.kt",
    ".claude/scripts/check-demo-design-system.py",
)

# Detect the palette by its SHAPE, not by its hex values. `0xFF6446CD` is the "3D Basics"
# accent AND the theme's brand tertiary — same number, different meaning — so a hex-literal
# rule flags `Theme.kt` for defining a colour that is legitimately its own. What is actually
# forbidden is a second table mapping category NAMES to colours, and that signature is
# unambiguous: several category names in one file that also builds colours.
CATEGORY_NAMES = (
    "3D Basics", "Lighting & Environment", "Content",
    "Interaction", "Advanced", "Augmented Reality",
)
MIN_NAMES_FOR_A_PALETTE = 3

# `Text(` … `)` then whitespace then `Slider(`. The Text call is matched with a paren counter
# rather than a regex because its label routinely contains `${"%.1f".format(x)}` — quotes and
# parens inside a string template that no flat pattern survives.
TEXT_CALL = re.compile(r"\bText\s*\(")
SLIDER_CALL = re.compile(r"^([ \t]*)Slider\s*\(", re.M)


def call_end(src, open_paren):
    """Index just past the ')' closing the call whose '(' is at open_paren, or None."""
    depth, i, n = 0, open_paren, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i = literal_end(src, i)
            if i is None:
                return None
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def literal_end(src, i):
    """Index just past the Kotlin string literal starting at src[i] == '"', or None."""
    if src.startswith('"""', i):
        j = src.find('"""', i + 3)
        return None if j < 0 else j + 3
    n = len(src)
    j = i + 1
    while j < n:
        if src[j] == "\\":
            j += 2
            continue
        if src[j] == "$" and j + 1 < n and src[j + 1] == "{":
            depth = 0
            j += 1
            while j < n:
                if src[j] == '"':
                    j = literal_end(src, j)
                    if j is None:
                        return None
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
    return None


def line_of(src, index):
    return src.count("\n", 0, index) + 1


def find_hand_rolled_sliders(path, src):
    out = []
    for m in SLIDER_CALL.finditer(src):
        line_start = m.start()
        head = src[:line_start].rstrip()
        if not head.endswith(")"):
            continue
        # walk back to the Text( whose call ends exactly where this line begins
        for tm in reversed(list(TEXT_CALL.finditer(src, 0, line_start))):
            end = call_end(src, src.index("(", tm.start()))
            if end is None:
                continue
            if end <= line_start and src[end:line_start].strip() == "":
                out.append((line_of(src, m.start()), "Text(...) directly above a bare Slider("))
            break
    return out


def find_copied_accents(rel, src):
    """A second category-name -> colour table, wherever it is spelled."""
    present = [n for n in CATEGORY_NAMES if f'"{n}"' in src]
    if len(present) < MIN_NAMES_FOR_A_PALETTE:
        return []
    colour = re.search(r"Color\(0x[0-9A-Fa-f]{8}\)", src)
    if not colour:
        return []
    first = min(src.index(f'"{n}"') for n in present)
    return [(
        line_of(src, first),
        f"a second category->colour table ({len(present)} category names + Color(0x…) literals)",
    )]


def main():
    if not SAMPLES.is_dir():
        print(f"check-demo-design-system: {SAMPLES} not found", file=sys.stderr)
        return 2

    findings = []
    for path in sorted(SAMPLES.rglob("*.kt")):
        rel = path.relative_to(REPO).as_posix()
        if "/build/" in f"/{rel}" or "/src/test/" in f"/{rel}":
            continue
        try:
            src = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            print(f"check-demo-design-system: cannot read {rel}: {exc}", file=sys.stderr)
            return 2
        for line, why in find_hand_rolled_sliders(path, src):
            findings.append((rel, line, why, "use io.github.sceneview.sample.ui.LabeledSlider"))
        if rel not in PALETTE_EXEMPT:
            for line, why in find_copied_accents(rel, src):
                findings.append((rel, line, why, "read io.github.sceneview.sample.ui.DemoCategoryAccent"))

    if not findings:
        print("✓ demo design system: no hand-rolled control, no copied accent")
        return 0

    print(f"✗ demo design system: {len(findings)} site(s) re-invent a shared control\n")
    for rel, line, why, fix in findings:
        print(f"  {rel}:{line}")
        print(f"      {why}")
        print(f"      → {fix}")
    print(
        "\nThe shared controls live in "
        f"{SHARED_UI}.\n"
        "If a demo genuinely needs something they cannot express, widen the shared control —\n"
        "a local copy is how the samples drifted into looking like five different apps."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
