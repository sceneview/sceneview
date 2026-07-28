#!/usr/bin/env python3
"""Decide whether a shell command really DRIVES an Android device, or merely
mentions one.

Read by hook-dispatch.sh's emulator-lease guard. Substring matching is not
enough and produced a false block within minutes of shipping the guard: a
`gh issue comment --body "... <a device command> ..."` quotes the command in
prose and drives nothing, yet contains every substring the guard looked for.

Input : the full command line on stdin.
Output: the targeted serial on stdout (may be empty = "the default device").
Exit   : 0 -> this really is a mutating device command; 1 -> it is not.

Fail-open by contract: anything unparseable (unbalanced quotes, a heredoc, an
exotic construct) exits 1, so the guard degrades to "allow" rather than block on
its own confusion. hook-dispatch.sh never blocks on a hook bug.
"""

import os
import shlex
import sys

# Command names that drive a device.
DRIVERS = {"adb", "android"}

# Shell operators that start a fresh command word.
SEPARATORS = {";", "&&", "||", "|", "&", "(", ")", "{", "}", "\n"}

# Single tokens that make an invocation mutating.
MUTATING_WORDS = {"install", "uninstall", "reboot", "install-multiple"}

# Token pairs that make an invocation mutating.
MUTATING_PAIRS = {
    ("pm", "clear"),
    ("pm", "install"),
    ("pm", "uninstall"),
    ("pm", "grant"),
    ("pm", "revoke"),
    ("am", "force-stop"),
    ("am", "start"),
    ("am", "startservice"),
    ("am", "broadcast"),
    ("am", "kill"),
    ("emu", "kill"),
    ("shell", "input"),
    ("shell", "reboot"),
    ("android", "run"),
}


def is_env_assignment(token: str) -> bool:
    """`FOO=bar` before a command name is an env assignment, not the command."""
    if "=" not in token:
        return False
    name = token.split("=", 1)[0]
    return bool(name) and (name[0].isalpha() or name[0] == "_") and all(
        c.isalnum() or c == "_" for c in name
    )


def split_commands(tokens):
    """Yield each command as its own token list, split on shell separators."""
    current = []
    for tok in tokens:
        if tok in SEPARATORS:
            if current:
                yield current
            current = []
        else:
            current.append(tok)
    if current:
        yield current


def serial_of(tokens, assignments):
    """The serial this invocation targets, or '' for the default device."""
    for i, tok in enumerate(tokens):
        if tok == "-s" and i + 1 < len(tokens):
            return tokens[i + 1]
        if tok.startswith("--serial="):
            return tok.split("=", 1)[1]
        if tok == "--device" and i + 1 < len(tokens):
            return tokens[i + 1]
        if tok.startswith("--device="):
            return tok.split("=", 1)[1]
    for a in assignments:
        if a.startswith("ANDROID_SERIAL="):
            return a.split("=", 1)[1]
    return ""


def is_mutating(tokens) -> bool:
    if any(t in MUTATING_WORDS for t in tokens):
        return True
    return any(pair in MUTATING_PAIRS for pair in zip(tokens, tokens[1:]))


def main() -> int:
    raw = sys.stdin.read()
    if not raw.strip():
        return 1
    try:
        tokens = shlex.split(raw, comments=False, posix=True)
    except ValueError:
        # Unbalanced quoting / heredoc — cannot reason, so do not block.
        return 1

    for cmd in split_commands(tokens):
        assignments = []
        idx = 0
        while idx < len(cmd) and is_env_assignment(cmd[idx]):
            assignments.append(cmd[idx])
            idx += 1
        if idx >= len(cmd):
            continue
        name = os.path.basename(cmd[idx])
        if name not in DRIVERS:
            continue
        rest = cmd[idx:]
        if not is_mutating(rest):
            continue
        sys.stdout.write(serial_of(rest, assignments))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
