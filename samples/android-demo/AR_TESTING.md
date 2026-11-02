# AR + 3D demo rendering tests — capture pixels, compare to golden

> Audience: SceneView contributors (and Claude sessions) editing demos. Goal: catch
> visual regressions on every commit without anyone having to look at the screen.

## TL;DR — what's tested

Three layers of real-rendering tests in `samples/android-demo/src/androidTest/`:

1. **`DemoRenderingScreenshotTest`** (3D demos): launches each demo via deep-link,
   waits N seconds, screenshots via UiAutomator, compares to a golden in
   `androidTest/assets/render-goldens/`.
2. **`ARDemoPlaybackSmokeTest`** (AR demos): for each MP4 fixture in
   `androidTest/assets/ar-recordings/`, replays it via
   `ARSceneView(playbackDataset = file)`, screenshots once after a fixed wait,
   compares to a golden in `androidTest/assets/ar-render-goldens/`. A crash smoke
   test with a single visual check.
3. **`ARReplayHarnessTest`** (autonomous AR replay harness, #1565): replays a recorded
   ARCore session through **every** AR demo and grades each on real checks (frames
   replayed, brightness, plane, anchor, model coverage on screen), emitting a
   machine-readable `ar-qa-summary.json` and one screenshot per demo. The breadth
   counterpart to the golden tests. See [Autonomous AR replay harness](#autonomous-ar-replay-harness-1565)
   below.

All run on `connectedDebugAndroidTest` (real device / hardware-accelerated emulator —
SwiftShader CI crashes on Filament pixel readback). Diff images on failure dump to
`/sdcard/Android/data/io.github.sceneview.demo.qa/files/render-test-output/` for review.

## Autonomous AR replay harness (#1565)

`ARReplayHarnessTest` gives **breadth**: it replays a recorded ARCore session through
*every* AR demo (24 cards, plus the Session MP4 mode of `ar-rerun`) and checks what each
one shows.

### One injection point

`arsceneview` exposes `LocalARSessionProbe` (`io.github.sceneview.ar.replay`). Every
`ARSceneView` reads it: when its call site passes no `playbackDataset`, it replays the
probe's dataset, and it hands the probe every frame (`Session`, `Frame`, its nodes).

The demo app provides one `ARReplayProbe` at the root of the composition when a debug
build is launched with `--es ar_playback_file <path>` (`DemoSettings.arReplayProbe`). So
every demo replays the recording with no per-demo wiring, and a release build never
creates the probe.

### What is checked

The probe measures, per frame. The harness then takes a screenshot and grades.

| Check | Measure | Default threshold | Catches |
|---|---|---|---|
| `REPLAYED` | frames the recording advanced | ≥ 5 | playback not consumed |
| `SCENE_BRIGHTNESS` | mean Rec.709 luma of the screen centre | ≥ 0.08 | dark render |
| `PLANE` | frame of the first tracked plane | ≤ 300 | plane detection broken |
| `ANCHOR` | anchors in `TRACKING` | ≥ 1 | placement broken |
| `MODEL_COVERAGE` | screen share of the visible models' projected bounds | 0.2 % – 85 % | model hidden, culled or off screen; model giant (camera inside it) |
| `MODEL_BRIGHTNESS` | mean luma of the area the models cover | ≥ 0.05 | black model |

Each demo gets the checks it can pass (`ARReplayHarnessTest.CHECKS`):

- **placement demos**: every check. They place the model on the first good plane with no
  tap (`FeaturePlacementScene`): `ar-cloud-anchor`, `ar-depth-occlusion`,
  `ar-people-occlusion`, `ar-image-stabilization`. `ar-pool` gets every check but the
  model ones: its pool is procedural geometry, not a `ModelNode`.
- Labs demos are opened for the sweep (in-memory Labs store, nothing persisted).
- **plane demos**: frames, brightness and plane. These are `ar-plane-node`,
  `ar-surfaces` and `ar-measure`.
- **every other demo**: frames and brightness.

Every metric is recorded for every demo either way, so a demo can be promoted once its
numbers are known.

The arithmetic (projection, coverage, luminance, verdict) is in `ARReplayMetrics` /
`evaluateReplay`. It is pure Kotlin, unit-tested on the JVM (`ARReplayMetricsTest`), and
reusable by any app that provides its own probe.

Coverage is the projected **bounding box**, an upper bound and not a pixel mask. A model
inside its box but transparent still counts as covering.

### Verdicts

| Verdict | Meaning |
|---|---|
| `replayed` | frames advanced and every check of the demo passed |
| `failed` | a check failed. The entry lists the measured value and the threshold. Fails the run |
| `alive` | the first screen opens no AR session (`ar-placement` chooser, `ar-splat-room`, `ar-xr`). The demo survived |
| `skipped` | 0 frames replayed. ARCore dataset playback does not run on every emulator (#1645, #2620). This is an environment limit, never a pass (exit 3) |
| `crashed` | the process died. Fails the run |

### Recordings: a real corpus, never in the repository

The harness replays every `.mp4` it finds in `/sdcard/Download/SceneView/ar-corpus/`
(instrumentation argument `ar_corpus_dir` overrides the path). With none, it falls back to
the bundled `bundled-pixel9-sample.mp4` (debug sourceSet).

Real clips live on the host in `samples/android-demo/ar-corpus/`. That path is ignored by
version control: phone recordings carry the full camera feed (see the PII checklist). The
script pushes them to the device on every run.

### Running it

```bash
# Build both APKs, install them on the emulator, then run (never installDebug):
./gradlew :samples:android-demo:assembleDebug :samples:android-demo:assembleDebugAndroidTest
adb -s emulator-5554 install -r -d samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk
adb -s emulator-5554 install -r -d samples/android-demo/build/outputs/apk/androidTest/debug/android-demo-debug-androidTest.apk
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/ar-replay-qa.sh --no-install --out /tmp/ar-qa

# Only some demos:
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/ar-replay-qa.sh --no-install --demos ar-pool,ar-measure

# Deliberate failure: every model hidden, MODEL_COVERAGE must fail (exit 1):
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/ar-replay-qa.sh --no-install \
  --hide-models --demos ar-pool,ar-depth-occlusion --out /tmp/ar-qa-hidden
```

Outputs in `--out`:

- `ar-qa-summary.json`, merged across the shards;
- `ar-replay/<demo>.png`, one downscaled screenshot per graded demo, taken when it was
  graded;
- `ar-logcat.txt` and `ar-instrumentation.txt`.

A corpus clip's entry is keyed `<demo>@<clip>`:

```json
{
  "harness": "ar-replay",
  "passed": 20, "replayed": 17, "skipped": 0, "failed": 1, "total": 25,
  "demos": [
    { "id": "ar-pool", "key": "ar-pool", "recording": "bundled-pixel9-sample.mp4",
      "verdict": "failed", "replayedFrames": 212,
      "metrics": { "firstPlaneFrame": 41, "trackedAnchors": 1, "visibleModels": 0,
                   "coverage": null, "cameraInsideModel": false,
                   "sceneLuminance": 0.41, "modelLuminance": null },
      "checks": [
        { "check": "MODEL_COVERAGE", "passed": false,
          "measured": "no visible model", "expected": "0.0020..0.8500 of the screen" }
      ] }
  ]
}
```

## Recording a real clip

1. On a phone (Pixel 9, daylight), open the demo's `ar-record-playback` mode:
   `adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.MainActivity --es demo ar-record-playback`.
2. **Record** tab: tap **● Record** and move slowly through the scenario.
   `EXCESSIVE_MOTION` aborts tracking. 20–30 s is enough. Then tap **■ Stop**.
3. **Playback** tab: tap **Export**. The clip lands in
   `Downloads/SceneView/ar-session-<timestamp>.mp4` (`ARRecorder.exportToDownloads`).
4. Pull it under a scenario name, into the ignored corpus:
   `adb pull /sdcard/Download/SceneView/ar-session-<timestamp>.mp4 samples/android-demo/ar-corpus/table.mp4`.

Scenarios worth one clip each:

- a table;
- a floor;
- a wall;
- low light;
- outdoors;
- an object to scan.

> ⚠️ **Do not use `android run`** to install the demo. It has a measured install no-op:
> it prints success-shaped output and exits 0 having installed nothing (#2796, #2854,
> #2990). Use `adb install` above.

### PII audit checklist (before a clip is shared anywhere)

ARCore recordings are MP4 videos that bundle the **full camera feed**, IMU, depth maps,
and (on Geospatial) GPS coordinates. Treat each clip like a public-facing video upload:

- [ ] **Faces** — front-camera or back-camera framing of any human face, including bystanders
- [ ] **Voices** — back-channel mic audio captured by ARCore, even if you didn't speak
- [ ] **Home interior** — recognisable furniture, art, books, mail, screens, paperwork
- [ ] **Personal documents** — laptop screens, phone screens, notebook pages caught in frame
- [ ] **Geolocation** — Geospatial recordings include real lat/lng metadata (and frames
      from a public landmark are still publicly tied to your route there)
- [ ] **Children, pets** — implicit consent issues even for your own family members
- [ ] **Reflective surfaces** — mirrors / TVs / glass that incidentally captured anything
      from the above categories

The corpus stays out of the repository for exactly this reason. The only committed clip is
the vetted `bundled-pixel9-sample.mp4`.

Repo memory: the test suite already had to retract leaked goldens once
([55f183c3](https://github.com/sceneview/sceneview/commit/55f183c3)). MP4 fixtures are
much heavier in PII surface than PNG goldens.

---

## Why this works

ARCore's `Session.startRecording(RecordingConfig)` captures **every signal** the session
sees — camera frames, IMU, depth (when enabled), light estimation, planes, anchors,
augmented images. `Session.setPlaybackDataset(absolutePath)` then replays that MP4 as if
the camera were live. Anchors land on the same coordinates, the same plane is detected at
the same frame, light estimation returns the same colour temperature.

That makes the recording a perfect test fixture: it's the AR equivalent of mocked HTTP
responses — deterministic, fast, and reproducible across machines.

---

## Writing a regression test

With a vetted fixture in the assets, scaffold a test (or extend an existing one):

```kotlin
@RunWith(AndroidJUnit4::class)
class ARDemoPlaybackSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun arPlacementDemo_replays_recording_without_crash() {
        // Recipe today: stage the fixture to a private file, then deep-link the
        // demo with the path through `--es ar_playback_file`. The MainActivity
        // intent path validates the path is inside the app's external-files dir
        // (security guard, see #958) and forwards it through rememberArPlaybackDataset to participating AR demos, including ARRecordPlaybackDemo, via
        // DemoSettings.arPendingPlaybackFile. No `playbackOverride` parameter
        // is exposed on the demo composables.
        val fixture = copyAssetToAppFiles("ar-recordings/bundled-pixel9-sample.mp4")
        DemoSettings.arPendingPlaybackFile = fixture.absolutePath
        composeRule.setContent {
            ARRecordPlaybackDemo(onBack = {})
        }
        composeRule.waitUntilExactlyOneExists(hasText("Replay"), timeoutMillis = 10_000)
        // Assert plane detection happens within N seconds:
        composeRule.waitUntil(timeoutMillis = 15_000) { /* check via testTag */ }
    }
}
```

Two complementary assertion levels:

1. **Smoke**: demo composable mounts, no exception, plane detection fires within a
   timeout. Catches everything that's catastrophically broken.
2. **Visual**: capture a screenshot at `playbackTime = 5s` via `Frame.timestamp` →
   compare to a checked-in golden by pixel-hash. Catches subtle visual
   regressions (lighting, anchor positions, mesh rendering).

`samples/android-demo/src/androidTest/java/.../ar/ARDemoPlaybackSmokeTest.kt` is the
scaffold for level 1. Extend with level-2 assertions per scenario.

---

## Re-recording when ARCore behaviour changes

ARCore updates occasionally change recording semantics (e.g. new metadata fields, depth
format changes). When `connectedDebugAndroidTest` starts failing across all AR demos
after an ARCore SDK bump:

1. Confirm the failure is due to the SDK, not a regression — check the changelog.
2. Re-record the affected scenarios on a current device.
3. Replace the fixture MP4s.
4. Update this doc with the ARCore version that re-baseline'd the suite.

Last full re-baseline: ARCore 1.54.0. The `ARCORE_VERSION` pinned in the
emulator-sideload section below has since moved to 1.56.0 without a re-baseline.

---

## Running on emulator (Apple Silicon / arm64)

ARCore service must be installed before the smoke test runs. Play Store gates
ARCore install on a device-certification list and rejects standard AVDs as
"incompatible". Workaround — sideload the official APK from Google's ARCore SDK
GitHub releases:

```bash
# Pick the latest release: https://github.com/google-ar/arcore-android-sdk/releases
ARCORE_VERSION=1.56.0
curl -L -o /tmp/arcore.apk \
  "https://github.com/google-ar/arcore-android-sdk/releases/download/${ARCORE_VERSION}/Google_Play_Services_for_AR_${ARCORE_VERSION}.apk"
adb install -r /tmp/arcore.apk
```

**Use the multi-arch `Google_Play_Services_for_AR_X.Y.Z.apk`, NOT the
`_x86_for_emulator.apk` variant** — the x86 build crashes on Apple Silicon AVDs
(arm64-v8a host) per [google-ar/arcore-android-sdk#1571](https://github.com/google-ar/arcore-android-sdk/issues/1571). The standard release APK contains both
`arm64-v8a` and `armeabi-v7a` libs and works on Apple Silicon emulators.

## Limitations (known)

- **Recordings cannot be created at unit-test time.** Only on a real device with ARCore
  hardware. The fixture lifecycle is "human captures once, machines replay forever".
- **ARCore versioning matters.** A recording made with ARCore N may not replay 1:1 in
  ARCore N+1 (rare but documented). Re-baseline when needed.
- **Front-camera face recordings are bigger** (~30 % more data per second than
  back-camera) due to the higher-resolution face mesh. Cap face recordings at 15 s.
- **Geospatial recordings include real lat/lng** — be careful about Personally
  Identifying Information (PII). Strip GPS at recording time if the fixture comes from
  a private location, or capture from a public landmark.

---

## Related

- [`arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecorder.kt`](../../arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecorder.kt)
  — the lib API (`start` / `stop` / `exportToDownloads`).
- [`samples/android-demo/.../demos/ARRecordPlaybackDemo.kt`](src/main/java/io/github/sceneview/demo/demos/ARRecordPlaybackDemo.kt)
  — the demo with the Export button.
- [`docs/docs/ar-recording.md`](../../docs/docs/ar-recording.md) — public-facing
  recording / playback recipe.
- Issue [#876](https://github.com/sceneview/sceneview/issues/876) — proposed stateless
  `recordFrame(session, frame)` API + dedicated `onPlaybackFailed` callback (v4.1
  candidate).

### Interpreting a replay result

The playback intent is consumed by all demos wired to `rememberArPlaybackDataset`, not only
Record & Playback. A selected file, a live process, or a visible model does not prove camera
replay. Verify advancing AR session frames and the expected recorded camera scene. If session
creation fails, record that as unavailable playback, not a successful replay.
