# Maestro device-QA flows

[Maestro](https://maestro.dev) YAML flows that drive the SceneView demo apps
**like a real user** — taps, swipes, camera-orbit drags, navigation — and
assert no crash. This is the Android/iOS leg of the autonomous device-QA
harness (umbrella [#1560](https://github.com/sceneview/sceneview/issues/1560)).

> For a full cross-platform pass (Android + iOS + web + AR replay) in one
> command, run the orchestrator: `bash .claude/scripts/device-qa.sh --platform=all`.
> The sections below cover running the Maestro legs directly. See
> [`CLAUDE.md` → "Device QA"](../CLAUDE.md) for the harness overview and the
> release-checkpoint mandate.

## Layout

```
.maestro/
  android/
    catalog.yaml      master flow — all 52 demos
    3d-basics.yaml    per-category subsets (run a fast slice)
    lighting.yaml
    content.yaml
    interaction.yaml
    advanced.yaml
    ar.yaml
    flows/
      demo.yaml       reusable parameterised subflow (one demo)
  ios/
    catalog.yaml      master flow — all 63 deep-linkable demos
    3d-basics.yaml    per-category subsets (run a fast slice)
    lighting.yaml
    content.yaml
    interaction.yaml
    advanced.yaml
    ar.yaml           AR demos — launch-only smoke
    placeholders.yaml deep-link placeholder smoke (un-ported ids)
    flows/
      demo.yaml          reusable subflow (one demo, with crash assertion)
      demo-noassert.yaml subflow for demos with no overlaid SwiftUI chrome
      ar-demo.yaml       launch-only AR smoke subflow
      placeholder.yaml   deep-link-placeholder smoke subflow
```

## Run it — Android

```bash
# Install the pinned Maestro version (idempotent, user-local, no shell-rc edit).
source .claude/scripts/lib/maestro.sh && maestro_ensure

# Boot the ARCore-friendly emulator and install the demo APK.
bash .claude/scripts/setup-ar-emulator.sh
./gradlew :samples:android-demo:assembleDebug
adb install -r samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk

# Full catalog (52 demos) …
maestro test .maestro/android/catalog.yaml
# … or a fast subset.
maestro test .maestro/android/lighting.yaml
```

The legacy `.claude/scripts/qa-android-demos.sh` is now a thin wrapper that
builds + installs the APK and invokes `catalog.yaml` through Maestro.

Through the wrapper, `catalog.yaml` is **expanded**: each per-category flow
runs as its own `maestro test` under its own `MAESTRO_FLOW_TIMEOUT` (default
900 s), and an expired budget is reported as a `timeout` verdict naming the
flow — not as a demo failure (#3141). A bare `maestro test catalog.yaml` as
above runs the whole catalog in one unbounded invocation, which is fine for an
interactive smoke and is *not* how the harness runs it.

## Run it — iOS

```bash
# Build + install the demo on an iOS simulator and run the full catalog.
bash .claude/scripts/ios-device-qa.sh --install

# … or, against an already-installed build, a fast subset.
bash .claude/scripts/ios-device-qa.sh --flow lighting
```

`ios-device-qa.sh` is the iOS analogue of `qa-android-demos.sh`: it boots a
simulator, optionally builds + installs `samples/ios-demo` (`xcodebuild`,
scheme `SceneViewDemo`), runs the Maestro flow, then sweeps the simulator log
for crash markers. The `xcodebuild` step is heavy — on a disk-constrained host
omit `--install` and reuse an installed build.

### Keyed QA — exercising the Sketchfab / Explore path ([#2356](https://github.com/sceneview/sceneview/issues/2356))

On a fresh checkout the iOS demo builds **keyless**: `SketchfabConfig.apiKey`
is `nil`, the Explore tab's Sketchfab carousels + search are disabled, and the
streamed-USDZ demos (`MultiModelDemo`, `OrbitalARDemo`, `ModelViewerDemo`) fall
back to bundled assets. So a default `--install` QA run never exercises the
live Sketchfab → USDZ-download → RealityKit `Entity(contentsOf:)` path — the
one that caused the App Review 2.1(a) rejection in
[#2252](https://github.com/sceneview/sceneview/issues/2252) (RealityKit cannot
load GLB, so the iOS service must request **USDZ**).

To run a **keyed** build that exercises that path, supply a Sketchfab API token
one of three ways (this mirrors how Android #2343 keys `assembleDebug`):

```bash
# 1. Local-dev xcconfig (persists across Xcode + xcodebuild runs):
cp samples/ios-demo/SceneViewDemo/Secrets.xcconfig.template \
   samples/ios-demo/SceneViewDemo/Secrets.xcconfig
#   …then paste your token into Secrets.xcconfig (gitignored — never committed).

# 2. The harness resolves the key automatically from the env or the
#    repo-root local.properties `sketchfab.api.key` and bakes it into the build:
SKETCHFAB_API_KEY=<token> bash .claude/scripts/ios-device-qa.sh --install
#    (or just set sketchfab.api.key=<token> in local.properties, then run --install)

# 3. Explicit flag (overrides 1 + 2):
bash .claude/scripts/ios-device-qa.sh --install --sketchfab-key <token>
```

`ios-device-qa.sh` sources `.claude/scripts/lib/qa-keys.sh` (the same resolver
the Android leg uses), passes the key to `xcodebuild` as a `SKETCHFAB_API_KEY`
user-defined build setting (which `Info.plist`'s `SketchfabAPIKey =
$(SKETCHFAB_API_KEY)` substitutes), and reports **presence only** — the token
value is never printed or committed. A keyless run prints a loud banner and is
treated as NOT having tested the Sketchfab path (advisory, mirroring Android's
`sketchfab` skipped sub-leg). `Config.xcconfig` `#include?`s `Secrets.xcconfig`
optionally, so a checkout without the file builds keyless and silently.

A key present is still not sufficient on its own (#2959): both `device-qa.sh`
(Android, via an on-device probe) and `ios-device-qa.sh` (host-side, since the
Simulator shares the Mac's network) also probe an actual route to the
streamed-asset host before trusting that a keyed run exercised it — airplane
mode, a captive portal, or a dead DNS resolver would otherwise resolve every
streamed slug to its bundled fallback silently. See `lib/qa-connectivity.sh`
and pass `--allow-offline` to acknowledge a deliberately offline run.

### iOS coverage and known gaps

- **CI wiring — nightly + release-checkpoint, advisory
  ([#2803](https://github.com/sceneview/sceneview/issues/2803)).** `device-qa.yml`
  now defines an `ios` job that runs `device-qa.sh --platform=ios --fast --ci`
  on a `macos-15` runner (routing to the self-hosted Mac when its heartbeat
  reports it online). It is gated `if: github.event_name != 'push'`, so it runs
  on the nightly `workflow_call` (nightly-ci.yml) and on manual
  `workflow_dispatch` — NEVER on a per-push build (a macOS runner is ~10x the
  ubuntu per-minute cost). The leg is **advisory** (`continue-on-error` + tagged
  advisory in `device-qa.sh`): a red iOS leg is a release WARN, never a hard
  block, until the CI simulator leg is proven reliably green. You can still run
  it locally any time (`bash .claude/scripts/device-qa.sh --platform=ios` /
  `ios-device-qa.sh`).
- **Deep-link reachable set, not a strict subset.** iOS flows reach demos via
  the public `sceneview://demo/<id>` custom scheme. The reachable set is
  `DemoDeepLinkRegistry.allowedIds` (63 ids, up from 24 when
  [#1563](https://github.com/sceneview/sceneview/issues/1563) landed) — mostly
  Android's 52 registered demo ids plus several retired-alias / coming-soon
  ids, though 2 entries (`ar-lighting`, `ar-recording`) don't match any current
  Android id and 12 current Android ids aren't in the set yet, so it isn't a
  clean subset either way. The iOS Samples-tab presents demos in a
  `.fullScreenCover` with **no Close affordance**, so a UI-navigation walk of
  the catalog cannot advance past the first demo — deep-link launch +
  `stopApp: true` cold-restart per demo is the only viable ingress. Widening
  `allowedIds` (or adding a Close button to the full-screen cover) is a tracked
  follow-up.
- **AR demos are launch-only smoke.** RealityKit AR cannot run on the iOS
  simulator — `ar.yaml` only asserts the AR screen mounts without crashing.
- **No `qa_mode`.** Android freezes auto-rotation with a `qa_mode` deep-link
  argument for a deterministic screenshot; iOS has no equivalent yet (tracked
  follow-up). iOS screenshots are smoke artefacts, not pixel baselines.
- **`text` / `model-viewer`** render no overlaid SwiftUI chrome on a key-less
  simulator, so they use the assertion-free `flows/demo-noassert.yaml`; their
  crash detection falls to the log sweep below.
- **Distinct from `render-tests.yml`'s "iOS screenshot tests" job — now REAL
  (#2803).** That job runs the dedicated `SceneViewDemoUITests` UI-testing
  target (an XCUITest that launches the app and captures an `XCTAttachment`
  screenshot of the launch screen, every tab, and a representative subset of
  working 3D demos reached via the `-demo <id>` launch arg), then exports the
  attachments as real PNG artifacts. It uses its own `SceneViewDemoUITests`
  scheme, so the per-PR unit-test check (`ios.yml`, `-scheme SceneViewDemo`)
  stays fast and simulator-free. The Maestro leg here and that XCUITest leg are
  complementary screenshot paths.

## How a demo is exercised

**Android** — `android/flows/demo.yaml` runs once per demo with `DEMO_ID` /
`DEMO_NAME` env vars:

1. `launchApp` with `demo` / `qa_mode` `arguments` — Maestro delivers these as
   intent extras on the launch intent (`--es demo <id>`, `--ez qa_mode true`).
   `MainActivity` routes the `demo` extra through `DeepLinkRouter.validate`
   (the same closed-registry allow-list as the `sceneview://demo/<id>` scheme),
   and `qa_mode` freezes auto-rotation for a deterministic screenshot. Maestro
   `launchApp` has no `deepLink` property — that key aborts flow parsing.
   State-driven flows (`flows/ar-cloud-anchor.yaml`) add one more argument,
   `qa_state: <id>` — the single seam that pins a demo to a named state the
   emulator cannot reach on its own ([#3421](https://github.com/sceneview/sceneview/issues/3421),
   [#3455](https://github.com/sceneview/sceneview/issues/3455)). It is ignored
   unless `qa_mode` is also set; the per-demo id lists live in
   `samples/android-demo/README.md`.
2. Orbit the camera (two opposite horizontal drags + one vertical drag).
3. Tap the viewport centre (node pick / harmless empty-space tap).
4. Capture **exactly one** screenshot (`demo-<id>`).
5. Assert the "Navigate back" affordance is still visible — a process crash
   makes this fail.
6. Navigate back to the demo list.
7. **Optional zoom QA** ([#1571](https://github.com/sceneview/sceneview/issues/1571)):
   when the caller sets the `CAMERA_DISTANCE` env var, `flows/demo.yaml`
   additionally re-launches the demo at a near + far framing via the
   `camera_distance` intent extra. Maestro delivers env-interpolated launch
   arguments as **String** extras (not `--ef` floats — verified on-emulator,
   [#2652](https://github.com/sceneview/sceneview/issues/2652)), so
   `MainActivity` coerces the extra type-agnostically
   (`DeepLinkRouter.coerceCameraDistanceExtra`) before it feeds
   `DemoSettings.cameraDistance`, overriding the 3D hero-orbit camera distance
   — the only way to exercise 3D zoom, since Maestro has no pinch gesture.
   Each zoom relaunch is a cold start, so before its screenshot the flow waits
   for the model-load scrim to clear plus a fixed 9 s render warm-up (engine +
   IBL + shader compilation; a plain `waitForAnimationToEnd` returns early on
   a `qa_mode`-frozen black viewport, which is how the near/far captures used
   to come out black and byte-identical, #2652). `3d-basics.yaml` sets it on
   `model-viewer`, producing `demo-model-viewer-zoom-near` / `-zoom-far`
   screenshots.

**iOS** — `ios/flows/demo.yaml` runs once per demo with `DEMO_ID` /
`DEMO_NAME` / `ASSERT_TEXT` env vars:

1. `launchApp` cold-starts the app, then `openLink` fires the
   `sceneview://demo/<id>` custom-scheme deep link.
2. `extendedWaitUntil` waits for `ASSERT_TEXT` (a known on-screen string) so a
   launch crash fails fast.
3. Orbit the camera (two opposite horizontal drags + one vertical drag).
4. Tap the viewport centre.
5. Capture **exactly one** screenshot (`demo-<id>`).
6. Assert `ASSERT_TEXT` is still visible — a process crash drops the app to
   SpringBoard, failing this. There is no "navigate back": `stopApp: true` on
   the next demo's `launchApp` is the per-demo isolation (the iOS Samples-tab
   `.fullScreenCover` has no Close affordance — see the iOS gaps above).

## Known limitations

- **Live-camera AR cannot run on any emulator on Apple Silicon — QA it on a
  physical device.** ARCore ships no arm64 emulator build
  ([#2754](https://github.com/sceneview/sceneview/issues/2754)): the device
  APK hard-requires the back camera at HAL id `0`, which arm64 AVDs never
  expose (back enumerates as `10`), and the `_x86_for_emulator` APK carries
  x86/x86_64 native libs only. On the arm64 QA AVD every AR flow therefore
  runs in `qa_mode` fallback (canned engines, synthetic frames) — that is a
  UI/state check, not a live-camera check. An x86_64-under-Rosetta rig
  ([#2758](https://github.com/sceneview/sceneview/issues/2758)) was built to
  close that gap and was **measured not to**: on a quiet host it did boot
  (~45 min under pure-software TCG), but it exposed the *same* camera topology
  as arm64 (HAL ids `1`/`10`, still no `0` — so that numbering comes from the
  emulator's camera HAL, not the guest ABI), installing the ARCore APK killed
  `system_server`, and nothing rendered. The rig and its `--rosetta` flag were
  removed once that dead end was established; the finding is what remains. Real
  ARCore-session QA (tracking, hit-tests, anchors) needs a physical device.
- **No pinch gesture.** Maestro cannot pinch, so 3D camera zoom cannot be
  driven by touch. On **Android** this is solved by the `camera_distance`
  deep-link param ([#1571](https://github.com/sceneview/sceneview/issues/1571),
  see step 7 above): the demo app accepts a `camera_distance` intent extra of
  any Bundle type (Float from `adb --ef`, String from Maestro launch arguments
  — [#2652](https://github.com/sceneview/sceneview/issues/2652)) and a
  `sceneview://demo/<id>?cameraDistance=<f>` query parameter that
  override the hero-orbit camera distance, so `flows/demo.yaml` can deep-link a
  near + far framing. The matching iOS and web deep-link params are tracked in
  [#1563](https://github.com/sceneview/sceneview/issues/1563) /
  [#1564](https://github.com/sceneview/sceneview/issues/1564) — until they land,
  zoom is not exercised on iOS or web.
- **Device-log crash sweep** is not a Maestro primitive in 1.39. The
  orchestrator runner (`device-qa.sh`, umbrella slice
  [#1566](https://github.com/sceneview/sceneview/issues/1566)) clears / tails
  the device log before the run and greps it afterwards — `adb logcat` on
  Android (`FATAL EXCEPTION` / `ANR`), `simctl spawn … log` on iOS (`Fatal
  error` / `NSException`). The wrapper scripts (`qa-android-demos.sh`,
  `ios-device-qa.sh`) also do this sweep. Per-demo `assertVisible` crash
  detection works standalone.
- **SwiftUI sheet content is invisible to Maestro on recent runtimes.** With a
  presented sheet visibly open and rendered (screenshot-verified on the
  iPhone 17 / iOS 26.3 simulator with Maestro 2.6.1, 2026-07-09), the
  accessibility hierarchy Maestro sees contains only the gear FAB and the
  status bar — none of the sheet's control labels (`Subject`, `Playback`,
  `Density`, …). `flows/demo-settings.yaml` therefore marks its `ASSERT_TEXT`
  sheet-content assertion `optional: true` (advisory): the tap that opens the
  sheet still composes its content (a compose crash kills the app), and the
  hard crash gates remain the final FAB re-assert plus the wrapper's
  simulator-log sweep. Re-promote the assertion to hard once a Maestro/runtime
  combo traverses sheet content again.

## Emulator boot snapshots — faster, deterministic Android QA

[#1672](https://github.com/sceneview/sceneview/issues/1672)

The QA AVD's userdata partition fills up after ~6 QA runs and Filament
viewports turn black — a long-standing storage-degradation bug. The fix is a
**golden boot snapshot**: a clean post-ARCore-install image seeded once, then
cold-booted from on every subsequent run with `-no-snapshot-save` so QA runs
*load* the warm state but never *write back* to it.

```bash
# Seed the golden snapshot once (or after a --clean rebuild):
bash .claude/scripts/setup-ar-emulator.sh --clean --seed-snapshot

# Every normal run now restores 'qa-clean' automatically — faster boot,
# identical post-install state each time, userdata never degrades:
bash .claude/scripts/setup-ar-emulator.sh

# Inspect snapshot state without mutating anything:
bash .claude/scripts/setup-ar-emulator.sh --check

# Escape hatch — force a cold boot even when a snapshot exists:
bash .claude/scripts/setup-ar-emulator.sh --no-snapshot
```

Why this is correct and bounded:

- **Restore is read-only.** `-snapshot qa-clean -no-snapshot-save` loads the
  golden RAM/disk image and discards every mutation at shutdown — the snapshot
  is immutable, so it can never accumulate the run-to-run cruft that degrades
  the partition.
- **Pool peers cold-boot.** Only the base-port (`emulator-5554`) emulator
  restores the snapshot. `-snapshot` is incompatible with `-read-only`, and the
  RAM-budgeted adaptive pool (#1654) boots `-read-only` peers that share one
  AVD — those always cold-boot. The snapshot is a per-AVD single-writer asset.
- **`--clean` drops the snapshot.** A wipe-and-recreate also removes the stale
  `qa-clean` snapshot directory, so a fresh AVD never restores a mismatched
  image. Re-seed with `--seed-snapshot` afterwards.
- **CI is unaffected.** GitHub-hosted device-QA uses
  `ReactiveCircus/android-emulator-runner`, which already has its own
  AVD-snapshot caching and a fresh runner per job. The golden snapshot is a
  *local* QA speed-up; the CI emulator options are unchanged.

## Android Studio Journeys — spike planned (the pinch/zoom lead)

[#1672](https://github.com/sceneview/sceneview/issues/1672) also proposed
[Android Studio Journeys](https://developer.android.com/studio/gemini/journeys)
— natural-language functional tests (`.journey.xml`) executed by Gemini. They
are an attractive fit for SceneView's "drive the demos like a real user, assert
no crash" mandate. The earlier assessment ("not adopted — blocked on an AGP
9.0.0 bump") is **stale** on the three points that mattered:

- **No AGP-9 bump is required.** The old blocker was the headless Gradle runner
  building the app itself. `JOURNEYS_CUSTOM_APP_ID` runs a journey against an
  **already-installed** APK (our emulator-installed `io.github.sceneview.demo`)
  instead of driving a Gradle build — sidestepping the AGP-9 requirement
  entirely. This repo can stay on its current AGP.
- **Out of Studio Labs — but NOT yet headless.** Journeys has shipped in Studio
  Labs since **Otter 3 (Jan 2026)**. Google's docs announce Journeys execution
  from the **android CLI 1.0** (I/O May 2026), but the shipped binary
  `1.0.15498356` has **no `journeys` command** (verified on-device 2026-07-09:
  absent from `android help`, from the `studio` group, and from `strings` on
  the payload). Until the CLI actually ships it, running a journey requires an
  interactive Android Studio session (Gemini + Studio Labs).
- **It is the only credible pinch/zoom lead.** Neither Maestro (pinch is still
  open upstream — [maestro#2169](https://github.com/mobile-dev-inc/maestro/issues/2169))
  nor the android CLI has a real multi-touch pinch. 3D camera zoom is currently
  faked via the `camera_distance` deep-link param (see "Optional zoom QA"
  above). Journeys drives Gemini-piloted gestures, so it is the one path that
  could exercise a genuine pinch-to-zoom instead of the deep-link workaround.

**Status: spike blocked on tooling (2026-07-09).** The plan stands — prototype
one journey ("open demo X, pinch to zoom, assert no crash") against the
pre-installed demo APK via `JOURNEYS_CUSTOM_APP_ID` — but the headless path is
blocked until the `android` CLI actually ships its `journeys` command (absent
from `1.0.15498356`); meanwhile it requires an interactive Studio host
(Gemini + Studio Labs). Re-probe on each CLI release, then compare to the
Maestro catalog. The open empirical question is whether the Journeys executor issues a
**real multi-touch pinch** the Filament orbit camera reacts to; that is the
whole point of the spike and can only be settled on device. The other audit
items (Gradle Managed Devices + ATD images, Firebase Test Lab Spark tier,
emulator gRPC API) remain open backlog — none is blocked, but each is a
separate change.
