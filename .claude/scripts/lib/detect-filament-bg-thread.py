#!/usr/bin/env python3
"""detect-filament-bg-thread.py — does this diff put a Filament factory call on
a background dispatcher?

CLAUDE.md's critical threading rule: Filament JNI calls run on the MAIN thread,
so `modelLoader.createModel*` / `materialLoader.*` must never be reached from a
background coroutine.

This lived inline in `quality-gate.sh` as a two-stage grep, and it was hollow in
two independent ways:

  BGTHREAD=$(git diff HEAD | grep "+.*Dispatchers\\.IO" \\
             | grep -c "modelLoader\\|materialLoader\\|createModel\\|createMaterial" || echo "0")
  [ "$BGTHREAD" -eq 0 ] && check ... "PASS" || check ... "FAIL" "THREADING VIOLATION"

1. `grep -c` prints `0` AND exits 1 when it matches nothing, so `|| echo "0"`
   appended a second line: the variable held `0\\n0`, `[ -eq 0 ]` died with
   "integer expression expected", and the `||` branch fired. The check therefore
   reported THREADING VIOLATION on a *clean* diff — it could never PASS once any
   `.kt` file was modified. It only looked green in CI because there `git diff
   HEAD` is empty, which skips the whole block.
2. It required the dispatcher and the Filament call on the SAME line, so the
   shape the rule actually exists to catch —

       withContext(Dispatchers.IO) {
           modelLoader.createModelInstance(...)
       }

   — matched nothing even when the arithmetic worked.

So the detection is a file with a self-test (`test-detect-filament-bg-thread.sh`)
pinning BOTH directions: it must stay silent on a clean diff and it must fire on
the multi-line shape. A guard nobody can falsify is how a gate starts lying.

Usage:
  git diff HEAD | python3 .claude/scripts/lib/detect-filament-bg-thread.py
  python3 .claude/scripts/lib/detect-filament-bg-thread.py <diff-file>

stdout: one `path:line: <source>` per violation, plus the dispatcher that opened
        the block. Nothing at all when the diff is clean.
Exit:   0 = no violation · 1 = at least one violation · 64 = bad usage.
"""

import re
import sys

# How many *added* lines after a background dispatcher still count as "inside"
# it. A block opened on one line and left open for a dozen more is the realistic
# shape; anything longer is guesswork, and guessing produces the false positives
# that get a gate disabled.
WINDOW = 12

BACKGROUND_RE = re.compile(r"Dispatchers\s*\.\s*(IO|Default)\b")
# An explicit hop back to the main thread closes the window: a background block
# that hands the Filament call to `Dispatchers.Main` is the CORRECT shape and
# must not be reported.
MAIN_RE = re.compile(r"Dispatchers\s*\.\s*Main\b")
# The rule names these by hand in CLAUDE.md. Deliberately not widened to every
# `create*` — a broad pattern here costs more in false positives than it catches.
FILAMENT_RE = re.compile(
    r"\b(?:modelLoader|materialLoader)\s*\.|\bcreateModel\w*\s*\(|\bcreateMaterial\w*\s*\("
)
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")
KOTLIN_SUFFIXES = (".kt", ".kts")


def scan(lines):
    """Yield (path, lineno, source, dispatcher_lineno) for each violation."""
    path = None
    lineno = 0
    window = 0
    opened_at = 0

    for raw in lines:
        line = raw.rstrip("\n")

        if line.startswith("+++ "):
            target = line[4:].strip()
            if target.startswith("b/"):
                target = target[2:]
            path = None if target == "/dev/null" else target
            window = 0
            continue

        if line.startswith("--- ") or line.startswith("diff --git"):
            window = 0
            continue

        hunk = HUNK_RE.match(line)
        if hunk:
            lineno = int(hunk.group(1))
            # A new hunk is a discontinuity in the file: whatever block was open
            # in the previous hunk may or may not still be open here, and
            # carrying the window across would be a guess.
            window = 0
            continue

        if path is None or not path.endswith(KOTLIN_SUFFIXES):
            continue

        if line.startswith("+"):
            source = line[1:]
            if MAIN_RE.search(source):
                window = 0
            if window > 0 and FILAMENT_RE.search(source):
                yield path, lineno, source.strip(), opened_at
                # Report once per dispatcher block: a five-call block is one
                # defect to fix, not five.
                window = 0
            elif BACKGROUND_RE.search(source):
                window = WINDOW
                opened_at = lineno
                # Same-line shape: `withContext(Dispatchers.IO) { loader.createModel(...) }`
                if FILAMENT_RE.search(source):
                    yield path, lineno, source.strip(), lineno
                    window = 0
            elif window > 0:
                window -= 1
            lineno += 1
        elif line.startswith("-"):
            # A removed line cannot introduce a violation, and removing one must
            # never fail the gate.
            continue
        elif line.startswith(" ") or line == "":
            # Context lines advance the file position but do not reopen or
            # extend a window: only added code is under review here.
            lineno += 1


def usage():
    """The `Usage:` block of the docstring above, verbatim.

    Sliced on the marker, not by index: this used to print
    `__doc__.strip().splitlines()[-4]`, which is the BLANK line above
    `stdout:` — so the one path that exists to tell a caller how to invoke the
    script printed an empty line and exited 64. Any edit to the docstring moved
    that index; a marker slice cannot rot the same way, and
    test-detect-filament-bg-thread.sh pins that the text comes out non-empty.
    """
    lines = __doc__.splitlines()
    start = lines.index("Usage:")
    block = [lines[start]]
    for line in lines[start + 1:]:
        if not line.strip():
            break
        block.append(line)
    return "\n".join(block)


def main(argv):
    if len(argv) > 2:
        print(usage(), file=sys.stderr)
        return 64

    if len(argv) == 2:
        with open(argv[1], "r", encoding="utf-8", errors="replace") as handle:
            violations = list(scan(handle))
    else:
        violations = list(scan(sys.stdin))

    for path, lineno, source, opened_at in violations:
        where = (
            "same line" if opened_at == lineno else f"Dispatchers block opened at line {opened_at}"
        )
        print(f"{path}:{lineno}: {source}")
        print(f"    ^ Filament factory call on a background dispatcher ({where}).")
        print("      Filament JNI must run on the main thread — see CLAUDE.md.")

    if violations:
        print(f"\n{len(violations)} threading violation(s).")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
