package io.github.sceneview.demo.ar

import android.content.Context
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
 * Replay-driven AR-demo smoke tests.
 *
 * For each fixture under `androidTest/assets/ar-recordings/`, this test launches the
 * `ARRecordPlaybackDemo` deep-link (`--es demo ar-record-playback`), copies the fixture
 * into the app's external files dir so the demo lists it as a recording, then asserts
 * the activity stays alive for `MIN_PLAYBACK_SECONDS`. Catches any catastrophic crash
 * (e.g. ARCore session init exception, missing manifest entry, Filament JNI failure)
 * that would tank the demo on every replay.
 *
 * **Adding more scenario coverage**: as `samples/android-demo/AR_TESTING.md` describes,
 * drop additional MP4s into `androidTest/assets/ar-recordings/` and they auto-enroll
 * via the [discoverFixtures] helper. Long-term goal: per-demo tests that mount
 * each AR demo composable with a fixture-specific playback file via a future
 * `playbackOverride` ctor param. This file is the scaffold those tests slot into.
 *
 * **No fixtures available**: the test is `assumeTrue`-skipped when no MP4s are present,
 * so the suite stays green on a fresh clone before anyone has captured a baseline. Once
 * a contributor records and commits the first fixture, the test starts running.
 */
@RunWith(AndroidJUnit4::class)
class ARDemoPlaybackSmokeTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Pre-grant runtime permissions: AGP reinstalls the demo APK before each test
        // class, so any prior `pm grant` is wiped. Without these, the AR demo blocks at
        // the camera-permission prompt and we capture the system dialog instead of the
        // ARSceneView playback.
        device.executeShellCommand("pm grant io.github.sceneview.demo android.permission.CAMERA")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test
    fun arRecordPlaybackDemo_replays_each_fixture_without_crash() {
        val fixtures = discoverFixtures()
        assumeTrue(
            "No AR recording fixtures in androidTest/assets/ar-recordings/. " +
                "Capture one on device and commit it — see samples/android-demo/AR_TESTING.md",
            fixtures.isNotEmpty(),
        )

        for (fixture in fixtures) {
            val deployed = deployFixtureToAppPrivateDir(fixture)
            try {
                // Pass the deployed fixture's path through the launch intent so the demo
                // auto-starts in Mode.PLAYBACK with this file pre-loaded — no UiAutomator
                // clicking through mode chips. See `DemoSettings.arPendingPlaybackFile`.
                launchDemo("ar-record-playback", playbackFile = deployed.absolutePath)
                // Give the activity time to boot, the AR session to initialize, and
                // ARCore to consume at least the first few frames of the dataset.
                Thread.sleep(MIN_PLAYBACK_MILLIS)

                // Capture the rendered AR frame and compare to the per-fixture golden.
                // This is the actual visual regression check — anchors, planes, lighting,
                // model placements all replay deterministically from the MP4, so the
                // captured screen should match the golden modulo GPU fp drift.
                val goldenName = fixture.removeSuffix(".mp4")
                captureAndCompareAgainstGolden(goldenName)
            } finally {
                // Tear down: kill the demo activity so the next iteration starts clean.
                device.pressHome()
                deployed.delete()
            }
        }
    }

    /**
     * Takes a UiAutomator screenshot of the current screen, then compares it to
     * `androidTest/assets/ar-render-goldens/<goldenName>.png`. On first run (golden
     * absent), saves the capture to the device for promotion as the new golden.
     *
     * Tolerance: 8/255 max channel diff, 2% pixels allowed to fail. AR rendering
     * has more fp drift than 3D-only because depth + light estimation depend on
     * driver versions; loosen further per-fixture if a particular scene jitters.
     */
    private fun captureAndCompareAgainstGolden(goldenName: String) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = File(targetContext.cacheDir, "ar-capture-$goldenName.png")
        val ok = device.takeScreenshot(captured)
        assertTrue("UiAutomator screenshot capture must succeed for $goldenName", ok)
        val rawCapture = BitmapFactory.decodeFile(captured.absolutePath)
        assertNotNull("Captured bitmap must decode for $goldenName", rawCapture)
        // Strip the system status bar overlay (top 96 px) so re-records on a real device
        // don't bake clock / weather / notification icons into the AR golden. Same
        // mitigation as DemoRenderingScreenshotTest for the same root cause.
        val capturedBitmap = if (rawCapture.height > STATUS_BAR_PX) {
            Bitmap.createBitmap(
                rawCapture, 0, STATUS_BAR_PX,
                rawCapture.width, rawCapture.height - STATUS_BAR_PX,
            )
        } else rawCapture

        val goldenAsset = "ar-render-goldens/$goldenName.png"
        val golden = runCatching {
            testContext.assets.open(goldenAsset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        if (golden == null) {
            // First-run path — save as candidate golden and skip.
            val saved = saveToDeviceForReview(capturedBitmap, "${goldenName}_first_run")
            assumeTrue(
                "No AR golden at $goldenAsset — capture saved to $saved. " +
                    "Pull via adb, review, then commit:\n" +
                    "  adb pull $saved samples/android-demo/src/androidTest/assets/$goldenAsset",
                false,
            )
            return
        }

        val result = compareBitmaps(capturedBitmap, golden)
        if (!result.passed) {
            result.diff?.let { saveToDeviceForReview(it, "${goldenName}_diff") }
            saveToDeviceForReview(capturedBitmap, "${goldenName}_actual")
        }
        assertTrue("[$goldenName] ${result.message}", result.passed)
    }

    private data class CompareResult(val passed: Boolean, val message: String, val diff: Bitmap?)

    private fun compareBitmaps(
        rendered: Bitmap, golden: Bitmap,
        maxChannelDiff: Int = 8, maxFailPercent: Float = 2.0f,
    ): CompareResult {
        if (rendered.width != golden.width || rendered.height != golden.height) {
            return CompareResult(
                false,
                "Size mismatch: rendered=${rendered.width}x${rendered.height}, " +
                    "golden=${golden.width}x${golden.height}",
                null,
            )
        }
        val w = rendered.width; val h = rendered.height; val total = w * h
        // Bulk-extract pixel arrays (one JNI call each) instead of per-pixel
        // getPixel/setPixel which would JNI-roundtrip 2.5 M times for a 1080×2304 frame.
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
                (0xFF shl 24) or (cmax.coerceIn(50, 255) shl 16)
            } else {
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
                  else "FAIL — $failing/$total px (${"%.2f".format(pct)}%), max=$maxDiff"
        return CompareResult(passed, msg, if (passed) null else diff)
    }

    private fun saveToDeviceForReview(bitmap: Bitmap, name: String): File {
        // Write to a path that survives AGP's post-test uninstall: the public
        // Downloads/SceneView/test-captures directory. The target app's
        // `getExternalFilesDir(...)` would be wiped along with the app data when
        // `connectedDebugAndroidTest` cleans up, leaving the contributor with no way
        // to pull the first-run capture or the diff image.
        device.executeShellCommand("mkdir -p /sdcard/Download/SceneView/test-captures")
        val file = File("/sdcard/Download/SceneView/test-captures/$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Lists every `.mp4` under `androidTest/assets/ar-recordings/` (or returns an empty
     * list if the dir doesn't exist or has no MP4s yet). Sorted alphabetically so the
     * test order is deterministic.
     */
    private fun discoverFixtures(): List<String> {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val assetManager = testContext.assets
        return runCatching {
            assetManager.list("ar-recordings")
                ?.filter { it.endsWith(".mp4", ignoreCase = true) }
                ?.sorted()
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Copies a fixture from the test apk's assets into the app's external-files
     * `ar-recordings/` directory — the same path the demo's Recordings list scans.
     */
    private fun deployFixtureToAppPrivateDir(fixtureName: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetDir = context.getExternalFilesDir("ar-recordings")!!
        targetDir.mkdirs()
        val targetFile = File(targetDir, fixtureName)
        testContext.assets.open("ar-recordings/$fixtureName").use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        return targetFile
    }

    /**
     * Launches the demo activity via `am start --es demo <slug>` — the same deep-link path
     * that contributors use to QA demos manually. Uses UiAutomator's shell access (already
     * in `androidTestImplementation`) instead of pulling in `androidx.test:core` for
     * `ActivityScenario` — saves a dep and matches the existing test infrastructure.
     */
    private fun launchDemo(demoSlug: String, playbackFile: String? = null) {
        val playbackArg = playbackFile?.let { " --es ar_playback_file $it" } ?: ""
        device.executeShellCommand(
            "am start -n io.github.sceneview.demo/.MainActivity " +
                "-f 0x14000000 " + // CLEAR_TOP | NEW_TASK so onNewIntent fires for the second-and-onward fixture
                "--es demo $demoSlug$playbackArg"
        )
    }

    private companion object {
        /**
         * Time to leave the demo running before declaring it healthy. Empirically chosen:
         * long enough for ARCore to finish session init + consume the first few frames of
         * the dataset (~3–5 s is enough on Pixel 9 / 7a), but short enough that a 10-fixture
         * sweep stays under 90 s wall-clock for CI.
         */
        const val MIN_PLAYBACK_MILLIS = 6_000L

        /** System status bar height in pixels. See DemoRenderingScreenshotTest for rationale. */
        const val STATUS_BAR_PX = 96
    }
}
