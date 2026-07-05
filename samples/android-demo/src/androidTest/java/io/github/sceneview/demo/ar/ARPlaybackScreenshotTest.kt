package io.github.sceneview.demo.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * **Frame-indexed** AR screenshot regression pipeline (issue
 * [#1050](https://github.com/sceneview/sceneview/issues/1050)).
 *
 * Replays the bundled ARCore playback recording through `ARRecordPlaybackDemo`,
 * captures the rendered AR frame at **fixed ARCore frame indices** (`f=30 / 60 /
 * 120 / 180`), and compares each capture against a committed golden so AR-rendering
 * regressions — anchors drifting, planes failing to detect, light estimation
 * shifting colour temperature — are caught on every PR rather than on a release
 * build a user notices.
 *
 * ## Why frame-indexed, not time-based
 *
 * `ARDemoPlaybackSmokeTest` (its sibling) fires a single capture after a fixed
 * `Thread.sleep(...)`. That is fine as a crash smoke test, but a `sleep`-gated
 * capture lands on a different ARCore frame every run — replay advances faster
 * or slower than wall-clock depending on emulator load (#1050 "Why it's heavy"
 * §2). This test instead **polls** [io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount]
 * — a counter the demo bumps once per `onSessionUpdated` during playback — and
 * triggers each capture exactly when the target frame index is reached. Same
 * frame, every run, every machine.
 *
 * ## Determinism caveats (read before re-baselining)
 *
 * - **GPU divergence.** Goldens captured on a `hw.gpu.mode = host` developer
 *   emulator will differ from CI's headless `swiftshader_indirect`. Capture
 *   `--record` goldens on the **same emulator config** the CI matrix pins —
 *   see `samples/android-demo/src/androidTest/assets/ar-screenshot-goldens/README.md`.
 * - **Tolerance.** AR rendering carries more fp drift than 3D-only (depth +
 *   light estimation depend on driver versions), so the comparator allows an
 *   8/255 max channel diff and 3 % failing pixels. Loosen per-frame if a
 *   particular frame index jitters.
 *
 * ## First-time golden capture
 *
 * On a fresh checkout the `ar-screenshot-goldens/` dir has no PNGs, so every
 * frame's comparison takes the **record path**: the capture is written to
 * `/sdcard/Download/SceneView/test-captures/` and the assertion is `assumeTrue`-
 * skipped (suite stays green). Pull the captures, review them, and commit them
 * as the goldens — full flow in that dir's README.
 *
 * ## Requirements
 *
 * - A connected device or **hardware-accelerated** emulator — Filament pixel
 *   readback crashes on SwiftShader.
 * - Google Play Services for AR installed (the bundled recording still needs
 *   the ARCore runtime to drive playback). See `AR_TESTING.md` for the
 *   emulator sideload command.
 *
 * The test is `assumeTrue`-skipped — never failed — when the bundled recording
 * is absent, so a sparse checkout that excludes the debug-asset MP4 stays green.
 */
@RunWith(AndroidJUnit4::class)
class ARPlaybackScreenshotTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Pre-grant runtime permissions: AGP reinstalls the demo APK before each test
        // class, wiping any prior `pm grant`. Without CAMERA the AR demo blocks at the
        // permission prompt and we would screenshot the system dialog.
        device.executeShellCommand("pm grant io.github.sceneview.demo android.permission.CAMERA")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test
    fun bundledRecording_capturesGoldenFramesAtFixedIndices() {
        val fixture = locateBundledRecording()
        assumeTrue(
            "Bundled ARCore recording not found in the demo APK assets " +
                "($BUNDLED_RECORDING). It ships in the `debug` sourceSet " +
                "(samples/android-demo/src/debug/assets/ar-recordings/) — run the " +
                "test against a debug build, or `git sparse-checkout add` that path.",
            fixture != null,
        )
        val deployed = deployFixtureToAppPrivateDir(fixture!!)
        try {
            // Auto-start the demo in Mode.PLAYBACK with the bundled recording
            // pre-loaded — same deep-link path ARDemoPlaybackSmokeTest uses.
            launchPlaybackDemo(deployed.absolutePath)

            // The demo zeroes DemoSettings.arPlaybackFrameCount on mount; wait for
            // the first frame to confirm playback actually started before gating.
            val started = waitForFrameIndex(minIndex = 1, timeoutMillis = SESSION_INIT_TIMEOUT_MILLIS)
            assumeTrue(
                "ARCore playback never advanced past frame 0 within " +
                    "${SESSION_INIT_TIMEOUT_MILLIS}ms — Google Play Services for AR " +
                    "is likely not installed on this device/emulator. See AR_TESTING.md.",
                started,
            )

            // Walk the ascending target indices, capturing each as the counter
            // crosses it. Ascending order means one monotonic forward poll.
            for (frameIndex in GOLDEN_FRAME_INDICES) {
                val reached = waitForFrameIndex(
                    minIndex = frameIndex,
                    timeoutMillis = FRAME_ADVANCE_TIMEOUT_MILLIS,
                )
                assumeTrue(
                    "Playback ended before reaching frame $frameIndex — the bundled " +
                        "recording is shorter than expected, or replay stalled.",
                    reached,
                )
                captureAndCompareAgainstGolden("bundled-pixel9-f$frameIndex")
            }
        } finally {
            device.pressHome()
            deployed.delete()
        }
    }

    // ── Frame-counter polling ───────────────────────────────────────────────

    /**
     * Blocks until [io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount]
     * reaches [minIndex], or [timeoutMillis] elapses. Returns `true` if the index
     * was reached.
     *
     * The counter lives in the **target app's** process, so the instrumentation
     * thread reads it through the loaded `DemoSettings` class (instrumentation
     * runs in-process with the app for `connectedDebugAndroidTest`). The field is
     * `@Volatile`, so each poll observes the latest value written by the AR
     * session callback.
     */
    private fun waitForFrameIndex(minIndex: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount >= minIndex) {
                return true
            }
            Thread.sleep(FRAME_POLL_INTERVAL_MILLIS)
        }
        return io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount >= minIndex
    }

    // ── Capture + golden compare ────────────────────────────────────────────

    /**
     * Screenshots the current screen and compares it to
     * `androidTest/assets/ar-screenshot-goldens/<goldenName>.png`. On the
     * first run (golden absent) the capture is saved to the device and the
     * assertion is `assumeTrue`-skipped for promotion as the new golden.
     */
    private fun captureAndCompareAgainstGolden(goldenName: String) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = File(targetContext.cacheDir, "ar-screenshot-$goldenName.png")
        val ok = device.takeScreenshot(captured)
        assertTrue("UiAutomator screenshot capture must succeed for $goldenName", ok)
        val rawCapture = BitmapFactory.decodeFile(captured.absolutePath)
        assertNotNull("Captured bitmap must decode for $goldenName", rawCapture)
        // Strip the system status bar (top STATUS_BAR_PX) so a re-record on a real
        // device doesn't bake clock / notification icons into the golden — same
        // mitigation as ARDemoPlaybackSmokeTest / DemoRenderingScreenshotTest.
        val capturedBitmap = if (rawCapture.height > STATUS_BAR_PX) {
            Bitmap.createBitmap(
                rawCapture, 0, STATUS_BAR_PX,
                rawCapture.width, rawCapture.height - STATUS_BAR_PX,
            )
        } else rawCapture

        val goldenAsset = "ar-screenshot-goldens/$goldenName.png"
        val golden = runCatching {
            testContext.assets.open(goldenAsset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        if (golden == null) {
            val saved = saveToDeviceForReview(capturedBitmap, "${goldenName}_first_run")
            assumeTrue(
                "No AR screenshot golden at $goldenAsset — capture saved to $saved. " +
                    "Pull via adb, review, then commit:\n" +
                    "  adb pull $saved " +
                    "samples/android-demo/src/androidTest/assets/$goldenAsset",
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
        maxChannelDiff: Int = 8, maxFailPercent: Float = 3.0f,
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
        // Bulk pixel extraction — one JNI call each, not 2.5 M getPixel round-trips.
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
        // Public Downloads survives AGP's post-test app uninstall.
        device.executeShellCommand("mkdir -p /sdcard/Download/SceneView/test-captures")
        val file = File("/sdcard/Download/SceneView/test-captures/$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    // ── Fixture plumbing ────────────────────────────────────────────────────

    /**
     * Locates the bundled ARCore recording. It ships in the demo's `debug`
     * sourceSet (`src/debug/assets/ar-recordings/`), which merges into the
     * **debug app APK** — so it is read through the *target* app's asset
     * manager, not the test APK's. Returns `null` if the file is absent (e.g.
     * a release build, or a sparse checkout that excluded the MP4).
     */
    private fun locateBundledRecording(): String? = runCatching {
        context.assets.list("ar-recordings")
            ?.firstOrNull { it == BUNDLED_RECORDING }
    }.getOrNull()

    /**
     * Copies the bundled recording from the demo APK's assets into the app's
     * external-files `ar-recordings/` directory — the path the demo's Recordings
     * list scans and that the `--es ar_playback_file` security guard allows.
     */
    private fun deployFixtureToAppPrivateDir(fixtureName: String): File {
        val targetDir = context.getExternalFilesDir("ar-recordings")!!
        targetDir.mkdirs()
        val targetFile = File(targetDir, fixtureName)
        context.assets.open("ar-recordings/$fixtureName").use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        return targetFile
    }

    /**
     * Deep-links `ARRecordPlaybackDemo` straight into Mode.PLAYBACK with
     * [playbackFile] pre-loaded, via the `--es ar_playback_file` intent extra.
     */
    private fun launchPlaybackDemo(playbackFile: String) {
        device.executeShellCommand(
            "am start -n io.github.sceneview.demo/.MainActivity " +
                "-f 0x14000000 " + // CLEAR_TOP | NEW_TASK so onNewIntent fires on re-launch
                "--es demo ar-record-playback " +
                "--es ar_playback_file $playbackFile"
        )
    }

    private companion object {
        /** Bundled recording filename — matches `src/debug/assets/ar-recordings/`. */
        const val BUNDLED_RECORDING = "bundled-pixel9-sample.mp4"

        /**
         * ARCore frame indices to capture goldens at. Chosen per #1050: f=30 is
         * past session init, f=60/120 are mid-replay (planes converged, light
         * estimation settled), f=180 is late in the ~18 s recording.
         */
        val GOLDEN_FRAME_INDICES = intArrayOf(30, 60, 120, 180)

        /** Max wait for ARCore session init + the first replayed frame. */
        const val SESSION_INIT_TIMEOUT_MILLIS = 20_000L

        /** Max wait for the counter to advance from one target index to the next. */
        const val FRAME_ADVANCE_TIMEOUT_MILLIS = 20_000L

        /** Frame-counter poll cadence. Tight enough to land within ~1 frame of target. */
        const val FRAME_POLL_INTERVAL_MILLIS = 50L

        /** System status bar height in pixels. See DemoRenderingScreenshotTest. */
        const val STATUS_BAR_PX = 96
    }
}
