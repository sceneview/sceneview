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
  mismatched halves and crashed 10 demos at runtime. Two checks, both manual:
  `bash tools/GenerateFilamat.sh --check` (blob drift) and
  `bash .claude/scripts/check-web-filamat-abi.sh` (web runtime ABI, #2783).
- **`local.properties` holds a live API key.** Never print it, never commit it.
- **Merging to `main` deploys.** Play Store rollout is automatic. The App Store
  submission is automated but Apple's review is not — "submitted" ≠ "live". Maven
  Central / npm / SPM are irreversible once published.
- **Never QA on a personal device.** `bash .claude/scripts/setup-ar-emulator.sh` boots
  the reusable emulator; `.claude/scripts/device-qa.sh` drives the harness.
- **Major version `4` is frozen.** `5.0.0` is a deliberate milestone, never automatic.
  Never sync `mcp/` or the Flutter/RN *consumed* dependency to `VERSION_NAME` — both
  are independent tracks.

## Conventions

- **UI**: design tokens live in [`DESIGN.md`](./DESIGN.md) — read it before writing any
  UI, never hardcode colors or spacing, always light + dark. Demo-app UI is designed
  natively against real references (Sketchfab, Polycam, Reality Composer), never
  tool-generated.
- **Changelog**: one fragment per PR, `changelog.d/<issue-or-pr>-<slug>.md` with a
  `<!-- category: Fixed -->` tag. `CHANGELOG.md` is generated — never hand-edit it, same
  for `gpt/knowledge-*.md` and every `CREDITS.md` (licence compliance).
- **Public surfaces are English only** — commit messages and PR bodies included.
- **Delegate to Codex by default** — Codex bills the ChatGPT plan, a quota separate
  from Claude's. Always `.claude/scripts/codex-delegate.sh`, never a raw `codex`, never
  an OpenAI API key. Default routing: exploration/audit → `ask` · second opinion on a
  diff → `review` · mechanical implementation → `implement --new-worktree`. Decisions,
  integration, commits, merges and releases stay with Claude.

## CI

`.github/workflows/ci.yml` is the one PR workflow. Its `changes` job detects touched
paths and gates every other job; `changes-verdict` ("Path filter completed") always runs
and is the required check. Heavy suites (`render-tests.yml`, `device-qa.yml`) run
on push to `main` and nightly, never cancelling each other; the one per-PR
exception is `render-tests.yml`'s `android-library-render` job, path-gated on
`sceneview/**`, `sceneview-core/**` and the Gradle files (#3216). Details in [CONTRIBUTING.md](CONTRIBUTING.md).
