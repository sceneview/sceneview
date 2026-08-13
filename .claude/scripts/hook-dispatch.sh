#!/bin/bash
# hook-dispatch.sh — central dispatcher for Claude Code hooks (sceneview).
#
# Replaces the ~17 dead hooks that used permission-rule syntax in their
# `matcher` field ("Bash(git commit*)", "Edit(*/gradle.properties)") — the
# matcher is a regex on the TOOL NAME only, so those hooks never fired.
# settings.json now uses valid matchers ("Bash", "Edit|Write") and routes
# everything here.
#
# Usage (wired in .claude/settings.json):
#   hook-dispatch.sh pre-bash    # PreToolUse  Bash
#   hook-dispatch.sh post-bash   # PostToolUse Bash
#   hook-dispatch.sh post-edit   # PostToolUse Edit|Write
#
# Reads the hook JSON payload on stdin, extracts tool_name and
# tool_input.command / tool_input.file_path via jq.
#
# SAFETY RULES (this settings.json is shared by several live sessions):
#   - exit 2 (blocking) ONLY for the two guards below — both are
#     deterministic, local, sub-second checks, never heuristics:
#       (a) gradle.properties VERSION MISMATCH before `git commit`;
#       (b) a mutating adb/android command aimed at an emulator carrying a
#           LIVE lease owned by another session (#2862). Added after a real
#           incident on 2026-07-27: a session wiped a sibling's QA run with
#           `install -r` + `pm clear` because it probed `emu avd name` (AVD
#           identity) but never read the lease (availability). The scripts
#           honour leases; raw adb from a session bypassed all of it.
#   - Everything else: exit 0 with a reminder on stderr (non-blocking).
#   - Any internal failure (jq missing, git missing, empty/malformed
#     stdin, missing files) => exit 0 silently. NEVER block on a hook bug.
#   - Must run < 1s: no network, no gradle. Only jq/grep/git rev-parse.

set +e
set +u

ROUTE="${1:-}"

# Non-blocking reminder: message to stderr, keep going (final exit 0).
remind() { printf '%s\n' "$1" >&2; }

# --- Parse stdin safely --------------------------------------------------
command -v jq >/dev/null 2>&1 || exit 0
INPUT="$(cat 2>/dev/null)"
[ -n "$INPUT" ] || exit 0

TOOL_NAME="$(printf '%s' "$INPUT" | jq -r '.tool_name // empty' 2>/dev/null)" || exit 0
[ -n "$TOOL_NAME" ] || exit 0
CMD="$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null)"
FILE_PATH="$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)"
HOOK_CWD="$(printf '%s' "$INPUT" | jq -r '.cwd // empty' 2>/dev/null)"

# Repo root resolved from the session's cwd (works in worktrees too).
repo_root() {
  local base="${HOOK_CWD:-$PWD}"
  command -v git >/dev/null 2>&1 || return 1
  git -C "$base" rev-parse --show-toplevel 2>/dev/null
}

case "$ROUTE" in

  # ========================================================== PreToolUse Bash
  pre-bash)
    [ "$TOOL_NAME" = "Bash" ] || exit 0
    [ -n "$CMD" ] || exit 0

    case "$CMD" in
      # A real `git commit` has end-of-string or a non-hyphen after "commit" —
      # this excludes `git commit-tree` (the no-force-push reconcile playbook,
      # which never reads the working tree) and `git commit-graph`.
      *"git commit"|*"git commit"[!-]*)
        # --- Guard 1 (BLOCKING): VERSION_NAME mismatch across gradle.properties
        ROOT="$(repo_root)"
        if [ -n "$ROOT" ] && [ -f "$ROOT/gradle.properties" ]; then
          V="$(grep '^VERSION_NAME=' "$ROOT/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2)"
          if [ -n "$V" ]; then
            for f in sceneview/gradle.properties arsceneview/gradle.properties sceneview-core/gradle.properties; do
              if [ -f "$ROOT/$f" ]; then
                FV="$(grep '^VERSION_NAME=' "$ROOT/$f" 2>/dev/null | head -1 | cut -d= -f2)"
                if [ -n "$FV" ] && [ "$FV" != "$V" ]; then
                  echo "VERSION MISMATCH: $f has VERSION_NAME=$FV but root gradle.properties has $V. Align them before committing (see .claude/scripts/sync-versions.sh)." >&2
                  exit 2
                fi
              fi
            done
          fi
        fi
        # --- Guard 2 (non-blocking): deprecated API scan on staged files
        if [ -n "$ROOT" ] && [ -x "$ROOT/.claude/scripts/check-deprecated-api.sh" ]; then
          OUT="$(cd "$ROOT" 2>/dev/null && bash "$ROOT/.claude/scripts/check-deprecated-api.sh" 2>&1)"
          RC=$?
          if [ "$RC" -ne 0 ] && [ -n "$OUT" ]; then
            remind "DEPRECATED API CHECK (non-blocking): $OUT"
          fi
        fi
        ;;
      *"git push"*)
        remind 'PRE-PUSH QUALITY GATE: Before pushing, ensure you have verified: 1) Android compiles (sceneview + arsceneview), 2) Unit tests pass, 3) Bundle builds if store-affecting, 4) Website JS valid if website changed. Run: bash .claude/scripts/pre-push-check.sh'
        ;;
      *"git worktree add"*)
        # Non-blocking: creating a worktree is the one deterministic signal that a
        # session is starting parallel work. Three unclaimed worktrees plus an open
        # PR collided on issue #2740 on 2026-07-27; claim.sh would have caught it.
        remind 'PARALLEL WORKTREE — claim the issue before writing code: bash .claude/scripts/claim.sh --check <issue#> then bash .claude/scripts/claim.sh <issue#>. Sibling worktrees right now:'
        ROOT="$(repo_root)"
        [ -n "$ROOT" ] && git -C "$ROOT" worktree list 2>/dev/null | sed 's/^/  /' >&2
        ;;
      *adb*|*"android run"*)
        # `android run` is DISAVOWED for installing (#2796, #2854, #2990 — it can
        # print success and install nothing), but this pattern must keep matching
        # it: the guard's job is to intercept whatever a session actually types,
        # and a session may still type a disavowed command. Removing it here
        # would let exactly that call reach a foreign-leased emulator unguarded.
        # --- Guard 2 (BLOCKING): mutating adb/android vs a foreign-leased emulator.
        # Lease format is owned by lib/emulator-select.sh (#2862): line 1 = owner pid,
        # then key=value lines (mode, session, avd, since, label).
        # Only commands typed BY a session reach this hook — the adb calls inside
        # qa-android-demos.sh / device-qa.sh are invisible here, so the legitimate
        # script path is never obstructed.
        # Substring matching is NOT enough, and shipping it produced a false block
        # within minutes: a `gh issue comment --body "…<a device command>…"` quotes
        # the command in prose and drives nothing, yet contains every substring.
        # lib/hook-adb-target.py tokenises with shlex and requires the driver in
        # COMMAND position (line start or after a separator, env assignments
        # allowed) — text inside a quoted string collapses to one token and can
        # never look like an invocation. It prints the targeted serial (empty =
        # default device) and exits 0 only for a genuinely mutating invocation.
        # No python3, or unparseable quoting => fail OPEN, per this hook's doctrine.
        HELPER="$(dirname "$0")/lib/hook-adb-target.py"
        if [ -f "$HELPER" ] && command -v python3 >/dev/null 2>&1; then
          TGT="$(printf '%s' "$CMD" | python3 "$HELPER" 2>/dev/null)"
          DRIVES=$?
        else
          DRIVES=1
        fi
        if [ "$DRIVES" = 0 ]; then
            LEASE_DIR="${EMU_LEASE_DIR:-${TMPDIR:-/tmp}/sceneview-device-qa-emu}"
            [ -d "$LEASE_DIR" ] || exit 0
            NOW="$(date +%s 2>/dev/null)"
            [ -n "$NOW" ] || exit 0
            for lf in "$LEASE_DIR"/*.lease; do
              [ -f "$lf" ] || continue
              SERIAL="$(basename "$lf" .lease)"
              # A command naming a serial only conflicts with that serial's lease.
              if [ -n "$TGT" ] && [ "$TGT" != "$SERIAL" ]; then continue; fi
              MODE="$(sed -n 's/^mode=//p' "$lf" 2>/dev/null | head -1)"
              SESS="$(sed -n 's/^session=//p' "$lf" 2>/dev/null | head -1)"
              SINCE="$(sed -n 's/^since=//p' "$lf" 2>/dev/null | head -1)"
              OWNER="$(head -n1 "$lf" 2>/dev/null | tr -d '[:space:]')"
              LIVE=0
              TTL="${EMU_LEASE_STICKY_TTL:-14400}"
              if [ "$MODE" = "sticky" ]; then
                case "$SINCE" in
                  ''|*[!0-9]*) LIVE=1 ;;
                  *) [ "$((NOW - SINCE))" -lt "$TTL" ] && LIVE=1 ;;
                esac
              else
                case "$OWNER" in
                  ''|*[!0-9]*) LIVE=0 ;;
                  *) kill -0 "$OWNER" 2>/dev/null && LIVE=1 ;;
                esac
              fi
              [ "$LIVE" = 1 ] || continue
              # The lease is mine -> allowed.
              #
              # EMU_LEASE_TAKEOVER=1 is deliberately NOT an escape hatch here, and this
              # guard therefore does NOT mirror lib/emulator-select.sh. The two serve
              # different actors: the lib serves an operator at a terminal, who can see
              # the peer's run and judge; this guard serves a Claude Code session, for
              # which CLAUDE.md states a hard rule ("never set EMU_LEASE_TAKEOVER=1").
              # Until 2026-08-13 the guard let the var through and its own refusal text
              # advertised it, so the rule was unenforced and actively undermined at the
              # one moment a session reads it. A session that needs a device has two
              # honest routes, both below. Terminal use is unaffected: the hook only
              # sees commands issued through a session.
              case "$CMD" in
                *"EMU_LEASE_SESSION=$SESS"*) continue ;;
              esac
              echo "EMULATOR LEASE GUARD: $SERIAL carries a LIVE lease (mode=${MODE:-pid}, session=${SESS:-none}) held by another session. Driving it raw corrupts that session's QA run (incident 2026-07-27). If the lease is yours: EMU_LEASE_SESSION=$SESS <your command>. Otherwise get your own device: bash .claude/scripts/setup-ar-emulator.sh — do NOT set EMU_LEASE_TAKEOVER=1, this guard refuses it." >&2
              exit 2
            done
        fi
        ;;
    esac
    exit 0
    ;;

  # ========================================================= PostToolUse Bash
  # PostToolUse Bash / Edit|Write branches REMOVED 2026-08-11.
  # They emitted 14 `remind` lines — one per edited version file, per platform source
  # tree, per UI file. Each fired AFTER the action, so it had no lever: the edit had
  # already happened. Each was redundant with either a BLOCKING gate (the VERSION_NAME
  # guard on `git commit`, below) or with CLAUDE.md. And each injected 30-60 words into
  # a context that is re-read on every later turn. Measured cost of that pattern:
  # 2,280 edits in 30 days, each forking a shell to print advice nobody could act on.
  # Rule: a hook either BLOCKS or it does not exist. See memory
  # `project_resume_cost_hook.md` — an alert without a lever gets removed.

  # ================================================ PostToolUse EnterWorktree
  post-worktree)
    remind 'PARALLEL WORKTREE — claim the issue before writing code: bash .claude/scripts/claim.sh --check <issue#> then bash .claude/scripts/claim.sh <issue#>. Sibling worktrees right now:'
    ROOT="$(repo_root)"
    [ -n "$ROOT" ] && git -C "$ROOT" worktree list 2>/dev/null | sed 's/^/  /' >&2
    exit 0
    ;;

  *)
    exit 0
    ;;
esac

exit 0
