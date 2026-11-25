package io.github.sceneview.demo.demos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.demos.internal.AnswerProvider
import io.github.sceneview.demo.demos.internal.AskContext
import io.github.sceneview.demo.demos.internal.Handwriting
import io.github.sceneview.demo.demos.internal.HandwritingLayout
import io.github.sceneview.demo.demos.internal.InkPoint
import io.github.sceneview.demo.demos.internal.InkStroke
import io.github.sceneview.demo.demos.internal.ScriptedAnswerProvider
import io.github.sceneview.demo.demos.internal.StrokeProgress
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
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 *   it (yaw only), or on the wall straight ahead when ARCore found one closer than 3 m.
 * - **The question** comes from Android's `SpeechRecognizer`, with a live caption while it
 *   listens. Answers come from an [AnswerProvider]: scripted, on the phone, in this prototype.
 *
 * **Automation for filming:** `sceneview://demo/ask-ar?q=what%20should%20I%20cook%20tonight`
 * (or `--es ask_q "…"`) plays the whole sequence by itself once ARCore tracks: listening,
 * the caption word by word, then the answer writing itself. No voice, no tap.
 */
@Composable
fun AskInArDemo(onBack: () -> Unit) {
    // LayoutLib ships no Filament: stop before any rememberEngine().
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = stringResource(R.string.demo_ask_ar_title), onBack = onBack)
        return
    }
    AskInArScene(onBack, ScriptedAnswerProvider)
}

/** Where the demo is between a question and its answer. */
internal enum class AskPhase { Idle, Listening, Thinking, Writing, Done }

/** Everything the screen shows, and what the AR frame callback reads. */
@Stable
private class AskState {
    var phase by mutableStateOf(AskPhase.Idle)
    var transcript by mutableStateOf("")
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

    /** Distance to whatever the centre of the screen points at, read every few frames. */
    var distanceAhead: Float? = null
    var frameCount = 0

    fun clearAnswer() {
        handwriting = null
        anchor = null
        stagePlaced = false
        elapsed = 0f
        placeRequested = false
    }
}

@Composable
private fun AskInArScene(onBack: () -> Unit, provider: AnswerProvider) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { AskState() }
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<Job?>(null) }

    var isTracking by remember { mutableStateOf(false) }
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

    /** Answers [question]: a beat of thought, then the anchor, then the pen. */
    suspend fun answer(question: String) {
        state.phase = AskPhase.Thinking
        state.level = 0f
        val reply = provider.answer(question, AskContext(distanceAheadMeters = state.distanceAhead))
        delay(THINK_MILLIS)
        state.handwriting = HandwritingLayout.layout(reply.text, reply.doodle)
        if (stage) {
            val size = currentViewSize
            val aspect = if (size.height > 0) size.width.toFloat() / size.height else STAGE_ASPECT
            val tall = 2f * -STAGE_BLOCK.z * STAGE_SENSOR_HALF / STAGE_FOCAL
            state.blockScale = state.handwriting
                ?.let { HandwritingLayout.fitScale(it.bounds, tall * aspect, tall) } ?: 1f
            state.stagePlaced = true
        } else {
            state.placeRequested = true
            snapshotFlow { state.anchor }.first { it != null }
        }
        state.elapsed = 0f
        state.phase = AskPhase.Writing
        val total = state.handwriting?.totalSeconds ?: 0f
        val start = withFrameNanos { it }
        while (state.elapsed < total) {
            withFrameNanos { now -> state.elapsed = (now - start) / NANOS_PER_SECOND }
        }
        state.phase = AskPhase.Done
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
        state.transcript = ""
        state.level = 0f
        state.phase = AskPhase.Idle
    }

    // Filming: a question passed in the link plays by itself once the room is tracked, after
    // a beat for ARCore to settle its idea of the floor.
    val automated = remember(activity) { AskArAutomation.take(activity?.intent) }
    LaunchedEffect(automated) {
        val question = automated ?: return@LaunchedEffect
        snapshotFlow { isTracking }.first { it }
        delay(SETTLE_MILLIS)
        launchFlow { simulate(question) }
    }

    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            state.notice = null
        }
    }

    DemoScaffold(
        monitorArMotionSensors = true,
        title = stringResource(R.string.demo_ask_ar_title),
        onBack = onBack,
        actionsMenu = true,
        chromeToggleOnTap = true,
        recorder = recorder,
        bottomOverlay = {
            DemoStatusBanner(
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
                level = state.level,
                showHint = isTracking,
                hasAnswer = state.handwriting != null || state.transcript.isNotEmpty(),
                onMic = ::onMic,
                onClear = ::clear,
                onExample = ::askExample,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            if (stage) {
                QaCameraBackdrop(seed = "ask-ar")
                AskQaStage(engine, materialLoader, recorder.surfaceMirrorer) {
                    val handwriting = state.handwriting
                    if (state.stagePlaced && handwriting != null) {
                        Node(position = STAGE_BLOCK) { AnswerInk(handwriting, state.blockScale, state.elapsed, ink, rim) }
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
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionFailure = {
                    state.clearAnswer()
                    isTracking = false
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING
                    isTracking = tracking
                    if (tracking) {
                        if (state.frameCount++ % DISTANCE_EVERY_FRAMES == 0) {
                            state.distanceAhead = distanceAhead(frame, currentViewSize)
                        }
                        if (state.placeRequested) {
                            val (pose, distance) = AskPlacement.pose(frame, currentViewSize)
                            val anchor = runCatching { session.createAnchor(pose) }.getOrNull()
                            if (anchor != null) {
                                val (wide, tall) = AskPlacement.visibleSize(frame, distance)
                                state.blockScale = state.handwriting
                                    ?.let { HandwritingLayout.fitScale(it.bounds, wide, tall) } ?: 1f
                                state.placeRequested = false
                                state.anchor = anchor
                            }
                        }
                    }
                },
            ) {
                val anchor = state.anchor
                val handwriting = state.handwriting
                if (anchor != null && handwriting != null) {
                    key(anchor) {
                        AnchorNode(anchor = anchor) { AnswerInk(handwriting, state.blockScale, state.elapsed, ink, rim) }
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
    // Centred on what is drawn, doodle included, and shrunk to fit the screen.
    val bounds = handwriting.bounds
    Node(
        position = Position(-bounds.centerX * scale, -bounds.centerY * scale, 0f),
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
 * The QA stage: a transparent 3D scene over the room photo, its camera fixed at eye height
 * looking straight ahead, so the answer is written where the AR mode would hang it.
 */
@Composable
private fun AskQaStage(
    engine: Engine,
    materialLoader: MaterialLoader,
    surfaceMirrorer: SurfaceMirrorer?,
    content: @Composable SceneScope.() -> Unit,
) {
    val camera = rememberCameraNode(engine) {
        position = Position(0f, 0f, 0f)
        focalLength = STAGE_FOCAL.toDouble()
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
) {
    val full = remember(stroke) { stroke.points.map { Position(it.x, it.y, 0f) } }
    val fullRim = remember(stroke) { stroke.radii.map { it + RIM_WIDTH } }
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
    val rimRadii = remember(radii) { if (radii === stroke.radii) fullRim else radii.map { it + RIM_WIDTH } }
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
        position = Position(z = -RIM_DEPTH),
        apply = {
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
}

private fun lerp(a: InkPoint, b: InkPoint, t: Float) = InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** The caption over the mic, the mic itself, and the two small actions either side of it. */
@Composable
private fun AskControls(
    phase: AskPhase,
    transcript: String,
    level: Float,
    showHint: Boolean,
    hasAnswer: Boolean,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onExample: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val caption = when {
            transcript.isNotEmpty() -> transcript
            phase == AskPhase.Listening -> stringResource(R.string.demo_ask_ar_listening)
            showHint -> stringResource(R.string.demo_ask_ar_idle_hint)
            else -> null
        }
        AnimatedVisibility(
            visible = caption != null,
            enter = fadeIn(SceneViewTokens.Motion.fade()),
            exit = fadeOut(SceneViewTokens.Motion.fade()),
        ) {
            CaptionChip(
                text = caption.orEmpty(),
                live = phase == AskPhase.Listening,
                muted = transcript.isEmpty(),
            )
        }
        Spacer(Modifier.height(Space.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            GlassIconButton(
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.demo_ask_ar_clear),
                onClick = onClear,
                enabled = hasAnswer && phase != AskPhase.Thinking,
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
    fun pose(frame: Frame, viewSize: IntSize): Pair<Pose, Float> {
        val camera = frame.camera.pose
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

    private fun yawRotation(yaw: Float) = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))

    private const val AHEAD = 1.5f
    private const val BELOW_EYE = 0.05f
    private const val WALL_REACH = 3f
    private const val WALL_OFFSET = 0.03f
    private const val MIN_FLAT = 0.2f
    private const val NEAR = 0.1f
    private const val FAR = 100f
}

/** How far the surface at the centre of the screen is, for "how far is that wall?". */
private fun distanceAhead(frame: Frame, viewSize: IntSize): Float? {
    if (viewSize.width == 0 || viewSize.height == 0) return null
    return runCatching { frame.hitTest(viewSize.width / 2f, viewSize.height / 2f) }.getOrNull()
        ?.firstOrNull()?.distance
}

/**
 * The question a link or a launch extra asked for, once: `sceneview://demo/ask-ar?q=…` or
 * `--es ask_q "…"`. The intent is marked so coming back to the demo does not replay it.
 */
internal object AskArAutomation {
    const val QUERY_PARAM = "q"
    const val EXTRA = "ask_q"
    private const val CONSUMED = "ask_q_consumed"

    fun take(intent: Intent?): String? {
        if (intent == null || intent.getBooleanExtra(CONSUMED, false)) return null
        val fromLink = runCatching {
            intent.data?.takeIf { it.lastPathSegment == "ask-ar" }?.getQueryParameter(QUERY_PARAM)
        }.getOrNull()
        val question = (intent.getStringExtra(EXTRA) ?: fromLink)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        intent.putExtra(CONSUMED, true)
        // A spoken question starts with a capital and ends with a question mark.
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
private const val RIM_WIDTH = 0.0024f
private const val RIM_DEPTH = 0.004f

private const val THINK_MILLIS = 600L
private const val SETTLE_MILLIS = 1500L
private const val LISTEN_LEAD_MILLIS = 700L
private const val WORD_MILLIS = 210L
private const val WORD_JITTER_MILLIS = 60L
private const val LISTEN_TAIL_MILLIS = 700L
private const val NOTICE_MILLIS = 4000L
private const val DISTANCE_EVERY_FRAMES = 10
private const val FRAMES_PER_SECOND = 60f
private const val NANOS_PER_SECOND = 1e9f

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
