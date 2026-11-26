package io.github.sceneview.demo.demos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Surface as AndroidSurface
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.utils.SurfaceMirrorer
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoFullScreenSystemBars
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.demos.internal.AskAnchorWatch
import io.github.sceneview.demo.demos.internal.AskArLiveConfig
import io.github.sceneview.demo.demos.internal.AskContext
import io.github.sceneview.demo.demos.internal.AskFrameCapture
import io.github.sceneview.demo.demos.internal.AskFloor
import io.github.sceneview.demo.demos.internal.AskLiveContext
import io.github.sceneview.demo.demos.internal.AskRoom
import io.github.sceneview.demo.demos.internal.AskYuvFrame
import io.github.sceneview.demo.demos.internal.DrawingPlan
import io.github.sceneview.demo.demos.internal.HangPicture
import io.github.sceneview.demo.demos.internal.InkSheet
import io.github.sceneview.demo.demos.internal.SheetFacing
import io.github.sceneview.demo.demos.internal.SofaFit
import io.github.sceneview.demo.demos.internal.SofaSpot
import io.github.sceneview.demo.demos.internal.WallEdges
import io.github.sceneview.demo.demos.internal.WallHitKind
import io.github.sceneview.demo.demos.internal.WallPick
import io.github.sceneview.demo.demos.internal.WallSpot
import io.github.sceneview.demo.demos.internal.Doodle
import io.github.sceneview.demo.demos.internal.Handwriting
import io.github.sceneview.demo.demos.internal.HandwritingLayout
import io.github.sceneview.demo.demos.internal.InkPoint
import io.github.sceneview.demo.demos.internal.InkStroke
import io.github.sceneview.demo.demos.internal.IncrementalHandwriting
import io.github.sceneview.demo.demos.internal.ScriptedAnswerProvider
import io.github.sceneview.demo.demos.internal.StreamingAnswerProvider
import io.github.sceneview.demo.demos.internal.StrokeProgress
import io.github.sceneview.demo.demos.internal.askCameraRotationDegrees
import io.github.sceneview.demo.demos.internal.askLiveContextFromFrame
import io.github.sceneview.demo.demos.internal.playAskStream
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.GlassIconButton
import io.github.sceneview.demo.ui.GlassSurface
import io.github.sceneview.geometries.Tube
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ask in AR — ask a question out loud, and the answer writes itself by hand in the room.
 *
 * - **The ink** is Hershey Script Simplex, a single-stroke cursive font, laid out and timed by
 *   [HandwritingLayout]. Each stroke is a [TubeNode] that grows a few points per frame — a
 *   `capacity` sized to the whole stroke, so growing rewrites its buffers in place — with
 *   per-point `radii` for the pen's pressure and round caps for its ends.
 * - **The block** hangs on an ARCore anchor 1.5 m in front of the phone at eye height, facing
 *   it (yaw only), or on the wall straight ahead when ARCore found one closer than 3 m. An
 *   answer about the surface ahead ("how far is that wall?") is measured on it — a vertical
 *   plane or a depth hit — and hangs there, its arrow's tip on the spot it measured.
 * - **The question** comes from Android's `SpeechRecognizer`, with a live caption while it
 *   listens. Answers are scripted on the phone by default, or streamed through the optional
 *   [StreamingAnswerProvider] live path.
 *
 * **Automation for filming:** `sceneview://demo/ask-ar?q=what%20should%20I%20cook%20tonight`
 * (or `--es ask_q "…"`) plays the whole sequence by itself once ARCore tracks: listening,
 * the caption word by word, then the answer writing itself. No voice, no tap. A second
 * question, `&q2=…` (or `--es ask_q2 "…"`), follows once the first answer has stayed up
 * [CHAIN_HOLD_MILLIS] and unwritten itself.
 */
@Composable
fun AskInArDemo(onBack: () -> Unit) {
    // LayoutLib ships no Filament: stop before any rememberEngine().
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = stringResource(R.string.demo_ask_ar_title), onBack = onBack)
        return
    }
    val activity = LocalActivity.current
    val context = LocalContext.current
    val proxyUrl = AskArLiveConfig.proxyUrl(context, activity?.intent)
    val provider = remember(proxyUrl) { AskArLiveConfig.provider(proxyUrl) }
    LaunchedEffect(proxyUrl) {
        if (proxyUrl.isNotBlank()) Log.i(LOG_TAG, "live: on ${Uri.parse(proxyUrl).host ?: "unknown"}")
    }
    AskInArScene(onBack, provider, liveEnabled = proxyUrl.isNotBlank())
}

/** Where the demo is between a question and its answer. */
internal enum class AskPhase { Idle, Listening, Thinking, Writing, Done }

/** Everything the screen shows, and what the AR frame callback reads. */
@Stable
private class AskState {
    var phase by mutableStateOf(AskPhase.Idle)
    var transcript by mutableStateOf("")

    /** The question being answered, as the conversation on screen shows it. */
    var question by mutableStateOf("")

    /** The answer in full sentences, as the conversation shows it: see [AskAnswer.reply]. */
    var reply by mutableStateOf("")

    /** The reply is typed out with the pen's clock; a live one is shown as it streams in. */
    var replyTyped by mutableStateOf(true)
    var handwriting by mutableStateOf<Handwriting?>(null)
    var anchor by mutableStateOf<Anchor?>(null)
    var elapsed by mutableFloatStateOf(0f)

    /** 0..1, how loud the speaker is — drives the mic's rings. */
    var level by mutableFloatStateOf(0f)

    /** A string resource to say for a few seconds, or `null`. */
    var notice by mutableStateOf<Int?>(null)

    /** QA stage only: the block is up, at its fixed spot in front of the camera. */
    var stagePlaced by mutableStateOf(false)

    /** The block shrunk to fit the screen where it hangs: see [HandwritingLayout.fitScale]. */
    var blockScale by mutableFloatStateOf(1f)

    /** Set by the flow, acted on by the next tracked camera frame: it needs the camera's pose. */
    var placeRequested = false

    /** The answer points at the surface ahead: hang it there, by its arrow's tip. */
    var placeOnSurface = false

    /** Where a drawing plan goes, in world space: anchored there as it is, by the next frame. */
    var placePose: Pose? = null

    /** A live answer hung at [placePose]: sized to the screen at that distance when anchored. */
    var fitOnPlace = false

    /**
     * While ARCore has lost tracking, the handwriting being written holds still in the middle
     * of the screen, as far away as its anchor was: a world position, a yaw in degrees, and
     * the scale that fits it to the screen at that distance. Its anchor hides meanwhile, and
     * takes the answer back when tracking returns.
     */
    var held by mutableStateOf<Triple<Position, Float, Float>?>(null)

    /** How far in front of the phone [held] hangs. */
    var holdDistance = HOLD_MIN_METRES

    /** Hold, release or re-anchor the answer as tracking comes and goes: see [AskAnchorWatch]. */
    val watch = AskAnchorWatch()

    /** Where the answer's anchor last was while tracked: a re-anchor goes back there. */
    var lastTrackedPose: Pose? = null

    /** Tries left to move a re-anchored answer onto the wall where it shows: see [REFINE_TRIES]. */
    var refineTries = 0
    var refineWait = 0

    /** Distance to whatever the centre of the screen points at, read every few frames. */
    var distanceAhead: Float? = null
    var frameCount = 0

    /** Earlier answers left where they were written, oldest first: AR only. */
    var pinned by mutableStateOf<List<PinnedAnswer>>(emptyList())

    /** An answer drawn in the room itself (a level line, a footprint), instead of [handwriting]. */
    var plan by mutableStateOf<DrawingPlan?>(null)

    /** Set by the flow, filled in by the next tracked camera frame: see [AskRoomReader]. */
    var roomRequested = false
    var roomFrames = 0
    var room by mutableStateOf<AskRoom?>(null)

    /** The current anchor's tracking state as last logged. */
    var anchorState: TrackingState? = null

    fun clearAnswer() {
        question = ""
        reply = ""
        replyTyped = true
        handwriting = null
        plan = null
        anchor = null
        stagePlaced = false
        elapsed = 0f
        placeRequested = false
        placeOnSurface = false
        placePose = null
        fitOnPlace = false
        held = null
        watch.reset()
        lastTrackedPose = null
        refineTries = 0
        refineWait = 0
        anchorState = null
    }

    /**
     * Leaves the answer on screen where it hangs, finished, and frees the pen for the next.
     * Nothing to pin on the QA stage: its block has no anchor, the next one takes its place.
     */
    fun pinAnswer() {
        val anchor = anchor
        val handwriting = handwriting
        val plan = plan
        if (anchor != null && (handwriting != null || plan != null)) {
            // One dropped here leaves the scene, and its node detaches its anchor.
            pinned = (pinned + PinnedAnswer(anchor, handwriting, plan, blockScale)).takeLast(MAX_PINNED)
        }
        clearAnswer()
    }

    fun clearPinned() {
        pinned = emptyList()
    }
}

/** An answer written earlier, still on its anchor: words on a block, or a drawing in the room. */
private class PinnedAnswer(val anchor: Anchor, val handwriting: Handwriting?, val plan: DrawingPlan?, val scale: Float) {
    val totalSeconds: Float get() = plan?.totalSeconds ?: handwriting?.totalSeconds ?: 0f
}

/**
 * A run of questions to play by themselves (see [AskArAutomation]). A new instance each time,
 * so the same questions sent twice play twice.
 *
 * @param keepPrevious The answer on screen stays where it is (a question sent while the demo
 *                     was open, the phone since carried elsewhere).
 */
private class AskRequest(val questions: List<String>, val keepPrevious: Boolean)

@Composable
private fun AskInArScene(
    onBack: () -> Unit,
    provider: StreamingAnswerProvider,
    liveEnabled: Boolean,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { AskState() }
    val scope = rememberCoroutineScope()
    val displayView = LocalView.current
    val cameraManager = remember(context) { context.getSystemService(CameraManager::class.java) }
    var captureRequest by remember {
        mutableStateOf<CompletableDeferred<Triple<AskLiveContext, AskYuvFrame?, Int>>?>(null)
    }
    var flow by remember { mutableStateOf<Job?>(null) }

    var isTracking by remember { mutableStateOf(false) }
    // Seconds since the session's first frame, from the frames' own timestamps: on a replay,
    // the recording's clock, so a filming cue lands on the same scene on every take.
    var sessionSeconds by remember { mutableStateOf(0f) }
    var firstFrameNanos by remember { mutableStateOf(0L) }
    var cameraReady by remember { mutableStateOf(false) }
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val currentViewSize by rememberUpdatedState(viewSize)

    // QA stage (debug `--ez qa_backdrop true` or `qa_mode`): the arm64 emulator cannot run
    // ARCore (#2754), so the same ink is written over the QA room photo by a plain 3D scene,
    // 1.5 m in front of a fixed camera. Everything but the anchoring is the real thing.
    val stage = remember { qaCameraBackdropEnabled() }
    LaunchedEffect(stage) {
        if (stage) {
            cameraReady = true
            isTracking = true
            // No ARCore to measure with: the room photo's far wall, as a hit would read it.
            state.distanceAhead = STAGE_WALL_METRES
        }
    }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine)
    val cameraNode = rememberARCameraNode(engine)
    val recorder = rememberDemoSceneRecorder()
    // Replay QA (debug `--es ar_playback_file`): a recorded room instead of the camera.
    val arPlaybackDataset = rememberArPlaybackDataset()
    // Unlit: the ink keeps its colour in a dim room and on a sunlit wall alike. A bright core
    // over a deeper rim reads on a white wall as well as on a dark sofa.
    val ink = remember(materialLoader) { materialLoader.createUnlitColorInstance(INK_CORE) }
    val rim = remember(materialLoader) { materialLoader.createUnlitColorInstance(INK_RIM) }

    /** The room as the next tracked frame reads it; the QA stage's fixed room on the stage. */
    suspend fun readRoom(question: String): AskRoom {
        if (stage) return stageRoom(question)
        state.room = null
        state.roomRequested = true
        return snapshotFlow { state.room }.first { it != null }!!
    }

    /** Runs the pen's clock, [AskState.elapsed], from nothing to [total] seconds. */
    suspend fun writeFor(total: Float) {
        val start = withFrameNanos { it }
        while (state.elapsed < total) {
            withFrameNanos { now -> state.elapsed = (now - start) / NANOS_PER_SECOND }
        }
    }

    /** Answers [question]: a beat of thought, then the anchor, then the pen. */
    suspend fun answer(question: String) {
        state.question = question
        state.reply = ""
        state.phase = AskPhase.Thinking
        state.level = 0f
        val room = withTimeoutOrNull(ROOM_WAIT_MILLIS) { readRoom(question) }
        if (room == null) {
            // No tracked frame came: ARCore is not running, or lost the room for good.
            state.phase = AskPhase.Idle
            state.question = ""
            state.notice = R.string.demo_ask_ar_tracking_hint
            return
        }
        val reply = ScriptedAnswerProvider.answer(
            question,
            AskContext(distanceAheadMeters = state.distanceAhead, room = room),
        )
        state.replyTyped = true
        val plan = reply.plan
        if (plan != null) {
            delay(THINK_MILLIS)
            // Drawn in the room at the size of the room: anchored where the plan says, no fitting.
            Log.i(
                LOG_TAG,
                "plan: \"${reply.text}\" origin=${AskRoomReader.fmt(plan.origin)} yaw=${AskRoomReader.fmt(plan.yaw)} " +
                    "sheets=${plan.sheets.map { it.facing }} ${AskRoomReader.fmt(plan.totalSeconds)} s",
            )
            state.plan = plan
            state.reply = reply.reply
            state.blockScale = 1f
            if (stage) {
                state.stagePlaced = true
            } else {
                state.placePose = Pose(floatArrayOf(plan.origin.x, plan.origin.y, plan.origin.z), yawQuaternion(plan.yaw))
                state.placeRequested = true
                snapshotFlow { state.anchor }.first { it != null }
            }
            state.elapsed = 0f
            state.phase = AskPhase.Writing
            writeFor(plan.totalSeconds)
            state.phase = AskPhase.Done
            return
        }
        if (liveEnabled) {
            val liveStarted = SystemClock.elapsedRealtime()
            val snapshot = if (!stage) {
                val request = CompletableDeferred<Triple<AskLiveContext, AskYuvFrame?, Int>>()
                captureRequest = request
                try {
                    withTimeoutOrNull(750L) { request.await() }
                } finally {
                    if (captureRequest === request) captureRequest = null
                    request.cancel()
                }
            } else null
            // No tracked frame within the wait: send time/locale only, never a stale distance.
            val facts = snapshot?.first ?: AskLiveContext()
            val image = snapshot?.second?.let { copied ->
                try {
                    AskFrameCapture.jpeg(copied, snapshot.third)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // An unavailable frame must not prevent a text/measurement answer.
                }
            }
            val liveContext = facts.copy(image = image)
            val imageBytes = image?.data?.let { encoded ->
                encoded.length * 3 / 4 - encoded.takeLastWhile { it == '=' }.length
            }
            Log.i(
                LOG_TAG,
                "live: snapshot distance=${liveContext.distanceAheadMeters ?: "null"} " +
                    "image=${imageBytes ?: "null"} after ${SystemClock.elapsedRealtime() - liveStarted} ms",
            )
            val answerContext = AskContext(
                distanceAheadMeters = liveContext.distanceAheadMeters,
                room = room,
            )
            state.handwriting = IncrementalHandwriting().snapshot()
            state.replyTyped = false
            if (stage) {
                val size = currentViewSize
                val aspect = if (size.height > 0) size.width.toFloat() / size.height else STAGE_ASPECT
                val tall = 2f * -STAGE_BLOCK.z * STAGE_SENSOR_HALF / STAGE_FOCAL
                state.blockScale = state.handwriting
                    ?.let { HandwritingLayout.fitScale(it, tall * aspect, tall) } ?: 1f
                state.stagePlaced = true
            } else {
                // Hung on the spot the phone aimed at when the question was asked, read with the
                // room: by the time the snapshot is sent the camera may have turned away from it.
                state.placePose = room.wall?.let { spot ->
                    val at = spot.point + spot.normal * LIVE_WALL_LIFT
                    Pose(floatArrayOf(at.x, at.y, at.z), yawQuaternion(atan2(spot.normal.x, spot.normal.z)))
                }
                state.fitOnPlace = state.placePose != null
                state.placeOnSurface = reply.doodle == Doodle.Pointer
                state.placeRequested = true
                snapshotFlow { state.anchor }.first { it != null }
            }
            state.elapsed = 0f
            state.phase = AskPhase.Writing
            var firstTextLogged = false
            var finalInk = state.handwriting
            val failure = playAskStream(
                provider = provider,
                question = question,
                context = answerContext,
                liveContext = liveContext,
                onFrame = { ink, elapsed ->
                    state.handwriting = ink
                    state.reply = ink.lines.joinToString(" ") { it.joinToString(" ") }
                    state.elapsed = elapsed
                    finalInk = ink
                    if (!firstTextLogged && ink.lines.any { it.isNotEmpty() }) {
                        firstTextLogged = true
                        Log.i(LOG_TAG, "live: first text after ${SystemClock.elapsedRealtime() - liveStarted} ms")
                    }
                },
            )
            if (failure != null) state.notice = R.string.demo_ask_ar_live_unavailable
            val finalText = finalInk?.lines?.flatten()?.joinToString(" ").orEmpty().replace("\"", "\\\"")
            Log.i(LOG_TAG, "live: done \"$finalText\" failure=${failure ?: "null"}")
            state.phase = AskPhase.Done
            return
        }
        delay(THINK_MILLIS)
        state.reply = reply.reply
        state.handwriting = HandwritingLayout.layout(reply.text, reply.doodle)
        if (stage) {
            val size = currentViewSize
            val aspect = if (size.height > 0) size.width.toFloat() / size.height else STAGE_ASPECT
            val tall = 2f * -STAGE_BLOCK.z * STAGE_SENSOR_HALF / STAGE_FOCAL
            state.blockScale = state.handwriting
                ?.let { HandwritingLayout.fitScale(it, tall * aspect, tall) } ?: 1f
            state.stagePlaced = true
        } else {
            state.placeOnSurface = reply.doodle == Doodle.Pointer
            state.placeRequested = true
            snapshotFlow { state.anchor }.first { it != null }
        }
        state.elapsed = 0f
        state.phase = AskPhase.Writing
        writeFor(state.handwriting?.totalSeconds ?: 0f)
        state.phase = AskPhase.Done
    }

    /** Rubs the answer out the way it was written, last stroke first, quickly. */
    suspend fun unwrite() {
        val from = state.elapsed
        if (from <= 0f) return
        val start = withFrameNanos { it }
        var t = 0f
        while (t < 1f) {
            withFrameNanos { now -> t = ((now - start) / NANOS_PER_MILLI / UNWRITE_MILLIS).coerceAtMost(1f) }
            state.elapsed = from * (1f - t)
        }
    }

    /** Plays a question as if it had been spoken: the caption fills in word by word. */
    suspend fun simulate(question: String) = coroutineScope {
        state.clearAnswer()
        state.transcript = ""
        state.phase = AskPhase.Listening
        val levels = launch {
            var t = 0f
            while (true) {
                withFrameNanos { }
                t += 1f / FRAMES_PER_SECOND
                state.level = (0.55f + 0.35f * sin(t * 9f) * sin(t * 2.3f)).coerceIn(0f, 1f)
            }
        }
        delay(LISTEN_LEAD_MILLIS)
        val words = question.split(' ').filter { it.isNotBlank() }
        words.forEachIndexed { i, _ ->
            state.transcript = words.take(i + 1).joinToString(" ")
            delay(WORD_MILLIS + (i % 3) * WORD_JITTER_MILLIS)
        }
        delay(LISTEN_TAIL_MILLIS)
        levels.cancel()
        answer(question)
    }

    fun launchFlow(block: suspend () -> Unit) {
        flow?.cancel()
        flow = scope.launch { block() }
    }

    val speech = remember(context) {
        SpeechListener(
            context = context,
            onPartial = { if (state.phase == AskPhase.Listening) state.transcript = it },
            onLevel = { if (state.phase == AskPhase.Listening) state.level = it },
            onFinal = { text ->
                if (state.phase == AskPhase.Listening) {
                    if (text.isBlank()) {
                        state.phase = AskPhase.Idle
                        state.notice = R.string.demo_ask_ar_not_heard
                    } else {
                        state.transcript = text
                        launchFlow { answer(text) }
                    }
                }
            },
            onError = {
                if (state.phase == AskPhase.Listening) {
                    state.phase = AskPhase.Idle
                    state.level = 0f
                    state.notice = R.string.demo_ask_ar_not_heard
                }
            },
        )
    }
    DisposableEffect(speech) { onDispose { speech.destroy() } }

    fun listen() {
        if (!speech.available) {
            state.notice = R.string.demo_ask_ar_no_speech
            return
        }
        flow?.cancel()
        state.clearAnswer()
        state.transcript = ""
        state.notice = null
        state.phase = AskPhase.Listening
        speech.start()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else state.notice = R.string.demo_ask_ar_mic_denied
    }

    fun onMic() {
        when (state.phase) {
            AskPhase.Listening -> speech.stop()
            AskPhase.Thinking, AskPhase.Writing -> Unit
            AskPhase.Idle, AskPhase.Done -> {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    var exampleIndex by remember { mutableIntStateOf(0) }
    fun askExample() {
        speech.cancel()
        val question = ScriptedAnswerProvider.EXAMPLES[exampleIndex % ScriptedAnswerProvider.EXAMPLES.size]
        exampleIndex++
        launchFlow { simulate(question) }
    }

    fun clear() {
        flow?.cancel()
        speech.cancel()
        state.clearAnswer()
        state.clearPinned()
        state.transcript = ""
        state.level = 0f
        state.phase = AskPhase.Idle
    }

    // Filming: the questions passed in the link play by themselves once the room is tracked,
    // after a beat for ARCore to settle its idea of the floor. Between two of one link, the
    // first answer stays up a moment, then unwrites itself: the phone has not moved, and a
    // second block on top would scribble over it.
    var request by remember(activity) {
        mutableStateOf(AskRequest(AskArAutomation.take(activity?.intent), keepPrevious = false))
    }
    // Filming cues (debug `--es ask_q_at 41 --es ask_q2_at 63`): each question starts at that
    // second of the session clock instead of chaining, and the earlier answer stays pinned.
    val cues = remember(activity) { AskArAutomation.cues(activity?.intent) }
    // Filming a replay (debug `--es ask_aim 0.62,0.45`): the point on screen the user aimed at,
    // a tap on a screw say, read in place of the screen's centre.
    val aim = remember(activity) { AskArAutomation.aim(activity?.intent) }
    var hideChrome by remember(activity) { mutableStateOf(AskArAutomation.hideChrome(activity?.intent) == true) }
    // A question sent while the demo is open (`am start` again, or the link again) is taken
    // in place: the AR session and any replay run on, and the answer on screen stays where it
    // was written — the phone has been carried to the next spot since.
    DisposableEffect(Unit) {
        val handler: (Intent) -> Boolean = { intent ->
            AskArAutomation.claims(intent).also { claimed ->
                if (claimed) {
                    AskArAutomation.hideChrome(intent)?.let { hideChrome = it }
                    val questions = AskArAutomation.take(intent)
                    if (questions.isNotEmpty()) request = AskRequest(questions, keepPrevious = true)
                }
            }
        }
        DemoSettings.inPlaceIntentHandler = handler
        onDispose { if (DemoSettings.inPlaceIntentHandler === handler) DemoSettings.inPlaceIntentHandler = null }
    }
    LaunchedEffect(request) {
        val run = request
        if (run.questions.isEmpty()) return@LaunchedEffect
        val settled = isTracking
        snapshotFlow { isTracking }.first { it }
        if (!settled) delay(SETTLE_MILLIS)
        speech.cancel()
        if (run.keepPrevious) state.pinAnswer()
        launchFlow {
            run.questions.forEachIndexed { i, question ->
                val at = cues.getOrNull(i)
                if (at != null) {
                    snapshotFlow { sessionSeconds }.first { it >= at }
                    if (i > 0) state.pinAnswer()
                } else if (i > 0) {
                    delay(CHAIN_HOLD_MILLIS)
                    unwrite()
                }
                simulate(question)
            }
        }
    }

    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            state.notice = null
        }
    }

    // Filming hides the system bars too, as full screen does.
    DemoFullScreenSystemBars(hideChrome)
    DemoScaffold(
        monitorArMotionSensors = true,
        title = stringResource(R.string.demo_ask_ar_title),
        onBack = onBack,
        actionsMenu = true,
        chromeToggleOnTap = true,
        chromeHidden = hideChrome,
        recorder = recorder,
        bottomOverlay = {
            if (!hideChrome) DemoStatusBanner(
                text = when {
                    !cameraReady -> null
                    !isTracking -> stringResource(R.string.demo_ask_ar_tracking_hint)
                    notice != null -> stringResource(notice)
                    else -> null
                },
                tone = if (isTracking) DemoStatusTone.Guidance else DemoStatusTone.Progress,
            )
            AskControls(
                phase = state.phase,
                transcript = state.transcript,
                question = state.question,
                reply = if (state.replyTyped) {
                    state.reply.take((state.elapsed * REPLY_CHARS_PER_SECOND).toInt())
                } else {
                    state.reply
                },
                replyDone = state.phase == AskPhase.Done,
                level = state.level,
                showHint = isTracking,
                hasAnswer = state.handwriting != null || state.pinned.isNotEmpty() || state.transcript.isNotEmpty(),
                filming = hideChrome,
                onMic = ::onMic,
                onClear = ::clear,
                onExample = ::askExample,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            if (stage) {
                QaCameraBackdrop(seed = "ask-ar")
                val stagePlan = state.plan?.takeIf { state.stagePlaced }
                // A footprint on the floor is looked down at, through a wider lens, as a phone
                // would be pointed at it; a radiator up close through a wider lens too, so the
                // steps beside it are in the picture; everything else straight ahead.
                val onFloor = stagePlan?.sheets?.any { it.facing == SheetFacing.Floor } == true
                val close = stagePlan != null && -stagePlan.origin.z < STAGE_CLOSE_METRES + 0.1f
                AskQaStage(
                    engine,
                    materialLoader,
                    recorder.surfaceMirrorer,
                    pitch = if (onFloor) STAGE_FLOOR_PITCH else 0f,
                    focal = when {
                        onFloor -> STAGE_FLOOR_FOCAL
                        close -> STAGE_CLOSE_FOCAL
                        else -> STAGE_FOCAL
                    },
                ) {
                    val handwriting = state.handwriting
                    if (state.stagePlaced && handwriting != null) {
                        Node(position = STAGE_BLOCK) { AnswerInk(handwriting, state.blockScale, state.elapsed, ink, rim) }
                    }
                    if (stagePlan != null) {
                        key(stagePlan) {
                            Node(
                                position = Position(stagePlan.origin.x, stagePlan.origin.y, stagePlan.origin.z),
                                rotation = Rotation(y = Math.toDegrees(stagePlan.yaw.toDouble()).toFloat()),
                            ) {
                                PlanInk(stagePlan, state.elapsed, ink, rim) { STAGE_ROOM.camera }
                            }
                        }
                    }
                }
            } else ARSceneView(
                lifecycle = rememberHostLifecycle(),
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                view = view,
                cameraNode = cameraNode,
                playbackDataset = arPlaybackDataset,
                surfaceMirrorer = recorder.surfaceMirrorer,
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Depth, where the phone has it, measures a bare wall ARCore has no plane on.
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                    }
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionFailure = {
                    state.clearAnswer()
                    isTracking = false
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    if (frame.timestamp > 0L) {
                        if (firstFrameNanos == 0L) firstFrameNanos = frame.timestamp
                        sessionSeconds = (frame.timestamp - firstFrameNanos) / 1e9f
                    }
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING
                    isTracking = tracking
                    // Tracking lost (a bare wall, a fast turn) while handwriting is up: hold it in
                    // front of the phone, where the frozen camera keeps it on screen. Tracking
                    // back, its anchor takes it again; if ARCore leaves that anchor paused (it
                    // resumed on a fresh map), a new one goes where the old one last was, and the
                    // answer stays held until it is tracked. A drawing in the room is not held.
                    val anchored = state.anchor
                    if (anchored != null && state.handwriting != null && state.plan == null) {
                        val anchorTracking = anchored.trackingState == TrackingState.TRACKING
                        if (anchorTracking) state.lastTrackedPose = anchored.pose
                        when (state.watch.step(tracking, anchorTracking)) {
                            AskAnchorWatch.Action.Hold -> {
                                val c = frame.camera.pose
                                val z = c.zAxis
                                val a = state.lastTrackedPose ?: anchored.pose
                                val dx = a.tx() - c.tx()
                                val dy = a.ty() - c.ty()
                                val dz = a.tz() - c.tz()
                                val d = sqrt(dx * dx + dy * dy + dz * dz).coerceIn(HOLD_MIN_METRES, HOLD_MAX_METRES)
                                val (wide, tall) = AskPlacement.visibleSize(frame, d)
                                state.holdDistance = d
                                state.held = Triple(
                                    Position(c.tx() - z[0] * d, c.ty() - z[1] * d, c.tz() - z[2] * d),
                                    Math.toDegrees(atan2(z[0], z[2]).toDouble()).toFloat(),
                                    state.handwriting?.let { HandwritingLayout.fitScale(it, wide, tall) } ?: state.blockScale,
                                )
                                Log.i(LOG_TAG, "hold: tracking lost, answer held ${AskRoomReader.fmt(d)} m ahead")
                            }
                            AskAnchorWatch.Action.Release -> {
                                state.held = null
                                Log.i(LOG_TAG, "hold: anchor tracked, answer back on it at ${AskRoomReader.fmt(anchored.pose)}")
                            }
                            AskAnchorWatch.Action.Reanchor -> {
                                // ARCore resumed on a fresh map that drifted from the old one.
                                // The wall where the answer was, along the ray to it, if the
                                // depth already answers; else its last pose right away (never
                                // nothing), corrected onto the wall below once the depth answers.
                                val last = state.lastTrackedPose ?: anchored.pose
                                val wall = AskRoomReader.wallToward(frame, currentViewSize, last)
                                val at = wall ?: last
                                val fresh = runCatching { session.createAnchor(at) }.getOrNull()
                                if (fresh != null) {
                                    // The old anchor leaves the scene with its node, which detaches it.
                                    Log.i(
                                        LOG_TAG,
                                        "hold: anchor still ${anchored.trackingState}, re-anchored on " +
                                            "${if (wall != null) "a fresh wall hit" else "its last pose"} at " +
                                            "${AskRoomReader.fmt(at)} (was ${AskRoomReader.fmt(last)})",
                                    )
                                    state.refineTries = if (wall == null) REFINE_TRIES else 0
                                    state.refineWait = 0
                                    state.anchorState = null
                                    state.anchor = fresh
                                }
                            }
                            AskAnchorWatch.Action.None -> Unit
                        }
                        // Tracking back but the answer still held: it stays in the middle of the
                        // screen, at the same distance, until its anchor takes it.
                        val held = state.held
                        if (tracking && held != null && state.watch.holding) {
                            val c = frame.camera.pose
                            val z = c.zAxis
                            val d = state.holdDistance
                            state.held = Triple(
                                Position(c.tx() - z[0] * d, c.ty() - z[1] * d, c.tz() - z[2] * d),
                                Math.toDegrees(atan2(z[0], z[2]).toDouble()).toFloat(),
                                held.third,
                            )
                        }
                    }
                    // A re-anchor that had to take the old pose: put it on the wall where it shows,
                    // as soon as the depth finds that wall.
                    val reanchored = state.anchor
                    if (tracking && state.refineTries > 0 && reanchored != null &&
                        reanchored.trackingState == TrackingState.TRACKING && ++state.refineWait >= REFINE_EVERY_FRAMES
                    ) {
                        state.refineWait = 0
                        state.refineTries--
                        val wall = AskRoomReader.wallToward(frame, currentViewSize, reanchored.pose)
                        val fresh = wall?.let { runCatching { session.createAnchor(it) }.getOrNull() }
                        if (wall != null && fresh != null) {
                            Log.i(LOG_TAG, "hold: re-anchor put on a wall hit at ${AskRoomReader.fmt(wall)} (was ${AskRoomReader.fmt(reanchored.pose)})")
                            state.refineTries = 0
                            state.lastTrackedPose = fresh.pose
                            state.anchorState = null
                            state.anchor = fresh
                        } else if (state.refineTries == 0) {
                            Log.i(LOG_TAG, "hold: no wall hit, the answer stays on its last pose")
                        }
                    }
                    // An anchored answer hides while its anchor is not tracked: say when that
                    // changes, and where the anchor has got to.
                    state.anchor?.let { anchor ->
                        val anchorState = anchor.trackingState
                        if (anchorState != state.anchorState) {
                            state.anchorState = anchorState
                            Log.i(LOG_TAG, "anchor $anchorState at ${AskRoomReader.fmt(anchor.pose)}, camera $tracking")
                        }
                    }
                    if (tracking) {
                        captureRequest?.let { request ->
                            captureRequest = null // Consume once, even if no camera image is available.
                            if (request.isActive) runCatching {
                                val facts = askLiveContextFromFrame(
                                    frame,
                                    session.getAllTrackables(Plane::class.java),
                                    currentViewSize.width,
                                    currentViewSize.height,
                                )
                                val displayDegrees = when (displayView.display?.rotation) {
                                    AndroidSurface.ROTATION_90 -> 90
                                    AndroidSurface.ROTATION_180 -> 180
                                    AndroidSurface.ROTATION_270 -> 270
                                    else -> 0
                                }
                                val sensorDegrees = cameraManager
                                    .getCameraCharacteristics(session.cameraConfig.cameraId)
                                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                val rotation = askCameraRotationDegrees(sensorDegrees, displayDegrees)
                                // Acquire, copy every strided plane, and close the Image in this callback.
                                val copied = try {
                                    AskFrameCapture.copy(frame)
                                } catch (_: Exception) {
                                    null
                                }
                                request.complete(Triple(facts, copied, rotation))
                            }.onFailure {
                                // A frame ARCore will not read: the question goes without it.
                                Log.w(LOG_TAG, "live: no snapshot from this frame", it)
                                request.complete(Triple(AskLiveContext(), null, 0))
                            }
                        }
                        if (state.frameCount++ % DISTANCE_EVERY_FRAMES == 0) {
                            state.distanceAhead = distanceAhead(frame, currentViewSize)
                        }
                        if (state.roomRequested) {
                            // A wall plane or a depth hit, and the floor, are worth a short wait
                            // over a feature point and a guessed floor: ARCore often finds them
                            // within a second of being pointed at the wall.
                            val room = AskRoomReader.read(session, frame, currentViewSize, aim)
                            if (AskRoomReader.isSettled(room) || ++state.roomFrames >= ROOM_PATIENCE_FRAMES) {
                                Log.i(LOG_TAG, AskRoomReader.describe(room) + " after ${state.roomFrames} frames")
                                state.roomRequested = false
                                state.roomFrames = 0
                                state.room = room
                            }
                        }
                        if (state.placeRequested) {
                            val planned = state.placePose
                            if (planned != null) {
                                val anchor = runCatching { session.createAnchor(planned) }.getOrNull()
                                if (anchor != null) {
                                    if (state.fitOnPlace) {
                                        // Lines wrap to what the screen shows at that distance.
                                        val c = frame.camera.pose
                                        val dx = planned.tx() - c.tx()
                                        val dy = planned.ty() - c.ty()
                                        val dz = planned.tz() - c.tz()
                                        val (wide, tall) = AskPlacement.visibleSize(frame, sqrt(dx * dx + dy * dy + dz * dz))
                                        state.blockScale = state.handwriting
                                            ?.let { HandwritingLayout.fitScale(it, wide, tall) } ?: 1f
                                    }
                                    Log.i(LOG_TAG, "anchor: ${AskRoomReader.fmt(anchor.pose)} ${anchor.trackingState} scale=${AskRoomReader.fmt(state.blockScale)}")
                                    state.placeRequested = false
                                    state.anchor = anchor
                                }
                            } else {
                                val (pose, distance) = AskPlacement.pose(frame, currentViewSize, state.placeOnSurface)
                                val anchor = runCatching { session.createAnchor(pose) }.getOrNull()
                                if (anchor != null) {
                                    val (wide, tall) = AskPlacement.visibleSize(frame, distance)
                                    state.blockScale = state.handwriting
                                        ?.let { HandwritingLayout.fitScale(it, wide, tall) } ?: 1f
                                    state.placeRequested = false
                                    state.anchor = anchor
                                }
                            }
                        }
                    }
                },
            ) {
                // The answers kept from earlier questions, then the one being written: one keyed
                // list, so an answer pinned keeps its node — and its anchor — as it moves up.
                val anchor = state.anchor
                val handwriting = state.handwriting
                val plan = state.plan
                val current = if (anchor != null && (handwriting != null || plan != null)) {
                    listOf(PinnedAnswer(anchor, handwriting, plan, state.blockScale))
                } else {
                    emptyList()
                }
                // Held while tracking is lost: the same ink, in front of the phone.
                val held = state.held
                if (held != null && handwriting != null && plan == null) {
                    Node(position = held.first, rotation = Rotation(y = held.second)) {
                        AnswerInk(handwriting, held.third, state.elapsed, ink, rim)
                    }
                }
                (state.pinned + current).forEach { answer ->
                    key(answer.anchor) {
                        val elapsed = if (answer.anchor == anchor) state.elapsed else answer.totalSeconds
                        AnchorNode(anchor = answer.anchor) {
                            answer.plan?.let { PlanInk(it, elapsed, ink, rim) { cameraNode.worldPosition } }
                            answer.handwriting?.let { AnswerInk(it, answer.scale, elapsed, ink, rim) }
                        }
                    }
                }
            }
            if (!stage) ARCameraInitScrim(initializing = !cameraReady, arCoreAvailability = arCoreAvailability)
        }
    }
}

/** Every stroke the pen has reached [elapsed] seconds into the writing. */
@Composable
private fun SceneScope.AnswerInk(
    handwriting: Handwriting,
    scale: Float,
    elapsed: Float,
    ink: MaterialInstance,
    rim: MaterialInstance,
) {
    // Hung by its anchor point (the centre of what is drawn, or a pointer's tip), and sized to
    // the screen.
    val anchor = handwriting.anchor
    Node(
        position = Position(-anchor.x * scale, -anchor.y * scale, 0f),
        scale = Scale(scale),
    ) {
        handwriting.strokes.forEachIndexed { index, stroke ->
            val progress = handwriting.progressOf(index, elapsed)
            if (progress.count > 0) {
                key(stroke.id) { InkTube(stroke, progress, ink, rim) }
            }
        }
    }
}

/**
 * A drawing plan, drawn up to [elapsed] seconds, in its own frame (the caller puts the frame where
 * the plan says). Each sheet lies as it faces: on the wall, flat on the floor, or upright and
 * turned toward [cameraPosition] every frame — about the vertical only, so its words stay level.
 */
@Composable
private fun SceneScope.PlanInk(
    plan: DrawingPlan,
    elapsed: Float,
    ink: MaterialInstance,
    rim: MaterialInstance,
    cameraPosition: () -> Position,
) {
    plan.sheets.forEach { sheet ->
        key(sheet) {
            val position = Position(sheet.offset.x, sheet.offset.y, sheet.offset.z)
            val strokes: @Composable NodeScope.() -> Unit = { SheetInk(sheet, elapsed, ink, rim) }
            when (sheet.facing) {
                SheetFacing.Wall -> Node(position = position, content = strokes)
                // The sheet's y runs away from the phone, its +z (the side that shows) up.
                SheetFacing.Floor -> Node(position = position, rotation = Rotation(x = -90f), content = strokes)
                SheetFacing.Billboard -> Node(
                    position = position,
                    apply = {
                        onFrame = {
                            val eye = cameraPosition()
                            val at = worldPosition
                            val dx = eye.x - at.x
                            val dz = eye.z - at.z
                            if (dx * dx + dz * dz > 1e-6f) {
                                val yaw = atan2(dx, dz)
                                worldQuaternion = Quaternion(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
                            }
                        }
                    },
                    content = strokes,
                )
            }
        }
    }
    // Then the real things the answer is about, built where the drawing put them.
    PlanProps(plan, elapsed)
}

/** Every stroke of [sheet] the pen has reached by [elapsed]. */
@Composable
private fun SceneScope.SheetInk(sheet: InkSheet, elapsed: Float, ink: MaterialInstance, rim: MaterialInstance) {
    val writing = sheet.writing
    writing.strokes.forEachIndexed { index, stroke ->
        val progress = writing.progressOf(index, elapsed)
        if (progress.count > 0) {
            key(stroke.id) { InkTube(stroke, progress, ink, rim, rimWidth = sheet.rimWidth, rimDepth = sheet.rimWidth * 2f) }
        }
    }
}

/**
 * The QA stage: a transparent 3D scene over the room photo, its camera fixed at eye height
 * looking straight ahead, so the answer is written where the AR mode would hang it.
 */
@Composable
private fun AskQaStage(
    engine: Engine,
    materialLoader: MaterialLoader,
    surfaceMirrorer: SurfaceMirrorer?,
    pitch: Float,
    focal: Float,
    content: @Composable SceneScope.() -> Unit,
) {
    val camera = rememberCameraNode(engine) {
        position = Position(0f, 0f, 0f)
        focalLength = STAGE_FOCAL.toDouble()
    }
    LaunchedEffect(camera, pitch, focal) {
        camera.rotation = Rotation(x = pitch)
        camera.focalLength = focal.toDouble()
    }
    SceneView(
        modifier = Modifier.fillMaxSize(),
        surfaceType = SurfaceType.TextureSurface,
        engine = engine,
        materialLoader = materialLoader,
        isOpaque = false,
        frameRatePolicy = FrameRatePolicy.Continuous(),
        autoCenterContent = false,
        cameraNode = camera,
        cameraManipulator = null,
        surfaceMirrorer = surfaceMirrorer,
        content = content,
    )
}

/**
 * One stroke of ink, drawn up to the pen: the finished points, then the tip part of the way to
 * the next. Two tubes — the bright core, and a slightly wider rim just behind it.
 */
@Composable
private fun SceneScope.InkTube(
    stroke: InkStroke,
    progress: StrokeProgress,
    ink: MaterialInstance,
    rim: MaterialInstance,
    rimWidth: Float = RIM_WIDTH,
    rimDepth: Float = RIM_DEPTH,
) {
    val full = remember(stroke) { stroke.points.map { Position(it.x, it.y, 0f) } }
    val fullRim = remember(stroke, rimWidth) { stroke.radii.map { it + rimWidth } }
    val (points, radii) = remember(stroke, progress) {
        if (progress.count >= stroke.points.size) {
            full to stroke.radii
        } else {
            val last = progress.count - 1
            val next = (last + 1).coerceAtMost(stroke.points.lastIndex)
            val tip = lerp(stroke.points[last], stroke.points[next], progress.tipFraction)
            val tipRadius = stroke.radii[last] + (stroke.radii[next] - stroke.radii[last]) * progress.tipFraction
            (full.subList(0, progress.count) + Position(tip.x, tip.y, 0f)) to
                (stroke.radii.subList(0, progress.count) + tipRadius)
        }
    }
    val rimRadii = remember(radii) { if (radii === stroke.radii) fullRim else radii.map { it + rimWidth } }
    val capacity = stroke.points.size + 1
    TubeNode(
        points = points,
        radii = radii,
        radialSegments = RADIAL_SEGMENTS,
        capStyle = Tube.CapStyle.Round,
        capacity = capacity,
        materialInstance = ink,
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
    TubeNode(
        points = points,
        radii = rimRadii,
        radialSegments = RADIAL_SEGMENTS,
        capStyle = Tube.CapStyle.Round,
        capacity = capacity,
        materialInstance = rim,
        position = Position(z = -rimDepth),
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
}

private fun lerp(a: InkPoint, b: InkPoint, t: Float) = InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * The caption over the mic, the mic itself, and the two small actions either side of it.
 * [filming] keeps only the question's caption and the mic while it listens.
 */
@Composable
private fun AskControls(
    phase: AskPhase,
    transcript: String,
    question: String,
    reply: String,
    replyDone: Boolean,
    level: Float,
    showHint: Boolean,
    hasAnswer: Boolean,
    filming: Boolean,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onExample: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AskConversation(
            phase = phase,
            heard = transcript,
            question = question,
            reply = reply,
            replyDone = replyDone,
            hint = if (showHint && !filming) stringResource(R.string.demo_ask_ar_idle_hint) else null,
        )
        Spacer(Modifier.height(Space.md))
        if (filming) {
            // The mic's place is kept, so the caption does not move when it leaves.
            Box(Modifier.size(ShutterSize), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = phase == AskPhase.Listening,
                    enter = fadeIn(SceneViewTokens.Motion.fade()),
                    exit = fadeOut(SceneViewTokens.Motion.fade()),
                ) {
                    MicShutter(phase = phase, level = level, onClick = onMic)
                }
            }
            return@Column
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            GlassIconButton(
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.demo_ask_ar_clear),
                onClick = onClear,
                enabled = hasAnswer || phase == AskPhase.Thinking,
            )
            MicShutter(phase = phase, level = level, onClick = onMic)
            GlassIconButton(
                icon = Icons.Rounded.AutoAwesome,
                contentDescription = stringResource(R.string.demo_ask_ar_example),
                onClick = onExample,
                enabled = phase != AskPhase.Thinking && phase != AskPhase.Writing,
            )
        }
    }
}

/**
 * The conversation over the camera, as a chat reads: the question in a warm bubble on the
 * right (live while it is heard), the answer on the left, typed out in full sentences while the
 * pen draws its short version in the room. Idle, a hint of what to ask.
 */
@Composable
private fun AskConversation(
    phase: AskPhase,
    heard: String,
    question: String,
    reply: String,
    replyDone: Boolean,
    hint: String?,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = Space.lg)
            .widthIn(max = CaptionMaxWidth)
            .fillMaxWidth()
            .animateContentSize(SceneViewTokens.Motion.spring()),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        val asked = when {
            phase == AskPhase.Listening -> heard
            else -> question
        }
        when {
            phase == AskPhase.Listening && heard.isEmpty() ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CaptionChip(text = stringResource(R.string.demo_ask_ar_listening), live = true, muted = true)
                }
            asked.isNotEmpty() -> ChatBubble(text = asked, mine = true, live = phase == AskPhase.Listening)
            hint != null && phase == AskPhase.Idle ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CaptionChip(text = hint, live = false, muted = true)
                }
        }
        if (phase == AskPhase.Thinking) {
            ChatBubble(text = stringResource(R.string.demo_ask_ar_thinking), mine = false, typing = true)
        } else if (reply.isNotEmpty() && (phase == AskPhase.Writing || phase == AskPhase.Done)) {
            ChatBubble(text = reply, mine = false, typing = !replyDone)
        }
    }
}

/**
 * One turn of the conversation. Mine: a warm bubble on the right, its corner nearest the mic
 * squared off. The answer's: glass on the left, its first corner squared, a caret while it is
 * still being written.
 */
@Composable
private fun ChatBubble(text: String, mine: Boolean, live: Boolean = false, typing: Boolean = false) {
    val big = SceneViewTokens.Radius.lg
    val small = SceneViewTokens.Radius.xs
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = if (mine) {
                RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = small, bottomStart = big)
            } else {
                RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = big, bottomStart = big)
            },
            ground = if (mine) MIC_DISC.copy(alpha = MINE_ALPHA) else ArOverlay.cameraDim,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Space.md + Space.xs, vertical = Space.sm + Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live) {
                    LiveDot()
                    Spacer(Modifier.size(Space.sm))
                }
                val caret by rememberInfiniteTransition(label = "caret").animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(LIVE_DOT_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
                    label = "caret-blink",
                )
                Text(
                    text = if (typing) "$text\u2009\u258D" else text,
                    style = if (mine) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal,
                    color = if (typing && !mine) ArOverlay.onScrim.copy(alpha = 0.92f + 0.08f * caret) else ArOverlay.onScrim,
                )
            }
        }
    }
}

/** The mic's live dot, pulsing while it listens. */
@Composable
private fun LiveDot() {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LIVE_DOT_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
        label = "live-dot",
    )
    Box(Modifier.size(Space.sm + Space.xs / 2).background(INK_CORE.copy(alpha = pulse), CircleShape))
}

/** The question as it is heard: a subtitle on glass, a live dot while the mic is open. */
@Composable
private fun CaptionChip(text: String, live: Boolean, muted: Boolean) {
    GlassSurface(
        modifier = Modifier
            .padding(horizontal = Space.lg)
            .widthIn(max = CaptionMaxWidth)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
        ground = ArOverlay.cameraDim,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.md + Space.xs, vertical = Space.sm + Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) {
                val pulse by rememberInfiniteTransition(label = "live").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(LIVE_DOT_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
                    label = "live-dot",
                )
                Box(
                    Modifier
                        .size(Space.sm + Space.xs / 2)
                        .background(INK_CORE.copy(alpha = pulse), CircleShape),
                )
                Spacer(Modifier.size(Space.sm + Space.xs / 2))
            }
            AnimatedContent(
                targetState = text,
                transitionSpec = { fadeIn(tween(CAPTION_FADE_MILLIS)) togetherWith fadeOut(tween(CAPTION_FADE_MILLIS)) },
                label = "caption",
            ) { shown ->
                Text(
                    text = shown,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
                    color = if (muted) ArOverlay.onScrimMuted else ArOverlay.onScrim,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            }
        }
    }
}

/**
 * The mic, as a camera shutter: a white ring around a warm disc. Listening, rings spread from
 * it with the speaker's voice and the disc shows Stop; thinking, the ring turns.
 */
@Composable
private fun MicShutter(phase: AskPhase, level: Float, onClick: () -> Unit) {
    val listening = phase == AskPhase.Listening
    val busy = phase == AskPhase.Thinking || phase == AskPhase.Writing
    val motion = LocalMotionEnabled.current
    val spread by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RING_MILLIS, easing = LinearEasing)),
        label = "mic-rings",
    )
    val loudness by animateFloatAsState(if (listening) level else 0f, tween(LEVEL_MILLIS), label = "mic-level")
    val label = stringResource(if (listening) R.string.demo_ask_ar_stop else R.string.demo_ask_ar_ask)
    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = !busy,
            shape = CircleShape,
            color = ArOverlay.meterTrack,
            border = BorderStroke(ShutterRingWidth, ArOverlay.onScrim.copy(alpha = if (busy) BUSY_ALPHA else 1f)),
            modifier = Modifier
                .size(ShutterSize)
                .drawBehind {
                    if (!listening) return@drawBehind
                    val base = size.minDimension / 2f
                    val phases = if (motion) listOf(spread, (spread + 0.5f) % 1f) else listOf(0.5f)
                    phases.forEach { p ->
                        val reach = base * (1f + (0.25f + 0.55f * loudness) * p)
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - p) * 0.55f),
                            radius = reach,
                            center = Offset(size.width / 2f, size.height / 2f),
                            style = Stroke(width = ShutterRingWidth.toPx()),
                        )
                    }
                }
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(ShutterDiscSize)
                        .background(MIC_DISC.copy(alpha = if (busy) BUSY_ALPHA else 1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(MicIconSize),
                    )
                }
            }
        }
        if (phase == AskPhase.Thinking) {
            CircularProgressIndicator(
                modifier = Modifier.size(ShutterSize),
                color = ArOverlay.onScrim,
                strokeWidth = ShutterRingWidth,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** Where the answer's block hangs, worked out from one camera frame. */
private object AskPlacement {

    /**
     * On the wall straight ahead if ARCore found one closer than [WALL_REACH], facing out of
     * it; otherwise [AHEAD] in front of the phone, at eye height, turned to face it. Upright
     * either way: only the heading follows the phone.
     */
    fun pose(frame: Frame, viewSize: IntSize, onSurface: Boolean): Pair<Pose, Float> {
        val camera = frame.camera.pose
        if (onSurface) surfaceHit(frame, viewSize)?.let { hit -> return onHit(hit, camera) }
        wallHit(frame, viewSize)?.let { (hit, normal) ->
            val yaw = atan2(normal[0], normal[2])
            val t = hit.second.translation
            return Pose(
                floatArrayOf(t[0] + normal[0] * WALL_OFFSET, t[1], t[2] + normal[2] * WALL_OFFSET),
                yawRotation(yaw),
            ) to hit.first
        }
        val z = camera.zAxis
        var fx = -z[0]
        var fz = -z[2]
        val flat = sqrt(fx * fx + fz * fz)
        // Looking straight down or up: no heading to read, so face the way the phone's top points.
        if (flat < MIN_FLAT) {
            val y = camera.yAxis
            fx = y[0]
            fz = y[2]
        }
        val norm = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-6f)
        fx /= norm
        fz /= norm
        val c = camera.translation
        return Pose(
            floatArrayOf(c[0] + fx * AHEAD, c[1] - BELOW_EYE, c[2] + fz * AHEAD),
            // The block's front (+Z) looks back at the phone.
            yawRotation(atan2(-fx, -fz)),
        ) to AHEAD
    }

    /**
     * How much of the room the screen shows at [distance], across and top to bottom, in
     * metres, from the camera's projection: a phone held upright sees far less across.
     */
    fun visibleSize(frame: Frame, distance: Float): Pair<Float, Float> {
        val projection = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, NEAR, FAR)
        return 2f * distance / projection[0] to 2f * distance / projection[5]
    }

    private fun wallHit(frame: Frame, viewSize: IntSize): Pair<Pair<Float, Pose>, FloatArray>? {
        if (viewSize.width == 0 || viewSize.height == 0) return null
        val hits = runCatching { frame.hitTest(viewSize.width / 2f, viewSize.height / 2f) }.getOrNull() ?: return null
        val hit = hits.firstOrNull { hit ->
            val plane = hit.trackable as? Plane
            plane != null && plane.type == Plane.Type.VERTICAL && plane.isPoseInPolygon(hit.hitPose) &&
                hit.distance < WALL_REACH
        } ?: return null
        val normal = hit.hitPose.yAxis
        val flat = sqrt(normal[0] * normal[0] + normal[2] * normal[2])
        if (flat < MIN_FLAT) return null
        return (hit.distance to hit.hitPose) to floatArrayOf(normal[0] / flat, 0f, normal[2] / flat)
    }

    /**
     * On the spot [hit] measured, a hair toward the phone and turned to face it: an answer
     * that points at the surface hangs by its arrow's tip right there.
     */
    private fun onHit(hit: HitResult, camera: Pose): Pair<Pose, Float> {
        val t = hit.hitPose.translation
        val c = camera.translation
        var dx = c[0] - t[0]
        var dz = c[2] - t[2]
        val norm = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
        dx /= norm
        dz /= norm
        return Pose(
            floatArrayOf(t[0] + dx * WALL_OFFSET, t[1], t[2] + dz * WALL_OFFSET),
            yawRotation(atan2(dx, dz)),
        ) to hit.distance
    }

    private fun yawRotation(yaw: Float) = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))

    private const val AHEAD = 1.5f
    private const val BELOW_EYE = 0.05f
    private const val WALL_REACH = 3f
    private const val WALL_OFFSET = 0.03f
    private const val MIN_FLAT = 0.2f
    private const val NEAR = 0.1f
    private const val FAR = 100f
}

/** A rotation of [yaw] radians about the vertical, as ARCore's (x, y, z, w) quaternion. */
private fun yawQuaternion(yaw: Float) = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))

/**
 * Reads the room from one tracked frame for the answers drawn in it ([AskRoom]): where the phone
 * looks, the floor, the wall at the centre of the screen, and the room either side of a sofa in
 * front of it. Every number in there was measured by ARCore, except a floor it has not found
 * (see [AskFloor.pick]).
 */
private object AskRoomReader {

    fun read(session: Session, frame: Frame, viewSize: IntSize, aim: Pair<Float, Float>? = null): AskRoom {
        val pose = frame.camera.pose
        val c = pose.translation
        val camera = Float3(c[0], c[1], c[2])
        val heading = heading(pose)
        val floorHeights = session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            .map { it.centerPose.ty() }
        val floor = AskFloor.pick(camera.y, floorHeights, lowPointHeights(frame, viewSize))
        val hits = centreHits(frame, viewSize, aim)
        // What the centre ray (or the aimed one) hit, by kind: a wall plane, else a depth point, else a feature
        // point; a feature point never nearer than WallPick.MIN_DISTANCE (one on the phone's
        // own edge of the scene is not the wall), a plane or depth from MIN_SURFACE_DISTANCE.
        val kinds = hits.map { hit -> wallKind(hit) to hit.distance }
        val wall = WallPick.best(kinds)?.let { index ->
            val hit = hits[index]
            val t = hit.hitPose.translation
            val point = Float3(t[0], t[1], t[2])
            // A plane's or a depth point's pose carries the surface normal on its y axis; a
            // feature point's does not.
            val candidate = when (hit.trackable) {
                is Plane, is DepthPoint -> hit.hitPose.yAxis.let { Float3(it[0], it[1], it[2]) }
                else -> null
            }
            val normal = HangPicture.wallNormal(candidate, point, camera)
            val plane = (hit.trackable as? Plane)?.takeIf { it.type == Plane.Type.VERTICAL }
            val (left, right) = plane?.let { edges(it, point, normal) } ?: (null to null)
            WallSpot(point, normal, left, right, kinds[index].first, hit.distance)
        }
        val floorHit = hits.firstOrNull { hit ->
            val plane = hit.trackable as? Plane
            plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(hit.hitPose) &&
                kotlin.math.abs(hit.hitPose.ty() - floor.y) < FLOOR_TOLERANCE
        }?.hitPose?.translation?.let { Float3(it[0], it[1], it[2]) }
        // In front of the wall the phone points at, square to it when it is close.
        val (centre, facing) = SofaFit.place(camera, heading, floor.y, floorHit, wall)
        val axis = SofaFit.axis(facing)
        val origin = Float3(centre.x, floor.y + SofaFit.RAY_HEIGHT, centre.z)
        val left = SofaFit.nearest(sideHits(frame, origin, Float3(-axis.x, 0f, -axis.z)))
        val right = SofaFit.nearest(sideHits(frame, origin, axis))
        return AskRoom(camera, heading, floor, wall, SofaSpot(centre, left, right, facing))
    }

    /**
     * Heights of what the bottom of the screen hits — depth points, feature points, a floor
     * plane — for a floor ARCore has no plane on yet.
     */
    private fun lowPointHeights(frame: Frame, viewSize: IntSize): List<Float> {
        if (viewSize.width == 0 || viewSize.height == 0) return emptyList()
        val y = viewSize.height * LOW_ROW
        return LOW_COLUMNS.flatMap { fx ->
            runCatching { frame.hitTest(viewSize.width * fx, y) }.getOrNull().orEmpty()
                .filter { hit ->
                    when (val trackable = hit.trackable) {
                        is DepthPoint, is Point -> true
                        is Plane -> trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                        else -> false
                    }
                }
                .map { it.hitPose.ty() }
        }
    }

    private const val LOW_ROW = 0.9f
    private val LOW_COLUMNS = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)

    /** What kind of wall a centre-ray hit is, or `null` for one that is not standing (a floor, a table). */
    private fun wallKind(hit: HitResult): WallHitKind? = when (val trackable = hit.trackable) {
        is Plane -> if (trackable.type == Plane.Type.VERTICAL && trackable.isPoseInPolygon(hit.hitPose)) {
            WallHitKind.VerticalPlane
        } else {
            null
        }
        is DepthPoint -> WallHitKind.Depth
        is Point -> WallHitKind.FeaturePoint
        else -> null
    }

    /** One line for logcat: what the room read found. */
    fun describe(room: AskRoom): String {
        val wall = room.wall
        val hit = if (wall == null) "none" else "${wall.kind} d=${wall.distance?.let { fmt(it) }} at ${fmt(wall.point)} n=${fmt(wall.normal)}"
        val sofa = room.sofa?.let {
            "sofa=${fmt(it.centre)} facing=${it.heading?.let(::fmt)} left=${it.left?.let(::fmt)} right=${it.right?.let(::fmt)}"
        }
        return "room: hit=$hit floorY=${fmt(room.floor.y)} (${room.floor.source}) " +
            "camera=${fmt(room.camera)} heading=${fmt(room.heading)} $sofa"
    }

    fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v)
    fun fmt(v: Float3) = "(${fmt(v.x)}, ${fmt(v.y)}, ${fmt(v.z)})"
    fun fmt(pose: Pose) = "(${fmt(pose.tx())}, ${fmt(pose.ty())}, ${fmt(pose.tz())}) q=(${pose.qx().let(::fmt)}, " +
        "${fmt(pose.qy())}, ${fmt(pose.qz())}, ${fmt(pose.qw())})"

    /** A read good enough not to wait for a better one: a wall plane or a depth hit, and the floor found. */
    fun isSettled(room: AskRoom): Boolean =
        room.floor.measured && room.wall?.kind.let { it == WallHitKind.VerticalPlane || it == WallHitKind.Depth }

    /**
     * How far, level with [point], [plane]'s outline reaches to the left and the right along
     * the wall: its corners laid out on the wall, then [WallEdges.around].
     */
    private fun edges(plane: Plane, point: Float3, normal: Float3): Pair<Float?, Float?> {
        val polygon = plane.polygon
        val pose = plane.centerPose
        val right = HangPicture.right(normal)
        val outline = (0 until polygon.limit() / 2).map { i ->
            val w = pose.transformPoint(floatArrayOf(polygon.get(2 * i), 0f, polygon.get(2 * i + 1)))
            val dx = w[0] - point.x
            val dz = w[2] - point.z
            InkPoint(dx * right.x + dz * right.z, w[1] - point.y)
        }
        return WallEdges.around(outline)
    }

    /** Where the phone looks, flat on the floor; the way its top points when it looks straight down. */
    private fun heading(camera: Pose): Float3 {
        val z = camera.zAxis
        var fx = -z[0]
        var fz = -z[2]
        if (sqrt(fx * fx + fz * fz) < MIN_FLAT) {
            val y = camera.yAxis
            fx = y[0]
            fz = y[2]
        }
        val norm = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-6f)
        return Float3(fx / norm, 0f, fz / norm)
    }

    /**
     * Where the wall is now along the camera's ray to [last], an answer's pose from before a
     * tracking loss: [last] projected to the screen, hit-tested there, kept if it lands on a
     * wall plane or a depth point. Facing the camera, lifted off the wall as a live answer is.
     * `null` when [last] is off screen or nothing standing is there.
     */
    fun wallToward(frame: Frame, viewSize: IntSize, last: Pose): Pose? {
        if (viewSize.width == 0 || viewSize.height == 0) return null
        val view = FloatArray(16)
        val projection = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        val viewProjection = FloatArray(16)
        android.opengl.Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val clip = FloatArray(4)
        android.opengl.Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(last.tx(), last.ty(), last.tz(), 1f), 0)
        if (clip[3] <= 0f) return null
        val x = (clip[0] / clip[3] + 1f) / 2f * viewSize.width
        val y = (1f - clip[1] / clip[3]) / 2f * viewSize.height
        if (x !in 0f..viewSize.width.toFloat() || y !in 0f..viewSize.height.toFloat()) return null
        val hits = runCatching { frame.hitTest(x, y) }.getOrNull().orEmpty()
        val hit = hits.firstOrNull { hit ->
            val kind = wallKind(hit)
            (kind == WallHitKind.VerticalPlane || kind == WallHitKind.Depth) && hit.distance >= REANCHOR_MIN_METRES
        }
        if (hit == null) {
            val seen = hits.joinToString { "${wallKind(it) ?: it.trackable.javaClass.simpleName} ${fmt(it.distance)}" }
            Log.i(LOG_TAG, "rehit: (${x.toInt()}, ${y.toInt()}) no wall among [$seen]")
            return null
        }
        val c = frame.camera.pose.translation
        val t = hit.hitPose.translation
        val point = Float3(t[0], t[1], t[2])
        val normal = HangPicture.wallNormal(
            hit.hitPose.yAxis.let { Float3(it[0], it[1], it[2]) }, point, Float3(c[0], c[1], c[2]),
        )
        val at = point + normal * LIVE_WALL_LIFT
        return Pose(floatArrayOf(at.x, at.y, at.z), yawQuaternion(atan2(normal.x, normal.z)))
    }

    private fun centreHits(frame: Frame, viewSize: IntSize, aim: Pair<Float, Float>?): List<HitResult> {
        if (viewSize.width == 0 || viewSize.height == 0) return emptyList()
        val (fx, fy) = aim ?: (0.5f to 0.5f)
        return runCatching { frame.hitTest(viewSize.width * fx, viewSize.height * fy) }.getOrNull().orEmpty()
    }

    /** What a ray from [origin] along [direction] hits: a wall ARCore found, a depth point, or else. */
    private fun sideHits(frame: Frame, origin: Float3, direction: Float3): List<SofaFit.SideHit> {
        val hits = runCatching {
            frame.hitTest(floatArrayOf(origin.x, origin.y, origin.z), 0, floatArrayOf(direction.x, direction.y, direction.z), 0)
        }.getOrNull().orEmpty()
        return hits.map { hit ->
            val t = hit.hitPose.translation
            val dx = t[0] - origin.x
            val dy = t[1] - origin.y
            val dz = t[2] - origin.z
            val kind = when (val trackable = hit.trackable) {
                is Plane -> if (trackable.type == Plane.Type.VERTICAL && trackable.isPoseInPolygon(hit.hitPose)) {
                    SofaFit.HitKind.VerticalPlane
                } else {
                    SofaFit.HitKind.Other
                }
                is DepthPoint -> SofaFit.HitKind.Depth
                else -> SofaFit.HitKind.Other
            }
            SofaFit.SideHit(sqrt(dx * dx + dy * dy + dz * dz), kind)
        }
    }

    private const val MIN_FLAT = 0.2f

    /** A floor hit this far off the floor's height is a table, a bed, a step: not where a sofa goes. */
    private const val FLOOR_TOLERANCE = 0.15f
}

/** How far the surface at the centre of the screen is, for "how far is that wall?". */
private fun distanceAhead(frame: Frame, viewSize: IntSize): Float? = surfaceHit(frame, viewSize)?.distance

/**
 * The nearest thing standing at the centre of the screen: a wall ARCore found (a vertical
 * plane), or a depth or feature point. A floor or a table top is looked past — "that wall"
 * is never the floor in front of it.
 */
private fun surfaceHit(frame: Frame, viewSize: IntSize): HitResult? {
    if (viewSize.width == 0 || viewSize.height == 0) return null
    val hits = runCatching { frame.hitTest(viewSize.width / 2f, viewSize.height / 2f) }.getOrNull() ?: return null
    return hits.firstOrNull { hit ->
        val standing = when (val trackable = hit.trackable) {
            is Plane -> trackable.type == Plane.Type.VERTICAL && trackable.isPoseInPolygon(hit.hitPose)
            is DepthPoint, is Point -> true
            else -> false
        }
        standing && hit.distance <= SURFACE_REACH
    }
}

/**
 * The questions a link or launch extras asked for, in order, once:
 * `sceneview://demo/ask-ar?q=…&q2=…` or `--es ask_q "…" --es ask_q2 "…"`. The intent is
 * marked so coming back to the demo does not replay them.
 */
internal object AskArAutomation {
    /** Query parameter and launch extra of each question, first to last. */
    val SOURCES: List<Pair<String, String>> = listOf("q" to "ask_q", "q2" to "ask_q2")
    private const val CONSUMED = "ask_q_consumed"

    /** Debug extra for filming: `--ez ask_hide_chrome true` leaves only the caption and the mic. */
    private const val HIDE_CHROME = "ask_hide_chrome"
    private const val DEMO_ID = "ask-ar"

    /** Filming cues, debug only: `--es ask_q_at <s> --es ask_q2_at <s>`, seconds of session clock. */
    private val CUES = listOf("ask_q_at", "ask_q2_at")

    fun cues(intent: Intent?): List<Float?> =
        if (intent == null || !BuildConfig.DEBUG) emptyList()
        else CUES.map { intent.getStringExtra(it)?.trim()?.toFloatOrNull() }

    /**
     * Filming a replay, debug only: `--es ask_aim <x>,<y>`, fractions of the screen's width and
     * height, is where the room read aims instead of the centre, standing in for the user who
     * centres the screw. A recording rarely holds the spot dead centre at the second it is read.
     */
    private const val AIM = "ask_aim"

    fun aim(intent: Intent?): Pair<Float, Float>? {
        if (intent == null || !BuildConfig.DEBUG) return null
        val xy = intent.getStringExtra(AIM)?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        return xy.takeIf { it.size == 2 && it.all { f -> f in 0f..1f } }?.let { it[0] to it[1] }
    }

    /** `--ez ask_hide_chrome`, if the intent says (debug builds only), else `null`. */
    fun hideChrome(intent: Intent?): Boolean? =
        intent?.takeIf { BuildConfig.DEBUG && it.hasExtra(HIDE_CHROME) }?.getBooleanExtra(HIDE_CHROME, false)

    /**
     * Whether an intent that reaches the open demo is its own to take in place: it asks a
     * question or toggles filming, and names this demo or none.
     */
    fun claims(intent: Intent): Boolean {
        // Questions that play by themselves are a filming tool: debug builds only, so a link can
        // never make a release build send the camera's view anywhere without a tap.
        if (!BuildConfig.DEBUG) return false
        val demo = intent.getStringExtra("demo")
        if (demo != null && demo != DEMO_ID) return false
        val data = intent.data
        if (data != null && data.lastPathSegment != DEMO_ID) return false
        val asks = SOURCES.any { (param, extra) ->
            intent.hasExtra(extra) || runCatching { data?.getQueryParameter(param) }.getOrNull() != null
        }
        return asks || hideChrome(intent) != null
    }

    fun take(intent: Intent?): List<String> {
        if (intent == null || !BuildConfig.DEBUG || intent.getBooleanExtra(CONSUMED, false)) return emptyList()
        val link = intent.data?.takeIf { it.lastPathSegment == DEMO_ID }
        val questions = SOURCES.mapNotNull { (param, extra) ->
            val fromLink = runCatching { link?.getQueryParameter(param) }.getOrNull()
            (intent.getStringExtra(extra) ?: fromLink)?.trim()?.takeIf { it.isNotEmpty() }?.let(::spoken)
        }
        if (questions.isNotEmpty()) intent.putExtra(CONSUMED, true)
        return questions
    }

    /** A spoken question starts with a capital and ends with a question mark. */
    private fun spoken(question: String): String {
        val capitalised = question.replaceFirstChar { it.uppercaseChar() }
        return if (capitalised.last() in ".?!") capitalised else "$capitalised?"
    }
}

/**
 * Android's on-device speech recognizer, with partial results for the live caption. Every call
 * on the main thread. [available] is false on a phone with no recognition service.
 */
private class SpeechListener(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Int) -> Unit,
) : RecognitionListener {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }

    init {
        recognizer?.setRecognitionListener(this)
    }

    val available: Boolean get() = recognizer != null

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    fun stop() = recognizer?.stopListening()

    fun cancel() = recognizer?.cancel()

    fun destroy() = recognizer?.destroy()

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults.firstResult()?.let(onPartial)
    }

    override fun onResults(results: Bundle?) {
        onFinal(results.firstResult().orEmpty())
    }

    override fun onError(error: Int) = onError.invoke(error)

    override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB - RMS_FLOOR) / RMS_RANGE).coerceIn(0f, 1f))

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val RMS_FLOOR = -2f
        const val RMS_RANGE = 10f
    }
}

// The ink: DESIGN.md's tertiary pair. `tertiary` (dark) for the core, bright enough for a dim
// room; `tertiary-container` for the rim, deep enough for a white wall.
private val INK_CORE = Color(0xFFFFB595)
private val INK_RIM = Color(0xFFA33F00)
private val MIC_DISC = INK_RIM

private const val RADIAL_SEGMENTS = 8

/** Where the QA stage hangs the block: 1.5 m ahead, a touch below the eye. */
private val STAGE_BLOCK = Position(0f, -0.05f, -1.5f)

/**
 * The QA stage's lens, in mm on Filament's 24 mm sensor: 62 degrees top to bottom, about what
 * a phone's main camera shows held upright once ARCore has cropped it to the screen.
 */
private const val STAGE_FOCAL = 20f
private const val STAGE_SENSOR_HALF = 12f
private const val STAGE_ASPECT = 0.45f

/** A live answer hangs this far off the wall it was asked at, so the ink never sinks into it. */
private const val LIVE_WALL_LIFT = 0.03f

/** Tries, [REFINE_EVERY_FRAMES] apart, to find the wall under an answer re-anchored on its last pose. */
private const val REFINE_TRIES = 60
private const val REFINE_EVERY_FRAMES = 10

/** A re-anchor hit nearer than this is the phone's own edge of the scene, not the wall. */
private const val REANCHOR_MIN_METRES = 0.3f

/** Nearest and farthest a held answer hangs in front of the phone: see [AskState.held]. */
private const val HOLD_MIN_METRES = 0.6f
private const val HOLD_MAX_METRES = 3f
private const val RIM_WIDTH = 0.0038f
private const val RIM_DEPTH = 0.004f

private const val THINK_MILLIS = 600L
private const val SETTLE_MILLIS = 1500L
private const val LISTEN_LEAD_MILLIS = 700L
private const val WORD_MILLIS = 210L
private const val WORD_JITTER_MILLIS = 60L
private const val LISTEN_TAIL_MILLIS = 700L
private const val NOTICE_MILLIS = 4000L
private const val CHAIN_HOLD_MILLIS = 2000L

/** Answers kept in the room from earlier questions, at most. */
private const val MAX_PINNED = 4
private const val UNWRITE_MILLIS = 700f

private const val LOG_TAG = "AskInAr"

/** Frames a room read waits for a wall plane or depth hit and a found floor, at most (about a second). */
private const val ROOM_PATIENCE_FRAMES = 30

/** What the QA stage says the wall ahead measures. */
private const val STAGE_WALL_METRES = 2.4f

/**
 * The QA stage's room, for the answers drawn in it: the camera at the origin looking down -Z,
 * the wall [STAGE_WALL_METRES] ahead, the floor at [AskFloor]'s fallback height (nothing is
 * measured on the stage), and the phone pointed at the floor 2.4 m ahead. Nothing either side of
 * the sofa: the stage never writes a number it did not measure.
 */
private val STAGE_ROOM: AskRoom = run {
    val camera = Float3(0f, 0f, 0f)
    val heading = Float3(0f, 0f, -1f)
    val floor = AskFloor.pick(camera.y, emptyList())
    AskRoom(
        camera = camera,
        heading = heading,
        floor = floor,
        wall = WallSpot(Float3(0f, 0f, -STAGE_WALL_METRES), Float3(0f, 0f, 1f)),
        sofa = SofaSpot(SofaFit.centre(camera, heading, floor.y, Float3(0f, floor.y, -2f))),
    )
}

/**
 * The QA stage's room for [question]: [STAGE_ROOM], with the surface ahead brought up to
 * [STAGE_CLOSE_METRES] for a radiator — a bleed screw is looked at from arm's length, not
 * from across the room.
 */
private fun stageRoom(question: String): AskRoom =
    if (ScriptedAnswerProvider.match(question)?.id == "radiator") {
        STAGE_ROOM.copy(wall = WallSpot(Float3(0f, 0f, -STAGE_CLOSE_METRES), Float3(0f, 0f, 1f)))
    } else {
        STAGE_ROOM
    }

private const val STAGE_CLOSE_METRES = 1f
private const val STAGE_CLOSE_FOCAL = 12f

/** The stage looks down at a footprint on the floor, through a wider lens. */
private const val STAGE_FLOOR_PITCH = -28f
private const val STAGE_FLOOR_FOCAL = 10f

/** Farthest a surface is measured for an answer, in metres. */
private const val SURFACE_REACH = 8f
private const val DISTANCE_EVERY_FRAMES = 10
private const val FRAMES_PER_SECOND = 60f
private const val NANOS_PER_SECOND = 1e9f
private const val NANOS_PER_MILLI = 1e6f

private const val RING_MILLIS = 1400
private const val LEVEL_MILLIS = 120
private const val LIVE_DOT_MILLIS = 600
private const val CAPTION_FADE_MILLIS = 120
private const val BUSY_ALPHA = 0.5f

private val ShutterSize = Space.x3l + Space.sm // 72 dp, like the recording shutter
private val ShutterDiscSize = Space.x2l + Space.sm // 56 dp
private val ShutterRingWidth = Space.xs - Space.xs / 4 // 3 dp
private val MicIconSize = Space.lg + Space.xs // 28 dp
private val CaptionMaxWidth = Space.x4l * 4 // 384 dp

/** How fast the reply types out on screen while the pen draws: a quick reader's pace. */
private const val REPLY_CHARS_PER_SECOND = 38f

/** A bubble leaves a quarter of the screen to the other side of the conversation. */
private val BubbleMaxWidth = Space.x4l * 3 // 288 dp
private const val MINE_ALPHA = 0.88f

/** How long a question waits for ARCore to see the room before it gives up. */
private const val ROOM_WAIT_MILLIS = 8_000L
