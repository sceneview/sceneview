#!/usr/bin/env bash
#
# test-pre-push-log-dir.sh — self-test for lib/log-dir.sh (#3074).
#
# `pre-push-check.sh` used to write its logs to one machine-wide
# `${TMPDIR:-/tmp}/sceneview-pre-push`, while this repo runs many worktrees in
# parallel by design. The interesting failure is not the lost file: it is that
# leg 19 keeps the list of self-tests it is executing in that directory, so a
# NEIGHBOUR's `: > selftests.txt` truncated it under this run's open descriptor
# and the loop ended early while the pre-computed count still printed
# `✓ N gate self-test(s) pass` (#3137). A false green, in the gate whose whole
# purpose is to stop false greens.
#
# So the load-bearing case here (case 5) replays that interleaving rather than
# asserting on the shape of a path: it FAILS when two runs share a directory —
# the control half of the case proves it by reproducing the old collapse — and
# passes only because they no longer do. Everything else is cheap invariants
# around it. Pure bash, no network, sub-second.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
LIB="$ROOT/.claude/scripts/lib/log-dir.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-pre-push-log-dir.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# A private TMPDIR keeps the suite out of the real log directory: it must never
# be able to clobber a gate run happening on this host while it runs.
export TMPDIR="$SCRATCH/tmp"
mkdir -p "$TMPDIR"

# shellcheck source=lib/log-dir.sh
source "$LIB"

WT_A="$SCRATCH/worktrees/feature-a"
WT_B="$SCRATCH/worktrees/feature-b"
mkdir -p "$WT_A" "$WT_B"

# ─── 1. two worktrees, two directories ─────────────────────────────────────
DIR_A="$(pre_push_log_dir "$WT_A")"
DIR_B="$(pre_push_log_dir "$WT_B")"
[ "$DIR_A" != "$DIR_B" ] \
    && ok "two checkout roots resolve to two different directories" \
    || bad "two checkout roots collided on $DIR_A"

# ─── 2. stable across runs — nothing to garbage-collect ────────────────────
[ "$(pre_push_log_dir "$WT_A")" = "$DIR_A" ] \
    && ok "the same checkout root always resolves to the same directory" \
    || bad "the directory moved between two calls for the same root"

# Two roots sharing a basename are the case a basename-only suffix would miss.
mkdir -p "$SCRATCH/other/feature-a"
[ "$(pre_push_log_dir "$SCRATCH/other/feature-a")" != "$DIR_A" ] \
    && ok "two worktrees with the same basename still get different directories" \
    || bad "same-basename worktrees collided"

# ─── 3. it stays under TMPDIR, as one component ────────────────────────────
case "$DIR_A" in
    "${TMPDIR%/}/sceneview-pre-push/"*) SUFFIX="${DIR_A##*/sceneview-pre-push/}" ;;
    *) SUFFIX="" ;;
esac
[ -n "$SUFFIX" ] && [ "$SUFFIX" = "${SUFFIX//\//}" ] \
    && ok "the directory is one component under \$TMPDIR/sceneview-pre-push" \
    || bad "unexpected layout: $DIR_A"

# A root whose basename carries a `/`-adjacent or otherwise hostile name must
# not turn one component into two, nor climb out of TMPDIR.
HOSTILE="$SCRATCH/wt/..evil name;rm -rf"
mkdir -p "$HOSTILE"
H_DIR="$(pre_push_log_dir "$HOSTILE")"
H_SUFFIX="${H_DIR##*/sceneview-pre-push/}"
case "$H_SUFFIX" in
    *[!A-Za-z0-9_-]*|*..*) bad "a hostile basename leaked into the path: $H_DIR" ;;
    *) ok "a hostile basename is folded to a safe single component" ;;
esac

# A trailing slash is a different spelling of the same root, not a crash.
pre_push_log_dir "$WT_A/" > /dev/null 2>&1 \
    && ok "a trailing slash on the root is tolerated" \
    || bad "a trailing slash on the root failed"

# No root at all is a caller bug, reported rather than silently shared.
RC=0; pre_push_log_dir > /dev/null 2>&1 || RC=$?
[ "$RC" -ne 0 ] \
    && ok "an empty checkout root is refused instead of defaulting to a shared path" \
    || bad "an empty checkout root produced a directory"

# ─── 4. 0700 survives, on the directory AND its parent ─────────────────────
# Gradle quotes the offending value on failure, and the demo build injects
# ARCORE_API_KEY / SKETCHFAB_API_KEY — a live key can land in one of these logs.
CREATED="$(pre_push_log_dir_create "$WT_A")"
[ "$CREATED" = "$DIR_A" ] && [ -d "$CREATED" ] \
    && ok "…_create creates the directory it names" \
    || bad "…_create returned $CREATED (expected $DIR_A, directory present: $([ -d "$CREATED" ] && echo yes || echo no))"

mode_of() { ls -ld "$1" | cut -c1-10; }
[ "$(mode_of "$CREATED")" = "drwx------" ] \
    && ok "the log directory is 0700" \
    || bad "the log directory is $(mode_of "$CREATED"), not drwx------"
[ "$(mode_of "${CREATED%/*}")" = "drwx------" ] \
    && ok "the parent directory is 0700 too" \
    || bad "the parent is $(mode_of "${CREATED%/*}"), not drwx------"

# ─── 5. the regression itself: leg 19's list under a concurrent run ────────
# Replay of #3137. `read`'s descriptor points at the file for the whole loop,
# so a neighbour rewriting the SAME path mid-loop moves the bytes under it and
# the loop ends early — while the count printed afterwards was computed before.
# `shared_dirs=yes` is the control: it must reproduce the collapse, otherwise
# this case proves nothing about the fix.
replay_leg19() {
    local a="$1" b="$2" seen=0 line
    printf 'one\ntwo\nthree\nfour\nfive\n' > "$a/selftests.txt"
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        seen=$((seen + 1))
        # The neighbouring worktree starts its own leg 19 after our first line.
        if [ "$seen" -eq 1 ]; then
            : > "$b/selftests.txt"
            printf 'x\ny\n' >> "$b/selftests.txt"
        fi
    done < "$a/selftests.txt"
    printf '%s' "$seen"
}

SHARED="$SCRATCH/shared"; mkdir -p "$SHARED"
CONTROL="$(replay_leg19 "$SHARED" "$SHARED")"
[ "$CONTROL" -ne 5 ] \
    && ok "control: a SHARED directory loses lines of selftests.txt (read $CONTROL of 5)" \
    || bad "control did not reproduce the shared-directory collapse — this case proves nothing"

REAL_A="$(pre_push_log_dir_create "$WT_A")"
REAL_B="$(pre_push_log_dir_create "$WT_B")"
FIXED="$(replay_leg19 "$REAL_A" "$REAL_B")"
[ "$FIXED" -eq 5 ] \
    && ok "per-worktree directories: a concurrent run cannot truncate this run's list" \
    || bad "a concurrent run still truncated the list (read $FIXED of 5)"

# ─── 6. the gate actually goes through this lib ────────────────────────────
# The invariants above are worth nothing if pre-push-check.sh recomputes the
# path inline again, which is exactly how it was written before #3074.
GATE="$ROOT/.claude/scripts/pre-push-check.sh"
grep -q 'pre_push_log_dir_create' "$GATE" \
    && ok "pre-push-check.sh resolves LOG_DIR through lib/log-dir.sh" \
    || bad "pre-push-check.sh no longer calls pre_push_log_dir_create"
grep -qE '^[[:space:]]*LOG_DIR=.*sceneview-pre-push' "$GATE" \
    && bad "pre-push-check.sh reintroduced a machine-wide LOG_DIR" \
    || ok "pre-push-check.sh carries no machine-wide LOG_DIR assignment"

echo
if [ "$FAIL" -eq 0 ]; then
    printf '  %s passed\n' "$PASS"
    exit 0
fi
printf '  %s passed, %s FAILED\n' "$PASS" "$FAIL"
exit 1
