package io.github.sceneview.demo.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * **Real-rendering** screenshot tests for SceneView demos.
 *
 * Captures the actual Filament-rendered output of each demo (3D content + UI overlay)
 * via UiAutomator's `device.takeScreenshot(file)` and compares to a checked-in golden
 * with per-channel tolerance. Catches every visual regression that matters: spinning
 * shapes that stop spinning, models that stop loading, lighting that goes wrong, IBL
 * that flips colour, slider effects that no longer reach the rendered scene.
 *
 * **NOT** a pure-JVM test — requires a connected device or hardware-accelerated emulator
 * because Filament needs a real GPU. SwiftShader (the software renderer used on most CI
 * runners) crashes on `capturePixels` (see `sceneview/src/androidTest/.../render/`'s
 * `@Ignore` blocks). For CI, use either:
 *   - GitHub Actions `ubuntu-22.04` runner with `enable-kvm` + `reactivecircus/android-emulator-runner`
 *     (hardware-accelerated emulator)
 *   - Firebase Test Lab (`gcloud firebase test android run …`) on real devices
 *   - Or simply `connectedDebugAndroidTest` against a tethered Pixel during local dev
 *
 * **Goldens**: PNGs in `samples/android-demo/src/androidTest/assets/render-goldens/`.
 * On first run (no golden), the captured image is saved for promotion and the test is
 * `assumeTrue`-skipped — re-run to verify. That skip is available ONLY to a golden that
 * has never been baselined: once a name is listed in [BASELINED_GOLDENS], a missing
 * asset is a hard failure, so deleting a baseline cannot turn its case green by absence.
 *
 * **Diff images**: when a comparison fails, the diff image is written with failing
 * pixels highlighted in red — to `/sdcard/Download/SceneView/test-captures` when that
 * is writable (it survives AGP's post-test uninstall), otherwise to
 * `getExternalFilesDir("render-test-output")`. Every failure message states the path it
 * actually used. Pull via `adb pull` for review.
 */
@RunWith(AndroidJUnit4::class)
class DemoRenderingScreenshotTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Wake + unlock so the activity actually renders.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // Pin the UI to light mode. The demo chrome (app bar, controls panel, background)
        // follows the system theme, so a device left in dark mode produces a capture that
        // differs from a light-mode golden on ~50% of its pixels — a full-suite red that
        // says nothing about rendering. The goldens are recorded light; the harness now
        // enforces it instead of inheriting whatever the device happened to be set to.
        device.executeShellCommand("cmd uimode night no")
    }

    @After
    fun tearDown() {
        // Give the device its theme back — this suite is not the only thing running on it.
        device.executeShellCommand("cmd uimode night auto")
    }

    @Test
    fun geometryDemo_default_state() {
        // 6 s settle: 2 s for Filament Engine init (FEngine first-create takes ~1.5 s on
        // emulator GLES 3.0 backend) + 4 s for Compose composition + scene materialization.
        // Real Pixel 9 only needs 3 s but a longer wait is harmless and keeps emulator
        // captures non-flaky. Same applies to the other settle bumps below.
        captureAndCompare(demoSlug = "geometry", goldenName = "geometry_default", settleSeconds = 6)
    }

    // #2239 Batch 5 — `multi-model` and `scene-gallery` consolidated into the existing
    // `model-viewer` entry (covered by `modelViewerDemo_default_state` below). The
    // Multi-Model / Gallery sub-modes are reachable via segmented-button taps; dedicated
    // sub-mode captures would need a tab-aware deep-link parameter (follow-up). The stale
    // `multimodel_default.png` golden + its `multiModelDemo_default_state` test (which
    // launched the now-retired `multi-model` slug) were removed.

    @Test
    fun animationPhysicsDemo_default_state() {
        // #2239 Batch 3 — `animation` and `physics` consolidated into
        // `animation-physics`. Default landing tab is Animation (the skeletal
        // playback scene), so the captured frame is comparable to the prior
        // `animation_default` golden once re-baselined. The Physics sub-mode is
        // reachable via a segmented-button tap but covered only via
        // DemoInteractionTest; a dedicated physics capture would need a tab-aware
        // deep-link parameter (follow-up).
        // TODO(qaMode-bind-pose): same root cause as multimodel — `stopAnimation()` only
        // pauses playback, it doesn't reset bones to the bind pose, so subsequent
        // `animator.updateBoneMatrices()` calls write whatever frame the animator was
        // last on. The Animation tab's `LaunchedEffect(qaMode) { stopAnimation(i) }` helps
        // (~22 % diff vs ~50 % without), but a true bind-pose freeze needs an explicit
        // `animator.applyAnimation(0, 0f)` call from inside ModelNodeImpl.
        captureAndCompare(demoSlug = "animation-physics", goldenName = "animationphysics_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 30.0f, maxChannelDiff = 32)
    }

    @Test
    fun lightingDemo_default_state() {
        // The light-probe contribution is keyed off the helmet's bounding box, which
        // depends on async glb load timing — visible difference between cold and warm
        // cache runs. 25 % covers cold + warm; tighten when the demo loads
        // synchronously or pre-warms.
        // 14 s, not 3: at 3 s the camera is still mid-approach and the lit plane sits
        // half off-frame, so the capture is a real render of the WRONG moment — the
        // content probe cannot catch that, only the settle budget can. Measured stable
        // from ~12 s on emulator-5554.
        captureAndCompare(demoSlug = "lighting", goldenName = "lighting_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 25.0f, maxChannelDiff = 32)
    }

    @Test
    fun modelViewerDemo_default_state() {
        captureAndCompare(demoSlug = "model-viewer", goldenName = "modelviewer_default", settleSeconds = 14)
    }

    @Test
    fun twoDInThreeDDemo_default_state() {
        // #3424 rebuilt this demo around `ViewNode` Compose cards on a turntable, so the
        // old golden (three `TextNode` labels) was deleted with the scene it depicted and
        // the slug was taken out of BASELINED_GOLDENS. Until a fresh capture is promoted,
        // this case takes the documented first-run path: save and skip.
        //
        // 14 s settle, not 3: the demo loads a 2 048² PBR GLB and a studio HDR, and each of the
        // four `ViewNode`s then needs several more frames for its off-screen `ComposeView` to
        // draw into a `SurfaceTexture` that starts out empty. Measured on the shared AVD, the
        // scene took ~12 s from deep link to a settled frame with all four cards present; a
        // shorter wait captures a black or half-populated scene.
        captureAndCompare(demoSlug = "two-d-in-three-d", goldenName = "twodinthreed_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 8.0f, maxChannelDiff = 16)
    }

    @Test
    fun linesPathsDemo_default_state() {
        captureAndCompare(demoSlug = "lines-paths", goldenName = "linespaths_default", settleSeconds = 3)
    }

    // #2239 Batch 1 — `shape` (and `custom-mesh`) consolidated into `custom-geometry`
    // (covered by `customGeometryDemo_default_state` below). The Shape Extrude sub-mode
    // is reachable via the "Shape" segmented button; a dedicated sub-mode capture would
    // need a tab-aware deep-link parameter (follow-up). The old `shapeDemo_default_state`
    // test was removed: it launched the retired `shape` slug (aliased to `custom-geometry`)
    // against the deleted `shape_default` golden, so it only ever silently skipped.

    @Test
    fun pickingCollisionDemo_default_state() {
        // #2239 Batch 1 — `collision` and `view-node` consolidated into `picking-collision`.
        // The default landing tab is Ray Hit-Test, so the captured frame is comparable to
        // the prior `collision_default` golden once it is re-baselined.
        captureAndCompare(demoSlug = "picking-collision", goldenName = "pickingcollision_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 8.0f, maxChannelDiff = 16)
    }

    @Test
    fun fogDemo_default_state() {
        captureAndCompare(demoSlug = "fog", goldenName = "fog_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 8.0f, maxChannelDiff = 16)
    }

    @Test
    fun lightingLabDemo_default_state() {
        // #2239 Batch 2 — `dynamic-sky`, `environment`, `reflection-probes`, and
        // `post-processing` consolidated into `lighting-lab`. Default landing tab is
        // Sky (the dynamic-sky scene), so the captured frame is comparable to the
        // prior `dynamicsky_default` golden once re-baselined. The Environment /
        // Reflections / Post-FX sub-modes are reachable via segmented-button taps but
        // covered only via DemoInteractionTest; dedicated screenshot captures would
        // need a tab-aware deep-link parameter (follow-up). The procedural-sky shader
        // has very high gradient sensitivity around the horizon, so TAA jitter bleeds
        // into entire pixel rows along the sun band — 15 % handles cold + warm runs.
        captureAndCompare(demoSlug = "lighting-lab", goldenName = "lightinglab_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 15.0f, maxChannelDiff = 24)
    }

    @Test
    fun materialsDemo_default_state() {
        // #2239 Batch 4 — `texture-streaming` and `occlusion-material` consolidated into
        // the existing `materials` entry. Default landing tab is PBR Materials, which
        // streams a Sketchfab CC-BY model and falls back to a bundled asset offline; the
        // Streaming / Occlusion sub-modes are reachable via segmented-button taps but
        // covered only via DemoInteractionTest (a dedicated capture would need a tab-aware
        // deep-link parameter — follow-up). The streamed model resolves asynchronously and
        // the studio HDR IBL has cold/warm cache variance, so a generous tolerance handles
        // both; the test still catches "nothing rendered at all".
        captureAndCompare(demoSlug = "materials", goldenName = "materials_default", settleSeconds = 14,
            pixelDiffTolerancePercent = 15.0f, maxChannelDiff = 24)
    }

    // #2239 Batch 1 — `billboard` consolidated into `two-d-in-three-d`, whose #3424 rebuild
    // then dropped the segmented tabs entirely. There is one scene now, covered by
    // `twoDInThreeDDemo_default_state` above.

    // #2239 Batch 1 — `view-node` consolidated into `picking-collision` (covered by
    // `pickingCollisionDemo_default_state` above). The View Node sub-mode is reachable
    // by tapping the "View Node" segmented button, but the renderer-screenshot test
    // covers the default landing tab only; a dedicated sub-mode capture would need
    // a tab-aware deep-link parameter (follow-up).

    @Test
    fun debugOverlayDemo_default_state() {
        captureAndCompare(demoSlug = "debug-overlay", goldenName = "debugoverlay_default", settleSeconds = 3)
    }

    // #2239 Batch 1 — `gesture-editing` consolidated into `camera-gestures` (covered
    // by `cameraAndGesturesDemo_default_state` below). The Node Gestures sub-mode is
    // reachable via the "Node Gestures" segmented button; a dedicated capture would
    // need a tab-aware deep-link parameter (follow-up).

    // #2239 Batch 2 — `dynamic-sky`, `environment`, `reflection-probes`, and
    // `post-processing` consolidated into `lighting-lab` (covered by
    // `lightingLabDemo_default_state` above). The Environment / Reflections / Post-FX
    // sub-modes are reachable via segmented-button taps; dedicated sub-mode captures
    // would need a tab-aware deep-link parameter (follow-up).

    // #2239 Batch 4 — `texture-streaming` and `occlusion-material` consolidated into
    // `materials` (covered by `materialsDemo_default_state` above). The Streaming /
    // Occlusion sub-modes are reachable via segmented-button taps; dedicated sub-mode
    // captures would need a tab-aware deep-link parameter (follow-up).

    @Test
    fun cameraAndGesturesDemo_default_state() {
        // #2239 Batch 1 — `camera-controls` and `gesture-editing` consolidated into
        // `camera-gestures`. Default landing tab is Camera Modes; the captured frame
        // is comparable to the prior `cameracontrols_default` golden once re-baselined.
        captureAndCompare(demoSlug = "camera-gestures", goldenName = "cameragestures_default", settleSeconds = 14)
    }

    @Test
    fun secondaryCameraDemo_default_state() {
        // 14 s, not 4. This demo is the one case the content probe cannot defend: the PiP
        // inset draws a light 1 px frame inside the viewport, so an entirely unrendered
        // scene still scores well above the flat-viewport threshold. The probe stays a
        // floor against #2323's all-black baselines; the settle budget is what makes the
        // capture right. Measured: model present from ~12 s, absent at 4 s.
        captureAndCompare(demoSlug = "secondary-camera", goldenName = "secondarycamera_default", settleSeconds = 14)
    }

    @Test
    fun customGeometryDemo_default_state() {
        // #2239 Batch 1 — `custom-mesh` and `shape` consolidated into `custom-geometry`.
        // The deep-link alias keeps the old slug routable; the default tab is Custom Mesh
        // so the captured frame is comparable to the prior `custommesh_default` golden.
        captureAndCompare(demoSlug = "custom-geometry", goldenName = "customgeometry_default", settleSeconds = 3)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Launches a demo via deep-link, waits for it to settle, captures the screen via
     * UiAutomator, compares to the named golden under `androidTest/assets/render-goldens/`.
     *
     * On first run (no golden present): writes the capture as the new golden and skips.
     */
    /**
     * Per-test tolerance overrides for emulator-rendered captures.
     *
     * The default 2 % pixel tolerance with 8 channel diff is tight enough to catch a
     * regression where Filament fails to render an object, but too tight for emulator
     * captures that include any motion (TAA jitter, animation playback, model fade-in).
     * Demos with significant remaining motion at capture time need a much wider window
     * — these are tracked here with a TODO so we can tighten them once `qaMode` properly
     * freezes every per-demo animation source.
     *
     * `pixelDiffTolerancePercent` is a hard cap on the % of pixels allowed to differ.
     * `maxChannelDiff` is the per-pixel R/G/B max delta before a pixel counts as a diff.
     */
    private fun captureAndCompare(
        demoSlug: String, goldenName: String, settleSeconds: Int,
        pixelDiffTolerancePercent: Float = 2.0f,
        maxChannelDiff: Int = 8,
    ) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Wake + unlock before each test — the device's screen-off timeout can fire
        // between tests in a long suite, leaving the demo running but the screen black.
        // Capturing then yields a screenshot of the lockscreen, not the demo.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")

        // The slug is interpolated into a string that a shell interprets, so it is
        // validated rather than quoted. Quoting was tried first and is WRONG here:
        // `UiDevice.executeShellCommand` does not give the argument full shell-quoting
        // semantics, and the quotes ended up inside the extra — every one of the 14
        // demos then launched to the demo LIST, and the suite failed with "never
        // composed". A whitelist is both correct and stricter: today every caller passes
        // a constant, but the tab-aware deep-link parameter noted below is exactly the
        // change that would start sourcing a slug from a Gradle property or a
        // parameterised test.
        require(demoSlug.matches(DEMO_SLUG_PATTERN)) {
            "Refusing to build a shell command from slug '$demoSlug': demo slugs must " +
                "match ${DEMO_SLUG_PATTERN.pattern}."
        }

        // Launch the demo with qa_mode = true so spin loops, scene rotation, and
        // cinematic camera scripts freeze at deterministic values
        // (DemoMath.nextSpinDegrees pinned, rememberHeroYaw → staticYaw, AnimationDemo
        // `if (DemoSettings.qaMode)` early-return). Without qa_mode the captured frame
        // would differ on every run because the scene is in continuous motion.
        //
        // We use FLAG_ACTIVITY_CLEAR_TOP + FLAG_ACTIVITY_NEW_TASK so the second-and-onward
        // launches get a fresh demo activity (the slug + qa_mode flag take effect via
        // onNewIntent). `am force-stop` is rejected from instrumentation context with
        // "Calling from not trusted UID!" so we don't use it.
        device.executeShellCommand(
            "am start -n io.github.sceneview.demo/.MainActivity " +
                "-f 0x14000000 " + // CLEAR_TOP | NEW_TASK
                "--es demo $demoSlug --ez qa_mode true"
        )
        // Wait for the demo screen itself to be composed before anything else. Without this
        // the capture loop below can settle on the launcher SPLASH screen: the splash draws
        // the SceneView app icon on a dark field, which is "non-flat pixels" as far as the
        // content probe is concerned, so the probe breaks out happily and the golden ends up
        // being a picture of the app icon (#2323 — every re-record produced the identical
        // 52 704-byte splash for five different demos, MD5-identical across all of them).
        //
        // The QA badge is the positive cue: it is rendered by the demo screen's top bar and
        // only when `qa_mode` is set, so it cannot appear on the splash, on the demo list,
        // or on a leftover previous screen. `Navigate back` was rejected as the cue — it is
        // present on whatever screen preceded the launch, so it reads as "ready" a frame too
        // early. Measured on all 14 demo slugs: badge absent on the splash, present once the
        // demo is composed.
        // Match the pill's full text, not the bare word: "QA" alone appears in demo copy
        // and control labels, and a substring that loose would report "composed" on the
        // wrong screen. The pill is `" QA ×"` in DemoScaffold — its testTag is not
        // reachable from UiAutomator (the app does not set `testTagsAsResourceId`), so
        // the visible text is the cue. Renaming the pill breaks this wait loudly, by
        // timeout, which is the failure mode we want.
        val demoComposed = device.wait(Until.hasObject(By.textContains(QA_PILL_TEXT)), DEMO_COMPOSE_TIMEOUT_MS)
        assertTrue(
            "Demo '$demoSlug' never composed: the qa_mode badge never appeared within " +
                "${DEMO_COMPOSE_TIMEOUT_MS}ms, so the screen on display is not the demo. " +
                "Capturing here would bake the splash screen into $goldenName.",
            demoComposed,
        )

        // Wait for first frame + animation settle. Demos that load models or HDR need more.
        // We poll the SceneView center for non-flat pixels (model loading is async; fixed
        // sleep would either be too short for model demos or wastefully long for procedural
        // ones). `settleSeconds` is the MINIMUM wait — we keep polling up to MAX_SETTLE_MS
        // beyond it for content to appear.
        Thread.sleep(settleSeconds * 1000L)

        val captured = File(targetContext.cacheDir, "render-capture-$goldenName.png")
        var capturedBitmap: Bitmap? = null
        var settled = false
        val pollDeadline = System.currentTimeMillis() + MAX_SETTLE_MS
        while (System.currentTimeMillis() < pollDeadline) {
            val ok = device.takeScreenshot(captured)
            if (!ok) { Thread.sleep(POLL_INTERVAL_MS); continue }
            val bmp = BitmapFactory.decodeFile(captured.absolutePath) ?: continue
            capturedBitmap = bmp // keep latest so a timeout still has something to report on
            if (hasRenderedContent(bmp)) {
                settled = true
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val rawCapture = capturedBitmap
            ?: throw AssertionError("UiAutomator screenshot capture failed entirely for $goldenName")
        // Timing out used to fall through and capture "whatever's on screen" — which is how
        // an unrendered black viewport becomes a committed golden. The scene either rendered
        // within the settle budget or this run has nothing worth comparing (#2323).
        if (!settled) {
            val savedTo = saveToDeviceForReview(rawCapture, "${goldenName}_never_settled")
            throw AssertionError(
                "Demo '$demoSlug' never rendered anything: its SceneView band stayed flat for " +
                    "${settleSeconds}s + ${MAX_SETTLE_MS}ms of polling. Either the demo is broken " +
                    "or the device is too slow for this budget — capture saved to $savedTo. " +
                    "Refusing to capture or compare an empty viewport.",
            )
        }
        // Crop the system status bar overlay before saving + comparing. UiAutomator's
        // `takeScreenshot` returns the FULL composited frame including the system bars
        // — clock, wifi/cellular, battery, notification icons, weather — which would
        // bake user PII into the golden if anyone re-records on a real phone (same root
        // cause as the leak retracted in 55f183c3 last session). The Pixel-class status
        // bar takes the top 96 px on a 1080×2400 viewport; cropping it away makes the
        // capture invariant across device locales, time of day, and notification state.
        val capturedBitmapNN = if (rawCapture.height > STATUS_BAR_PX) {
            Bitmap.createBitmap(
                rawCapture, 0, STATUS_BAR_PX,
                rawCapture.width, rawCapture.height - STATUS_BAR_PX,
            )
        } else rawCapture

        // Try to load the golden. If absent, this is first-run setup: save the capture as
        // the new golden and skip the test (re-run to verify).
        val goldenAsset = "render-goldens/$goldenName.png"
        val golden = runCatching {
            testContext.assets.open(goldenAsset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        // A committed golden that shows an empty SceneView is worse than no golden at all:
        // it freezes "nothing rendered" into the baseline, so the very regression this
        // suite exists to catch becomes the expected result (#2323 — four goldens were
        // committed as 320×544 all-black captures from a mis-sized AVD, and three more
        // (fog, lighting, lines-paths) as full-size frames whose viewport never rendered).
        // Separation is measured, not guessed — see MIN_CONTENT_SPREAD.
        if (golden != null && !hasRenderedContent(golden)) {
            throw AssertionError(
                "Golden $goldenAsset is DEGENERATE — its SceneView band is a flat colour " +
                    "(${golden.width}x${golden.height}), i.e. the baseline captured an " +
                    "empty viewport. Re-record it on a 1080x2400-class device with the " +
                    "demo fully settled; never commit a capture whose scene never rendered.",
            )
        }

        if (golden == null) {
            // First-run path: save the capture so it can be promoted to the golden.
            val savedTo = saveToDeviceForReview(capturedBitmapNN, "${goldenName}_first_run")
            // A slug that HAS a committed baseline must never silently lose its
            // protection (#2323): a missing golden there means the asset was
            // deleted/renamed — FAIL loudly instead of assume-skipping. Genuinely
            // new slugs keep the quiet first-run capture flow.
            if (goldenName in BASELINED_GOLDENS) {
                throw AssertionError(
                    "Golden $goldenAsset is EXPECTED (slug is in BASELINED_GOLDENS) but " +
                        "missing from the test assets — was it deleted or renamed? " +
                        "Fresh capture saved to $savedTo for re-baselining if intentional.",
                )
            }
            assumeTrue(
                "No golden at $goldenAsset — capture saved to $savedTo. " +
                    "Pull via adb, review, then commit as the new golden:\n" +
                    "  adb pull $savedTo samples/android-demo/src/androidTest/assets/$goldenAsset",
                false,
            )
            return
        }

        val result = compare(capturedBitmapNN, golden, maxChannelDiff, pixelDiffTolerancePercent)
        if (!result.passed) {
            // Save diff image for review.
            result.diff?.let { saveToDeviceForReview(it, "${goldenName}_diff") }
            saveToDeviceForReview(capturedBitmapNN, "${goldenName}_actual")
        }
        assertTrue(result.message, result.passed)
    }

    private data class Result(val passed: Boolean, val message: String, val diff: Bitmap?)

    /**
     * Per-channel diff with tolerance. Default 8/255 max channel diff, 2% pixels allowed
     * to fail — accommodates anti-aliasing / fp drift between identical renders on the
     * same GPU. Tighten per-test if a particular demo is more deterministic.
     */
    private fun compare(
        rendered: Bitmap, golden: Bitmap,
        maxChannelDiff: Int = 8, maxFailPercent: Float = 2.0f,
    ): Result {
        if (rendered.width != golden.width || rendered.height != golden.height) {
            return Result(
                passed = false,
                message = "Size mismatch: rendered=${rendered.width}x${rendered.height}, " +
                    "golden=${golden.width}x${golden.height}",
                diff = null,
            )
        }
        val w = rendered.width; val h = rendered.height
        val total = w * h
        // Bulk-extract via getPixels(IntArray, …) instead of per-pixel getPixel(): each
        // getPixel/setPixel is a JNI roundtrip (~µs each), and 1080×2304 = 2.5 M pixels
        // would push ~5 s of JNI overhead per test. Bulk transfer drops that to ~50 ms.
        val rendPx = IntArray(total).also { rendered.getPixels(it, 0, w, 0, 0, w, h) }
        val goldPx = IntArray(total).also { golden.getPixels(it, 0, w, 0, 0, w, h) }
        val diffPx = IntArray(total)
        var failing = 0; var maxDiff = 0
        for (i in 0 until total) {
            val rp = rendPx[i]; val gp = goldPx[i]
            val dr = abs(((rp shr 16) and 0xFF) - ((gp shr 16) and 0xFF))
            val dg = abs(((rp shr 8) and 0xFF) - ((gp shr 8) and 0xFF))
            val db = abs((rp and 0xFF) - (gp and 0xFF))
            val cmax = maxOf(dr, dg, db)
            diffPx[i] = if (cmax > maxChannelDiff) {
                failing++
                // Red, with intensity proportional to the per-pixel diff magnitude.
                (0xFF shl 24) or (cmax.coerceIn(50, 255) shl 16)
            } else {
                // Dim green for "within tolerance".
                (0xFF shl 24) or (30 shl 8)
            }
            if (cmax > maxDiff) maxDiff = cmax
        }
        val diff = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(diffPx, 0, w, 0, 0, w, h)
        }
        val pct = failing * 100f / total
        val passed = pct <= maxFailPercent
        val msg = if (passed) "OK — $failing px (${"%.2f".format(pct)}%), max=$maxDiff"
                  else "FAIL — $failing/$total px (${"%.2f".format(pct)}% > ${maxFailPercent}%), max=$maxDiff (>$maxChannelDiff)"
        return Result(passed, msg, if (passed) null else diff)
    }

    /**
     * Best-effort debug artifact dump. Returns where the image landed, or a human-readable
     * reason if it could not be written anywhere.
     *
     * This must NEVER throw. It used to: the public Downloads dir it writes to is created
     * by `mkdir` running as `shell`, so the app — under scoped storage — cannot always
     * write into it, and the resulting `FileNotFoundException: EACCES` propagated out of
     * the failure path and REPLACED the assertion that was being reported. Six real
     * verdicts (three golden mismatches, three missing baselines) surfaced as filesystem
     * errors instead. A debugging convenience that can mask the verdict is worse than no
     * debugging convenience (#2323).
     */
    private fun saveToDeviceForReview(bitmap: Bitmap, name: String): String {
        // Public Downloads dir survives AGP's post-test uninstall — see the matching block
        // in `ARDemoPlaybackSmokeTest.saveToDeviceForReview`. App-private external storage
        // is the fallback: always writable, but wiped with the app.
        val candidates = listOf(
            File("/sdcard/Download/SceneView/test-captures"),
            File(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null),
                "render-test-output",
            ),
        )
        val failures = mutableListOf<String>()
        for (dir in candidates) {
            val file = File(dir, "$name.png")
            val saved = runCatching {
                dir.mkdirs()
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (saved.isSuccess) return file.absolutePath
            failures += "${file.absolutePath} (${saved.exceptionOrNull()?.message})"
        }
        return "<not saved: ${failures.joinToString("; ")}>"
    }

    /**
     * Samples the whole SceneView vertical band (between status/title bar at the top and
     * the controls panel at the bottom) and reports whether it contains any non-flat
     * pixels. Used during settle polling so demos that load models async aren't captured
     * while the SceneView is still solid black — and again on the loaded golden, so a
     * baseline that froze an empty viewport is rejected instead of trusted.
     *
     * Why the wide band: each demo positions its scene differently — `geometry` puts the
     * primitives at upper-third, `modelviewer` at the middle, `text` extends top-to-bottom.
     * A small centre crop misses the upper-third demos. We sample from 15 % to 50 % of the
     * height, which covers the full SceneView area on the Pixel_7a AVD's 1080x2400 viewport
     * and keeps meaning the same band on any other geometry.
     *
     * The band is expressed in FRACTIONS, and there is deliberately no "unexpected
     * geometry → trust it" escape hatch: the previous absolute y=200..1300 window came
     * with `if (width < 1000) return true`, which is exactly how four all-black 320×544
     * captures were blessed and committed as goldens (#2323). A probe that exempts the
     * inputs it cannot measure asserts nothing about them.
     *
     * The threshold is [MIN_CONTENT_SPREAD] — measured, not guessed. It covers the case
     * where a single coloured object is rendered against the dark SceneView background,
     * while a fully un-rendered SceneView (which we want to keep polling past) reports 0-1.
     */
    private fun hasRenderedContent(bmp: Bitmap): Boolean {
        val x0 = 0
        val x1 = bmp.width
        // Strictly INSIDE the SceneView viewport. The band used to start at 8%, which on a
        // 1080x2400 frame still clips the app bar — its dark title on a near-white background
        // scores a full 255 spread on its own, so a demo whose viewport never rendered still
        // read as "content". Three committed goldens (fog, lighting, lines-paths) are all-black
        // viewports that this exact off-by-a-band bug waved through (#2323).
        val y0 = (bmp.height * 15) / 100   // below the app bar, inside the viewport
        val y1 = (bmp.height * 50) / 100   // above the controls panel
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        val step = maxOf(1, bmp.width / 68)
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            val p = bmp.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        val spread = maxOf(maxR - minR, maxG - minG, maxB - minB)
        return spread >= MIN_CONTENT_SPREAD
    }

    private companion object {
        // Max time we'll keep retrying after the per-test minimum settle, waiting for
        // the SceneView centre to show non-flat pixels. Covers slow first-time CDN
        // model downloads (~10s on emulator) plus Filament Engine init drift.
        /**
         * Slugs whose golden IS committed under `androidTest/assets/render-goldens/` —
         * for these a missing golden is a hard FAILURE, not a first-run skip (#2323).
         * Add a slug here in the SAME commit that adds its golden PNG.
         */
        val BASELINED_GOLDENS = setOf(
            "animationphysics_default",
            "cameragestures_default",
            "customgeometry_default",
            "debugoverlay_default",
            "fog_default",
            "geometry_default",
            "lighting_default",
            "lightinglab_default",
            "linespaths_default",
            "materials_default",
            "modelviewer_default",
            "pickingcollision_default",
            "secondarycamera_default",
            // "twodinthreed_default" — deliberately NOT baselined right now. #3424 rebuilt
            // the demo from scratch (Compose `ViewNode` cards around an annotated model
            // replaced three `TextNode` labels), so the committed golden depicted a scene
            // that no longer exists and was deleted with it. The case above therefore takes
            // the first-run path: the next suite run saves a fresh capture for promotion and
            // skips. Put the slug back here in the SAME commit that adds the new PNG.
        )

        /**
         * Minimum per-channel spread, over the in-viewport band, for a frame to count as
         * "the scene rendered". Calibrated by measurement on emulator-5554 (1080x2400,
         * 14s settle) over all 14 demo slugs: an unrendered viewport scores 0-1, the
         * weakest real render (picking-collision) scores 213. 32 sits ~6x above the noise
         * and ~6x below the weakest signal, so neither population is near the boundary.
         */
        const val MIN_CONTENT_SPREAD = 32

        /** Exact text of `DemoScaffold`'s qa_mode pill — the positive cue that the demo composed. */
        const val QA_PILL_TEXT = "QA ×"

        /** Every demo slug in `DemoRegistry` is lower-kebab; nothing here reaches a shell unchecked. */
        val DEMO_SLUG_PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        const val MAX_SETTLE_MS = 25_000L

        // How long we allow the demo screen to compose after `am start` — covers a cold
        // app start (splash + dexopt) on the QA emulator, measured at ~3 s warm and up to
        // ~12 s on the first launch after an install.
        const val DEMO_COMPOSE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 1_000L

        // System status bar height on Pixel-class devices (1080×2400 portrait): 96 px.
        // We strip this band before saving + comparing so goldens are invariant across
        // device locale, clock, notification state, carrier, and battery level — and so
        // re-records on a real phone don't bake the contributor's PII into the asset.
        // 96 px is enough on standard Android 12-16 status bars; cropping a few extra
        // pixels of app-bar background is harmless because every demo has the same
        // light app-bar.
        const val STATUS_BAR_PX = 96
    }
}
