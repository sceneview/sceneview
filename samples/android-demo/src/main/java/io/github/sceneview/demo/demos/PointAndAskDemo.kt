package io.github.sceneview.demo.demos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
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
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags for the Point & Ask QA flows (Maestro / layout dumps). */
object PointAndAskTestTags {
    const val ANSWER_CARD = "point_and_ask_answer_card"
    const val QUESTION_FIELD = "point_and_ask_question_field"
    const val MODE_BAR = "point_and_ask_mode_bar"
}

/** A pickable interaction mode, surfaced as a chip in the on-screen bar. */
private sealed interface DemoMode {
    val label: String
}

/** Tap → anchored floating card streaming Gemini Nano's answer to [prompt]. */
private data class AskMode(override val label: String, val prompt: String) : DemoMode

/** Tap → drop the model chosen in Settings on the hit surface. */
private data object DropMode : DemoMode {
    override val label = "Drop 3D"
}

/** On-device voice-question lifecycle for the mic button. */
private sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    /** A recognised question — becomes the active prompt until a preset chip is tapped. */
    data class Question(val text: String) : VoiceState
}

/** Screen-space ask lifecycle — kept for the qaMode (emulator) path only. */
private sealed interface AskState {
    data object Idle : AskState
    data object Capturing : AskState
    data object Thinking : AskState
    data class Answered(val text: String, val streaming: Boolean = false) : AskState
    data object Failed : AskState
}

/**
 * One world-space annotation: an anchor at the tapped point carrying a floating
 * card. While [done] is false it shows an animated loader; on [done] it flips to
 * the final [answer]; a [failed] card auto-removes after a beat.
 */
private class SpaceLabel(val id: Int, val anchor: Anchor) {
    var answer by mutableStateOf("")
    var dots by mutableStateOf(0)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

/** A placed showcase model (Drop-3D mode or long-press). */
private data class PlacedProp(
    val id: Int,
    val anchor: Anchor,
    val asset: String,
    val scaleUnits: Float,
)

/** A bundled showcase model offered in the Settings "Choose model" picker. */
private data class ModelSpec(val asset: String, val name: String, val scaleUnits: Float)

/**
 * AR demo — Point & Ask, world-space edition (#2648 P2): pick a mode (or type your
 * own question, right on screen), then tap ANYTHING — real or virtual. Ask-modes
 * float a card at that point in space; a clean animated loader spins while **Gemini
 * Nano on-device** thinks, then the answer appears. Drop-3D drops the model chosen
 * in Settings. Everything stays pinned with parallax. Fully offline.
 *
 * Perf note: the question/mode state lives in [modeState] / [customState] holders
 * read ONLY inside gesture callbacks and the isolated [AskBar] — never in the AR
 * composable's body. Typing therefore recomposes only the small bar, not the heavy
 * ARSceneView tree, which killed the per-keystroke lag.
 *
 *  - The capture is the SceneView **SurfaceView** (camera + placed 3D), cropped
 *    around the tap — a window PixelCopy misses the camera layer and hands the model
 *    a black frame. [framePrompt] adds invisible framing so the placed dino/plant
 *    read as really present, in one short confident line.
 *  - Availability is gated honestly (AICore, Pixel 8+); no cloud fallback (#2648).
 *  - Under `DemoSettings.qaMode` the engine is canned and the flow falls back to the
 *    screen-space answer card, keeping the Maestro flow + testTags stable.
 */
@Composable
fun PointAndAskDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val viewNodeWindowManager = rememberViewNodeManager()
    val arPlaybackDataset = rememberArPlaybackDataset()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val askEngine = rememberAskEngine()
    var engineStatus by remember { mutableStateOf<AskEngineStatus?>(null) }
    LaunchedEffect(askEngine) { engineStatus = askEngine.status() }

    var askState by remember { mutableStateOf<AskState>(AskState.Idle) }
    var isTracking by remember { mutableStateOf(false) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var askBusy by remember { mutableStateOf(false) }

    val labels = remember { mutableStateListOf<SpaceLabel>() }
    val placedProps = remember { mutableStateListOf<PlacedProp>() }
    var nextId by remember { mutableStateOf(0) }

    // Read only in callbacks + AskBar — never in this body (no recompose churn).
    val modeState = remember { mutableStateOf<DemoMode>(ASK_MODES[0]) }
    val voiceState = remember { mutableStateOf<VoiceState>(VoiceState.Idle) }
    // Recognition language: default English (Google ASR is accent-tolerant); the mic's
    // EN/FR toggle flips to French so a French speaker gets perfect recognition — the
    // answer is forced back to English by [framePrompt] either way.
    val voiceLang = remember { mutableStateOf("en-US") }
    var selectedModel by remember { mutableStateOf(MODELS[0]) }

    // On-device voice input — the mic path (no keyboard over the AR view). The
    // recogniser runs in a system service, off the render thread, so it stays smooth.
    val startListening = rememberVoiceRecognizer(voiceState, voiceLang)
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() }
    val requestVoice: () -> Unit = {
        // A preset chip stops overriding once you speak; clear any prior answer intent.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    var ping by remember { mutableStateOf<Pair<Offset, Long>?>(null) }
    // Camera world position, refreshed each AR frame and read only inside the answer
    // cards' per-frame billboard callback (never in composition — no recompose churn).
    val cameraPos = remember { mutableStateOf(Position(0f)) }

    // qaMode fallback: synthetic capture → screen-space card (unchanged contract).
    LaunchedEffect(askState) {
        if (!DemoSettings.qaMode || askState != AskState.Capturing) return@LaunchedEffect
        delay(QA_CAPTURE_TIMEOUT_MS)
        if (askState != AskState.Capturing) return@LaunchedEffect
        askState = AskState.Thinking
        val prompt = (modeState.value as? AskMode)?.prompt ?: ASK_MODES[0].prompt
        val synthetic = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        scope.askAboutBitmap(synthetic, askEngine, prompt) { askState = it }
    }

    /** SurfaceView capture cropped around the tap, answered into [label]. */
    fun askAt(tap: Offset, prompt: String, label: SpaceLabel) {
        val surfaceView = context.findActivity()?.window?.decorView?.findSurfaceView()
        if (surfaceView == null || surfaceView.width == 0 || surfaceView.height == 0) {
            label.failed = true
            return
        }
        askBusy = true
        val full = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            full,
            { result ->
                if (result != PixelCopy.SUCCESS) {
                    full.recycle()
                    label.failed = true
                    askBusy = false
                    return@request
                }
                val crop = cropAround(full, tap)
                full.recycle()
                scope.launch {
                    val sb = StringBuilder()
                    try {
                        askEngine.askStream(crop, framePrompt(prompt)).collect { d -> sb.append(d) }
                        val text = shortAnswer(sb.toString())
                        if (text.isEmpty()) label.failed = true
                        else { label.answer = text; label.done = true }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        val text = shortAnswer(sb.toString())
                        if (text.isEmpty()) label.failed = true
                        else { label.answer = text; label.done = true }
                    } finally {
                        crop.recycle()
                        askBusy = false
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun dropPropAt(tap: Offset) {
        if (!isTracking) return
        latestFrame?.hitTest(tap.x, tap.y)?.firstOrNull { r ->
            val t = r.trackable
            t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(r.hitPose)
        }?.let { hit ->
            placedProps.add(
                PlacedProp(nextId++, hit.createAnchor(), selectedModel.asset, selectedModel.scaleUnits)
            )
            ping = tap to System.nanoTime()
        }
    }

    // Animated dot loader for every card still thinking.
    labels.filter { !it.done && !it.failed }.forEach { loading ->
        LaunchedEffect(loading.id) {
            while (!loading.done && !loading.failed) {
                loading.dots = (loading.dots + 1) % (LOADER.size)
                delay(LOADER_TICK_MS)
            }
        }
    }
    labels.filter { it.failed }.forEach { failedLabel ->
        LaunchedEffect(failedLabel.id) {
            delay(FAILED_LABEL_LINGER_MS)
            failedLabel.anchor.detach()
            labels.remove(failedLabel)
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_point_and_ask_title),
        onBack = onBack,
        onReset = {
            askState = AskState.Idle
            modeState.value = ASK_MODES[0]
            labels.forEach { it.anchor.detach() }
            labels.clear()
            placedProps.forEach { it.anchor.detach() }
            placedProps.clear()
        },
        peekHeader = "Model · ${selectedModel.name}",
        controls = {
            Text("Model to drop", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MODELS.forEach { model ->
                    FilterChip(
                        selected = selectedModel == model,
                        onClick = { selectedModel = model },
                        label = { Text(model.name) },
                    )
                }
            }
            if (DemoSettings.qaMode) {
                Spacer(Modifier.height(8.dp))
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
                viewNodeWindowManager = viewNodeWindowManager,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                onSessionUpdated = { _, frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    frame.camera.pose.let { cameraPos.value = Position(it.tx(), it.ty(), it.tz()) }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { e, node ->
                        if (engineStatus != AskEngineStatus.Ready || askBusy) {
                            return@rememberOnGestureListener
                        }
                        if (DemoSettings.qaMode) {
                            askState = AskState.Capturing
                            return@rememberOnGestureListener
                        }
                        when (val mode = modeState.value) {
                            is DropMode -> if (node == null) dropPropAt(Offset(e.x, e.y))
                            is AskMode -> {
                                val hit = latestFrame?.hitTest(e)?.firstOrNull { r ->
                                    val t = r.trackable
                                    r.distance < MAX_HIT_DISTANCE_M && (
                                        (t is Plane && t.isPoseInPolygon(r.hitPose)) ||
                                            t is DepthPoint || t is Point
                                        )
                                } ?: return@rememberOnGestureListener
                                // A spoken question wins over the selected preset chip.
                                val prompt = (voiceState.value as? VoiceState.Question)?.text
                                    ?: mode.prompt
                                val label = SpaceLabel(nextId++, hit.createAnchor())
                                labels.add(label)
                                ping = Offset(e.x, e.y) to System.nanoTime()
                                askAt(Offset(e.x, e.y), prompt, label)
                            }
                        }
                    },
                    onLongPress = { e, node -> if (node == null) dropPropAt(Offset(e.x, e.y)) },
                ),
            ) {
                placedProps.forEach { placed ->
                    key(placed.id) {
                        AnchorNode(
                            anchor = placed.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
                        ) {
                            val instance = rememberModelInstance(modelLoader, fileLocation = placed.asset)
                            val textured = rememberTexturesSettled(ready = instance != null)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = placed.scaleUnits,
                                    rotation = DemoMath.placementRotationFor(placed.asset),
                                    isVisible = textured,
                                    // NOT editable: an editable node swallows the tap for
                                    // move/rotate, blocking tap-to-ask ON a placed model.
                                    // Non-editable → the tap falls through and we ask about it.
                                    isEditable = false,
                                )
                            }
                        }
                    }
                }

                val offline = context.isOffline()
                labels.forEach { label ->
                    key(label.id) {
                        AnchorNode(
                            anchor = label.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
                        ) {
                            ViewNode(
                                windowManager = viewNodeWindowManager,
                                unlit = true,
                                position = Position(y = LABEL_LIFT_M),
                                scale = Float3(LABEL_SCALE),
                                // Billboard: face the camera every frame so the card stays
                                // upright and readable instead of lying flat on the plane.
                                apply = {
                                    onFrame = { _ ->
                                        val dir = worldPosition - cameraPos.value
                                        if (dir.x * dir.x + dir.y * dir.y + dir.z * dir.z > 1e-6f) {
                                            lookTowards(lookDirection = dir)
                                        }
                                    }
                                },
                            ) {
                                AnswerCard(label = label, offline = offline)
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
                            color = ACCENT.copy(alpha = alpha),
                            radius = 24.dp.toPx() + progress.value * 56.dp.toPx(),
                            center = center,
                            style = Stroke(width = 3.dp.toPx() * (1f - progress.value * 0.6f)),
                        )
                        drawCircle(ACCENT.copy(alpha = alpha * 0.9f), 5.dp.toPx(), center)
                    }
                }
            }

            // Top-center: instruction while empty, offline proof once used.
            val hasContent = labels.isNotEmpty() || placedProps.isNotEmpty()
            AnimatedVisibility(
                visible = engineStatus != null && !hasContent,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                StatusPill(
                    text = if (engineStatus == AskEngineStatus.Ready)
                        "Pick a mode below, then tap anything"
                    else stringResource(R.string.demo_point_and_ask_status_limited),
                    color = Color.White,
                )
            }
            AnimatedVisibility(
                visible = hasContent,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                StatusPill(
                    text = "Gemini Nano · on-device" + if (context.isOffline()) " · no network" else "",
                    color = ACCENT_LIGHT, bold = true,
                )
            }

            // "Listening…" overlay while the mic is open — its own composable so a voice
            // state change recomposes only this, not the AR tree.
            ListeningOverlay(voiceState)

            // The ask bar — mic (voice questions) + one-tap preset chips, NO keyboard (a
            // text field over the live AR SurfaceView is inherently janky). Isolated so a
            // chip/mic tap never recomposes the scene.
            if (engineStatus == AskEngineStatus.Ready) {
                AskBar(
                    modeState = modeState,
                    voiceState = voiceState,
                    voiceLang = voiceLang,
                    onMic = requestVoice,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 76.dp, bottom = 20.dp),
                )
            }

            // Availability states + the qaMode screen-space card.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 96.dp),
            ) {
                when (val status = engineStatus) {
                    null -> Unit
                    AskEngineStatus.Unavailable -> BottomCard {
                        Text(stringResource(R.string.demo_point_and_ask_unavailable_title),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.demo_point_and_ask_unavailable_body),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    AskEngineStatus.Downloadable -> BottomCard {
                        Text(stringResource(R.string.demo_point_and_ask_download_title),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch { askEngine.download().collect { engineStatus = it } }
                        }) { Text(stringResource(R.string.demo_point_and_ask_download_cta)) }
                    }
                    is AskEngineStatus.Downloading -> BottomCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                text = status.totalBytesDownloaded?.let { b ->
                                    stringResource(R.string.demo_point_and_ask_downloading_progress, b / (1024 * 1024))
                                } ?: stringResource(R.string.demo_point_and_ask_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    AskEngineStatus.Ready -> if (DemoSettings.qaMode) {
                        when (val ask = askState) {
                            AskState.Idle -> Unit
                            AskState.Capturing, AskState.Thinking -> BottomCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    Text("✦ Gemini Nano is thinking…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                            is AskState.Answered -> BottomCard(testTag = PointAndAskTestTags.ANSWER_CARD) {
                                Text(if (ask.streaming) "${ask.text}▌" else ask.text,
                                    style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.demo_point_and_ask_answer_source),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            AskState.Failed -> BottomCard {
                                Text(stringResource(R.string.demo_point_and_ask_error),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else Unit
                }
            }
        }
    }
}

/**
 * Isolated bottom bar: a mic button (on-device voice questions), a pill showing the
 * spoken question, and one-tap preset question chips. No keyboard — a text field over
 * a live AR SurfaceView is unusable. Owns the only reads of [modeState] / [voiceState],
 * so nothing here recomposes the ARSceneView tree above.
 */
@Composable
private fun AskBar(
    modeState: MutableState<DemoMode>,
    voiceState: MutableState<VoiceState>,
    voiceLang: MutableState<String>,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = modeState.value
    val voice = voiceState.value
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .testTag(PointAndAskTestTags.MODE_BAR),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mic button — tap to ask a question out loud (on-device, offline).
        M3Surface(
            onClick = onMic,
            shape = CircleShape,
            color = if (voice is VoiceState.Listening) ACCENT else GLASS,
            contentColor = if (voice is VoiceState.Listening) Color(0xFF0B1620) else Color.White,
            border = if (voice is VoiceState.Listening) null
            else BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Ask by voice",
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        // EN/FR toggle — switch to French if English trips on your accent.
        ModeChip(
            text = if (voiceLang.value == "fr-FR") "FR" else "EN",
            selected = false,
            onClick = { voiceLang.value = if (voiceLang.value == "fr-FR") "en-US" else "fr-FR" },
        )
        // When a question was spoken, show it as the active pill (preset chips defer).
        if (voice is VoiceState.Question) {
            ModeChip(
                text = "“" + voice.text.take(28) + (if (voice.text.length > 28) "…" else "") + "”",
                selected = true,
                onClick = onMic,
            )
        }
        ASK_MODES.forEach { m ->
            ModeChip(
                text = m.label,
                selected = mode == m && voice !is VoiceState.Question,
                onClick = { modeState.value = m; voiceState.value = VoiceState.Idle },
            )
        }
        ModeChip(
            text = DropMode.label,
            selected = mode == DropMode && voice !is VoiceState.Question,
            onClick = { modeState.value = DropMode; voiceState.value = VoiceState.Idle },
        )
    }
}

/**
 * Wires an on-device [SpeechRecognizer] to [state]. Returns a `start()` you call once
 * the RECORD_AUDIO permission is granted. On-device (API 31+, available on Pixel) keeps
 * it fully offline; older devices fall back to `EXTRA_PREFER_OFFLINE`. All callbacks land
 * on the main thread — cheap state flips only, so they never disturb AR rendering.
 */
@Composable
private fun rememberVoiceRecognizer(
    state: MutableState<VoiceState>,
    langState: MutableState<String>,
): () -> Unit {
    val context = LocalContext.current
    val recognizer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { state.value = VoiceState.Listening }
            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                state.value = if (text.isNullOrBlank()) VoiceState.Idle else VoiceState.Question(text)
            }
            override fun onError(error: Int) {
                if (state.value is VoiceState.Listening) state.value = VoiceState.Idle
            }
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { runCatching { recognizer.destroy() } }
    }
    return remember(recognizer) {
        {
            state.value = VoiceState.Listening
            val lang = langState.value
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            runCatching { recognizer.startListening(intent) }
                .onFailure { state.value = VoiceState.Idle }
        }
    }
}

/** Brand cyan + a frosted-glass neutral for the overlay chrome. */
private val ACCENT = Color(0xFF5AC8D8)
private val ACCENT_LIGHT = Color(0xFF9FE3EC)
private val GLASS = Color(0xFF10161D).copy(alpha = 0.72f)

/** Loader frames for the "thinking" card — a spinning quarter-circle. */
private val LOADER = listOf("◐", "◓", "◑", "◒")
private const val LOADER_TICK_MS = 130L

/**
 * Ready-made questions surfaced as one-tap chips — the label is the chip, the prompt
 * the full question. Covers the general cases plus the two film scenarios ("Real?" for
 * the plant-next-to-plant reveal, "Biggest?" for the T-Rex in the room). [framePrompt]
 * enforces one short confident line.
 */
private val ASK_MODES = listOf(
    AskMode("What is it?", "What is this?"),
    AskMode("Fun fact", "Tell me one surprising fact about what I'm pointing at."),
    AskMode("Translate", "Translate any text you see. If there is none, name what's in front of me."),
    AskMode("Real?", "Is this a real object or a 3D model placed in the room? A few words."),
    AskMode("Biggest?", "What is the biggest thing in this room?"),
    AskMode("Fits here?", "Does this fit next to what's already in the room?"),
)

private val MODES: List<DemoMode> = ASK_MODES + DropMode

private val MODELS = listOf(
    ModelSpec("models/animated_trex.glb", "T-Rex", 1.6f),
    ModelSpec("models/monstera_plant.glb", "Monstera", 1.1f),
    ModelSpec("models/khronos_damaged_helmet.glb", "Helmet", 0.4f),
    ModelSpec("models/khronos_fox.glb", "Fox", 0.5f),
    ModelSpec("models/shiba.glb", "Shiba", 0.5f),
    ModelSpec("models/khronos_lantern.glb", "Lantern", 0.4f),
    ModelSpec("models/khronos_toy_car.glb", "Toy car", 0.3f),
    ModelSpec("models/threejs_soldier.glb", "Soldier", 1.0f),
)

private const val MAX_HIT_DISTANCE_M = 5f
private const val LABEL_LIFT_M = 0.18f
private const val LABEL_SCALE = 0.14f
private const val FAILED_LABEL_LINGER_MS = 2_500L
private const val PING_MS = 500
private const val QA_CAPTURE_TIMEOUT_MS = 5_000L
private const val MAX_ANSWER_CHARS = 150
private const val CROP_FRACTION = 0.45f

/**
 * Invisible framing wrapped around every question before it reaches Gemini Nano:
 * tells the model the scene is a real room that may contain placed 3D objects (so
 * it treats the virtual plant/dino as present) and forces one short confident line.
 */
private fun framePrompt(question: String): String =
    "You are looking at a live photo of a real room. It may contain 3D objects " +
        "placed into it — treat everything you see as genuinely present. " +
        "The question may be in French; understand it and always answer in ENGLISH, " +
        "in one short line (under 18 words), plainly and confidently. " +
        "No disclaimers, no \"I think\", no markdown.\n\nQuestion: $question"

/** Clamp Nano's reply to a single short line so the 3D card stays small. */
private fun shortAnswer(raw: String): String {
    val line = raw.trim().substringBefore('\n').trim()
    return if (line.length > MAX_ANSWER_CHARS) line.take(MAX_ANSWER_CHARS - 1).trimEnd() + "…" else line
}

/** Square crop centred on the tap — the model answers about what was aimed at. */
private fun cropAround(source: Bitmap, tap: Offset): Bitmap {
    val side = (minOf(source.width, source.height) * CROP_FRACTION).toInt()
    val x = (tap.x.toInt() - side / 2).coerceIn(0, (source.width - side).coerceAtLeast(0))
    val y = (tap.y.toInt() - side / 2).coerceIn(0, (source.height - side).coerceAtLeast(0))
    return Bitmap.createBitmap(source, x, y, side, side)
}

/** Unwraps the [Activity] hosting this composition (needed to reach the window). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** First [SurfaceView] in the hierarchy — SceneView's Filament surface (camera + 3D). */
private fun View.findSurfaceView(): SurfaceView? = when (this) {
    is SurfaceView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSurfaceView() }
    else -> null
}

/** True when the device currently has no active network (airplane mode, no Wi-Fi/data). */
private fun Context.isOffline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return cm?.activeNetwork == null
}

/** The floating answer card, rendered in 3D by a [ViewNode]. */
@Composable
private fun AnswerCard(label: SpaceLabel, offline: Boolean) {
    M3Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F1722).copy(alpha = 0.95f),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        modifier = Modifier.width(208.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            when {
                label.failed -> Text("Couldn't read that — tap again",
                    style = MaterialTheme.typography.bodySmall)
                !label.done -> Text(
                    text = LOADER.getOrElse(label.dots) { LOADER.first() } + "  Gemini Nano is thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ACCENT_LIGHT, fontWeight = FontWeight.SemiBold,
                )
                else -> {
                    Text(label.answer, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "✦ Gemini Nano · on-device" + if (offline) " · no network" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = ACCENT, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** A refined overlay chip — frosted-glass ghost, white fill when active. No emoji. */
@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    M3Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White else GLASS,
        contentColor = if (selected) Color(0xFF0B1620) else Color.White.copy(alpha = 0.92f),
        border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/** Isolated "Listening…" pill shown at the top while the mic is open. */
@Composable
private fun BoxScope.ListeningOverlay(voiceState: MutableState<VoiceState>) {
    AnimatedVisibility(
        visible = voiceState.value is VoiceState.Listening,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
    ) {
        StatusPill("Listening… ask your question out loud", ACCENT_LIGHT, bold = true)
    }
}

/** Top-center status/badge pill. */
@Composable
private fun StatusPill(text: String, color: Color, bold: Boolean = false) {
    M3Surface(
        modifier = Modifier.padding(top = 8.dp),
        color = GLASS,
        contentColor = color,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** qaMode screen-space round-trip (canned engine). Takes ownership of [bitmap]. */
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

/** Shared bottom-overlay card chrome (availability states + qaMode answer). */
@Composable
private fun BottomCard(testTag: String? = null, content: @Composable () -> Unit) {
    M3Surface(
        modifier = Modifier.fillMaxWidth().let { if (testTag != null) it.testTag(testTag) else it },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}
