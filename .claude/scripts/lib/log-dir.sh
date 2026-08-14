#!/usr/bin/env bash
#
# log-dir.sh — the scratch directory `pre-push-check.sh` writes its logs to,
# resolved PER WORKTREE (#3074).
#
# The gate used to compute it inline as `${TMPDIR:-/tmp}/sceneview-pre-push`.
# That path carries no worktree component, and this repo runs many worktrees
# in parallel by design, so every concurrent run wrote to the same files.
# Three measured consequences, worst last:
#
#   1. Misdirected diagnostics — the gate prints `Full log: …/api-check.log`,
#      and that pointer could name ANOTHER session's failure. The tail quoted
#      above it could come from a build unrelated to the code being pushed.
#   2. Cross-worktree self-test discovery (#3131) — leg 19 writes the list of
#      self-tests it is about to execute into this directory, so two worktrees
#      ran each other's suites.
#   3. A FALSE GREEN in the gate itself (#3137) — a second run's `: > selftests.txt`
#      truncated the file under the first run's open descriptor: the read loop
#      ended early while the pre-computed count still printed
#      `✓ 35 gate self-test(s) pass` over a loop that ran twenty. The gate whose
#      whole purpose is to stop false greens produced one.
#
# The suffix is DERIVED from the checkout root rather than from a run id: it is
# stable across runs of the same worktree, so there is nothing to garbage-collect,
# and distinct between worktrees, which is the property that matters. Mode 0700
# is kept (and now applied to the parent too) because a manifest-merger or javac
# failure can quote a live ARCORE_API_KEY / SKETCHFAB_API_KEY into one of these
# logs, and with TMPDIR unset the fallback is a shared world-readable /tmp.
#
# Usage (sourced):
#   source .claude/scripts/lib/log-dir.sh
#   LOG_DIR="$(pre_push_log_dir "$CHECKOUT_ROOT")"          # path only
#   LOG_DIR="$(pre_push_log_dir_create "$CHECKOUT_ROOT")"   # path + mkdir 0700
#
# Pinned by test-pre-push-log-dir.sh.

# Short, stable digest of a string. `shasum` and `sha1sum` cover macOS and the
# CI runners; `cksum` is the POSIX floor so this never becomes the reason a
# host cannot run the gate.
_pre_push_digest() {
    if command -v shasum > /dev/null 2>&1; then
        printf '%s' "$1" | shasum | cut -c1-8
    elif command -v sha1sum > /dev/null 2>&1; then
        printf '%s' "$1" | sha1sum | cut -c1-8
    else
        printf '%s' "$1" | cksum | tr -cd '0-9' | cut -c1-8
    fi
}

# `<basename>-<digest>`: the basename is there so a human reading
# `Full log: …` can tell which worktree it belongs to, the digest is what makes
# it unique. Everything outside [A-Za-z0-9_-] is folded to `_`, the dot
# included — a `/` would otherwise turn one component into two, and a basename
# that is or begins with `..` would name a directory outside TMPDIR entirely.
# Dots buy nothing here and every rule that keeps them needs a second rule
# about `..`, so they go.
_pre_push_worktree_id() {
    local name digest
    name="$(printf '%s' "${1##*/}" | tr -c 'A-Za-z0-9_-' '_')"
    case "$name" in ''|*[!A-Za-z0-9_-]*) name="worktree" ;; esac
    digest="$(_pre_push_digest "$1")"
    printf '%s-%s' "$name" "$digest"
}

# Print the log directory for a checkout root. Pure: creates nothing.
pre_push_log_dir() {
    local root="${1:-}" base
    if [ -z "$root" ]; then
        echo "pre_push_log_dir: checkout root required" >&2
        return 64
    fi
    base="${TMPDIR:-/tmp}"
    base="${base%/}/sceneview-pre-push"
    printf '%s/%s\n' "$base" "$(_pre_push_worktree_id "$root")"
}

# Same, and create it 0700 (parent included).
pre_push_log_dir_create() {
    local dir
    dir="$(pre_push_log_dir "$1")" || return $?
    mkdir -p "$dir"
    chmod 700 "${dir%/*}" 2> /dev/null || true
    chmod 700 "$dir" 2> /dev/null || true
    printf '%s\n' "$dir"
}
