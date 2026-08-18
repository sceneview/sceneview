package io.github.sceneview.demo.demos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ai.AskEngine
import io.github.sceneview.demo.ai.AskEngineStatus
import io.github.sceneview.demo.ai.rememberAskEngine
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags for the Point & Ask QA flows (Maestro / layout dumps). */
object PointAndAskTestTags {
    const val ANSWER_CARD = "point_and_ask_answer_card"
    const val QUESTION_FIELD = "point_and_ask_question_field"
}

/** Lifecycle of one "ask" round-trip, driving the bottom card. */
private sealed interface AskState {
    /** Nothing in flight — tap to ask. */
    data object Idle : AskState

    /** Tap registered, composited window capture in flight. */
    data object Capturing : AskState

    /** Frame captured, Gemini Nano inference in flight. */
    data object Thinking : AskState

    /**
     * Answer text so far. While [streaming] the model is still appending deltas (P3
     * progressive display); once `false` the answer is complete and auto-dismisses.
     */
    data class Answered(val text: String, val streaming: Boolean = false) : AskState

    /** Inference or capture failed — transient, retry on next tap. */
    data object Failed : AskState
}

/** One long-press placement: a real-world anchor carrying the showcase model. */
private data class PlacedProp(val id: Int, val anchor: Anchor)

/**
 * One world-anchored answer (P2): the tapped surface's ARCore [Anchor] plus the streamed
 * answer, rendered on a `ViewNode` at that pose. Every tap that lands on a tracked surface
 * pins a new panel, and they accumulate until Reset — the room fills with the answers you
 * asked for, each one staying where you pointed.
 *
 * [text]/[streaming] are snapshot state, so the in-scene card grows delta by delta exactly
 * like the screen-space card does. [question] is frozen at tap time: a later edit of the
 * question field must not rewrite the label of an answer already pinned.
 *
 * The card needs no facing rotation of its own — but only because the hit is filtered to
 * `HORIZONTAL_UPWARD_FACING` planes and feature points. On those, the ARCore hit pose is
 * oriented "Z+ … roughly toward the user's device" (see `PoseNode`), and a `ViewNode`'s
 * quad faces its own +Z, so an identity rotation under the `AnchorNode` faces where the
 * user stood at tap time. That facing is then frozen with the anchor — orbiting AROUND a
 * fixed card is what proves the answer is anchored in the world rather than billboarded.
 *
 * The qualifier is load-bearing: on a VERTICAL plane, Y+ is the wall normal and Z+ lies IN
 * the wall surface, so the same identity rotation would pin the card edge-on. Wall taps are
 * therefore rejected by the hit filter and fall through to the screen-space card; pinning
 * them properly needs `wallFacingRotation()` and a device to verify on (#2754).
 */
private class AnswerPanel(
    val id: Int,
    val anchor: Anchor,
    val question: String,
) {
    var text by mutableStateOf("")
    var streaming by mutableStateOf(true)

    /**
     * Mirrors the shared ask-stream state machine onto this panel. A failure before any
     * delta shows [failedText] on the card rather than removing it: the anchor placement
     * already succeeded, and keeping the panel makes the failure visible where the user
     * pointed instead of silently un-pinning it.
     */
    fun accept(state: AskState, failedText: String) {
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
 * AR demo — Point & Ask: tap anything in the AR view, the **composited AR frame**
 * (camera + placed virtual objects) is captured and sent (image + question) to
 * **Gemini Nano on-device** through ML Kit's GenAI Prompt API, and the streamed answer
 * is shown in an overlay card. Fully offline — the frame never leaves the device.
 *
 * Long-press a surface to drop a virtual prop into the room: because the capture is the
 * composited window (PixelCopy), the on-device model *sees the augmented scene* — ask
 * "Is there an animal in this room?" after placing the shiba and Nano answers about a
 * dog that only exists in AR. That is the demo's whole point.
 *
 *  - Availability is gated honestly: AICore devices (Pixel 8+, recent flagships) get the
 *    real engine; a `DOWNLOADABLE` model gets a download CTA with progress; unsupported
 *    devices see an explanatory banner. No cloud fallback — on-device only (#2648).
 *  - Under `DemoSettings.qaMode` the engine is a deterministic canned stand-in and the
 *    capture falls back to a synthetic frame (AICore/camera are structurally unavailable
 *    on emulators) — the tap → capture → answer UI flow stays device-QA-able.
 *
 * P1+P2+P3 of [#2648](https://github.com/sceneview/sceneview/issues/2648), plus the
 * film-mode polish pass: composited capture, long-press placement, tap ping, quieter
 * overlays, offline badge, auto-dismissing answer card. P2 anchors the answer **in world
 * space**: the tap is hit-tested against the latest frame and a hit on a tracked surface
 * pins an [AnswerPanel] (`AnchorNode` + `ViewNode`) that holds its place in the room while
 * the camera orbits it. Panels accumulate — one per successful tap — until Reset; a tap
 * that hits nothing trackable keeps the screen-space card.
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
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Long-press placements — each drops the showcase model on the hit surface.
    val placedProps = remember { mutableStateListOf<PlacedProp>() }
    var nextPropId by remember { mutableStateOf(0) }

    // World-anchored answers (P2) — one per tap that lands on a tracked surface, until
    // Reset. `pendingPanel` is the one the in-flight round streams into; `null` means the
    // tap hit nothing trackable and the answer falls back to the screen-space card.
    val panels = remember { mutableStateListOf<AnswerPanel>() }
    var nextPanelId by remember { mutableStateOf(0) }
    var pendingPanel by remember { mutableStateOf<AnswerPanel?>(null) }

    // A round is busy while the screen card is working OR any anchored panel is
    // still streaming. An anchored round hands the screen card back to Idle on
    // its first delta, so `askState` alone under-reports it. The tap guard and
    // the status pill BOTH read this single value — otherwise the pill returns
    // to "tap to ask" mid-stream while the guard silently drops every tap.
    val busy by remember {
        derivedStateOf {
            askState == AskState.Capturing ||
                askState == AskState.Thinking ||
                (askState as? AskState.Answered)?.streaming == true ||
                panels.any { it.streaming }
        }
    }
    val viewNodeManager = rememberViewNodeManager()

    // The in-flight ask. It runs on `scope`, NOT on the capture effect, so nothing else
    // stops it: a Reset that only cleared the panels would leave a round streaming into an
    // orphaned panel, and — because an anchored round writes `askState` — that zombie would
    // knock the NEXT round out of its capture and wedge the demo (every later tap swallowed
    // by the busy-guard). Reset cancels it instead.
    var askJob by remember { mutableStateOf<Job?>(null) }

    // Belt and braces on the #2043 anchor contract. `AnchorNode.destroy()` already detaches
    // on dispose, so this only covers the window where a panel/prop is in the list but its
    // node has not composed yet (tap, then dispose in the same frame). `detach()` is
    // idempotent, so overlapping with the node's own detach is harmless.
    DisposableEffect(Unit) {
        onDispose {
            panels.forEach { runCatching { it.anchor.detach() } }
            placedProps.forEach { runCatching { it.anchor.detach() } }
        }
    }

    // Tap ping — a one-shot expanding ring at the tapped point, connecting the gesture
    // to the answer that follows. Keyed by timestamp so consecutive taps re-animate.
    var ping by remember { mutableStateOf<Pair<Offset, Long>?>(null) }

    // Overlays are hidden for the few frames around the PixelCopy so the captured
    // composite is a clean viewfinder (no pill, no card baked into the AI's input).
    var hideOverlaysForCapture by remember { mutableStateOf(false) }

    // Free-form question (P3): blank = the default English prompt (best Nano quality).
    // Saveable so a rotation mid-session keeps the user's custom question. Film mode
    // prefills the "AI sees the augmented room" reveal question.
    val defaultQuestion = stringResource(R.string.demo_point_and_ask_question)
    val prefillQuestion = stringResource(R.string.demo_point_and_ask_prefill_question)
    var questionText by rememberSaveable { mutableStateOf(prefillQuestion) }
    val question = questionText.trim().ifBlank { defaultQuestion }
    // Resolved at composition — anchored panels route results from non-composable callbacks.
    val failedText = stringResource(R.string.demo_point_and_ask_error)

    // Composited capture (film mode): PixelCopy on the window sees camera + virtual
    // props exactly as the user does. qaMode keeps the synthetic-frame fallback so the
    // flow stays deterministic on emulators (#1645).
    LaunchedEffect(askState) {
        if (askState != AskState.Capturing) {
            // A state change mid-capture (e.g. reset) cancels the capturing run at its
            // settle delay before the PixelCopy callback un-hides the overlays — this
            // relaunch is the only place left to restore them.
            hideOverlaysForCapture = false
            return@LaunchedEffect
        }
        // Where this round's answer goes: the panel pinned by the tap (P2), or the
        // screen-space card when the tap hit nothing trackable. Resolved once, here, so a
        // panel pinned by a LATER tap can never steal this round's deltas.
        val onResult = answerSink(pendingPanel, failedText) { askState = it }
        if (DemoSettings.qaMode) {
            delay(QA_CAPTURE_TIMEOUT_MS)
            if (askState != AskState.Capturing) return@LaunchedEffect
            askState = AskState.Thinking
            val synthetic = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            askJob = scope.askAboutBitmap(synthetic, askEngine, question, onResult)
            return@LaunchedEffect
        }
        val activity = context.findActivity()
        val decor = activity?.window?.decorView
        if (decor == null || decor.width == 0 || decor.height == 0) {
            onResult(AskState.Failed)
            return@LaunchedEffect
        }
        hideOverlaysForCapture = true
        delay(CAPTURE_OVERLAY_SETTLE_MS)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            activity.window,
            bitmap,
            { result ->
                hideOverlaysForCapture = false
                if (result == PixelCopy.SUCCESS) {
                    askState = AskState.Thinking
                    askJob = scope.askAboutBitmap(bitmap, askEngine, question, onResult)
                } else {
                    bitmap.recycle()
                    onResult(AskState.Failed)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    // Auto-dismiss a completed answer so nothing lingers over the viewfinder.
    LaunchedEffect(askState) {
        val answered = askState as? AskState.Answered ?: return@LaunchedEffect
        if (answered.streaming) return@LaunchedEffect
        delay(ANSWER_AUTO_DISMISS_MS)
        if (askState == answered) askState = AskState.Idle
    }

    DemoScaffold(
        title = stringResource(R.string.demo_point_and_ask_title),
        onBack = onBack,
        onReset = {
            askJob?.cancel()
            askJob = null
            askState = AskState.Idle
            placedProps.forEach { runCatching { it.anchor.detach() } }
            placedProps.clear()
            pendingPanel = null
            panels.forEach { runCatching { it.anchor.detach() } }
            panels.clear()
        },
        onResetSettings = { questionText = "" },
        controls = {
            // Free-form question (P3) — blank falls back to the default prompt, which the
            // placeholder shows. The next tap asks THIS question about the composited frame.
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
        // The status pill — one clear instruction, and it gets out of the way: hidden
        // while thinking/answering, and hidden from the AI's captured frame. Hosted by
        // the scaffold's `topOverlay` slot, which owns the top gutter and the inset
        // (#3237).
        topOverlay = {
            AnimatedVisibility(
                visible = !hideOverlaysForCapture &&
                    askState == AskState.Idle &&
                    !busy &&
                    engineStatus != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                M3Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    contentColor = Color.White,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = when {
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
            }
        },
        // The answer card / banner / progress row. Hosted by the scaffold's
        // `bottomOverlay` slot so it is laid out against the Settings FAB instead of
        // under it: this card is `fillMaxWidth()`, so at plain `Alignment.BottomCenter`
        // it ran into the bottom-end FAB by construction, in every state (#2779).
        bottomOverlay = {
            // Bottom overlay — exactly one of: unavailable banner, download CTA/progress,
            // thinking indicator, answer card, transient failure.
            if (!hideOverlaysForCapture) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    // Full-width card: only its end edge can reach the Settings
                    // FAB, so only the end edge is inset (0.dp when there is no FAB).
                    .padding(end = settingsFabReservedSpace),
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

                        AskState.Capturing, AskState.Thinking -> BottomCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(
                                    R.string.demo_point_and_ask_status_thinking
                                ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }

                        is AskState.Answered -> BottomCard(
                            testTag = PointAndAskTestTags.ANSWER_CARD,
                        ) {
                            Text(
                                text = question,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            )
                            Spacer(Modifier.height(6.dp))
                            // "▌" = live-typing cursor while deltas keep arriving.
                            Text(
                                text = renderMarkdownLite(
                                    if (ask.streaming) "${ask.text}▌" else ask.text
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(
                                    if (context.isOffline()) {
                                        R.string.demo_point_and_ask_answer_source_offline
                                    } else {
                                        R.string.demo_point_and_ask_answer_source
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
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
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                // Planes are shown so the user can see where a tap will pin its answer —
                // but never during the capture window: the composited frame is what Nano
                // is asked about, and a grid baked over the room would be part of the
                // question. Same reasoning as `hideOverlaysForCapture` for the 2D chrome.
                planeRenderer = !hideOverlaysForCapture,
                viewNodeWindowManager = viewNodeManager,
                onSessionUpdated = { _, frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { e, node ->
                        // `busy` is hoisted to the demo scope so the status pill above
                        // shares it — see its declaration for why askState is not enough.
                        if (node == null && engineStatus == AskEngineStatus.Ready && !busy) {
                            ping = Offset(e.x, e.y) to System.nanoTime()
                            // P2 — pin the answer where the user pointed. The hit-test runs
                            // on the latest frame, the same one the capture is about to
                            // composite, so the pinned pose matches what the model sees.
                            // Planes accept only inside their polygon; feature points cover
                            // the surfaces ARCore has not meshed into a plane yet. No hit
                            // (sky, untracked wall) → screen-space card, unchanged.
                            //
                            // HORIZONTAL_UPWARD_FACING only, deliberately. The card below
                            // adds no rotation because a horizontal hit pose's Z+ already
                            // points back toward the device. A VERTICAL plane's Y+ is the
                            // wall normal and its Z+ lies IN the wall, so reusing that pose
                            // would pin the card edge-on — an unreadable sliver — while the
                            // demo reported success. Wall taps therefore fall through to the
                            // screen-space card, which is already the no-hit behaviour.
                            // Orienting walls properly needs wallFacingRotation() and a
                            // device to verify on (#2754 blocks that on the emulator).
                            pendingPanel = latestFrame
                                ?.takeIf { isTracking }
                                ?.hitTest(e)
                                ?.firstOrNull { result ->
                                    val trackable = result.trackable
                                    trackable.trackingState == TrackingState.TRACKING &&
                                        (trackable is Point ||
                                            (trackable is Plane &&
                                                trackable.type ==
                                                Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                                trackable.isPoseInPolygon(result.hitPose)))
                                }
                                // ARCore throws ResourceExhaustedException once too many
                                // anchors exist — a pinned answer is a nice-to-have, so
                                // degrade to the screen-space card instead of crashing.
                                ?.let { hit -> runCatching { hit.createAnchor() }.getOrNull() }
                                ?.let { anchor ->
                                    AnswerPanel(
                                        id = nextPanelId++,
                                        anchor = anchor,
                                        question = question,
                                    ).also { panels.add(it) }
                                }
                            // Each panel costs an ARCore anchor plus a ViewNode (Filament
                            // stream + texture + an off-screen ComposeView), and both grow
                            // per-frame cost — retire the oldest past the cap rather than
                            // letting a long session accumulate without bound.
                            while (panels.size > MAX_PANELS) {
                                panels.removeAt(0).also { runCatching { it.anchor.detach() } }
                            }
                            askState = AskState.Capturing
                        }
                    },
                    // Long-press drops the showcase prop on the hit surface — the object
                    // Nano will later describe even though it only exists in AR.
                    onLongPress = { e, node ->
                        if (node == null && isTracking) {
                            latestFrame?.hitTest(e)?.firstOrNull { result ->
                                val trackable = result.trackable
                                trackable is Plane &&
                                    trackable.trackingState == TrackingState.TRACKING &&
                                    trackable.isPoseInPolygon(result.hitPose)
                            }?.let { hit ->
                                placedProps.add(PlacedProp(nextPropId++, hit.createAnchor()))
                            }
                        }
                    },
                ),
            ) {
                placedProps.forEach { placed ->
                    key(placed.id) {
                        AnchorNode(
                            anchor = placed.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
                        ) {
                            val instance = rememberModelInstance(
                                modelLoader,
                                fileLocation = PROP_ASSET,
                            )
                            val textured = rememberTexturesSettled(ready = instance != null)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = PROP_SCALE_UNITS,
                                    rotation = DemoMath.placementRotationFor(PROP_ASSET),
                                    isVisible = textured,
                                    isEditable = true,
                                )
                            }
                        }
                    }
                }

                // P2 — one world-anchored answer card per tap that hit a tracked surface.
                // The AnchorNode follows ARCore's refined pose, so the card holds its place
                // in the room while the camera moves around it; the ViewNode renders the
                // same streamed answer as the screen-space card.
                panels.forEach { panel ->
                    key(panel.id) {
                        AnchorNode(
                            anchor = panel.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
                        ) {
                            ViewNode(
                                windowManager = viewNodeManager,
                                unlit = true,
                                position = Position(y = PANEL_LIFT_METERS),
                                // No rotation: the anchor's own frame already faces the
                                // device (ARCore hit pose, Z+ toward the user) and a
                                // ViewNode's quad faces its +Z.
                                scale = Scale(PANEL_SCALE),
                                // Unlike the props, the cards are UI, not scenery: keeping
                                // them out of the capture stops the model from reading its
                                // own earlier answers back as part of the next question.
                                isVisible = !hideOverlaysForCapture,
                            ) {
                                AnchoredAnswerCard(
                                    question = panel.question,
                                    text = panel.text,
                                    streaming = panel.streaming,
                                )
                            }
                        }
                    }
                }
            }

            // Tap ping — expanding ring, 500 ms, then gone.
            ping?.let { (center, stamp) ->
                key(stamp) {
                    val progress = remember { Animatable(0f) }
                    LaunchedEffect(stamp) {
                        progress.animateTo(1f, animationSpec = tween(PING_MS))
                        ping = null
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val alpha = (1f - progress.value).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color(0xFF57D9E2).copy(alpha = alpha),
                            radius = 24.dp.toPx() + progress.value * 56.dp.toPx(),
                            center = center,
                            style = Stroke(width = 3.dp.toPx() * (1f - progress.value * 0.6f)),
                        )
                        drawCircle(
                            color = Color(0xFF57D9E2).copy(alpha = alpha * 0.9f),
                            radius = 5.dp.toPx(),
                            center = center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal, dependency-free Markdown renderer for the streamed answer text.
 *
 * Supports **bold** (`**..**`) and *italic* (`*..*` or `_.._`) only, in a single
 * left-to-right pass. It is **streaming-safe**: a marker that has not been closed
 * yet — the tail of a still-arriving delta, or the live-typing "▌" cursor — is
 * rendered as a literal character instead of eating the rest of the line. No third
 * -party dependency: `androidx.compose.ui.text` is already on the classpath.
 */
private fun renderMarkdownLite(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            // Bold: **..** — checked before single '*' so '**' never parses as italic.
            text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, close))
                    }
                    i = close + 2
                } else {
                    // Unclosed marker (still streaming) → render literally.
                    append("**")
                    i += 2
                }
            }
            // Italic: *..*
            text[i] == '*' -> {
                val close = text.indexOf('*', i + 1)
                if (close >= 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append('*')
                    i += 1
                }
            }
            // Italic: _.._
            text[i] == '_' -> {
                val close = text.indexOf('_', i + 1)
                if (close >= 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append('_')
                    i += 1
                }
            }
            else -> {
                append(text[i])
                i += 1
            }
        }
    }
}

/** Long-press showcase prop and its placement size. */
private const val PROP_ASSET = "models/shiba.glb"
private const val PROP_SCALE_UNITS = 0.45f

/** Frames-settle delay between hiding overlays and the PixelCopy. */
private const val CAPTURE_OVERLAY_SETTLE_MS = 120L

/** A finished answer stays on screen this long, then clears the viewfinder. */
private const val ANSWER_AUTO_DISMISS_MS = 12_000L

/** Tap-ping animation duration. */
private const val PING_MS = 500

/**
 * Shorter capture window under [DemoSettings.qaMode], after which a synthetic frame
 * stands in for the capture — emulators without a working camera stream / dataset
 * playback (#1645) would otherwise never complete the flow.
 */
private const val QA_CAPTURE_TIMEOUT_MS = 5_000L

/** An anchored answer card floats this high above its hit pose (meters). */
private const val PANEL_LIFT_METERS = 0.12f

/**
 * World scale of an anchored card's `ViewNode`. The node renders at `ViewNode.pxPerUnits`
 * (250 px/m), so a ~650 px card would span ~2.6 m at scale 1; 0.15 brings it to ~0.4 m —
 * readable at arm's length without walling off the room.
 */
private const val PANEL_SCALE = 0.15f

/** How many answers stay pinned before the oldest is retired. */
private const val MAX_PANELS = 8

/**
 * Routes one ask round's results: into [panel] when the tap pinned one (P2), otherwise to
 * [fallback], the screen-space card. With a panel, [fallback] is also driven back to
 * [AskState.Idle] so the bottom sheet hands the round over instead of showing the same
 * answer twice.
 */
private fun answerSink(
    panel: AnswerPanel?,
    failedText: String,
    fallback: (AskState) -> Unit,
): (AskState) -> Unit = if (panel == null) {
    fallback
} else {
    { state ->
        panel.accept(state, failedText)
        fallback(AskState.Idle)
    }
}

/** Unwraps the [Activity] hosting this composition (needed for window PixelCopy). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** True when the device currently has no active network (airplane mode, no Wi-Fi/data). */
private fun Context.isOffline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return cm?.activeNetwork == null
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
 * The in-scene answer card rendered by an anchored `ViewNode` (P2). Mirrors the states of
 * the screen-space card — spinner until the first delta, live text with a typing cursor
 * while streaming, question label above — at a width that stays legible once the node is
 * scaled down to [PANEL_SCALE].
 */
@Composable
private fun AnchoredAnswerCard(question: String, text: String, streaming: Boolean) {
    // A ViewNode composes in its own off-screen ComposeView, which inherits none of this
    // demo's CompositionLocals — without re-applying the theme here, `MaterialTheme` would
    // resolve to M3 defaults and the in-scene card would not match the card it mirrors.
    SceneViewDemoTheme {
        M3Surface(
            // Fixed height as well as width, and NOT for looks: every panel shares one
            // ViewNode WindowManager, whose single wrap-content FrameLayout sizes itself
            // to its LARGEST child and then re-measures every match-parent sibling at
            // that size. A taller card therefore resizes every other card's quad, and
            // because Plane.DEFAULT_CENTER centres the quad on the node origin while the
            // content draws top-left, the shorter cards visibly drift and keep moving
            // while a sibling streams. Identical measurements keep the shared window
            // constant, so each card stays where it was pinned (#2918).
            modifier = Modifier.width(ANCHORED_CARD_WIDTH).height(ANCHORED_CARD_HEIGHT),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            if (text.isEmpty() && streaming) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.demo_point_and_ask_status_thinking),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                return@M3Surface
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // A long answer scrolls inside the fixed box instead of growing it.
                    // Nothing drives this scroll by touch — the rendered UI is a texture,
                    // not an interactive view — so it also clips gracefully.
                    text = renderMarkdownLite(if (streaming) "$text▌" else text),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/**
 * Layout size of an anchored card. A `ViewNode`'s window is `WRAP_CONTENT`, so its content is
 * measured `AT_MOST(display)` — `fillMaxWidth()` would resolve to the full display width and
 * put a metres-wide card in the room. Hence an explicit width: 320 dp at [PANEL_SCALE] lands
 * around 0.4 m.
 *
 * The height is explicit for a second, sharper reason: all panels share one window, which
 * sizes to its largest child. Without a fixed height the tallest answer would resize every
 * other card. See the comment in `AnchoredAnswerCard` (#2918).
 */
private val ANCHORED_CARD_WIDTH = 320.dp
private val ANCHORED_CARD_HEIGHT = 200.dp

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
