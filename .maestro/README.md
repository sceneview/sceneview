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
    catalog.yaml      master flow — all 42 demos
    3d-basics.yaml    per-category subsets (run a fast slice)
    lighting.yaml
    content.yaml
    interaction.yaml
    advanced.yaml
    ar.yaml
    flows/
      demo.yaml       reusable parameterised subflow (one demo)
  ios/
    catalog.yaml      master flow — all 24 deep-linkable demos
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

# Full catalog (42 demos) …
maestro test .maestro/android/catalog.yaml
# … or a fast subset.
maestro test .maestro/android/lighting.yaml
```

The legacy `.claude/scripts/qa-android-demos.sh` is now a thin wrapper that
builds + installs the APK and invokes `catalog.yaml` through Maestro.

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

### iOS coverage and known gaps

- **Deep-link subset, not the full catalog.** iOS flows reach demos via the
  public `sceneview://demo/<id>` custom scheme. The reachable set is
  `DemoDeepLinkRegistry.allowedIds` (24 ids at the time slice
  [#1563](https://github.com/sceneview/sceneview/issues/1563) landed), a subset
  of Android's 42-demo catalog. The iOS Samples-tab presents demos in a
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

## How a demo is exercised

**Android** — `android/flows/demo.yaml` runs once per demo with `DEMO_ID` /
`DEMO_NAME` env vars:

1. `launchApp` with `demo` / `qa_mode` `arguments` — Maestro delivers these as
   intent extras on the launch intent (`--es demo <id>`, `--ez qa_mode true`).
   `MainActivity` routes the `demo` extra through `DeepLinkRouter.validate`
   (the same closed-registry allow-list as the `sceneview://demo/<id>` scheme),
   and `qa_mode` freezes auto-rotation for a deterministic screenshot. Maestro
   `launchApp` has no `deepLink` property — that key aborts flow parsing.
2. Orbit the camera (two opposite horizontal drags + one vertical drag).
3. Tap the viewport centre (node pick / harmless empty-space tap).
4. Capture **exactly one** screenshot (`demo-<id>`).
5. Assert the "Navigate back" affordance is still visible — a process crash
   makes this fail.
6. Navigate back to the demo list.
7. **Optional zoom QA** ([#1571](https://github.com/sceneview/sceneview/issues/1571)):
   when the caller sets the `CAMERA_DISTANCE` env var, `flows/demo.yaml`
   additionally re-launches the demo at a near + far framing via the
   `camera_distance` float extra (`--ef`). That extra feeds
   `DemoSettings.cameraDistance` through `DeepLinkRouter`, overriding the 3D
   hero-orbit camera distance — the only way to exercise 3D zoom, since Maestro
   has no pinch gesture. `3d-basics.yaml` sets it on `model-viewer`, producing
   `demo-model-viewer-zoom-near` / `-zoom-far` screenshots.

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

- **No pinch gesture.** Maestro cannot pinch, so 3D camera zoom cannot be
  driven by touch. On **Android** this is solved by the `camera_distance`
  deep-link param ([#1571](https://github.com/sceneview/sceneview/issues/1571),
  see step 7 above): the demo registry exposes a `camera_distance` float extra
  (`--ef`) and a `sceneview://demo/<id>?cameraDistance=<f>` query parameter that
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

## Android Studio Journeys — assessed, not adopted (yet)

[#1672](https://github.com/sceneview/sceneview/issues/1672) also proposed
[Android Studio Journeys](https://developer.android.com/studio/gemini/journeys)
— natural-language functional tests (`.journey.xml`) executed by Gemini. They
are an attractive fit for SceneView's "drive the demos like a real user, assert
no crash" mandate, but **they are not adopted in this PR**:

- **Hard AGP-version blocker.** Journeys' headless Gradle runner
  (`testJourneysTestDefaultDebugTestSuite`) requires **AGP 9.0.0+**. This repo
  is on **AGP 8.13.2** (`gradle/libs.versions.toml`). An AGP-9 major bump
  touches every module, the Filament/`.filamat` ABI invariant, and CI — it is
  its own scoped task, not in scope for a QA-tooling change.
- **Preview, IDE-coupled.** Journeys is a Studio Labs *preview* feature. Its
  execution still leans on Gemini-in-Studio; the headless CI path is new and
  not yet a stable, version-pinnable artifact like Maestro's CLI.
- **Maestro already covers the same ground.** The 42-demo Maestro catalog
  already drives every demo as a real user and asserts no crash, runs headless
  in CI, and is version-pinned (`lib/maestro.sh`). The marginal win from
  Journeys is layout-drift resilience — real, but not worth an AGP-9 major bump
  on its own.

**Verdict:** revisit Journeys when an AGP 9.x bump lands for other reasons.
At that point, prototype one `.journey.xml` (e.g. model-viewer orbit) and run
it alongside the Maestro catalog. Until then the emulator-snapshot win above is
the concrete, scriptable, CI-safe outcome of this issue. The other audit
items (Gradle Managed Devices + ATD images, Firebase Test Lab Spark tier,
emulator gRPC API) remain open backlog — none is blocked, but each is a
separate change.
