# Délégation multi-LLM — Codex · Gemini/Antigravity · Kimi (exploration 2026-07-23)

> Session d'exploration : comment déléguer certaines tâches SceneView à des LLMs
> non-Claude, avec Claude Code comme **orchestrateur unique**. État : CLIs installés
> et sondés sur le Mac ; **aucun authentifié** (étape Thomas, §Auth).
> Entrée unique : [`llm-delegate.sh`](../scripts/llm-delegate.sh).

## 1. État des lieux (mesuré sur cette machine, 2026-07-23)

| Provider | CLI | Version | Headless | Sandbox | Auth | Piège mesuré |
|---|---|---|---|---|---|---|
| OpenAI Codex | `codex` (npm `@openai/codex`) | 0.145.0 | `codex exec "…"` + `codex exec review` | `--sandbox read-only\|workspace-write` | `codex login` (ChatGPT Plus/Pro) ou clé API ; `codex login status` rc=1 si déloggé | — |
| Google Gemini | `agy` (Antigravity CLI) | 1.1.5 | `agy -p "…"` (`--print-timeout` 5m def.) | `--sandbox` (restrictions terminal) | Sign-in Google interactif (TTY requis) | ⛔ rc=0 même déloggé — détecter « sign in » dans la sortie |
| Moonshot Kimi | `kimi` (uv `kimi-cli`) | 1.49.0 | `kimi --print --final-message-only -p "…"` | pas de sandbox ; `--yolo` = auto-approve | Setup interactif ou `MOONSHOT_API_KEY` | ⛔ rc=0 même déloggé — signature « LLM not set » |

⚠️ **`gemini` CLI n'existe plus** : Google l'a coupé le 18/06/2026 (comptes individuels),
remplacé par Antigravity CLI (`agy`, binaire Go, installeur curl officiel). Le free tier
personnel Google passe par Antigravity.

## 2. Pourquoi déléguer (par ordre de valeur)

1. **Second avis cross-vendor** — une review par un modèle d'une autre famille n'a pas
   les mêmes angles morts que 4 reviewers Claude. `codex exec review` est même un
   sous-commande dédiée. Toujours **ADVISORY** : jamais un gate de merge.
2. **Pools de quota indépendants** — le quota Claude Max est par modèle ; Codex
   (abonnement ChatGPT), Antigravity (free tier Google) et Kimi (API ~0,60 $/M in)
   sont des réservoirs séparés. Décharger le mécanique y préserve fable/opus pour le
   raisonnement dur (règle mémoire « routage modèle »).
3. **Contexte 1M de Gemini** — audits/synthèses sur très gros volumes (llms-full,
   sweeps doc) sans découpage.
4. **Coût marginal quasi nul pour le mécanique en masse** — Kimi K2.x est ~10-20×
   moins cher que les tiers premium.

## 3. Matrice de routage (extension de la règle mémoire existante)

| Tâche | Moteur | Mode |
|---|---|---|
| Orchestration, archi, debug retors, décisions, tout ce qui committe | **Claude (fable/opus)** — jamais délégué | — |
| Review adversariale 2e avis sur une PR | `codex exec review` + `agy -p` | read-only, ADVISORY dans le triptyque/review-fanout |
| Audit/synthèse gros contexte (doc-drift, llms-full) | `agy -p` (Gemini, 1M ctx) | read-only |
| Scaffolds, boilerplate de tests, flows Maestro, conversions mécaniques | `kimi` (ou codex) | `--write` dans un worktree/clone jeté uniquement |
| Recherche ponctuelle, question factuelle croisée | n'importe lequel (le moins cher dispo) | read-only |

## 4. Règles de sécurité (non négociables)

- Les LLMs externes ne **committent jamais, ne pushent jamais, ne touchent jamais `gh`**.
  Ils rendent du texte (réponse, diff, rapport) ; Claude review et applique.
- **Read-only par défaut** ; `--write` refusé hors worktree jeté/clone `/tmp` (garde
  codée dans le wrapper). Jamais `danger-full-access` / bypass d'approbations.
- **SKIP honnête** (#2343) : CLI absent ou déloggé → exit 3 + `SKIP:`, jamais un vert
  silencieux. `agy` et `kimi` rendent rc=0 déloggés → détection par signature de sortie.
- Étanchéité pro/perso et zéro secret dans les prompts délégués — mêmes règles que
  pour tout contenu sortant.

## 5. Auth — étape Thomas (une fois par CLI)

```bash
codex login          # OAuth ChatGPT (Plus/Pro requis) — ou codex login --api-key
agy                  # lancement nu → sign-in Google (free tier perso OK)
kimi                 # setup interactif — ou export MOONSHOT_API_KEY (clé → profile-private)
```

Coûts : Codex inclus dans ChatGPT Plus (20 $/m) ; Antigravity free tier compte Google
perso ; Kimi membership ~19 $/m ou API pay-as-you-go. **Aucun abonnement requis pour
commencer : Antigravity seul suffit à valider le pattern.**

## 6. Prochaines étapes

1. Thomas authentifie ce qu'il veut activer (au minimum `agy`, gratuit).
2. Smoke test : `bash .claude/scripts/llm-delegate.sh gemini "Résume llms.txt en 5 points"`.
3. Premier usage réel : brancher un avis externe ADVISORY dans `review-fanout`
   (une voix `codex exec review` + une voix `agy`), comparer la valeur sur 2-3 PRs.
4. Si la valeur est là : promouvoir en étape optionnelle du triptyque + documenter
   dans `.claude/workflows/README.md`.
