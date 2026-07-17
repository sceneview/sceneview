# Store publishing as code — exécution P2 (#2612, phase 2 de l'umbrella)

> Design doc court (méthodo v2) — session dédiée 2026-07-17. Le design doc COMPLET
> (inventaire + APIs vérifiées + verdict coût/valeur) est le commentaire du 2026-07-11
> sur #2612 ; ce fichier ne le répète pas, il fixe l'exécution du reste.
> P1 (détection : `store-preflight.sh`) est LIVRÉ (#2682, durci #2731/#2738) — ne pas re-livrer.

## Surfaces de config (SSOT) — inchangées, PAS de nouveau `store/` tree

| Surface | SSOT (existant) | Ajout P2 |
|---|---|---|
| Play texte + graphics | `samples/android-demo/distribution/play-store/<locale>/` | — |
| Play Data safety | `…/play-store/DATA_SAFETY.md` (prose, « transcrire à la main ») | `data-safety.csv` (format console-CSV, poussé par API) — Phase D |
| ASC texte | `samples/ios-demo/distribution/app-store/en-US/` | — |
| ASC screenshots | `samples/ios-demo/appstore-screenshots/{iphone-6.9,ipad-13}/` (committés, JAMAIS uploadés) | upload API — Phase B |
| Icônes / feature graphic | `branding/*.svg` → `branding/exports/` | check CI régénération — P3 drip |

Format déclaratif : le layout fastlane-style existant EST le format. Aucun YAML/JSON
nouveau, sauf le `data-safety.csv` (format d'export console officiel, seul format que
`applications.dataSafety` accepte).

## Architecture — `.claude/scripts/store-sync/`

Deux scripts Python exécutables, un par store, code path UNIQUE local + CI :

```
.claude/scripts/store-sync/
  play_listing.py   # extraction FIDÈLE du heredoc sync-listing de play-store.yml
  asc_listing.py    # nouveau : diff listing/screenshots ASC (read-only d'abord)
  test/             # unit tests logique pure (caps, mapping, diff, checksums)
.claude/scripts/test-store-sync.sh   # self-test style test-store-preflight.sh
```

- **`--dry-run` = défaut** : GET l'état live, imprime un diff champ/checksum
  (Play : `edits.listings.get` + `edits.images.list` SHA-256, edit abandonné ;
  ASC : localizations live + `appScreenshots.sourceFileChecksum`), n'écrit RIEN.
  Sans créds → SKIP honnête exit 0 (doctrine play-vitals/store-preflight).
- **`--apply`** : le comportement CI actuel, à l'identique (403 Play → warning +
  exit 0 #1386 ; delete-then-upload #1710 ; rollback edit ; caps + truncation).
  Les workflows passent `--apply` — les gates d'application NE CHANGENT PAS
  (minor-bump / workflow_dispatch / continue-on-error).
- JWT ASC : PyJWT comme app-store.yml (requirements réutilisés) ; en local le .p8
  vient de `APP_STORE_CONNECT_API_KEY_PATH` (même convention que store-preflight.sh).

## Ce que ça alimente

- **`play-store.yml`** (Phase A) : le job `sync-listing` appelle `play_listing.py --apply`
  au lieu du heredoc. Zéro changement de comportement, de gate ou de secret.
- **`app-store.yml`** (Phase B) : NOUVEAU step séparé « Upload App Store screenshots »
  (asc_listing.py --apply-screenshots), gated minor-bump/dispatch comme le texte.
  ⛔ Le script submit-for-review inline N'EST PAS touché (fraîchement réparé #2731 ;
  la 4.22.0 n'est toujours pas soumise — zone gelée tant que le cleanup ASC-side
  gated Thomas n'est pas fait).
- **`maintenance.yml`** (Phase C) : job hebdo `store-drift` → les 2 scripts en dry-run →
  drift = UNE issue dédupliquée par store (pattern play-reviews/store-preflight).
- **`release-checklist.sh`** (Phase C) : §17 advisory — drift affiché, jamais bloquant.
- **`store-preflight.sh`** : inchangé — il détecte les blockers humains (agreements,
  rejections, certs) ; store-sync détecte le drift de CONTENU. Complémentaires.

## Phases livrables (1 PR compilable/testable chacune, review-fanout avant merge)

- **A — fondation** : extraction `play_listing.py` (+ appel depuis play-store.yml) +
  `asc_listing.py` read-only + tests. Risque principal : régresser un fix accumulé →
  extraction ligne-à-ligne, la review compare heredoc vs script.
- **B — upload screenshots iOS** : reserve → PUT chunks → `uploaded:true`,
  skip-if-identical par checksum ; mapping `iphone-6.9→APP_IPHONE_67`,
  `ipad-13→APP_IPAD_PRO_3GEN_129` ; step app-store.yml ; header de
  capture-appstore-screenshots.sh mis à jour (le « NOT part of this script » devient faux).
- **C — drift visible** : job maintenance.yml + release-checklist §17 (advisory).
- **D — Data safety as code** : `data-safety.csv` généré depuis DATA_SAFETY.md +
  push `applications.dataSafety` dans `--apply`. ⚠️ endpoint write-only (pas de GET) →
  premier push réel = gated Thomas avec vérif console après coup ; d'ici là le CSV
  committé + le code restent dormants derrière un flag explicite.

## Non-goals (verdict du design doc complet, reconduits)

Pas de fastlane ; pas de port du `store/` arcamera ; pas d'automatisation agreements /
IARC / privacy labels / Resolution Center (détection + deep-link seulement) ; pas
d'auto-reply reviews (#1692) ; aucun nouveau gate bloquant day-one ; zéro nouveau
secret/scope (réutilise `PLAY_STORE_SERVICE_ACCOUNT_JSON` + `APP_STORE_CONNECT_*`).
