#!/usr/bin/env python3
"""DESIGN.md must not contradict itself.

Every design token has an authoritative value: the one in its markdown table
row. The same file also names tokens inline in prose, repeating the value in
parentheses -- `radius-lg` (24px). Those repeats drift.

They drifted silently for months: `radius-lg` was 24px in the table and 28px in
two prose sentences, and the Compose theme copied the prose. Nothing caught it,
because nothing compared the file to itself.

A token legitimately holds two values when it has a Light Mode and a Dark Mode
row (`shadow-sm`, the Liquid Glass tokens). Those are keyed per theme, and an
inline repeat is accepted when it matches either variant -- prose rarely says
which mode it means, and guessing would only produce false reds.

Exit 0 when every inline repeat matches a table row, 1 otherwise.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DESIGN = ROOT / "DESIGN.md"

# | `radius-lg` | 24px | L | Section cards, bottom sheets |
TABLE_ROW = re.compile(r"^\|\s*`([a-z0-9-]+)`\s*\|\s*([^|]+?)\s*\|")
# ... use `radius-lg` (24px), ...
INLINE = re.compile(r"`([a-z0-9-]+)`\s*\((\d+(?:\.\d+)?[a-z%]*)\)")
# ### Light Mode / ### Dark Mode
THEME_HEADING = re.compile(r"^#{2,4}\s+(light|dark)\s+mode\s*$", re.IGNORECASE)
ANY_HEADING = re.compile(r"^#{2,4}\s+")


def normalise(value):
    return " ".join(value.strip().lower().split())


def main():
    if not DESIGN.is_file():
        print(f"FAIL  {DESIGN} not found")
        return 1

    lines = DESIGN.read_text(encoding="utf-8").splitlines()

    # token -> {theme: (value, line)}. theme is "light", "dark" or "any".
    table = {}
    theme = "any"
    for number, line in enumerate(lines, 1):
        heading = THEME_HEADING.match(line)
        if heading:
            theme = heading.group(1).lower()
        elif ANY_HEADING.match(line):
            theme = "any"

        row = TABLE_ROW.match(line)
        if not row:
            continue
        token, value = row.group(1), normalise(row.group(2))
        variants = table.setdefault(token, {})
        previous = variants.get(theme)
        if previous and previous[0] != value:
            print(
                f"FAIL  DESIGN.md:{number}  `{token}` is defined twice in the "
                f"same theme ({theme}) with different values: "
                f"{previous[0]} (line {previous[1]}) vs {value}"
            )
            return 1
        variants.setdefault(theme, (value, number))

    if not table:
        print("FAIL  no token table found in DESIGN.md -- has the format changed?")
        return 1

    failures = []
    checked = 0
    for number, line in enumerate(lines, 1):
        if TABLE_ROW.match(line):
            continue  # the table is the authority, not a repeat of it
        for token, quoted in INLINE.findall(line):
            variants = table.get(token)
            if not variants:
                continue  # not a token, just a backticked word with a number
            checked += 1
            if normalise(quoted) in {value for value, _ in variants.values()}:
                continue
            expected = ", ".join(
                f"{value} ({name}, line {where})"
                for name, (value, where) in sorted(variants.items())
            )
            failures.append(
                f"  DESIGN.md:{number}  `{token}` ({quoted}) "
                f"contradicts the table: {expected}"
            )

    if failures:
        print(f"FAIL  {len(failures)} inline token value(s) contradict the table:")
        print("\n".join(failures))
        print("\nFix the prose, or the table if the table is the one that is wrong.")
        return 1

    print(f"PASS  {len(table)} tokens, {checked} inline repeat(s), no contradiction")
    return 0


if __name__ == "__main__":
    sys.exit(main())
