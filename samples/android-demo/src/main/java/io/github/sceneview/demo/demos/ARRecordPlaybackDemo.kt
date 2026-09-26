package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.recording.ARRecordInterpretation
import io.github.sceneview.ar.recording.ARRecordInterpreter
import io.github.sceneview.ar.recording.ARRecorder
import io.github.sceneview.ar.recording.rememberARPlaybackStatus
import io.github.sceneview.ar.recording.rememberARRecordInterpreter
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.LiveStat
import io.github.sceneview.demo.demos.internal.NewFrameGate
import io.github.sceneview.demo.demos.internal.PlacementReplayQueue
import io.github.sceneview.demo.demos.internal.PlacementTrack
import io.github.sceneview.demo.demos.internal.PlacementWriteSchedule
import io.github.sceneview.demo.demos.internal.RecordingQaState
import io.github.sceneview.demo.demos.internal.RigidPose
import io.github.sceneview.demo.demos.internal.formatRecordingTitle
import io.github.sceneview.demo.demos.internal.liveCaptureStats
import io.github.sceneview.demo.demos.internal.lostReasonLines
import io.github.sceneview.demo.demos.internal.placementOf
import io.github.sceneview.demo.demos.internal.placementWorldPose
import io.github.sceneview.demo.demos.internal.readyToRecordLine
import io.github.sceneview.demo.demos.internal.recorderErrorLine
import io.github.sceneview.demo.demos.internal.recordingFileName
import io.github.sceneview.demo.demos.internal.recordingTitleOf
import io.github.sceneview.demo.demos.internal.savedTakeSummary
import io.github.sceneview.demo.demos.internal.takeQualityLine
import io.github.sceneview.demo.demos.internal.takeQualityOf
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * AR Recording — record an AR session, then replay it here as often as you like (#3831).
 *
 * ARCore's `Session.startRecording` writes an ordinary MP4: the camera video any player can
 * show, plus the motion-sensor and camera data ARCore reads back so that
 * `ARSceneView(playbackDataset = file)` replays the session 1:1 without holding the phone up.
 * This demo also writes a track of its own into the same file — where you placed each fox —
 * which is something ARCore offers beyond ARKit, and reads it back on replay to put the foxes
 * back where they were.
 *
 * Two steps in the dock, like a camera app:
 *
 * - **Record** — a live camera with a shutter. While recording, a card shows what is being
 *   captured, live: time, file size, frames, how far the phone moved, surfaces found and
 *   placements. Stopping swaps the shutter for the saved take: its first frame, its length,
 *   how steady it was, and Replay / Share.
 * - **Recordings** — every take on the phone with a frame from it, its length, size and what
 *   it holds. Tapping one replays it in place: a progress bar and the replay's own numbers
 *   while it plays, then a report of how it went.
 *
 * Replaying needs a fresh ARCore session bound to the file, so the scene is keyed on the step,
 * the replayed file and a replay generation ("Replay again").
 */
@Composable
fun ARRecordPlaybackDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val library = remember { RecordingLibrary() }
    val today = remember { LocalDate.now() }

    val qa = RecordingQaState.of(DemoSettings.qaDemoState?.takeIf { qaStateOverridesAllowed() })

    // `--es ar_playback_file <path>` (DemoSettings.arPendingPlaybackFile) opens straight into
    // a replay of that file — the entry the instrumentation replay tests and
    // `ar-replay-qa.sh` use. Consumed once so a recreation does not re-trigger it.
    val pendingFile = remember {
        DemoSettings.arPendingPlaybackFile?.let(::File)?.takeIf { it.exists() }
    }
    LaunchedEffect(Unit) { DemoSettings.arPendingPlaybackFile = null }

    var step by remember {
        mutableStateOf(
            if (pendingFile != null || qa.opensOnRecordings()) RecordingStep.Recordings else RecordingStep.Record
        )
    }
    var replayFile by remember { mutableStateOf(pendingFile) }
    var replayGeneration by remember { mutableIntStateOf(0) }
    var lastSaved by remember { mutableStateOf<SavedTake?>(null) }
    var newestName by remember { mutableStateOf<String?>(null) }

    val recordingsDir = remember(context) {
        requireNotNull(context.getExternalFilesDir("ar-recordings")).also { it.mkdirs() }
    }
    val recordings = remember { mutableStateListOf<File>() }
    fun refreshRecordings() {
        recordings.clear()
        if (qa != RecordingQaState.Empty) recordings.addAll(listRecordings(recordingsDir))
    }
    // The debug build ships a sample take (#934) so the gallery is never empty on a fresh
    // install — and so the emulator, which cannot record, has something to show.
    LaunchedEffect(recordingsDir) {
        extractBundledRecordings(context, recordingsDir)
        refreshRecordings()
    }
    LaunchedEffect(step, replayFile) { refreshRecordings() }

    // QA "saved": the card that replaces the shutter, on the newest file in the folder.
    LaunchedEffect(qa, recordings.firstOrNull()) {
        val newest = recordings.firstOrNull()
        if (qa == RecordingQaState.Saved && newest != null) {
            lastSaved = SavedTake(newest, QA_DURATION_MILLIS, placements = 2, trackedFrames = 512, frames = 550)
        }
    }

    val take = key(step, replayFile?.absolutePath, replayGeneration) {
        val recorder = rememberARRecorder()
        val interpreter = rememberARRecordInterpreter()
        remember { TakeState(recorder, interpreter) }
    }
    val replaying = replayFile != null
    val isRecording = take.recorder.state == ARRecorder.State.RECORDING || qa == RecordingQaState.Recording
    val playbackStatus by rememberARPlaybackStatus(if (replaying) take.arSession else null)
    val replayFinished = replaying && playbackStatus == PlaybackStatus.FINISHED
    val replayDurationMillis by produceState(0L, replayFile) {
        value = replayFile?.let { library.details(it).durationMillis } ?: 0L
    }

    // Recording clock and file size, polled rather than per frame.
    var recordingElapsedMillis by remember(take) { mutableLongStateOf(0L) }
    var recordingSizeBytes by remember(take) { mutableLongStateOf(0L) }
    var lostForMillis by remember(take) { mutableLongStateOf(0L) }
    LaunchedEffect(take, take.recorder.state) {
        if (take.recorder.state != ARRecorder.State.RECORDING) {
            recordingElapsedMillis = 0L
            recordingSizeBytes = 0L
            lostForMillis = 0L
            return@LaunchedEffect
        }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            recordingElapsedMillis = now - take.recordingStartedMillis
            lostForMillis = if (take.isTracking || take.lastTrackedMillis == 0L) 0L else now - take.lastTrackedMillis
            recordingSizeBytes = withContext(Dispatchers.IO) { take.recordingFile?.length() ?: 0L }
            delay(RECORDING_POLL_MILLIS)
        }
    }

    fun startRecording() {
        take.placementTrack = take.recorder.addTrack(PlacementTrack.TRACK_ID, PlacementTrack.MIME_TYPE)
        take.interpreter.reset()
        take.writeSchedule.reset()
        val file = File(recordingsDir, recordingFileName(LocalDateTime.now()))
        take.recordingFile = file
        take.recordingStartedMillis = SystemClock.elapsedRealtime()
        lastSaved = null
        take.recorder.start(file = file, recordingRotation = recordingSurfaceRotation(context, take.arSession))
    }

    fun stopRecording() {
        val file = take.recordingFile
        val durationMillis = SystemClock.elapsedRealtime() - take.recordingStartedMillis
        val interpretation = take.interpreter.interpretation
        take.recorder.stop()
        take.recordingFile = null
        if (file != null && file.exists() && file.length() > 0L) {
            lastSaved = SavedTake(
                file = file,
                durationMillis = durationMillis,
                placements = take.anchors.size,
                trackedFrames = interpretation.trackedFrameCount,
                frames = interpretation.frameCount,
            )
            newestName = file.name
        }
        refreshRecordings()
    }

    fun openReplay(file: File) {
        lastSaved = null
        step = RecordingStep.Recordings
        replayFile = file
    }

    fun share(file: File) {
        if (!shareRecording(context, file)) {
            Toast.makeText(context, resources.getString(R.string.ar_rec_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun saveToDownloads(file: File) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { ARRecorder.exportToDownloads(context, file) }
            val message = if (uri != null) {
                resources.getString(R.string.ar_rec_saved_to_downloads, file.name)
            } else {
                resources.getString(R.string.ar_rec_save_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun delete(file: File) {
        file.delete()
        library.forget(file)
        refreshRecordings()
    }

    // Back from a replay returns to the gallery rather than leaving the demo. Declared before
    // the scaffold so the settings sheet's own back handler still wins while it is open.
    BackHandler(enabled = replaying) { replayFile = null }

    val showsCamera = step == RecordingStep.Record || replaying
    val qaReplayPreview = replayFile == null && step == RecordingStep.Recordings &&
        (qa == RecordingQaState.Replaying || qa == RecordingQaState.ReplayFinished)
    val replayTitle = replayFile?.let { formatRecordingTitle(recordingTitleOf(it.name), today) }
        ?: formatRecordingTitle(recordingTitleOf("bundled-pixel9-sample.mp4"), today)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_record_playback_title),
        onBack = onBack,
        arSessionFailed = showsCamera && take.sessionFailed,
        arOverlaysEnabled = !showsCamera || take.arCoreAvailability == null,
        dock = listOf(
            DockItem(
                icon = Icons.Rounded.Videocam,
                label = stringResource(R.string.ar_rec_step_record),
                onClick = {
                    replayFile = null
                    step = RecordingStep.Record
                },
                enabled = !isRecording,
                selected = step == RecordingStep.Record,
            ),
            DockItem(
                icon = Icons.Rounded.VideoLibrary,
                label = stringResource(R.string.ar_rec_step_recordings),
                onClick = {
                    lastSaved = null
                    replayFile = null
                    step = RecordingStep.Recordings
                },
                enabled = !isRecording,
                selected = step == RecordingStep.Recordings,
            ),
        ),
        controls = {
            RecordingKeepsSection()
            // QA only: stage each tracking failure without a real one (#1881).
            ForceTrackingFailureMenu()
        },
        topOverlay = {
            when {
                step == RecordingStep.Record && isRecording -> {
                    val live = liveCaptureView(take, qa, recordingElapsedMillis, recordingSizeBytes, lostForMillis)
                    LiveCaptureCard(
                        elapsedMillis = live.elapsedMillis,
                        sizeBytes = live.sizeBytes,
                        stats = live.stats,
                        guidance = live.guidance,
                        mayNotReplay = live.mayNotReplay,
                    )
                }
                (replaying && take.cameraReady && !replayFinished) ||
                    (qaReplayPreview && qa == RecordingQaState.Replaying) -> {
                    val preview = qa == RecordingQaState.Replaying && !replaying
                    val interpretation = take.interpreter.interpretation
                    ReplayCard(
                        title = replayTitle,
                        elapsedMillis = if (preview) QA_REPLAY_ELAPSED_MILLIS else take.replayElapsedMillis,
                        durationMillis = if (preview) QA_DURATION_MILLIS else replayDurationMillis,
                        stats = if (preview) {
                            liveCaptureStats(212, 1.1f, 2, 2, placementsLabel = RESTORED_LABEL)
                        } else {
                            liveCaptureStats(
                                interpretation.frameCount,
                                interpretation.trajectoryLengthMeters,
                                interpretation.planeCount,
                                take.restoredCount,
                                placementsLabel = RESTORED_LABEL,
                            )
                        },
                        onStop = { replayFile = null },
                    )
                }
            }
        },
        bottomOverlay = {
            when {
                replayFinished || (qaReplayPreview && qa == RecordingQaState.ReplayFinished) -> {
                    val interpretation = if (replayFinished) take.interpreter.interpretation else QA_REPORT
                    val restored = if (replayFinished) take.restoredCount else 2
                    ReplayReportCard(
                        title = replayTitle,
                        stats = liveCaptureStats(
                            interpretation.frameCount,
                            interpretation.trajectoryLengthMeters,
                            interpretation.planeCount,
                            restored,
                            placementsLabel = RESTORED_LABEL,
                        ),
                        quality = takeQualityOf(interpretation.trackedFrameCount, interpretation.frameCount),
                        qualityLine = takeQualityLine(interpretation.trackedFrameCount, interpretation.frameCount),
                        lostReasons = lostReasonLines(
                            interpretation.failureReasonFrameCounts.mapKeys { it.key.name },
                            interpretation.frameCount,
                        ),
                        onReplayAgain = { replayGeneration++ },
                        onAllRecordings = { replayFile = null },
                    )
                }
                step == RecordingStep.Record -> RecordStepBottom(
                    take = take,
                    isRecording = isRecording,
                    lastSaved = lastSaved,
                    library = library,
                    onStart = ::startRecording,
                    onStop = ::stopRecording,
                    onReplay = ::openReplay,
                    onShare = ::share,
                    onCloseSaved = { lastSaved = null },
                )
            }
        },
    ) {
        when {
            qaReplayPreview -> QaCameraBackdrop(seed = QA_SEED, modifier = Modifier.fillMaxSize())
            showsCamera -> key(step, replayFile?.absolutePath, replayGeneration) {
                TakeScene(
                    take = take,
                    replayFile = replayFile,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                )
            }
            else -> RecordingsGallery(
                recordings = recordings,
                library = library,
                newestName = newestName,
                onReplay = ::openReplay,
                onShare = ::share,
                onSaveToDownloads = ::saveToDownloads,
                onDelete = ::delete,
                onRecord = { step = RecordingStep.Record },
            )
        }
    }
}

/** The Record step's bottom band: guidance, then the shutter or the take just saved. */
@Composable
private fun DemoBottomOverlayScope.RecordStepBottom(
    take: TakeState,
    isRecording: Boolean,
    lastSaved: SavedTake?,
    library: RecordingLibrary,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReplay: (File) -> Unit,
    onShare: (File) -> Unit,
    onCloseSaved: () -> Unit,
) {
    val recorderState = take.recorder.state
    val failed = recorderState == ARRecorder.State.ERROR || recorderState == ARRecorder.State.IO_ERROR
    val forced = ForcedTrackingFailure.override
    val tracking = take.isTracking && forced == null

    if (lastSaved != null && !isRecording) {
        val thumbnail by produceState<ImageBitmap?>(library.cached(lastSaved.file)?.thumbnail, lastSaved.file) {
            value = library.details(lastSaved.file).thumbnail
        }
        SavedTakeCard(
            thumbnail = thumbnail,
            summary = savedTakeSummary(lastSaved.durationMillis, lastSaved.file.length(), lastSaved.placements),
            quality = takeQualityOf(lastSaved.trackedFrames, lastSaved.frames),
            qualityLine = takeQualityLine(lastSaved.trackedFrames, lastSaved.frames),
            onReplay = { onReplay(lastSaved.file) },
            onShare = { onShare(lastSaved.file) },
            onClose = onCloseSaved,
        )
        return
    }

    if (!isRecording) {
        when {
            failed -> DemoStatusBanner(
                text = recorderErrorLine(storageFailed = recorderState == ARRecorder.State.IO_ERROR),
                tone = DemoStatusTone.Blocked,
            )
            !tracking && take.cameraReady -> {
                val reason = forced ?: take.trackingFailureReason
                val message = trackingFailureMessage(reason)
                DemoStatusBanner(
                    text = message ?: stringResource(R.string.ar_status_scanning),
                    tone = if (message != null) DemoStatusTone.Guidance else DemoStatusTone.Progress,
                )
            }
            tracking -> DemoStatusBanner(
                text = readyToRecordLine(take.anchors.size),
                tone = DemoStatusTone.Guidance,
            )
        }
    }
    RecordShutter(
        isRecording = isRecording,
        // A take started before the camera has its bearings fails; ERROR allows a retry.
        startEnabled = tracking || failed,
        onStart = onStart,
        onStop = onStop,
    )
}

/** What the live card shows — the real take, or the fixed QA "recording" screen. */
private class LiveCaptureView(
    val elapsedMillis: Long,
    val sizeBytes: Long,
    val stats: List<LiveStat>,
    val guidance: String?,
    val mayNotReplay: Boolean,
)

@Composable
private fun liveCaptureView(
    take: TakeState,
    qa: RecordingQaState?,
    elapsedMillis: Long,
    sizeBytes: Long,
    lostForMillis: Long,
): LiveCaptureView {
    if (qa == RecordingQaState.Recording && take.recorder.state != ARRecorder.State.RECORDING) {
        return LiveCaptureView(
            elapsedMillis = 42_000,
            sizeBytes = 38_400_000,
            stats = liveCaptureStats(1_204, 2.3f, 3, 2),
            guidance = null,
            mayNotReplay = false,
        )
    }
    val interpretation = take.interpreter.interpretation
    val forced = ForcedTrackingFailure.override
    val lost = !take.isTracking || forced != null
    val guidance = if (lost) {
        trackingFailureMessage(forced ?: take.trackingFailureReason) ?: stringResource(R.string.ar_status_scanning)
    } else {
        null
    }
    return LiveCaptureView(
        elapsedMillis = elapsedMillis,
        sizeBytes = sizeBytes,
        stats = liveCaptureStats(
            interpretation.frameCount,
            interpretation.trajectoryLengthMeters,
            interpretation.planeCount,
            take.anchors.size,
        ),
        guidance = guidance,
        mayNotReplay = lost && (forced != null || lostForMillis >= MAY_NOT_REPLAY_AFTER_MILLIS),
    )
}

/** The live or replayed AR scene: camera, surfaces, and a fox on every placement. */
@Composable
private fun TakeScene(
    take: TakeState,
    replayFile: File?,
    engine: com.google.android.filament.Engine,
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
) {
    val replaying = replayFile != null
    val cameraStream = rememberARCameraStream(materialLoader)
    // QA camera backdrop (#3308): the emulator delivers no camera frame.
    val qaBackdrop = rememberQaCameraBackdropActive(take.cameraReady)

    // Frame-indexed replay hook (#1050): ARPlaybackScreenshotTest and ar-replay-qa.sh wait
    // on DemoSettings.arPlaybackFrameCount, zeroed each time a dataset is mounted.
    LaunchedEffect(replayFile) {
        if (replayFile != null) DemoSettings.arPlaybackFrameCount = 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (qaBackdrop) QaCameraBackdrop(seed = QA_SEED)
        ARSceneView(
            onSessionFailure = { take.sessionFailed = true },
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            isOpaque = !qaCameraBackdropEnabled(),
            surfaceType = qaCameraBackdropSurfaceType(),
            cameraStream = if (qaBackdrop) null else cameraStream,
            planeRenderer = true,
            playbackDataset = replayFile,
            sessionConfiguration = { _: Session, config: Config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onARCoreAvailability = { take.arCoreAvailability = it },
            onSessionUpdated = { session: Session, frame: Frame -> take.onFrame(session, frame, replaying) },
            onTrackingFailureChanged = { take.trackingFailureReason = it },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event: MotionEvent, _ ->
                    // A replay puts back what was recorded; it does not take new placements.
                    if (!replaying) take.placeAt(event)
                }
            ),
        ) {
            take.anchors.forEach { anchor ->
                // One model instance per fox: a Filament instance can only hang off one node.
                key(anchor) {
                    val fox = rememberModelInstance(modelLoader, FOX_ASSET)
                    AnchorNode(anchor = anchor) {
                        fox?.let { ModelNode(modelInstance = it, scaleToUnits = FOX_SIZE_METERS) }
                    }
                }
            }
        }
        ARCameraInitScrim(
            initializing = !take.cameraReady,
            arCoreAvailability = take.arCoreAvailability,
            label = stringResource(if (replaying) R.string.ar_rec_starting_replay else R.string.ar_starting_camera),
        )
    }
}

private enum class RecordingStep { Record, Recordings }

/** The take just stopped, for the card that replaces the shutter. */
private data class SavedTake(
    val file: File,
    val durationMillis: Long,
    val placements: Int,
    val trackedFrames: Int,
    val frames: Int,
)

/**
 * Everything one ARCore session of this demo owns — a live camera or one replay. Created per
 * (step, replayed file, replay generation), in lockstep with the scene's ARSceneView, and
 * read by both the scene and the overlay slots.
 */
@Stable
private class TakeState(
    val recorder: ARRecorder,
    val interpreter: ARRecordInterpreter,
) {
    var sessionFailed by mutableStateOf(false)
    var arCoreAvailability by mutableStateOf<ARCoreAvailability?>(null)
    var cameraReady by mutableStateOf(false)
    var isTracking by mutableStateOf(false)
    var trackingFailureReason by mutableStateOf<TrackingFailureReason?>(null)

    /** Published for `rememberARPlaybackStatus`, which has no other handle on the session. */
    var arSession by mutableStateOf<Session?>(null)

    val anchors = mutableStateListOf<Anchor>()
    var restoredCount by mutableIntStateOf(0)
    var replayElapsedMillis by mutableLongStateOf(0L)

    var lastTrackedMillis = 0L
    var recordingFile: File? = null
    var recordingStartedMillis = 0L
    var placementTrack: ARRecorder.TrackHandle? = null
    val writeSchedule = PlacementWriteSchedule()

    private var latestFrame: Frame? = null
    private var firstFrameNanos = 0L
    private val frameGate = NewFrameGate()
    private val replayQueue = PlacementReplayQueue()

    /** ARCore copies the packet synchronously, so one direct buffer serves every write. */
    private val packet: ByteBuffer =
        ByteBuffer.allocateDirect(PlacementTrack.PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** `onSessionUpdated` — runs on the thread that owns [frame]. */
    fun onFrame(session: Session, frame: Frame, replaying: Boolean) {
        cameraReady = true
        latestFrame = frame
        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        isTracking = tracking
        if (arSession !== session) arSession = session
        // Stateless side channel (#876): publishes the session to the recorder. Idempotent.
        recorder.recordFrame(session)
        if (replaying) DemoSettings.arPlaybackFrameCount++
        // onSessionUpdated repeats a camera image until the next one arrives: count and read
        // tracks once per image, not once per display refresh.
        if (!frameGate.isNew(frame.timestamp)) return
        if (tracking) lastTrackedMillis = SystemClock.elapsedRealtime()
        when {
            replaying -> onReplayFrame(session, frame, tracking)
            recorder.state == ARRecorder.State.RECORDING -> onRecordingFrame(session, frame, tracking)
        }
    }

    private fun onReplayFrame(session: Session, frame: Frame, tracking: Boolean) {
        interpreter.ingest(session, frame)
        if (firstFrameNanos == 0L) firstFrameNanos = frame.timestamp
        val elapsed = (frame.timestamp - firstFrameNanos) / NANOS_PER_MILLI
        replayElapsedMillis = elapsed - elapsed % REPLAY_CLOCK_STEP_MILLIS
        val packets = try {
            frame.getUpdatedTrackData(PlacementTrack.TRACK_ID)
        } catch (_: Exception) {
            return
        }
        if (packets.isEmpty()) return
        val camera = frame.camera.pose.toRigidPose()
        packets
            .mapNotNull { PlacementTrack.decode(it.data) }
            .filter { replayQueue.shouldRestore(it, tracking) }
            .forEach { placement ->
                val anchor = try {
                    session.createAnchor(placementWorldPose(placement, camera).toArPose())
                } catch (_: Exception) {
                    null
                }
                if (anchor != null) anchors += anchor
            }
        restoredCount = anchors.size
    }

    /**
     * Writes every placement into the recording, relative to the camera, about once a second
     * — anchors placed before the take started included, so a replay that misses one packet
     * takes the next.
     */
    private fun onRecordingFrame(session: Session, frame: Frame, tracking: Boolean) {
        interpreter.ingest(session, frame)
        val handle = placementTrack ?: return
        if (!tracking) return
        val camera = frame.camera.pose.toRigidPose()
        anchors.forEachIndexed { index, anchor ->
            if (anchor.trackingState != TrackingState.TRACKING) return@forEachIndexed
            if (!writeSchedule.isDue(index, frame.timestamp)) return@forEachIndexed
            val bytes = PlacementTrack.encode(placementOf(index, camera, anchor.pose.toRigidPose()))
            packet.clear()
            packet.put(bytes)
            packet.flip()
            if (recorder.recordTrack(handle, frame, packet)) writeSchedule.markWritten(index, frame.timestamp)
        }
    }

    /** Tap-to-place: the nearest surface under the finger within reach. */
    fun placeAt(event: MotionEvent) {
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val hit = frame.hitTest(event).firstOrNull { result ->
            val trackable = result.trackable
            trackable is Plane && trackable.isPoseInPolygon(result.hitPose) && result.distance <= MAX_PLACE_DISTANCE
        } ?: return
        anchors += hit.createAnchor()
    }
}

private fun Pose.toRigidPose() = RigidPose(tx(), ty(), tz(), qx(), qy(), qz(), qw())

private fun RigidPose.toArPose() = Pose(floatArrayOf(tx, ty, tz), floatArrayOf(qx, qy, qz, qw))

private fun RecordingQaState?.opensOnRecordings(): Boolean = when (this) {
    RecordingQaState.Recordings, RecordingQaState.Replaying,
    RecordingQaState.ReplayFinished, RecordingQaState.Empty -> true
    else -> false
}

private const val FOX_ASSET = "models/khronos_fox.glb"
private const val FOX_SIZE_METERS = 0.3f
private const val MAX_PLACE_DISTANCE = 5.0f
private const val NANOS_PER_MILLI = 1_000_000L
private const val REPLAY_CLOCK_STEP_MILLIS = 100L
private const val RECORDING_POLL_MILLIS = 250L
private const val MAY_NOT_REPLAY_AFTER_MILLIS = 4_000L
private const val RESTORED_LABEL = "Restored"
private const val QA_SEED = "ar-record-playback"

// Fixed values of the QA screens (`--ez qa_mode true --es qa_state <id>`), so each card can be
// captured on the emulator, which cannot run AR (#2754).
private const val QA_DURATION_MILLIS = 18_300L
private const val QA_REPLAY_ELAPSED_MILLIS = 7_000L
private val QA_REPORT = ARRecordInterpretation(
    frameCount = 550,
    trackedFrameCount = 512,
    durationSeconds = 18.3,
    trajectoryLengthMeters = 2.4f,
    trajectoryExtentMeters = 1.6f,
    failureReasonFrameCounts = mapOf(
        TrackingFailureReason.EXCESSIVE_MOTION to 26,
        TrackingFailureReason.INSUFFICIENT_FEATURES to 12,
    ),
    horizontalPlaneCount = 2,
    verticalPlaneCount = 1,
    planeAreaMeters2 = 3.2f,
)
