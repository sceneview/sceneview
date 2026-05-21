# Non-AR demo regression tests — pin the state machine, snapshot the controls

> Audience: SceneView contributors (and Claude sessions) editing the 3D demos.
> Goal: catch non-AR demo regressions on every commit without an emulator and
> without anyone having to look at the screen. Sister doc to [`AR_TESTING.md`](AR_TESTING.md),
> which covers the device-side AR record-replay workflow.

## Why a separate workflow

The demo composables intermix `SceneView { … }` (Filament-backed, JNI) with their
controls panel. Robolectric stubs Android but **not** Filament — instantiating the
full demo composable in a pure-JVM test crashes trying to create the Filament
`Engine`. Device-backed `connectedDebugAndroidTest` rendering tests exist
([`AR_TESTING.md`](AR_TESTING.md) layer 1), but they are slow (~9 min) and the
SwiftShader CI crashes on Filament pixel readback.

So the non-AR demos are regression-tested in **three layers**, two of which run in
plain `./gradlew :samples:android-demo:testDebugUnitTest` — no device, no emulator.
This is the issue [#880](https://github.com/sceneview/sceneview/issues/880) plan.

## Layer 1 — pure-JVM state machine (runs in `testDebugUnitTest`)

The testable logic of each demo is extracted out of the Compose composable into
pure-Kotlin functions, gathered in
[`DemoMath`](src/main/java/io/github/sceneview/demo/demos/internal/DemoMath.kt).
`DemoMath` is the **single source of truth** for the calculation — the demo
composable just calls into it — so a JVM test pinning the function pins the
demo's visible behaviour.

| Demo | Extracted function | What it pins |
|---|---|---|
| `GeometryDemo` | `DemoMath.nextSpinDegrees` | 36°/s spin rate, 360° wrap, NTP-clock-backwards guard |
| `MultiModelDemo` | `DemoMath.rotateAroundCentre` | turntable rotation, distance-preservation, the 4-model layout |
| `AnimationDemo` | `DemoMath.cameraModeScript` | the 5 cinematic camera shots — yaw sweep, dolly-zoom FOV/radius opposition, hold beats, slider ranges |
| AR placement demos | `DemoMath.placementRotationFor` | the bundled-helmet −90° X correction |

Tests live next to the source in `src/test/.../demos/internal/`:

- [`DemoMathTest`](src/test/java/io/github/sceneview/demo/demos/internal/DemoMathTest.kt)
  — `nextSpinDegrees`, `rotateAroundCentre`, `placementRotationFor`.
- [`AnimationDemoStateMachineTest`](src/test/java/io/github/sceneview/demo/demos/internal/AnimationDemoStateMachineTest.kt)
  — every `CameraShot` script + the speed / IBL slider ranges.

Plus the registry-integrity layer:

- [`DemoRegistryIntegrityTest`](src/test/java/io/github/sceneview/demo/DemoRegistryIntegrityTest.kt)
  — `ALL_DEMOS` is collated from the per-demo `*Fragment.kt` files by
  `scripts/collate-demos.sh`. This test catches a bad fragment (duplicate id,
  typo'd category, non-kebab id that breaks `sceneview://demo/<id>` routing,
  unresolved `@StringRes`) at commit time instead of when a user opens the
  Samples tab.
- [`DeepLinkRouterTest`](src/test/java/io/github/sceneview/demo/DeepLinkRouterTest.kt)
  — the `sceneview://demo/<id>` and `--es demo <id>` ingress validation.

Run them all:

```bash
./gradlew :samples:android-demo:testDebugUnitTest
```

**Catches:** math regressions, slider ranges, animation-curve drift, divide-by-zero
/ NaN paths, registry collisions, broken deep-link routing.

### The extraction pattern (for a new demo)

When adding a demo with non-trivial logic, push the math/state out of the
composable:

1. Add a pure function (or a `data class` model) to `DemoMath` — no Compose, no
   Filament imports.
2. Have the demo composable call into it (e.g. `GeometryDemo` calls
   `DemoMath.nextSpinDegrees` from inside its `withFrameNanos` loop).
3. Add a `*Test.kt` next to the existing ones. Pin the *visible* contract: rates,
   ranges, wrap-around, defaults.

`AnimationDemo` keeps driving the real `Animatable`s imperatively inside its
`LifecyclePausingLaunchedEffect` — `DemoMath.cameraModeScript` is the *golden
reference* those hand-written `animateTo` keyframes are checked against, and the
demo sources its `BASE_RADIUS` / `DEFAULT_FOV_DEGREES` / slider ranges directly
from `DemoMath` so the test and the demo can never silently drift apart.

## Layer 2 — Roborazzi snapshot of the controls panel (runs in `testDebugUnitTest`)

The `controls = { … }` lambda of a demo is extracted into a separate stateless
`@Composable` taking an explicit state + callbacks. It doesn't touch `SceneView`,
so Roborazzi (Robolectric + NATIVE graphics) can screenshot it in pure JVM.

Reference implementation: `GeometryDemoControls` in
[`GeometryDemo.kt`](src/main/java/io/github/sceneview/demo/demos/GeometryDemo.kt),
tested by
[`GeometryDemoControlsSnapshotTest`](src/test/java/io/github/sceneview/demo/demos/GeometryDemoControlsSnapshotTest.kt).
Goldens land in [`src/test/snapshots/`](src/test/snapshots/).

Generate the goldens (run once, after a deliberate UI change):

```bash
./gradlew :samples:android-demo:recordRoborazziDebug --tests "*ControlsSnapshotTest"
```

Verify against goldens (every CI run):

```bash
./gradlew :samples:android-demo:verifyRoborazziDebug
```

**Catches:** Material 3 layout drift, `FilterChip` / `Slider` / `Switch` state
desync, accessibility labels, font-weight changes.

To add snapshot coverage to another demo: extract its `controls` body into a
stateless `<Demo>Controls(...)` composable (same as `GeometryDemoControls`), then
add a `<Demo>ControlsSnapshotTest` modelled on `GeometryDemoControlsSnapshotTest`.

## Layer 3 — device-backed rendering screenshots (`connectedDebugAndroidTest`)

For checks that need the actual Filament render (does the sphere visibly spin? does
the IBL slider change scene brightness?), the 3D demos are screenshot-tested on a
real device / hardware-accelerated emulator by
[`DemoRenderingScreenshotTest`](src/androidTest/java/io/github/sceneview/demo/render/DemoRenderingScreenshotTest.kt).
It launches each demo via the `sceneview://demo/<id>` deep link with
`DemoSettings.qaMode` freezing animations to a deterministic pose, waits, captures,
and compares to a golden in `androidTest/assets/render-goldens/`.

This layer is **not** part of `testDebugUnitTest` — it needs a GPU and is slow.
Re-capture the goldens with:

```bash
./gradlew :samples:android-demo:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.sceneview.demo.render.DemoRenderingScreenshotTest
```

See [`AR_TESTING.md`](AR_TESTING.md) for the device-side capture details — the 3D
rendering screenshots share that harness.

## Quick reference

| Layer | Command | Device? | Speed |
|---|---|---|---|
| 1 — state machine + registry | `:samples:android-demo:testDebugUnitTest` | no | fast |
| 2 — controls snapshot | `:samples:android-demo:verifyRoborazziDebug` | no | fast |
| 3 — render screenshot | `:samples:android-demo:connectedDebugAndroidTest` | yes (GPU) | slow |

Layers 1 and 2 run on every `pre-push-check.sh` and CI gate. Layer 3 runs on the
device-QA jobs only.

## Related

- [`AR_TESTING.md`](AR_TESTING.md) — sister workflow for the AR demos (device-side
  record-once / replay-many).
- [`DemoMath.kt`](src/main/java/io/github/sceneview/demo/demos/internal/DemoMath.kt)
  — the pure-Kotlin source of truth for every extracted demo calculation.
- Issue [#880](https://github.com/sceneview/sceneview/issues/880) — the plan this
  doc implements.
