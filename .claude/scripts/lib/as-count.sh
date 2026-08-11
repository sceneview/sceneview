#!/usr/bin/env bash
# as-count.sh — normalise a `grep -c` result into something arithmetic can use.
#
# `grep -c` prints `0` AND exits 1 when nothing matches, so the familiar
# `$(grep -c … || echo 0)` yields the TWO-LINE value `0\n0`. That value breaks in
# two different ways depending on where it lands, and both were live in
# `quality-gate.sh` at the same time:
#
#   [ "$N" -eq 0 ] && check PASS || check FAIL
#       -> `[: 0\n0: integer expression expected`, and in a `A && x || y` chain
#          that failure silently takes the `y` branch. The threading check
#          therefore reported THREADING VIOLATION on a *clean* diff.
#
#   TOTAL=$((TOTAL + N))
#       -> `syntax error in expression (error token is "0")`. Under `set -e` this
#          kills the enclosing block, so the force-unwrap check AND the threading
#          check below it were both skipped and the gate printed NEITHER line —
#          a green run that had verified nothing at all. Strictly worse than the
#          false red, because nothing on screen says a check went missing.
#
# Neither symptom ever showed up in CI: there `git diff HEAD` is empty, the
# `if [ -n "$CHANGED_KT" ]` guard is false, and the whole block is skipped. The
# checks could only run locally, which is exactly where nobody was watching.
#
# Usage — `|| true` on the grep, normalise the result here:
#   COUNT=$(as_count "$(grep -c 'foo' file 2>/dev/null || true)")
#
# Sourced by quality-gate.sh; pinned by test-as-count.sh.

# Takes the first line, strips everything that is not a digit, and defaults to
# `0`. Every input shape a failing grep can produce collapses to one integer.
#
# `${1-}` FIRST, and only then the pattern strip: under `set -u` a combined
# `${1%%…}` on an absent positional is an unbound-variable error on bash 5 (every
# GitHub runner) but NOT on the bash 3.2 that ships with macOS. So a caller that
# forgets the argument dies in CI and passes locally — the exact local-vs-runner
# divergence this file exists to stop. test-as-count.sh pins it two ways: the
# behavioural `as_count` with no argument, plus a static assertion that no
# positional here is dereferenced without a default (that one holds on 3.2 too).
as_count() {
    local first="${1-}"
    first="${first%%$'\n'*}"
    first="${first//[^0-9]/}"
    printf '%s' "${first:-0}"
}

# Same normalisation for the diagnostic counts scraped out of a failed checker's
# log, where `?` means "it failed but itemised nothing" — a real distinction, and
# one `0` would misreport as "no offending lines".
as_count_or_unknown() {
    local n
    n="$(as_count "${1:-}")"
    if [ "$n" = "0" ]; then printf '?'; else printf '%s' "$n"; fi
}
