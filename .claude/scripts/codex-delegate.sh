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
    printf '\033[36m· codex résolu via PATH (hors emplacements canoniques): %s\033[0m\n' \
      "$(command -v codex)" >&2
    command -v codex; return 0
  fi
  return 1
}

CODEX_BIN="$(resolve_codex)" || die "Codex CLI introuvable. Installer: npm i -g @openai/codex" 2
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
preflight() {
  local quiet="${1:-}"
  # CODEX_HOME wins when set — Codex itself honours it, so reading ~/.codex
  # unconditionally could validate a credential file that is NOT the one used
  # for billing, which would defeat the whole point of this check.
  local auth="${CODEX_HOME:-$HOME/.codex}/auth.json"

  [ -f "$auth" ] || die "Pas d'auth Codex. Lancer: codex login (flux ChatGPT)" 2

  # Authoritative check first — the CLI's own view of its credentials.
  local status
  status="$(run_codex login status 2>&1)" || die "codex login status a échoué: $status" 2
  case "$status" in
    *ChatGPT*|*chatgpt*) : ;;
    *API*key*|*api*key*|*API\ key*)
      die "Codex est authentifié par CLÉ API (facturation à l'usage). Refus. Repasser en ChatGPT: codex logout && codex login" 2 ;;
    *) die "Mode d'authentification Codex INDÉTERMINÉ ($status) — ne pas déléguer avant clarification" 2 ;;
  esac

  # Second, independent check on the credential file itself.
  local mode key
  mode="$(python3 -c "import json;print(json.load(open('$auth')).get('auth_mode'))" 2>/dev/null)"
  key="$(python3 -c "import json;d=json.load(open('$auth'));print('SET' if d.get('OPENAI_API_KEY') else 'NONE')" 2>/dev/null)"
  [ "$mode" = "chatgpt" ] || die "auth.json: auth_mode=$mode (attendu chatgpt). Refus." 2
  [ "$key" = "NONE" ]     || die "auth.json contient une clé API OpenAI. Refus — la facturation API n'est pas autorisée." 2

  if [ "$quiet" != "quiet" ]; then
    # Through run_codex, never the raw binary: the codex shim is a node script
    # and needs its own bin dir on PATH. A raw call prints "node: No such file
    # or directory" INSIDE a green line — a false green, which this repo treats
    # as the most expensive class of bug.
    local ver
    ver="$(run_codex --version 2>&1)" || die "codex --version a échoué: $ver" 2
    case "$ver" in
      *codex*) : ;;
      *) die "Sortie inattendue de codex --version: $ver" 2 ;;
    esac
    ok "Codex $ver — $CODEX_BIN"
    ok "Authentification: ChatGPT (auth_mode=chatgpt, aucune clé API dans auth.json)"
    local leak="" v
    for v in $SCRUB; do
      eval "val=\${$v:-}"
      [ -n "$val" ] && leak="$leak $v"
    done
    if [ -n "$leak" ]; then
      info "Présentes dans l'environnement mais NEUTRALISÉES pour Codex:$leak"
    else
      ok "Aucune variable de clé API dans l'environnement"
    fi
  fi
}

reject_banned_flags() {
  local a
  for a in "$@"; do
    case "$a" in
      --with-api-key|--dangerously-bypass-approvals-and-sandbox|--dangerously-bypass-hook-trust|--oss)
        die "Option interdite par la politique de délégation: $a" 2 ;;
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
  if tail -25 "$log" 2>/dev/null | grep -qiE "usage limit|rate limit|quota|too many requests|plan limit|429"; then
    printf '\033[33m⚠ Codex signale une limite de quota. Aucun contournement ne sera tenté.\033[0m\n' >&2
    printf '  Log: %s\n' "$log" >&2
    printf '  → Prévenir Thomas. Ne pas changer de compte, ne pas basculer sur l API.\n' >&2
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

  [ "$rc" -eq 124 ] && die "Codex a dépassé le timeout de ${tmo}s. Log: $log" 4
  check_quota_and_exit "$log" "$rc"
  preflight quiet          # billing mode must be unchanged after the call
  [ "$rc" -eq 0 ] || die "Codex a échoué (code $rc). Log: $log" 1

  ok "Résultat: $out"
  ok "Log complet: $log"
  return 0
}

# ----------------------------------------------------------------- subcommands
CMD="${1:-}"; shift || true
reject_banned_flags ${1+"$@"}

LABEL="" MODEL="" TIMEOUT="" DIR="" NEW_WT="" HERE="" SCHEMA="" FILE=""
REST=()
while [ $# -gt 0 ]; do
  case "$1" in
    --label)        LABEL="$2"; shift 2 ;;
    --model)        MODEL="$2"; shift 2 ;;
    --timeout)      TIMEOUT="$2"; shift 2 ;;
    --dir)          DIR="$2"; shift 2 ;;
    --new-worktree) NEW_WT="$2"; shift 2 ;;
    --here)         HERE=1; shift ;;
    --schema)       SCHEMA="$2"; shift 2 ;;
    --file)         FILE="$2"; shift 2 ;;
    *)              REST+=("$1"); shift ;;
  esac
done

CODEX_ARGS=()
[ -n "$MODEL" ]  && CODEX_ARGS+=(-m "$MODEL")
[ -n "$SCHEMA" ] && CODEX_ARGS+=(--output-schema "$SCHEMA")

get_prompt() {
  if [ -n "$FILE" ]; then
    [ -f "$FILE" ] || die "Fichier de prompt introuvable: $FILE"
    cat "$FILE"; return
  fi
  local first="${REST[0]:-}"
  case "$first" in
    "") die "Prompt vide. Passer un texte, --file F, ou - pour stdin." ;;
    -)  cat ;;
    *)  printf '%s' "$first" ;;
  esac
}

case "$CMD" in
  check)
    preflight
    ok "Délégation Claude → Codex opérationnelle"
    ;;

  ask)
    # Read-only second opinion / analysis. Codex cannot modify the tree.
    preflight quiet
    PROMPT="$(get_prompt)" || exit 2
    [ -n "$PROMPT" ] || die "Prompt vide." 2
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
      die "codex review n'accepte pas d'instructions libres avec --uncommitted/--base/--commit. Soit 'review --base main' seul, soit passer les instructions via 'ask'." 2
    fi
    info "Codex → review (read-only, cwd=$RDIR)"
    ( cd "$RDIR" && PATH="$CODEX_PATH_PREFIX:$PATH" timeout --foreground "${TIMEOUT:-900}" \
        env "${UNSET_ARGS[@]}" "$CODEX_BIN" review ${REST[@]+"${REST[@]}"} ) 2>&1 | tee "$LOG"
    RC="${PIPESTATUS[0]}"
    [ "$RC" -eq 124 ] && die "codex review a dépassé le timeout. Log: $LOG" 4
    check_quota_and_exit "$LOG" "$RC"
    preflight quiet
    [ "$RC" -eq 0 ] || die "codex review a échoué (code $RC). Log: $LOG" 1
    ok "Review: $LOG"
    ;;

  implement)
    # The only write-capable mode. Isolation is mandatory unless --here.
    preflight quiet
    PROMPT="$(get_prompt)" || exit 2
    [ -n "$PROMPT" ] || die "Prompt vide." 2

    if [ -n "$NEW_WT" ]; then
      WT_PATH="$REPO_ROOT/.claude/worktrees/codex-$NEW_WT"
      if [ -d "$WT_PATH" ]; then
        info "Worktree existant réutilisé: $WT_PATH"
      else
        git -C "$REPO_ROOT" worktree add -b "codex/$NEW_WT" "$WT_PATH" >/dev/null 2>&1 \
          || git -C "$REPO_ROOT" worktree add "$WT_PATH" "codex/$NEW_WT" >/dev/null \
          || die "Création du worktree impossible: $WT_PATH"
        ok "Worktree dédié: $WT_PATH (branche codex/$NEW_WT)"
      fi
      DIR="$WT_PATH"
    fi

    TARGET="${DIR:-$REPO_ROOT}"
    [ -d "$TARGET" ] || die "Répertoire cible inexistant: $TARGET"
    if [ -z "$NEW_WT" ] && [ -z "$HERE" ] && [ "$(cd "$TARGET" && pwd)" = "$(pwd)" ]; then
      die "implement refuse d'écrire dans le worktree courant sans isolation. Utiliser --new-worktree NOM, --dir PATH, ou --here si l'écriture concurrente est réellement exclue." 2
    fi

    printf '%s' "$PROMPT" | invoke "${LABEL:-implement}" workspace-write "${TIMEOUT:-1800}" "$TARGET" \
      exec ${CODEX_ARGS[@]+"${CODEX_ARGS[@]}"} -
    info "État git de $TARGET après passage de Codex (à inspecter avant intégration):"
    git -C "$TARGET" status --short >&2
    ;;

  ""|-h|--help|help)
    sed -n '2,42p' "$0" | sed 's/^# \{0,1\}//'
    ;;

  *)
    die "Sous-commande inconnue: $CMD (check|ask|review|implement)" 2 ;;
esac
