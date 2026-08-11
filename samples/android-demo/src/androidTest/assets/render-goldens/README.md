# 3D-demo render goldens

PNG screenshots of the actual Filament-rendered demo output, captured by
`DemoRenderingScreenshotTest` via UiAutomator and compared on every
`connectedDebugAndroidTest` run.

## How to add a new golden

1. Add a `@Test fun` in `DemoRenderingScreenshotTest` that calls
   `captureAndCompare(demoSlug, goldenName, settleSeconds)`.
2. Run the test once on a real device (Pixel 9 / Pixel 7a / etc.):
   ```bash
   ./gradlew :samples:android-demo:connectedDebugAndroidTest \
       --tests DemoRenderingScreenshotTest.<methodName>
   ```
3. The test skips (`assumeTrue`) and saves the captured first-run image.
4. Pull and **look at it** — this step is not optional, see "What the harness cannot
   check" below:
   ```bash
   adb pull /sdcard/Download/SceneView/test-captures/<name>_first_run.png \
       samples/android-demo/src/androidTest/assets/render-goldens/<name>.png
   ```
5. Commit the PNG **and add its `goldenName` to `BASELINED_GOLDENS`** in the same
   commit. From then on a missing golden is a hard failure instead of a silent skip —
   that allow-list is what stops a deleted baseline from turning the case green-by-
   absence (#2323).
6. Subsequent runs verify against it with 8/255 channel tolerance, 2 % pixel-fail
   budget. Diff images dump to `/sdcard/Download/SceneView/test-captures` on fail.

## What the harness checks, and what it cannot

The harness refuses to record or compare a frame whose SceneView band is flat: an
all-black viewport fails with `DEGENERATE` (committed golden) or `never rendered
anything` (fresh capture). That guard exists because seven baselines had been
committed as empty viewports and every one of their tests was passing.

It cannot tell a *complete* scene from an *incomplete* one. A demo whose skybox has
loaded but whose model has not fills the viewport with content and reads as settled —
`lighting-lab` recorded exactly that. Only the settle budget defends against it, which
is why every model-loading demo waits 14 s. **Look at a capture before promoting it.**

## Determinism

The suite pins the device to light mode (`cmd uimode night no`) in `@Before` and
restores `night auto` in `@After`. The demo chrome follows the system theme, so a
device left in dark mode differs from these light-mode goldens on ~50 % of its pixels.

## Tolerance tuning

Default tolerance accommodates GPU fp drift between identical runs on the same
hardware. If a particular demo has more variance (e.g. animated scenes) loosen
the per-test thresholds; if a demo is fully deterministic (single static frame),
tighten to catch sub-pixel regressions.

## CI

Currently runs only on `connectedDebugAndroidTest` — needs a real device or a
hardware-accelerated emulator (KVM-enabled GitHub Actions Linux runner, or
Firebase Test Lab). SwiftShader software renderer crashes on `capturePixels`;
see the `@Ignore` blocks in `sceneview/src/androidTest/.../render/` for context.
