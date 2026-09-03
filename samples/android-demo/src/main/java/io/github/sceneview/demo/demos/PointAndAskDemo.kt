package io.github.sceneview.demo.demos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
import androidx.core.net.toUri
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ai.ASK_LOG_TAG
import io.github.sceneview.demo.ai.AskCaptureOutcome
import io.github.sceneview.demo.ai.AskCaptureSource
import io.github.sceneview.demo.ai.AskEngine
import io.github.sceneview.demo.ai.AskFailure
import io.github.sceneview.demo.ai.AskFlow
import io.github.sceneview.demo.ai.AskFrame
import io.github.sceneview.demo.ai.AskRecovery
import io.github.sceneview.demo.ai.AskStep
import io.github.sceneview.demo.ai.asFailure
import io.github.sceneview.demo.ai.askStepForQaOverride
import io.github.sceneview.demo.ai.captureAskFrame
import io.github.sceneview.demo.ai.rememberAskEngine
import io.github.sceneview.demo.ai.toAvailability
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.putVoiceSilenceExtras
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
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
import kotlinx.coroutines.withTimeoutOrNull

/** Test tags for the Point & Ask QA flows (Maestro / layout dumps). */
object PointAndAskTestTags {
    const val ANSWER_CARD = "point_and_ask_answer_card"
    const val QUESTION_FIELD = "point_and_ask_question_field"
    const val PROP_PICKER = "point_and_ask_prop_picker"
    const val FAILURE_CARD = "point_and_ask_failure_card"
    const val FAILURE_ACTION = "point_and_ask_failure_action"
    const val DEBUG_FRAME = "point_and_ask_debug_frame"
}

/**
 * The frame that was actually handed to the model, kept for the debug/QA preview (#3407).
 * A thumbnail copy, made before the round recycles the real frame — so "the model saw
 * nothing" is checkable on the spot instead of from a bug report three days later.
 */
private class AskFramePreview(
    val thumbnail: Bitmap,
    val width: Int,
    val height: Int,
    val source: AskCaptureSource,
)

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
    fun accept(step: AskStep, failedText: (AskFailure) -> String) {
        when (step) {
            is AskStep.Answered -> {
                text = step.text
                streaming = step.streaming
            }
            is AskStep.Failed -> {
                if (text.isBlank()) text = failedText(step.failure)
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
 * Long-press a surface to drop a virtual prop into the room: because the capture reads the
 * AR view itself, the on-device model *sees the augmented scene* — tap the shiba you just
 * placed and Nano describes a dog that only exists in AR. That is the demo's whole point,
 * which is why a tap on a node is never swallowed (#3187).
 *
 * **The screen is one explicit state machine** ([AskFlow], plain Kotlin, JVM-tested):
 * checking availability → downloadable / downloading (with progress) → ready → capturing
 * the frame → thinking → answer (screen card + world-anchored panel) → or a failure that
 * names its cause and offers the single action that could fix it. Two rules hold it
 * together, and both come from #3407:
 *
 *  - **Only the platform may say "not on this phone."** `AskStep.ModelUnsupported` is
 *    reachable from a `FeatureStatus` report, a failed download, or a terminal ML Kit error
 *    code — and from nothing else. A run of ordinary failures changes the failure card's
 *    headline and nothing more. The previous build promoted three failures into a permanent
 *    "Point & Ask can't answer on this device" on a Pixel 9 whose Gemini Nano was working
 *    the whole time.
 *  - **Nothing unusable reaches the model.** The frame is read back from the AR view
 *    (`captureAskFrame`), cropped around the tap, downscaled to the model's budget, and then
 *    validated for size, transparency AND flatness (`inspectAskFrame`). The old path read
 *    the whole window — which can lose the Filament `SurfaceView` layer the AR scene lives
 *    in — and checked only for `alpha == 0`, so that layer coming back opaque black went
 *    straight to Gemini Nano as a blank image.
 *
 * No cloud fallback — on-device only, by design (#2648), and the controls sheet says so
 * rather than leaving it to the failure copy. In a debug/QA build the same sheet shows a
 * thumbnail of the exact frame that was sent, so "it sees nothing" is checkable on the spot.
 *
 * Under `DemoSettings.qaMode` the engine is a deterministic canned stand-in, and a capture
 * the emulator cannot produce falls back to a synthetic (textured, so it passes the real
 * validation) frame — the tap → capture → answer UI flow stays device-QA-able. Every card
 * can additionally be pinned for screenshots with `--es qa_ask_state <id>`
 * (`ASK_QA_STATE_IDS`), because an emulator has neither ARCore nor AICore (#2754).
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

    // The whole screen is one explicit state machine (#3407), and it lives in plain Kotlin
    // (`AskFlow`) so every transition is unit-tested off-device. `step` is the snapshot the
    // UI reads; `syncStep` republishes it after any transition. Keeping the machine itself
    // free of Compose is what makes "does a pile of capture failures declare the phone
    // unsupported?" a JVM test rather than a device question.
    val flow = remember { AskFlow() }
    var step by remember { mutableStateOf<AskStep>(AskStep.CheckingAvailability) }
    val syncStep: () -> Unit = { step = flow.step }

    // QA-only card override (`--es qa_ask_state <id>`): pins the bottom card to one state so
    // an emulator with neither ARCore nor AICore (#2754) can still screenshot every state in
    // light and dark. `null` for every normal launch.
    val qaStep = remember(DemoSettings.qaAskState) { askStepForQaOverride(DemoSettings.qaAskState) }
    val shownStep = qaStep ?: step

    LaunchedEffect(askEngine) {
        flow.onAvailability(askEngine.status().toAvailability())
        syncStep()
    }

    var isTracking by remember { mutableStateOf(false) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    // QA camera backdrop (#3308): the emulator has no camera HAL, so a blurred room photo
    // stands in for the camera feed until a real frame arrives. This demo had none, which
    // meant its emulator screenshots were flat black — the same thing a lost AR layer looks
    // like, so the QA sweep could not tell the fix from the bug (#3407).
    var cameraReady by remember { mutableStateOf(false) }
    val cameraStream = rememberARCameraStream(materialLoader)
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)
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

    // A round is busy while the screen card is working OR any anchored panel is still
    // streaming. `AskFlow.isBusy` covers the screen card; the panels add the anchored half.
    // The tap guard and the status pill BOTH read this single value — otherwise the pill
    // returns to "tap to ask" mid-stream while the guard silently drops every tap.
    val busy by remember {
        derivedStateOf {
            step == AskStep.CapturingFrame ||
                step == AskStep.Thinking ||
                (step as? AskStep.Answered)?.streaming == true ||
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

    // In-scene overlays (the plane grid, the anchored answer cards) are hidden for the few
    // frames around the read-back: they live INSIDE the Filament surface, so leaving them up
    // would bake a grid and the model's own earlier answers into the next question. The 2D
    // chrome no longer has to be hidden — the capture reads the AR view directly rather than
    // the whole window (#3407) — so the screen stops blinking on every tap.
    var hideOverlaysForCapture by remember { mutableStateOf(false) }

    // What was actually sent to the model, kept as a thumbnail for the debug/QA preview in
    // the controls sheet (#3407). Never built in a release build.
    var lastFramePreview by remember { mutableStateOf<AskFramePreview?>(null) }
    val keepFramePreview = BuildConfig.DEBUG || DemoSettings.qaMode

    // Bumped by every tap that starts a round. The capture effect keys off it rather than
    // off the state: two consecutive rounds produce the same `AskStep.CapturingFrame` value,
    // so a state-keyed effect would simply not re-run — and "the second tap does nothing" is
    // the kind of silence #3407 is about.
    var captureToken by remember { mutableIntStateOf(0) }

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

    // Where the last tap landed, in window pixels. The capture is cropped around it so the
    // model is shown what the user pointed at rather than the whole floor-to-ceiling frame
    // (see `askCaptureRegion`). Null before the first tap.
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

    // -- One ask round -----------------------------------------------------------------
    // Keyed by `captureToken`, not by the state: two rounds in a row produce the same
    // `AskStep.CapturingFrame` value, and a state-keyed effect would simply not re-run.
    //
    // The read-back itself moved to `captureAskFrame` (#3407). The demo used to PixelCopy
    // the whole WINDOW and accept anything that was not `alpha == 0`. But the AR scene is a
    // Filament `SurfaceView` -- its own compositor layer, which "punches a hole through the
    // window" -- and when a window read-back loses that layer as opaque black rather than as
    // transparent, the alpha probe passes, the #3343 crop then centres tightly on exactly
    // that region, and Gemini Nano is handed a flat frame. It answers about nothing, or
    // completes empty. That is #3407's "aucune reponse Gemini qui voit rien sur la frame AR".
    // Now the AR view is read back directly, every candidate frame is validated before it
    // leaves the app, and a rejected frame names its own cause.
    LaunchedEffect(captureToken) {
        if (captureToken == 0) return@LaunchedEffect
        // Where this round's answer goes: the panel pinned by the tap (P2), or the
        // screen-space card when the tap hit nothing trackable. Resolved once, here, so a
        // panel pinned by a LATER tap can never steal this round's deltas.
        val panel = pendingPanel
        val onStep: (AskStep) -> Unit = { newStep ->
            panel?.accept(newStep, failedText)
            step = newStep
        }
        val fail: (AskFailure) -> Unit = { failure ->
            Log.w(ASK_LOG_TAG, "Point & Ask round failed: $failure (#3407).")
            flow.onFailure(failure)
            onStep(flow.step)
        }

        val activity = context.findActivity()
        if (activity == null) {
            fail(AskFailure.CaptureFailed)
            return@LaunchedEffect
        }

        // Only the IN-SCENE overlays come down: they are drawn inside the Filament surface
        // this capture reads, so a plane grid or a previous anchored answer would become
        // part of the question. The 2D chrome stays up -- it is not in that surface, so the
        // screen no longer blinks on every tap.
        hideOverlaysForCapture = true
        try {
            delay(CAPTURE_OVERLAY_SETTLE_MS)
            // A read-back that never comes back would otherwise wedge the demo in
            // `CapturingFrame` forever, with `busy` stuck true and every later tap dropped
            // (#3188). Time it out into an ordinary, retryable failure instead.
            val outcome = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                captureAskFrame(activity, tapFocus?.x, tapFocus?.y)
            }
            val frame = when (outcome) {
                null -> {
                    Log.w(ASK_LOG_TAG, "Frame capture exceeded its budget (#3188).")
                    fail(AskFailure.CaptureFailed)
                    return@LaunchedEffect
                }

                is AskCaptureOutcome.Rejected -> {
                    // An emulator has no camera HAL and no AICore (#2754), so every capture
                    // path there legitimately comes back blank. QA mode substitutes a
                    // synthetic frame so the tap -> capture -> answer flow stays screenshot-
                    // able -- but only AFTER the real validation has run and logged, so the
                    // QA path exercises exactly the code a device does.
                    if (DemoSettings.qaMode) {
                        AskFrame(syntheticQaFrame(), AskCaptureSource.Window)
                    } else {
                        fail(outcome.verdict.asFailure() ?: AskFailure.CaptureFailed)
                        return@LaunchedEffect
                    }
                }

                is AskCaptureOutcome.Captured -> outcome.frame
            }

            if (keepFramePreview) {
                lastFramePreview?.thumbnail?.recycle()
                lastFramePreview = framePreviewOf(frame)
            }
            flow.onFrameAccepted()
            onStep(flow.step)
            askJob = scope.askAboutBitmap(frame.bitmap, askEngine, question) { failure, text ->
                when {
                    failure != null -> flow.onFailure(failure)
                    text != null -> flow.onDelta(text)
                    else -> flow.onStreamCompleted()
                }
                onStep(flow.step)
            }
        } finally {
            hideOverlaysForCapture = false
        }
    }

    // Auto-dismiss a completed answer so nothing lingers over the viewfinder.
    LaunchedEffect(step) {
        val answered = step as? AskStep.Answered ?: return@LaunchedEffect
        if (answered.streaming) return@LaunchedEffect
        delay(ANSWER_AUTO_DISMISS_MS)
        if (step == answered) {
            flow.onAnswerDismissed()
            syncStep()
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_point_and_ask_title),
        onBack = onBack,
        onReset = {
            askJob?.cancel()
            askJob = null
            // Reset clears the failure history too — the user explicitly asked for a clean
            // slate, so the demo gives the device another honest chance (#3343).
            flow.reset()
            syncStep()
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
            // Which engine answers, stated up front rather than only in the failure copy.
            // There is no cloud fallback in this demo and no API key anywhere near it: the
            // frame is captured, cropped and inferred on-device (#2648). Saying so where the
            // user can see it is the "make the choice explicit" half of #3407 — the choice
            // here is that there is only one, and it is the private one.
            Text(
                text = stringResource(R.string.demo_point_and_ask_engine_on_device),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

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

            // Debug / QA only: exactly what went to the model, at the size it went. "It
            // sees nothing on the AR frame" (#3407) was un-diagnosable from a bug report —
            // the frame was never visible anywhere, and the logcat line that would have said
            // so is drowned by ~90 ARCore "Use dataspace" lines a second, so the reporter's
            // last-53-lines window never contains it. Now it is on screen.
            if (keepFramePreview) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.demo_point_and_ask_debug_frame_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(6.dp))
                val preview = lastFramePreview
                if (preview == null) {
                    Text(
                        text = stringResource(R.string.demo_point_and_ask_debug_frame_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                } else {
                    Image(
                        bitmap = preview.thumbnail.asImageBitmap(),
                        contentDescription =
                            stringResource(R.string.demo_point_and_ask_debug_frame_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(DEBUG_FRAME_PREVIEW_HEIGHT)
                            .testTag(PointAndAskTestTags.DEBUG_FRAME),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.demo_point_and_ask_debug_frame_meta,
                            preview.width,
                            preview.height,
                            preview.source.label,
                            question,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
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
                visible = shownStep == AskStep.Ready && !busy,
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
                        text = if (isTracking) {
                            stringResource(R.string.demo_point_and_ask_status_ready)
                        } else {
                            stringResource(R.string.ar_status_scanning)
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
            // Exactly one card, chosen by exactly one value. Every state the demo can be in
            // is named here and none of them is a dead end: availability has its own cards
            // (checking / download / downloading / unsupported), a round has its own
            // (capturing / thinking / answer), and a failure names its cause AND offers the
            // one action that could fix it (#3407).
            if (!hideOverlaysForCapture) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    // Full-width card: only its end edge can reach the Settings
                    // FAB, so only the end edge is inset (0.dp when there is no FAB).
                    .padding(end = settingsFabReservedSpace),
            ) {
                when (val current = shownStep) {
                    // Say so in words while the availability probe runs: a blank bottom edge
                    // here was indistinguishable from a broken demo (#3188).
                    AskStep.CheckingAvailability -> BottomCard {
                        ProgressRow(stringResource(R.string.demo_point_and_ask_status_checking))
                    }

                    AskStep.ModelDownloadable -> BottomCard {
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_download_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch {
                                askEngine.download().collect {
                                    flow.onAvailability(it.toAvailability())
                                    syncStep()
                                }
                            }
                        }) {
                            Text(stringResource(R.string.demo_point_and_ask_download_cta))
                        }
                    }

                    is AskStep.ModelDownloading -> BottomCard {
                        ProgressRow(
                            current.bytesDownloaded
                                ?.let { bytes ->
                                    stringResource(
                                        R.string.demo_point_and_ask_downloading_progress,
                                        bytes / (1024 * 1024),
                                    )
                                }
                                ?: stringResource(R.string.demo_point_and_ask_downloading),
                        )
                    }

                    // The ONLY card that says this device cannot run the demo — and it is
                    // reached only from a platform report (`FeatureStatus`, a failed
                    // download, or a terminal ML Kit code), never from a retry count (#3407).
                    AskStep.ModelUnsupported -> BottomCard {
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_unavailable_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.demo_point_and_ask_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { context.openAicoreSettings() }) {
                            Text(stringResource(R.string.demo_point_and_ask_action_aicore))
                        }
                    }

                    // Ready and idle: the pill above carries the instruction, the viewfinder
                    // stays clear.
                    AskStep.Ready -> Unit

                    AskStep.CapturingFrame -> BottomCard {
                        ProgressRow(stringResource(R.string.demo_point_and_ask_status_capturing))
                    }

                    AskStep.Thinking -> BottomCard {
                        ProgressRow(stringResource(R.string.demo_point_and_ask_status_thinking))
                    }

                    is AskStep.Answered -> BottomCard(
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
                                if (current.streaming) "${current.text}▌" else current.text
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

                    is AskStep.Failed -> AskFailureCard(
                        failure = current.failure,
                        persistent = current.persistent,
                        onAction = {
                            when (current.failure.recovery) {
                                AskRecovery.OpenAicoreSettings -> context.openAicoreSettings()
                                AskRecovery.FreeStorage -> context.openStorageSettings()
                                else -> Unit
                            }
                            flow.onRetry()
                            syncStep()
                        },
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // QA camera backdrop (#3308). The demo had none, so every emulator screenshot of
            // it landed on flat black — which is also a frame the model would see nothing in,
            // making the emulator indistinguishable from the device defect this fixes.
            if (qaBackdrop) QaCameraBackdrop(seed = "point-and-ask")
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                playbackDataset = arPlaybackDataset,
                // Planes are shown so the user can see where a tap will pin its answer —
                // but never during the capture window: the composited frame is what Nano
                // is asked about, and a grid baked over the room would be part of the
                // question. Same reasoning as `hideOverlaysForCapture` for the 2D chrome.
                planeRenderer = !hideOverlaysForCapture,
                viewNodeWindowManager = viewNodeManager,
                onSessionUpdated = { _, frame ->
                    latestFrame = frame
                    cameraReady = true
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
                        // `busy` is the anchored half of the guard; `flow.canAsk` is the
                        // screen half (model ready, no round in flight). A QA state override
                        // pins the card, so taps must not fight it.
                        if (qaStep == null && flow.canAsk && !busy) {
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
                            flow.onTap()
                            syncStep()
                            captureToken++
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

/** Unwraps the [Activity] hosting this composition (needed for the frame read-back). */
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
 * Opens the system app-details screen for AICore / Android System Intelligence — the one
 * place a user can actually check for the update the two terminal failures ask for. Falls
 * back to the generic app-settings screen when that package is not installed (which is
 * itself the reason the model is unavailable), and does nothing at all rather than crash if
 * neither resolves.
 */
private fun Context.openAicoreSettings() {
    val targets = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:$AICORE_PACKAGE".toUri()),
        Intent(Settings.ACTION_APPLICATION_SETTINGS),
    )
    for (intent in targets) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
    Log.w(ASK_LOG_TAG, "No settings activity accepted the AICore intent (#3407).")
}

/** Opens the storage settings screen — the action offered for `NOT_ENOUGH_DISK_SPACE`. */
private fun Context.openStorageSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        .onFailure { Log.w(ASK_LOG_TAG, "No storage settings activity (#3407).", it) }
}

/** The system package that hosts AICore / Gemini Nano. */
private const val AICORE_PACKAGE = "com.google.android.aicore"

/**
 * Runs one streamed [AskEngine.askStream] round-trip over [bitmap], reporting results through
 * [onEvent]: a non-null failure, else a non-null accumulated answer for each delta, else
 * `(null, null)` for "the stream completed". Takes ownership of [bitmap] (recycled when the
 * round ends).
 *
 * The throwable is classified rather than swallowed (#3343): the card names the actual cause,
 * and every failure is logged with its ML Kit error code. Interpreting those events — keeping
 * a partial answer, counting a run of failures, deciding whether the platform actually said
 * "unsupported" — is `AskFlow`'s job, not this function's, which is exactly why that logic is
 * unit-tested and this glue is not (#3407).
 */
private fun CoroutineScope.askAboutBitmap(
    bitmap: Bitmap,
    askEngine: AskEngine,
    question: String,
    onEvent: (failure: AskFailure?, text: String?) -> Unit,
) = launch {
    var text = ""
    try {
        askEngine.askStream(bitmap, question).collect { delta ->
            text += delta
            onEvent(null, text)
        }
        if (text.isBlank()) {
            Log.w(ASK_LOG_TAG, "Gemini Nano completed the stream with no text (#3343).")
        }
        onEvent(null, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: a minified build missing the ML Kit classes raises a
        // NoClassDefFoundError, which is an Error — letting it escape would kill the
        // coroutine scope silently and leave the demo wedged in `Thinking` (cf. #3188).
        val failure = AskFailure.of(e)
        Log.w(ASK_LOG_TAG, "Gemini Nano inference failed — classified as $failure (#3343).", e)
        onEvent(failure, null)
    } finally {
        bitmap.recycle()
    }
}

/**
 * A deterministic stand-in frame for QA runs on an emulator, which has no camera HAL and so
 * no real frame to read back (#2754/#3308). Deliberately textured rather than flat, so it
 * passes the same [io.github.sceneview.demo.ai.inspectAskFrame] validation a device frame
 * must pass — a QA frame that could not survive the real check would be testing nothing.
 */
private fun syntheticQaFrame(): Bitmap {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val checker = if (((x / 32) + (y / 32)) % 2 == 0) 40 else 200
            pixels[y * size + x] = (0xFF shl 24) or (checker shl 16) or (checker shl 8) or checker
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}

/** Longest edge of the debug frame thumbnail kept in memory between rounds. */
private const val DEBUG_FRAME_PREVIEW_MAX_EDGE = 192

/** On-screen height of that thumbnail in the controls sheet. */
private val DEBUG_FRAME_PREVIEW_HEIGHT = 140.dp

/**
 * A small copy of the frame about to be sent, for the debug/QA preview. A copy, because the
 * round recycles the real bitmap the moment inference ends — and the whole point is to still
 * be able to look at what the model looked at afterwards (#3407).
 */
private fun framePreviewOf(frame: AskFrame): AskFramePreview? = runCatching {
    val longest = maxOf(frame.bitmap.width, frame.bitmap.height).coerceAtLeast(1)
    val scale = (DEBUG_FRAME_PREVIEW_MAX_EDGE.toFloat() / longest).coerceAtMost(1f)
    val thumbnail = Bitmap.createScaledBitmap(
        frame.bitmap,
        (frame.bitmap.width * scale).toInt().coerceAtLeast(1),
        (frame.bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    AskFramePreview(
        // `createScaledBitmap` can hand back the source itself; copy so recycling the frame
        // at the end of the round does not blank the preview.
        thumbnail = if (thumbnail === frame.bitmap) {
            frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            thumbnail
        },
        width = frame.bitmap.width,
        height = frame.bitmap.height,
        source = frame.source,
    )
}.getOrNull()

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
 * The failure card (#3343, reworked by #3407). One shape, one component:
 *
 *  - it always names the **actual cause**, so a busy model, a rejected frame, a lost AR
 *    layer and a blank frame read differently;
 *  - it always offers the **one action** that could fix that cause
 *    ([AskFailure.recovery]) — a button, not a sentence;
 *  - when [persistent] it changes only its headline, to say the step keeps failing. It does
 *    NOT claim the device cannot run the model. #3343 wired a retry counter straight to
 *    "Point & Ask can't answer on this device", so three ordinary capture failures on a
 *    perfectly capable Pixel 9 ended the demo in a dead end that also happened to be untrue
 *    (#3407). "Not on this phone" is now `AskStep.ModelUnsupported`'s card alone, and only
 *    the platform can put the demo there.
 *
 * `DESIGN.md`'s "Blocked" severity: an error indicator plus the theme's `error` role, so
 * both schemes stay legible without a hardcoded colour.
 */
@Composable
private fun AskFailureCard(
    failure: AskFailure,
    persistent: Boolean,
    onAction: () -> Unit,
) {
    BottomCard(testTag = PointAndAskTestTags.FAILURE_CARD) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.demo_point_and_ask_error_cd),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(
                    if (persistent) {
                        R.string.demo_point_and_ask_error_repeated_title
                    } else {
                        failure.messageRes
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (persistent) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (persistent) {
            Spacer(Modifier.height(6.dp))
            // The specific cause stays on screen under the headline — it is what makes a bug
            // report actionable, and it is the line that matches logcat.
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
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onAction,
            modifier = Modifier.testTag(PointAndAskTestTags.FAILURE_ACTION),
        ) {
            Text(stringResource(failure.recovery.labelRes))
        }
    }
}

/** Spinner + one line of copy — the shape every "working on it" card shares. */
@Composable
private fun ProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
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
