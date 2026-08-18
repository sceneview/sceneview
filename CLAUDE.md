# SceneView — Claude Code guide

**SceneView is an AI-first SDK.** Its purpose is to let an AI generate correct 3D/AR
Compose code on the first try. Every API, doc and sample is judged by: *can an AI read
this and emit working code?* If not, simplify the API or fix the doc until it can.

Where we are right now → `.claude/STATE.md`, in the **main checkout only** (it is
gitignored, so a worktree does not have it — that is absence, not "no state"). This file
holds stable facts only — **never session state**.

## What "done" means

**Merged, and released when user-facing.** Not "PR opened", not "analysed".

`implemented → gates green (output quoted) → changelog.d/ fragment → PR → blockers fixed
→ merged → released if the version plan calls for it → STATE.md updated`

**Nothing in that chain requires permission** — merge, tag, publish to Maven/npm/Play/
App Store are pre-authorised (mandate 2026-08-11). See "Release" below for the two
platform nuances. The only stops: a secret to handle, a product decision that changes
the deliverable, a verified external block.

## Before every push

```
bash .claude/scripts/pre-push-check.sh
```

That is the gate. It runs compile, tests, impact-check and the deterministic CI legs.
**It is a floor, not the full set** — the `CI-PARITY LEGS` comment block inside the
script names every CI check it cannot cover (network, Gradle, device, Checks-API) and
why. A CI check named in neither is an unaudited gap, not a decision. `.claude/scripts/`
holds the rest, indexed by the `automation-map` skill.

Rules: never push code that does not compile · never push without tests · a review
blocker is fixed before merge, all of them · if `gradle/libs.versions.toml` bumps any
of its three independent pins (`filament`, `filamentWebsite`, `filamentWeb`),
recompile that pin's `.filamat` blobs in the same PR with the matching `matc`
([CONTRIBUTING.md](CONTRIBUTING.md#filament-runtime---filamat-abi-invariant) — v4.1.0
shipped split halves and crashed 10 demos at runtime).

## Release

Fast-release to production is the default path, `/release` drives it end to end.
Two platform nuances, both handled by the pipeline, neither a reason to stop and ask:

- **Google Play** — automated rollout on merge. Fully autonomous.
- **Apple App Store** — submission is automated; the store review is Apple's, so
  "submitted" ≠ "live". Never report live state from an upload; `/store-status` probes
  the real one.
- **Maven Central / npm / SPM** — irreversible once published. That is a reason to run
  `/publish-preflight`, not a reason to ask.

Major version `4` is frozen — `5.0.0` is a deliberate milestone, never automatic.
→ `versioning` skill.

## Hard rules that must survive a lazy load

The detail of each moved into a skill; the rule itself stays here, or a session that
never opens that skill never learns it. Gated by `test-context-budget.sh`.

- **Never QA on a personal device.** Use the reusable emulator:
  `bash .claude/scripts/setup-ar-emulator.sh` → `device-qa`
- **Never call `adb` directly** for screenshots, UI dumps or install+launch — use
  `.claude/scripts/lib/android-cli.sh`. Raw `adb` stays correct for `input tap`,
  `am force-stop`, `pm grant`, `logcat`. → `android-tooling`
- **Never drive an emulator holding another session's lease**, and never set
  `EMU_LEASE_TAKEOVER=1`. A blocking hook refuses it. → `android-tooling`
- **Never hand-edit a generated file** — `gpt/knowledge-*.md` and every `CREDITS.md`
  are regenerated and gated (CREDITS is licence compliance). → `doc-drift`
- **A public API change reaches every platform and the docs**, or says why not.
  → `cross-platform`
- **A full device-QA pass runs at every release checkpoint, before tagging**; no
  release ships with a red *blocking* leg. → `device-qa`
- **Never sync `mcp/` or the Flutter/RN *consumed* dependency to `VERSION_NAME`** —
  both are independent tracks, and forcing them has caused regressions. → `versioning`
- **Codex is a delegated developer, and it bills the ChatGPT plan only** — every
  call goes through `.claude/scripts/codex-delegate.sh`, never a raw `codex`.
  Never `--with-api-key`, never an OpenAI API key. → `codex-delegation`
- **CI-green is never proof of live.** Upload ≠ submitted ≠ approved ≠ live.
- **Public surfaces are English only** — commits and PR bodies included.

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
| `docs/` · `website-static/` · `branding/` | Docs site, static site, brand assets |
| `.claude/scripts/` | Checks and harnesses — the automation surface |

**Critical threading rule:** Filament JNI calls run on the **main thread**. Never call
`modelLoader.createModel*` or `materialLoader.*` from a background coroutine.
`rememberModelInstance` handles it — use it in composables.

Full API reference: [`llms.txt`](./llms.txt). Design tokens: [`DESIGN.md`](./DESIGN.md)
(read it before generating any UI; never hardcode colors/spacing; light + dark).
**The demo-app UI is reference-driven, never tool-generated** — design natively against
`DESIGN.md` anchored on real apps (Sketchfab, Polycam, Reality Composer). Design tools
stay fine for marketing surfaces.

## Changelog

One fragment per PR: `changelog.d/<issue-or-pr>-<slug>.md` with a
`<!-- category: Fixed -->` tag. Never hand-edit `CHANGELOG.md`. HTML comments are
stripped; a fragment declaring a breaking change forces a MINOR release.
→ [`changelog.d/README.md`](changelog.d/README.md)

## Skills — loaded on demand, never pasted back here

| Skill | Load it when |
|---|---|
| `device-qa` | Running the QA harness or the release gate |
| `android-tooling` | Driving a device by hand |
| `versioning` | Bumping, releasing, republishing the MCP |
| `doc-drift` | A public API changed, or a drift/CREDITS gate failed |
| `automation-map` | Looking for the script that already does X |
| `cross-platform` | Adding an API to one platform, assessing parity |
| `ci-agents` | Touching the review fan-out or the `@claude` bot |
| `self-hosted-runner` | A macOS CI job is slow or the runner looks offline |
| `codex-delegation` | Handing a bounded task to Codex, or a Codex call failed |

This file is re-sent on **every turn of every session**. Anything only *some* sessions
need belongs in a skill. It was 263 lines; it is not allowed to grow back.
