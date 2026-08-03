# Contributing to SceneView

Thanks for your interest in contributing! This guide covers everything you need to get started.

---

## Development environment setup

### Prerequisites

- **JDK 17** (for Android/KMP modules)
- **Android Studio** (latest stable recommended)
- **Xcode 15+** (for SceneViewSwift / iOS work only)
- Optional but recommended: Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli)
  for agent-driven QA. Bootstrap in one shot:
  ```bash
  bash .claude/scripts/android-env-check.sh --fix
  ```
  This installs the binary to `~/.local/bin/android` and registers the SceneView
  agent skill under `~/.android/cli/skills/xr/sceneview/`, so `android skills list`
  exposes it to any AI agent on this host.

### Clone and open

```bash
git clone https://github.com/sceneview/sceneview.git
cd sceneview
```

Open the project in Android Studio. Gradle sync will pull all dependencies automatically.

### Build

```bash
# Android libraries
./gradlew assembleDebug

# Android demo app
./gradlew :samples:android-demo:assembleDebug
```

For iOS (SceneViewSwift), open `SceneViewSwift/Package.swift` in Xcode and build from there.

### Store secrets and the release-build guard

The demo apps read two optional secrets — `SKETCHFAB_API_KEY` (Sketchfab
carousels in the Explore tab) and `ARCORE_API_KEY` (AR Streetscape / Geospatial
/ Cloud Anchor demos). They're resolved from an environment variable or, on
developer machines, from the repo-root `local.properties`
(`sketchfab.api.key` and `ARCORE_API_KEY` respectively).

**Debug builds are permissive** — a missing key just disables those features at
runtime, so you can build and contribute without either secret.

**Release builds fail loud (#1915).** A release `assembleRelease` /
`bundleRelease` (Android) or `Release` archive (iOS) *refuses to build* when a
secret is empty or unsubstituted, instead of silently shipping a store APK with
invisible Sketchfab carousels (the regression class behind #1909). This guard
only fires on the release path — it never affects `assembleDebug` or PR-check
builds.

Forks have no access to the org GitHub Secrets, so to produce a release build
without the keys, opt out of the guard:

```bash
# Android
./gradlew :samples:android-demo:bundleRelease -PSV_ALLOW_MISSING_SECRETS=1
# or export SV_ALLOW_MISSING_SECRETS=1

# iOS
xcodebuild archive ... SV_ALLOW_MISSING_SECRETS=1
```

### Run tests

```bash
# All tests
./gradlew test

# KMP core tests only
./gradlew :sceneview-core:allTests
```

### Reading a leak-churn failure

`LeakChurnTest` (`sceneview/src/androidTest/.../leak/`, issue #2762) builds and
tears down node trees 40 times per test and asserts engine state returns to
baseline. It runs headless — no SwapChain, no `readPixels` — so unlike the
`render` package it really executes on the CI emulator instead of skipping.
It lands in `render-tests.yml`'s `continue-on-error` job, so a red run is an
advisory signal in the Actions tab, not a merge block.

```bash
ANDROID_SERIAL=<your-emulator> ./gradlew :sceneview:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.sceneview.leak.LeakChurnTest
```

What a red run means, by probe:

| Failing assert | What leaked | Where to look |
|---|---|---|
| `LightManager component count did not return to baseline` | one light component per cycle | a `destroy()` override that returns early or never reaches `super.destroy()` (`Node.kt`'s `isDestroyed` guard, or a subclass that forgets the super call). Note `Engine.destroyEntity()` already drops *every* Filament-known component, so `LightNode.destroy()`'s explicit `lightManager.destroy(entity)` is defensive, not the load-bearing release |
| `still has a TransformManager component` | the node's transform was never released | `Node.destroy()` path — a `return` before `safeDestroyTransformable`, or an exception swallowed by `runCatching` |
| `still has a RenderableManager component` | geometry/renderable never released | the renderable owner's `destroy()`; check an early-return added to a subclass |
| `the queue still holds resources after N drained frames` | textures/streams enqueued but never released (#1630 shape) | `DeferredDestroyQueue.drain()` frame accounting, or a `GRACE_FRAMES` change that outlives the drain loop |
| `enqueued textures must be held for the grace period` | `DeferredDestroyQueue.enqueue` stopped deferring — the #874 `SIGABRT` shape | the deferral branch in `EngineDestroyQueue.kt`. **Uncovered gap:** this probe enqueues directly, so it cannot catch a *caller* (`ImageNode`, `ViewNode`, `SplatNode`) switching to an eager `engine.destroyTexture` — those need an asset/bitmap, which would make the probe non-deterministic |
| `canary_injectedLeakIsDetected` fails | **the instrument is broken**, not your code | a Filament upgrade changed `hasComponent`/`getComponentCount` semantics — fix the probe before trusting any other result in the class |
| `entityIdRecycling_isTheKnownPreExistingGap` fails | someone **fixed** entity-id recycling (#2859) | invert that test and tighten `assertComponentsReleased` to also require `EntityManager.isAlive(entity) == false` |

To bisect: the failure message names the exact entity id and the cycle index
that created it, so re-run with a smaller `CYCLES` and log around that cycle.
Never widen an assertion to silence a red you cannot explain.

### Set up an emulator

Google's `android` CLI creates and boots emulators with one command — no
sdkmanager / avdmanager dance:

```bash
android emulator create medium_phone        # positional <profile>; device auto-named from it
android emulator start medium_phone         # boots, waits for ready
```

`android emulator create` takes a single positional argument — the **profile**
— and the resulting device is auto-named from the profile (so `medium_phone`
above creates a device called `medium_phone`). v0.7 does not support a
separate `--name` flag. List profiles with `android emulator create --list-profiles`,
list existing devices with `android emulator list`, remove with
`android emulator remove <name>`.

The `medium_phone` profile matches the Pixel-7-class form factor most of the
screenshot scripts assume.

For SDK packages, prefer `android sdk install` / `android sdk list` over the
legacy `sdkmanager` from `cmdline-tools`.

---

## AI-assisted workflow (recommended)

SceneView ships with a full Claude Code setup so you can contribute with AI assistance
from the first keystroke — no context-gathering needed.

### Quick start

1. Install [Claude Code](https://claude.ai/code)
2. Clone the repo and open it: `claude` inside the project root
3. Run `/contribute` — Claude walks you through the entire workflow

See [CLAUDE.md](CLAUDE.md) for the full module map, architecture overview, threading rules, and AI contributor guidelines.

### Available slash commands

| Command | What it does |
|---|---|
| `/contribute` | Full guided workflow from understanding to PR |
| `/review` | Checks threading rules, Compose API, Kotlin style, module boundaries |
| `/document` | Generates/updates KDoc and `llms.txt` for changed APIs |
| `/review --coverage` | Audits coverage and generates missing tests |

### MCP server (optional)

If you use Claude Desktop or another MCP-compatible editor, add the SceneView MCP server
for full API context in any chat:

```json
{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

---

## Pull request guidelines

1. **One feature per PR.** Keep changes focused and reviewable.
2. **Tests required.** Add or update tests for any behavior change.
3. **Follow existing code style.** Match the patterns in the module you are editing.
4. **Describe the why.** PR descriptions should explain the motivation, not just list changed files.
5. **Keep commits clean.** Squash fixups before requesting review.

Contributions to any part of the project are welcome — Android (`sceneview/`, `arsceneview/`), iOS (`SceneViewSwift/`), shared KMP core (`sceneview-core/`), samples, documentation, or the MCP server.

### Adding a demo to `samples/android-demo`

The Android demo app uses an **append-only fragment registry** so that two
parallel PRs adding two different demos never conflict on a shared file
(issue #1797). To add a demo:

1. Add the demo composable under
   `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/`.
2. Drop a new fragment file at
   `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/<MyDemo>Fragment.kt`
   declaring `object <MyDemo>Fragment : DemoFragment` with the demo's id,
   title/subtitle string resources, category, icon, and a one-line `Screen`
   wrapper calling your composable. See [the package
   README](samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/README.md)
   for the full template.
3. Drop the demo's strings into their own resource fragment at
   `samples/android-demo/src/main/res/values/strings_demo_<my_demo>.xml`
   (`-` → `_` in the id). Each demo owns its strings file so parallel PRs
   never share a string-resource anchor (#1870). Android's resource merger
   fans every `res/values/*.xml` file in at build time, so `R.string.demo_*`
   references resolve identically.
4. Run the collator to regenerate `GeneratedDemos.kt`:
   `bash samples/android-demo/scripts/collate-demos.sh`. The quality gate runs
   the collator in `--check` mode and blocks the push if the file is stale.

You should **never** edit `DemoRegistry.kt`, `MainActivity.kt`, or
`GeneratedDemos.kt` by hand — the fragments are the single source of truth.

### Device-QA flows when adding a demo

The demo apps are exercised on real emulators/simulators by the **autonomous
device-QA harness** (`bash .claude/scripts/device-qa.sh`). When you **add or
change a demo**, update its device-QA coverage in the same PR — an untested
demo is invisible to the release-checkpoint gate:

- **Android** — add the demo to its category flow under `.maestro/android/`
  (`3d-basics.yaml`, `lighting.yaml`, `content.yaml`, `interaction.yaml`,
  `advanced.yaml`, or `ar.yaml`) and to the master `catalog.yaml`. Each entry
  reuses `flows/demo.yaml` with the demo's `DEMO_ID` / `DEMO_NAME`.
- **iOS** — add the matching entry under `.maestro/ios/` (same category files
  + `catalog.yaml`). A deep-linkable demo must also be registered in
  `DemoDeepLinkRegistry.allowedIds` so the `sceneview://demo/<id>` ingress can
  reach it; otherwise add a `placeholders.yaml` entry.
- **Web** — extend the Playwright `catalog.spec.ts` coverage in
  `samples/web-demo/tests/` so the new demo/tab is walked and asserted.

See [`.maestro/README.md`](.maestro/README.md) for the flow layout and the
known no-pinch / deep-link-zoom limitation. Run `device-qa.sh` for the affected
platform before requesting review.

### Changelog entries

**Do not edit `CHANGELOG.md` directly.** Changelog entries go in `changelog.d/`
as a small fragment file — one per PR. Add a file named
`changelog.d/<issue-or-pr-number>-<short-slug>.md` containing your release-note
bullet(s), prefixed with a category tag:

```markdown
<!-- category: Fixed -->
- **Short headline ([#1234](https://github.com/sceneview/sceneview/issues/1234)).** What changed and why.
```

Recognised categories: `Added`, `Changed`, `Fixed`, `Removed`, `Tests`, `Docs`.
Distinct filenames mean parallel PRs never conflict on the changelog. At release
time `.claude/scripts/collate-changelog.sh` collates all fragments into a new
`## vX.Y.Z` section. See [`changelog.d/README.md`](changelog.d/README.md) for
the full convention.

After your changes are merged, the Discord bot will award you the **Contributor** role.

### Public API changes

SceneView is an **AI-first SDK**: `llms.txt` and the KDoc promise *exact* public
signatures so an AI can generate correct code on the first try. To stop a public
signature from changing silently, the published library modules — `sceneview`,
`arsceneview` and `sceneview-core` — have their public ABI tracked by Kotlin's
[`binary-compatibility-validator`](https://github.com/Kotlin/binary-compatibility-validator).
Each module's public surface is dumped to a committed text file under
`<module>/api/<module>.api`, and CI runs a **blocking** `apiCheck` (the
`API compatibility` job in `ci.yml`, same tier as the unit tests).

**If your change touches a public signature** (adds/removes/renames a public
class, function, property, or changes a public type), `apiCheck` will fail with
a diff naming the offending symbol, e.g.:

```
> API check failed for project sceneview-core.
  +	public static final fun temporaryGateProbe (J)D
  You can run :sceneview-core:apiDump task to overwrite API declarations
```

When the change is **intentional**, regenerate the dumps and commit the diff:

```bash
./gradlew apiDump          # rewrites <module>/api/*.api for every module
git add '**/api/*.api'
```

The updated `.api` diff then rides along in your PR, so the reviewer sees the
exact public-API delta — a new symbol, a removed one, or a changed signature —
as a first-class part of the review. Treat a *removed* or *retyped* public symbol
as a breaking change: it needs a deprecation cycle and a matching update to
`llms.txt`, the KDoc, and the cross-platform surfaces (see
[CLAUDE.md](CLAUDE.md) "Documentation drift").

> **Scope.** Only the JVM public surface is validated. `sceneview-core`'s
> `jvm("android")` dump already captures the shared `commonMain` API. Native
> (iOS) and JS klib ABI validation stays disabled for now — it is experimental
> and not buildable on the ubuntu CI runner. `sceneview-web` (a JS-only KMP
> module published to npm, not Maven) has no JVM surface and is excluded; its
> public API is documented via the hand-written `sceneview-web.d.ts` — there is
> no automated JS ABI gate yet (a `.d.ts` drift gate is shortlist #2642 item 12).

### CI on docs-only PRs

**Docs-only PRs** — changes confined to `*.md`, `docs/**`, `website-static/**`,
`marketing/**`, `branding/**`, `llms*.txt`, or `LICENSE` — skip the Android +
MCP build and verification jobs by design. The diff cannot affect runtime
behaviour, so spending 10-20 min of emulator time to re-build and re-render
is pure noise. You will see fewer green checks than on a code PR; this is
correct. Specifically:

- **`ci.yml`** — the single consolidated PR workflow (`build`, `lint`,
  `unit-test`, `api-check`, `web-desktop`, `flutter-demo`, `compile-kmp`,
  `repo-hygiene`, `quality-gate`) — carries a `paths-ignore` filter for those
  paths, so it
  does not trigger on a docs-only PR. (Before #1370 this was three separate
  workflows — `ci.yml`, `pr-check.yml`, `quality-gate.yml` — each with its
  own `changes` job; they are now one workflow with one path-detection job.)
- **`render-tests.yml`** never runs on *any* pull request — it is push-to-main
  + `workflow_dispatch` only — so docs-only PRs skip it for that reason rather
  than via a path filter.
- The **`CI Gate`** aggregator (`ci-gate.yml`) still runs on every PR and
  resolves green when the path-filtered jobs are skipped — that is how a
  docs-only PR stays mergeable.

If your docs PR needs to force a full CI run (for example, you suspect a
markdown change has accidentally invalidated an example referenced from
runtime code), trigger the gates manually from the Actions tab —
`Run workflow` on `ci.yml` / `render-tests.yml` accepts your PR's branch as
input.

### Code style

- **Kotlin**: follow the official [Kotlin style guide](https://developer.android.com/kotlin/style-guide) and existing Compose API conventions (composable functions, `remember*` helpers, named parameters). The code style is stored in the repository and auto-configured by Android Studio.
- **Swift**: follow the existing SceneViewSwift patterns (builder-style modifiers, RealityKit conventions).
- No wildcard imports. No unused imports.
- Keep changes minimal — you can fix obvious mistakes in formatting or documentation along the way.
- **Swift `#if os(...)` guards**: do not nest a platform `#if` whose condition is
  a permutation of the file's outer guard — it is always true and only adds
  noise (issue #2044). Every `SceneViewSwift` file is wrapped in
  `#if os(iOS) || os(macOS) || os(visionOS)`; an inner
  `#if os(iOS) || os(visionOS) || os(macOS)` is the same three platforms and
  therefore dead. Use a `#if` only for a genuinely narrower set
  (`#if os(visionOS)`, `#if !os(visionOS)`, `#if os(macOS)`), and prefer a
  runtime `#available` check when the gate is an OS-version requirement.

### Changes in Filament materials

Recompile Filament materials using the [current Filament version](https://github.com/google/filament/releases) if you modify them. Enable the [Filament plugin](https://github.com/sceneview/sceneview/blob/main/gradle.properties) and build.

#### Filament runtime ↔ `.filamat` ABI invariant

> **The Filament runtime version (in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) → `filament = "X.Y.Z"`) and the `matc` toolchain that produced every committed `.filamat` blob MUST be the same major version.**

Filament refuses any material whose binary version field does not match the runtime, with `Filament panic — material version N ≠ runtime M` on first frame. There is no compile-time check; the mismatch only manifests at runtime, demo by demo. v4.1.0 shipped with the runtime at 1.70.2 and blobs at 1.71 (two parallel branches each fixed half of the pair) — 10 demos crashed; v4.1.1 hot-fixed by realigning both sides to 1.71.

**The 26 committed blobs that must stay in sync with their `.mat` sources**, spread across three modules:

```
sceneview/src/main/assets/materials/     (14) — image_texture, occlusion, opaque/transparent
                                                colored/textured/unlit, semantics_overlay, splat,
                                                video_texture(_chroma_key), view_texture_lit/_unlit
arsceneview/src/main/assets/materials/    (9) — camera_stream_flat/_depth/_person_occlusion,
                                                face_mesh(_occluder), plane_renderer(_shadow)(_v2),
                                                shadow_receiver
website-static/materials/                 (3) — lit_colored, transparent_colored, unlit_colored
```

**The `tools/GenerateFilamat.sh` workflow.** [`tools/GenerateFilamat.sh`](tools/GenerateFilamat.sh) is the single entry point for every Filament material in the repo. It auto-downloads the `matc` toolchain pinned to the Filament version in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and compiles each `.mat` to its `.filamat` blob — no manual `matc` install needed.

```
bash tools/GenerateFilamat.sh                 # regenerate all 26 .filamat blobs in place
bash tools/GenerateFilamat.sh --check         # diff against committed blobs; exit 1 on drift
bash tools/GenerateFilamat.sh --mat <name>    # regenerate one (e.g. --mat opaque_colored)
bash tools/GenerateFilamat.sh --ci-tolerant   # treat a matc download failure as WARN, not FAIL
```

The `matc` binary is cached at `~/.cache/sceneview/matc-<version>/` (overridable via `$XDG_CACHE_HOME`); the first run downloads it, subsequent runs reuse it. `--ci-tolerant` exists for sandboxed CI runners with no network — it lets the check pass with a WARN instead of failing the build when `matc` cannot be fetched.

**The drift gate.** `bash .claude/scripts/quality-gate.sh` runs `GenerateFilamat.sh --check` on every pre-push gate. Editing a `.mat` source **without** recompiling its `.filamat` blob now **blocks the PR** — the gate reports the drifted blob(s) and fails. This catches the v4.1.0-class mistake before it ships.

**The five matc flag profiles.** The committed blobs were compiled with five distinct profiles (recorded in each blob's MRPC chunk). `GenerateFilamat.sh` reproduces each one — including flag *order*, since matc embeds the verbatim flag string:

| Profile | Flags | Module |
|---|---|---|
| **A** — heavy Android | `-p all -a all` | `sceneview/` lit/textured/video/view materials |
| **B** — lean Android | `-a opengl -p mobile` | `sceneview/` unlit colored materials (2) |
| **C** — ARCore | `--optimize-size -p mobile -a opengl -a vulkan` | `arsceneview/` (9) + `sceneview/` semantics_overlay (1) |
| **D** — website / WebGL | `-p mobile -a opengl` | `website-static/materials/` (3) |
| **E** — Android occluder | `-a vulkan -a opengl -p mobile` | `sceneview/` occlusion material (1) |

When adding a new material, pick a profile by deployment target and add an entry to the `MATS` inventory in `GenerateFilamat.sh`. Each `.mat` source carries a short header block (purpose, used-by, parameters, profile) — read those headers to learn what an individual material does and where it is consumed.

**How to recompile after a Filament version bump:**

1. Bump `filament = "X.Y.Z"` in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
2. Run `bash tools/GenerateFilamat.sh` — it downloads the matching `matc` and recompiles every `.filamat` from its `.mat` source.
3. Commit **the runtime bump AND the recompiled `.filamat` files in the SAME PR**. Never split them across commits — that's the failure mode that broke v4.1.0.

`GenerateFilamat.sh --check` (and the `quality-gate.sh` drift gate) will catch a runtime/blob mismatch before merge. If you somehow bypass the gate, the first signal is a runtime crash on whichever demo loads the affected material first.

#### Visual QA for material / shader changes (Mac-friendly)

A material change can compile, pass unit tests, and still render wrong (the v4.16.x plane-renderer "white blob", #2224, is the canonical example — three "fixes" shipped before the real cause was found). Compile + unit tests are **not** a visual gate. This is the validated flow, refined while fixing #2224.

**1 — Diagnose & iterate on the shader on a plain Mac, with no ARCore.** ARCore cannot run in an Android emulator on Apple Silicon (x86 needs TCG → ARCore watchdog-timeouts; arm64 has no ARCore camera bridge — see `feedback_arcore_emulator_mac_dead_end` / issue #1645, definitively closed). **But pure Filament rendering works perfectly on the standard arm64 emulator.** So isolate the shader: render the exact committed `.filamat` on a static, hand-built mesh in a **non-AR** `SceneView`, with no ARCore session — the pattern in [`PlaneGridPreviewDemo`](samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PlaneGridPreviewDemo.kt) (debug-only, `adb shell am start -n io.github.sceneview.demo/.DemoHostActivity --es demo_id plane-grid-preview`). Mirror the AR render pipeline: pass `renderQuality = RenderQuality.Performance` (bloom/SSAO **off**, like `ARSceneView`) — the default `SceneView` keeps bloom on, which washes a translucent material into a uniform blob and hides the real output.

**2 — Pixel-measure, don't eyeball.** Capture `adb exec-out screencap -p`, convert to BMP (`sips -s format bmp`), parse pixels (no ImageMagick on most Macs). Over a known background you can read the actual composite. #2224's root cause was found this way: an unlit `blending: transparent` material that writes a straight (non-premultiplied) `baseColor.rgb` composites as `color + (1-α)·bg` (colour at full strength) instead of `lerp(bg, color, α)`, so capping `α` never reduces the colour. Fix: premultiply in the fragment — `material.baseColor.rgb = <color> * material.baseColor.a;`. A distinguishing test (render with two different `color` values and check the slope) tells premultiplied from straight-add unambiguously.

**3 — Confirm on a real device (the only true AR gate).** USB, not wireless — `adb`-over-Wi-Fi drops mid-transfer on a 200+ MB demo APK. The Play Store build is release-signed, so `uninstall io.github.sceneview.demo` first (debug APK can't replace it; `INSTALL_FAILED_VERSION_DOWNGRADE` / signature mismatch otherwise). Launch an AR demo via `DemoHostActivity` (`--es demo_id ar-placement`), grant `android.permission.CAMERA`, and screen-record while sweeping over varied surfaces — **especially a bright/outdoor one**, the condition that made the #2224 blob worst.

**4 — Analyse the screen recording.** Pull it, then: contact-sheet the frames with ffmpeg's `tile` filter (`-vf "fps=1/3,scale=200:-1,tile=5x4"` — no ImageMagick needed) for a fast overview, then extract full-res frames at the interesting timestamps for the actual judgement. If the recording has narration, transcribe with Whisper **large-v3** (smaller models are not reliable for this). ⚠️ A transcript that is only `Sous-titrage ST'…` / `Merci` / `Amara.org` repeated is Whisper's **silence hallucination** — it means there was no speech, not a real transcript.

**Two hard rules (born from the #2224 saga):** (a) **look at an actual rendered frame — and pixel-measure it — before claiming a visual fix works**; never infer "it looks better" from code. (b) **Verify any flag an LLM suggests against the real binary before trusting it** — e.g. the proposed ARCore replay property `debug.com.google.ar.core.camera_playback_autostart` was a confident hallucination (0 hits in 1 M strings extracted from the ARCore APK).

---

## Maintenance scripts

The `.claude/scripts/` directory holds the housekeeping scripts that
keep parallel-orchestrator sessions tidy. Two are worth knowing about
explicitly because the safety contract has gotten complex enough that
you can't infer it from the source on first read.

### `worktree-auto-prune.sh`

Reclaims `.claude/worktrees/*` whose branch has merged. Safe-by-default:
the only way it can lose work is via an explicit override flag.

| Flag | Effect |
|---|---|
| `--dry-run` | Preview only. No worktree is removed. |
| `--yes` | Non-interactive. Skip the confirmation prompt. |
| `--keep <path>` | Repeatable. Never touch this worktree (the caller's own tree should always be `--keep`). |
| `--allow-stale` | Proceed offline if `git fetch origin main` fails. `ahead=0` then additionally requires a merged-PR signal. |
| `--no-check-active-sessions` | Disable the cwd scan that protects worktrees with a live process inside them. Almost never the right call. |
| `--unlock-locked` | Override `git worktree lock`: prune locked-but-clean worktrees too. The dirty check still wins. |

Skip ladder (a worktree must pass every layer to be reclaimed):

1. Not in `--keep`.
2. `git status --porcelain` is empty (no uncommitted changes).
3. Not `locked` via `git worktree lock` (unless `--unlock-locked`).
4. No process anywhere on the host has cwd inside the worktree
   (gradle daemons, `python`, IDE indexers — all detected, not just
   `node`/`claude`).
5. Either `ahead-count == 0` vs `origin/main`, OR the branch's
   associated GitHub PR is `MERGED`.

Forensic trail: every evaluated worktree appends one JSON line to
`~/.claude/logs/worktree-prune-YYYYMMDD.log` (daily-rotated, never
auto-deleted). Cheap to write, priceless if an incident occurs.

Pin: `.claude/scripts/test-worktree-auto-prune.sh` exercises 7 scenarios
(merged, unmerged, dirty, locked, locked + `--unlock-locked`, active
subprocess, `--keep`) and runs advisorily inside `quality-gate.sh`.

### `cleanup-branches-worktrees.sh`

Wrapper that runs `worktree-auto-prune.sh` AND deletes the corresponding
merged `claude/*` branches (local + remote) in a single batched
`git push --delete` to avoid bot-burst rate limits. Same flags, same
safety contract; runs daily in `.github/workflows/maintenance.yml`.

---

## Issues and discussions

- **Bug reports**: use the issue templates on [GitHub Issues](https://github.com/sceneview/sceneview/issues). Include platform, SceneView version, minimal reproduction steps, and relevant logs.
- **Questions**: open a [Discussion](https://github.com/sceneview/sceneview/discussions) instead of an issue.
- **Feature requests**: welcomed as issues or discussions.
- **Chat**: join the [Discord](https://discord.gg/UbNDDBTNqb) to talk with the community and maintainers.
