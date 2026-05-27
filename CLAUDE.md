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
| `android` | Maestro flows `.maestro/android/` via `qa-android-demos.sh` | All 59 demos on an emulator | `device-qa-report.json` |
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

#### Android Vitals release-gate (#1691)

Device-QA validates the demo app on **emulators** *before* release. Android
Vitals is the complementary post-release signal: the **real crash & ANR rate**
across live Play Store users.

- `release-checklist.sh` **section 15** runs `.claude/scripts/play-vitals.sh`,
  which queries the **Play Developer Reporting API** for
  `io.github.sceneview.demo` and grades the 28-day user-perceived crash & ANR
  rates against Google Play's bad-behaviour thresholds (crash 1.09%, ANR 0.47%).
- **Advisory-first**: the gate is `WARN`-only by default and never freezes a
  release. A missing `PLAY_STORE_SERVICE_ACCOUNT_JSON` secret, the 403 you get
  before the read-only **"View app quality information"** Play Console
  permission is granted, or a fresh app with no data all degrade to `WARN`.
  Set `PLAY_VITALS_HARD=1` to promote a hard-threshold breach to a release
  blocker once the numbers are trusted.
- Reuses the existing deploy service account — **no new write scope**.

#### Play Store reviews → triage issues (#1692)

`maintenance.yml`'s `play-reviews` job runs daily, ingesting Play Store
ratings + written reviews via the Android Publisher `reviews.list` API (same
deploy service account, read-only). Reviews matching a crash/bug signal —
or 1-star reviews with a real comment — auto-open a **de-duplicated** triage
issue (keyed by the stable Play `reviewId`) with the review text, device, and
app version. **Documented API gaps:** `reviews.list` only returns reviews from
≈the last week, and install counts are not exposed by any queryable Play API
(only bulk CSV reports) — both are surfaced honestly rather than faked.

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

- Use `SceneView { }` for 3D-only scenes (`io.github.sceneview:sceneview:4.16.8`)
- Use `ARSceneView { }` for augmented reality (`io.github.sceneview:arsceneview:4.16.8`)
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

**Golden boot snapshot — faster, deterministic QA (#1672).** The QA AVD's
userdata partition fills up after ~6 runs and Filament viewports turn black.
Seed a clean post-install boot snapshot once; every subsequent run cold-boots
from it with `-no-snapshot-save` (loads the warm state, never writes back), so
runs start identical and the partition never degrades:

```bash
bash .claude/scripts/setup-ar-emulator.sh --clean --seed-snapshot   # seed once
bash .claude/scripts/setup-ar-emulator.sh                           # restores 'qa-clean'
bash .claude/scripts/setup-ar-emulator.sh --no-snapshot             # force cold boot
```

Only the base-port emulator restores the snapshot (`-snapshot` is incompatible
with the `-read-only` pool peers); `--clean` drops the snapshot; CI is
unaffected (the GitHub emulator action has its own snapshot caching). See
[`.maestro/README.md`](.maestro/README.md) for the full rationale and the
Android Studio Journeys assessment (not adopted — blocked on an AGP 9.0.0 bump).

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

## Self-hosted macOS runner (opt-in)

GitHub-hosted `macos-15` runners cost ~10x ubuntu per-minute and have no KVM.
SceneView ships **6 jobs on `macos-15`** (`ios.yml`, `bridge-ios-compile.yml`,
`rn-ios-compile.yml`, `app-store.yml` × 2, `render-tests.yml`) plus a
NIGHTLY-ONLY iOS device-QA leg (#1601) deliberately skipped on per-push runs
because of the macOS cost. A self-hosted runner on a Mac removes that
multiplier and unblocks per-push iOS device-QA.

Inspired by [Zach Rattner's M4 Mac cluster
playbook](https://zachrattner.com/projects/m4-mac-cluster) (8 Mac minis, $35k/yr
saved vs GCP, 4-min builds → 40s) — SceneView's scale doesn't justify a
cluster, but a single self-hosted Mac with **transparent fallback** is a
strict win.

### Install

```bash
brew install gh
gh auth login --scopes "repo,workflow"   # PAT needs Variables:write
bash .claude/scripts/setup-self-hosted-runner.sh
bash .claude/scripts/setup-self-hosted-runner.sh --check
```

The installer (a) downloads `actions/runner` for `osx-arm64`/`osx-x64`
into `~/sceneview-runner/` (deliberately *outside* `~/Library/Application
Support/` — that path contains a space, and the runner's generated step
scripts get invoked as `/bin/bash -e <path>` which splits on space and
fails with `No such file or directory`; v2 hit exactly this on the
pilot bridge-ios-compile run id 26418464635), (b) registers it with
label `sceneview-mac`, (c) writes a user LaunchAgent plist directly
and `launchctl bootstrap`s it (the v1 attempt to delegate to
`actions/runner`'s `svc.sh` was a dead end — `svc.sh` shells out to
the deprecated `launchctl load` which fails with `Input/output error`
on macOS 11+, see actions/runner issue 1424).
The plist uses `KeepAlive=true` so the runner survives reboots, login,
sleep/wake, and the runner's own auto-update cycle (the runner exits
to install a new version, launchd restarts it, new version takes over —
verified working). (d) installs a second launchd heartbeat plist that
fires `runner-heartbeat.sh` every 300s. The heartbeat pings
`/repos/.../actions/runners` to confirm the runner is *actually*
online, then updates two repo variables: `SELF_HOSTED_MACOS_ONLINE`
(`"true"`/`"false"`) and `SELF_HOSTED_MACOS_LAST_SEEN` (ISO 8601 UTC).

### Opt a workflow in — single line

Workflows route to self-hosted only when the heartbeat is fresh, and fall
back to `macos-15` automatically when the Mac is asleep, off, or the runner
process is dead (heartbeat sets `ONLINE=false` if `runner.status != "online"`):

```yaml
jobs:
  build:
    # Was:  runs-on: macos-15
    runs-on: ${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}
    steps:
      - ...
```

That's the whole change per workflow. **No composite action, no reusable
workflow, no pre-job** — `runs-on` accepts expressions since GitHub Actions
late-2024. Thomas opts workflows in one at a time as confidence grows; no
existing workflow is modified by this commit.

### Safety net

- Heartbeat refuses to mark `ONLINE=true` when the runner service status is
  anything other than `"online"` (dead service / failed boot / runner mid
  auto-update → workflows route to `macos-15`).
- `KeepAlive=true` on the runner LaunchAgent means a crashed runner is
  re-launched within `ThrottleInterval` (30s). The runner's auto-update
  cycle (exit → launchd restart → new version) is invisible to consumers.
- On `--uninstall`, both LaunchAgents are `launchctl bootout`-ed, any
  rogue `run.sh` is `pkill`-ed, and `ONLINE` is forced to `false` so no
  stale routing survives.
- `--check` prints `.runner` config, both LaunchAgent loaded states with
  `state =` + `last exit code` excerpts, recent heartbeat log, GitHub-side
  runner status (live API call), and both repo variables — single source
  of truth.
- Heartbeat interval (300s) is well under any reasonable workflow queue
  timeout. After `pmset sleepnow` the runner is paused; on wake launchd
  re-validates `KeepAlive` and the runner re-connects to GitHub within a
  few seconds; the heartbeat picks up the offline → online transition at
  the next tick.

## @claude mention bot (GitHub Action)

[`.github/workflows/claude.yml`](.github/workflows/claude.yml) runs the official
[`anthropics/claude-code-action@v1`](https://github.com/anthropics/claude-code-action)
whenever a contributor drops **`@claude`** in any of:

- a new issue body or title
- an issue comment
- a PR review or PR review comment

Claude reads the repo (full git history), the issue/PR context, and replies in
place — proposing a fix, opening a PR, or answering questions. Open-source
contributors benefit too; they don't need an Anthropic account.

**Auth — OAuth via Claude Max** (no per-call API spend). Generate the token
once on a logged-in machine and push it to the repo secret:

```bash
claude setup-token                                                    # outputs an OAuth token
gh secret set CLAUDE_CODE_OAUTH_TOKEN -R sceneview/sceneview -b "<token>"
```

The token is long-lived; rotate via the same flow if revoked. Cost is on
Thomas's Max quota; gate every fire with an explicit `@claude` mention so
Dependabot etc. never trigger it. Concurrency is keyed per issue/PR — a second
mention cancels a still-running earlier reply.

## Samples

One unified showcase app per platform — all features integrated into tabs.

| Directory | Platform | Demonstrates |
|---|---|---|
| `samples/android-demo` | Android | Play Store app — 4-tab Material 3 (Explore, AR View, Samples, About), 59 demos (30 non-AR + 29 AR) |
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
| | `samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj` | `MARKETING_VERSION = X.Y.Z` (iOS + macOS App Store marketing version — the `SceneViewDemo` app target, both Debug & Release configs; the test target's `1.0` is a placeholder, leave it) |
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

**The source-of-truth version is always `VERSION_NAME` in the root `gradle.properties`** — read that file, never hardcode a version here. Any AI bootstrapping from this file should treat the `gradle.properties` `VERSION_NAME` as the latest published version across all surfaces (Maven Central, npm `sceneview-web`/`@sceneview-sdk/react-native`, SPM tag `vX.Y.Z`, web CDN). At the time of writing this is `4.15.1`, but `gradle.properties` is authoritative if they ever disagree. The dated session logs below are historical context only — do not infer the latest version from them.

### Current state (last updated: 2026-05-27, session pedantic-robinson — maintenance sweep + v4.16.8 SHIPPED + shadow fix)

- 🚀 **v4.16.8 RELEASED** — tag `v4.16.8` at HEAD `aba3e83e0`. release.yml ✅, play-store.yml ✅, docs.yml ✅. app-store.yml iOS TestFlight ✅, macOS ❌ (missing `MACOS_INSTALLER_CERT_BASE64` — Thomas action, #2252).
- ✅ **Library 16KB page-size alignment SHIPPED** (#2226, #2255) — `experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"` added to `sceneview/` and `arsceneview/` library modules. Consumers' APKs now pass Google Play's Jan 2026 enforcement for Android 15+.
- ✅ **macOS App Store compile errors fixed** (#1794, v4.16.6) — 15 iOS demo files guard iOS-only APIs. Export blocked on missing `MACOS_INSTALLER_CERT_BASE64` secret (issue #2252 — Thomas action).
- ✅ **iOS TestFlight deploying** from v4.16.5 onwards ✅.
- ✅ **[#2235] Sketchfab viewer ground shadow SHIPPED** (#2256, merged 2026-05-27) — directional light + `plane_renderer_shadow.filamat` plane at computed ground Y. Models no longer float on a blank background.
- ✅ **Maintenance sweep 2026-05-27 COMPLETE**: MCP tests 1864/1864 ✅, AI skills ✅, Filament 1.71.5 not on Maven Central yet, sceneview.github.io at 4.16.8. Version sync: 0 errors, 2 intentional WARNs (consumed Flutter/RN deps).
- ⚠️ **macOS installer cert** — #2252: Thomas must add `MACOS_INSTALLER_CERT_BASE64` + `MACOS_INSTALLER_CERT_PASSWORD` GitHub secrets from Apple Developer Portal.
- 📋 **20 open issues** (mostly enhancement + needs-device + v5 backlog).

### Followups for next session

1. **[#2252](https://github.com/sceneview/sceneview/issues/2252) macOS installer cert** — Thomas action: add `MACOS_INSTALLER_CERT_BASE64` + `MACOS_INSTALLER_CERT_PASSWORD` GitHub secrets from Apple Developer Portal.
2. **[#2188](https://github.com/sceneview/sceneview/issues/2188) Re-add FGS** — re-add `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` once Play Console FGS declarations visible.
3. **Filament 1.71.5** — bump once it appears on Maven Central (checked 2026-05-27: not yet, GitHub tag exists).
4. **[#1831](https://github.com/sceneview/sceneview/issues/1831)** — stale `appStoreVersionSubmission` auto-delete before POST in `app-store.yml`.
5. **[#2241](https://github.com/sceneview/sceneview/issues/2241) Sprint 1 V2 plane renderer redo** — PlaneDiscoveryGuide + ShadowReceiverPlane + PlacementReticle + ARPlacementDemo modernization.
6. **[#2239](https://github.com/sceneview/sceneview/issues/2239) Samples catalog regrouping** — 60 cards → ~25 cards aggressive grouping.
7. **[#894 iOS AR feature parity](https://github.com/sceneview/sceneview/issues/894)** — Cloud Anchors, ARRecorder via ReplayKit, Streetscape. Needs physical device.

### Older session logs

Historical session snapshots (past version sprints, ARCore feature coverage, plugin marketplace work, store-deploy sweeps, etc.) live in `.claude/handoff.md` (gitignored, chronological log) and in `git log` / PR descriptions for permanent "why did we do X" context. CLAUDE.md keeps only the **current** state — read `handoff.md` at session start for everything older.

For full multi-session history with grep context, see the user-memory file `project_session_history.md`.

### How to update

After significant work, update **only** the "Current state" + "Followups for next session" blocks above. Append the old "Current state" entry to `.claude/handoff.md` rather than letting it accumulate here — this section was trimmed from 992 → 741 lines on 2026-05-25 after exactly that accumulation problem (~280 lines of nested "Previous state" entries).

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
| `setup-ar-emulator.sh` | Bootstrap a reusable ARCore-ready `Pixel_7a` emulator (virtualscene camera, 4 GB RAM, host GPU, ARCore APK). Idempotent — `--check` (read-only, reports pool + snapshot state), `--clean` (wipe+recreate), `--seed-snapshot` (seed the golden `qa-clean` boot snapshot), `--no-snapshot` (force cold boot). RAM-budgeted adaptive emulator pool (#1647 → #1654): leases a free running emulator, or boots a new one on a distinct `-port` when the live RAM-budgeted cap has room and free RAM clears the hard safety gate, or waits for a lease to free. Boot snapshots (#1672): once seeded, the base-port emulator cold-boots from the immutable `qa-clean` snapshot — faster and deterministic, and fixes the userdata storage-degradation bug. **Use this for routine QA — never QA on a personal device.** |
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
