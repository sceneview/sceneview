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

## QUALITY RULES (MANDATORY — every session, every commit)

**ZERO TOLERANCE for bugs reaching the user.** Every change must be verified before push.

### Before EVERY push to main:
1. **Compile check**: `./gradlew :sceneview:compileReleaseKotlin :arsceneview:compileReleaseKotlin`
2. **Unit tests**: `./gradlew :sceneview:testDebugUnitTest :arsceneview:testDebugUnitTest`
3. **Bundle build** (if store-affecting): `./gradlew :samples:android-demo:bundleRelease`
4. **Website JS** (if website changed): `node -c website-static/js/sceneview.js`
5. **Full gate**: `bash .claude/scripts/pre-push-check.sh`

### Rules:
- NEVER push code that doesn't compile
- NEVER push without running tests
- NEVER modify website JS without validating syntax
- NEVER deploy to stores without verifying the bundle builds locally first
- When an agent modifies code, ALWAYS verify compilation before committing
- If a review finds blockers, fix them ALL before pushing — no exceptions
- If you bump `gradle/libs.versions.toml` → `filament = "X.Y.Z"`, you MUST recompile every `.filamat` blob in the same PR with the matching `matc` toolchain — see [CONTRIBUTING.md "Filament runtime ↔ .filamat ABI invariant"](CONTRIBUTING.md#filament-runtime---filamat-abi-invariant). v4.1.0 shipped split halves and crashed 10 demos at runtime.

### Quality plan: `.claude/plans/v4.0-quality-plan.md`

## Device QA

The **autonomous device-QA harness** (umbrella [#1560](https://github.com/sceneview/sceneview/issues/1560))
drives the demo apps **like a real user** — taps, swipes, camera-orbit drags,
navigation — and asserts no crash across every platform, unattended (no
screenshot-by-screenshot loop). CI-green plus self-review is not enough: a demo
can compile, pass unit tests, and still crash the moment it renders on a device.

### Run it

```bash
# Full cross-platform pass — every platform feasible on this host.
bash .claude/scripts/device-qa.sh --platform=all

# One platform, or a fast per-category subset.
bash .claude/scripts/device-qa.sh --platform=android
bash .claude/scripts/device-qa.sh --platform=web --fast
```

`device-qa.sh` is the single orchestrator entrypoint. It boots the
emulator/simulator each leg needs, builds + installs the demo app, delegates to
the per-platform harness, and aggregates every verdict into one
`device-qa-report.json` at the repo root (override the directory with `--out`).
Exit status is non-zero if any selected platform failed. Flags: `--platform=android|ios|web|ar|all`,
`--fast` (per-category subset, not the full catalog), `--ci` (treat a skipped
platform as a failure), `--out <dir>`.

### What each leg covers

| Leg | Harness | Drives | Report |
|---|---|---|---|
| `android` | Maestro flows `.maestro/android/` via `qa-android-demos.sh` | All 42 demos on an emulator | `device-qa-report.json` |
| `ios` | Maestro flows `.maestro/ios/` via `ios-device-qa.sh` | 24 deep-linkable demos on a simulator (AR = launch-only smoke) | `device-qa-report.json` |
| `web` | Playwright suite `samples/web-demo/tests/` | Browser 3D viewer + every catalog tab | `web-qa-summary.json` |
| `ar` | `ar-replay-qa.sh` + `ARReplayHarnessTest` | Every Android AR demo replayed against recorded ARCore sessions — no physical device | `ar-qa-summary.json` |

See [`.maestro/README.md`](.maestro/README.md) for the Maestro flow layout and
known limitations (no pinch gesture → 3D zoom is driven via deep-link param).

### Release-checkpoint mandate

**A full device-QA pass runs at every release checkpoint, before tagging.**
No release ships with a red *blocking* leg in `device-qa-report.json`. The
gate is enforced in two places — keep both honest:

- `release-checklist.sh` **section 14** reads the report's graded
  `releaseGate.verdict` and fails the checklist on a `blocked` verdict (or a
  missing report).
- The `/release` skill (**Step 6.5**) runs `device-qa.sh --platform=all`
  before the tag step.

#### Release-gate policy for `continue-on-error` legs (#1651)

The legs are **graded**, because they are not equally reliable:

| Leg | CI behaviour | Release gate |
|---|---|---|
| `web` | NOT `continue-on-error` — a red leg fails the workflow | **BLOCKING** — a red web leg is a release `FAIL` |
| `android`, `ar` | `continue-on-error: true` (flaky SwiftShader emulator, #1643) | **ADVISORY** — a red leg is a `WARN`, never a silent pass, never a hard block |

`device-qa.sh` tags each leg `advisory: true|false` (default advisory set:
`android,ar`, override with `--advisory=<csv>`) and pre-computes
`releaseGate.verdict` in `device-qa-report.json`:

- `clear` — every leg passed → checklist `PASS`.
- `warn` — an advisory leg (android/ar) did not pass → checklist `WARN`
  ("advisory leg(s) did not pass: … — review before tagging"). A human sees
  it, but it does not block the release.
- `blocked` — a blocking leg (web) failed → checklist `FAIL`, hard block.

Advisory legs stay non-blocking until #1643/#1645 make the emulator reliably
green; then promote them to blocking by shrinking the `--advisory=` set. A red
*blocking* leg means a demo crashes for a real user — fix it before tagging,
no exceptions.

## About

SceneView provides 3D and AR as declarative UI for Android (Jetpack Compose, Filament,
ARCore) and Apple platforms (SwiftUI, RealityKit, ARKit) — iOS, macOS, and visionOS —
with shared logic in Kotlin Multiplatform.

## Full API reference

See [`llms.txt`](./llms.txt) at the repo root for the complete, machine-readable API reference:
composable signatures, node types, resource loading, threading rules, and common patterns.

## Design System (Google Stitch)

See [`DESIGN.md`](./DESIGN.md) for the complete design system: colors, typography, spacing,
radius, shadows, motion, breakpoints, and component patterns.

**Rules:**
- Always read `DESIGN.md` before generating any UI code (website, app, docs)
- Use CSS custom properties — never hardcode color/spacing/radius values
- Support both light and dark modes
- Follow Material 3 Expressive patterns

**Google Stitch MCP:** when configured, enables direct UI generation from Stitch projects.
To set up: `npm install @google/stitch-sdk`, then add the Stitch MCP server in Claude Code settings.

## When writing any SceneView code

- Use `SceneView { }` for 3D-only scenes (`io.github.sceneview:sceneview:4.11.2`)
- Use `ARSceneView { }` for augmented reality (`io.github.sceneview:arsceneview:4.11.2`)
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

## Android CLI (preferred for agent-driven QA)

Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli)
(tested against **v0.7.15411012**, first release April 2026) is the agent-focused
front-end for `adb` / `uiautomator` / `emulator` / `sdkmanager`. SceneView's QA scripts
and CI install it on the fly and use it for:

- `android layout --device=<serial> -o ui.json --pretty` — JSON UI tree with
  **precomputed `center` coords** per node (no `uiautomator dump` XML parsing, no
  `bounds` regex). `--diff` returns only nodes changed since the last invocation.
- `android screen capture --output ui.png` — PNG screenshot that bypasses the adb
  shell PTY, so it doesn't suffer the **LF/CRLF translation that the legacy
  `adb shell screencap -p > file` form does** (modern `adb exec-out screencap` also
  bypasses the PTY and is fine).
- `android screen capture --annotate` + `android screen resolve --screenshot=ui.png
  --string="tap #5"` — visual-label tapping (the AI-first workflow this CLI exists for).
- `android run --apks=app.apk --activity=pkg/.Main` — install + launch in one call.

**When to use what:**
- Screenshots, UI dumps, install+launch → `android` CLI (via `.claude/scripts/lib/android-cli.sh`)
- `input tap/swipe/keyevent`, `am force-stop`, `pm grant`, `emu sensor set`, `logcat`,
  `adb pull` → still `adb` — the CLI has no equivalent in v0.7

The helper auto-installs the CLI to `~/.local/bin/android` on first use and falls back to
`adb` if the install fails or on multi-device hosts (the `screen capture` subcommand
has no `--device` flag in v0.7 — but `android layout --device=<serial>` does work).
Telemetry is disabled via `--no-metrics` on every invocation.

**SceneView agent skills.** This repo ships three platform-specific agent
skills under [`agents/`](agents/) — see [`agents/REGISTRY.md`](agents/REGISTRY.md):

- [`agents/sceneview/SKILL.md`](agents/sceneview/SKILL.md) — Android (Jetpack Compose + ARCore)
- [`agents/sceneview-ios/SKILL.md`](agents/sceneview-ios/SKILL.md) — Apple (SwiftUI + RealityKit, iOS/macOS/visionOS)
- [`agents/sceneview-web/SKILL.md`](agents/sceneview-web/SKILL.md) — Web (Filament.js + WebXR)

Install them with:

```bash
bash .claude/scripts/install-sceneview-skill.sh        # Android
bash .claude/scripts/install-sceneview-ios-skill.sh    # iOS / macOS / visionOS
bash .claude/scripts/install-sceneview-web-skill.sh    # Web
```

After install, `android skills list` shows `sceneview`, `sceneview-ios` and
`sceneview-web` under the `xr` category, making the API contract, recipes, and
migration guide available to any AI agent on the host.
`bash .claude/scripts/android-env-check.sh --fix` installs the Android skill plus
the `android` CLI itself. The Android skill's Google `android-cli` registry
submission (issue #1082) is tracked in [`agents/REGISTRY.md`](agents/REGISTRY.md).

**Emulator-first QA (mandatory).** Routine demo QA runs on a reusable ARCore-ready
emulator — **never on a personal device**. Bootstrap it once on a fresh host:

```bash
bash .claude/scripts/setup-ar-emulator.sh
```

This creates/configures a `Pixel_7a` AVD (virtualscene back camera, emulated front
camera for Augmented Faces, 4 GB RAM, host GPU), boots it headless, and installs
Google Play Services for AR. Re-run anytime — it's idempotent. `--check` inspects
state read-only; `--clean` wipes and recreates. The emulator covers all 3D demos
and AR UI/state QA. AR features that need real world tracking (Cloud Anchor,
Streetscape/VPS, face mesh against a live face) still need a physical-device
AR Record — request one rather than driving someone's personal phone over USB.

**Visible (windowed) emulator — opt-in (#1660).** The emulator boots **headless
by default** (`-no-window`), which is marginally lighter on the host (skips the
skin-window draw + window-server compositing). To watch it locally, opt in:

```bash
bash .claude/scripts/setup-ar-emulator.sh --window   # windowed, this run
EMU_VISIBLE=1 bash .claude/scripts/setup-ar-emulator.sh   # equivalent env var
```

`--window` simply sets `EMU_VISIBLE=1`. The guest VM cost (RAM, pool, leases) is
identical either way — only the host window draw differs — so the default stays
headless and resource-safe. **Local only:** CI (`device-qa.yml`) never sets this
and stays headless (GitHub runners have no display).

**RAM-budgeted adaptive emulator pool (#1647 → #1654).** The harness runs an
**adaptive pool** of emulators — as many as live host RAM safely allows, with a
floor of 1 and never a rigid barrier. #1647's strict-single design is superseded:
parallel sessions / agents no longer serialise behind one emulator when the host
has RAM to spare. `setup-ar-emulator.sh` (via `lib/emulator-select.sh`):
- **computes a RAM-budgeted cap** —
  `max_emulators = floor((free_RAM − EMU_HOST_HEADROOM_MB) / EMU_RAM_BUDGET_PER_EMU_MB)`,
  clamped to `[1, EMU_POOL_MAX]` (defaults: headroom 2048 MB, budget 3072 MB/emu,
  `EMU_POOL_MAX=3`). On a RAM-tight host this resolves to 1 naturally — physics,
  not policy;
- **leases from a per-emulator pool** — each running emulator has a lease file
  (`${TMPDIR:-/tmp}/sceneview-device-qa-emu/<serial>.lease`, owner pid inside). A
  caller leases a free running emulator; else, if the running count is below the
  live cap, boots a new one on a distinct `-port` (5554, 5556, …) so emulators
  coexist; else waits (bounded) for a lease to free;
- **re-gates RAM before every boot** — free RAM is re-read immediately before
  each boot and the boot is refused below `EMU_MIN_FREE_RAM_MB` (default 3072 MB)
  even when the cap said there was room. Memory safety is the hard invariant —
  the pool never pushes the host into RAM exhaustion;
- **right-sizes `-memory`** — scales the guest memory flag to RAM headroom,
  clamped to `[EMU_MEMORY_FLOOR_MB, EMU_MEMORY_CEILING_MB]` (2048–4096 MB);
- **reclaims stale leases** — a lease whose owner pid is dead AND whose serial is
  gone from `adb devices` is reclaimed automatically.
Every threshold is env-overridable. `setup-ar-emulator.sh --check` reports pool
state (running count, computed cap, free RAM, active leases). The QA scripts
(`device-qa.sh`, `qa-android-demos.sh`, `ar-replay-qa.sh`) pin `ANDROID_SERIAL`
to the leased emulator so the right device is targeted when the pool has several.

`--check` now also reports host free RAM and whether a running emulator would be
reused. This is why parallel Claude Code sessions running device-QA on the same
RAM-constrained Mac no longer contend for emulator resources.

## Samples

One unified showcase app per platform — all features integrated into tabs.

| Directory | Platform | Demonstrates |
|---|---|---|
| `samples/android-demo` | Android | Play Store app — 4-tab Material 3 (3D, AR, Samples, About), 37 demos (24 3D + 13 AR) |
| `samples/android-tv-demo` | Android TV | D-pad controls, model cycling, auto-rotation |
| `samples/web-demo` | Web | Browser 3D viewer, Filament.js (WASM), WebXR AR/VR |
| `samples/ios-demo` | iOS | App Store app — 3-tab SwiftUI (3D, AR, Samples) |
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

## Version Location Map

**Source of truth:** `gradle.properties` -> `VERSION_NAME=X.Y.Z`

Every file below MUST be updated when bumping the version. Use `/version-bump` or `bash .claude/scripts/sync-versions.sh --fix`.

| Category | File | Pattern |
|---|---|---|
| **Android** | `gradle.properties` (root) | `VERSION_NAME=X.Y.Z` |
| | `sceneview/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| | `arsceneview/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| | `sceneview-core/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| **npm** | `sceneview-web/package.json` | `"version": "X.Y.Z"` |
| | `react-native/react-native-sceneview/package.json` | `"version": "X.Y.Z"` |
| **Flutter** | `flutter/sceneview_flutter/pubspec.yaml` | `version: X.Y.Z` |
| | `flutter/.../android/build.gradle` | `version 'X.Y.Z'` |
| | `flutter/.../ios/sceneview_flutter.podspec` | `s.version = 'X.Y.Z'` |
| **Docs** | `llms.txt` | `io.github.sceneview:sceneview:X.Y.Z` |
| | `README.md` | install snippets |
| | `CLAUDE.md` | code examples section |
| | `docs/docs/index.md` | install snippets |
| | `docs/docs/quickstart.md` | dependency snippets |
| | `docs/docs/llms-full.txt` | artifact versions |
| | `docs/docs/cheatsheet.md` | install snippets |
| | `docs/docs/platforms.md` | install line |
| | `docs/docs/android-xr.md` | install snippets |
| | `docs/docs/migration.md` | "upgrade to" version |
| **Website** | `website-static/index.html` | softwareVersion, badge, code |
| | `sceneview.github.io/index.html` | deployed version (separate repo) |
| **Samples** | `samples/android-demo/build.gradle` | versionName default |
| | `sceneview/Module.md` | version ref |
| **Swift** | `SceneViewSwift/` uses git tag `vX.Y.Z` | not a file version |

> ⚠️ **Do NOT bump the Flutter/RN plugins' *consumed* SceneView dependency.**
> The `io.github.sceneview:(ar)sceneview:X.Y.Z` lines in
> `flutter/.../android/build.gradle` and
> `react-native/.../android/build.gradle.kts` are dependencies on the
> **published** Maven Central artifact — they must lag to the **last released**
> version and cannot point at the in-flight release (it isn't on Maven Central
> yet; pointing at it breaks the `Build flutter-demo APK` CI check). Only the
> plugins' OWN package versions (`version 'X.Y.Z'`, `pubspec.yaml`, podspec,
> `package.json`) bump to the release version. `sync-versions.sh` reports these
> consumed-dep coordinates WARN-only and never auto-bumps them (issue #1494).

> ⚠️ **`mcp/package.json` and `mcp/src/index.ts` follow an INDEPENDENT version
> track — do NOT sync them to `VERSION_NAME`.**
> `sceneview-mcp` (npm) has its own release cadence (e.g. `4.0.12` while the
> SDK is at `4.10.0`) and is published independently of the Maven Central
> artifacts. `sync-versions.sh` deliberately **excludes** `mcp/package.json`
> from the version check — forcing them to match once caused a regression
> where the sync agent downgraded `mcp/package.json` behind the published npm
> `@next` tag. When releasing `sceneview-mcp`, bump these two files to the
> next *MCP* version, never to the SDK `VERSION_NAME` (issue #1705).

**Automation:**
- `bash .claude/scripts/sync-versions.sh` — checks all 30+ locations
- `bash .claude/scripts/sync-versions.sh --fix` — auto-fixes mismatches
- Claude Code plugin marketplace lives in [`sceneview/claude-marketplace`](https://github.com/sceneview/claude-marketplace) — run `bash scripts/sync-plugin-versions.sh` from THAT repo
- `bash .claude/scripts/quality-gate.sh` — full pre-push quality gate
- `bash .claude/scripts/cross-platform-check.sh` — API parity across platforms
- `bash .claude/scripts/release-checklist.sh` — pre-release validation

---

## Session continuity

Every Claude Code session MUST read this section first to stay in sync.

**NOTE FOR OTHER SESSIONS:** Always run `/sync-check` at the start and end of every session.
Never say "everything is good" without verifying published packages.

### Latest release: see `gradle.properties`

**The source-of-truth version is always `VERSION_NAME` in the root `gradle.properties`** — read that file, never hardcode a version here. Any AI bootstrapping from this file should treat the `gradle.properties` `VERSION_NAME` as the latest published version across all surfaces (Maven Central, npm `sceneview-web`/`@sceneview-sdk/react-native`, SPM tag `vX.Y.Z`, web CDN). At the time of writing this is `4.9.0`, but `gradle.properties` is authoritative if they ever disagree. The dated session logs below are historical context only — do not infer the latest version from them.

### Current state (last updated: 2026-05-20 night, session post-vibrant-shtern — v4.11.1 patch SHIPPED end-to-end)

- 🚀 **v4.11.1 released** — tag `v4.11.1` pushed at HEAD `d55ab7d13`. release.yml ✅, play-store.yml ✅, docs.yml ✅, app-store.yml iOS TestFlight upload ✅ (macOS leg failed on #1794, explicitly out of scope). 24 changelog fragments collated (3 new patch fixes + 21 post-v4.11.0 follow-ups). [GitHub Release v4.11.1](https://github.com/sceneview/sceneview/releases/tag/v4.11.1).
- 🛠️ **3 deploy-time fixes** that motivated the patch:
  - [#1795](https://github.com/sceneview/sceneview/issues/1795) — `app-store.yml`'s submit step now reads `VERSION_NAME` from `gradle.properties` on `workflow_dispatch` fallback (was `build_version` = `CFBundleVersion` = `367`, which produced nonsense App Store version records). Validated in v4.11.1's run: `Target App Store versionString: 4.11.1` then `Reused editable draft …: retargeted 367 → 4.11.1`. The #1687 + #1795 retargeting works end-to-end.
  - [#1788](https://github.com/sceneview/sceneview/issues/1788) — `react-native/react-native-sceneview/package.json` declares `publishConfig.access=public` as belt-and-suspenders to the workflow's `--access public` flag. v4.11.0's original 404 cascaded from the webpack build failure (resolved by main's #1791 webpack pin), but the declarative anchor closes the ambiguity.
  - [#1789](https://github.com/sceneview/sceneview/issues/1789) — `sceneview-web jsBrowserProductionWebpack` regression on v4.11.0 (kotlin-web-helpers vs webpack 5.107.0 `ModuleNotFoundError` path move). Already fixed on main by [#1791](https://github.com/sceneview/sceneview/pull/1791) (`webpack <5.107.0` resolution pin); v4.11.1 re-validates the publish path. Build green locally + on tag-push.
- 🟢 **App Store version record 367 résorbé** — the stranded 4.11.0 draft was renamed to `4.11.1` and now carries the v4.11.1 TestFlight build. The version record is clean.
- 🔴 **App Store submission still 403s** on CREATE because the stale submission attached to the (renamed) draft persists: `"The resource 'appStoreVersionSubmissions' does not allow 'CREATE'. Allowed operation is: DELETE"`. Step exits 0 (workflow stays green) but the build isn't auto-submitted for review. Filed [#1831](https://github.com/sceneview/sceneview/issues/1831) — extend the submit step to GET the existing `appStoreVersionSubmission` relationship and DELETE it before POST. Manual click in App Store Connect web UI works as a one-off.
- 🍎 **macOS App Store still failing on #1794** — `Theme.swift` / `ExploreTab.swift` use iOS-only SwiftUI APIs (`Color(.systemBackground)`, `.secondarySystemBackground`, `.tertiarySystemBackground`). Explicitly scoped out of v4.11.1 per Thomas; tied to the v2.2.0 Beta Testing rejection refactor.
- ⚙️ **Worktree note**: the v4.11.1 patch worktree was deleted automatically by `worktree-auto-prune.sh` after the push (likely from the daily maintenance hook). Everything is on `origin/main` (`d55ab7d13`) + tag `v4.11.1` (`d553148d7`); the handoff worktree (`.claude/worktrees/v4.11.1-handoff`) only carries this CLAUDE.md edit.
- 📦 **Two commits landed on main right after v4.11.1**: [#1828](https://github.com/sceneview/sceneview/pull/1828) (`SceneViewJS.SCENEVIEW_VERSION` bumped to 4.11.1 + sync-versions WARN promoted to ERROR — closes a regression latch) and [#1829](https://github.com/sceneview/sceneview/pull/1829) (recording/playback completeness — `PlaybackStatus`, `IO_ERROR`, custom tracks, scoped-storage Uri). Both for the next release (v4.11.2 or v4.12).

### Followups for next session

1. **Land [#1831](https://github.com/sceneview/sceneview/issues/1831)** so the next absorbed-draft release auto-deletes the stale `appStoreVersionSubmission` and the submit POST returns 201 — closes the last loose end of the #1795/#1687 saga.
2. **Tackle #1794 macOS SwiftUI** (cross-platform `Color(.systemBackground)` etc. in `Theme.swift` + `ExploreTab.swift`) — paired with the v2.2.0 Beta Testing rejection refactor.
3. **Manually click "Submit for Review"** in App Store Connect on the existing `4.11.1` draft (or fix #1831 + re-trigger `app-store.yml` via `workflow_dispatch`).
4. **Coordination check** — the `claude/qa-1439-autofit-framing-v2` session was still dirty in the primary checkout when v4.11.1 was cut. Confirm it lands / pauses / merges before the next release.

### Previous state (last updated: 2026-05-20 evening, session vibrant-shtern-147a5c — v4.11.0 SHIPPED + store-deploy correctness sweep)

- 🚀 **v4.11.0 released** — tag `v4.11.0` pushed at HEAD `f55b43e1e`. release.yml + play-store.yml + app-store.yml + docs.yml all fired. 20 changelog entries (19 collated fragments + #1380 picked up post-rebase). [GitHub Release v4.11.0](https://github.com/sceneview/sceneview/releases/tag/v4.11.0).
- 🛠️ **Store-deploy correctness sweep, 5 PRs**:
  - [#1678](https://github.com/sceneview/sceneview/pull/1678) — Play Store deploy self-heals a corrupt release AAB (fixes the silent loss of v4.6.0+v4.6.1 from a flaky CI runner).
  - [#1680](https://github.com/sceneview/sceneview/pull/1680) — **App Store submission step was silently `pip`-failing on every release** (working-directory `samples/ios-demo` made the requirements file path unresolvable since #1286) + Play Store sync targets the actual `en-GB` locale + listing copy aligned with the live app names.
  - [#1687](https://github.com/sceneview/sceneview/pull/1687) — iOS deploy absorbs a stale `PREPARE_FOR_SUBMISSION` draft by PATCHing its versionString to the release version (the comment promised this fallback; the code now does it).
  - [#1688](https://github.com/sceneview/sceneview/pull/1688) — demo app unified on **"SceneView"** everywhere (`CFBundleDisplayName`/`CFBundleName` literal so the macOS menu name no longer depends on per-target `PRODUCT_NAME`; Android Play title + manifest intent-filter labels; resolves Apple 2.3.8 name-mismatch rejection).
  - Plus 2 in-band fixes to `release-checklist.sh` (pipefail-vulnerable `grep` patterns + SECURITY.md under `.github/`).
- 📋 **4 follow-up issues filed**: [#1691](https://github.com/sceneview/sceneview/issues/1691) Vitals release-gate · [#1692](https://github.com/sceneview/sceneview/issues/1692) Play Store reviews ingestion · [#1695](https://github.com/sceneview/sceneview/issues/1695) deep-link verification monitoring (all platforms) · [#1710](https://github.com/sceneview/sceneview/issues/1710) consolidate `play/` vs `distribution/play-store/` + sync graphics.
- 🔓 **Play Console service-account permission "Manage store presence"** granted by Thomas during the session — the listing-sync 403 is unblocked. The deploy SA also has `View app information / bulk reports` + `View app quality information` (read-only, enabling #1691/#1692) + `Manage deep links` + `Manage policy declarations`. Admin explicitly NOT granted.
- 🍎 **macOS App Store**: 1.0 was REFUSÉE under 2.3.8 (app-name mismatch ASC "SceneView" vs installed "SceneViewDemo"). Metadata corrected directly in ASC during the session (description without AR/Sketchfab claims, keywords without "AR" / "augmented reality"). Resubmission still needs a NEW build carrying the unified "SceneView" name (#1688 delivered the rename; v4.11.0 produces that build) — Thomas will resubmit from the Soumission macOS page. `MACOS_PROVISIONING_PROFILE_BASE64` secret is still missing → macOS deploy job stays `skipped` until that's set.
- 🛍️ **Play Store visuals** still pending manual upload: `samples/android-demo/play/listings/en-US/graphics/` has a real `feature-graphic.png` (SceneView cube on starfield) + 4 phone screenshots + `icon-512.png` that have never reached the live store because the CI listing-sync syncs text only. #1710 captures the consolidation + auto-sync. Tablet screenshots + promo video genuinely don't exist.

### Followups for next session

1. **Watch v4.11.0's deploy workflows complete** — Maven Central, npm, GitHub Release, Play Store, App Store. If `app-store.yml`'s submit step actually creates the 4.11.0 version record and absorbs the stranded 4.3.0 draft, that validates the #1680+#1687 fix end-to-end.
2. **Resubmit macOS** (manual ASC click) once the v4.11.0 macOS build is available — the metadata is already corrected.
3. **Upload Play Store graphics manually** (feature graphic + phone screenshots from `play/listings/en-US/graphics/`) or land [#1710](https://github.com/sceneview/sceneview/issues/1710) so the next release auto-syncs them.
4. **Pick up #1691 (Vitals gate) or #1692 (Play reviews → triage issues)** — both are real-user post-release telemetry that complements the device-QA harness; permissions are already granted.
5. Coordination: 3 parallel sessions were active during this work (`claude/qa-1439-autofit-framing-v2`, `claude/cool-nightingale`, `claude/sharp-bhaskara`) — main moved by 24 commits in a few hours. Confirm they've landed/paused before the next release.

### Historical state (last updated: 2026-05-14 night, session upbeat-kare-a31ed4 — v4.2.0 SHIPPED end-to-end + handoff for new session)

- 🚀🚀 **v4.2.0 fully published** — Maven Central `<latest>4.2.0</latest>` (.pom HTTP/2 200), npm `sceneview-web@4.2.0` + `@sceneview-sdk/react-native@4.2.0`, Dokka API docs, [GitHub Release v4.2.0](https://github.com/sceneview/sceneview/releases/tag/v4.2.0) auto-populated, Play Store production track delivered. **First fully-green `release.yml` run** (all 7 jobs ✅) since the v4.1.0 saga — release.yml hardening #1002 worked end-to-end.
- ✅ **iOS parity sprint major scope COMPLETE** ([umbrella #1004](https://github.com/sceneview/sceneview/issues/1004) ~75% done). v4.2.0 ships LightSlot + LightNode.fill + .mainLight/.fillLight (#1016), RenderQuality preset (#1018), onEntityTapped real entity hit-test + CameraNode.exposure docs (#1019, #928), NodeGesture wire (#1024, #928), AnchorNode.image/.face/.body (#1025, #894 partial), iOS deep-link routing model-viewer + multi-model (#1020, closes #1015), reactive light update (#1031, closes #1017), AR sessionInterruption + LightNode.spot innerAngle clamp (#1013, #928).
- 🔵 **5 v4.3.0 follow-up issues filed at handoff**: [#1032 ARRecorder via ReplayKit](https://github.com/sceneview/sceneview/issues/1032), [#1033 sceneview-core XCFramework](https://github.com/sceneview/sceneview/issues/1033), [#1034 CameraControls.firstPerson + .pan port](https://github.com/sceneview/sceneview/issues/1034) (last #928 fixable), [#1035 ViewNode<Content> v5.0](https://github.com/sceneview/sceneview/issues/1035), [#1036 RealityKit-impossible APIs umbrella](https://github.com/sceneview/sceneview/issues/1036). Plus [#1026 camera framing](https://github.com/sceneview/sceneview/issues/1026#issuecomment-4445612584) with detailed design comment.
- 📦 **Cumulative session stats (upbeat-kare-a31ed4 across 2 days)**: 32 PRs merged + 23 issues filed + 3 tags shipped (v4.1.0, v4.1.1, **v4.2.0**). Cool-wescoff shipped v4.1.2 in parallel.

### Followups for next session

Full pickup notes in `~/.claude/projects/-Users-thomasgorisse-Projects-sceneview/memory/handoff_2026-05-14_v4.2.0_shipped.md`. Recommended order:

1. **[#1034 CameraControls.firstPerson + .pan port](https://github.com/sceneview/sceneview/issues/1034)** — last #928 fixable item, M/MED. Port math from Android `CameraGestureDetector.kt`.
2. **[#1026 camera framing auto-center](https://github.com/sceneview/sceneview/issues/1026)** — biggest UX win. Detailed design in issue comment: intermediate `contentRoot` Entity + auto-center on first stable frame.
3. **[#1032 ARRecorder ReplayKit](https://github.com/sceneview/sceneview/issues/1032)** OR **[#1033 sceneview-core XCFramework](https://github.com/sceneview/sceneview/issues/1033)** — pick based on AR-vs-animation audience priority.
4. **Close [#1036 RealityKit-impossible umbrella](https://github.com/sceneview/sceneview/issues/1036)** by adding the parity table to `docs/docs/cheatsheet-ios.md`.

### Previous state (last updated: 2026-05-13 evening, session upbeat-kare-a31ed4 — iOS parity v4.2.0-WIP sprint, post-restart)

- 🚀 **iOS parity sprint shipping toward v4.2.0** ([umbrella #1004](https://github.com/sceneview/sceneview/issues/1004)). Cumulative today **15 PRs landed by upbeat-kare** + 7 PRs by cool-wescoff (v4.1.2 Android demo recovery). All on top of v4.1.2 (`50145a78` → +12 commits).
- ✅ **iOS parity items shipped this evening (post-restart)**:
  - [#1016 LightSlot enum + LightNode.fill factory + .mainLight/.fillLight modifiers](https://github.com/sceneview/sceneview/pull/1016) — addresses every Agent 1+2 finding from the previous review (LightSlot vs Optional<LightNode?>, @MainActor vs Sendable, drop circular v4.2.0 docs, castsShadow param, no baked orientation, bump fallback defaults to Android-parity 10_000/3_000 lux + (0.5,-0.5,0.5) direction).
  - [#1018 RenderQuality preset + .renderQuality(_:) modifier](https://github.com/sceneview/sceneview/pull/1018) — `.cinematic` / `.default` / `.performance`, walks DirectionalLight children + adjusts IBL `intensityExponent`. RealityKit-vs-Filament parity gap documented in enum doc-comment (no SSAO/MSAA/HDR-buffer/bloom on iOS).
  - [#1019 onEntityTapped real hit-test + CameraNode.exposure docs (#928)](https://github.com/sceneview/sceneview/pull/1019) — `SpatialTapGesture().targetedToAnyEntity()` so callback gets actual tapped entity (was always `entities.root`). CameraNode.exposure stays deprecated no-op with clear redirect to working alternatives (verified `PerspectiveCameraComponent.exposureCompensation` does NOT exist despite Agent 4's claim — direct Xcode 26.x build confirmed).
  - [#1020 deep-link routing model-viewer + multi-model](https://github.com/sceneview/sceneview/pull/1020) — closes [#1015](https://github.com/sceneview/sceneview/issues/1015). Both ids now route to `SceneGalleryDemo` instead of "Coming soon" placeholder.
  - [#1024 NodeGesture wire to SwiftUI gestures (#928)](https://github.com/sceneview/sceneview/pull/1024) — last big "fixable" #928 item. 5 new `.simultaneousGesture(...).targetedToAnyEntity()` route to `NodeGesture.dispatch{Tap,Drag,Scale,Rotate,LongPress}`. Empty-space gestures still drive camera.
  - [#1025 AnchorNode.image / .face / .body factories](https://github.com/sceneview/sceneview/pull/1025) — closes 3 of the iOS AR parity gaps (#1004 + #894 partial). 1-line wrappers around RealityKit's existing `AnchorEntity(...)` cases.
  - [#1027 llms.txt + 11 SPM refs bump to 4.1.2](https://github.com/sceneview/sceneview/pull/1027) — exposes the new v4.2.0 iOS API surface to MCP consumers.
- ✅ **Visual smoke test on iPhone 16e simulator confirmed SHIP for all 4 v4.2.0-WIP PRs** (#1016 / #1018 / #1019 / #1020). 5 demos captured, no regression. Side-finding (off-center camera framing in all iOS demos — pre-existing, not regression) filed as [#1026](https://github.com/sceneview/sceneview/issues/1026).
- 🔵 **Follow-up issues filed**: [#1017 reactive light update](https://github.com/sceneview/sceneview/issues/1017) (Android `prevFillLightRef` swap pattern → iOS `RealityView.update:`), [#1026 camera framing off-center](https://github.com/sceneview/sceneview/issues/1026).
- 📦 **#928 silent-stub batch FIXABLE items COMPLETE** for v4.2.0: sessionInterruptionEnded full re-apply (#1013), spot innerAngle clamp (#1013), onEntityTapped real hit-test (#1019), NodeGesture dispatch wire (#1024), CameraNode.exposure docs (#1019, negative result). Remaining stubs are RealityKit-impossible and stay deprecated with clear redirect docs.

### Followups for next session

1. **Cut v4.2.0** — major iOS parity wins shipped (LightSlot + RenderQuality + #928 fixable batch + AR anchors). Acceptance criteria of #1004 mostly met; remaining items (CloudAnchorNode, ARRecorder via ReplayKit, sceneview-core XCFramework, reactive light update #1017) can ship in v4.3.0.
2. **Continue iOS parity** if not cutting v4.2.0 yet:
   - **ARRecorder record-only via ReplayKit** (M, MED, no playback)
   - **sceneview-core XCFramework** + Swift bridge for NodeAnimator/Spring/Property (M, MED)
   - **Reactive light update** (M, fixes #1017 limitation in #1016)
   - **Camera framing centering** (#1026, M, library-level vs per-demo)
3. **Wait for cool-wescoff** — she shipped v4.1.2 + #1021 fixture autosync; check if she's still active before touching shared zones.
4. **Daily metrics 2026-05-13** captured at memory `metrics_2026-05-13.md`.

### Previous state (last updated: 2026-05-13, session cool-wescoff-28cd9c — v4.1.2 demo recovery)

- 🚀 **v4.1.2 SHIPPED** end-to-end:
  - PR [#1010](https://github.com/sceneview/sceneview/pull/1010) merged to main as commit `d16dc9b9`
  - Tag `v4.1.2` pushed → `release.yml` (Maven Central + GitHub Release + Dokka) + `play-store.yml` (internal + production tracks) firing
  - 7 commits on branch: AR launcher fix, Samples M3 Expressive grid, Explore polish, .filamat revert (superseded by main's v4.1.1 1.71 bump on merge), matc 1.70.2 recompile (also superseded), 5-agent review fixes, version bump 4.1.0 → 4.1.2
- 🩹 **Demo app recovery** (Thomas: "ya rien qui va… horrible UI/UX… moitié des samples qui marchent pas"):
  - **AR View tab** no longer crashes the app. New launcher screen gates the live `ARSceneView` behind an explicit "Start AR Camera" CTA + `ArCoreApk.checkAvailability()` status pill + 2×3 grid of headline AR demos. `runCatching` around the availability check; CTA hard-disabled on `UNSUPPORTED_DEVICE_NOT_CAPABLE` / `UNKNOWN_*`. Top-right exit button for back affordance. `sessionStarted` is `rememberSaveable` for process-death survival.
  - **Samples tab** replaces the plain `ListItem` text list with a 2-col M3 Expressive grid. Compact accent-tinted icon tile (36% of card, was 55%), per-category accent hues (3D Basics purple, Lighting amber, Content blue, Interaction pink, Advanced teal, AR green), per-demo semantic Material icon. `DemoEntry` carries `icon: ImageVector` and `status: DemoStatus` (Working / KnownIssue / ComingSoon); non-Working demos surface an outlined "Preview" chip (not red alarm).
  - **Explore tab** drops the dev-flavored "Set SKETCHFAB_API_KEY" placeholder that was leaking to end-user Play Store builds. `SampleCard` rebuilt to match `DemoCard` styling. Empty `FeedSection`s self-hide instead of stacking three "Nothing here yet." headers under each other.
- 🛡️ **5-agent Opus independent review** ran in parallel before merge — 2 BLOCKER + 6 MAJOR + N MINOR all addressed in commit `2556c467`:
  - `ArCoreApk.checkAvailability` now wrapped in `runCatching` (BLOCKER — could silently die on OEMs without Play Services).
  - Permission-denied path now flips `sessionStarted = false` (BLOCKER — was stranding users on a dead placeholder).
  - `rememberTopAppBarState` for the Samples scroll behavior (MAJOR — collapse offset reset on every recomposition).
  - Grid item keys namespaced `"demo-${id}"` (MAJOR — guarded against id collision).
  - Dark-mode accent palette: each category resolves to a desaturated hue on dark theme so tinted tiles don't burn at >9:1 contrast (MAJOR).
  - 4 better semantic icons (gesture-editing → OpenWith, collision → CenterFocusStrong, shape → Pentagon, physics → ScatterPlot).
  - **Holistic agent caught the real Filament .filamat ABI root cause** — `efd296f1` bumped blobs to matc 1.71 but `4a31b579` reverted runtime to 1.70.2 without recompiling blobs. Two opposite fixes shipped in parallel: this branch reverted blobs back to 1.70.x + recompiled the 2 unlit_color ones with matc 1.70.2 (downloaded from upstream v1.70.2 release tarball); main's v4.1.1 hotfix went the other way and bumped the runtime to 1.71 to match the existing 1.71 blobs. On merge we took main's 1.71 truth.
- ✅ **25 / 25 non-AR demos pass** on Pixel_7a `-gpu host` emulator (was 14 / 25 in the v4.1.0 audit). Previously crashing: `lighting`, `movable-light`, `fog`, `environment`, `text`, `lines-paths`, `image`, `billboard`, `view-node`, `debug-overlay` — all now render.
- 🛑 **Memory rule rewritten** — `feedback_stitch_mandatory.md` now drops Google Stitch as the mandated UI source for SceneView demo. Reference-driven (Sketchfab mobile / Polycam / Reality Composer) + `DESIGN.md` tokens is the new workflow. Figma reserved for marketing surfaces only.
- 🗝️ Sketchfab API key now supported in `local.properties` (`sketchfab.api.key=...`) for developer builds. CI flow unchanged (still sources from the GitHub Secret `SKETCHFAB_API_KEY`). Verified the key never appears in any committed file via `git grep`.
- 📋 Next session follow-ups: 5-agent review left a few MINOR items (extract shared `DemoCard` to `samples/android-demo/.../ui/common/`, monitor `tools/qa-screenshots/` cleanup, document the "Filament version + matc must match" invariant in `CONTRIBUTING.md`). All non-blocking.

### Current state (last updated: 2026-05-11, session elegant-wright-b9cd6f — audit-sweep + 5-agent review)

- 🚀 **40 commits / 22 issues fermées / 5 follow-ups filés** (#971–#975) in a single session.
  - **Security cluster shipped**: DOM XSS in web-demo (#957 Kotlin + post-review JS-path patch in `3f369736`), --es demo registry validation (#958), Auto Backup exclude-all rules covering both `dataExtractionRules` (API 31+) AND `fullBackupContent` (API 24..30, added post-review) (#959), CSP meta on all 27 HTML files (#960).
  - **Build / release polish**: Filament pinned 1.71→1.70.2 to match committed `.filamat`s (#961), JaCoCo wired with CI artefacts + step-summary (#964), root `Package.swift` so SPM consumers ride the monorepo (no more `sceneview-swift` mirror lag) (#920), `release.yml` gate tightened + publish-rn + validate-spm jobs added (#962), web-demo finally building to `/web-demo/` via `jsBrowserDistribution` (#926, fixed post-review — `Webpack`-only task missed HTML).
  - **MCP** : version drift sweep (#941, follow-up sweep on remaining string-template leftovers in `de692709`), 9 dead modules dropped from npm tarball (-57 kB packed / -235 kB unpacked, #938), validator catches the `Scene { } → SceneView { }` rename (#939), `autoDetectIssue` regex catches AR+black/dark natural phrasings without false-positive on 3D-only sentences (#940 + post-review tightening in `c7f0399f`).
  - **UX** : haptic on shutter / mode chips / qaMode long-press (#956), QA pill tap-to-disable (#951), `LifecycleAwareLaunchedEffect` helper + GeometryDemo/DebugOverlay migration (#936; AnimationDemo REVERTED post-review due to camera teleport on foreground — tracked in #974), `rememberMaterialInstance` helper with PBR-key safety pattern (#937 + #971 follow-up for the 10-demo migration sweep).
  - **Docs** : iOS deep-link surfaced in README (#918), RN package name corrected with explicit "3.6.1 stale, 4.x publish pending" disclaimer (#924), Flutter pub.dev avoidance with git ref (#923), KDoc for the three smallest fully-undocumented files (#965 partial), `sceneview-web` hand-written `.d.ts` shipped (#946).
  - **Scripts** : `capture-play-store-screenshots.sh` lifted from chat history into `.claude/scripts/` (#919, with #975 follow-up for `--status-bar-px` flag + reject-multi-device).
- 🛡️ **5-agent Opus independent review** ran in parallel after the initial wave and caught **3 BLOCKERS + 4 MAJORS + 8 MINORS** before any public announcement:
  - DOM XSS duplicate in the inline-JS path of `samples/web-demo/src/jsMain/resources/index.html` (Kotlin fix didn't reach it).
  - MCP `list_platforms` rendered literal `${LATEST_SCENEVIEW_RELEASE}` text — substring was in `"..."` not `` `...` ``.
  - `data_extraction_rules.xml` covered 1 storage domain instead of 5, and `fullBackupContent` was missing entirely so API 24..30 still leaked to Drive.
  - `docs.yml` web-demo deploy used `jsBrowserProductionWebpack` (HTML missing) instead of `jsBrowserDistribution`.
  - Plus 4 MAJORs (rememberMaterialInstance PBR-key churn, AnimationDemo teleport-on-foreground, RN docs 3.6.1 stale, MCP residual `4.0.0`/`1.70.1` leftovers). All shipped in `de692709`.
- ✅ **3002 tests passing** end-to-end (sceneview 306 + arsceneview 123 + samples 41 + MCP 2532). 8 pre-existing orphan `dist/*.test.js` suite-loads still fail (modules dropped by #938, stale compiled tests).
- ✅ `:samples:android-demo:assembleDebug` → 161 MB APK built. Deploy website + docs CI green on `f2af4615`.
- 📊 **JaCoCo baselines surfaced**: `sceneview` 7.46 % line coverage, `arsceneview` 10.24 %. Honest starting point; delta-coverage gate is #973's job.
- 50 → 39 open issues. Remaining critical: #966 audit umbrella, #932 Delaunator thread-safety, #933 MaterialLoader coroutine leak, #934 APK 84→30 MB, #928 SceneViewSwift 21 silent stubs, #929 SceneViewSwift 9 test fails, #917 iOS App Store screenshots stale.

### Followups for next session

1. **#971** — migrate the remaining 10 demos to `rememberMaterialInstance` (good-first-issue; PBR-key safety doc'd in the helper).
2. **#972** — wire test infra in `samples/common/` so `LifecycleAwareLaunchedEffect` + `rememberMaterialInstance` get regression pins. Prereq for #971.
3. **#973** — JaCoCo delta-coverage gate. Establish baseline file + fail PR if `delta < -0.5pp`.
4. **#974** — state-preserving lifecycle pattern for AnimationDemo. 3 design options sketched.
5. **#975** — capture-play-store-screenshots.sh hardening (multi-device reject, --status-bar-px, variance threshold).
6. Optional: continue #965 KDoc sweep — 7 more fully-undocumented files (`RenderableManager.kt`, `NodeAnimator.kt`, `Cube/Cone/Cylinder/Sphere/Capsule/Torus.kt`, `Texture/VideoTexture/TextureSampler/ImageTexture.kt`, `UbershaderInstance.kt`, `sceneview-core/logging/Log.kt`). Pre-req for the Detekt `UndocumentedPublic*` rule.

### Previous state (last updated: 2026-05-08, session bold-villani-42902e — plugin marketplace + brand-scope cleanup + portfolio scrub)

- 🚀 **Claude Code plugin marketplace LIVE** at [`sceneview/claude-marketplace`](https://github.com/sceneview/claude-marketplace), single plugin scoped strictly to SceneView:
  - **sceneview** v4.0.11 (Apache-2.0) — `sceneview-mcp` + 11 namespaced contributor commands + cross-platform reminder hooks
  - Install: `/plugin marketplace add sceneview/claude-marketplace` then `/plugin install sceneview@sceneview`
- ⛔ **Strict rule** recorded in memory `feedback_sceneview_org_strict`: the `sceneview` GitHub org hosts ONLY SceneView-SDK artefacts. Personal-portfolio MCPs live elsewhere.
- 🔒 **Big repo scrub** committed in this session:
  - Removed off-topic personal-portfolio code from public sceneview/sceneview repo: `hub-gateway/`, `hub-mcp/`, `mcp-gaming/`, `mcp-interior/`, plus the strategy / submission docs that listed every personal MCP
  - Scrubbed in-place mentions in `CLAUDE.md`, `ROADMAP.md`, `CHANGELOG.md`, `docs/docs/ai-context.md`, `.gitignore`
  - Untracked `.claude/handoff*.md`, `.claude/plans/`, `.claude/marketplace-submissions/`, `.claude/SESSION_*.md`, `.dart_tool/`, `qa_log.txt` — these were carrying employer-domain leakage and references to the suspended (lowercase) GitHub account
  - Verified the standard employer/portfolio identifier greps return **0 hits** in tracked HEAD files
  - `npm hub-mcp` deprecated with a "project discontinued — use individual MCPs directly" message; local backups deleted
- ⚠️ **Past git history** still contains the leaked strings + the moved-out files. A `git filter-repo` session is the next-up follow-up — must coordinate with the 9 active worktrees before force-push.

### Followups for next session

1. **filter-repo run** — scrub the historical employer/portfolio strings from the entire git history of `sceneview/sceneview`. See plan in `~/Projects/profile-private/plans/filter-repo-plan.md`.
2. **Submit Anthropic marketplace** — guide at `~/Projects/profile-private/marketplace-submissions/2026-05-07-anthropic-marketplace-submission.md` (Thomas clicks through the in-app form).
3. **Reddit + HN posts** — drafts at `~/Projects/profile-private/announcements/` (single-plugin framing, ready to post when Thomas is ready).
- 4-agent independent Opus review caught 6 BLOCKING + 5 MAJOR before public announcement: dead-code hooks (matcher syntax invalid, validator missed it), license mismatch (Apache-2.0 vs MIT), 1.4 GB monolithic clone (split to dedicated repo to fix), `/review` `/test` namespace collision with built-in skills, version drift (plugin pinned 4.0.9 vs npm @4.0.11), DL/mo numbers gonflés in announcement drafts. All BLOCKING fixed in [`a88f7f8c`](https://github.com/sceneview/sceneview/commit/a88f7f8c) + the marketplace migration.
- 🔒 CDI-safety scrub on the same commit: untracked all `.claude/handoff*.md`, `.claude/plans/`, `.claude/marketplace-submissions/`, `.claude/SESSION_*.md` etc. that were carrying employer-domain leakage and suspended-account references. `.gitignore` extended to stop new HEAD leaks. Past history still leaked — separate `git filter-repo` session is the next-up follow-up.
- Side effect: enriched `.claude/commands/*.md` with YAML frontmatter (`description:`) so `/help` now shows one-line summaries for every contributor command.
- README.md "Claude Code plugin" section explains the MCP + commands + hooks bundle and points at the dedicated marketplace repo.

### Previous state (last updated: 2026-05-07, session exciting-napier-1c8c70 — v4.0.9 SHIPPED + 5-agent review + Play Store fix)

- 🚀 **v4.0.9 fully published end-to-end** (verified):
  - Maven Central `sceneview/arsceneview/sceneview-core 4.0.8` ✅ (`<latest>4.0.8</latest>`)
  - npm `sceneview-mcp@4.0.10` ✅ on `@latest`
  - npm `sceneview-web@4.0.8` ✅
  - GitHub Release [v4.0.9](https://github.com/sceneview/sceneview/releases/tag/v4.0.9) ✅ — body contains the full CHANGELOG narrative
  - Dokka API docs ✅
  - App Store iOS v4.0.9 build 364 ✅ (Apple review in progress)
  - Play Store Android v4.0.9 production track ✅ (Google review in progress)
- ✅ **3 demos reconstructed** + ARFaceDemo migrated to `createUnlitColorInstance(PrimaryOverlay)` (translucent overlay, fixed by Agent 2 review)
- ✅ **Issue [#863](https://github.com/sceneview/sceneview/issues/863) closed** — `NoTangentsGlbContractTest` (6 JVM tests, regex-anchored after review hardening)
- ✅ **5-agent independent review** (Opus, parallel) → 13 findings → all BLOCKING + MAJOR + MINOR shipped in [`04e75ad5`](https://github.com/sceneview/sceneview/commit/04e75ad5)
- ✅ **Play Store race fix** (`max-parallel: 1`) — proven on next push, both internal + production tracks succeed for first time since v4.0.5
- ✅ **iOS pbxproj added to sync-versions.sh** — sync-versions now 29 checks, prevents the regression that hit this session
- ✅ **Cross-platform unlit parity** — Flutter / React Native bridges now expose `unlit: bool` (RN with type-safe ReadableType.Boolean check)
- 🟡 **4 issues open** (all enhancements): #876 (ARRecorder stateless API → v4.1 breaking), #874 (ImageNode/ViewNode destroy queue), #873 (cache SurfaceOrientation perf — verdict comment posted), **NEW [#878](https://github.com/sceneview/sceneview/issues/878)** (skip computeTangents when AugmentedFaceNode material is unlit — could close #873 as won't-fix if landed)

### Previous state (last updated: 2026-05-06, session wizardly-elbakyan — ARCore feature coverage sprint)

- 🚀 **6 commits shipped on main** — first-class ARCore Recording / Playback in `arsceneview/` (`ARRecorder` + `ARSceneView(playbackDataset = file)`), 6 new AR demos in `samples/android-demo/` (Record & Playback, Depth Occlusion, Instant Placement, Terrain Anchors, Rooftop Anchors, Image Stabilization EIS), 21 JVM unit tests for `ARRecorder` (Robolectric-backed), docs on 5 surfaces (`llms.txt`, mkdocs page, sample-app guide, README, CLAUDE.md). AR demo count went **7 → 13**.
- ✅ **14 reviews indépendantes Opus** across 3 feature commits (6 + 5 + 3) → 23 fixes applied → 0 BLOCKING dur at the end. 4-bucket triage works.
- ✅ **2 GitHub issues closed**: [#875](https://github.com/sceneview/sceneview/issues/875) (ARRecorder JVM tests), [#877](https://github.com/sceneview/sceneview/issues/877) (mkdocs page + README + sample-app guide).
- 🟡 **1 GitHub issue OPEN**: [#876](https://github.com/sceneview/sceneview/issues/876) — refactor `ARRecorder.attach()/start()` to a stateless `recordFrame(session, frame)` pattern matching `RerunBridge`, plus a dedicated `onPlaybackFailed` callback. Breaks the public API → bundle in v4.1 or use deprecation hygiene.
- ✅ **Build vert + tests verts à chaque push** — `:samples:android-demo:compileDebugKotlin` + `:arsceneview:testDebugUnitTest` (109 tests total: 21 new ARRecorder + 88 existing).
- ⚠️ **No on-device validation in this session** — emulator alone is insufficient for AR demos. Next session should run the 6 new demos on a Pixel 7a / Pixel 9 before claiming coverage is "done".
- ⚠️ **ARCore features still NOT exposed** by `arsceneview/` (candidates for next sprint): Scene Semantics (`Config.SemanticMode`), Mesh API (depth → polygonal mesh for physics), runtime AugmentedImageDatabase building (helper exists at `ArSession.kt:182` but no demo).
- 🟡 **Stale handoff note corrected** — previous "ARCore non encore exposé" list (in `eloquent-panini` session) included Recording/Playback and EIS — both shipped here, so those bullets are obsolete.

### Previous state (last updated: 2026-05-06, session nervous-payne — v4.0.2 cut + multi-agent PR sweep)

- 🚀 **v4.0.2 released** — `release.yml` succeeded end-to-end. Maven Central + npm `sceneview-web@4.0.2` + `sceneview-mcp` (skipped, already at 4.0.8 on independent track) + Dokka API docs + GitHub Release. Tag: `v4.0.2`. Notes: https://github.com/sceneview/sceneview/releases/tag/v4.0.2
- ✅ **13 PRs merged in this session**: #853 ViewNodeManager cleanup, #854 BillboardNode mirror (closes #838), #855 marketplace submission packet, #830 dependabot roborazzi, #857 ViewNode reactive props (closes #856), #858 BillboardNode JVM regression suite, #859 kotlin-math 1.6→1.8 docs sync, #860 CLAUDE.md session block, #861 handoff.md, #862 hono+postcss security (resolves 13 Dependabot alerts), #864 CHANGELOG Unreleased entry, #865 v4.0.2 version bump, #866 handoff v4.0.2.
- ✅ **Multi-agent review pattern documented** in memory `feedback_pr_review_workflow.md`: 5–7 Opus agents in parallel + 4-bucket triage. Caught: Engine.kt API breakage in #851 hold, latent Filament UAF in #852 hold, ViewNode reactive-props regression from #842 → fixed via #857.
- ⚠️ **2 PRs in HOLD with public verdicts**: [#851](https://github.com/sceneview/sceneview/pull/851) tender-haibt (6 blockers, conflicts with already-merged #821/#842/#850, rebase strategy posted), [#852](https://github.com/sceneview/sceneview/pull/852) AugmentedFace follow-ups (1 blocker — tangent buffer dangling Filament ref if vertex count changes).
- ✅ **0 Dependabot alerts** (was 13 before #862).
- ✅ **2 GitHub Discussions answered**: #843 (MaterialInstance still in use → fixed in v4.0.2), #844 (DEAD_OBJECT startMirroring → API removed in v4.x).
- ✅ **Stale memory corrected**: render tests are STILL `@Ignore`'d on SwiftShader CI — PR #814 added Engine-sharing but commit `0a6f4bea` re-ignored them. Pure-JVM math regressions can still land in `:sceneview:test` (cf. PR #858).
- ✅ **Marketplace packet preserved** in `.claude/marketplace-submissions/` (project-side, not profile-private). Account-tied identifiers (OpenAI App ID, GitHub support ticket) stripped + saved to `~/Projects/profile-private/marketplace-private/`.
- 2 open issues (#848 enhancement record video/photos, #863 follow-up GLB no-TANGENTS test).

### Previous state (last updated: 2026-04-13, session relaxed-faraday — Empire Dashboard + GA4 + Telemetry + Funnel)

- ✅ **Empire Analytics Dashboard** (`tools/empire-dashboard.html`): 7-tab dashboard (Portfolio, Tendances, Acquisition, Funnel, Monetisation, Usage/Telemetry, Actions). Live npm data via registry API, GitHub stars, Chart.js charts, MRR projections, telemetry worker integration, dark/light mode. Launch config in `.claude/launch.json`. Open: `open tools/empire-dashboard.html`
- ✅ **GA4 LIVE on sceneview.github.io** — Property "SceneView Website" (`G-HX1JWGSMTH`), stream ID 14357002837. Custom events: tab_click, cta_click, code_copy. MkDocs also wired with feedback widget.
- ✅ **Telemetry Worker DEPLOYED** — `sceneview-telemetry.mcp-tools-lab.workers.dev`, health OK. Real data: 6 events, 4 unique users, top tools: generate_scene, debug_issue, create_scene. STATS_TOKEN in `profile-private/credentials/sceneview-telemetry.env`.
- ✅ **sceneview-mcp@4.0.0-rc.6 published on @latest** — CTA sponsor now includes Pro pricing link (`/pricing` on gateway). All 13.7k monthly users see the conversion CTA.
- ✅ **Conversion funnel complete**: npm install → use free tools → 10th call CTA → pricing page → Stripe checkout (`cs_live_...`) → API key. Verified end-to-end. Links on: website nav, GitHub README, npm README, in-tool CTA.
- ✅ **npm dist-tags**: `{ latest: '4.0.0-rc.6', beta: '4.0.0-beta.1', next: '4.0.0-rc.5' }`.
- ✅ **0 open issues, 0 open PRs** — exhaustive audit of all 420 issues complete. 419 confirmed fixed, 1 found & fixed (#388 NodeAnimator, commit `3ae4d839`).
- ✅ **95 GitHub Discussions answered** with v4.0 solutions, migration guidance, and workarounds.
- ✅ **PR #814 merged** (`dcdb98df`): render-tests Engine-sharing fix (closes #803) + AR camera exposure API (closes #792). +117 new tests.
- ✅ **Dependency bumps**: AGP 8.11.1 → 8.13.2, maven-publish 0.35.0 → 0.36.0.
- ✅ **Stale branch cleanup**: `claude/confident-rhodes` remote branch deleted.
- ✅ **CI quality-gate GREEN on main** (run 24309254779). All checks pass: quality-gate, CI, PR Check, Build APKs, Deploy Website.
- ✅ **Repo cleanup complete**: 10 worktrees removed, 35+ local branches cleaned, 19 remote branches deleted, 6 stashes dropped.
- ✅ **polar.sh migration complete**: all 10 files with dead Polar URLs updated to GitHub Sponsors or gateway pricing URL. Zero polar.sh references remain in codebase (except historical handoff.md notes).
- ✅ **SceneView ↔ Rerun.io integration SHIPPED** (session `crazy-lichterman`, 5 phases merged on `main`):
  - Phase 1: `rerun-3d-mcp@1.0.0` on npm `@latest` — 5 tools, 73 vitest, `npx rerun-3d-mcp`
  - Phase 2: Playground "AR Debug (Rerun)" example — iframe Rerun Web Viewer embed
  - Phase 3: `io.github.sceneview.ar.rerun.RerunBridge` + `rememberRerunBridge` composable — non-blocking TCP, `Channel.CONFLATED` drop-on-backpressure, 10 Hz rate limit, 16 JVM tests
  - Phase 4: `samples/android-demo` RerunDebugDemo tile + `tools/rerun-bridge.py` Python sidecar
  - Phase 5: `SceneViewSwift.RerunBridge` + new `ARSceneView.onFrame` modifier + `samples/ios-demo` demo — `NWConnection` + `@Published eventCount`, 12 Swift tests
  - Wire format parity: 24 golden tests (12 Kotlin + 12 Swift) with character-identical expected JSON
  - Plan: `.claude/plans/fuzzy-prancing-turing.md`
- ✅ **Telemetry Worker DEPLOYED** (PR #815): `telemetry-worker/` Cloudflare Worker (Hono + D1 + KV) live at `sceneview-telemetry.mcp-tools-lab.workers.dev`. 6 endpoints (events, batch, stats, timeseries, export, health), client-side batching in `mcp/src/telemetry.ts`, dashboard.html with CSV export, `X-RateLimit-*` headers, CI workflow. **79 tests** (54 worker + 25 mcp). D1 `sceneview-telemetry` + KV `RL_KV` provisioned.
- ✅ **v4.0.0 release candidate** — all version locations bumped (`gradle.properties`, npm, flutter, docs, website, samples — 28 files). Git tag `v4.0.0` + GitHub pre-release created. **Maven Central / SPM NOT published** (release.yml only matches strict semver `v[0-9]+.[0-9]+.[0-9]+`).
- ✅ **npm dist-tags**: `{ latest: '3.6.5', beta: '4.0.0-beta.1', next: '4.0.0' }`. `@latest` intentionally NOT bumped to 4.x — protected by `publishConfig: { tag: "next" }` in `mcp/package.json`. `3.6.5` was published by another session (Pro upgrade message fix). `rerun-3d-mcp@1.0.0` on separate `@latest`.
- 🟢 **MCP GATEWAY #1 IS LIVE (Stripe production mode)** — see `.claude/NOTICE-2026-04-11-mcp-gateway-live.md`. 4 products, 4 `price_1TL6...` LIVE ids, webhook `we_1TL7HfEr7tnnFQbdFDu7bmUr`, all 4 plans return `cs_live_...`. **Pending: first real paying customer.** Do NOT bump `@latest` to 4.x until at least one real checkout succeeds.
- ⚠️ **customer_creation bug FIX (commit `88aec77b`)** — do NOT re-introduce `form.customer_creation = "always"` unconditionally in any `stripe-client.ts` (Gateway #1 or #2). Guard with `if (mode === "payment")`.
- **Playground**: 14 examples (13 original + 1 "AR Debug (Rerun)"), 7 platform tabs
- **Demo apps**: 20 Android samples (19 original + RerunDebugDemo), iOS AR Debug demo in SamplesTab
- **llms.txt**: now includes "AR Debug — Rerun.io integration" section (~150 lines, architecture diagram, Android/iOS/Python usage, wire format ref, limits). Bundled in `mcp/src/generated/llms-txt.ts` (82.5 kB). Resource `sceneview://api` serves it.
- **CHANGELOG**: v4.0.0 entry at top of `CHANGELOG.md`
- **Announcements**: 7 draft posts in `~/Projects/profile-private/announcements/` (LinkedIn × 2, Reddit × 4, HN × 1). None published yet — await Thomas's review + manual submit.

### Previous state snapshot (2026-04-11 14:30, session 34 rollup)

- **Active branch**: `main`
- **Latest release**: v3.6.2 — fully published on **GitHub Release + npm + Maven Central** (2026-04-08, #780 closed). iOS TestFlight build 3.6.2 (358) uploaded 2026-04-11 after fixing `MARKETING_VERSION 1.0 → 3.6.2` in project.pbxproj + app-store.yml.
- ✅ **v3.6.4 publiée sur npm (12:33 UTC / 14:33 Paris)** — le gap 3.6.3 est réglé. PR #810 (commit 3f3a595f) a mergé `claude/mcp-files-fix` sur main avec le vrai root-cause fix : `files[]` remplacé par glob `dist/**/*.js` (robust), bump 3.6.3 → **3.6.4** (3.6.3 burned comme broken bookmark), nouveau test `src/package-files.test.ts` qui valide le tarball via `npm pack --dry-run --json`. Publish fait manuellement (`cd mcp && npm publish --access public`) puisque aucun tag `v3.6.4` n'existe ni en local ni sur le remote. Tarball final: 37 fichiers / 747 kB unpacked, ships tous les `dist/tools/*.js`, `dist/telemetry.js`, `dist/auth.js`, `dist/billing.js`, `dist/tiers.js`, `dist/search-models.js`, `dist/analyze-project.js`, `dist/generated/llms-txt.js`. `npm view sceneview-mcp` retourne `latest = 3.6.4`, versions publiées : 3.5.5, 3.6.0, 3.6.1, 3.6.2, 3.6.4. Ships les features cumulées des PR #804 (telemetry), #805 (search_models Sketchfab BYOK), #807 (analyze_project), #808 (sponsor CTA every 10 calls), #810 (files[] glob fix). Les 3 4xx DL/mo continuent en 3.6.4 au prochain `npx` cache bust.
- **Android rewrite**: SceneRenderer, NodeGestureDelegate/AnimationDelegate/State, ARPermissionHandler
- **Demo app**: Material 3 Expressive, 4 tabs, 40 models, 19 sample demos
- **MCP servers**: sceneview-mcp 3.6.2 on npm — **3 450 DL/mo**.
- **sceneview-web**: v3.6.2 on npm (1 221 DL/mo, Kotlin/JS + Filament.js)
- **GitHub orgs**: `sceneview`, `sceneview-tools`, `mcp-tools-lab`
- **Website**: redesigned — 8 sections on index, showcase rewritten, playground enhanced (7 platforms, camera manipulator, Open in Claude), docs 404 fixed
- **Playground**: 13 examples, 7 platforms, 23 models, camera manipulator, Open in Claude + AI dropdown
- **Branding**: 22 PNG exports generated, organized in branding/exports/
- **Open Collective**: logo + cover + tiers (Backer $10, Sponsor $50, Gold $200) + 10 tags — balance $2 338.71, 18 backers
- **Claude Artifacts**: documented in llms.txt with CDN templates + 26 model URLs
- **Filament**: 1.70.2 (1.71.0 bump parked — "New Material Version" impose recompile `.filamat`, session dédiée requise, suivi dans #800)
- ⚠️ **Render Tests STILL @Ignore'd on SwiftShader CI** (issue #803). PR #814 added an Engine-sharing helper but a follow-up commit (`0a6f4bea`) re-applied `@Ignore` to all 4 classes (GeometryRenderTest, VisualVerificationTest, LightingRenderTest, RenderSmokeTest) because `Filament.capturePixels()` still crashes the SwiftShader emulator process. Coverage is provided by iOS simulator, Web Playwright, and Android demo screenshot jobs. Re-enable only when CI moves to a hardware-accelerated GPU runner OR Filament ships a SwiftShader-compatible readback path. Pure-JVM math regressions (e.g. BillboardNode geometry/orientation, see PR #858) can land in `:sceneview:test` without depending on render tests.
- **Nodes reference**: docs/docs/nodes.md (980 lines, AI-first) added 2026-04-11, wired into `llms.txt` for sceneview-mcp consumption — closes #802. Intro/TOC/headings ensuite bumpés en `SceneView{}/ARSceneView{}` par 71c10fea (rename finalization).
- **ViewNode fix**: viewNodeWindowManager now wired to Scene.kt lifecycle (resume/pause/ownerViewRef) — fixes the "black rectangle" regression, closes #801
- ✅ **MCP Gateway (Cloudflare Workers) LIVE**: le Worker est **déployé et opérationnel** sur `https://sceneview-mcp.mcp-tools-lab.workers.dev` — vérifié à 15:25 : `/health` 200 `{"ok":true,"service":"sceneview-mcp-gateway","version":"0.0.1"}`, `/` 200, `/pricing` 200 (landing Free/Pro 19€/Team 49€), `POST /mcp` 401 Unauthorized (auth gate fonctionne). 168 tests passing / 15 files. D1 database `8aaddcda-e36e-4287-9222-1df924426c9f` wiré, KV namespace `9a40d334be6149f7a4ba18451a60245f` wiré, 4 Stripe prices wirés en mode TEST (`price_1TL0...`). Post-pivot Stripe-first cleanup (commit 489da00f) : removed stale `sceneview-mcp.workers.dev` phantom URL, removed `MAGIC_LINK_FROM_EMAIL` var, documented JWT_SECRET + RESEND_API_KEY as no-longer-required. **Seul blocker restant pour revenus Pro** : publier `sceneview-mcp@4.0.0-beta.1` (lite mode) sur npm pour que les 3 450 DL/mo puissent s'abonner. Stripe prices encore en mode TEST → à repointer vers LIVE pour vrai go-live. Note : Sprint 1 (d4e4c167) + Sprint 2 (3b14d9b1) étaient la base auth magic-link qui a été strippée ensuite dans 673ddd88 (refactor Stripe-first). Plus de dashboard user-facing, plus de magic-link — API keys provisionnées via Stripe webhook + KV handoff single-use.
- ✅ **Anonymous Stripe checkout = PIVOT Stripe-first complet sur main** (14:43, 5 commits): `c7d957f3` provisionne l'API key via webhook Stripe + KV handoff, `64f399f2` ajoute `/checkout/success` avec single-use KV handoff, `136410d0` ouvre `/billing/checkout` aux non-authentifiés, **`673ddd88` refactor drastique : STRIP magic-link + REMOVE dashboard/billing user pages**, `67959f25` aligne la suite de tests avec le flux Stripe-first. Pivot UX radical : plus de login wall, plus de dashboard user-facing — l'utilisateur clique CTA landing/pricing, paie dans Stripe, reçoit son API key par email via webhook. Le gateway ne gère plus l'auth lui-même. **Configuration en place** (14:46-14:54): `7157ea84` wire D1/KV provisionnés + Stripe test price ids, `57d81413` set `DASHBOARD_BASE_URL` sur le subdomain workers.dev réel. **Deploy encore bloqué** sur secrets Cloudflare + creds Stripe live + domain Resend si emails sont gardés, mais l'ossature est là et le npm package 4.0.0-beta.1 attend toujours go-live.
- ~~⚠️ **sceneview-mcp v4.0.0-beta.1 (lite mode) — CODE PERDU / À RÉÉCRIRE**~~ **RESOLVED**: proxy.ts was recovered, v4.0.0-beta.1 published on `@beta`, v4.0.0 published on `@next` (includes Rerun integration). The proxy routing + startup banner + LITE/HOSTED mode detection are all on main and working.
- **#808 sponsor CTA every 10 tool calls**: mergé sur main, affiche un prompt de sponsoring (Open Collective + GitHub Sponsors) à chaque 10e appel d'outil MCP.
- **Scene → SceneView rename**: finalisé sur TOUTES les surfaces publiques (library KDocs 4818d0a8/d3dd0d5b, mkdocs nav, SEO data, MCP packages d6a31759, runtime bridges/templates/top-level MCP 025915e9, nodes.md intro/TOC/headings 71c10fea, READMEs react-native + flutter, SceneViewSwift mapping tables, ROADMAP).
- **Demo apps (session 34)**: audit frais de toutes les 7 demos apps (l'ancien audit session 19 était périmé). `.claude/scripts/validate-demo-assets.sh` créé (4a1bb02a) — scan tous les refs GLB/USDZ/HDR, expand `$CDN/...`, follow redirects, supporte patterns iOS `asset:` et `ModelNode.load()`. Premier run a trouvé 8 refs cassées (android-tv-demo + web-demo) — toutes corrigées. web-demo unblocked (webpack 5 + filament.js polyfills). flutter/sceneview_flutter unblocked (Kotlin 2.0 + compose compiler plugin). RN demo scaffolded android/ + ios/ natifs (68cf829c).
- **Org allowlist** : `sceneview`, `sceneview-tools`, `mcp-tools-lab`.

For full session history, see memory file `project_session_history.md`.
For current priorities and next steps, see `.claude/handoff.md`.

### How to update

After significant work, update this block and `.claude/handoff.md`.

---

## Long-running session rules

Based on [Anthropic harness design for long-running apps](https://www.anthropic.com/engineering/harness-design-long-running-apps).

### Context management
- **Read `.claude/handoff.md` at session start** — structured handoff artifact
- **Update `.claude/handoff.md` at session end** — what was done, decisions, next steps
- **Context resets > compaction** — when context gets long, start a fresh session with handoff
- **Don't prematurely wrap up** — if approaching context limits, hand off cleanly instead

### Separate generator from evaluator
- **Never self-evaluate** — run `/evaluate` or `/review` as a separate step
- Evaluators should be skeptical; generators should be creative
- If any evaluation criterion scores 1-2/5, it's BLOCKING — fix before pushing

### Sprint contracts
- Before starting a feature chunk, define **what "done" looks like**
- Use the sprint contract template in `.claude/handoff.md`
- Prevents scope creep and ensures alignment

### Decomposition
- **One feature at a time** — break complex work into discrete chunks
- Each chunk should compile, test, and be commitable independently
- Don't attempt end-to-end execution of large features in one go

### Criteria-driven quality
- Use measurable criteria (compile? tests pass? review checklist?)
- Weight criteria: Safety (3x) > Correctness (3x) > API consistency (2x) > Completeness (2x) > Minimality (1x)
- Explicit > vague — "tests pass" beats "looks good"

### Complexity hygiene
- Every harness component encodes an assumption about model limitations
- Regularly stress-test: does this hook/check still add value?
- Remove scaffolding that newer model capabilities make unnecessary

### Available evaluator commands
| Command | Role |
|---|---|
| `/review` | Code review checklist (threading, Compose API, style) |
| `/evaluate` | Independent quality assessment (5 criteria, weighted scores) |
| `/test` | Test coverage audit |
| `/sync-check` | Repo synchronization verification |
| `/contribute` | Full contribution workflow |
| `/version-bump` | Coordinated version update across all platforms |
| `/publish-check` | Verify all published artifacts are up to date |
| `/release` | Full release lifecycle (bump, changelog, tag, publish) |
| `/maintain` | Daily maintenance sweep (CI, issues, deps, quality) |

---

## Automation ecosystem

### Hooks (settings.json)

Hooks trigger automatically on specific Claude Code actions:

| Trigger | When | Action |
|---|---|---|
| Pre-commit version check | `git commit` | Blocks if VERSION_NAME mismatches across modules |
| Post-edit gradle.properties | Any gradle.properties edit | Reminds to update ALL version locations |
| Post-edit Android API | Edit in `sceneview/src/` | Reminds to check SceneViewSwift + llms.txt |
| Post-edit Swift API | Edit in `SceneViewSwift/Sources/` | Reminds to check Android + llms.txt |
| Post-push reminder | `git push` | Reminds to update CLAUDE.md and website |

### Scripts (.claude/scripts/)

| Script | Purpose |
|---|---|
| `sync-versions.sh` | Scan ALL version declarations, report/fix mismatches |
| `cross-platform-check.sh` | Compare Android vs iOS vs Web API surface, report gaps |
| `release-checklist.sh` | Pre-release validation (versions, changelog, tests, etc.) |
| `lib/android-cli.sh` | Shared helpers for Google's `android` CLI (screenshot, layout, install+launch) with `adb` fallback |
| `setup-ar-emulator.sh` | Bootstrap a reusable ARCore-ready `Pixel_7a` emulator (virtualscene camera, 4 GB RAM, host GPU, ARCore APK). Idempotent — `--check` (read-only, reports pool state), `--clean` (wipe+recreate). RAM-budgeted adaptive emulator pool (#1647 → #1654): leases a free running emulator, or boots a new one on a distinct `-port` when the live RAM-budgeted cap has room and free RAM clears the hard safety gate, or waits for a lease to free. **Use this for routine QA — never QA on a personal device.** |
| `lib/emulator-select.sh` | Sourced helper for `setup-ar-emulator.sh` / `device-qa.sh` / `qa-android-demos.sh` — RAM monitoring (`vm_stat`/`/proc/meminfo`), RAM-budgeted pool-cap computation, a per-emulator lease registry, RAM-scaled `-memory`, multi-port boot, and stale-lease reclaim. The adaptive pool runs as many emulators as live host RAM safely allows (floor 1, `EMU_POOL_MAX` ceiling), superseding #1647's strict-single design (#1654). |
| `qa-android-demos.sh` | QA loop over every demo — uses `android layout`/`screen capture` for the UI dump and screenshots |
| `capture-play-store-screenshots.sh` | Play Store screenshot capture — `android screen capture` (no LF/CRLF corruption) |
| `visual-check.sh` | Before/after baseline capture — Android via `android` CLI, iOS via `xcrun simctl` |
| `android-env-check.sh` | Sanity check for the Android dev env — `--fix` installs the CLI + SceneView skill |
| `install-sceneview-skill.sh` | Copies `agents/sceneview/` (Android skill) to `~/.android/cli/skills/xr/sceneview/` |
| `install-sceneview-ios-skill.sh` | Copies `agents/sceneview-ios/` (Apple skill) to `~/.android/cli/skills/xr/sceneview-ios/` |
| `install-sceneview-web-skill.sh` | Copies `agents/sceneview-web/` (Web skill) to `~/.android/cli/skills/xr/sceneview-web/` |
| `check-sceneview-skill.sh` | Verifies all three `agents/sceneview*/` skills (API identifiers, demo refs, frontmatter) are in sync with the library source. Runs in `quality-gate.sh`, `pr-check.yml`, and daily via `maintenance.yml` |
| `worktree-auto-prune.sh` | Safe GC for `.claude/worktrees/*` — removes worktrees whose branch is merged (`--dry-run`, `--yes`, `--keep <path>`). Never touches dirty or unmerged trees |
| `cleanup-branches-worktrees.sh` | One-shot GC for stale `claude/*` branches **and** worktrees: deletes merged local + remote branches (single `git push --delete`, no bot-burst) and delegates worktree pruning to `worktree-auto-prune.sh`. Defaults to dry-run; `--yes` to act, `--keep <branch\|path>`, `--no-worktrees`. Current-branch / unmerged / open-PR guarded. Runs daily in `maintenance.yml` |

### Version location map

Source of truth: `gradle.properties` → `VERSION_NAME=X.Y.Z`

| File | Field |
|---|---|
| `gradle.properties` (root) | `VERSION_NAME=` |
| `sceneview/gradle.properties` | `VERSION_NAME=` |
| `arsceneview/gradle.properties` | `VERSION_NAME=` |
| `sceneview-core/gradle.properties` | `VERSION_NAME=` |
| `llms.txt` | Artifact version references |
| `README.md` | Install snippets |
| `CLAUDE.md` | "Latest release" in session state |

> ⚠️ `mcp/package.json` / `mcp/src/index.ts` are **not** in this map —
> `sceneview-mcp` has its own independent npm version track. `sync-versions.sh`
> excludes it on purpose. See the Version Location Map note above (issue #1705).

### Published artifact registry

| Artifact | Platform | How to check |
|---|---|---|
| sceneview | Maven Central | Maven search API |
| arsceneview | Maven Central | Maven search API |
| sceneview-mcp | npm | `npm view sceneview-mcp version` |
| sceneview-web | npm | `npm view sceneview-web version` |
| SceneViewSwift | SPM (git tags) | `git tag -l 'v*'` |
| GitHub Release | GitHub | `gh release list` |
| Website | GitHub Pages | sceneview.github.io |

### Quality gates (must pass before any push to main)

1. All versions aligned (run `sync-versions.sh`)
2. No lint errors in library modules
3. Unit tests pass (`./gradlew :sceneview-core:allTests`)
4. MCP tests pass (`cd mcp && npm test`)
5. llms.txt matches current public API
6. CLAUDE.md session state is current
7. No model-viewer or Three.js in website code
8. No external CDN dependencies in website

---

## Cross-platform strategy

### Architecture: native renderer per platform

```
┌─────────────────────────────────────────────┐
│              sceneview-core (KMP)            │
│     math, collision, geometry, animations    │
│         commonMain → XCFramework             │
└──────────┬──────────────────┬───────────────┘
           │                  │
    ┌──────▼──────┐   ┌──────▼──────┐
    │  sceneview  │   │SceneViewSwift│
    │  (Android)  │   │   (Apple)    │
    │  Filament   │   │  RealityKit  │
    └──────┬──────┘   └──────┬──────┘
           │                  │
     Compose UI        SwiftUI (native)
                       Flutter (PlatformView)
                       React Native (Fabric)
                       KMP Compose (UIKitView)
```

**Key decision:** KMP shares **logic** (math, collision, geometry, animations), not **rendering**.
Each platform uses its native renderer: Filament on Android, RealityKit on Apple.

Rationale:
- RealityKit is the only path to visionOS spatial computing
- Swift Package integration (1 line SPM) vs KMP XCFramework (opaque binary, poor DX)
- SceneViewSwift is consumable by any iOS framework (Flutter, React Native, KMP Compose)
- No Filament dependency on Apple = smaller binary, native debugging, native tooling

### Supported platforms

| Platform | Renderer | Framework | Status |
|---|---|---|---|
| Android | Filament | Jetpack Compose | Stable (v3.3.0) |
| Android TV | Filament | Compose TV | Alpha (sample app) |
| Android XR | Jetpack XR SceneCore | Compose XR | Planned |
| iOS | RealityKit | SwiftUI | Alpha (v3.3.0) |
| macOS | RealityKit | SwiftUI | Alpha (v3.3.0, in Package.swift) |
| visionOS | RealityKit | SwiftUI | Alpha (v3.3.0, in Package.swift) |
| Web | Filament.js (WASM) | Kotlin/JS | Alpha (sceneview-web + WebXR) |
| Desktop | Wireframe placeholder (not SceneView) | Compose Desktop | Placeholder (Filament JNI not available) |
| Flutter | Filament / RealityKit | PlatformView | Alpha (bridge implemented) |
| React Native | Filament / RealityKit | Fabric | Alpha (bridge implemented) |

### KMP core role

`sceneview-core/` targets `android`, `iosArm64`, `iosSimulatorArm64`, `iosX64` with shared:
- Collision system (Ray, Box, Sphere, Intersections)
- Triangulation (Earcut, Delaunator)
- Geometry generation (Cube, Sphere, Cylinder, Plane, Path, Line, Shape)
- Animation (Spring, Property, Interpolation, SmoothTransform)
- Physics simulation
- Scene graph, math utilities, logging

SceneViewSwift can consume this as an XCFramework for shared algorithms,
while keeping RealityKit as the rendering backend.

### Cross-framework iOS consumption

| Framework | Integration method |
|---|---|
| Swift native | `import SceneViewSwift` via SPM |
| Flutter | Plugin with `PlatformView` wrapping `SceneView`/`ARSceneView` |
| React Native | Turbo Module / Fabric component bridging to SceneViewSwift |
| KMP Compose | `UIKitView` in Compose iOS wrapping the underlying UIView |

### Phased plan (revised)

| Phase | Scope | Complexity |
|---|---|---|
| 1 — SceneViewSwift stabilization | Complete 3D+AR API, add macOS target, tests, docs | Medium |
| 2 — KMP core consumption | Build XCFramework from sceneview-core, integrate into SceneViewSwift | Medium |
| 3 — Cross-framework bridges | Flutter plugin, React Native module | Medium |
| 4 — visionOS spatial | Immersive spaces, hand tracking, spatial anchors | High |
| 5 — Docs & website | Update all docs/README/site for multi-platform (iOS, macOS, visionOS) | Low |
