package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.view.Surface
import android.view.WindowManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.cameraImage
import io.github.sceneview.ar.arcore.toArgbBitmap
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ai.AskEngine
import io.github.sceneview.demo.ai.AskEngineStatus
import io.github.sceneview.demo.ai.rememberAskEngine
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlin.math.atan2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags for the Point & Ask QA flows (Maestro / layout dumps). */
object PointAndAskTestTags {
    const val ANSWER_CARD = "point_and_ask_answer_card"
    const val QUESTION_FIELD = "point_and_ask_question_field"
}

/** Lifecycle of one "ask" round-trip, driving the bottom card. */
private sealed interface AskState {
    /** Nothing in flight — tap to ask. */
    data object Idle : AskState

    /**
     * Tap registered at view coordinates ([tapX], [tapY]), waiting for the next camera
     * frame with a CPU image. The coordinates feed the P2 hit-test so the answer is
     * pinned where the user pointed, on the SAME frame the pixels come from.
     */
    data class Capturing(val tapX: Float, val tapY: Float) : AskState

    /** Frame captured but the tap hit no tracked surface — screen-space fallback round. */
    data object Thinking : AskState

    /**
     * Screen-space fallback answer (the tap hit no tracked surface, or the qa_mode
     * synthetic-frame timeout fired). While [streaming] the model is still appending
     * deltas (P3); once `false` the answer is complete and stays until the next tap
     * or reset. World-anchored answers live in [AnswerPanel], not here.
     */
    data class Answered(val text: String, val streaming: Boolean = false) : AskState

    /** Inference or capture failed — transient, retry on next tap. */
    data object Failed : AskState
}

/**
 * One world-anchored answer panel (P2): the tap's ARCore [Anchor] plus the streamed
 * answer text rendered on a `ViewNode` at that anchor. Multiple panels can coexist —
 * every successful tap pins a new one until Reset.
 *
 * [text]/[streaming] are snapshot state so the in-scene card grows live with the
 * stream deltas exactly like the P3 screen-space card does.
 */
private class AnswerPanel(
    val id: Int,
    val anchor: Anchor,
    /**
     * World-space yaw (degrees) turning the card toward where the user stood at tap
     * time. Frozen on purpose: orbiting AROUND a fixed card is what proves the answer
     * is anchored in the world, not billboarded to the screen.
     */
    val facingYawDegrees: Float,
) {
    var text by mutableStateOf("")
    var streaming by mutableStateOf(true)

    /**
     * Adapts the shared ask-stream state machine to this panel's card. A failure before
     * any delta shows [failedText] on the card rather than removing it — the anchor
     * placement already succeeded, and keeping the panel makes the failure visible in
     * place instead of silently un-pinning.
     */
    fun resultRouter(failedText: String): (AskState) -> Unit = { state ->
        when (state) {
            is AskState.Answered -> {
                text = state.text
                streaming = state.streaming
            }
            AskState.Failed -> {
                if (text.isBlank()) text = failedText
                streaming = false
            }
            else -> Unit
        }
    }
}

/**
 * AR demo — Point & Ask: tap anything in the camera view, the current AR camera frame is
 * captured and sent (image + question) to **Gemini Nano on-device** through ML Kit's GenAI
 * Prompt API, and the one-sentence answer is shown in an overlay card. Fully offline — the
 * frame never leaves the device.
 *
 *  - Availability is gated honestly: AICore devices (Pixel 8+, recent flagships) get the real
 *    engine; a `DOWNLOADABLE` model gets a download CTA with progress; unsupported devices see
 *    an explanatory banner instead of a hidden demo. There is deliberately no cloud fallback —
 *    on-device only is the privacy contract of this demo (#2648).
 *  - Under `DemoSettings.qaMode` the engine is a deterministic canned stand-in, because AICore
 *    is structurally unavailable on emulators — the tap → capture → answer UI flow stays
 *    device-QA-able (see `AskEngine.kt`).
 *  - Frame pixels come from the existing `Frame.cameraImage()` + `Image.toArgbBitmap()`
 *    helpers; acquisition happens in `onSessionUpdated` (the only place the latest frame's CPU
 *    image is reliably available) and the YUV → ARGB conversion runs off the main thread.
 *
 * P1+P2+P3 of [#2648](https://github.com/sceneview/sceneview/issues/2648): P3 adds the
 * free-form question field (controls sheet; blank = default prompt) and **streamed**
 * answers — `askStream` deltas grow the card live with a typing cursor. P2 anchors the
 * answer **in world space**: the tap is hit-tested against the SAME frame the pixels come
 * from; a hit on a tracked surface pins an [AnswerPanel] (`AnchorNode` + `ViewNode`) at
 * that pose, facing where the user stood, and stays put while the camera orbits. Multiple
 * panels accumulate (one per successful tap) until Reset; a tap that hits no tracked
 * surface falls back to the P1 screen-space bottom card.
 */
@Composable
fun PointAndAskDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>`. `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val askEngine = rememberAskEngine()
    var engineStatus by remember { mutableStateOf<AskEngineStatus?>(null) }
    LaunchedEffect(askEngine) { engineStatus = askEngine.status() }

    var askState by remember { mutableStateOf<AskState>(AskState.Idle) }
    var isTracking by remember { mutableStateOf(false) }

    // World-anchored answer panels (P2) — one per successful tap, until Reset.
    val panels = remember { mutableStateListOf<AnswerPanel>() }
    var nextPanelId by remember { mutableIntStateOf(0) }
    val viewNodeManager = rememberViewNodeManager()

    // ARCore anchors accrue per-frame tracking cost while attached (#2043) — release
    // every panel's anchor when the demo leaves composition. `detach()` is idempotent,
    // so a Reset-then-dispose sequence double-detaching is harmless.
    DisposableEffect(Unit) {
        onDispose { panels.forEach { it.anchor.detach() } }
    }

    // Free-form question (P3): blank = the default English prompt (best Nano quality).
    // Saveable so a rotation mid-session keeps the user's custom question.
    val defaultQuestion = stringResource(R.string.demo_point_and_ask_question)
    // Resolved at composition — panel result routing runs in non-composable callbacks.
    val failedText = stringResource(R.string.demo_point_and_ask_error)
    var questionText by rememberSaveable { mutableStateOf("") }
    val question = questionText.trim().ifBlank { defaultQuestion }

    // Capture timeout — a tap can only complete once ARCore delivers a CPU camera image,
    // which never happens when tracking can't start (tap before scanning finishes) or on
    // emulators whose camera stream / dataset playback is broken (#1645). Under qaMode the
    // timeout falls back to a synthetic frame so the tap → answer flow stays deterministic
    // for the device-QA harness; otherwise it surfaces the transient Failed card instead of
    // spinning forever.
    LaunchedEffect(askState) {
        if (askState !is AskState.Capturing) return@LaunchedEffect
        delay(if (DemoSettings.qaMode) QA_CAPTURE_TIMEOUT_MS else CAPTURE_TIMEOUT_MS)
        // Still Capturing after the delay (any state change restarts this effect).
        if (DemoSettings.qaMode) {
            askState = AskState.Thinking
            val synthetic = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            scope.askAboutBitmap(synthetic, askEngine, question) { askState = it }
        } else {
            askState = AskState.Failed
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_point_and_ask_title),
        onBack = onBack,
        onReset = {
            askState = AskState.Idle
            panels.forEach { it.anchor.detach() }
            panels.clear()
        },
        onResetSettings = { questionText = "" },
        controls = {
            // Free-form question (P3) — blank falls back to the default prompt, which the
            // placeholder shows. The next tap asks THIS question about the camera frame.
            OutlinedTextField(
                value = questionText,
                onValueChange = { questionText = it },
                label = { Text(stringResource(R.string.demo_point_and_ask_question_label)) },
                placeholder = { Text(defaultQuestion) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PointAndAskTestTags.QUESTION_FIELD),
            )
            if (DemoSettings.qaMode) {
                ForceTrackingFailureMenu()
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                // P2: planes are visible so the user can see where a tap will pin the answer.
                planeRenderer = true,
                viewNodeWindowManager = viewNodeManager,
                onSessionUpdated = { _, frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Serve a pending tap with THIS frame's CPU image — acquiring from a stored
                    // older frame throws once the session has advanced, so capture must happen
                    // here, not in the tap callback. A `null` image is normal during warm-up:
                    // stay in Capturing and retry on the next frame.
                    val capturing = askState as? AskState.Capturing
                    if (capturing != null && isTracking) {
                        frame.cameraImage()?.let { image ->
                            // P2: hit-test the tap against the SAME frame the pixels come
                            // from, so the pinned pose matches what the model is looking at.
                            // Tracked planes (tap inside the polygon) and feature points both
                            // accept; no hit falls back to the P1 screen-space card.
                            val hit = frame.hitTest(capturing.tapX, capturing.tapY)
                                .firstOrNull { result ->
                                    val trackable = result.trackable
                                    trackable.trackingState == TrackingState.TRACKING &&
                                        (trackable is Point ||
                                            (trackable is Plane &&
                                                trackable.isPoseInPolygon(result.hitPose)))
                                }
                            val panel = hit?.let { h ->
                                val camera = frame.camera.pose
                                AnswerPanel(
                                    id = nextPanelId++,
                                    anchor = h.createAnchor(),
                                    facingYawDegrees = facingYawDegrees(
                                        fromX = h.hitPose.tx(), fromZ = h.hitPose.tz(),
                                        toX = camera.tx(), toZ = camera.tz(),
                                    ),
                                ).also { panels.add(it) }
                            }
                            askState = if (panel != null) AskState.Idle else AskState.Thinking
                            scope.askAboutImage(
                                image = image,
                                rotationDegrees = cameraRotationDegrees(context),
                                askEngine = askEngine,
                                question = question,
                                onResult = panel?.resultRouter(failedText = failedText)
                                    ?: { askState = it },
                            )
                        }
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event, _ ->
                        val busy = askState is AskState.Capturing ||
                            askState == AskState.Thinking ||
                            (askState as? AskState.Answered)?.streaming == true ||
                            panels.any { it.streaming }
                        if (engineStatus == AskEngineStatus.Ready && !busy) {
                            askState = AskState.Capturing(event.x, event.y)
                        }
                    },
                ),
            ) {
                // P2: one world-anchored answer card per successful tap. The AnchorNode
                // follows ARCore's refined pose; the ViewNode renders the same streamed
                // card UI, standing upright ~12 cm above the hit, turned toward where the
                // user stood at tap time (orbiting around it proves world anchoring).
                panels.forEach { panel ->
                    key(panel.id) {
                        AnchorNode(anchor = panel.anchor) {
                            ViewNode(
                                windowManager = viewNodeManager,
                                unlit = true,
                                position = Position(y = PANEL_LIFT_METERS),
                                rotation = Rotation(y = panel.facingYawDegrees),
                                scale = Scale(PANEL_SCALE),
                            ) {
                                AnchoredAnswerCard(
                                    text = panel.text,
                                    streaming = panel.streaming,
                                )
                            }
                        }
                    }
                }
            }

            // Top-center status pill — what to do next, or why nothing happens.
            M3Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = when {
                        engineStatus == null ->
                            stringResource(R.string.demo_point_and_ask_status_checking)
                        engineStatus == AskEngineStatus.Ready && !isTracking ->
                            stringResource(R.string.ar_status_scanning)
                        engineStatus == AskEngineStatus.Ready ->
                            stringResource(R.string.demo_point_and_ask_status_ready)
                        else ->
                            stringResource(R.string.demo_point_and_ask_status_limited)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Bottom overlay — exactly one of: unavailable banner, download CTA/progress,
            // thinking indicator, answer card, transient failure.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                when (val status = engineStatus) {
                    null -> Unit

                    AskEngineStatus.Unavailable -> BottomCard {
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_unavailable_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    AskEngineStatus.Downloadable -> BottomCard {
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_download_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch {
                                askEngine.download().collect { engineStatus = it }
                            }
                        }) {
                            Text(stringResource(R.string.demo_point_and_ask_download_cta))
                        }
                    }

                    is AskEngineStatus.Downloading -> BottomCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                text = status.totalBytesDownloaded
                                    ?.let { bytes ->
                                        stringResource(
                                            R.string.demo_point_and_ask_downloading_progress,
                                            bytes / (1024 * 1024),
                                        )
                                    }
                                    ?: stringResource(R.string.demo_point_and_ask_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }

                    AskEngineStatus.Ready -> when (val ask = askState) {
                        AskState.Idle -> Unit

                        is AskState.Capturing, AskState.Thinking -> BottomCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Text(
                                    text = stringResource(
                                        R.string.demo_point_and_ask_status_thinking
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }

                        is AskState.Answered -> BottomCard(
                            testTag = PointAndAskTestTags.ANSWER_CARD,
                        ) {
                            // "▌" = live-typing cursor while deltas keep arriving.
                            Text(
                                text = if (ask.streaming) "${ask.text}▌" else ask.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.demo_point_and_ask_answer_source),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        AskState.Failed -> BottomCard {
                            Text(
                                text = stringResource(R.string.demo_point_and_ask_error),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The anchored answer card floats this high above the hit pose (meters). */
private const val PANEL_LIFT_METERS = 0.12f

/**
 * World scale of the anchored card's `ViewNode`. The node renders at
 * `ViewNode.pxPerUnits` (250 px/m): a ~650 px card would be ~2.6 m wide at scale 1;
 * 0.15 brings it to ~0.4 m — readable at arm's length without walling off the scene.
 */
private const val PANEL_SCALE = 0.15f

/**
 * World-space yaw (degrees) turning a card at (`fromX`, `fromZ`) toward a viewer at
 * (`toX`, `toZ`) — the rotation around +Y that points the card's front (+Z) at the
 * viewer's ground-plane position.
 */
private fun facingYawDegrees(fromX: Float, fromZ: Float, toX: Float, toZ: Float): Float =
    Math.toDegrees(atan2((toX - fromX).toDouble(), (toZ - fromZ).toDouble())).toFloat()

/** Capture must complete within this window on a normal device before failing the round. */
private const val CAPTURE_TIMEOUT_MS = 12_000L

/**
 * Shorter window under [DemoSettings.qaMode], after which a synthetic frame stands in for
 * the camera image — emulators without a working camera stream / dataset playback (#1645)
 * would otherwise never complete the flow.
 */
private const val QA_CAPTURE_TIMEOUT_MS = 5_000L

/**
 * Converts [image] to an upright bitmap off the main thread, then delegates to
 * [askAboutBitmap]. Always closes [image] — including when the scope is cancelled
 * mid-flight: [CoroutineStart.UNDISPATCHED] enters the `try` synchronously before the
 * first suspension, so the `finally` close runs even if the composition is disposed
 * during the capture window (leaking one CPU image stalls ARCore within a few frames).
 */
private fun CoroutineScope.askAboutImage(
    image: Image,
    rotationDegrees: Int,
    askEngine: AskEngine,
    question: String,
    onResult: (AskState) -> Unit,
) = launch(start = CoroutineStart.UNDISPATCHED) {
    // JPEG round-trip conversion is main-thread-hostile — off to Default.
    val bitmap = try {
        withContext(Dispatchers.Default) { image.toArgbBitmap(rotationDegrees) }
    } finally {
        image.close()
    }
    if (bitmap == null) {
        onResult(AskState.Failed)
        return@launch
    }
    askAboutBitmap(bitmap, askEngine, question, onResult)
}

/**
 * Runs one streamed [AskEngine.askStream] round-trip over [bitmap], reporting a growing
 * [AskState.Answered] per delta and the final state on completion. A failure mid-stream
 * keeps the text already received (marked complete) — only a failure before any delta
 * surfaces [AskState.Failed]. Takes ownership of [bitmap] (recycled when the round ends).
 */
private fun CoroutineScope.askAboutBitmap(
    bitmap: Bitmap,
    askEngine: AskEngine,
    question: String,
    onResult: (AskState) -> Unit,
) = launch {
    var text = ""
    try {
        askEngine.askStream(bitmap, question).collect { delta ->
            text += delta
            onResult(AskState.Answered(text, streaming = true))
        }
        onResult(if (text.isBlank()) AskState.Failed else AskState.Answered(text))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onResult(if (text.isBlank()) AskState.Failed else AskState.Answered(text))
    } finally {
        bitmap.recycle()
    }
}

/**
 * In-scene answer card rendered by the anchored `ViewNode` (P2). Mirrors the P3
 * screen-space card states: spinner while waiting for the first delta, live text with a
 * typing cursor while streaming, text + source label when complete.
 */
@Composable
private fun AnchoredAnswerCard(text: String, streaming: Boolean) {
    M3Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .padding(16.dp),
        ) {
            if (text.isEmpty() && streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.demo_point_and_ask_status_thinking),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                Text(
                    text = if (streaming) "${text}▌" else text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.demo_point_and_ask_answer_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Shared bottom-overlay card chrome for every Point & Ask state. */
@Composable
private fun BottomCard(
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    M3Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (testTag != null) it.testTag(testTag) else it },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/**
 * Degrees to rotate the ARCore CPU camera image to upright for the current display
 * orientation. ARCore's back-camera sensor orientation on Android phones is 90° — same
 * mapping as the ML Kit object-label demo.
 */
private fun cameraRotationDegrees(context: Context): Int {
    val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
    }
    return when (displayRotation) {
        Surface.ROTATION_0 -> 90
        Surface.ROTATION_90 -> 0
        Surface.ROTATION_180 -> 270
        Surface.ROTATION_270 -> 180
        else -> 90
    }
}
