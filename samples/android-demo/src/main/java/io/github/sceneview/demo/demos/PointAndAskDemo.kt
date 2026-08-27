package io.github.sceneview.demo.demos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.PixelCopy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ai.ASK_FAILURE_ESCALATION_THRESHOLD
import io.github.sceneview.demo.ai.AskEngine
import io.github.sceneview.demo.ai.AskEngineStatus
import io.github.sceneview.demo.ai.AskFailure
import io.github.sceneview.demo.ai.askCaptureRegion
import io.github.sceneview.demo.ai.rememberAskEngine
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.putVoiceSilenceExtras
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.demo.feedback.hasTransparentHole
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
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
    const val PROP_PICKER = "point_and_ask_prop_picker"
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

    /**
     * Inference or capture failed. [failure] says which cause, so the card can name it and
     * a terminal one can retire the "tap to try again" invitation entirely (#3343).
     */
    data class Failed(val failure: AskFailure) : AskState
}

/**
 * One bundled showcase model offered by Drop-3D mode's picker (#3083). All three ship in
 * the APK already (used elsewhere in the demo app), so adding the picker costs zero new
 * assets — [scaleUnits] matches the value already tuned for that asset in `ArViewTab`.
 */
private data class PropSpec(val asset: String, val label: String, val scaleUnits: Float)

/** Drop-3D mode's picker options, in display order. Shiba stays first — the prior default. */
private val DROP_PROPS = listOf(
    PropSpec(asset = "models/shiba.glb", label = "Shiba", scaleUnits = 0.45f),
    PropSpec(asset = "models/khronos_fox.glb", label = "Fox", scaleUnits = 0.3f),
    PropSpec(asset = "models/khronos_toy_car.glb", label = "Toy Car", scaleUnits = 0.3f),
)

/**
 * One long-press placement: a real-world anchor carrying whichever [PropSpec] was selected
 * in the Drop-3D picker at placement time (#3083). Frozen at placement so a later change of
 * the picker never rewrites a prop already dropped in the room.
 */
private data class PlacedProp(val id: Int, val anchor: Anchor, val prop: PropSpec)

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
    fun accept(state: AskState, failedText: (AskFailure) -> String) {
        when (state) {
            is AskState.Answered -> {
                text = state.text
                streaming = state.streaming
            }
            is AskState.Failed -> {
                if (text.isBlank()) text = failedText(state.failure)
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
 * composited window (PixelCopy), the on-device model *sees the augmented scene* — tap the
 * shiba you just placed and Nano describes a dog that only exists in AR. That is the
 * demo's whole point, which is why a tap on a node is never swallowed (#3187).
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
 * the camera orbits it. Panels accumulate — one per successful tap — until Reset. The
 * screen-space card shows every round regardless (thinking, answer, failure) — it is the
 * surface the user can always see, the anchored card is the one that stays in the room
 * (#3188).
 *
 * Two more pieces of #2648 landed later, re-implemented against this file's current shape
 * rather than reapplied from the original branch — that branch (`claude/point-and-ask-voice`,
 * tip `bc3ed0170`) forked 200+ commits back with no reachable merge-base by the time it was
 * revisited (#3083):
 *  - **Voice input** — an optional mic button on the question field launches the system
 *    speech recognizer (`ACTION_RECOGNIZE_SPEECH`) and appends the dictated text, the same
 *    zero-permission pattern `BugReportSheet` already shipped for its own dictation shortcut
 *    (#3292) — chosen over the original branch's raw `SpeechRecognizer` + runtime
 *    `RECORD_AUDIO` grant for less permission friction and one fewer failure mode to QA.
 *  - **Drop-3D mode** — long-press now drops whichever [PropSpec] is selected in the picker
 *    (`DROP_PROPS`), not always the shiba. Re-scoped to the three GLBs already bundled in the
 *    APK (shiba, fox, toy car) instead of the original branch's two new ~7 MB binary assets
 *    (`animated_trex.glb`, `monstera_plant.glb`) — same "pick a model, drop it, ask about it"
 *    experience with zero new assets or `CREDITS.md` licensing entries to land.
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
    // Camera world position, refreshed every AR frame — read by each anchored answer
    // card's per-frame billboard (#3276). A plain holder, not Compose state: it is only
    // read inside an `onFrame` node callback, never inside a composable body, so there is
    // nothing here for Compose to observe.
    val cameraPosition = remember { floatArrayOf(0f, 0f, 0f) }

    // Long-press placements — each drops the picker's currently selected model on the hit
    // surface. Drop-3D mode (#3083): which model is a `controls`-sheet picker, not a fixed
    // constant — see `DROP_PROPS`.
    val placedProps = remember { mutableStateListOf<PlacedProp>() }
    var nextPropId by remember { mutableStateOf(0) }
    var selectedProp by remember { mutableStateOf(DROP_PROPS[0]) }

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

    // Free-form question (P3): blank = the default English prompt (best Nano quality),
    // which asks the model to describe what the tap pointed at. The field starts blank so
    // the placeholder shows that default instead of a canned question that has nothing to
    // do with what the user tapped (#3187). Saveable so a rotation mid-session keeps the
    // user's custom question.
    val defaultQuestion = stringResource(R.string.demo_point_and_ask_question)
    var questionText by rememberSaveable { mutableStateOf("") }
    val question = questionText.trim().ifBlank { defaultQuestion }
    // Resolved through the context — anchored panels route results from non-composable
    // callbacks, and the message now depends on which failure occurred (#3343).
    val failedText: (AskFailure) -> String = { context.getString(it.messageRes) }

    // How many rounds in a row have failed, and with what. Once a failure is terminal, or
    // the same non-terminal one repeats, the card stops saying "tap to try again" — the
    // exact loop reported in #3343 — and explains the situation instead.
    var consecutiveFailures by remember { mutableIntStateOf(0) }

    // Where the last tap landed, in window pixels. The capture is cropped around it so the
    // model is shown what the user pointed at rather than the whole floor-to-ceiling frame
    // (see `askCaptureRegion`). Null before the first tap and for QA's synthetic frame.
    var tapFocus by remember { mutableStateOf<Offset?>(null) }

    // Voice input (#3083): the question field's optional mic button. Same zero-permission
    // `ACTION_RECOGNIZE_SPEECH` intent `BugReportSheet` already ships for its own dictation
    // shortcut (#3292) — the system recognizer app does the listening, this demo only reads
    // back its result, so there is no RECORD_AUDIO grant to request or lose across rotation.
    val speechAvailable = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    val voicePrompt = stringResource(R.string.demo_point_and_ask_voice_prompt)
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (spoken != null) {
                questionText = spoken
            }
        }
    }

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
        // Counting failures here rather than in a `LaunchedEffect(askState)` is deliberate:
        // two identical failures in a row produce the SAME `AskState.Failed` value, so a
        // state-keyed effect would never re-run — and "the same error over and over" is
        // precisely the case #3343 is about.
        val onResult = answerSink(pendingPanel, failedText) { state ->
            when (state) {
                is AskState.Failed -> consecutiveFailures++
                is AskState.Answered -> consecutiveFailures = 0
                else -> Unit
            }
            askState = state
        }
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
            onResult(AskState.Failed(AskFailure.CaptureFailed))
            return@LaunchedEffect
        }
        hideOverlaysForCapture = true
        delay(CAPTURE_OVERLAY_SETTLE_MS)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        // If PixelCopy never calls back, nothing else would ever leave `Capturing`: the
        // overlays stay hidden, `busy` stays true and every later tap is dropped — a
        // permanent blank viewfinder (#3188). The round is failed after a timeout instead;
        // `roundLive` keeps a late callback from resurrecting it.
        var roundLive = true
        PixelCopy.request(
            activity.window,
            bitmap,
            { result ->
                if (!roundLive) {
                    bitmap.recycle()
                    return@request
                }
                roundLive = false
                hideOverlaysForCapture = false
                // `PixelCopy.SUCCESS` alone does not prove the frame is usable (#3276). The
                // same compositor quirk already tracked for the bug-report screenshot
                // (`hasTransparentHole`, #2654) — a read-back that reports SUCCESS while the
                // Filament `SurfaceView` layer (camera + placed AR objects) was left out of
                // the composite, an `alpha == 0` hole exactly where the augmented scene
                // should be — applies just as much here. Sending that hole to Gemini is
                // literally "the model sees nothing": no exception, no failure banner, just
                // an on-device answer about a blank/transparent image. Guard for it the same
                // way the feedback screenshot does, and log so a future report of this can be
                // correlated with logcat instead of re-diagnosed from scratch.
                if (result == PixelCopy.SUCCESS && !hasTransparentHole(bitmap)) {
                    askState = AskState.Thinking
                    // Crop around the tap and downscale before handing the frame to ML
                    // Kit (#3343). ML Kit only clamps the SHORT edge to 768 px, so the
                    // raw window capture reached Gemini Nano as a full-height strip —
                    // mostly floor and ceiling, and a vision-token bill the on-device
                    // budget has no room for. See `askCaptureRegion`.
                    val framed = frameForModel(bitmap, tapFocus)
                    askJob = scope.askAboutBitmap(framed, askEngine, question, onResult)
                } else {
                    if (result == PixelCopy.SUCCESS) {
                        Log.w(
                            ASK_LOG_TAG,
                            "Composited capture came back with a transparent hole where the " +
                                "AR viewport should be (PixelCopy reported SUCCESS) — refusing " +
                                "to send a blank frame to Gemini (#3276).",
                        )
                    } else {
                        Log.w(ASK_LOG_TAG, "PixelCopy failed with result $result (#3343).")
                    }
                    bitmap.recycle()
                    onResult(AskState.Failed(AskFailure.CaptureFailed))
                }
            },
            Handler(Looper.getMainLooper()),
        )
        delay(CAPTURE_TIMEOUT_MS)
        if (roundLive) {
            roundLive = false
            hideOverlaysForCapture = false
            Log.w(ASK_LOG_TAG, "PixelCopy never called back within the capture budget (#3188).")
            onResult(AskState.Failed(AskFailure.CaptureFailed))
        }
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
            // Reset clears the escalated failure card too — the user explicitly asked for
            // a clean slate, so the demo gives the device another honest chance (#3343).
            consecutiveFailures = 0
            placedProps.forEach { runCatching { it.anchor.detach() } }
            placedProps.clear()
            pendingPanel = null
            panels.forEach { runCatching { it.anchor.detach() } }
            panels.clear()
        },
        onResetSettings = {
            questionText = ""
            selectedProp = DROP_PROPS[0]
        },
        controls = {
            // Free-form question (P3) — blank falls back to the default prompt, which the
            // placeholder shows. The next tap asks THIS question about the composited frame.
            // Voice input (#3083): the trailing mic launches the system speech recognizer and
            // replaces the field with what it heard — hidden when the device has no recognizer
            // to hand the intent to, same guard `BugReportSheet` uses (#3292).
            OutlinedTextField(
                value = questionText,
                onValueChange = { questionText = it },
                label = { Text(stringResource(R.string.demo_point_and_ask_question_label)) },
                placeholder = { Text(defaultQuestion) },
                singleLine = true,
                trailingIcon = if (speechAvailable) {
                    {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                                    putVoiceSilenceExtras()
                                }
                                runCatching { speechLauncher.launch(intent) }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription =
                                    stringResource(R.string.demo_point_and_ask_voice_cd),
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PointAndAskTestTags.QUESTION_FIELD),
            )

            // Drop-3D mode (#3083): which bundled model the next long-press drops. All three
            // ship in the APK already (see `DROP_PROPS`), so switching is instant — no download,
            // no new asset.
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.demo_point_and_ask_prop_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag(PointAndAskTestTags.PROP_PICKER),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DROP_PROPS.forEach { prop ->
                    FilterChip(
                        selected = selectedProp == prop,
                        onClick = { selectedProp = prop },
                        label = { Text(prop.label) },
                    )
                }
            }

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
                    // Say so in words while the availability check runs: a blank bottom
                    // edge here was indistinguishable from a broken demo (#3188).
                    null -> BottomCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.demo_point_and_ask_status_checking),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }

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

                        // One card per cause, and — once retrying is demonstrably not
                        // going to help — an explanation instead of an invitation to keep
                        // tapping (#3343).
                        is AskState.Failed -> AskFailureCard(
                            failure = ask.failure,
                            escalated = ask.failure.isTerminal ||
                                consecutiveFailures >= ASK_FAILURE_ESCALATION_THRESHOLD,
                        )
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
                    // Keep the camera world position fresh so every anchored answer card can
                    // billboard toward the viewer (#3276) — same pattern as `ARMLObjectLabelDemo`'s
                    // `cameraPosition`. The pose translation is the camera eye position in world
                    // space, all a billboard needs to orient toward.
                    val camPose = frame.camera.pose.position
                    cameraPosition[0] = camPose.x
                    cameraPosition[1] = camPose.y
                    cameraPosition[2] = camPose.z
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { e, _ ->
                        // `busy` is hoisted to the demo scope so the status pill above
                        // shares it — see its declaration for why askState is not enough.
                        //
                        // The hit node is deliberately ignored: "tap anything" includes the
                        // prop you just dropped and an answer already pinned. A `node ==
                        // null` guard here swallowed every tap on the object the user most
                        // wanted described — no ping, no capture, no answer (#3187).
                        if (engineStatus == AskEngineStatus.Ready && !busy) {
                            ping = Offset(e.x, e.y) to System.nanoTime()
                            // Same point the capture is cropped around: the model is asked
                            // about what the finger landed on, not the whole room (#3343).
                            tapFocus = Offset(e.x, e.y)
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
                    // Long-press drops the Drop-3D picker's currently selected model (#3083)
                    // on the hit surface — the object Nano will later describe even though it
                    // only exists in AR.
                    onLongPress = { e, node ->
                        if (node == null && isTracking) {
                            latestFrame?.hitTest(e)?.firstOrNull { result ->
                                val trackable = result.trackable
                                trackable is Plane &&
                                    trackable.trackingState == TrackingState.TRACKING &&
                                    trackable.isPoseInPolygon(result.hitPose)
                            }?.let { hit ->
                                placedProps.add(
                                    PlacedProp(nextPropId++, hit.createAnchor(), selectedProp),
                                )
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
                                fileLocation = placed.prop.asset,
                            )
                            val textured = rememberTexturesSettled(ready = instance != null)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = placed.prop.scaleUnits,
                                    rotation = DemoMath.placementRotationFor(placed.prop.asset),
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
                                // Initial rotation/scale before the first `onFrame` tick
                                // below runs — that per-frame billboard immediately takes
                                // over both (#3276).
                                scale = Scale(PANEL_SCALE),
                                // Unlike the props, the cards are UI, not scenery: keeping
                                // them out of the capture stops the model from reading its
                                // own earlier answers back as part of the next question.
                                isVisible = !hideOverlaysForCapture,
                                apply = {
                                    // Billboard + distance-legible scale (#3276). The card
                                    // used to keep the fixed orientation of the tap that
                                    // pinned it — proving the anchor but going edge-on (and
                                    // effectively unreadable) the moment the user moved
                                    // around it. Every AR frame, rotate to face the live
                                    // camera position and rescale so the text stays legible
                                    // whether the user is standing close or across the room.
                                    // `lookTowards` is the same world-space-safe primitive
                                    // `BillboardNode` uses (it converts into this node's
                                    // *local* rotation relative to the anchor automatically —
                                    // see `Node.worldTransform`), so this inherits the fix for
                                    // the mirrored/edge-on billboard bug from #2478 instead of
                                    // re-deriving the rotation math from scratch.
                                    onFrame = { _ ->
                                        val pos = worldPosition
                                        val dx = pos.x - cameraPosition[0]
                                        val dy = pos.y - cameraPosition[1]
                                        val dz = pos.z - cameraPosition[2]
                                        val distanceSq = dx * dx + dy * dy + dz * dz
                                        // Guards the zero-vector AND any NaN component
                                        // (NaN comparisons are always false) — same
                                        // reasoning as `BillboardNode`.
                                        if (distanceSq > 1e-12f) {
                                            lookTowards(lookDirection = Position(dx, dy, dz))
                                            scale = Scale(
                                                clampedPanelScale(kotlin.math.sqrt(distanceSq))
                                            )
                                        }
                                    }
                                },
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

/** Frames-settle delay between hiding overlays and the PixelCopy. */
private const val CAPTURE_OVERLAY_SETTLE_MS = 120L

/** A PixelCopy that has not called back by then fails the round instead of wedging it. */
private const val CAPTURE_TIMEOUT_MS = 3_000L

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
 * World scale of an anchored card's `ViewNode`, AT [PANEL_REFERENCE_DISTANCE] — the node
 * renders at `ViewNode.pxPerUnits` (250 px/m), so a ~650 px card would span ~2.6 m at
 * scale 1; 0.15 brings it to ~0.4 m, readable at arm's length. [clampedPanelScale] scales
 * this up/down from the live camera distance so the card stays legible across a room
 * (#3276) — see its Kdoc.
 */
private const val PANEL_SCALE = 0.15f

/**
 * Distance (metres) at which [PANEL_SCALE] was tuned to look right — "arm's length" per
 * its own Kdoc. [clampedPanelScale] uses this as the 1:1 point of its scale ramp.
 */
private const val PANEL_REFERENCE_DISTANCE = 1.0f

/**
 * Below this distance the perspective-compensated scale would keep *shrinking* the card
 * as the user leans in — which is backwards for legibility — so the ramp bottoms out
 * here instead (#3276).
 */
private const val PANEL_MIN_READABLE_DISTANCE = 0.6f

/**
 * Beyond this distance the card is scaled as if it were still this close: past a few
 * metres, a card sized to stay pixel-legible would loom absurdly large in the room, and
 * the answer is better re-read by walking closer (#3276).
 */
private const val PANEL_MAX_READABLE_DISTANCE = 3.5f

/**
 * World scale for an anchored answer card at [distanceMeters] from the camera,
 * compensating for perspective so the text stays legible whether the user is standing
 * close or across the room (#3276) — a card sized only for [PANEL_REFERENCE_DISTANCE]
 * shrinks to unreadable pixels a few metres out, which is exactly what the bug report
 * described ("the text is not visible in AR").
 *
 * [distanceMeters] is clamped to [PANEL_MIN_READABLE_DISTANCE]..[PANEL_MAX_READABLE_DISTANCE]
 * first: below the near clamp the raw ramp would shrink the card as the user leans in
 * (backwards), and beyond the far clamp it would balloon the card to an unreasonable size
 * instead of just asking the user to walk closer.
 *
 * Pure function — no ARCore/Filament types — so it is unit-testable on the JVM
 * (`PanelScaleTest`).
 */
internal fun clampedPanelScale(distanceMeters: Float): Float {
    val clamped = distanceMeters.coerceIn(PANEL_MIN_READABLE_DISTANCE, PANEL_MAX_READABLE_DISTANCE)
    return PANEL_SCALE * (clamped / PANEL_REFERENCE_DISTANCE)
}

/** How many answers stay pinned before the oldest is retired. */
private const val MAX_PANELS = 8

/**
 * Routes one ask round's results: always to [screenCard] (the bottom card), and ALSO into
 * [panel] when the tap pinned one (P2).
 *
 * The screen card is never handed off. An earlier version drove it back to [AskState.Idle]
 * once a panel existed, so an anchored round drew zero screen chrome — no card, no pill, no
 * failure text — and when the in-scene card was off-screen, edge-on or its texture had not
 * come up, the answer was simply invisible (#3188). The bottom card is the guaranteed
 * surface; the anchored card is the bonus that stays in the room after the bottom card
 * auto-dismisses.
 */
private fun answerSink(
    panel: AnswerPanel?,
    failedText: (AskFailure) -> String,
    screenCard: (AskState) -> Unit,
): (AskState) -> Unit = if (panel == null) {
    screenCard
} else {
    { state ->
        panel.accept(state, failedText)
        screenCard(state)
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
 *
 * The throwable is classified rather than swallowed (#3343): the card names the actual
 * cause, and every failure is logged with its ML Kit error code so the next report of
 * "it only says it can't answer" arrives with the code attached instead of needing to be
 * re-diagnosed from scratch. A stream that completes with no text at all is [
 * AskFailure.EmptyAnswer] — a distinct outcome from a thrown inference error, and one the
 * user can act on (rephrase) rather than retry blindly.
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
        onResult(
            if (text.isBlank()) {
                Log.w(ASK_LOG_TAG, "Gemini Nano completed the stream with no text (#3343).")
                AskState.Failed(AskFailure.EmptyAnswer)
            } else {
                AskState.Answered(text)
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: a minified build missing the ML Kit classes raises a
        // NoClassDefFoundError, which is an Error — letting it escape would kill the
        // coroutine scope silently and leave the demo wedged in `Thinking` (cf. #3188).
        val failure = AskFailure.of(e)
        Log.w(ASK_LOG_TAG, "Gemini Nano inference failed — classified as $failure (#3343).", e)
        onResult(
            if (text.isBlank()) AskState.Failed(failure) else AskState.Answered(text)
        )
    } finally {
        bitmap.recycle()
    }
}

/** Logcat tag for every Point & Ask failure path — one grep away in a bug report. */
private const val ASK_LOG_TAG = "PointAndAskDemo"

/**
 * Crops [capture] to [askCaptureRegion] around [focus] and downscales it to the model's
 * budget, recycling the oversized original. Returns [capture] unchanged when it is already
 * within budget, so the caller's ownership contract (the returned bitmap is recycled once
 * the round ends) holds either way.
 *
 * Why this exists rather than trusting ML Kit's own resize: genai-prompt 1.0.0-beta4
 * rescales only when `min(width, height) > 768`, and only the SHORT edge — a 1080×2424
 * window capture arrives as 768×1723. See `ASK_IMAGE_MAX_EDGE` (#3343).
 */
private fun frameForModel(capture: Bitmap, focus: Offset?): Bitmap {
    val region = askCaptureRegion(
        sourceWidth = capture.width,
        sourceHeight = capture.height,
        focusX = focus?.x,
        focusY = focus?.y,
    )
    val unchanged = region.x == 0 && region.y == 0 &&
        region.width == capture.width && region.height == capture.height &&
        region.scaledWidth == capture.width && region.scaledHeight == capture.height
    if (unchanged) return capture
    return runCatching {
        val cropped = Bitmap.createBitmap(
            capture, region.x, region.y, region.width, region.height,
        )
        val scaled = if (
            cropped.width == region.scaledWidth && cropped.height == region.scaledHeight
        ) {
            cropped
        } else {
            Bitmap.createScaledBitmap(
                cropped, region.scaledWidth, region.scaledHeight, true,
            ).also { if (it !== cropped) cropped.recycle() }
        }
        // `createBitmap`/`createScaledBitmap` may return the source itself when nothing
        // had to change; only recycle the capture when a genuinely new bitmap came back.
        if (scaled !== capture) capture.recycle()
        scaled
    }.getOrElse {
        // Out of memory or a degenerate rectangle — the full frame is still a usable
        // question, so degrade to it rather than failing the round.
        Log.w(ASK_LOG_TAG, "Could not reframe the capture for the model; sending it whole.", it)
        capture
    }
}

/**
 * The in-scene answer card rendered by an anchored `ViewNode` (P2). Mirrors the states of
 * the screen-space card — spinner until the first delta, live text with a typing cursor
 * while streaming, question label above — at a width that stays legible once the node is
 * scaled down to [PANEL_SCALE].
 *
 * Uses `DESIGN.md`'s Spatial Gallery **scrim** treatment (`stage-scrim-end` /
 * [SceneViewTokens.SpatialGalleryColor.stageScrimEnd]) instead of the M3 `surface` role
 * (#3276): the card floats over a live camera feed of unpredictable brightness and colour,
 * not over the app's own background, so a theme-relative surface can land near-white-on
 * -white or low-contrast depending on the room and the user's light/dark setting. A ~90%
 * opaque near-black scrim with plain white "on-scrim" text guarantees contrast regardless
 * of what is behind it — the same reasoning the Spatial Gallery already applies to text
 * over photo/video content. Text sizes are bumped a step up from the screen-space card's
 * (`titleMedium`/`bodyMedium` instead of `bodyLarge`/`labelMedium`) because this card is
 * additionally viewed through [clampedPanelScale] perspective scaling — legible-sized type
 * at the outset means the perspective compensation has less shrinking to fight.
 */
@Composable
private fun AnchoredAnswerCard(question: String, text: String, streaming: Boolean) {
    // A ViewNode composes in its own off-screen ComposeView, which inherits none of this
    // demo's CompositionLocals — without re-applying the theme here, `MaterialTheme` would
    // resolve to M3 defaults and the in-scene card would not match the card it mirrors.
    SceneViewDemoTheme {
        val onScrim = Color.White
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
            color = SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
            contentColor = onScrim,
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            if (text.isEmpty() && streaming) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = onScrim)
                    Text(
                        text = stringResource(R.string.demo_point_and_ask_status_thinking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onScrim,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                return@M3Surface
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onScrim.copy(alpha = 0.80f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // A long answer scrolls inside the fixed box instead of growing it.
                    // Nothing drives this scroll by touch — the rendered UI is a texture,
                    // not an interactive view — so it also clips gracefully.
                    text = renderMarkdownLite(if (streaming) "$text▌" else text),
                    style = MaterialTheme.typography.titleMedium,
                    color = onScrim,
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

/**
 * The failure card (#3343). Two shapes, one component:
 *
 *  - **transient** — a single line naming what actually went wrong, so a busy model, a
 *    rejected frame and a failed capture read differently instead of all being "Gemini
 *    Nano couldn't answer";
 *  - **escalated** — reached when the failure is terminal (this device cannot run the
 *    model at all) or the same kind of failure has repeated
 *    [ASK_FAILURE_ESCALATION_THRESHOLD] times. The retry invitation is dropped and the
 *    card explains the on-device-only design and what the user can actually check.
 *
 * `DESIGN.md`'s "Blocked" severity: an error indicator plus the theme's `error` role, so
 * both schemes stay legible without a hardcoded colour.
 */
@Composable
private fun AskFailureCard(failure: AskFailure, escalated: Boolean) {
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.demo_point_and_ask_error_cd),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(
                    if (escalated) {
                        R.string.demo_point_and_ask_error_repeated_title
                    } else {
                        failure.messageRes
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (escalated) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (escalated) {
            Spacer(Modifier.height(6.dp))
            // The specific cause stays on screen under the explanation — it is what makes
            // a bug report actionable, and it is the line that matches logcat.
            Text(
                text = stringResource(failure.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.demo_point_and_ask_error_repeated_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
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
