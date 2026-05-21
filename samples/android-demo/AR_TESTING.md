# AR + 3D demo rendering tests — capture pixels, compare to golden

> Audience: SceneView contributors (and Claude sessions) editing demos. Goal: catch
> visual regressions on every commit without anyone having to look at the screen.

## TL;DR — what's tested

Four layers of real-rendering tests in `samples/android-demo/src/androidTest/`:

1. **`DemoRenderingScreenshotTest`** (3D demos): launches each demo via deep-link,
   waits N seconds, screenshots via UiAutomator, compares to a golden in
   `androidTest/assets/render-goldens/`.
2. **`ARDemoPlaybackSmokeTest`** (AR demos): for each MP4 fixture in
   `androidTest/assets/ar-recordings/`, replays it via
   `ARSceneView(playbackDataset = file)`, screenshots once after a fixed wait,
   compares to a golden in `androidTest/assets/ar-render-goldens/`. A crash smoke
   test with a single visual check.
3. **`ARPlaybackScreenshotTest`** (AR frame-indexed regression, #1050): replays the
   **bundled** ARCore recording (`src/debug/assets/ar-recordings/`) and captures the
   rendered AR frame at **fixed ARCore frame indices** (f=30/60/120/180), comparing
   each to a golden in `androidTest/assets/ar-screenshot-goldens/`. See
   [Frame-indexed AR screenshot regression](#frame-indexed-ar-screenshot-regression-1050)
   below.
4. **`ARReplayHarnessTest`** (autonomous AR replay harness, #1565): drives **every**
   AR demo through the bundled recorded session and asserts no crash, emitting a
   machine-readable `ar-qa-summary.json`. The breadth counterpart to the depth
   golden tests. See [Autonomous AR replay harness](#autonomous-ar-replay-harness-1565)
   below.

All run on `connectedDebugAndroidTest` (real device / hardware-accelerated emulator —
SwiftShader CI crashes on Filament pixel readback). Diff images on failure dump to
`/sdcard/Android/data/io.github.sceneview.demo/files/render-test-output/` for review.

## Frame-indexed AR screenshot regression (#1050)

`ARDemoPlaybackSmokeTest` captures once, after a fixed `Thread.sleep(...)`. That is
enough as a crash smoke test, but a `sleep`-gated capture lands on a different ARCore
frame every run — replay advances faster or slower than wall-clock depending on
emulator load. `ARPlaybackScreenshotTest` fixes this:

- The demo bumps a cross-thread counter — `DemoSettings.arPlaybackFrameCount` — once
  per consumed ARCore frame during playback (`onSessionUpdated`). It is reset to `0`
  every time a playback `ARSceneView` mounts.
- The test **polls** that counter and fires a capture exactly when each target frame
  index is reached (f=30/60/120/180). Same frame, every run, every machine.
- Captures are pixel-compared to goldens in `androidTest/assets/ar-screenshot-goldens/`.

### Re-recording / first-time golden capture

The `ar-screenshot-goldens/` dir starts **empty** (only a README). The first run
takes the *record path* — captures are written to the device and the assertion is
`assumeTrue`-skipped. Pull, review, and commit them as goldens. Full step-by-step
flow, plus the critical "capture goldens on the CI emulator profile, not a host-GPU
dev emulator" warning, is in
[`src/androidTest/assets/ar-screenshot-goldens/README.md`](src/androidTest/assets/ar-screenshot-goldens/README.md).

CI runs this pipeline in the `ar-demo-playback-screenshots` job of
`.github/workflows/render-tests.yml`, on a pinned emulator profile (api-30 /
`google_apis` / `x86_64` / `swiftshader_indirect`) so goldens stay reproducible.

## Autonomous AR replay harness (#1565)

The frame-indexed test above gives **depth** — pixel-exact regression on the one
demo (`ar-record-playback`) that genuinely consumes `playbackDataset`.
`ARReplayHarnessTest` gives **breadth**: it drives *every* AR demo in
`DemoCategory.AUGMENTED_REALITY` (13 demos) through the bundled recorded session
and asserts the process survives.

It is the headless, no-physical-device entrypoint the device-QA orchestrator
runner (umbrella [#1560](https://github.com/sceneview/sceneview/issues/1560),
slice [#1566](https://github.com/sceneview/sceneview/issues/1566)) calls for the
Android-AR leg, via the wrapper script:

```bash
bash .claude/scripts/ar-replay-qa.sh          # build + install + run + pull verdict
bash .claude/scripts/ar-replay-qa.sh --no-install   # APK already on device
```

The harness writes a machine-readable verdict to
`/sdcard/Download/SceneView/ar-qa-summary.json`, which the script pulls:

```json
{
  "harness": "ar-replay",
  "recording": "bundled-pixel9-sample.mp4",
  "passed": 13,
  "total": 13,
  "demos": [
    { "id": "ar-record-playback", "verdict": "replayed", "replayedFrames": 47 },
    { "id": "ar-placement",       "verdict": "alive",    "replayedFrames": 0 }
  ]
}
```

Per-demo verdict:

- **`replayed`** — the demo consumed recorded ARCore frames (the frame counter
  advanced). Today only `ar-record-playback` does, because it is the one demo
  that reads `DemoSettings.arPendingPlaybackFile` and mounts
  `ARSceneView(playbackDataset = file)`.
- **`alive`** — the demo's process survived the recorded-session replay window
  but did not advance the frame counter. The other 12 AR demos build a *live*
  `ARSceneView`; surviving a recorded session still exercises ARCore session
  creation, demo composition, the Filament engine and the AR overlay — the
  layer that breaks silently between releases. As a demo opts into
  `playbackDataset`, its verdict graduates from `alive` to `replayed`
  automatically — no harness change needed.
- **`crashed`** — the demo's process died during replay. **Any** `crashed`
  verdict fails the harness.

## The AR record-once playback-many workflow

1. **Record one baseline session per AR scenario** on a real device (Pixel 7a / 9 / Galaxy
   S-something — any ARCore device with depth, planes, light estimation).
2. **Export it** to `Downloads/SceneView/<scenario>.mp4` via the in-app **Export** button
   in `ARRecordPlaybackDemo` → Playback tab → tap **Export**.
3. **Pull it** with `adb pull /sdcard/Download/SceneView/<scenario>.mp4
   samples/android-demo/src/androidTest/assets/ar-recordings/`.
4. **Audit the MP4 for PII before committing** — see the checklist below. ARCore datasets
   are full camera-feed videos; they capture **everything the camera saw** during the
   recording session. This is a public repo.
5. **Commit the MP4** as a fixture. From then on, every `connectedDebugAndroidTest` run
   loads each AR demo against the recorded baseline, captures the rendered frame, and
   compares to its golden — anchors land in the same place, planes are detected at the
   same frame, lighting estimation returns the same colour temperature.

No phone, no hands required for the regression run. Recording is a one-time human action.

### PII audit checklist (mandatory before every fixture commit)

ARCore recordings are MP4 videos that bundle the **full camera feed**, IMU, depth maps,
and (on Geospatial) GPS coordinates. Treat each fixture like a public-facing video upload:

- [ ] **Faces** — front-camera or back-camera framing of any human face, including bystanders
- [ ] **Voices** — back-channel mic audio captured by ARCore, even if you didn't speak
- [ ] **Home interior** — recognisable furniture, art, books, mail, screens, paperwork
- [ ] **Personal documents** — laptop screens, phone screens, notebook pages caught in frame
- [ ] **Geolocation** — Geospatial recordings include real lat/lng metadata (and frames
      from a public landmark are still publicly tied to your route there)
- [ ] **Children, pets** — implicit consent issues even for your own family members
- [ ] **Reflective surfaces** — mirrors / TVs / glass that incidentally captured anything
      from the above categories

If any check fails, **don't commit** — re-record at a public location (a SceneView
contributor used a co-working space lounge) or against a printed paper texture taped to
a wall. When in doubt, record at the same controlled setup the existing fixtures use
(see `ar-recordings/baseline.mp4` for the established pattern).

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

## Step-by-step: capture a baseline

### 1. Pick the scenario

The repo ships **`baseline.mp4`** today (room pan with floor + table; exercises
plane detection + orientation classification) — it's the only fixture currently
under `src/androidTest/assets/ar-recordings/`. The fixtures listed below are
**aspirational** — record them as you encounter the corresponding regression
needs. Each baseline should exercise a different AR-feature surface.

| Filename | Status | Scenario | What it exercises |
|---|---|---|---|
| `baseline.mp4` | ✅ shipped | Slow pan around an indoor room with a flat floor + table | Plane detection, plane orientation classification |
| `outdoor-streetscape.mp4` | aspirational | Stationary capture outside facing buildings | Geospatial / Streetscape Geometry |
| `face-front-camera.mp4` | aspirational | Face mesh capture (front camera) | AugmentedFaceNode |
| `image-detection.mp4` | aspirational | Pan over the printed `qrcode.png` | Augmented Images |
| `cloud-anchor.mp4` | aspirational | Drop an anchor + survey + look back at it | Cloud Anchors |
| `depth-occlusion.mp4` | aspirational | Person walking in front of the camera | Depth Occlusion |
| `instant-placement.mp4` | aspirational | Tap-to-drop before plane convergence | Instant Placement |

Aim for **30 s recordings** — long enough to exercise the feature, short enough to keep
fixtures under 50 MB each.

### 2. Record on device

```
adb shell am start -n io.github.sceneview.demo/.MainActivity --es demo ar-record-playback
```

The same launch with [`android` CLI](https://developer.android.com/tools/agents/android-cli)
(installs the APK as part of the same call if needed — pass `--apks`):

```
android run --activity=io.github.sceneview.demo/.MainActivity
```

Note: `android run` does not yet expose `--es` intent extras, so for the
deep-link launch above stick with `adb shell am start` until v0.8+.

In the demo:

- Switch to the **Record** tab.
- Tap the red **● Record** button.
- Move the phone through the scenario (don't be too fast — `EXCESSIVE_MOTION` aborts
  tracking and ruins the fixture).
- Tap **■ Stop**.

### 3. Export to public Downloads

Switch to the **Playback** tab. The new recording appears at the top of the list. Tap
**Export**. A toast confirms the destination:

```
Exported to Downloads/SceneView/ar-session-20260507-143012.mp4
```

Under the hood this calls
`ARRecorder.exportToDownloads(context, file, subdirectory = "SceneView")` which uses
`MediaStore.Downloads` (Android 10+) or the legacy public Downloads directory (P-).

### 4. Pull + rename + commit

```bash
adb pull /sdcard/Download/SceneView/ar-session-20260507-143012.mp4
mv ar-session-20260507-143012.mp4 samples/android-demo/src/androidTest/assets/ar-recordings/room-planes.mp4
git add samples/android-demo/src/androidTest/assets/ar-recordings/room-planes.mp4
git commit -m "test(android-demo): add baseline AR recording for room-planes scenario"
```

Use a descriptive name (the scenario, not the timestamp). This becomes part of the
contract — the test will look it up by name.

---

## Writing a regression test

Once the fixture is committed, scaffold a test (or extend an existing one):

```kotlin
@RunWith(AndroidJUnit4::class)
class ARDemoPlaybackSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun arPlacementDemo_replays_baseline_without_crash() {
        // Recipe today: stage the fixture to a private file, then deep-link the
        // demo with the path through `--es ar_playback_file`. The MainActivity
        // intent path validates the path is inside the app's external-files dir
        // (security guard, see #958) and forwards it to ARRecordPlaybackDemo via
        // DemoSettings.arPendingPlaybackFile. No `playbackOverride` parameter
        // is exposed on the demo composables.
        val fixture = copyAssetToAppFiles("ar-recordings/baseline.mp4")
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
   compare to a checked-in golden via `roborazzi` or pixel-hash. Catches subtle visual
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

Last full re-baseline: ARCore 1.54.0 (matches the `ARCORE_VERSION` pinned in the
emulator-sideload section below).

---

## Running on emulator (Apple Silicon / arm64)

ARCore service must be installed before the smoke test runs. Play Store gates
ARCore install on a device-certification list and rejects standard AVDs as
"incompatible". Workaround — sideload the official APK from Google's ARCore SDK
GitHub releases:

```bash
# Pick the latest release: https://github.com/google-ar/arcore-android-sdk/releases
ARCORE_VERSION=1.54.0
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
