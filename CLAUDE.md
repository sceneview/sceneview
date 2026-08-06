# SceneView — Claude Code guide

## Project purpose

SceneView is an **AI-first SDK**: its primary goal is to enable Claude (and other AI
assistants) to help developers build 3D and AR apps in Jetpack Compose. Every design
decision — API surface, documentation, samples, `llms.txt` — should be optimized so
that when a developer asks an AI "build me an AR app", the AI can produce correct,
complete, working code on the first try.

**Implication for contributors:** when adding or changing APIs, always ask "can an AI
read the docs and generate correct code for this?" If not, simplify the API or improve
the documentation until it can.

> **Start here.** Read [`.claude/STATE.md`](.claude/STATE.md) for *where we are* and
> [`.claude/workflows/README.md`](.claude/workflows/README.md) for *how we work* (the v2
> working methodology). This file holds stable project facts only — never session state.

## QUALITY RULES (MANDATORY — every session, every commit)

**ZERO TOLERANCE for bugs reaching the user.** Every change must be verified before push.

### Before EVERY push to main:
1. **Compile check**: `./gradlew :sceneview:compileReleaseKotlin :arsceneview:compileReleaseKotlin`
2. **Unit tests**: `./gradlew :sceneview:testDebugUnitTest :arsceneview:testDebugUnitTest`
3. **Bundle build** (if store-affecting): `./gradlew :samples:android-demo:bundleRelease`
4. **Website JS** (if website changed): `node -c website-static/js/sceneview.js`
5. **Impact check** (after ANY code, API or doc change): `bash .claude/scripts/impact-check.sh`
6. **Full gate**: `bash .claude/scripts/pre-push-check.sh`

> ⚠️ **This list is a floor, not the full set** — `.claude/scripts/` holds ~40 more
> checks, indexed in the `automation-map` skill. Read it as "never push with less
> than this", never as "these are all the gates". Item 5 is here because it was
> not: a session ran it from memory alone and it surfaced 10 pre-existing failures
> ([#2987](https://github.com/sceneview/sceneview/issues/2987)) plus a gate that
> silently skipped itself inside every worktree
> ([#2988](https://github.com/sceneview/sceneview/issues/2988)). A short list reads
> as complete precisely *because* it is short — when this file was 1126 lines
> nobody believed they held the whole picture.

### Rules:
- NEVER push code that doesn't compile
- NEVER push without running tests
- NEVER modify website JS without validating syntax
- NEVER deploy to stores without verifying the bundle builds locally first
- When an agent modifies code, ALWAYS verify compilation before committing
- If a review finds blockers, fix them ALL before pushing — no exceptions
- If you bump `gradle/libs.versions.toml` → `filament = "X.Y.Z"`, you MUST recompile every `.filamat` blob in the same PR with the matching `matc` toolchain — see [CONTRIBUTING.md "Filament runtime ↔ .filamat ABI invariant"](CONTRIBUTING.md#filament-runtime---filamat-abi-invariant). v4.1.0 shipped split halves and crashed 10 demos at runtime.

### Quality plan: `.claude/plans/v4.0-quality-plan.md`

## Where the rest of this guide lives

This file is loaded into **every** session, so it carries only what every session
needs. The detail lives in `.claude/skills/` and loads **on demand** — each skill's
one-line description is what a session sees until it actually needs the body.

| Skill | Load it when |
|---|---|
| `device-qa` | Running the SCRIPTED QA harness (device-qa.sh, Maestro) or the release gate |
| `android-tooling` | Driving a device BY HAND — screenshots, UI dumps, APK install, lease refusals |
| `ci-agents` | Touching the review fan-out, the `@claude` bot, event-driven jobs, agent cost |
| `versioning` | Bumping the version, releasing, republishing the MCP |
| `doc-drift` | A public API changed, or a drift/CREDITS/knowledge gate failed |
| `automation-map` | Looking for the script that already does X, or what a gate really enforces |
| `cross-platform` | Adding an API to one platform, or assessing parity |
| `self-hosted-runner` | A macOS CI job is slow/expensive, or the runner looks offline |
| `long-running-sessions` | The session is getting long, or you are about to hand off |

**Do not paste a skill's content back into this file.** It was moved out on purpose:
this file was 1126 lines and every one of them was re-sent on every turn of every
session. Anything that only *some* sessions need belongs in a skill.

## Hard rules that must survive a lazy load

These are the rules whose *detail* moved into a skill but whose *cost of being
forgotten* is too high to make conditional. Each one points at the skill that
explains it.

- **Never QA on a personal device.** Routine demo QA runs on the reusable
  ARCore-ready emulator — `bash .claude/scripts/setup-ar-emulator.sh`. → `device-qa`
- **Never call `adb` directly** for screenshots, UI dumps or install+launch — use
  `.claude/scripts/lib/android-cli.sh`. `adb` stays correct for `input tap`,
  `am force-stop`, `pm grant`, `logcat`. → `android-tooling`
- **Never drive an emulator carrying another session's lease**, and never set
  `EMU_LEASE_TAKEOVER=1` to get past one. A blocking `PreToolUse` hook refuses
  mutating `adb`/`android` commands on a leased device. → `android-tooling`
- **Never hand-edit a generated file.** `gpt/knowledge-*.md` and
  `assets/CREDITS.md` are regenerated and gated in CI; the CREDITS gate is
  licence compliance, not tidiness. → `doc-drift`
- **A full device-QA pass runs at every release checkpoint, before tagging**, and
  no release ships with a red *blocking* leg. → `device-qa`
- **Never sync `mcp/` or the Flutter/RN *consumed* dependency to `VERSION_NAME`** —
  both are independent tracks and forcing them has caused regressions. → `versioning`
- **A public API change must reach every platform and the docs**, or say
  explicitly why it does not. → `cross-platform`, `doc-drift`

## About

SceneView provides 3D and AR as declarative UI for Android (Jetpack Compose, Filament,
ARCore) and Apple platforms (SwiftUI, RealityKit, ARKit) — iOS, macOS, and visionOS —
with shared logic in Kotlin Multiplatform.

## Full API reference

See [`llms.txt`](./llms.txt) at the repo root for the complete, machine-readable API reference:
composable signatures, node types, resource loading, threading rules, and common patterns.

## Design System

See [`DESIGN.md`](./DESIGN.md) for the complete design system: colors, typography, spacing,
radius, shadows, motion, breakpoints, and component patterns.

**Rules:**
- Always read `DESIGN.md` before generating any UI code (website, app, docs)
- Use CSS custom properties — never hardcode color/spacing/radius values
- Support both light and dark modes
- Follow Material 3 Expressive patterns

**The demo-app UI is reference-driven, not tool-generated.** Do NOT use Stitch, v0, or
Figma to author the demo app's Compose/SwiftUI chrome — that path shipped a poor UI in
v4.1.0 (generic cards, flat hierarchy) because a web-oriented design tool does not know
it is framing a 3D Filament viewport. Instead, design natively against `DESIGN.md` tokens,
anchored on real reference apps (Sketchfab mobile, Polycam, Reality Composer, Apple Quick
Look, Google Scene Viewer), then verify visually on device/emulator before every push.
The "Spatial Studio"-style redesign that this method produced is the bar to clear.

Design tools stay fine for **marketing** surfaces (store screenshots, website hero shots),
where pixel precision has real ROI — never for the app chrome itself.

## When writing any SceneView code

- Use `SceneView { }` for 3D-only scenes (`io.github.sceneview:sceneview:4.26.0`)
- Use `ARSceneView { }` for augmented reality (`io.github.sceneview:arsceneview:4.26.0`)
- Declare nodes as composables inside the trailing content block — not imperatively
- Load models with `rememberModelInstance(modelLoader, "models/file.glb")` — returns `null`
  while loading, always handle the null case
- `LightNode`'s `apply` is a **named parameter** (`apply = { intensity(…) }`), not a trailing lambda
- For AR record-replay debugging, use `rememberARRecorder()` to capture sessions and
  `ARSceneView(playbackDataset = file)` to replay them — see `llms.txt` "AR Recording & Playback"

## Critical threading rule

Filament JNI calls must run on the **main thread**. Never call `modelLoader.createModel*`
or `materialLoader.*` from a background coroutine directly.
`rememberModelInstance` handles this correctly — use it in composables.
For imperative code, use `modelLoader.loadModelInstanceAsync`.

## Samples

One unified showcase app per platform — all features integrated into tabs.

| Directory | Platform | Demonstrates |
|---|---|---|
| `samples/android-demo` | Android | Play Store app — 4-tab Material 3 (Explore, AR View, Samples, About), 53 demos (19 non-AR + 34 AR) |
| `samples/android-tv-demo` | Android TV | D-pad controls, model cycling, auto-rotation |
| `samples/web-demo` | Web | Browser 3D viewer, Filament.js (WASM), WebXR AR/VR |
| `samples/ios-demo` | iOS | App Store app — 4-tab SwiftUI (Explore multi-source, AR, Samples, About) |
| `samples/desktop-demo` | Desktop | Wireframe placeholder (NOT SceneView) — Compose Canvas, no Filament |
| `samples/flutter-demo` | Flutter | PlatformView bridge demo (Android + iOS) |
| `samples/react-native-demo` | React Native | Fabric bridge demo (Android + iOS) |
| `samples/common` | Shared | Helpers and utilities for all Android samples |
| `samples/recipes` | Docs | Markdown code recipes (model-viewer, AR, physics, geometry, text) |

## Module structure

| Module | Purpose |
|---|---|
| `sceneview-core/` | KMP module — portable collision, math, geometry, animation, physics (commonMain/androidMain/iosMain/jsMain) |
| `sceneview/` | Android 3D library — `Scene`, `SceneScope`, all node types (Filament renderer) |
| `sceneview-compose/` | Compose Multiplatform façade — one `SceneViewer` composable from `commonMain`, delegating to each platform's own renderer. **Viewer subset only**, no AR (see `docs/docs/compose-multiplatform.md`) |
| `arsceneview/` | Android AR layer — `ARScene`, `ARSceneScope`, ARCore integration |
| `sceneview-web/` | Web 3D library — Kotlin/JS + Filament.js (same engine as Android, WebGL2/WASM) |
| `SceneViewSwift/` | Apple 3D+AR library — `SceneView`, `ARSceneView` (RealityKit renderer, iOS/macOS/visionOS) |
| `samples/` | All demo apps — one per platform (`android-demo`, `ios-demo`, `web-demo`, etc.) |
| `mcp/` | `sceneview-mcp` — MCP server + `packages/` (automotive, gaming, healthcare, interior) + `docs/` |
| `flutter/` | Flutter plugin — PlatformView bridge to SceneView (Android + iOS), with native rendering |
| `react-native/` | React Native module — Fabric/Turbo bridge to SceneView (Android + iOS), with native rendering |
| `assets/` | Shared 3D models (GLB + USDZ) and environments for demos and website |
| `tools/` | Build utilities — Filament material generation, asset download, try-demo script |
| `website-static/` | Static HTML/CSS/JS website (sceneview.github.io) |
| `docs/` | MkDocs documentation source (built by CI) |
| `branding/` | Logo SVGs, brand guide, store asset specs |
| `buildSrc/` | Gradle build logic + detekt config |
| `third_party/` | Vendored third-party source. **Empty today** — the Filament KMP desktop binding was vendored here and removed again before it ever compiled; re-vendoring is a one-command restore from `c01ae5d87`, see [docs/docs/desktop-filament.md](docs/docs/desktop-filament.md#re-vendoring-the-binding). Any future vendored tree must ship LICENSE + NOTICE + a §4(b) guard wired into a CI job — a guard no job invokes is prose |
| `.github/` | CI workflows + community docs (CoC, Security, Support, Governance, Sponsors, Privacy) |

## Changelog entries

**Changelog entries go in `changelog.d/`, not `CHANGELOG.md`.** Each PR adds one
fragment file `changelog.d/<issue-or-pr>-<slug>.md` with its release-note
bullet(s) and a `<!-- category: Fixed -->` tag (Added/Changed/Fixed/Removed/
Tests/Docs). Distinct filenames mean parallel PRs never conflict on the
changelog. At release time `bash .claude/scripts/collate-changelog.sh X.Y.Z`
collates every fragment into a new `## vX.Y.Z` section and deletes them. Never
hand-edit the `## Unreleased` anchor — it is kept empty for backward-compat.
See [`changelog.d/README.md`](changelog.d/README.md).

## Session continuity

> **Where are we right now? → [`.claude/STATE.md`](.claude/STATE.md).**
> That gitignored file is the single live source of truth: `NOW` (released version ·
> what just shipped · what's broken), the `IN-FLIGHT` claim ledger, `NEXT` (<=6 issue
> links), and the `BOOTSTRAP` commands a fresh session runs (<2 min). **CLAUDE.md carries
> zero session state** — never add a "Current state" block here again. Done items move to
> `.claude/handoff.md`; the backlog lives in GitHub issues.

> **How do we work? → [`.claude/workflows/README.md`](.claude/workflows/README.md).**
> The canonical methodology (v2): principles, the unified lifecycle, the 5 tooling layers,
> the parallelism model, autonomy boundaries, quality gates, the saved-workflow index, and
> the claim protocol (`.claude/scripts/claim.sh`) that kills the #2300 dup-implementation race.

### Latest release: see `gradle.properties`

**The source-of-truth version is always `VERSION_NAME` in the root `gradle.properties`** —
read that file, never hardcode a version. Treat it as the latest published version across
all surfaces (Maven Central, npm `sceneview-web`, SPM tag `vX.Y.Z`, web CDN);
`gradle.properties` is authoritative if anything disagrees. `/store-status` verifies the
REAL live versions (CI-green != live).

### Older session logs

Chronological history (the "why did we do X") lives in `.claude/handoff.md` (gitignored,
append-only, rotated to `.claude/handoff-archive/YYYY-QN.md` at 400 lines). `git log` / PR
descriptions are the permanent record; durable cross-session *rules* live in agent memory
(`MEMORY.md` index). Run `/handoff` at session end to reconcile STATE.md -> handoff, and
`/sync-check` before a PR / at session end (never claim "everything is good" without it).

---

