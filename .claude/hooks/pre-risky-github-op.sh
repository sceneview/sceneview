#!/usr/bin/env bash
# Claude Code PreToolUse hook — bloque les opérations GitHub à risque d'abuse detection.
# Installé après le ban GitHub 2026-04-13 (ticket #4280656).
#
# Logique:
# - Lit le JSON du tool call sur stdin
# - Pour Bash: analyse la commande
# - Bloque (exit 2) si pattern dangereux + affiche la raison sur stderr
# - Laisse passer (exit 0) sinon
#
# Escape hatches:
# - SV_BATCH_REBASE=1 — bypasses the force-push rate-limit for the calling
#   command. Use this for a known-good multi-PR rebase batch (e.g. an ARCore
#   audit sprint that needs to force-push 9 rebased branches in one session).
#   The bypass logs an explicit notice to stderr so it stays auditable. Do NOT
#   manipulate `~/.claude/logs/force-push.log` — that's the anti-pattern this
#   escape hatch exists to replace. See issue
#   https://github.com/sceneview/sceneview/issues/1796.

set -euo pipefail

input=$(cat)

# On n'agit que sur Bash
tool=$(echo "$input" | jq -r '.tool_name // empty' 2>/dev/null || true)
if [ "$tool" != "Bash" ]; then
  exit 0
fi

cmd=$(echo "$input" | jq -r '.tool_input.command // empty' 2>/dev/null || true)
[ -z "$cmd" ] && exit 0

# Allowlist d'orgs "internes" — PRs sur ces orgs sont sans limite
internal_orgs_regex='(sceneview|sceneview-tools|mcp-tools-lab|thomasgorisse|ThomasGorisse|anthropics)'

block() {
  local reason="$1"
  echo "❌ CLAUDE CODE HOOK BLOCKED — $reason" >&2
  echo "" >&2
  echo "Raison: après le ban GitHub du 2026-04-13 (ticket #4280656)," >&2
  echo "certaines opérations GitHub sont bloquées pour éviter une récidive." >&2
  echo "" >&2
  echo "Si c'est vraiment nécessaire: demande à Thomas de l'exécuter manuellement," >&2
  echo "OU édite ~/.claude/hooks/pre-risky-github-op.sh pour ajuster." >&2
  exit 2
}

# ------------------------------------------------------------------
# 1. Bloque gh pr create / gh issue create sur repos externes
# ------------------------------------------------------------------
if echo "$cmd" | grep -qE '\bgh\s+(pr|issue)\s+create\b'; then
  # Extrait le --repo si présent, sinon utilise le remote origin courant
  repo=$(echo "$cmd" | grep -oE '(--repo|-R)[[:space:]]+[^[:space:]]+' | head -1 | sed 's/.*[[:space:]]//')
  if [ -z "$repo" ]; then
    # Sans --repo, gh utilise le cwd remote. On laisse passer en supposant que c'est interne,
    # MAIS on ajoute un rappel.
    echo "⚠️  Claude Code: 'gh pr create' sans --repo — utilise le remote du cwd." >&2
    echo "   Vérifie que c'est bien un repo interne (sceneview/*, mcp-tools-lab/*, …)." >&2
    exit 0
  fi
  if ! echo "$repo" | grep -qE "^$internal_orgs_regex/"; then
    block "gh pr/issue create sur un repo externe: $repo
Règle feedback_no_pr_burst: 1 PR externe par semaine MAX, soumission manuelle via navigateur."
  fi
fi

# ------------------------------------------------------------------
# 2. Bloque les force-push en série (>1 dans les 24h)
#    Escape: SV_BATCH_REBASE=1 pour un batch de rebase multi-branches légitime.
# ------------------------------------------------------------------
if echo "$cmd" | grep -qE '\bgit\s+push\b.*(--force|--force-with-lease|\s-f\b)'; then
  # Escape hatch for legitimate multi-PR rebase batches (issue #1796).
  # Set SV_BATCH_REBASE=1 in the environment of the calling command to bypass
  # the 1/24h cap. The bypass is logged for auditability.
  if [ "${SV_BATCH_REBASE:-0}" = "1" ]; then
    echo "ℹ️  Claude Code hook: batch-rebase mode (SV_BATCH_REBASE=1) — force-push rate-limit bypassed for this call." >&2
    # Still record the push so the regular 1/24h counter resumes correctly once
    # the batch is over.
    log="$HOME/.claude/logs/force-push.log"
    mkdir -p "$(dirname "$log")"
    touch "$log"
    echo "$(date +%s)  [SV_BATCH_REBASE] $cmd" >> "$log"
    exit 0
  fi

  log="$HOME/.claude/logs/force-push.log"
  mkdir -p "$(dirname "$log")"
  touch "$log"
  now=$(date +%s)
  cutoff=$((now - 86400))
  recent=$(awk -v c="$cutoff" '$1 > c' "$log" | wc -l | tr -d ' ')
  if [ "$recent" -ge 1 ]; then
    block "Force-push détecté — 1 force-push déjà effectué dans les 24h.
Règle: max 1 force-push / 24h pour éviter les patterns bot-like (cause aggravante du ban 2026-04-13).
Pour un batch de rebase multi-branches légitime, relance la commande avec SV_BATCH_REBASE=1:
  SV_BATCH_REBASE=1 git push --force-with-lease ...
Voir issue #1796 pour le rationale. Ne pas effacer $log — c'est l'anti-pattern que cette escape hatch remplace."
  fi
  # OK: log ce push pour les 24h suivantes
  echo "$now  $cmd" >> "$log"
fi

# ------------------------------------------------------------------
# 3. Bloque les mass-delete / mass-archive de repos via gh
# ------------------------------------------------------------------
if echo "$cmd" | grep -qE '\bgh\s+repo\s+(delete|archive)\b'; then
  block "gh repo delete/archive détecté — opération destructive.
Exécute-la manuellement avec confirmation explicite de Thomas."
fi

# ------------------------------------------------------------------
# 4. Bloque les bursts de stars/follows
# ------------------------------------------------------------------
if echo "$cmd" | grep -qE '\bgh\s+api\s+.*\b(starred|following)\b.*\s(-X\s*(PUT|POST|DELETE)|--method\s*(PUT|POST|DELETE))'; then
  block "Opération d'étoile/follow via API détectée.
Mass-star et mass-follow sont des signaux de spam GitHub. Fais-le manuellement."
fi

# ------------------------------------------------------------------
# 5. Signale (sans bloquer) les bursts de commits
# ------------------------------------------------------------------
if echo "$cmd" | grep -qE '\bgit\s+commit\b'; then
  log="$HOME/.claude/logs/commits-today.log"
  mkdir -p "$(dirname "$log")"
  touch "$log"
  today=$(date +%Y-%m-%d)
  count=$(grep -c "^$today" "$log" 2>/dev/null || echo 0)
  if [ "$count" -ge 50 ]; then
    echo "⚠️  Claude Code: déjà $count commits aujourd'hui." >&2
    echo "   Au-delà de 50 commits/jour le pattern devient bot-like (signal aggravant du ban 2026-04-13)." >&2
    echo "   Envisage de pauser." >&2
  fi
  echo "$today  $(pwd)" >> "$log"
fi

exit 0
