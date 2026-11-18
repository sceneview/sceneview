# SceneView

An AI-first 3D/AR SDK for Compose, SwiftUI and the web. The bar for every API, doc and
sample: can an AI read this and emit working code on the first try? If not, simplify the
API or fix the doc until it can.

## Modules

| Module | Purpose |
|---|---|
| `sceneview-core/` | KMP — collision, math, geometry, animation, physics |
| `sceneview/` | Android 3D — `SceneView`, `SceneScope`, node types (Filament) |
| `arsceneview/` | Android AR — `ARSceneView`, ARCore |
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
- **Devices.** AVD `Pixel_7a` on `emulator-5554`: `bash .claude/scripts/setup-ar-emulator.sh`
  boots or recreates it (`--seed-snapshot` re-seeds `qa-clean`); `.claude/scripts/device-qa.sh`
  drives the harness. Real AR runs on the Pixel 4a (`09291JEC223501`, lock `/tmp/pixel4a.lock`).
- **Demo and website 3D assets are not in git.** `assets/manifest.json` lists them;
  `tools/fetch-assets.sh` fetches them from the `assets-v1` release (Gradle and Xcode run
  it on every build). A new model needs `--register` and its upload to `assets-v1` before
  merging, or every build fails on it. Sparse checkouts must include `assets` and `tools`.
- **Major version `4` is frozen.** `5.0.0` is a deliberate milestone, never automatic.
  Never sync `mcp/` or the Flutter/RN *consumed* dependency to `VERSION_NAME` — both
  are independent tracks.

## Conventions

- **UI**: [`DESIGN.md`](./DESIGN.md) describes the theme and its tokens.
- **Changelog**: one fragment per PR, `changelog.d/<issue-or-pr>-<slug>.md` with a
  `<!-- category: Fixed -->` tag. `CHANGELOG.md` is generated — never hand-edit it, same
  for `gpt/knowledge-*.md` and every `CREDITS.md` (licence compliance).
- **Public surfaces are English only** — commit messages and PR bodies included.
- **Codex**: always `.claude/scripts/codex-delegate.sh`, never a raw `codex` or an OpenAI
  key. Codex can carry a task end to end on its own branch; it holds no push credential. Details: [`.claude/scripts/CODEX.md`](.claude/scripts/CODEX.md).
- **CI**: `.github/workflows/ci.yml` is the one PR workflow; its `changes` job gates
  every other job and `changes-verdict` ("Path filter completed") is the required check.
  Heavy suites and their path gates: [CONTRIBUTING.md](CONTRIBUTING.md).
