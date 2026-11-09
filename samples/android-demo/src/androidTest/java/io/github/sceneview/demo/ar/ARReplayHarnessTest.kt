package io.github.sceneview.demo.ar

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.sceneview.ar.replay.ARReplayCheck
import io.github.sceneview.ar.replay.ARReplayCheckResult
import io.github.sceneview.ar.replay.ARReplayMetrics
import io.github.sceneview.ar.replay.ARReplayObservation
import io.github.sceneview.ar.replay.ARReplayProbe
import io.github.sceneview.ar.replay.ScreenRect
import io.github.sceneview.ar.replay.evaluateReplay
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demoPackage
import io.github.sceneview.demo.isArDemo
import io.github.sceneview.demo.labs.Labs
import io.github.sceneview.demo.labs.LabsStore
import io.github.sceneview.demo.mainActivityComponent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **AR replay harness**: every AR demo replays a recorded ARCore session on an emulator, and each
 * one is graded on what it shows, not only on surviving the launch (umbrella
 * [#1560](https://github.com/sceneview/sceneview/issues/1560), first slice #1565).
 *
 * ## How every demo replays
 *
 * Each demo is deep-linked with `--es ar_playback_file <path>`. On a debug build that puts an
 * [ARReplayProbe] at the root of the composition ([DemoSettings.arReplayProbe], provided through
 * `LocalARSessionProbe`), and every `ARSceneView` below it replays the recording: one injection
 * point, no per-demo wiring. The same probe counts the frames each demo consumed and measures it.
 *
 * ## What is checked, per demo ([checksFor])
 *
 * - [ARReplayCheck.REPLAYED]: the recording advanced at least a few frames (every demo);
 * - [ARReplayCheck.SCENE_BRIGHTNESS]: the middle of the screen is not dark (every demo) — the
 *   "dark render" defect;
 * - [ARReplayCheck.PLANE]: a plane tracked within N frames (plane and placement demos);
 * - [ARReplayCheck.ANCHOR]: an anchor is tracking (placement demos);
 * - [ARReplayCheck.MODEL_COVERAGE]: the visible models' projected bounds cover a sane share of
 *   the screen — neither nothing (hidden, culled, off screen) nor everything (the camera inside
 *   a model scaled far too large: the "giant invisible model" defect);
 * - [ARReplayCheck.MODEL_BRIGHTNESS]: the screen area the models cover is not dark.
 *
 * The arithmetic lives in `arsceneview` (`io.github.sceneview.ar.replay`), unit-tested on the JVM
 * and reusable by any app that provides the same probe.
 *
 * ## Recordings: the real corpus, or the bundled clip
 *
 * Clips found on the device in [DEFAULT_CORPUS_DIR] (`/sdcard/Download/SceneView/ar-corpus/`,
 * override with `-e ar_corpus_dir <dir>`) are each replayed through every demo — that is where a
 * real corpus filmed on a phone goes; it is never committed. With no corpus, the harness falls
 * back to the bundled `bundled-pixel9-sample.mp4` (debug sourceSet).
 *
 * ## Instrumentation arguments
 *
 * - `-e ar_shard_index <i> -e ar_shard_count <n>`: run one shard of the sweep (#2643);
 * - `-e ar_demos id1,id2`: replay only these demo launch ids;
 * - `-e ar_hide_models true`: hide every model — the deliberate failure that must make
 *   [ARReplayCheck.MODEL_COVERAGE] fail on the placement demos;
 * - `-e ar_corpus_dir <dir>`: where to look for real clips.
 *
 * ## Verdicts (`ar-qa-summary.json`, `Download/SceneView/`)
 *
 * - `replayed`: frames advanced and every check of the demo passed;
 * - `failed`: frames advanced but a check failed — a product regression, with the measured value;
 * - `skipped`: no frame advanced. ARCore dataset playback does not run on every emulator
 *   (#1645, #2620), so this is reported as an environment limitation, never as a pass;
 * - `alive`: the demo opens no AR session on its first screen ([NO_SESSION_ON_ENTRY]), so there is
 *   nothing to replay; it survived the launch;
 * - `crashed`: the process died.
 *
 * A downscaled screenshot of each demo, taken at grading time, is saved to
 * `Download/SceneView/ar-replay/<key>.png` so the verdict can be checked by eye.
 */
@RunWith(AndroidJUnit4::class)
class ARReplayHarnessTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    /** See the class doc: `ar_shard_index` / `ar_shard_count` (#2643). */
    private var shardIndex: Int = 0
    private var shardCount: Int = 1
    private var demoFilter: Set<String>? = null
    private var hideModels: Boolean = false
    private var corpusDir: String = DEFAULT_CORPUS_DIR

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        resolveArguments()
        // Pre-grant runtime permissions: AGP reinstalls the demo APK before each
        // test class, wiping any prior `pm grant`. Without CAMERA the AR demos
        // block at the permission prompt instead of mounting the ARSceneView.
        device.executeShellCommand("pm grant $demoPackage android.permission.CAMERA")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // Labs demos (ar-pool, ar-image-stabilization…) otherwise open on the Labs explainer
        // and never start a session. Open Labs for this process only: an in-memory store,
        // nothing written to the app's preferences.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Labs.attach(
                object : LabsStore {
                    override var joined: Boolean = true
                    override var enabled: Boolean = true
                },
            )
        }
    }

    @Test
    fun everyArDemo_replaysRecordedSession_andPassesItsChecks() {
        val recordings = resolveRecordings()
        assumeTrue(
            "No ARCore recording: no clip in $corpusDir and the bundled $BUNDLED_RECORDING is " +
                "not in the APK assets. It ships in the `debug` sourceSet " +
                "(samples/android-demo/src/debug/assets/ar-recordings/) — run the test against " +
                "a debug build, or `git sparse-checkout add` that path.",
            recordings.isNotEmpty(),
        )

        val allArDemos = ALL_DEMOS.filter { it.isArDemo }
        assertTrue("Expected at least one Augmented Reality demo in ALL_DEMOS", allArDemos.isNotEmpty())
        // Samples step 0 folded `ar-record-playback` into `ar-rerun` as its Session MP4 mode.
        // The card is listed once, but the sweep launches both modes.
        val launchIds = allArDemos.flatMap { REPLAY_MODE_LAUNCHES[it.id] ?: listOf(it.id) }
            .filter { demoFilter == null || it in demoFilter!! }
        val items = recordings.flatMap { rec -> launchIds.map { ReplayItem(it, rec) } }
            // Round-robin over (recording, demo), so heavy demos spread across shards.
            .filterIndexed { idx, _ -> idx % shardCount == shardIndex }
        // Written into the summary before the sweep starts: if the process is severed
        // mid-shard, `ar-replay-qa.sh` can still mark the items never reached (#2643).
        val planned = items.map { it.key }

        // Persisted after every demo, not only at the end: the demos run in the
        // instrumentation's own process, so a native crash or an OOM kill takes the test
        // down with it, and the partial summary (with `inProgress`) is all that is left
        // to name the culprit (#2620).
        val results = ArrayList<DemoResult>(items.size)
        try {
            for (item in items) {
                writeSummary(results, inProgress = item.key, plannedDemos = planned, recordings)
                results += replayDemo(item)
                // Land on the launcher between demos so onNewIntent fires cleanly next time.
                device.pressHome()
            }
        } finally {
            recordings.filter { it.deployed }.forEach { it.file.delete() }
        }

        val summaryPath = writeSummary(results, inProgress = null, plannedDemos = planned, recordings)

        val crashed = results.filter { it.verdict == VERDICT_CRASHED }
        assertTrue(
            "AR replay harness: ${crashed.size}/${results.size} demo(s) crashed during replay — " +
                "${crashed.joinToString { it.key }}. Full verdict: $summaryPath",
            crashed.isEmpty(),
        )
        val failed = results.filter { it.verdict == VERDICT_FAILED }
        assertTrue(
            "AR replay harness: ${failed.size}/${results.size} demo(s) failed a check — " +
                failed.joinToString("; ") { r ->
                    r.key + ": " + r.checks.filterNot { it.passed }
                        .joinToString { "${it.check} ${it.measured} (want ${it.expected})" }
                } + ". Full verdict: $summaryPath",
            failed.isEmpty(),
        )
        // Honesty gate (#1645): if not a single frame replayed anywhere, the AR leg cannot be
        // graded a pass. Reported as skipped, with the reason in the summary.
        val skipped = results.filter { it.verdict == VERDICT_SKIPPED }
        assumeTrue(
            "AR replay harness SKIPPED: ${skipped.size}/${results.size} demo(s) replayed 0 " +
                "frames — $REASON_NO_FRAMES. Full verdict: $summaryPath",
            skipped.isEmpty(),
        )
    }

    // ── Per-demo replay ─────────────────────────────────────────────────────

    private fun replayDemo(item: ReplayItem): DemoResult {
        val previousProbe = DemoSettings.arReplayProbe
        launchDemo(item.demoId, item.recording.file.absolutePath)
        val probe = awaitNewProbe(previousProbe)
        val checks = checksFor(item.demoId)

        // Poll until the demo shows what it is graded on, or the window closes.
        val start = SystemClock.uptimeMillis()
        var observation = probe?.observe() ?: ARReplayObservation(frames = 0)
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= MAX_WINDOW_MILLIS) break
            if (elapsed >= MIN_WINDOW_MILLIS && isSettled(observation, checks)) break
            if (!isDemoProcessAlive()) break
            SystemClock.sleep(POLL_MILLIS)
            observation = probe?.observe() ?: observation
        }

        if (!isDemoProcessAlive()) {
            return DemoResult(item, VERDICT_CRASHED, observation, emptyList(), "process died")
        }
        // Brightness needs the screen's own pixels.
        val shot = takeScreenshot()
        if (shot != null) {
            val (pixels, w, h) = shot
            val modelRect = observation.coverage?.rect
            observation = observation.copy(
                sceneLuminance = ARReplayMetrics.meanLuminance(pixels, w, h, ScreenRect.CENTRE, stride = 2),
                modelLuminance = modelRect?.let { ARReplayMetrics.meanLuminance(pixels, w, h, it) },
            )
        }
        val results = evaluateReplay(observation, checks)
        val verdict = when {
            observation.frames >= MIN_REPLAYED_FRAMES ->
                if (results.all { it.passed }) VERDICT_REPLAYED else VERDICT_FAILED
            item.demoId in NO_SESSION_ON_ENTRY -> VERDICT_ALIVE
            else -> VERDICT_SKIPPED
        }
        val reason = when (verdict) {
            VERDICT_SKIPPED -> if (probe == null) REASON_NO_PROBE else REASON_NO_FRAMES
            VERDICT_ALIVE -> REASON_NO_SESSION
            else -> null
        }
        return DemoResult(item, verdict, observation, results, reason)
    }

    /** Every frame-count-independent signal a check waits for has arrived. */
    private fun isSettled(o: ARReplayObservation, checks: Set<ARReplayCheck>): Boolean {
        if (o.frames < MIN_REPLAYED_FRAMES) return false
        if (ARReplayCheck.PLANE in checks && o.firstPlaneFrame == null) return false
        if (ARReplayCheck.ANCHOR in checks && o.trackedAnchors == 0) return false
        if (ARReplayCheck.MODEL_COVERAGE in checks && (o.coverage?.rect == null)) return false
        return true
    }

    /**
     * The probe the new launch installed — the app's own marker that it took the intent — or
     * `null` if none arrived within [PROBE_WAIT_MILLIS] (release build, rejected path, process
     * gone). A cold start of the debug demo on a slow device can take well over 5 s, so the
     * wait returns as soon as the marker appears rather than after a fixed delay.
     */
    private fun awaitNewProbe(previous: ARReplayProbe?): ARReplayProbe? {
        val deadline = SystemClock.uptimeMillis() + PROBE_WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val current = DemoSettings.arReplayProbe
            if (current != null && current !== previous) return current
            if (!isDemoProcessAlive()) return null
            SystemClock.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun isDemoProcessAlive(): Boolean =
        device.executeShellCommand("pidof $demoPackage").trim().isNotEmpty()

    /** The screen, downscaled, as ARGB pixels — and saved for the reviewer. */
    private fun takeScreenshot(): Triple<IntArray, Int, Int>? {
        val full = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return null
        val w = SHOT_WIDTH
        val h = (full.height.toFloat() / full.width * w).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, w, h, true)
            .copy(Bitmap.Config.ARGB_8888, false)
        full.recycle()
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        lastShot = small
        return Triple(pixels, w, h)
    }

    private var lastShot: Bitmap? = null

    // ── Recordings ──────────────────────────────────────────────────────────

    private data class Recording(val name: String, val file: File, val deployed: Boolean)

    private data class ReplayItem(val demoId: String, val recording: Recording) {
        /** The demo id for the bundled clip, `id@clip` for a corpus clip. */
        val key: String
            get() = if (recording.name == BUNDLED_RECORDING) demoId else "$demoId@${recording.name}"
    }

    /**
     * The clips to replay: every `.mp4` of [corpusDir] on the device, copied into the app's
     * `ar-recordings/` (the only place `ar_playback_file` is accepted from); the bundled clip
     * when there is none.
     */
    private fun resolveRecordings(): List<Recording> {
        val targetDir = context.getExternalFilesDir("ar-recordings")!!.apply { mkdirs() }
        val corpus = device.executeShellCommand("ls $corpusDir").lines()
            .map { it.trim() }
            .filter { it.endsWith(".mp4", ignoreCase = true) && !it.contains(' ') }
        val fromCorpus = corpus.mapNotNull { name ->
            val target = File(targetDir, "corpus-$name")
            device.executeShellCommand("cp $corpusDir/$name ${target.absolutePath}")
            target.takeIf { it.isFile && it.length() > 0 }?.let { Recording(name, it, deployed = true) }
        }
        if (fromCorpus.isNotEmpty()) return fromCorpus

        val bundled = runCatching {
            context.assets.list("ar-recordings")?.firstOrNull { it == BUNDLED_RECORDING }
        }.getOrNull() ?: return emptyList()
        val target = File(targetDir, bundled)
        context.assets.open("ar-recordings/$bundled").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return listOf(Recording(bundled, target, deployed = true))
    }

    private fun launchDemo(demoSlug: String, playbackFile: String) {
        device.executeShellCommand(
            "am start -n $mainActivityComponent " +
                "-f 0x14000000 " + // CLEAR_TOP | NEW_TASK so onNewIntent fires on re-launch
                "--es demo $demoSlug " +
                "--es ar_playback_file $playbackFile" +
                if (hideModels) " --ez ar_qa_hide_models true" else "",
        )
    }

    private fun resolveArguments() {
        val arguments = InstrumentationRegistry.getArguments()
        val count = arguments.getString("ar_shard_count")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val index = (arguments.getString("ar_shard_index")?.toIntOrNull() ?: 0).coerceIn(0, count - 1)
        shardCount = count
        shardIndex = index
        demoFilter = arguments.getString("ar_demos")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?.takeIf { it.isNotEmpty() }
        hideModels = arguments.getString("ar_hide_models") == "true"
        corpusDir = arguments.getString("ar_corpus_dir")?.takeIf { it.isNotBlank() } ?: DEFAULT_CORPUS_DIR
    }

    // ── Machine-readable summary ────────────────────────────────────────────

    private data class DemoResult(
        val item: ReplayItem,
        val verdict: String,
        val observation: ARReplayObservation,
        val checks: List<ARReplayCheckResult>,
        val reason: String? = null,
    ) {
        val key: String get() = item.key
    }

    /**
     * Writes `Download/SceneView/ar-qa-summary.json` through MediaStore — the one shared
     * location a permission-less app can publish to and `adb pull` can read after the app is
     * gone (#2643). Shape: `{ harness, passed, skipped, failed, total, plannedDemos,
     * inProgress?, demos: [{ id, key, recording, verdict, replayedFrames, reason?, metrics,
     * checks }] }`. `passed` counts `replayed` and `alive`; `failed` counts `crashed` and
     * `failed`.
     */
    private fun writeSummary(
        results: List<DemoResult>,
        inProgress: String?,
        plannedDemos: List<String>,
        recordings: List<Recording>,
    ): String {
        val demos = JSONArray()
        for (r in results) {
            val o = r.observation
            val metrics = JSONObject()
                .put("firstPlaneFrame", o.firstPlaneFrame ?: JSONObject.NULL)
                .put("trackedAnchors", o.trackedAnchors)
                .put("visibleModels", o.visibleModels)
                .put("coverage", o.coverage?.fraction?.toDouble() ?: JSONObject.NULL)
                .put("cameraInsideModel", o.coverage?.cameraInside ?: false)
                .put("sceneLuminance", o.sceneLuminance?.toDouble() ?: JSONObject.NULL)
                .put("modelLuminance", o.modelLuminance?.toDouble() ?: JSONObject.NULL)
            val checks = JSONArray()
            r.checks.forEach { c ->
                checks.put(
                    JSONObject().put("check", c.check.name).put("passed", c.passed)
                        .put("measured", c.measured).put("expected", c.expected),
                )
            }
            val obj = JSONObject()
                .put("id", r.item.demoId)
                .put("key", r.key)
                .put("recording", r.item.recording.name)
                .put("verdict", r.verdict)
                .put("replayedFrames", o.frames)
                .put("metrics", metrics)
                .put("checks", checks)
            if (r.reason != null) obj.put("reason", r.reason)
            demos.put(obj)
        }
        // Save the screenshot of the demo just graded next to the summary.
        results.lastOrNull()?.let { r -> lastShot?.let { saveScreenshot(r.key, it) } }
        lastShot = null

        val root = JSONObject()
            .put("harness", "ar-replay")
            .put("recording", recordings.joinToString(",") { it.name })
            .put("hideModels", hideModels)
            .put("passed", results.count { it.verdict == VERDICT_REPLAYED || it.verdict == VERDICT_ALIVE })
            .put("replayed", results.count { it.verdict == VERDICT_REPLAYED })
            .put("skipped", results.count { it.verdict == VERDICT_SKIPPED })
            .put("failed", results.count { it.verdict == VERDICT_CRASHED || it.verdict == VERDICT_FAILED })
            .put("total", results.size)
            .put("shardIndex", shardIndex)
            .put("shardCount", shardCount)
            .put("plannedDemos", JSONArray(plannedDemos))
        if (inProgress != null) root.put("inProgress", inProgress)
        root.put("demos", demos)
        return publish("Download/SceneView/", "ar-qa-summary.json", "application/json") { out ->
            out.write(root.toString(2).toByteArray())
        }
    }

    private fun saveScreenshot(key: String, bitmap: Bitmap) {
        val safe = key.replace(Regex("[^A-Za-z0-9._@-]"), "_")
        runCatching {
            publish("Download/SceneView/ar-replay/", "$safe.png", "image/png") { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        bitmap.recycle()
    }

    /** Delete-then-insert through MediaStore, so incremental rewrites stay idempotent. */
    private fun publish(
        relativePath: String,
        displayName: String,
        mime: String,
        write: (java.io.OutputStream) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        resolver.query(
            collection, arrayOf(MediaStore.Downloads._ID), selection,
            arrayOf(relativePath, displayName), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.delete(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
            }
        }
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending) ?: error("MediaStore insert returned null for $displayName")
        resolver.openOutputStream(uri)?.use(write) ?: error("MediaStore openOutputStream returned null for $displayName")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return "/sdcard/$relativePath$displayName"
    }

    private companion object {
        const val BUNDLED_RECORDING = "bundled-pixel9-sample.mp4"
        const val DEFAULT_CORPUS_DIR = "/sdcard/Download/SceneView/ar-corpus"

        /** Every demo gets at least this long before it is graded. */
        const val MIN_WINDOW_MILLIS = 7_000L

        /** A demo still waiting for a plane, an anchor or a model is graded at this point. */
        const val MAX_WINDOW_MILLIS = 25_000L
        const val POLL_MILLIS = 250L
        /** Cap on waiting for the app to take the launch intent (see [awaitNewProbe]). */
        const val PROBE_WAIT_MILLIS = 120_000L
        const val SHOT_WIDTH = 360
        const val MIN_REPLAYED_FRAMES = 5

        const val VERDICT_REPLAYED = "replayed"
        const val VERDICT_FAILED = "failed"
        const val VERDICT_ALIVE = "alive"
        const val VERDICT_SKIPPED = "skipped"
        const val VERDICT_CRASHED = "crashed"

        /** Session MP4 mode of `ar-rerun`, reached through this retired deep-link alias. */
        const val REPLAY_DEMO_ID = "ar-record-playback"

        val REPLAY_MODE_LAUNCHES: Map<String, List<String>> =
            mapOf("ar-rerun" to listOf("ar-rerun", REPLAY_DEMO_ID))

        /**
         * Demos whose first screen opens no AR session (a chooser, a dollhouse, reference
         * poses): nothing replays until the user acts, so they are graded on surviving.
         */
        val NO_SESSION_ON_ENTRY = setOf("ar-placement", "ar-splat-room", "ar-xr")

        private val BASE = setOf(ARReplayCheck.REPLAYED, ARReplayCheck.SCENE_BRIGHTNESS)
        private val PLANES = BASE + ARReplayCheck.PLANE
        private val PLACEMENT = PLANES + setOf(
            ARReplayCheck.ANCHOR, ARReplayCheck.MODEL_COVERAGE, ARReplayCheck.MODEL_BRIGHTNESS,
        )

        /**
         * Placement demos (`FeaturePlacementScene` places the model on the first good plane, no
         * tap needed) are graded on plane, anchor and model; plane demos on the plane; the rest
         * on frames and brightness. Metrics are recorded for every demo either way, so a demo
         * can be promoted here once its numbers are known.
         */
        val CHECKS: Map<String, Set<ARReplayCheck>> = buildMap {
            listOf(
                "ar-cloud-anchor", "ar-depth-occlusion",
                "ar-people-occlusion", "ar-image-stabilization",
            ).forEach { put(it, PLACEMENT) }
            // The pool is procedural geometry anchored on the plane, not a ModelNode: model
            // coverage cannot see it, so it is graded on the anchor it places.
            put("ar-pool", PLANES + ARReplayCheck.ANCHOR)
            listOf("ar-plane-node", "ar-surfaces", "ar-measure").forEach { put(it, PLANES) }
        }

        fun checksFor(demoId: String): Set<ARReplayCheck> = CHECKS[demoId] ?: BASE

        const val REASON_NO_FRAMES =
            "ARCore dataset playback advanced 0 frames — not supported on this emulator " +
                "configuration (#1645, #2620); environment limitation, not graded a pass"
        const val REASON_NO_PROBE =
            "no replay probe reached the app (release build, or the recording path was rejected)"
        const val REASON_NO_SESSION = "opens no AR session on its first screen; survived the launch"
    }
}
