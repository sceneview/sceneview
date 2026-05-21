package io.github.sceneview.demo.demos

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.recording.ARRecorder
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AR record-and-replay demo.
 *
 * ARCore's `Session.startRecording(RecordingConfig)` captures the **entire** AR session —
 * camera frames, IMU, planes, depth, anchors, light estimation — into an MP4 dataset.
 * `Session.setPlaybackDataset(absolutePath)` then makes the same session replay that MP4 1:1,
 * as if you were physically there. The combination is the most useful debugging primitive
 * ARCore exposes:
 *
 * - **Record outside, replay at the desk** — no need to retake a physical test for every
 *   tweak.
 * - **Reproduce bugs across devs** — share a recording instead of a verbal description.
 * - **Deterministic AR tests** — ship recordings as fixtures, replay them in CI.
 * - **Pair with the Rerun debug demo** — replay a recording while streaming to Rerun for a
 *   stable, repeatable visual inspection loop.
 *
 * The demo has four modes wired to a top segmented control:
 *
 * - **LIVE** — plain AR, tap a plane to drop a helmet (sanity check the session works).
 * - **RECORD** — same as LIVE, plus a record button. While recording, an elapsed-time pill
 *   is shown. Stopping reveals the saved MP4 in the Recordings card.
 * - **PLAYBACK** — lists the MP4s on disk; tapping one re-mounts the [ARSceneView] with
 *   `playbackDataset = file` so ARCore replays the dataset.
 * - **ANALYSE** — replay a recording *and interpret it*: every replayed frame is fed to an
 *   [io.github.sceneview.ar.recording.ARRecordInterpreter] which folds the dataset into a
 *   quantified [io.github.sceneview.ar.recording.ARRecordInterpretation] — trajectory
 *   length, tracked-frame ratio, dominant [TrackingFailureReason], plane count/area. The
 *   live numbers are overlaid on the replay; when the dataset ends
 *   (`rememberARPlaybackStatus == PlaybackStatus.FINISHED`) a final **report card** sums
 *   up the take. This is the desk-side counterpart to on-device QA — record a hard
 *   tracking stress-case once, then replay + measure it on every iteration.
 *
 * Switching mode forces the [ARSceneView] to be rebuilt via `key(currentMode, …)` because
 * ARCore binds the playback source at session-creation time and cannot be toggled after
 * resume. Recordings live in `context.getExternalFilesDir("ar-recordings")` — app-private
 * external storage, no runtime permission needed.
 */
@Composable
fun ARRecordPlaybackDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Honour `DemoSettings.arPendingPlaybackFile` (set via `--es ar_playback_file <path>` on
     // the launch intent) so instrumentation tests can drive a deterministic replay without
     // having to UiAutomator-click through Mode.PLAYBACK and the recording list.
    val pendingFile = io.github.sceneview.demo.DemoSettings.arPendingPlaybackFile
        ?.let(::File)?.takeIf { it.exists() }
    // Default mode is RECORD: it's the action 90% of users came here to take, and the
    // RECORD overlay's "How this helps" + big record button doubles as a clear hint that
    // a Playback tab exists for replaying. LIVE is still reachable from the chips for
    // sanity-checking session quality before a take.
    var currentMode by remember { mutableStateOf(if (pendingFile != null) Mode.PLAYBACK else Mode.RECORD) }
    var currentPlaybackFile by remember { mutableStateOf<File?>(pendingFile) }
    // Last file saved by the recorder this session — drives the green "Recording saved"
    // callout in RECORD mode + its Replay / Share buttons. Null until the first stop.
    var lastSavedFile by remember { mutableStateOf<File?>(null) }
    // Tracking-quality summary of the last completed recording (#1650) — the percentage
    // of recorded frames where ARCore was actually TRACKING. Surfaced in the post-stop
    // callout so the user can judge a take at a glance. Null until the first stop.
    var lastTrackingHealth by remember { mutableStateOf<TrackingHealth?>(null) }
    LaunchedEffect(Unit) {
        // Consume so a config change / process recreation doesn't re-trigger.
        io.github.sceneview.demo.DemoSettings.arPendingPlaybackFile = null
    }
    var exportToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportToast) {
        if (exportToast != null) {
            android.widget.Toast.makeText(context, exportToast, android.widget.Toast.LENGTH_LONG).show()
            exportToast = null
        }
    }

    // List of recordings on disk — refreshed when a recording finishes or the user enters
    // PLAYBACK mode.
    val recordingsDir = remember(context) {
        context.getExternalFilesDir("ar-recordings")!!.also { it.mkdirs() }
    }
    // Extract any bundled recordings shipped in `src/debug/assets/ar-recordings/`
    // into `recordingsDir` on first launch — gives emulator + ARCore-less
    // debug-build devices a known-good playback dataset out of the box, so the
    // AR demos are reproducible without a physical capture session. The bundle
    // lives in the `debug` sourceSet so the release APK doesn't ship it (#934).
    // See `samples/android-demo/src/debug/assets/ar-recordings/README.md`.
    LaunchedEffect(recordingsDir) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val assetMgr = context.assets
                val bundled = assetMgr.list("ar-recordings")?.filter {
                    it.endsWith(".mp4", ignoreCase = true)
                } ?: emptyList()
                for (name in bundled) {
                    val target = File(recordingsDir, name)
                    val expectedBytes = assetMgr.openFd("ar-recordings/$name").use { it.length }
                    // Skip if already present AND length matches the bundled
                    // asset — a future release that ships an updated recording
                    // (e.g. bumped to a newer ARCore SDK) will re-extract.
                    if (target.exists() && target.length() == expectedBytes) continue
                    // Write to a `.tmp` first then rename, so a kill / process
                    // death mid-copy can't leave a half-written file that the
                    // next `target.exists()` check would silently accept.
                    val tmp = File(recordingsDir, "$name.tmp")
                    try {
                        assetMgr.open("ar-recordings/$name").use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Re-throw to keep structured concurrency intact — the
                        // parent LaunchedEffect cancel must propagate (lesson
                        // from #980). Drop the partial tmp file first.
                        tmp.delete()
                        throw e
                    } catch (_: Throwable) {
                        tmp.delete()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Bundled extraction is a nice-to-have; missing files just
                // mean the user sees an empty Playback list and can record
                // their own session.
            }
        }
    }
    val recordings = remember { mutableStateListOf<File>() }
    fun refreshRecordings() {
        recordings.clear()
        recordingsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.let { recordings.addAll(it) }
    }
    LaunchedEffect(currentMode) { refreshRecordings() }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_record_playback_title),
        onBack = onBack,
        controls = {
            // ── About card ─────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "How this helps",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Record an AR session once, replay it 1:1 without holding a " +
                            "phone. Iterate on AR code at your desk against a stable real-world " +
                            "capture, share recordings between devs to reproduce bugs, or feed " +
                            "deterministic sessions into your test suite.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Pair with the Rerun Debug demo for a fully visual inspection " +
                            "loop: replay a recording while streaming to Rerun.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Mode selector ──────────────────────────────────────────────
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chipHaptic = rememberHapticFeedback()
                Mode.values().forEach { mode ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = {
                            if (currentMode != mode) {
                                // `selection()` is the small-confirm pulse — same
                                // one Material 3 uses on chip toggles when the
                                // system honours haptic-on-touch. Explicit here
                                // so it fires on every device, not only those
                                // with the OS setting turned on (#956). Skipped
                                // in qaMode so adb-driven instrumentation tests
                                // that tap these chips don't get unexpected
                                // vibration.
                                if (!io.github.sceneview.demo.DemoSettings.qaMode) {
                                    chipHaptic.selection()
                                }
                                currentMode = mode
                                // PLAYBACK and ANALYSE both replay the selected file —
                                // keep it across that pair so toggling between "just
                                // watch" and "watch + measure" doesn't drop the dataset.
                                if (!mode.isPlayback) currentPlaybackFile = null
                            }
                        },
                        label = { Text(mode.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (currentMode) {
                Mode.LIVE -> {
                    Text(
                        text = "Tap a detected plane to drop a model. This is the same as " +
                            "the Tap-to-Place demo — use it to sanity-check the session " +
                            "before recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Mode.RECORD -> {
                    Text(
                        text = "Tap ● Record to capture an AR session — camera frames, IMU, " +
                            "planes, depth and anchors. Hit ■ Stop and the recording lands in " +
                            "the Playback tab where you can replay or share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Strong feedback right after a save — without it, hitting Stop felt like
                    // nothing happened (the recordings list lives in a different tab).
                    lastSavedFile?.let { file ->
                        Spacer(Modifier.height(8.dp))
                        SavedRecordingCallout(
                            file = file,
                            recordingsCount = recordings.size,
                            trackingHealth = lastTrackingHealth,
                            onReplay = {
                                currentPlaybackFile = file
                                currentMode = Mode.PLAYBACK
                                lastSavedFile = null
                            },
                            onOpenInPlayback = {
                                currentMode = Mode.PLAYBACK
                                lastSavedFile = null
                            },
                            onShare = { shareRecording(context, file) },
                            onOpen = { openRecordingAsVideo(context, file) },
                            onExport = {
                                ARRecorder.exportToDownloads(context, file)?.let {
                                    exportToast = "Saved to Downloads/SceneView/${file.name}"
                                } ?: run { exportToast = "Export failed — see logs" }
                            },
                            onDismiss = { lastSavedFile = null }
                        )
                    }
                }
                Mode.PLAYBACK -> {
                    Text(
                        text = "Pick a recording to replay. ARCore re-runs it as if the camera " +
                            "were live — anchors, planes and tracking all replay deterministically. " +
                            "Each recording is a standard MP4 carrying ARCore data tracks: " +
                            "\"Open\" plays it in any video app, \"Export\" copies it to " +
                            "Downloads/SceneView/ for `adb pull /sdcard/Download/SceneView/…` " +
                            "or sharing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    RecordingsList(
                        recordings = recordings,
                        selected = currentPlaybackFile,
                        // Highlight the file just captured this session so it's
                        // obviously discoverable in the list — the on-device QA
                        // session reported recordings "disappearing" after a take
                        // because nothing pointed at the new entry (#1438).
                        justRecorded = lastSavedFile,
                        onSelect = { file ->
                            // Re-key the ARSceneView so ARCore gets a fresh Session bound to
                            // the new playback dataset.
                            currentPlaybackFile = file
                        },
                        onExport = { file ->
                            ARRecorder.exportToDownloads(context, file)?.let { uri ->
                                exportToast = "Exported to Downloads/SceneView/${file.name}"
                            } ?: run { exportToast = "Export failed — see logs" }
                        },
                        onShare = { file -> shareRecording(context, file) },
                        onOpen = { file -> openRecordingAsVideo(context, file) },
                        onRefresh = { refreshRecordings() }
                    )
                }
                Mode.ANALYSE -> {
                    Text(
                        text = "Pick a recording to replay AND interpret. Every replayed frame " +
                            "is folded into a quantified report — trajectory length, the " +
                            "percentage of frames ARCore tracked, the dominant tracking-failure " +
                            "reason, and how many planes were found. The live numbers overlay " +
                            "the replay; a final report card appears when the dataset ends. " +
                            "This is the desk-side counterpart to on-device QA — replay a hard " +
                            "tracking stress-case and measure it without holding a phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    RecordingsList(
                        recordings = recordings,
                        selected = currentPlaybackFile,
                        justRecorded = lastSavedFile,
                        onSelect = { file -> currentPlaybackFile = file },
                        onExport = { file ->
                            ARRecorder.exportToDownloads(context, file)?.let { uri ->
                                exportToast = "Exported to Downloads/SceneView/${file.name}"
                            } ?: run { exportToast = "Export failed — see logs" }
                        },
                        onShare = { file -> shareRecording(context, file) },
                        onOpen = { file -> openRecordingAsVideo(context, file) },
                        onRefresh = { refreshRecordings() }
                    )
                }
            }

            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The ARSceneView is keyed on (currentMode, currentPlaybackFile) so that any
            // mode switch — and any new playback selection — forces a fresh ARCore Session.
            // This is required: setPlaybackDataset must run before resume(), which only
            // happens when a brand-new session is created.
            key(currentMode, currentPlaybackFile?.absolutePath) {
                ModeContent(
                    mode = currentMode,
                    playbackFile = currentPlaybackFile,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    onRecordingFinished = { savedFile, trackingHealth ->
                        refreshRecordings()
                        // Drive the post-stop callout in the controls panel.
                        if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                            lastSavedFile = savedFile
                            lastTrackingHealth = trackingHealth
                        }
                    },
                    recordingsDir = recordingsDir
                )
            }
        }
    }
}

/**
 * Top-level 4-way segmented control values.
 *
 * [ANALYSE] is a second playback mode: it replays a recording exactly like [PLAYBACK] but
 * additionally feeds every frame to an [io.github.sceneview.ar.recording.ARRecordInterpreter]
 * and overlays the running interpretation + an end-of-replay report card.
 */
private enum class Mode(val label: String) {
    LIVE("Live"),
    RECORD("Record"),
    PLAYBACK("Playback"),
    ANALYSE("Analyse");

    /** `true` for the two modes that bind a `playbackDataset` and replay an MP4. */
    val isPlayback: Boolean get() = this == PLAYBACK || this == ANALYSE
}

/**
 * Wraps the per-mode [ARSceneView] + overlays. Pulled out into its own composable so that
 * the outer `key(...)` block remounts everything (including the recorder, recording timer
 * and tap state) on every mode change — the simplest way to guarantee a fresh ARCore
 * Session for each transition.
 */
@Composable
private fun ModeContent(
    mode: Mode,
    playbackFile: File?,
    engine: com.google.android.filament.Engine,
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    onRecordingFinished: (File?, TrackingHealth?) -> Unit,
    recordingsDir: File
) {
    val context = LocalContext.current
    val anchors = remember { mutableStateListOf<Anchor>() }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    // Per-recording tracking-quality accumulator (#1650). While the recorder is RECORDING,
    // every consumed ARCore frame ticks one of these counters depending on whether the
    // camera was TRACKING. After Stop, the ratio drives the "tracking healthy X%" stat in
    // the saved-recording callout. Reset on each fresh start() below.
    val trackingTracker = remember { TrackingHealthTracker() }
    // Wall-clock of the last frame ARCore reported TRACKING — drives the live "tracking
    // lost for Ns" soft warning so a stalled capture is obvious in real time. 0 = never
    // tracked yet this composition.
    var lastTrackingMillis by remember { mutableStateOf(0L) }
    // Flipped true on the first onSessionUpdated — i.e. once ARCore has opened the
    // camera (or started replaying the dataset) and delivered a frame. Until then the
    // ARSceneView surface is bare black, so we cover it with ARCameraInitScrim rather
    // than leave a viewport that reads as frozen/broken (#1473).
    var cameraReady by remember { mutableStateOf(false) }

    val recorder = rememberARRecorder()
    // The file we passed to recorder.start() — kept so we can hand it back to the
    // outer state on stop, which drives the post-save callout in the controls panel.
    var pendingRecordingFile by remember { mutableStateOf<File?>(null) }

    // Elapsed-time tick — only runs while actively recording so we don't burn cycles.
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(recorder.state) {
        if (recorder.state == ARRecorder.State.RECORDING) {
            val start = System.currentTimeMillis()
            while (recorder.state == ARRecorder.State.RECORDING) {
                elapsedSeconds = (System.currentTimeMillis() - start) / 1000
                delay(500)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    // When the recorder transitions out of RECORDING (and back to IDLE), refresh the
    // recordings list so the new MP4 shows up in PLAYBACK mode.
    DisposableEffect(recorder) {
        onDispose {
            if (recorder.state == ARRecorder.State.RECORDING) {
                recorder.stop()
                onRecordingFinished(pendingRecordingFile, trackingTracker.snapshot())
            }
        }
    }

    val helmet = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // PLAYBACK and ANALYSE both bind the selected MP4 as the replay source.
    val playbackDataset: File? = if (mode.isPlayback) playbackFile else null

    // ── ANALYSE mode — replay interpretation (#2027) ────────────────────────
    // ARRecordInterpreter folds every replayed frame into a quantified
    // ARRecordInterpretation. Only fed in ANALYSE mode; held unconditionally so the
    // composable structure stays stable across mode switches (the outer key(...)
    // remounts on a real mode change anyway).
    val interpreter = io.github.sceneview.ar.recording.rememberARRecordInterpreter()
    // The ARCore Session captured from onSessionUpdated — needed by
    // rememberARPlaybackStatus, which has no other handle on the session. Published as
    // Compose state so the status poller re-keys once the session exists.
    var arSession by remember { mutableStateOf<Session?>(null) }
    val playbackStatus by io.github.sceneview.ar.recording.rememberARPlaybackStatus(
        if (mode == Mode.ANALYSE) arSession else null
    )

    // Reset the cross-thread frame counter whenever a playback ARSceneView is
    // (re-)mounted, so a fresh `connectedDebugAndroidTest` run — or a re-tapped
    // recording — starts the frame index from a known origin (0). Keyed on the
    // dataset so swapping recordings re-zeroes. See DemoSettings.arPlaybackFrameCount
    // and ARPlaybackScreenshotTest (#1050).
    LaunchedEffect(playbackDataset) {
        if (playbackDataset != null) {
            io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount = 0
        }
    }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        planeRenderer = true,
        playbackDataset = playbackDataset,
        sessionConfiguration = { _: Session, config: Config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onSessionUpdated = { session: Session, frame: Frame ->
            cameraReady = true
            latestFrame = frame
            val frameTracking = frame.camera.trackingState == TrackingState.TRACKING
            isTracking = frameTracking
            // Stateless side-channel pattern (#876) — recordFrame publishes the
            // session per call, mirroring RerunBridge.logFrame. Idempotent.
            recorder.recordFrame(session)
            // ANALYSE mode (#2027): fold this replayed frame into the running
            // ARRecordInterpretation, and publish the Session so rememberARPlaybackStatus
            // can poll its PlaybackStatus. ingest() is required to run on the render
            // thread that owns the Frame — onSessionUpdated is exactly that thread.
            if (mode == Mode.ANALYSE) {
                interpreter.ingest(session, frame)
                if (arSession !== session) arSession = session
            }
            // Tracking-quality accounting while a capture is in progress (#1650):
            // count this frame as healthy / unhealthy so the post-stop callout can
            // report what fraction of the recording ARCore was actually tracking,
            // and stamp the wall-clock so the live "tracking lost" warning knows how
            // long tracking has been gone. onSessionUpdated runs on the main thread,
            // so the plain-counter accumulator needs no synchronisation.
            if (recorder.state == ARRecorder.State.RECORDING) {
                trackingTracker.tick(frameTracking)
            }
            if (frameTracking) lastTrackingMillis = System.currentTimeMillis()
            // Frame-indexed screenshot regression hook (#1050): bump the
            // cross-thread counter once per consumed ARCore frame during
            // playback so ARPlaybackScreenshotTest can capture at deterministic
            // frame indices instead of wall-clock sleeps. No-op for live mode —
            // a live session has no reproducible frame timeline to gate on.
            // Both replay modes have a reproducible frame timeline.
            if (mode.isPlayback) {
                io.github.sceneview.demo.DemoSettings.arPlaybackFrameCount++
            }
        },
        onTrackingFailureChanged = { trackingFailureReason = it },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event: MotionEvent, _ ->
                val frame = latestFrame ?: return@rememberOnGestureListener
                if (frame.camera.trackingState != TrackingState.TRACKING) {
                    return@rememberOnGestureListener
                }
                val hit = frame.hitTest(event).firstOrNull { result ->
                    val trackable = result.trackable
                    trackable is Plane &&
                        trackable.isPoseInPolygon(result.hitPose) &&
                        result.distance <= 5.0f
                }
                if (hit != null) anchors.add(hit.createAnchor())
            }
        )
    ) {
        anchors.forEach { anchor ->
            AnchorNode(anchor = anchor) {
                helmet?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.3f,
                        centerOrigin = Position(0f, 0f, 0f)
                    )
                }
            }
        }
    }

    // ── Mode-specific overlays ─────────────────────────────────────────────

    if (mode == Mode.RECORD) {
        // While recording, surface the live ARCore tracking quality (#1650): a soft
        // warning once tracking has been lost for more than a few seconds so a doomed
        // capture — e.g. shooting from inside a moving vehicle — is obvious in real
        // time instead of being discovered only on playback.
        val isRecording = recorder.state == ARRecorder.State.RECORDING
        var trackingLostSeconds by remember { mutableStateOf(0L) }
        LaunchedEffect(isRecording) {
            while (isRecording) {
                trackingLostSeconds = if (isTracking || lastTrackingMillis == 0L) {
                    0L
                } else {
                    (System.currentTimeMillis() - lastTrackingMillis) / 1000
                }
                delay(500)
            }
            trackingLostSeconds = 0L
        }
        // ForcedTrackingFailure lets QA stage a tracking loss without a real one (#1881);
        // honour it here too so the recording status pill / warning can be validated.
        val recordingReason = ForcedTrackingFailure.override ?: trackingFailureReason
        val recordingTracking = isTracking && ForcedTrackingFailure.override == null
        RecordOverlay(
            recorder = recorder,
            elapsedSeconds = elapsedSeconds,
            isTracking = recordingTracking,
            trackingFailureReason = recordingReason,
            trackingLostSeconds = trackingLostSeconds,
            // Disable the shutter until ARCore is actually tracking — without
            // a tracked frame, recorder.start() returns false and transitions
            // to ERROR with the dev-flavoured "call attach(session) first"
            // message, which is confusing for casual users (the QR-scanned
            // path). Keep the disable logic generous: we allow ERROR-state
            // re-tries (user can retry after a transient hiccup) but block
            // the very first tap when nothing is tracked yet.
            startEnabled = isTracking || recorder.state == ARRecorder.State.ERROR,
            onStart = {
                val name = "ar-session-${TIMESTAMP_FORMAT.format(Date())}.mp4"
                val file = File(recordingsDir, name)
                pendingRecordingFile = file
                // Zero the tracking-quality accumulator so the next take's stat
                // reflects only this recording (#1650).
                trackingTracker.reset()
                recorder.start(
                    file = file,
                    recordingRotation = currentDisplayRotation(context)
                )
            },
            onStop = {
                val saved = pendingRecordingFile
                val health = trackingTracker.snapshot()
                recorder.stop()
                onRecordingFinished(saved, health)
            }
        )
    }

    if (mode.isPlayback && playbackFile != null) {
        PlaybackBanner(playbackFile.name)
    }

    // ── ANALYSE mode overlays (#2027) ──────────────────────────────────────
    // While a dataset replays, overlay the running interpretation; once it ends
    // (PlaybackStatus.FINISHED) swap it for a full report card.
    if (mode == Mode.ANALYSE && playbackFile != null) {
        val finished = playbackStatus == com.google.ar.core.PlaybackStatus.FINISHED
        if (finished) {
            AnalysisReportCard(interpretation = interpreter.interpretation)
        } else {
            AnalysisLiveOverlay(interpretation = interpreter.interpretation)
        }
    }

    // Tracking failure overlay — uses its own fillMaxSize Box internally so it can align to
    // the bottom of the parent Box.
    // ForcedTrackingFailure.override shadows the real ARCore-reported reason when a developer
    // has picked one in the debug menu (#1881). Read it here so flipping the override
    // re-renders the overlay immediately.
    val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
    // Suppress the generic "point your camera at a surface" banner in ANALYSE mode —
    // there the failure breakdown is part of the interpretation overlay / report card,
    // and a second banner stacked on top would just clutter the analysis (#2027).
    if ((!isTracking || ForcedTrackingFailure.override != null) && mode != Mode.ANALYSE) {
        TrackingFailureBanner(reason = effectiveReason)
    }

    // Cover the still-black ARSceneView surface until ARCore delivers its first frame —
    // either the live camera feed, or the first replayed frame in PLAYBACK mode — so the
    // entry doesn't read as a frozen screen with a dimmed record button (#1473).
    ARCameraInitScrim(
        initializing = !cameraReady,
        label = when (mode) {
            Mode.PLAYBACK -> "Starting playback…"
            Mode.ANALYSE -> "Starting analysis…"
            else -> "Starting camera…"
        },
    )
}

@Composable
private fun RecordOverlay(
    recorder: ARRecorder,
    elapsedSeconds: Long,
    isTracking: Boolean,
    trackingFailureReason: TrackingFailureReason?,
    trackingLostSeconds: Long,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Elapsed-time pill + live tracking-quality pill, top-center, only while
        // recording. Inset below the system bars so they never overlap the status
        // bar / notch / camera cutout.
        AnimatedVisibility(
            visible = recorder.state == ARRecorder.State.RECORDING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(top = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = Color.Red.copy(alpha = 0.85f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "REC  ${formatElapsed(elapsedSeconds)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Tracking-quality pill (#1650): green "AR tracking OK" while ARCore
                // is TRACKING, amber/red with the failure reason when it isn't — so a
                // capture going bad is visible during the recording, not only after.
                TrackingQualityPill(
                    isTracking = isTracking,
                    reason = trackingFailureReason
                )
            }
        }

        // Soft warning when tracking has been lost for more than a few seconds —
        // e.g. a capture shot from inside a moving vehicle never tracks (#1650).
        if (recorder.state == ARRecorder.State.RECORDING &&
            trackingLostSeconds >= TRACKING_LOST_WARNING_SECONDS
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "AR tracking lost for ${trackingLostSeconds}s — this " +
                            "recording may be unusable. Point at a well-lit, textured " +
                            "scene and move slowly.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // Camera-style shutter button, bottom-center. Outer white ring + inner
        // colored disc — round red disc when idle (start), red rounded square
        // when recording (stop). Sized like a typical mobile camera app's
        // shutter (72 dp outer / 60 dp inner) so it reads as "tap here to
        // capture" without needing a label, and so the tap target meets
        // accessibility minimums (48 dp) with margin.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            val isRecording = recorder.state == ARRecorder.State.RECORDING
            // Greyed-out + non-clickable when AR isn't tracking yet — saves the
            // user from a confusing ERROR state and the disc visually fades
            // so the affordance "wait, it's not ready" is unambiguous.
            val isTappable = isRecording || startEnabled
            // Camera-app feel: a sharp pulse on shutter press so the user can
            // feel they started/stopped a recording without staring at the UI.
            // No haptic on a no-op tap (greyed shutter) — that would be
            // confusing rather than confirming. iOS/Android camera apps both
            // use a LongPress-strength haptic for shutter; matching it (#956).
            val shutterHaptic = rememberHapticFeedback()
            Surface(
                onClick = {
                    if (!isTappable) return@Surface
                    shutterHaptic.heavy()
                    if (isRecording) onStop() else onStart()
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.White.copy(alpha = if (isTappable) 0.18f else 0.08f),
                contentColor = Color.White,
                border = androidx.compose.foundation.BorderStroke(
                    width = 3.dp,
                    color = Color.White.copy(alpha = if (isTappable) 1f else 0.4f),
                ),
                modifier = Modifier.size(72.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isRecording) 30.dp else 56.dp)
                            .background(
                                color = Color.Red.copy(alpha = if (isTappable) 1f else 0.45f),
                                shape = if (isRecording) RoundedCornerShape(6.dp)
                                        else androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
            }
        }

        // Error pill, just above the button.
        if (recorder.state == ARRecorder.State.ERROR) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = recorder.errorMessage ?: "Recording failed",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact live tracking-quality pill shown under the REC timer while recording (#1650).
 *
 * - **Green** "AR tracking OK" when ARCore reports [TrackingState.TRACKING].
 * - **Amber/red** with the [TrackingFailureReason] (or a generic "tracking lost") when it
 *   does not — so the user sees a degraded capture in real time instead of discovering an
 *   empty playback afterwards.
 */
@Composable
private fun TrackingQualityPill(
    isTracking: Boolean,
    reason: TrackingFailureReason?
) {
    val healthy = isTracking
    val label = if (healthy) {
        "● AR tracking OK"
    } else {
        "▲ ${trackingFailureMessage(reason) ?: "AR tracking lost"}"
    }
    Surface(
        color = if (healthy) {
            Color(0xFF1B5E20).copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (healthy) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PlaybackBanner(filename: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = "Now replaying: $filename",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Live interpretation overlay shown over the replayed video in ANALYSE mode (#2027).
 *
 * A translucent bottom-left card with the four headline metrics from the running
 * [ARRecordInterpretation], updating every frame as [ARRecordInterpreter.ingest] folds in
 * the dataset: tracked-frame %, trajectory length, dominant tracking-failure reason, and
 * plane count. Stays compact so it never hides the camera feed it annotates — the full
 * breakdown lands in [AnalysisReportCard] when the dataset ends.
 */
@Composable
private fun AnalysisLiveOverlay(
    interpretation: io.github.sceneview.ar.recording.ARRecordInterpretation
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 12.dp, bottom = 12.dp),
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "◉ Analysing replay",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tracked  ${formatTrackedPercent(interpretation)}  " +
                        "(${interpretation.trackedFrameCount}/${interpretation.frameCount})",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Trajectory  ${"%.2f".format(interpretation.trajectoryLengthMeters)} m",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Planes  ${interpretation.planeCount}  " +
                        "(${interpretation.horizontalPlaneCount}H / ${interpretation.verticalPlaneCount}V)",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                dominantFailureLabel(interpretation)?.let { failure ->
                    Text(
                        text = "Worst fault  $failure",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFFCDD2)
                    )
                }
            }
        }
    }
}

/**
 * End-of-replay report card shown in ANALYSE mode once `rememberARPlaybackStatus` reports
 * [com.google.ar.core.PlaybackStatus.FINISHED] (#2027).
 *
 * Sums up the whole replayed dataset from the final [ARRecordInterpretation]: how long it
 * ran, the tracked-frame ratio (with a green/amber verdict), trajectory length + extent,
 * plane count + area, and the full per-[TrackingFailureReason] breakdown. This is the
 * deterministic "did this capture track well?" verdict the issue calls a report card.
 */
@Composable
private fun AnalysisReportCard(
    interpretation: io.github.sceneview.ar.recording.ARRecordInterpretation
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Replay analysis complete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${interpretation.frameCount} frames • " +
                        "${"%.1f".format(interpretation.durationSeconds)} s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // Headline tracking verdict — green when most frames tracked, amber/red
                // when the dataset is a hard tracking case worth flagging.
                val trackedPercent = (interpretation.trackedFrameRatio * 100).toInt()
                val healthy = trackedPercent >= TRACKING_HEALTH_GOOD_PERCENT
                Surface(
                    color = if (healthy) {
                        Color(0xFF1B5E20)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (healthy) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (healthy) {
                            "✓ Tracked $trackedPercent% of frames"
                        } else {
                            "▲ Tracked only $trackedPercent% of frames — hard tracking case"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))

                ReportRow(
                    "Trajectory length",
                    "${"%.2f".format(interpretation.trajectoryLengthMeters)} m"
                )
                ReportRow(
                    "Trajectory extent",
                    "${"%.2f".format(interpretation.trajectoryExtentMeters)} m"
                )
                ReportRow(
                    "Planes found",
                    "${interpretation.planeCount} " +
                        "(${interpretation.horizontalPlaneCount}H / ${interpretation.verticalPlaneCount}V)"
                )
                ReportRow(
                    "Plane area",
                    "${"%.2f".format(interpretation.planeAreaMeters2)} m²"
                )

                if (interpretation.failureReasonFrameCounts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tracking-failure breakdown",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    // Worst-first so the dominant failure reason is read at a glance.
                    interpretation.failureReasonFrameCounts.entries
                        .sortedByDescending { it.value }
                        .forEach { (reason, count) ->
                            ReportRow(
                                trackingFailureReasonName(reason),
                                "$count frames"
                            )
                        }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No tracking-failure frames — the whole dataset tracked.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** One label/value line in the [AnalysisReportCard]. */
@Composable
private fun ReportRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Tracked-frame ratio of an [ARRecordInterpretation] as a `NN%` string (`—` before any frame). */
private fun formatTrackedPercent(
    interpretation: io.github.sceneview.ar.recording.ARRecordInterpretation
): String = if (interpretation.frameCount == 0) {
    "—"
} else {
    "${(interpretation.trackedFrameRatio * 100).toInt()}%"
}

/**
 * Short label for the [TrackingFailureReason] that lost the most frames in an
 * interpretation, or `null` when nothing failed yet. Drives the "Worst fault" line of the
 * live overlay (#2027).
 */
private fun dominantFailureLabel(
    interpretation: io.github.sceneview.ar.recording.ARRecordInterpretation
): String? = interpretation.failureReasonFrameCounts.maxByOrNull { it.value }
    ?.let { (reason, count) -> "${trackingFailureReasonName(reason)} ($count)" }

/**
 * Concise human-readable name for a [TrackingFailureReason] — a few words, suitable for a
 * metrics row, not the full actionable sentence
 * [io.github.sceneview.demo.common.trackingFailureMessage] returns. Falls back to the enum
 * name so every reason is still labelled.
 */
private fun trackingFailureReasonName(reason: TrackingFailureReason): String = when (reason) {
    TrackingFailureReason.BAD_STATE -> "Bad state"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Insufficient light"
    TrackingFailureReason.EXCESSIVE_MOTION -> "Excessive motion"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Insufficient features"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
    TrackingFailureReason.NONE -> "None"
}

@Composable
private fun TrackingFailureBanner(reason: TrackingFailureReason?) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = trackingFailureMessage(reason)
                    ?: "Point your camera at a surface",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun RecordingsList(
    recordings: List<File>,
    selected: File?,
    justRecorded: File?,
    onSelect: (File) -> Unit,
    onExport: (File) -> Unit,
    onShare: (File) -> Unit,
    onOpen: (File) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Recordings (${recordings.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
            Spacer(Modifier.height(8.dp))
            if (recordings.isEmpty()) {
                Text(
                    text = "No recordings yet. Switch to Record mode to capture one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recordings.forEach { file ->
                    RecordingRow(
                        file = file,
                        isSelected = selected?.absolutePath == file.absolutePath,
                        isJustRecorded = justRecorded?.absolutePath == file.absolutePath,
                        onClick = { onSelect(file) },
                        onExport = { onExport(file) },
                        onShare = { onShare(file) },
                        onOpen = { onOpen(file) }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    file: File,
    isSelected: Boolean,
    isJustRecorded: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        // A thin accent outline on the file just captured this session so the
        // eye lands on it immediately — addresses the "recordings disappear
        // after a take" complaint from on-device QA (#1438).
        border = if (isJustRecorded) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isJustRecorded) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "● Just recorded",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "MP4 + ARCore data • ${formatBytes(file.length())} • " +
                    formatRelativeAge(file.lastModified()),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                ) { Text("Open") }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) { Text("Export") }
                Button(
                    onClick = onClick,
                    modifier = Modifier.weight(1f)
                ) {
                    // "Replaying" (9 chars) overflows the 1/3-row button width on
                    // Pixel 9 and wraps mid-word as "Replayin\ng" (#1205). "Stop"
                    // is the action the tap will perform when playback is active,
                    // and reads cleaner than a status word on an action button.
                    Text(
                        text = if (isSelected) "Stop" else "Replay",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Post-stop confirmation card shown in RECORD mode after [ARRecorder.stop].
 *
 * The earlier version only said "saved" + offered Replay/Share, which left two questions
 * from the on-device QA session unanswered (#1438): *where* did the file go, and *what*
 * is it? This version spells out the app-private path, makes clear the file is a normal
 * MP4 that also carries ARCore data tracks (so it opens in any video player), and offers
 * the full set of next steps — Replay, Share, Open as video, and Export to Downloads —
 * plus a shortcut into the Playback list so the recording is obviously discoverable.
 */
@Composable
private fun SavedRecordingCallout(
    file: File,
    recordingsCount: Int,
    trackingHealth: TrackingHealth?,
    onReplay: () -> Unit,
    onOpenInPlayback: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✓ Recording saved",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$recordingsCount in Playback",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${formatBytes(file.length())} • saved to the app's private storage",
                style = MaterialTheme.typography.labelSmall
            )
            // Tracking-quality summary of this take (#1650): the percentage of recorded
            // frames where ARCore was actually TRACKING. A low number means the capture
            // is likely unusable on playback — e.g. shot from a moving vehicle.
            trackingHealth?.takeIf { it.totalFrames > 0 }?.let { health ->
                Spacer(Modifier.height(6.dp))
                val good = health.healthPercent >= TRACKING_HEALTH_GOOD_PERCENT
                Surface(
                    color = if (good) {
                        Color(0xFF1B5E20).copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (good) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (good) {
                            "✓ Tracking healthy ${health.healthPercent}% of frames"
                        } else {
                            "▲ Tracking healthy only ${health.healthPercent}% of frames — " +
                                "this recording may have little usable AR content"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "This is a standard MP4 carrying ARCore data tracks (camera, IMU, " +
                    "planes, depth, anchors). \"Open\" plays it like any video; \"Export\" " +
                    "copies it to Downloads/SceneView/ so Files / Photos and adb pull can " +
                    "reach it; \"Replay\" re-runs the full AR session in the Playback tab.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                ) { Text("Open") }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) { Text("Export") }
                Button(
                    onClick = onReplay,
                    modifier = Modifier.weight(1f)
                ) { Text("Replay") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenInPlayback,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Show in Playback list") }
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Record another") }
        }
    }
}

private fun shareRecording(context: Context, file: File) {
    val authority = "${context.packageName}.fileprovider"
    val uri = try {
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: IllegalArgumentException) {
        // Misconfigured FileProvider — surface to the user rather than crashing.
        android.widget.Toast.makeText(
            context,
            "Couldn't share — FileProvider misconfigured: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AR session recording — SceneView")
        putExtra(
            Intent.EXTRA_TEXT,
            "ARCore session recording from SceneView. Replay 1:1 with " +
                "ARSceneView(playbackDataset = file)."
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share AR recording"))
}

/**
 * Opens the recording with a regular video viewer. ARCore datasets are valid MP4s — the
 * AR data lives in side tracks the player ignores — so any gallery / video app can play
 * the camera feed back. Gives the user a quick "did I capture the right thing?" check
 * without leaving for the Playback tab. Falls back to a toast if no video app is present.
 */
private fun openRecordingAsVideo(context: Context, file: File) {
    val authority = "${context.packageName}.fileprovider"
    val uri = try {
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: IllegalArgumentException) {
        android.widget.Toast.makeText(
            context,
            "Couldn't open — FileProvider misconfigured: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Open AR recording"))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(
            context,
            "No video player found — use Share or Export instead",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * Returns the current display rotation (`Surface.ROTATION_0` … `Surface.ROTATION_270`) so the
 * MP4 plays back upright when captured in landscape. Uses `Context.getDisplay()` on API 30+
 * and falls back to the deprecated `WindowManager.defaultDisplay` on older APIs.
 */
private fun currentDisplayRotation(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
}

/**
 * After how many continuous seconds without ARCore tracking the RECORD overlay surfaces
 * the "tracking lost — recording may be unusable" soft warning (#1650). Short enough to
 * catch a doomed capture early, long enough not to flash on a momentary hiccup.
 */
private const val TRACKING_LOST_WARNING_SECONDS = 4L

/**
 * Tracking-health percentage at or above which a finished recording's quality stat is
 * shown in green rather than as a warning (#1650).
 */
private const val TRACKING_HEALTH_GOOD_PERCENT = 70

/**
 * Immutable tracking-quality summary of one finished recording (#1650): how many ARCore
 * frames were consumed while recording and how many of those reported [TrackingState.TRACKING].
 */
private data class TrackingHealth(val trackedFrames: Int, val totalFrames: Int) {
    /** Percentage of recorded frames where ARCore was actually tracking (0..100). */
    val healthPercent: Int
        get() = if (totalFrames == 0) 0 else (trackedFrames * 100) / totalFrames
}

/**
 * Mutable per-recording accumulator for [TrackingHealth] (#1650).
 *
 * Ticked once per consumed ARCore frame from `onSessionUpdated` — which runs on the main
 * thread — so plain `Int` counters need no synchronisation. [reset] zeroes it at the start
 * of each take; [snapshot] produces the immutable summary handed to the saved-recording
 * callout on Stop.
 */
private class TrackingHealthTracker {
    private var trackedFrames = 0
    private var totalFrames = 0

    /** Record one consumed frame; [tracking] is whether ARCore reported TRACKING. */
    fun tick(tracking: Boolean) {
        totalFrames++
        if (tracking) trackedFrames++
    }

    /** Zero the counters — call on each fresh `recorder.start()`. */
    fun reset() {
        trackedFrames = 0
        totalFrames = 0
    }

    /** Immutable summary of the frames counted since the last [reset]. */
    fun snapshot(): TrackingHealth = TrackingHealth(trackedFrames, totalFrames)
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

private fun formatElapsed(seconds: Long): String {
    val mm = seconds / 60
    val ss = seconds % 60
    return "%02d:%02d".format(mm, ss)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatRelativeAge(epochMillis: Long): String {
    val seconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}
