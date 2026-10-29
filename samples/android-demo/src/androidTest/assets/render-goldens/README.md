# 3D-demo render goldens

PNG screenshots of the actual Filament-rendered demo output, captured by
`DemoRenderingScreenshotTest` via UiAutomator and compared on every
`connectedDebugAndroidTest` run.

## How to add a new golden

1. Add a `@Test fun` in `DemoRenderingScreenshotTest` that calls
   `captureAndCompare(demoSlug, goldenName, settleSeconds)`.
2. Run the test once on the shared `Pixel_7a` AVD — see "Where goldens are
   recorded" below; never on a personal device:
   ```bash
   bash .claude/scripts/setup-ar-emulator.sh
   ANDROID_SERIAL=emulator-5554 ./gradlew :samples:android-demo:connectedDebugAndroidTest \
       -Pandroid.testInstrumentationRunnerArguments.class=io.github.sceneview.demo.render.DemoRenderingScreenshotTest#<methodName>
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

## Where goldens are recorded

Every golden in this directory is recorded on the shared `Pixel_7a` AVD
(`emulator-5554`, created by `.claude/scripts/setup-ar-emulator.sh`): **1080x2400
@ 420 dpi, light mode, hardware GPU**. The test crops the top 96 px of status
bar, so the committed PNGs are **1080x2304**. Anything recorded at another
geometry fails every comparison on `Size mismatch` before a pixel is read — that
is not a hypothetical, it is what the CI leg did for months (#3551).

## CI

No CI job runs this suite. The `demo-render-goldens` leg of
`.github/workflows/render-tests.yml` ran it on a software rasteriser against a
separate `render-goldens-swangle/` set; it was removed with that set after a
30-day audit counted zero catches and 13 red nightlies out of 24. This
directory is a local tool: run it on the `Pixel_7a` AVD before and after a
rendering change.

The `sceneview` library's own render tests are a different story: they
`assumeTrue`-skip on SwiftShader because Filament's `readPixels` crashes there
(see the `@Ignore` blocks in `sceneview/src/androidTest/.../render/`). This suite
does not use `readPixels` — it screenshots the composited frame through
UiAutomator.
