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
- **`app-store-screenshots.yml`** (Phase B — LIVRÉ, révisé DEUX fois) : **workflow
  DÉDIÉ** (`asc_listing.py --apply-screenshots`), **ubuntu-latest, `workflow_dispatch`-only**
  — et non un step du job macOS gated minor-bump comme prévu initialement, ni même
  un job dans `app-store.yml` comme dans la 1re implémentation. ⛔ **Pourquoi le job
  dans `app-store.yml` était FAUX (attrapé par la review de PR #2781, 2 reviewers
  indépendants)** : `deploy-ios`/`deploy-macos` n'y sont gatés que sur
  `needs.check.outputs.*_ready`, sans garde d'événement ni d'input → un dispatch
  « screenshots seulement » lançait AUSSI 2 archives macos-15 et **uploadait un
  build TestFlight** (effet de bord irréversible). Une garde `&& inputs.sync_screenshots
  != 'true'` réparait l'instance ; un fichier séparé répare la CLASSE (aucun build à
  déclencher, tout futur job exempt par construction). ⚠️ **Le fichier séparé n'achète
  PAS l'indépendance de concurrency** : le workflow **REJOINT délibérément** le groupe
  `app-store-deploy` (tour 5 de review), parce que les deux workflows écrivent la MÊME
  version éditable — un sync qui supprime des screenshots pendant qu'un deploy verrouille
  la version la laisse partir en review avec un set tronqué. Une version antérieure de
  ce plan disait l'inverse (« sort du concurrency group ») : c'était le design abandonné,
  écrit ici comme s'il était le livré. Les 3 raisons initiales du fichier séparé,
  découvertes en lisant le flux réel :
  (a) les screenshots **persistent d'une version ASC à l'autre** (une nouvelle
  version hérite du set) → c'est de la maintenance de listing, pas une étape
  par-release ; (b) le seul point d'insertion correct dans le flux tag serait
  *entre* 4b et la création de la reviewSubmission, donc **dans** la zone gelée —
  l'alternative (uploader après) écrirait sur une version verrouillée ; (c) c'est
  du REST pur : aucun besoin de Xcode ni de rebuild de 40 min sur un runner à ~10x.
  Le script **ne crée jamais de version** : il cible l'éditable, SKIP honnête sinon.
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
- **B — upload screenshots iOS** ✅ : reserve → PUT chunks → commit `uploaded:true`
  + `sourceFileChecksum` (MD5), skip-if-identical, sinon delete-then-upload (#1710)
  pour que l'ordre live = l'ordre des noms de fichiers ; mapping `iphone-6.9→APP_IPHONE_67`,
  `ipad-13→APP_IPAD_PRO_3GEN_129` ; job `sync-screenshots` ; header de
  capture-appstore-screenshots.sh corrigé (le « NOT part of this script » + la
  mention fastlane étaient devenus faux). Protocole verrouillé sur fastlane/spaceship
  (`{uploaded: true, sourceFileChecksum: MD5.hexdigest(bytes)}`) — le champ `isUploaded`
  de certains write-ups est un artefact de wrapper Swift ; le fallback spéculatif sur
  400 a été RETIRÉ à la review (2e PATCH condamné qui écrasait le vrai corps d'erreur).
  Durcissement attrapé en chemin : `allow_abbrev=False` sur les DEUX scripts — argparse
  résolvait `--apply`/`--appl` en `--apply-screenshots`, donc une faute de frappe
  publiait vers un store. Durcissements issus de la review #2781 : DELETE échoué =
  fatal AVANT tout upload (sinon reliquats en tête de set + cap Apple de 10 → 409
  à mi-parcours), échec d'upload → message nommant le display type laissé partiel,
  `bundle_id` URL-encodé sur le chemin write, `$RUNNER_TEMP` (pas `/tmp` : le runner
  self-hosted mac ne le nettoie pas → venv périmé, versions épinglées contournées),
  SKIP annoté `::warning::` sur le chemin write (un dispatch humain qui n'uploade rien
  ne doit pas ressortir en vert muet), `--fail-on-drift` refusé en mode apply (un flag
  silencieusement ignoré = même classe qu'une abréviation qui écrit).
  ⛔ **Bug le plus grave, attrapé au 2e tour de review** : `_await_delivery` renvoyait
  une string surchargée (`"FAILED errors=[…]"`) alors que l'appelant testait
  `state == "FAILED"` → **un screenshot REJETÉ par Apple (mauvaises dimensions) était
  compté comme uploadé et le job sortait en vert, l'ancien set ayant déjà été
  supprimé** — exactement le « faux vert » que le fichier lui-même interdit. Fix :
  `(ok, detail)` structuré + 7 tests unitaires sur stub dont la forme exacte du rejet.
  ⚠️ **2e hypothèse non validée, traitée comme la 1re** : « ordre du set = ordre de
  création » n'est promis nulle part → sonde après upload (et déclarée *inconclusive*
  si les checksums n'ont pas tous été confirmés) + doc qui ne l'affirme plus.

### Journal de review de la Phase B (4 tours) — ce que ça dit du process

4 tours de review-fanout : `DO_NOT_MERGE` ×2 (2 ERROR confirmés), puis
`MERGE_AFTER_WARNINGS` ×2. **Aucun des 2 ERROR n'était détectable par la CI** —
elle ne fait tourner que des scripts, jamais l'API Apple. Les deux étaient des
« faux verts » :

1. le job d'upload vivait dans `app-store.yml` → un dispatch « screenshots »
   **uploadait un build TestFlight** ;
2. `_await_delivery` renvoyait `"FAILED errors=[…]"` là où l'appelant testait
   `== "FAILED"` → **un screenshot rejeté comptait comme uploadé**, exit 0,
   ancien set déjà supprimé.

Et 3 warnings distincts portaient sur des **affirmations fausses de ma part**,
publiées : « la Phase B mesure l'hypothèse MD5 » (impossible — Apple fait écho),
« `--apply` pouvait publier vers l'App Store » (le flag n'existait pas sur main),
« l'ordre live suit l'ordre des fichiers » (promis nulle part). Motif constant :
**durcir en prose ce qui n'a pas été mesuré**. La review l'a attrapé 3 fois ; moi
zéro. À garder en tête pour les Phases C/D, où la tentation sera la même.

- **C — drift visible** : job maintenance.yml + release-checklist §17 (advisory).
  ⚠️ Prérequis (warning fanout PR #2764, **reformulé après la review #2781**) :
  l'hypothèse `sourceFileChecksum == MD5(png)` se scinde en deux cas, et il ne
  faut pas les confondre :
  - **assets uploadés par ce script** : vrai PAR CONSTRUCTION (le client calcule
    le MD5 et le DÉCLARE dans le PATCH ; Apple le stocke). ⛔ Corollaire : la
    Phase B ne peut PAS « prouver » la convention — Apple ne fait qu'écho de ce
    qu'on a envoyé. Une version antérieure de ce plan (et de la PR) présentait
    cet écho comme une mesure : c'était faux.
  - **assets uploadés autrement** (console web = le cas réel aujourd'hui, aucun
    screenshot du repo n'ayant jamais été uploadé) : inconnu, possiblement absent.
  → La validation à faire avant de traiter le diff comme signal porte donc sur un
  set **console-uploadé**, pas sur un set produit par notre propre upload.
- **D — Data safety as code** : `data-safety.csv` généré depuis DATA_SAFETY.md +
  push `applications.dataSafety` dans `--apply`. ⚠️ endpoint write-only (pas de GET) →
  premier push réel = gated Thomas avec vérif console après coup ; d'ici là le CSV
  committé + le code restent dormants derrière un flag explicite.

## Non-goals (verdict du design doc complet, reconduits)

Pas de fastlane ; pas de port du `store/` arcamera ; pas d'automatisation agreements /
IARC / privacy labels / Resolution Center (détection + deep-link seulement) ; pas
d'auto-reply reviews (#1692) ; aucun nouveau gate bloquant day-one ; zéro nouveau
secret/scope (réutilise `PLAY_STORE_SERVICE_ACCOUNT_JSON` + `APP_STORE_CONNECT_*`).
