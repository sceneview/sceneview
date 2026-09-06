#!/usr/bin/env bash
# codex-delegate.sh — the single entry point for delegating work to Codex CLI.
#
# Claude Code is the Lead Developer; Codex is a delegated developer. Every call
# to Codex from a Claude session goes through this script, never through a raw
# `codex` invocation, because the billing and isolation guarantees below live
# here and nowhere else.
#
# THE BILLING INVARIANT (the reason this script exists)
#   Codex must bill the ChatGPT subscription, never the pay-per-token OpenAI
#   API. Two independent mechanisms enforce that, and BOTH must hold:
#     1. preflight  — `codex login status` must report ChatGPT, and
#        ~/.codex/auth.json must carry auth_mode=chatgpt with a null
#        OPENAI_API_KEY. Anything else is exit 2 and no call is made.
#     2. env scrub  — every API-key-shaped variable is stripped from the child
#        environment with `env -u`. Even if a key is exported later, Codex
#        cannot see it and cannot silently fall back to API billing.
#   Banned flags are rejected before exec: --with-api-key, --oss,
#   --dangerously-bypass-approvals-and-sandbox, --dangerously-bypass-hook-trust.
#   Preflight runs again AFTER every call: a mid-run switch to API billing
#   would be a serious regression and must not pass unnoticed.
#
# ISOLATION
#   `implement` writes files, so it refuses to run in the caller's own worktree
#   unless --here is passed. Use --new-worktree to get a dedicated branch and
#   worktree, which is the intended path for anything non-trivial.
#
# Usage:
#   codex-delegate.sh check
#   codex-delegate.sh ask       [opts] (<prompt> | --file F | -)
#   codex-delegate.sh review    [opts] [--base BRANCH | --uncommitted | --commit SHA]
#   codex-delegate.sh implement [opts] (<prompt> | --file F | -)
#
# Common opts: --label NAME  --model M  --timeout SECS  --dir PATH
#              --new-worktree BRANCH   --here   --schema FILE   --file F
#              --effort low|medium|high|xhigh   (default: the model's own)
#
# MODEL POLICY
#   The default model is pinned below (DEFAULT_MODEL) and passed explicitly on
#   every call. A Codex CLI update that changes its own bundled default — 0.153.4
#   makes gpt-6-astra the default when nothing is configured — must not silently
#   move every delegation onto a scarcer allowance. Opt in per call with --model
#   (e.g. --model gpt-6-astra), or globally with CODEX_DELEGATE_MODEL.
#
#   Two corrections applied on 2026-09-06, both from measurement, not preference:
#
#   1. ASTRA IMPLIES effort=high. Measured on 2026-09-06: at its own default
#      effort, gpt-6-astra returned "no actionable regressions" on a diff where
#      it finds two real bugs at effort high. Astra without high is the scarce
#      allowance bought at the cheap reasoning — the worst of both. So an
#      explicit --model gpt-6-astra with no --effort now gets high, out loud.
#
#   2. A PROMPT TOO BIG FOR SOL ESCALATES TO ASTRA, for `ask` only. Every
#      gpt-5.6-* model tops out at 272K tokens of context; gpt-6-astra takes
#      ~922K of input. Reading a dead session transcript, a whole module or a
#      log dump is the one job Astra can do that nothing else here can. Handing
#      such a prompt to Sol does not fail loudly — it truncates. Above
#      ASK_ESCALATE_BYTES the script switches, says so, and stays switchable off
#      with an explicit --model.
#
#   What did NOT change, and why. `implement` stays on Sol. On the ChatGPT Plus
#   plan, three parallel Astra implements at effort high burned 206K tokens in
#   nine minutes and exhausted the whole 5-hour window — for EVERY model, Sol
#   included (measured 2026-09-06 02:19 → 02:27, reset announced for 04:15).
#   Astra on `implement` is a deliberate, one-at-a-time choice for a hard issue
#   on a fresh window, never a default.
#
# Exit codes: 0 ok · 1 codex failed · 2 preflight refused (auth/binary/flags)
#             3 quota or rate limit hit — tell Thomas, never work around it
#             4 timed out
#
# Logs and results land in .claude/data/codex/ (gitignored).
#
# Written for bash 3.2 (the system bash on this Mac): empty-array expansions
# use the ${a[@]+"${a[@]}"} idiom because "${a[@]}" is an unbound-variable
# error there under `set -u`.

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
LOG_DIR="$REPO_ROOT/.claude/data/codex"
STAMP="$(date +%Y%m%d-%H%M%S)"

die()  { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit "${2:-1}"; }
info() { printf '\033[36m· %s\033[0m\n' "$1" >&2; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$1" >&2; }

# ---------------------------------------------------------------- codex binary
# Codex is installed under nvm and is NOT on the default PATH of a Claude
# session. Resolving it by hand is deliberate: `command -v codex` alone returns
# "not found" here, which has already produced three false "codex is not
# installed" conclusions on this machine.
# Canonical install locations are tried BEFORE $PATH. Codex's own review of
# this script flagged that trusting `command -v` first lets a `codex` earlier in
# PATH answer every safety probe itself. Preferring absolute, known-good paths
# closes the easy case; a PATH-only hit is still accepted (some installs are
# legitimately elsewhere) but is reported so it is never silent.
resolve_codex() {
  local c
  for c in "$HOME"/.nvm/versions/node/*/bin/codex \
           "$HOME"/.local/bin/codex \
           /opt/homebrew/bin/codex \
           /usr/local/bin/codex \
           "$HOME"/.volta/bin/codex \
           "$HOME"/.bun/bin/codex; do
    [ -x "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  if command -v codex >/dev/null 2>&1; then
    printf '\033[36m· codex resolved via PATH (outside canonical locations): %s\033[0m\n' \
      "$(command -v codex)" >&2
    command -v codex; return 0
  fi
  return 1
}

CODEX_BIN="$(resolve_codex)" || die "Codex CLI not found. Install it with: npm i -g @openai/codex" 2
CODEX_PATH_PREFIX="$(dirname "$CODEX_BIN")"   # node shim needs its own bin dir

# --------------------------------------------------------------- env scrubbing
# Deliberately broader than what Codex reads today: a future release adding a
# new key variable must not silently reopen API billing.
SCRUB="OPENAI_API_KEY CODEX_API_KEY OPENAI_BASE_URL OPENAI_ORGANIZATION \
OPENAI_ORG_ID OPENAI_PROJECT OPENAI_PROJECT_ID CODEX_ACCESS_TOKEN \
AZURE_OPENAI_API_KEY OPENAI_API_BASE"

UNSET_ARGS=()
for v in $SCRUB; do UNSET_ARGS+=(-u "$v"); done

# run_codex — for short, non-timed calls (login status).
run_codex() {
  PATH="$CODEX_PATH_PREFIX:$PATH" env "${UNSET_ARGS[@]}" "$CODEX_BIN" "$@"
}

# ------------------------------------------------------------------- preflight
# A fresh worktree inherits no gitignored file, so it has no local.properties and
# every Android build in it dies at configuration time. The obvious fix — copy the
# one from the main checkout — is a leak: on 2026-08-18 that file held a live
# sketchfab.api.key next to sdk.dir, and the sibling ar-model-viewer checkout holds
# the Play upload keystore's passwords in the same file.
#
# So the default is inverted. No value is ever copied except sdk.dir, which is a
# path and not a secret. Every other key keeps its NAME and loses its VALUE, which
# is what Gradle needs to configure: a key read as an empty string configures, a
# missing key can throw. Nothing has to be recognised as secret for this to hold —
# a key nobody has thought of yet is neutralised like the rest.
provision_local_properties() {
  local wt="$1" src="$REPO_ROOT/local.properties" dst="$1/local.properties"
  [ -f "$src" ] || return 0
  [ -f "$dst" ] && return 0
  awk -F= '
    /^[[:space:]]*#/ || NF == 0 { next }
    {
      key = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key == "") next
      if (key == "sdk.dir") { print; kept++ } else { print key "="; blanked++ }
    }
    END { printf "%d %d\n", kept, blanked > "/dev/stderr" }
  ' "$src" > "$dst" 2> /tmp/codex-lp-counts.$$
  read -r kept blanked < /tmp/codex-lp-counts.$$ || true
  rm -f /tmp/codex-lp-counts.$$
  ok "local.properties: sdk.dir kept, ${blanked:-0} other key(s) blanked (no value copied)"
}

preflight() {
  local quiet="${1:-}"
  # CODEX_HOME wins when set — Codex itself honours it, so reading ~/.codex
  # unconditionally could validate a credential file that is NOT the one used
  # for billing, which would defeat the whole point of this check.
  local auth="${CODEX_HOME:-$HOME/.codex}/auth.json"

  [ -f "$auth" ] || die "No Codex credentials. Run: codex login (ChatGPT flow)" 2

  # Authoritative check first — the CLI's own view of its credentials.
  local status
  status="$(run_codex login status 2>&1)" || die "codex login status failed: $status" 2
  case "$status" in
    *ChatGPT*|*chatgpt*) : ;;
    *API*key*|*api*key*|*API\ key*)
      die "Codex is authenticated with an API KEY (pay-per-token billing). Refusing. Switch back to ChatGPT: codex logout && codex login" 2 ;;
    *) die "Codex authentication mode is UNDETERMINED ($status) — do not delegate until this is clarified" 2 ;;
  esac

  # Second, independent check on the credential file itself.
  local mode key
  # Path goes through argv, never interpolated into the source literal: a
  # single quote in CODEX_HOME/HOME would otherwise break out and execute.
  mode="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("auth_mode"))' "$auth" 2>/dev/null)"
  key="$(python3 -c 'import json,sys;print("SET" if json.load(open(sys.argv[1])).get("OPENAI_API_KEY") else "NONE")' "$auth" 2>/dev/null)"
  [ "$mode" = "chatgpt" ] || die "auth.json: auth_mode=$mode (expected chatgpt). Refusing." 2
  [ "$key" = "NONE" ]     || die "auth.json carries an OpenAI API key. Refusing — API billing is not authorised." 2

  if [ "$quiet" != "quiet" ]; then
    # Through run_codex, never the raw binary: the codex shim is a node script
    # and needs its own bin dir on PATH. A raw call prints "node: No such file
    # or directory" INSIDE a green line — a false green, which this repo treats
    # as the most expensive class of bug.
    local ver
    ver="$(run_codex --version 2>&1)" || die "codex --version failed: $ver" 2
    case "$ver" in
      *codex*) : ;;
      *) die "Unexpected output from codex --version: $ver" 2 ;;
    esac
    ok "Codex $ver — $CODEX_BIN"
    ok "Auth: ChatGPT (auth_mode=chatgpt, no API key in auth.json)"
    local leak="" v val
    for v in $SCRUB; do
      eval "val=\${$v:-}"
      [ -n "$val" ] && leak="$leak $v"
    done
    if [ -n "$leak" ]; then
      info "Present in the environment but SCRUBBED from Codex:$leak"
    else
      ok "No API-key variable in the environment"
    fi
  fi
}

reject_banned_flags() {
  local a
  for a in "$@"; do
    case "$a" in
      --with-api-key|--with-api-key=*|--with-access-token|--with-access-token=*|\
      --oss|--oss=*|\
      --dangerously-bypass-approvals-and-sandbox*|--dangerously-bypass-hook-trust*)
        # The glued `--with-api-key=sk-...` form used to fall through here,
        # land in REST, and be forwarded verbatim to codex: a key on argv is
        # invisible to the env scrub (env vars only) AND to both preflights
        # (auth.json + login status, never argv), so it defeated the whole
        # billing invariant. Match both spellings.
        die "Flag forbidden by the delegation policy: $a" 2 ;;
    esac
  done
}

# check_quota_and_exit <log> <codex-exit-code>
#
# A quota block is TERMINAL: Codex exits non-zero and the message is the last
# thing it prints. Both conditions are required here, and only the tail is
# scanned, because scanning the whole log for these words matched THIS script's
# own source when Codex was asked to read it — a false "quota reached" on a
# call that had in fact succeeded. Requiring rc != 0 makes that structurally
# impossible: a successful call is never a quota block.
check_quota_and_exit() {
  local log="$1" rc="$2"
  [ "$rc" -eq 0 ] && return 0
  # An oversized prompt must NOT be read as a quota block. Measured 2026-09-06:
  # a 2,043,968-character prompt was refused by the CLI itself with
  #   turn/start failed: Input exceeds the maximum length of 1048576 characters
  # and the tail of that log also carried the words "session limit" and
  # "rate_limit" — because they were inside the PROMPT (a transcript quoting an
  # earlier error). The quota grep matched the prompt's own text and reported a
  # plan limit that did not exist. Named causes are checked before word-matching.
  if tail -40 "$log" 2>/dev/null | grep -qE "input_too_large|Input exceeds the maximum length"; then
    printf '\033[31m✗ Prompt rejected by Codex: too large for the CLI.\033[0m\n' >&2
    printf '  The CLI caps one turn at %s characters, whatever the model window is.\n' \
      "$CLI_MAX_PROMPT_CHARS" >&2
    printf '  Split the input, or narrow it, and try again. This is NOT a quota block.\n' >&2
    printf '  Log: %s\n' "$log" >&2
    exit 1
  fi
  if tail -25 "$log" 2>/dev/null | grep -qiE "usage limit|rate limit|quota|too many requests|plan limit|429"; then
    printf '\033[33m⚠ Codex reports a quota limit. No workaround will be attempted.\033[0m\n' >&2
    printf '  Log: %s\n' "$log" >&2
    printf '  → Tell Thomas. Do not switch account, do not fall back to the API.\n' >&2
    exit 3
  fi
}

# ------------------------------------------------------------------ invocation
# invoke <label> <sandbox> <timeout> <workdir> [codex args...]   (prompt on stdin)
invoke() {
  local label="$1" sandbox="$2" tmo="$3" workdir="$4"; shift 4
  mkdir -p "$LOG_DIR"
  local base="$LOG_DIR/$STAMP-$label"
  local log="$base.log" out="$base.out"
  local rc=0

  info "Codex → $label (sandbox=$sandbox, timeout=${tmo}s, cwd=$workdir)"

  # `timeout` execs a real binary, so the env scrub is inlined here rather than
  # routed through run_codex(): timeout cannot invoke a shell function.
  PATH="$CODEX_PATH_PREFIX:$PATH" timeout --foreground "$tmo" \
    env "${UNSET_ARGS[@]}" "$CODEX_BIN" "$@" \
      -C "$workdir" --sandbox "$sandbox" -o "$out" 2>&1 | tee "$log"
  rc="${PIPESTATUS[0]}"

  [ "$rc" -eq 124 ] && die "Codex exceeded the ${tmo}s timeout. Log: $log" 4
  check_quota_and_exit "$log" "$rc"
  preflight quiet          # billing mode must be unchanged after the call
  [ "$rc" -eq 0 ] || die "Codex failed (exit $rc). Log: $log" 1

  ok "Result: $out"
  ok "Full log: $log"
  return 0
}

# ----------------------------------------------------------------- subcommands
CMD="${1:-}"; shift || true
reject_banned_flags ${1+"$@"}

DEFAULT_MODEL="${CODEX_DELEGATE_MODEL:-gpt-5.6-sol}"
LABEL="" MODEL="" EFFORT="" TIMEOUT="" DIR="" NEW_WT="" HERE="" SCHEMA="" FILE=""
REST=()
while [ $# -gt 0 ]; do
  case "$1" in
    --label)        LABEL="$2"; shift 2 ;;
    --model)        MODEL="$2"; shift 2 ;;
    --effort)       EFFORT="$2"; shift 2 ;;
    --timeout)      TIMEOUT="$2"; shift 2 ;;
    --dir)          DIR="$2"; shift 2 ;;
    --new-worktree) NEW_WT="$2"; shift 2 ;;
    --here)         HERE=1; shift ;;
    --schema)       SCHEMA="$2"; shift 2 ;;
    --file)         FILE="$2"; shift 2 ;;
    *)              REST+=("$1"); shift ;;
  esac
done

# Prompt size, in bytes, above which `ask` moves from Sol to Astra. Sol's window
# is 272K tokens; at the ~3.5 bytes/token this repo's Kotlin and transcripts
# actually measure, 800K bytes is already ~230K tokens, which leaves room for the
# reply and for the harness's own preamble. Override with the env var to test.
ASK_ESCALATE_BYTES="${CODEX_DELEGATE_ASK_ESCALATE_BYTES:-800000}"
LONG_CONTEXT_MODEL="${CODEX_DELEGATE_LONG_CONTEXT_MODEL:-gpt-6-astra}"

# The CLI's own hard cap on one turn's input, in characters. Measured 2026-09-06
# against codex-cli 0.153.4: a 2,043,968-character prompt is refused outright with
#   Input exceeds the maximum length of 1048576 characters (code -32602)
# This is a CLI limit, NOT the model's window: gpt-6-astra advertises ~922K tokens
# of input, and this cap stops the prompt around 260-300K. Astra's real advantage
# through `codex exec` is therefore much smaller than its spec sheet suggests —
# roughly "a bit more than Sol", not "four times Sol". Refuse early and say why,
# rather than spend minutes uploading a prompt that will bounce.
CLI_MAX_PROMPT_CHARS="${CODEX_DELEGATE_MAX_PROMPT_CHARS:-1048576}"

# resolve_model_args — fills EFFECTIVE_MODEL and CODEX_ARGS from MODEL/EFFORT.
# Called once up front, and again by `ask` if the prompt turns out to need the
# long-context model. The model is always passed explicitly (see MODEL POLICY),
# never left to the CLI's own default.
EFFECTIVE_MODEL="" EFFECTIVE_EFFORT=""
resolve_model_args() {
  EFFECTIVE_MODEL="${MODEL:-$DEFAULT_MODEL}"
  EFFECTIVE_EFFORT="$EFFORT"
  # Astra implies high: see MODEL POLICY note 1.
  if [ -z "$EFFECTIVE_EFFORT" ]; then
    case "$EFFECTIVE_MODEL" in
      gpt-6-*) EFFECTIVE_EFFORT="high"
               info "$EFFECTIVE_MODEL: reasoning effort defaulted to high (policy)" ;;
    esac
  fi
  CODEX_ARGS=(-m "$EFFECTIVE_MODEL")
  [ -n "$EFFECTIVE_EFFORT" ] && CODEX_ARGS+=(-c "model_reasoning_effort=\"$EFFECTIVE_EFFORT\"")
  [ -n "$SCHEMA" ] && CODEX_ARGS+=(--output-schema "$SCHEMA")
  return 0
}
resolve_model_args

get_prompt() {
  if [ -n "$FILE" ]; then
    [ -f "$FILE" ] || die "Prompt file not found: $FILE"
    cat "$FILE"; return
  fi
  local first="${REST[0]:-}"
  case "$first" in
    "") die "Empty prompt. Pass text, --file F, or - for stdin." ;;
    -)  cat ;;
    *)  printf '%s' "$first" ;;
  esac
}

case "$CMD" in
  check)
    preflight
    ok "Claude → Codex delegation is operational"
    ;;

  ask)
    # Read-only second opinion / analysis. Codex cannot modify the tree.
    preflight quiet
    PROMPT="$(get_prompt)" || exit 2
    [ -n "$PROMPT" ] || die "Empty prompt." 2
    # A prompt Sol cannot hold does not fail on Sol — it truncates, and the
    # answer looks complete. Escalate rather than lose the tail. See MODEL
    # POLICY note 2. An explicit --model always wins.
    PROMPT_BYTES=${#PROMPT}
    if [ "$PROMPT_BYTES" -gt "$CLI_MAX_PROMPT_CHARS" ]; then
      die "Prompt is $PROMPT_BYTES characters; the Codex CLI refuses more than $CLI_MAX_PROMPT_CHARS in one turn (this is the CLI's cap, not the model's window). Split it or narrow it." 2
    fi
    if [ -z "$MODEL" ] && [ "$PROMPT_BYTES" -gt "$ASK_ESCALATE_BYTES" ]; then
      info "Prompt is $PROMPT_BYTES bytes (> $ASK_ESCALATE_BYTES): $DEFAULT_MODEL would truncate it."
      MODEL="$LONG_CONTEXT_MODEL"
      resolve_model_args
      info "Escalated to $EFFECTIVE_MODEL for this call. Pass --model to override."
    fi
    printf '%s' "$PROMPT" | invoke "${LABEL:-ask}" read-only "${TIMEOUT:-600}" "${DIR:-$REPO_ROOT}" \
      exec ${CODEX_ARGS[@]+"${CODEX_ARGS[@]}"} -
    ;;

  review)
    # Independent adversarial review. Read-only by construction.
    preflight quiet
    mkdir -p "$LOG_DIR"
    LOG="$LOG_DIR/$STAMP-${LABEL:-review}.log"
    RDIR="${DIR:-$REPO_ROOT}"
    # `codex review` rejects a free-text prompt combined with a target selector
    # ("the argument '--uncommitted' cannot be used with '[PROMPT]'"), and the
    # error only surfaces after the call is set up. Catch it here with the
    # workaround spelled out, rather than letting it look like a Codex failure.
    HAS_SEL="" HAS_PROMPT="" SKIP_VAL=""
    for a in ${REST[@]+"${REST[@]}"}; do
      if [ -n "$SKIP_VAL" ]; then SKIP_VAL=""; continue; fi   # value of --base/--commit/--title
      case "$a" in
        --uncommitted)          HAS_SEL=1 ;;
        --base|--commit|--title) HAS_SEL=1; SKIP_VAL=1 ;;
        --*)                    : ;;
        *)                      HAS_PROMPT=1 ;;
      esac
    done
    if [ -n "$HAS_SEL" ] && [ -n "$HAS_PROMPT" ]; then
      die "codex review rejects free-text instructions combined with --uncommitted/--base/--commit. Use 'review --base main' alone, or pass instructions through 'ask'." 2
    fi
    # `codex review` has no -m flag (0.149.0 .. 0.153.x): the model goes through
    # the generic -c override, so the pinned default applies here too.
    REVIEW_ARGS=(-c "model=\"$EFFECTIVE_MODEL\"")
    [ -n "$EFFECTIVE_EFFORT" ] && REVIEW_ARGS+=(-c "model_reasoning_effort=\"$EFFECTIVE_EFFORT\"")
    info "Codex → review (read-only, cwd=$RDIR, model=$EFFECTIVE_MODEL)"
    ( cd "$RDIR" && PATH="$CODEX_PATH_PREFIX:$PATH" timeout --foreground "${TIMEOUT:-900}" \
        env "${UNSET_ARGS[@]}" "$CODEX_BIN" review "${REVIEW_ARGS[@]}" ${REST[@]+"${REST[@]}"} ) 2>&1 | tee "$LOG"
    RC="${PIPESTATUS[0]}"
    [ "$RC" -eq 124 ] && die "codex review timed out. Log: $LOG" 4
    check_quota_and_exit "$LOG" "$RC"
    preflight quiet
    [ "$RC" -eq 0 ] || die "codex review failed (exit $RC). Log: $LOG" 1
    ok "Review: $LOG"
    ;;

  implement)
    # The only write-capable mode. Isolation is mandatory unless --here.
    preflight quiet
    PROMPT="$(get_prompt)" || exit 2
    [ -n "$PROMPT" ] || die "Empty prompt." 2

    if [ -n "$NEW_WT" ]; then
      WT_PATH="$REPO_ROOT/.claude/worktrees/codex-$NEW_WT"
      if [ -d "$WT_PATH" ]; then
        info "Reusing existing worktree: $WT_PATH"
      else
        git -C "$REPO_ROOT" worktree add -b "codex/$NEW_WT" "$WT_PATH" >/dev/null 2>&1 \
          || git -C "$REPO_ROOT" worktree add "$WT_PATH" "codex/$NEW_WT" >/dev/null \
          || die "Could not create worktree: $WT_PATH"
        ok "Dedicated worktree: $WT_PATH (branch codex/$NEW_WT)"
      fi
      provision_local_properties "$WT_PATH"
      DIR="$WT_PATH"
    fi

    TARGET="${DIR:-$REPO_ROOT}"
    [ -d "$TARGET" ] || die "Target directory does not exist: $TARGET"
    # Compare WORKTREE ROOTS, not literal cwd: launched from any subdirectory
    # of the repo, $(pwd) != REPO_ROOT and the guard silently did not fire,
    # handing Codex workspace-write over the caller's own tree — exactly the
    # concurrent-edit hazard it exists to prevent.
    TARGET_ROOT="$(git -C "$TARGET" rev-parse --show-toplevel 2>/dev/null || (cd "$TARGET" && pwd))"
    if [ -z "$NEW_WT" ] && [ -z "$HERE" ] && [ "$TARGET_ROOT" = "$REPO_ROOT" ]; then
      die "implement refuses to write in the caller's own worktree without isolation. Use --new-worktree NAME, --dir PATH, or --here if concurrent writes are genuinely ruled out." 2
    fi

    printf '%s' "$PROMPT" | invoke "${LABEL:-implement}" workspace-write "${TIMEOUT:-1800}" "$TARGET" \
      exec ${CODEX_ARGS[@]+"${CODEX_ARGS[@]}"} -
    info "Git state of $TARGET after Codex ran (inspect before integrating):"
    git -C "$TARGET" status --short >&2
    ;;

  ""|-h|--help|help)
    sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'
    ;;

  *)
    die "Unknown subcommand: $CMD (check|ask|review|implement)" 2 ;;
esac
