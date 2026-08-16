---
name: doc-drift
description: The two-tier docs-versus-API drift policy — advisory per-PR heuristic plus the weekly agent-driven DRAFT PR — and the two deterministic BLOCKING generated-file gates (gpt/knowledge-*.md from llms.txt, and every shipped CREDITS.md from catalog.json plus the bundled assets, a licence-compliance gate). Use when a public API changes, when check-doc-drift or a CREDITS/knowledge drift check fails, before adding or removing a bundled demo asset, or before hand-editing anything generated.
---

## Documentation drift (docs ↔ API) — two-tier policy

SceneView is AI-first: the prose docs (`llms.txt`, KDoc, `docs/docs/*`,
`samples/recipes/*`) are the surface an AI reads to generate user code, so
stale docs make an AI emit stale code. Keeping them in sync is enforced at
**two complementary tiers** — neither alone is enough:

1. **Per-PR — advisory, deterministic, cheap.** `check-doc-drift.sh` runs in
   `ci.yml` → `repo-hygiene` and WARNs (in the job step summary) when a PR
   changed a public-API source file *and* added/removed/retyped a public
   declaration without touching the relevant doc surface. **Non-blocking by
   design**: it is a heuristic, and blocking a heuristic guarantees false
   positives that erode trust (consistent with the repo's advisory-first
   stance on flaky device-QA legs). It reminds the author; it never freezes
   the PR. `/document` covers the KDoc half on demand.
2. **Weekly — deep, agent-driven, safe.** [`doc-audit.yml`](/.github/workflows/doc-audit.yml)
   fires every Monday 07:17 UTC (and `workflow_dispatch`). It seeds an Opus
   agent with `check-doc-drift.sh --audit` (a repo-wide candidate-drift
   worklist) plus the week's `git log`, the agent reasons over the four
   surfaces against the *current* API, and opens a **DRAFT** PR with concrete
   doc patches (or a single de-duplicated tracking issue when a patch is not
   safe). Draft + human review means a wrong prose patch can never land
   silently — this is where the "auto-fix" power lives, not on every PR.

Alongside the two heuristic tiers, two surfaces get a **deterministic,
blocking gate**, both in `ci.yml` → `repo-hygiene`. Generated files can be
gated hard because there is no false-positive risk — never hand-edit them:

- `gpt/knowledge-*.md` is GENERATED from `llms.txt` by
  `tools/generate-gpt-knowledge.js`; CI fails when the committed files drift
  (`--check`). `sync-versions.sh --fix` regenerates them on every version
  bump (#2724).
- **Every** `CREDITS.md` the project ships is GENERATED (or byte-mirrored) by
  `.claude/scripts/generate-credits.py`; CI fails when any of them drifts
  (`--check`). This one is a **licence-compliance** gate, not a docs gate:
  CREDITS.md is what discharges the attribution clause (CC-BY 4.0 §3a) of
  every asset that ships in the sample apps, so a catalog entry that never
  reaches CREDITS.md is an uncredited model in a published release. It went
  stale undetected twice — five catalog records (three distinct Khronos works:
  Toy Car, Sheen Chair, Iridescence Dish With Olives) shipped uncredited before
  a manual re-run caught it, and the copy inside the Play Store APK ran two
  months and six bundled files behind because the gate covered one file while
  the repository tracked five (#2941). The five, and their treatment:
  | File | Treatment |
  |---|---|
  | `assets/CREDITS.md` | GENERATED — the whole catalogue |
  | `samples/android-demo/src/main/assets/CREDITS.md` | GENERATED — only what the APK bundles |
  | `assets/audio/CREDITS.md` | SOURCE — hand-written; `bell.wav` is ffmpeg-generated, not a catalogue asset |
  | `samples/ios-demo/SceneViewDemo/Audio/CREDITS.md` | MIRROR — byte-identical to the source above |
  | `samples/web-demo/site/audio/CREDITS.md` | MIRROR — byte-identical to the source above |

  A bundled asset matching neither a catalogue entry nor an explicit
  `NON_CATALOG_BUNDLED` declaration fails the gate rather than shipping
  uncredited. Adding a sixth `CREDITS.md` without naming it in the generator
  fails `test-generate-credits.sh`.

Why not block per-PR or auto-fix per-PR? Blocking frustrates internal-only
refactors that get mis-classified; per-PR auto-fix is costly on every PR and a
green "bot fixed docs" check invites rubber-stamping a subtly-wrong prose
patch. The weekly draft-PR concentrates the cost and keeps a human in the loop.

