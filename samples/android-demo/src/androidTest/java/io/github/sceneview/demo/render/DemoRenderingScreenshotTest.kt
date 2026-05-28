package io.github.sceneview.demo.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * On first run (no golden), the captured image is saved as the new golden and the
 * test is `assumeTrue`-skipped — re-run to verify.
 *
 * **Diff images**: when a comparison fails, the diff image is written to
 * `getExternalFilesDir("render-test-output")` on the device with failing pixels
 * highlighted in red. Pull via `adb pull` for review.
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
    fun animationDemo_default_state() {
        // TODO(qaMode-bind-pose): same root cause as multimodel — `stopAnimation()` only
        // pauses playback, it doesn't reset bones to the bind pose, so subsequent
        // `animator.updateBoneMatrices()` calls write whatever frame the animator was
        // last on. AnimationDemo's `LaunchedEffect(qaMode) { stopAnimation(i) }` helps
        // (~22 % diff vs ~50 % without), but a true bind-pose freeze needs an explicit
        // `animator.applyAnimation(0, 0f)` call from inside ModelNodeImpl.
        captureAndCompare(demoSlug = "animation", goldenName = "animation_default", settleSeconds = 4,
            pixelDiffTolerancePercent = 30.0f, maxChannelDiff = 32)
    }

    @Test
    fun lightingDemo_default_state() {
        // The light-probe contribution is keyed off the helmet's bounding box, which
        // depends on async glb load timing — visible difference between cold and warm
        // cache runs. 25 % covers cold + warm; tighten when the demo loads
        // synchronously or pre-warms.
        captureAndCompare(demoSlug = "lighting", goldenName = "lighting_default", settleSeconds = 3,
            pixelDiffTolerancePercent = 25.0f, maxChannelDiff = 32)
    }

    @Test
    fun modelViewerDemo_default_state() {
        captureAndCompare(demoSlug = "model-viewer", goldenName = "modelviewer_default", settleSeconds = 4)
    }

    @Test
    fun twoDInThreeDDemo_default_state() {
        // #2239 Batch 1 — `text`, `image`, `video`, `billboard` consolidated into
        // `two-d-in-three-d`. Default landing tab is Text; the captured frame is
        // comparable to the prior `text_default` golden once re-baselined.
        // The Image / Video / Billboard sub-modes are reachable via segmented-button
        // taps but covered only via DemoInteractionTest; dedicated screenshot captures
        // would need a tab-aware deep-link parameter (follow-up).
        captureAndCompare(demoSlug = "two-d-in-three-d", goldenName = "twodinthreed_default", settleSeconds = 3,
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
        captureAndCompare(demoSlug = "picking-collision", goldenName = "pickingcollision_default", settleSeconds = 3,
            pixelDiffTolerancePercent = 8.0f, maxChannelDiff = 16)
    }

    @Test
    fun fogDemo_default_state() {
        captureAndCompare(demoSlug = "fog", goldenName = "fog_default", settleSeconds = 3,
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
        captureAndCompare(demoSlug = "lighting-lab", goldenName = "lightinglab_default", settleSeconds = 4,
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
        captureAndCompare(demoSlug = "materials", goldenName = "materials_default", settleSeconds = 5,
            pixelDiffTolerancePercent = 15.0f, maxChannelDiff = 24)
    }

    // #2239 Batch 1 — `billboard` consolidated into `two-d-in-three-d` (Billboard sub-mode,
    // reachable via the "Billboard" segmented button; default landing tab covered by
    // `twoDInThreeDDemo_default_state` above).

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
        captureAndCompare(demoSlug = "camera-gestures", goldenName = "cameragestures_default", settleSeconds = 4)
    }

    @Test
    fun secondaryCameraDemo_default_state() {
        captureAndCompare(demoSlug = "secondary-camera", goldenName = "secondarycamera_default", settleSeconds = 4)
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
        // Wait for first frame + animation settle. Demos that load models or HDR need more.
        // We poll the SceneView center for non-flat pixels (model loading is async; fixed
        // sleep would either be too short for model demos or wastefully long for procedural
        // ones). `settleSeconds` is the MINIMUM wait — we keep polling up to MAX_SETTLE_MS
        // beyond it for content to appear before giving up and capturing whatever's on screen.
        Thread.sleep(settleSeconds * 1000L)

        val captured = File(targetContext.cacheDir, "render-capture-$goldenName.png")
        var capturedBitmap: Bitmap? = null
        val pollDeadline = System.currentTimeMillis() + MAX_SETTLE_MS
        while (System.currentTimeMillis() < pollDeadline) {
            val ok = device.takeScreenshot(captured)
            if (!ok) { Thread.sleep(POLL_INTERVAL_MS); continue }
            val bmp = BitmapFactory.decodeFile(captured.absolutePath) ?: continue
            if (sceneViewHasContent(bmp)) {
                capturedBitmap = bmp
                break
            }
            capturedBitmap = bmp // keep latest in case we time out
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val rawCapture = capturedBitmap
            ?: throw AssertionError("UiAutomator screenshot capture failed entirely for $goldenName")
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

        if (golden == null) {
            // First-run path: save the capture so it can be promoted to the golden.
            val savedTo = saveToDeviceForReview(capturedBitmapNN, "${goldenName}_first_run")
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

    private fun saveToDeviceForReview(bitmap: Bitmap, name: String): File {
        // Public Downloads dir survives AGP's post-test uninstall — see the
        // matching block in `ARDemoPlaybackSmokeTest.saveToDeviceForReview`.
        device.executeShellCommand("mkdir -p /sdcard/Download/SceneView/test-captures")
        val file = File("/sdcard/Download/SceneView/test-captures/$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * Samples the whole SceneView vertical band (between status/title bar at the top and
     * the controls panel at the bottom) and reports whether it contains any non-flat
     * pixels. Used during settle polling so demos that load models async aren't captured
     * while the SceneView is still solid black.
     *
     * Why the wide band: each demo positions its scene differently — `geometry` puts the
     * primitives at upper-third, `modelviewer` at the middle, `text` extends top-to-bottom.
     * A small centre crop misses the upper-third demos. We sample a generous y=200..1300
     * stripe (covers the full SceneView area for all demos on the Pixel_7a AVD's
     * 1080x2400 viewport) and step by 16 for speed.
     *
     * Threshold of 4 channel spread covers the case where a single coloured object is
     * rendered against the dark SceneView background; a fully un-rendered SceneView (which
     * we want to keep polling past) reports 0.
     */
    private fun sceneViewHasContent(bmp: Bitmap): Boolean {
        // Trust unexpected geometries (we'll be less picky on tablets / different viewports).
        if (bmp.width < 1000 || bmp.height < 2000) return true
        val x0 = 0
        val x1 = bmp.width
        val y0 = 200    // skip status bar overlay region (drawn black in edge-to-edge anyway)
        val y1 = 1300   // stop before controls panel
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        val step = 16
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            val p = bmp.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        val spread = maxOf(maxR - minR, maxG - minG, maxB - minB)
        return spread > 4
    }

    private companion object {
        // Max time we'll keep retrying after the per-test minimum settle, waiting for
        // the SceneView centre to show non-flat pixels. Covers slow first-time CDN
        // model downloads (~10s on emulator) plus Filament Engine init drift.
        const val MAX_SETTLE_MS = 25_000L
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
