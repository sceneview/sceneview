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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
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
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * P1+P3 of [#2648](https://github.com/sceneview/sceneview/issues/2648), plus the
 * film-mode polish pass: composited capture, long-press placement, tap ping, quieter
 * overlays, offline badge, auto-dismissing answer card.
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

    // Composited capture (film mode): PixelCopy on the window sees camera + virtual
    // props exactly as the user does. qaMode keeps the synthetic-frame fallback so the
    // flow stays deterministic on emulators (#1645).
    LaunchedEffect(askState) {
        if (askState != AskState.Capturing) return@LaunchedEffect
        if (DemoSettings.qaMode) {
            delay(QA_CAPTURE_TIMEOUT_MS)
            if (askState != AskState.Capturing) return@LaunchedEffect
            askState = AskState.Thinking
            val synthetic = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            scope.askAboutBitmap(synthetic, askEngine, question) { askState = it }
            return@LaunchedEffect
        }
        val activity = context.findActivity()
        val decor = activity?.window?.decorView
        if (decor == null || decor.width == 0 || decor.height == 0) {
            askState = AskState.Failed
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
                    scope.askAboutBitmap(bitmap, askEngine, question) { askState = it }
                } else {
                    bitmap.recycle()
                    askState = AskState.Failed
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
            askState = AskState.Idle
            placedProps.forEach { it.anchor.detach() }
            placedProps.clear()
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
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                // The viewfinder stays clean — placements hit-test invisible planes.
                planeRenderer = false,
                onSessionUpdated = { _, frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { e, node ->
                        val busy = askState == AskState.Capturing ||
                            askState == AskState.Thinking ||
                            (askState as? AskState.Answered)?.streaming == true
                        if (node == null && engineStatus == AskEngineStatus.Ready && !busy) {
                            ping = Offset(e.x, e.y) to System.nanoTime()
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

            // Top-center status pill — one clear instruction, and it gets out of the way:
            // hidden while thinking/answering, and hidden from the AI's captured frame.
            AnimatedVisibility(
                visible = !hideOverlaysForCapture &&
                    askState == AskState.Idle &&
                    engineStatus != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                M3Surface(
                    modifier = Modifier.padding(top = 8.dp),
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

            // Bottom overlay — exactly one of: unavailable banner, download CTA/progress,
            // thinking indicator, answer card, transient failure.
            if (!hideOverlaysForCapture) Box(
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
                                text = if (ask.streaming) "${ask.text}▌" else ask.text,
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
