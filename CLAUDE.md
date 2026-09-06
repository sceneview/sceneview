# SceneView

An AI-first 3D/AR SDK for Compose, SwiftUI and the web. The bar for every API, doc and
sample: can an AI read this and emit working code on the first try? If not, simplify the
API or fix the doc until it can.

## Modules

| Module | Purpose |
|---|---|
| `sceneview-core/` | KMP — collision, math, geometry, animation, physics |
| `sceneview/` | Android 3D — `Scene`, `SceneScope`, node types (Filament) |
| `arsceneview/` | Android AR — `ARScene`, ARCore |
| `sceneview-compose/` | Compose Multiplatform façade — viewer subset, no AR |
| `sceneview-web/` | Kotlin/JS + Filament.js (WebGL2/WASM) |
| `SceneViewSwift/` | Apple 3D+AR — RealityKit (iOS/macOS/visionOS) |
| `flutter/` · `react-native/` | Native bridges (Android + iOS) |
| `samples/` | One demo app per platform — `android-demo` is the Play Store app |
| `mcp/` | `sceneview-mcp` server + packages |
| `agents/` | Installable SceneView skills — a product surface, shipped to users |

Full API reference: [`llms.txt`](./llms.txt).

## Things that break if you don't know them

- **Filament JNI calls run on the main thread.** Never call `modelLoader.createModel*`
  or `materialLoader.*` from a background coroutine. `rememberModelInstance` handles it
  — use it in composables.
- **`.filamat` blobs are compiled artifacts, not sources.** If `gradle/libs.versions.toml`
  bumps any of its three independent pins (`filament`, `filamentWebsite`, `filamentWeb`),
  recompile that pin's blobs with the matching `matc` in the same PR — v4.1.0 shipped
  mismatched halves and crashed 10 demos at runtime. Two manual checks:
  `bash tools/GenerateFilamat.sh --check` (blob drift) and
  `bash .claude/scripts/check-web-filamat-abi.sh` (web runtime ABI, #2783).
- **`local.properties` holds a live API key.** Never print it, never commit it.
- **Merging to `main` deploys.** Play Store rollout is automatic. The App Store
  submission is automated but Apple's review is not — "submitted" ≠ "live". Maven
  Central / npm / SPM are irreversible once published.
- **Never QA on a personal device.** `bash .claude/scripts/setup-ar-emulator.sh` boots
  the reusable emulator; `.claude/scripts/device-qa.sh` drives the harness. There is
  exactly **one AVD**, `Pixel_7a` on `emulator-5554` — every other AVD (the tablet
  rigs, the x86_64-under-Rosetta probe of #2758) was deleted. If it is missing, that
  same script recreates it; `--seed-snapshot` re-seeds the warm `qa-clean` snapshot.
- **Major version `4` is frozen.** `5.0.0` is a deliberate milestone, never automatic.
  Never sync `mcp/` or the Flutter/RN *consumed* dependency to `VERSION_NAME` — both
  are independent tracks.

## Conventions

- **UI**: tokens live in [`DESIGN.md`](./DESIGN.md) — read it before writing any UI,
  never hardcode colors or spacing, always light + dark. Demo-app UI is designed
  natively against real references (Sketchfab, Polycam, Reality Composer), never
  tool-generated.
- **Changelog**: one fragment per PR, `changelog.d/<issue-or-pr>-<slug>.md` with a
  `<!-- category: Fixed -->` tag. `CHANGELOG.md` is generated — never hand-edit it, same
  for `gpt/knowledge-*.md` and every `CREDITS.md` (licence compliance).
- **Public surfaces are English only** — commit messages and PR bodies included.
- **Codex is a delegation lever, not a rule** — it bills a quota separate from Claude's.
  Always `.claude/scripts/codex-delegate.sh` (`ask` · `review` · `implement
  --new-worktree`), never a raw `codex`, never an OpenAI API key. Commits, merges and
  releases stay with the lead session. Route to Codex what it does as well for less: an
  independent review of a merged PR, a whole-module read to draft an inventory, unit
  tests and mechanical refactors from a closed brief. Keep with Claude: product
  decisions, UI validated by capture, anything touching secrets, billing or irreversible
  steps — and the reading of whatever Codex returns. The script pins the model
  (`gpt-5.6-sol`) so a CLI update cannot change it silently; `--model gpt-6-astra` is
  opt-in, per call.
- **CI**: `.github/workflows/ci.yml` is the one PR workflow; its `changes` job gates
  every other job and `changes-verdict` ("Path filter completed") is the required check.
  Heavy suites and their path gates: [CONTRIBUTING.md](CONTRIBUTING.md).
