@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.math.Position
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Overlay that covers the 3D viewport while [loading] is true. Shows a centred spinner and
 * the [label] underneath so users know *why* the viewport is black. Fades out automatically
 * when [loading] flips to false (Compose removes the Box from the tree).
 *
 * Drop this inside a SceneView's content block OR over the whole Box that contains the
 * SceneView — the scrim is semi-transparent so the first rendered frame shows through.
 */
@Composable
fun LoadingScrim(
    loading: Boolean,
    label: String = stringResource(R.string.demo_loading_generic),
) {
    if (!loading) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            // M3 Expressive morphing-shape indicator: it reads as "a scene is coming",
            // where a bare ring reads as "the network is slow".
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            io.github.sceneview.demo.ui.NarrationText(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Error overlay that covers the 3D viewport when a model fails to resolve or load.
 *
 * Streamed demos ([io.github.sceneview.demo.demos.ModelViewerDemo]'s Gallery
 * section — formerly `SceneGalleryDemo`, #2239 Batch 5 —
 * [io.github.sceneview.demo.demos.MaterialsDemo]) used to wrap their
 * `resolver.resolve()` call in `runCatching { … }.getOrNull()`. On a
 * `FallbackUnavailable` throw (or any failure) the resolved path stayed `null`
 * forever and the [LoadingScrim] hung on "Streaming…" with nothing surfaced to
 * the user (#2088). [ErrorScrim] replaces that dead-end with an honest error
 * card and a [onRetry] button — the Android counterpart of the iOS demo's
 * `loadError` state.
 *
 * Render it as the last child of the [Box] that wraps the SceneView, mutually
 * exclusive with [LoadingScrim] (show the error scrim when an error state is
 * set, the loading scrim otherwise).
 *
 * @param message Short human-readable failure reason (e.g. the exception
 *                message). Shown under the headline.
 * @param onRetry Invoked when the user taps the retry button — re-trigger the
 *                resolve/load by clearing the error state.
 */
@Composable
fun ErrorScrim(
    message: String,
    onRetry: () -> Unit,
    label: String = "Couldn't load model",
    retryLabel: String = "Retry",
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text(retryLabel)
            }
        }
    }
}

/**
 * Camera-initialising scrim for AR demos.
 *
 * An [io.github.sceneview.ar.ARSceneView] paints its surface jet-black until ARCore
 * opens the camera and delivers the first frame — on a cold start that can take
 * several seconds. With no overlay the viewport reads as a frozen or crashed screen
 * (#1473). [ARCameraInitScrim] covers the viewport with a centred spinner and a
 * "Starting camera…" label until [initializing] flips to false, at which point
 * Compose drops the Box from the tree and the live camera feed shows through.
 *
 * Drive [initializing] off the demo's first `onSessionUpdated` callback — the first
 * invocation means ARCore has delivered a camera frame:
 *
 * ```kotlin
 * var cameraReady by remember { mutableStateOf(false) }
 * var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
 * Box(Modifier.fillMaxSize()) {
 *     ARSceneView(
 *         onSessionUpdated = { _, _ -> cameraReady = true /* … */ },
 *         onARCoreAvailability = { arCoreAvailability = it },
 *         …
 *     ) { … }
 *     ARCameraInitScrim(
 *         initializing = !cameraReady,
 *         arCoreAvailability = arCoreAvailability,
 *     )
 * }
 * ```
 *
 * Place it as the last child of the [Box] that wraps the `ARSceneView` so it draws
 * on top of the still-black viewport but below any other status overlays.
 *
 * [arCoreAvailability] is the ARCore verdict reported by `ARSceneView.onARCoreAvailability`,
 * and it is **not optional** (#3341): a non-null verdict means ARCore will never deliver a
 * frame on this device, so [initializing] stays true forever and the scrim would cover the
 * viewport permanently — including the SDK's own
 * [io.github.sceneview.ar.ARCoreAvailabilityOverlay] explanation card, which draws *inside*
 * the `ARSceneView` and therefore *below* this scrim. Pass the verdict and the scrim steps
 * aside the moment it lands. On an emulator (#2754) this is the only path there is.
 *
 * Defensive timeout (#2484): if the first frame never arrives — a device that fails
 * to open the camera, a session that errors before delivering a frame — the scrim
 * would otherwise cover the viewport forever. After [timeoutMillis] the **spinner card**
 * dismisses itself regardless, so the demo's own fallback / error messaging is never
 * permanently hidden behind a progress indicator that is lying. The black backdrop stays
 * (#3373) — an AR viewport that never received a frame is not black, and uncovering it
 * painted a parasitic band across the fallback screen. See [arCameraInitScrimVisibility].
 * The happy path (first frame in ~1–3 s) flips [initializing] false long before the
 * timeout, so this only ever fires on a genuinely stuck start.
 *
 * [onNarratingChange] reports whether the spinner card is on screen. A demo with its own
 * status pill uses it to keep one loader per screen (#3825): silent while this card
 * narrates the camera start, and taking over once the card has stepped aside.
 */
@Composable
fun ARCameraInitScrim(
    initializing: Boolean,
    arCoreAvailability: ARCoreAvailability?,
    label: String = stringResource(R.string.ar_starting_camera),
    timeoutMillis: Long = AR_CAMERA_INIT_SCRIM_TIMEOUT_MS,
    onNarratingChange: ((Boolean) -> Unit)? = null,
) {
    // Defensive fallback: force-dismiss even if the first camera frame never reports.
    var timedOut by androidx.compose.runtime.remember(initializing) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // QA camera backdrop (#3308): the emulator never delivers a frame, so drop the black
    // cover as soon as the backdrop takes over instead of holding it for the full timeout.
    val effectiveTimeout = if (io.github.sceneview.demo.common.qaCameraBackdropEnabled()) {
        minOf(timeoutMillis, io.github.sceneview.demo.common.QA_BACKDROP_TIMEOUT_MS)
    } else {
        timeoutMillis
    }
    if (initializing) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(effectiveTimeout)
            timedOut = true
        }
    }
    val visibility = arCameraInitScrimVisibility(
        initializing = initializing,
        timedOut = timedOut,
        qaBackdropEnabled = io.github.sceneview.demo.common.qaCameraBackdropEnabled(),
        arCoreUnavailable = arCoreAvailability != null,
    )
    // Tells a demo whether this scrim's card is the one narrating the camera start, so its own
    // status pill can stay silent instead of saying the same thing underneath (#3825).
    if (onNarratingChange != null) {
        val narrating = visibility == ArCameraInitScrimVisibility.BackdropAndSpinner
        androidx.compose.runtime.LaunchedEffect(narrating) { onNarratingChange(narrating) }
    }
    if (visibility == ArCameraInitScrimVisibility.Hidden) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (visibility == ArCameraInitScrimVisibility.BackdropOnly) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            io.github.sceneview.demo.ui.NarrationText(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What [ARCameraInitScrim] should draw for a given session state (#2484, #3373). */
internal enum class ArCameraInitScrimVisibility {
    /** Nothing — the camera feed is live, or the QA backdrop owns the viewport. */
    Hidden,

    /** The opaque black backdrop only — the session is stuck, but the viewport must stay covered. */
    BackdropOnly,

    /** The black backdrop plus the spinner card — a normal, still-progressing camera start. */
    BackdropAndSpinner,
}

/**
 * Decides what [ARCameraInitScrim] draws. Pure so it can be unit-tested JVM-side.
 *
 * The defensive timeout (#2484) used to dismiss the whole scrim, backdrop included. That was the
 * wrong half to drop (#3373): when ARCore never starts, the AR viewport behind the scrim is not
 * black — it is whatever the uninitialised camera-stream quad happens to sample — so uncovering
 * it painted a parasitic band across the demo's own "AR couldn't start" fallback. The timeout
 * exists so the *spinner* stops lying about progress; the backdrop is what guarantees the
 * fallback screen has a deliberate background. So after the timeout we keep the backdrop and
 * drop only the spinner.
 *
 * Two cases dismiss completely. The QA camera backdrop (#3308): there a synthetic room photo
 * is deliberately rendered behind the viewport and must be visible. And [arCoreUnavailable]
 * (#3341): once ARCore has returned a verdict, the session will never start, so there is
 * nothing left to wait for and the scrim is covering the SDK's own explanation card. That
 * branch comes first — it must win over the spinner phase too, because a verdict makes the
 * spinner wrong immediately, not eight seconds later. Uncovering the viewport is safe here
 * for a reason #3373 did not have: since #3377 the camera-stream quad stays out of the render
 * pass until a real frame is bound, so what shows through is the renderer's own opaque black
 * clear colour — exactly the ground the dark explanation card is designed for.
 */
internal fun arCameraInitScrimVisibility(
    initializing: Boolean,
    timedOut: Boolean,
    qaBackdropEnabled: Boolean,
    arCoreUnavailable: Boolean,
): ArCameraInitScrimVisibility = when {
    arCoreUnavailable -> ArCameraInitScrimVisibility.Hidden
    !initializing -> ArCameraInitScrimVisibility.Hidden
    !timedOut -> ArCameraInitScrimVisibility.BackdropAndSpinner
    qaBackdropEnabled -> ArCameraInitScrimVisibility.Hidden
    else -> ArCameraInitScrimVisibility.BackdropOnly
}

/**
 * Defensive dismiss window for [ARCameraInitScrim] (#2484). The first ARCore camera
 * frame normally arrives in ~1–3 s; 8 s is comfortably past that, so the timeout only
 * trips when the session is genuinely stuck (no camera, hard session failure).
 */
/**
 * How long [ARCameraInitScrim] covers a still-black AR viewport before giving up and
 * dismissing itself, milliseconds (#2484).
 *
 * `internal` rather than private since #3326: the placement screen's coaching layer has to
 * start speaking exactly where this stops, and a second hardcoded `8_000` over there would
 * be arithmetic that goes stale the first time this one is tuned. See
 * `PLACEMENT_STARTUP_STALL_MS`.
 */
internal const val AR_CAMERA_INIT_SCRIM_TIMEOUT_MS = 8_000L

/**
 * Resolves the ARCore playback dataset an AR demo should replay, or `null` for a normal
 * live-camera session.
 *
 * The autonomous AR replay device-QA harness ([io.github.sceneview.demo.ar.ARReplayHarnessTest])
 * deep-links every AR demo with `--es ar_playback_file <path>`, which [MainActivity] stores in
 * [DemoSettings.arPendingPlaybackFile]. Historically only the `ar-record-playback` demo read
 * that setting, so the other AR demos could only be graded `alive` (process survived), never
 * `replayed` (recorded ARCore frames actually advanced). Calling this helper and forwarding
 * the result to `ARSceneView(playbackDataset = ...)` lets a live-only demo honour the same
 * deep-link and graduate to `replayed` (#1576).
 *
 * ```kotlin
 * val playbackDataset = rememberArPlaybackDataset()
 * ARSceneView(playbackDataset = playbackDataset, ...) { ... }
 * ```
 *
 * ### Zero impact on real users - the critical safety property
 *
 * [DemoSettings.arPendingPlaybackFile] is `null` for every normal launch (it is only ever set
 * by the QA harness's intent extra). When it is `null` this helper returns `null`, and
 * `ARSceneView(playbackDataset = null)` is exactly the plain live-AR session - i.e. the demo
 * behaves identically to before this wiring existed. There is no live-AR regression path.
 *
 * The pending file is **consumed** (reset to `null`) on first composition via a
 * [androidx.compose.runtime.LaunchedEffect], mirroring `ARRecordPlaybackDemo`, so a
 * configuration change or process recreation does not silently re-enter playback. The
 * resolved [File] is captured in `remember` so the demo keeps replaying the dataset across
 * recompositions even after the setting is cleared.
 *
 * A path that does not point at an existing file resolves to `null` (live AR, no crash) -
 * the same defensive `takeIf { it.exists() }` guard `ARRecordPlaybackDemo` uses.
 *
 * @return the dataset [File] to pass as `ARSceneView(playbackDataset = ...)`, or `null` for a
 *         normal live-camera AR session.
 */
@Composable
fun rememberArPlaybackDataset(): File? {
    val dataset = androidx.compose.runtime.remember {
        DemoSettings.arPendingPlaybackFile
            ?.let(::File)
            ?.takeIf { it.exists() }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Consume so a config change / process recreation doesn't re-trigger playback.
        DemoSettings.arPendingPlaybackFile = null
    }
    return dataset
}

/**
 * First-frame signal for the [DemoScaffold] loading scrim.
 *
 * Cold-starting a SceneView demo leaves the viewport jet-black for 5–12 s while
 * Filament compiles shaders and uploads buffers — it reads as a crash to a
 * first-time user (#1022). [rememberFirstFrameState] returns this pair so a demo
 * can flip the scrim off exactly when the scene is really on screen:
 *
 * ```kotlin
 * val firstFrame = rememberFirstFrameState(engine)
 * DemoScaffold(title = …, onBack = onBack, firstFrameRendered = firstFrame.rendered) {
 *     SceneView(onFrame = firstFrame.onFrame, …) { … }
 * }
 * ```
 *
 * ### Why the FIRST presented frame is not the signal (#3444)
 *
 * `SceneView` calls back only for frames that Filament accepted, but accepting a
 * frame means it was **submitted**, not that the driver has drawn it. During
 * warm-up the driver runs far behind: measured on `emulator-5554`, the Materials
 * demo submits 4 frames in its first 6.3 s — ~1.5 s of GPU each, compiling the
 * ToyCar's clearcoat / sheen / transmission variants — and only then settles at
 * 60 fps. Lifting the cover on submission #1 uncovered a surface the driver would
 * not paint for another ~8 s: a black viewport with no spinner, no label and no
 * "Still loading…" card, which is what the QA screenshot (taken at ~11 s), the
 * store capture and the bug reporter all recorded.
 *
 * ### Why sustained cadence is not the signal either (#3108)
 *
 * The first answer to the paragraph above was a **cadence streak**: eight presented
 * frames in a row, each within 250 ms of the one before, on the reasoning that a
 * loop running at speed is a loop no longer waiting on the driver. That reasoning
 * silently assumed a loop that keeps ticking — true when every `SceneView` drew
 * every vsync forever, false the moment rendering became on-demand.
 *
 * Measured on `emulator-5554` after the switch: `model-viewer` and `splat-preview`
 * present **3 frames in 10 s** and then park, with the model fully drawn and lit on
 * screen. The streak needs eight and can never be paid, so `rendered` stayed
 * `false`, and at 12 s `DemoScaffold` replaced the spinner with its "Still loading…"
 * card — permanently, over a finished scene. Nudging the camera restarted the loop,
 * completed the streak and dismissed the card, which is the proof: the signal was
 * reading the *frame rate* and reporting it as *progress*.
 *
 * **A readiness signal must never depend on cadence. A parked scene is a ready
 * scene** — parking is what the renderer does when there is nothing left to draw.
 *
 * ### The signal
 *
 * [READY_PRESENTED_FRAMES] presented frames, **at any interval**, and then one
 * backend drain: a fence that signals only once the backend has actually executed the
 * queued work rather than merely accepted it. That is the driver truth the streak
 * was trying to infer from timing, asked directly.
 *
 * The drain is polled ([BackendDrainWait]), never awaited. This used to be an
 * `Engine.flushAndWait()` on the main thread, and on a software GL it blocked there for
 * the whole material-link time. A BACK press during that stall hit the 5 s input-dispatch
 * limit and raised an ANR (#3799).
 *
 * Two frames rather than one because Filament applies backpressure: a *second*
 * accepted submission is itself the evidence that the first was drained. Neither
 * number is a cadence — a scene that presents its two frames 1.5 s apart during
 * shader compilation waits exactly as long as it should, and a scene that presents
 * them in the settle tail and parks is ready in 33 ms.
 *
 * @property rendered Read in the scaffold — `false` until the scene is really on
 *                    screen, then `true`. Never goes back to `false`.
 * @property onFrame Pass straight to `SceneView(onFrame = …)`. Cheap after the
 *                   first call.
 */
class FirstFrameState internal constructor(
    private val renderedState: androidx.compose.runtime.MutableState<Boolean>,
    private val backendDrain: BackendDrainWait? = null,
) : androidx.compose.runtime.RememberObserver {
    /** How many frames the scene has presented so far, capped once [rendered] latches. */
    private var presentedFrames: Int = 0

    val rendered: androidx.compose.runtime.State<Boolean> get() = renderedState

    val onFrame: (frameTimeNanos: Long) -> Unit = {
        if (!renderedState.value && presentedFrames < READY_PRESENTED_FRAMES) {
            presentedFrames++
        }
        if (!renderedState.value && presentedFrames >= READY_PRESENTED_FRAMES) {
            // Latches once the backend has executed what those frames queued: on a software
            // GL that is the whole material-link time, on hardware ~100 ms. The cover is a
            // static image, so the wait is invisible — and it is the only thing here that
            // speaks to the driver rather than about it. Polled, never awaited (#3799);
            // repeated calls while the fence is out are no-ops.
            val drain = backendDrain
            if (drain == null) renderedState.value = true else drain.start { renderedState.value = true }
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        backendDrain?.cancel()
    }

    override fun onAbandoned() {
        backendDrain?.cancel()
    }
}

/**
 * Presented frames [FirstFrameState] needs, **at any interval**, before it flushes the backend
 * and calls the scene visible.
 *
 * Two, and the second one carries the argument: Filament refuses a new frame while the driver
 * is behind, so a second accepted submission proves the first was drained. One frame would
 * prove only that the loop asked. Anything larger re-introduces the failure this replaced —
 * a render-on-demand scene presents a handful of frames and parks, so a threshold above what
 * the settle tail pays is a threshold that is never reached.
 */
private const val READY_PRESENTED_FRAMES = 2

/**
 * Is the viewport showing something worth looking at?
 *
 * `null` means the demo has no first-frame state at all — an AR demo, whose viewport is the
 * camera feed and never goes through the loading cover. Those are ready as soon as they are
 * composed; everything else is ready only once [FirstFrameState] says a frame reached the
 * surface (#3444).
 *
 * This is the predicate behind the viewport's "Scene ready" accessibility name, which TalkBack
 * announces and device QA waits on, so the AR pass-through is part of the contract rather than
 * an accident: a flow that waited on a signal AR demos never publish would hang on every one
 * of them.
 */
internal fun demoSceneReady(firstFrameRendered: Boolean?): Boolean = firstFrameRendered ?: true

/**
 * Remembers a [FirstFrameState] for wiring the [DemoScaffold] loading scrim to a
 * SceneView's first presented frame. See [FirstFrameState] for the usage pattern.
 *
 * @param engine the scene's engine. Optional only so a preview or a test can leave it out:
 *               without it the cover lifts on the presented-frame count alone, which says the
 *               loop accepted the work but not that the backend finished it. Pass the same
 *               `rememberEngine()` the `SceneView` uses.
 */
@Composable
fun rememberFirstFrameState(
    engine: com.google.android.filament.Engine? = null,
): FirstFrameState {
    val rendered = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    return androidx.compose.runtime.remember(engine) {
        FirstFrameState(rendered, engine?.let(::filamentBackendDrainWait))
    }
}

/**
 * Idle auto-orbit state for a camera that sweeps slowly around a target.
 *
 * Returns a [OrbitState] whose [yaw][OrbitState.yaw] advances from 0° to 360° in
 * [durationMillis] and resets. Converts to a `Position` on a circle of radius
 * [radius] at height [yHeight]. Wire this into a SceneView with
 * `cameraManipulator = rememberCameraManipulator(orbitHomePosition = state.toPosition())`
 * OR directly into a `CameraNode.position` via SideEffect.
 *
 * When [DemoSettings.qaMode] is `true` the orbit freezes at [staticYaw] so screenshot
 * captures are deterministic.
 *
 * @param durationMillis One full sweep in ms. 16 s feels natural at phone scale.
 * @param radius Orbit radius in metres.
 * @param yHeight Camera y offset (positive = above the target).
 * @param staticYaw Yaw angle to freeze at in QA mode (degrees). Default 45° gives a
 *                  clean 3/4 hero view.
 */
@Composable
fun rememberAutoOrbit(
    durationMillis: Int = 16_000,
    radius: Float = 2.5f,
    yHeight: Float = 0.8f,
    staticYaw: Float = 45f,
): OrbitState {
    val transition = rememberInfiniteTransition(label = "auto-orbit")
    val animatedYaw by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "orbit-yaw",
    )
    val yaw = if (DemoSettings.qaMode) staticYaw else animatedYaw
    return OrbitState(yaw = yaw, radius = radius, yHeight = yHeight)
}

/**
 * Pause the hero auto-rotate as soon as the user touches the viewport — they're
 * interacting and a spinning model fights their gestures. State persists across
 * recompositions so it stays paused for the rest of the demo session.
 *
 * Wire `onPause` into the SceneView's `onGestureListener`:
 *
 * ```kotlin
 * val (yaw, onUserGesture) = rememberPausableHeroYaw(modelInstance != null)
 * onGestureListener = rememberOnGestureListener(
 *     onSingleTapUp = { _, _ -> onUserGesture() },
 *     onDown = { _, _ -> onUserGesture() },
 *     onScroll = { _, _, _, _, _ -> onUserGesture() },
 * )
 * ```
 *
 * Or just call `onUserGesture()` from `onTouchEvent` for the broadest coverage.
 *
 * ### Idle-resume (opt-in)
 *
 * Pass a non-null [idleResumeMillis] to make the rotation *gently resume* once the
 * user has been idle for that long, instead of staying paused forever. Each gesture
 * restarts the idle countdown, so the rotation only comes back when the user has
 * truly stopped interacting. Demos that don't pass it keep the original
 * pause-forever behaviour, so existing callers are unaffected.
 *
 * ```kotlin
 * // Resume the hero spin 3 s after the last gesture.
 * val (yaw, onUserGesture) = rememberPausableHeroYaw(
 *     trigger = modelInstance != null,
 *     idleResumeMillis = 3_000L,
 * )
 * ```
 */
data class HeroYawController(val yaw: Float, val onUserGesture: () -> Unit)

@Composable
fun rememberPausableHeroYaw(
    trigger: Boolean,
    durationMillis: Int = 20_000,
    staticYaw: Float = 45f,
    idleResumeMillis: Long? = null,
): HeroYawController {
    val pausedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val paused = pausedState.value
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    // Bumped on every gesture; the idle-resume effect keys off it so each new
    // gesture cancels the in-flight countdown and starts a fresh one.
    val gestureTick = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Opt-in: when idle-resume is enabled, wait out the idle window after the last
    // gesture, then lift the pause. Keyed on gestureTick so a gesture mid-countdown
    // restarts the timer; the coroutine is cancelled cleanly by Compose on each
    // re-key (and on dispose / qaMode toggle), so no timer ever leaks.
    if (idleResumeMillis != null) {
        androidx.compose.runtime.LaunchedEffect(gestureTick.intValue, DemoSettings.qaMode) {
            if (pausedState.value && !DemoSettings.qaMode) {
                kotlinx.coroutines.delay(idleResumeMillis)
                pausedState.value = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(trigger, DemoSettings.qaMode, paused) {
        if (trigger && !DemoSettings.qaMode && !paused) {
            // Resume from current yaw if previously paused — no snap.
            val currentYaw = anim.value % 360f
            anim.snapTo(currentYaw)
            // Animate to next 360° boundary, then loop full sweeps.
            anim.animateTo(
                targetValue = currentYaw + (360f - currentYaw),
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = ((360f - currentYaw) / 360f * durationMillis).toInt()
                        .coerceAtLeast(1),
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
            while (true) {
                anim.snapTo(0f)
                anim.animateTo(
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = durationMillis,
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                )
            }
        }
    }
    val yaw = if (DemoSettings.qaMode) staticYaw else anim.value
    return HeroYawController(
        yaw = yaw,
        onUserGesture = {
            pausedState.value = true
            // Restart the idle countdown (no-op when idle-resume is disabled).
            gestureTick.intValue++
        },
    )
}

/**
 * Smooth y-axis hero-rotation that starts from 0° **only after** [trigger] becomes true.
 *
 * Using a plain `rememberInfiniteTransition` for an auto-rotate creates a visible
 * "snap" the moment a heavy GLB finishes loading: the InfiniteTransition has been
 * ticking from the start of composition, so by the time the model's first frame
 * renders the yaw is already at e.g. 144° — the model appears at 0° for one frame
 * and then jumps to the current animated value. This helper avoids that by starting
 * an [androidx.compose.animation.core.Animatable] sweep from 0° **only when**
 * [trigger] flips to true (e.g. when modelInstance becomes non-null), so the model's
 * first frame and the first animated frame are at the same yaw.
 *
 * Returns [staticYaw] (default 45°) when [DemoSettings.qaMode] is on so screenshot
 * tests get deterministic output.
 *
 * @param trigger Animation starts when this flips to `true`. Pass `modelInstance != null`.
 * @param durationMillis One full sweep in ms.
 * @param staticYaw Yaw to use in QA mode (degrees).
 */
@Composable
fun rememberHeroYaw(
    trigger: Boolean,
    durationMillis: Int = 20_000,
    staticYaw: Float = 45f,
): Float {
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(trigger, DemoSettings.qaMode) {
        if (trigger && !DemoSettings.qaMode) {
            // Loop forever: 0° → 360° in `durationMillis`, then snap back to 0° and repeat.
            while (true) {
                anim.snapTo(0f)
                anim.animateTo(
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = durationMillis,
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                )
            }
        }
    }
    return if (DemoSettings.qaMode) staticYaw else anim.value
}

/**
 * Position on a horizontal orbit around the origin. Call [toPosition] to get an
 * `(x, y, z)` triple that swings around +Y by [yaw] degrees.
 */
data class OrbitState(val yaw: Float, val radius: Float, val yHeight: Float) {
    fun toPosition(): Triple<Float, Float, Float> {
        val rad = Math.toRadians(yaw.toDouble()).toFloat()
        return Triple(
            /* x = */ sin(rad) * radius,
            /* y = */ yHeight,
            /* z = */ cos(rad) * radius,
        )
    }
}

/**
 * A [CameraGestureDetector.CameraManipulator] that orbits the camera around [target]
 * while idle, then hands control off to a stock [DefaultCameraManipulator] the moment
 * the user touches the viewport — so the model stays fixed in world space (lights and
 * reflections hit the same surface every frame) instead of spinning under the camera.
 *
 * This is the "camera moves, object stays" counterpart to [rememberPausableHeroYaw]:
 * use it when a demo is *about* the object itself (hero showcase, PBR lighting,
 * environment comparison) so the viewer sees the model from different angles without
 * the model rotating through its own light setup.
 *
 * On first gesture the manipulator captures the pose on screen as the new
 * [DefaultCameraManipulator.eyePosition], so there's no snap — the user's first
 * drag continues from exactly where the idle orbit left off.
 *
 * ### Auto-orbit resume after idle (#2225, #3642)
 *
 * Once the user releases a grab/scroll the manipulator stays in user-control mode for
 * [resumeAfterMillis] of inactivity, then gives the camera back to the idle orbit **from the
 * pose on screen**: the user's framing is measured against the authored one
 * ([OrbitFramingOffset]) and the orbit carries on underneath it. [resume] picks what happens to
 * that offset next — kept for good ([HeroOrbitResume.KeepUserFraming], the default) or eased
 * away over [resumeBlendMillis] ([HeroOrbitResume.ReturnToAuthoredPath]).
 *
 * It used to simply drop the user-control manipulator, which put the camera back on the
 * authored pose within one frame. That cut is what #3642 reports on every demo sharing this
 * class, and what #3640 reads as "the camera resets when I flip a switch": the switch is just
 * what the user is reaching for three seconds after framing the subject.
 *
 * Set [resumeAfterMillis] to `0L` or negative to disable the resume — the manipulator then
 * stays in user control forever after the first touch (legacy behaviour).
 */
class HeroOrbitCameraManipulator(
    private val yawProvider: () -> Float,
    private val radius: Float,
    private val yHeight: Float,
    private val target: Position,
    private val resumeAfterMillis: Long = 3_000L,
    /**
     * Optional live override of [radius], read once per frame. A demo that animates the
     * framing — zooming onto a picked object and back out (#3609) — passes the animation's
     * current value here instead of rebuilding the manipulator, which would drop the
     * user-control fallback mid-gesture.
     */
    private val radiusProvider: (() -> Float)? = null,
    /** Optional live override of [target], same contract as [radiusProvider]. */
    private val targetProvider: (() -> Position)? = null,
    /**
     * Optional live override of [yHeight], same contract as [radiusProvider].
     *
     * A demo that flies the camera onto a subject and then hands the frame over to another
     * camera (#3624) animates this so the flight lands on the *elevation* the next camera
     * uses, not only on its distance: an eye that arrives level with the subject and is then
     * replaced by one tilted down cuts to a different angle at the last frame.
     */
    private val yHeightProvider: (() -> Float)? = null,
    /** What becomes of the user's framing once the idle orbit has the camera back. */
    private val resume: HeroOrbitResume = HeroOrbitResume.KeepUserFraming,
    /** How long [HeroOrbitResume.ReturnToAuthoredPath] takes to ease back, in milliseconds. */
    private val resumeBlendMillis: Long = DEFAULT_RESUME_BLEND_MILLIS,
    /**
     * Floor of the polar-angle clamp [userControlTransform] applies to a user drag, passed
     * straight through to [clampOrbitEyePitch]. The library default (just off the top pole)
     * is right for a scene with nothing to hide near it; a demo with a floor plane close to
     * [target] (#3794) tightens this instead.
     */
    private val minPolarDegrees: Float = DEFAULT_MIN_ORBIT_POLAR_DEGREES,
    /** Ceiling of the same clamp — see [minPolarDegrees]. */
    private val maxPolarDegrees: Float = DEFAULT_MAX_ORBIT_POLAR_DEGREES,
    /** Monotonic clock, in nanoseconds. The JVM tests drive it by hand. */
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * The turntable behind [yawProvider], when the manipulator is to drive it itself: [update]
     * then advances it once per rendered frame, towards [spinDegreesPerSecond], and only while
     * the idle orbit has the camera. One writer, one clock — and a speed that eases in at open,
     * after a hand-back, and out on pause, where a looping tween cut from rest to full speed in
     * a frame. `null` leaves the yaw entirely to [yawProvider] (a choreographed sweep).
     */
    private val spin: OrbitSpin? = null,
    /** Goal speed of [spin], read every frame; `0` pauses it where it stands. */
    private val spinDegreesPerSecond: () -> Float = { 0f },
    /**
     * Builds the manipulator the user drives, from the eye and pivot on screen. Injectable
     * because the stock one owns a native Filament `Manipulator`, which a JVM test cannot load.
     */
    private val userControlFactory: (
        eye: Position,
        pivot: Position,
    ) -> io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator = { eye, pivot ->
        io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
            eyePosition = eye,
            targetPosition = pivot,
        )
    },
) : io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator {
    private var fallback: io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator? = null

    /** The pivot [fallback] was built around — the stock manipulator does not publish its own. */
    private var fallbackPivot: Position = target
    private var viewportW = 1
    private var viewportH = 1

    /**
     * Timestamp (from [nanoTime]) when the last user gesture released, or `0L`
     * if the user is still actively dragging / scrolling (or has never touched yet).
     * Used by [settle] to know when to give the camera back to the auto-orbit.
     */
    private var grabEndTimeNanos: Long = 0L

    /**
     * The user's framing, carried by the idle orbit after the hand-back. Empty while the camera
     * is on the authored path — the state every demo starts in, and the only one QA mode sees.
     */
    private val carried = CarriedFraming(nanoTime)

    fun isPaused(): Boolean = fallback != null

    private fun currentRadius(): Float = radiusProvider?.invoke() ?: radius

    private fun currentTarget(): Position = targetProvider?.invoke() ?: target

    private fun currentYHeight(): Float = yHeightProvider?.invoke() ?: yHeight

    /**
     * Hand control back to the idle orbit immediately, without waiting out
     * [resumeAfterMillis]. A demo calls this when it starts a camera animation of its own
     * (#3609): the animation drives [radiusProvider] / [targetProvider], which the
     * user-control fallback ignores, so an un-cleared fallback would freeze the camera for
     * the whole animation and the zoom would simply not play.
     *
     * The user's framing is eased away over [blendMillis] **while** that animation plays, so
     * the flight starts from the pose on screen instead of cutting to the authored one first
     * (#3642). Pass the animation's own duration; `0` cuts, for QA mode's instant flights.
     */
    fun resumeAuto(blendMillis: Long = resumeBlendMillis) {
        handBack()
        if (blendMillis <= 0L) {
            dropUserFraming()
        } else if (!carried.isEasing) {
            // An ease already under way keeps its own clock: restarting it from weight 1 would
            // throw the camera back out to the pose it is half-way home from.
            carried.easeBack(blendMillis)
        }
    }

    private fun authoredEye(): Position {
        val rad = Math.toRadians(yawProvider().toDouble()).toFloat()
        val radius = currentRadius()
        val target = currentTarget()
        return Position(
            x = sin(rad) * radius + target.x,
            y = target.y + currentYHeight(),
            z = cos(rad) * radius + target.z,
        )
    }

    private fun authoredFraming(): OrbitFraming = authoredOrbitFraming(
        yawDegrees = yawProvider(),
        radius = currentRadius(),
        height = currentYHeight(),
        target = currentTarget(),
    )

    private fun orbitTransform(): io.github.sceneview.math.Transform {
        // `null` on the bare authored path, which keeps the eye formula it always had rather
        // than going through OrbitFraming: a round trip through atan2 / sqrt would move every
        // golden by a float's last bit for no visible gain.
        val framing = carried.over(::authoredFraming)
        val mat = dev.romainguy.kotlin.math.lookAt(
            eye = framing?.eye() ?: authoredEye(),
            target = framing?.pivot ?: currentTarget(),
            up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
        )
        return io.github.sceneview.math.Transform(mat)
    }

    private fun ensureFallback() {
        if (fallback == null) {
            // Capture the pose on screen — the authored orbit, the user's framing it carries, or
            // wherever an ease back has got to — as the manipulator's home, so the hand-off is
            // seamless: the first drag begins exactly where the idle camera stood.
            val framing = carried.over(::authoredFraming)
            val pivot = framing?.pivot ?: currentTarget()
            fallbackPivot = pivot
            fallback = userControlFactory(framing?.eye() ?: authoredEye(), pivot)
                .also { it.setViewport(viewportW, viewportH) }
            carried.clear()
        }
        // A new gesture is starting — clear the "idle since" stamp so the resume timer
        // doesn't fire mid-drag.
        grabEndTimeNanos = 0L
    }

    /**
     * Gives the camera back to the idle orbit **on the pose it shows**: what the user changed is
     * kept as an offset from the authored framing, so the next frame draws the same picture.
     */
    private fun handBack() {
        if (fallback == null) return
        val shown = userControlTransform()
        val eye = shown.position
        // A camera looks down its own -Z.
        val back = shown.z
        val depth = sqrt(
            (eye.x - fallbackPivot.x) * (eye.x - fallbackPivot.x) +
                (eye.y - fallbackPivot.y) * (eye.y - fallbackPivot.y) +
                (eye.z - fallbackPivot.z) * (eye.z - fallbackPivot.z),
        )
        val pivot = lookPoint(eye, Position(-back.x, -back.y, -back.z), depth)
        carried.hold(orbitFramingOffset(orbitFramingOf(eye, pivot), authoredFraming()))
        fallback = null
        grabEndTimeNanos = 0L
    }

    /** Back on the bare authored path, this frame. Only ever used where nobody can see the cut. */
    private fun dropUserFraming() {
        fallback = null
        grabEndTimeNanos = 0L
        carried.clear()
    }

    /** Runs the idle timer: past [resumeAfterMillis], the orbit gets the camera back. */
    private fun settle() {
        // `resumeAfterMillis <= 0L` disables the resume — legacy behaviour where the user
        // keeps the camera for good after the first touch.
        if (fallback == null || grabEndTimeNanos == 0L || resumeAfterMillis <= 0L) return
        val idleNanos = nanoTime() - grabEndTimeNanos
        val resumeNanos = resumeAfterMillis * NANOS_PER_MILLI
        if (idleNanos <= resumeNanos) return
        when {
            resume == HeroOrbitResume.KeepUserFraming -> handBack()
            // Far past the deadline means nobody was calling: the manipulator was swapped out
            // (Materials' two cameras) or the app was in the background. The demo that swaps it
            // back in expects the authored pose, and there is no frame to be continuous with.
            idleNanos > resumeNanos + UNWATCHED_MARGIN_NANOS -> dropUserFraming()
            else -> {
                handBack()
                // Nobody timed this one: it takes as long as the way home needs.
                if (resumeBlendMillis > 0L) {
                    carried.easeBack(resumeBlendMillis, paced = true)
                } else {
                    dropUserFraming()
                }
            }
        }
    }

    /**
     * `true` while the camera is **waiting** rather than moving: the countdown [settle] runs
     * between the last gesture and the hand-back, and the ease that carries the user's framing
     * home afterwards.
     *
     * Both advance from [update] / [getTransform], so both only exist while the render loop
     * runs — and under [io.github.sceneview.FrameRatePolicy.OnDemand] the loop parks about half a
     * second after the camera stops moving, which is well inside the three seconds
     * [resumeAfterMillis] asks for. Without this the loop would park on the user's pose and the
     * idle orbit would never come back: the auto-orbit of #3700 would simply stop existing under
     * the new default. Worse, whatever woke the loop minutes later would find the deadline missed
     * by more than `UNWATCHED_MARGIN_NANOS` and cut straight to the authored pose — the very jump
     * #3700 removed.
     *
     * It goes `false` as soon as the hand-back has happened and the ease has landed, so a screen
     * the user never touches still parks: the cost is the three seconds after a gesture, on a
     * screen whose turntable is about to render continuously anyway.
     */
    override val isFrameActive: Boolean
        get() = isResumePending || carried.isEasing

    /** The [settle] countdown is armed and has not fired yet. */
    private val isResumePending: Boolean
        get() = fallback != null && grabEndTimeNanos != 0L && resumeAfterMillis > 0L

    override fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        fallback?.setViewport(viewportW, viewportH)
    }

    override fun getTransform(): io.github.sceneview.math.Transform {
        settle()
        return if (fallback != null) userControlTransform() else orbitTransform()
    }

    private fun userControlTransform(): io.github.sceneview.math.Transform {
        val fb = fallback ?: return orbitTransform()
        // While the user is dragging (or in the post-drag resume window) the camera
        // pose comes from the stock Filament orbit manipulator, which does NOT clamp
        // its polar angle — a drag straight over the top/bottom of the model carries
        // the eye onto the orbit pole, the lookAt's fixed world-up collapses, and the
        // model snaps fully upside-down (#2487, Pixel 9 review). Re-derive the eye from
        // the manipulator's transform, clamp its pitch just shy of the poles, and
        // re-aim it at the orbit pivot so the flip can never happen. The pivot is the one
        // the fallback was BUILT around: once the idle orbit carries the user's framing,
        // that is no longer always the authored target.
        val transform = fb.getTransform()
        val eye = transform.position
        val clampedEye = clampOrbitEyePitch(eye, fallbackPivot, minPolarDegrees, maxPolarDegrees)
        if (clampedEye == eye) return transform
        val mat = dev.romainguy.kotlin.math.lookAt(
            eye = clampedEye,
            target = fallbackPivot,
            up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
        )
        return io.github.sceneview.math.Transform(mat)
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        ensureFallback()
        fallback?.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) {
        fallback?.grabUpdate(x, y)
    }

    override fun grabEnd() {
        fallback?.grabEnd()
        // Mark the moment the user released — `settle` watches this timestamp and gives the
        // camera back after `resumeAfterMillis`, restoring the auto-orbit. The detector also
        // sends this for a plain tap, which never began a grab: nothing to time then.
        if (fallback != null) grabEndTimeNanos = nanoTime()
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        ensureFallback()
        fallback?.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        fallback?.scrollUpdate(x, y, prevSeparation, currSeparation)
    }

    override fun scrollEnd() {
        fallback?.scrollEnd()
        if (fallback != null) grabEndTimeNanos = nanoTime()
    }

    /**
     * Double-tap to zoom (#3641). The interface's default is a no-op, so until this override
     * existed the gesture was dead on every screen using this manipulator. The zoom is the
     * stock manipulator's own — same factor, same ease, same toggle — started from the pose on
     * screen like any other gesture, and the idle orbit then resumes from wherever it lands.
     */
    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        ensureFallback()
        fallback?.doubleTapZoom(x, y, zoomIn)
        grabEndTimeNanos = nanoTime()
    }

    override fun update(deltaTime: Float) {
        fallback?.update(deltaTime)
        settle()
        // While the user holds the camera the turntable winds down out of sight, so the orbit
        // that gets it back starts from rest instead of lurching off at full speed.
        spin?.advance(deltaTime, if (fallback == null) spinDegreesPerSecond() else 0f)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Long enough to clear a dropped frame or two, short enough that nobody saw the pose. */
        const val UNWATCHED_MARGIN_NANOS = 1_000L * NANOS_PER_MILLI
    }
}

/**
 * Factory for [HeroOrbitCameraManipulator] that wires the idle-orbit yaw to a pausable
 * animator. Returns the manipulator ready to drop into a `SceneView(cameraManipulator = ...)`.
 *
 * In [DemoSettings.qaMode] the yaw is frozen at [staticYaw] so screenshot tests stay stable.
 *
 * ### The yaw never jumps (#3640), and neither does its speed
 *
 * The yaw is an [OrbitSpin] the manipulator advances on the render loop's own clock, with an
 * eased angular speed: the orbit accelerates in at open and after every hand-back from a gesture,
 * and coasts to a stop on pause, instead of cutting between rest and full speed in one frame.
 *
 * [trigger] going `false` **pauses** the turntable and `true` lets it carry on from the same
 * angle. It used to restart from 0° each time, so anything a demo wires into [trigger] — the
 * Orbit switch of the lighting screens, Lines & Paths' Animate, a model chip whose instance
 * goes `null` while the next one loads — threw the camera back to its opening azimuth.
 *
 * ### A new framing is a camera move, not a cut
 *
 * A new framing ([radius], [yHeight], [target]) is still a new manipulator, and so the authored
 * pose: those change when the *subject* does, and a framing the user chose for the previous
 * model means nothing for the next. But `SceneView` never sees that swap: what it is handed is a
 * [ContinuousCameraManipulator] remembered once, which eases from the pose on screen into the
 * new framing. It used to cut there — on the next model of a viewer, when a loaded model's
 * measured bounds replaced the placeholder fit, on a rotation that re-fits the radius.
 *
 * ### Deep-link zoom override (#1571)
 *
 * When [DemoSettings.cameraDistance] is non-null — set from the `camera_distance` intent
 * extra (`adb --ef` or a Maestro launch argument, any Bundle type — #2652) or the
 * `sceneview://demo/<id>?cameraDistance=<f>` deep link — it replaces
 * [radius] as the orbit distance, so the device-QA harness can launch a demo at a near or
 * far framing without a pinch gesture (Maestro has none). When `null` the caller's [radius]
 * (typically per-demo auto-fit) is used unchanged, so showcase behaviour is unaffected.
 */
@Composable
fun rememberHeroOrbitCameraManipulator(
    trigger: Boolean,
    radius: Float = 2.5f,
    yHeight: Float = 0.5f,
    durationMillis: Int = 20_000,
    staticYaw: Float = 45f,
    target: Position = Position(0f, 0f, 0f),
    resumeAfterMillis: Long = 3_000L,
    resume: HeroOrbitResume = HeroOrbitResume.KeepUserFraming,
    contentShown: Boolean = true,
    /**
     * User-drag pitch clamp, forwarded to [HeroOrbitCameraManipulator]. The defaults only keep
     * the eye off the orbit poles; a demo whose floor sits close to [target] (#3794) passes a
     * tighter [maxPolarDegrees] so an upward drag cannot carry the camera under it.
     */
    minPolarDegrees: Float = DEFAULT_MIN_ORBIT_POLAR_DEGREES,
    maxPolarDegrees: Float = DEFAULT_MAX_ORBIT_POLAR_DEGREES,
): io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator {
    val continuity = rememberContinuousCameraManipulator(pivot = target)
    // The turntable outlives the manipulator: a new framing rebuilds the latter, and the yaw —
    // and its speed — carry on across the rebuild.
    val spin = androidx.compose.runtime.remember { OrbitSpin() }
    val running = androidx.compose.runtime.rememberUpdatedState(trigger)
    val turnMillis = androidx.compose.runtime.rememberUpdatedState(durationMillis)
    // Deep-link zoom override (#1571): a non-null DemoSettings.cameraDistance wins over the
    // caller's auto-fit `radius`. Reading the Compose state here (not inside remember{})
    // keeps it a recomposition input; it is also a remember{} key so the manipulator is
    // rebuilt with the new orbit distance if the zoom changes (e.g. a warm-start onNewIntent).
    val effectiveRadius = DemoSettings.cameraDistance ?: radius
    val orbit = androidx.compose.runtime.remember(
        effectiveRadius, yHeight, target, resumeAfterMillis, resume, minPolarDegrees, maxPolarDegrees,
    ) {
        HeroOrbitCameraManipulator(
            yawProvider = { if (DemoSettings.qaMode) staticYaw else spin.yawDegrees },
            radius = effectiveRadius,
            yHeight = yHeight,
            target = target,
            resumeAfterMillis = resumeAfterMillis,
            resume = resume,
            spin = spin,
            spinDegreesPerSecond = {
                if (running.value && !DemoSettings.qaMode) {
                    OrbitSpin.degreesPerSecond(turnMillis.value)
                } else {
                    0f
                }
            },
            minPolarDegrees = minPolarDegrees,
            maxPolarDegrees = maxPolarDegrees,
        )
    }
    return continuity.driving(orbit, contentShown = contentShown)
}

/**
 * Fraction of the frame a demo's subject should span. `0.92` is the value the Model Viewer's own
 * framing (`DemoMath.VIEWER_HORIZONTAL_FILL`) settled on after on-device QA: on a portrait phone
 * the *horizontal* axis is what binds an orbiting subject, and anything tighter than ~0.9 there
 * reads as a small object floating in a large empty frame. Expressed as a *padding* fraction to
 * [io.github.sceneview.fitDistanceForBounds]: `1 / 0.92 - 1 ≈ 0.087`.
 */
const val DEMO_FRAMING_FILL: Float = 0.92f

/**
 * Orbit radius that frames a subject of the given world-space extents on **this** viewport
 * (#3426).
 *
 * Every non-AR sample used to carry a hand-tuned literal — `radius = 2.0f`, `2.2f`, `2.4f`,
 * `Position(0f, 0.1f, 2f)`, or nothing at all, which left the library's stock 2.78 m pose. None of
 * them looked at the aspect ratio, so the same number that framed a subject on a 16:9 phone either
 * cropped it or shrank it to a quarter of the frame elsewhere, and the numbers had drifted apart
 * between demos showing the *same* subject at the *same* scale. This is the one place that answers
 * "how far back" now; the per-demo constants that survive are the ones describing the *content*,
 * not the camera.
 *
 * The aspect comes from the device configuration rather than a `BoxWithConstraints`, because the
 * demos that need this render their scene edge-to-edge and most are not inside one.
 *
 * @param extentX          Content width, world units (full extent, not half).
 * @param extentY          Content height, world units.
 * @param extentZ          Content depth, world units.
 * @param elevationDegrees Camera elevation above the content centre — pass the same pitch the
 *                         demo's camera uses, since projected height shrinks as the camera rises.
 * @param fill             Fraction of the binding axis the subject should span.
 * @param azimuthInvariant `true` when the camera orbits (auto-orbit or user drag) and the subject
 *                         must never clip as it turns broadside; `false` for a fixed head-on shot.
 * @param focalLengthMm    Lens the demo's camera uses. SceneView's default is 28 mm.
 */
@Composable
fun rememberFitOrbitRadius(
    extentX: Float,
    extentY: Float,
    extentZ: Float,
    elevationDegrees: Float = 0f,
    fill: Float = DEMO_FRAMING_FILL,
    azimuthInvariant: Boolean = true,
    focalLengthMm: Double = 28.0,
): Float {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val aspect = androidx.compose.runtime.remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        val w = configuration.screenWidthDp.toFloat()
        val h = configuration.screenHeightDp.toFloat()
        if (w > 0f && h > 0f) w / h else 0.47f
    }
    return androidx.compose.runtime.remember(
        extentX, extentY, extentZ, elevationDegrees, fill, azimuthInvariant, aspect,
    ) {
        fitOrbitRadius(
            extentX, extentY, extentZ, aspect, elevationDegrees, fill, azimuthInvariant,
            focalLengthMm,
        )
    }
}

/**
 * Elevation, in degrees above the target, of the eye the library's `orbitRadius` overloads place
 * the camera at — `io.github.sceneview.gesture.DEFAULT_ORBIT_DIRECTION` is `(0, 0.4, 2.75)`
 * normalised, i.e. `asin(0.4 / 2.7789) ≈ 8.27°`. Pass it to [rememberFitOrbitRadius] whenever the
 * result feeds `rememberCameraManipulator(orbitRadius = …)`, so the fit knows the pose it is
 * fitting for.
 */
const val DEFAULT_ORBIT_ELEVATION_DEGREES: Float = 8.27f

/**
 * Non-Compose form of [rememberFitOrbitRadius] — the pure function, so it can be unit-tested on
 * the JVM and called from a `remember` block that already knows its viewport.
 */
fun fitOrbitRadius(
    extentX: Float,
    extentY: Float,
    extentZ: Float,
    aspect: Float,
    elevationDegrees: Float = 0f,
    fill: Float = DEMO_FRAMING_FILL,
    azimuthInvariant: Boolean = true,
    focalLengthMm: Double = 28.0,
): Float {
    val safeFill = if (fill.isFinite() && fill > 0f) fill else DEMO_FRAMING_FILL
    val radius = io.github.sceneview.fitDistanceForBounds(
        bounds = io.github.sceneview.Aabb(
            halfExtent = Position(
                x = maxOf(extentX, 0f) / 2f,
                y = maxOf(extentY, 0f) / 2f,
                z = maxOf(extentZ, 0f) / 2f,
            )
        ),
        verticalFovDegrees = io.github.sceneview.verticalFovDegreesForFocalLength(focalLengthMm),
        aspect = aspect.toDouble(),
        padding = 1f / safeFill - 1f,
        elevationDegrees = elevationDegrees.toDouble(),
        azimuthInvariant = azimuthInvariant,
    )
    // A degenerate content box must not put the camera on the subject; fall back to the library's
    // stock 2.78 m pose rather than 0.
    return if (radius.isFinite() && radius > 0f) radius.coerceIn(0.2f, 900f) else 2.78f
}

/** Closest the Model Viewer's pinch may take the camera, as a fraction of the auto-fit distance. */
const val VIEWER_MIN_ZOOM_FACTOR: Float = 0.25f

/** Furthest the Model Viewer's pinch may take the camera, as a multiple of the auto-fit distance. */
const val VIEWER_MAX_ZOOM_FACTOR: Float = 4f

/** Default polar-angle floor (degrees from world +Y) used by [clampOrbitEyePitch]. */
internal const val DEFAULT_MIN_ORBIT_POLAR_DEGREES: Float = 1f

/** Default polar-angle ceiling (degrees from world +Y) used by [clampOrbitEyePitch]. */
internal const val DEFAULT_MAX_ORBIT_POLAR_DEGREES: Float = 179f

/**
 * Clamps an orbit camera [eye] so its **polar angle** — the angle between the
 * `target → eye` direction and world-up `+Y` — stays inside
 * `[minPolarDegrees, maxPolarDegrees]`, keeping the orbit **radius** and
 * **azimuth** unchanged. Returns the original [eye] unchanged when it is already
 * in range (the common case), when it coincides with [target] (degenerate
 * radius), or when any component is non-finite.
 *
 * ### Why (#2487)
 *
 * The hero viewer's user-drag path delegates to Filament's `ORBIT`-mode
 * `Manipulator`, which does not clamp its polar angle. A drag straight over the
 * top (or bottom) of the model carries the eye onto the orbit pole; the
 * `lookAt(eye, target, up = +Y)` that builds the view matrix then has its
 * forward axis parallel to `up`, the `right = cross(up, forward)` collapses to
 * zero, and the model snaps fully **upside-down** — the orbit / gimbal flip the
 * Pixel 9 review reported on the Explore 3D viewer. Clamping just **shy** of the
 * poles (default `[1°, 179°]`) lets the eye reach a near-top-down / near-bottom-up
 * view but never the exact singularity, so `lookAt` always stays well-defined.
 *
 * @param eye    orbit eye world position to clamp.
 * @param target orbit target the eye looks at / pivots around.
 */
internal fun clampOrbitEyePitch(
    eye: Position,
    target: Position,
    minPolarDegrees: Float = DEFAULT_MIN_ORBIT_POLAR_DEGREES,
    maxPolarDegrees: Float = DEFAULT_MAX_ORBIT_POLAR_DEGREES,
): Position {
    val dx = eye.x - target.x
    val dy = eye.y - target.y
    val dz = eye.z - target.z
    if (!dx.isFinite() || !dy.isFinite() || !dz.isFinite()) return eye

    val radius = sqrt(dx * dx + dy * dy + dz * dz)
    // Degenerate orbit (eye == target): no direction to clamp.
    if (radius <= 1e-6f) return eye

    // Polar angle from world +Y, in radians, in [0, π].
    val polar = acos((dy / radius).coerceIn(-1f, 1f))
    val minRad = Math.toRadians(minPolarDegrees.toDouble()).toFloat()
    val maxRad = Math.toRadians(maxPolarDegrees.toDouble()).toFloat()
    val clampedPolar = polar.coerceIn(minRad, maxRad)
    // Already within range — return the eye untouched (fast path).
    if (clampedPolar == polar) return eye

    val horizontal = sqrt(dx * dx + dz * dz)
    val newDy = radius * cos(clampedPolar)
    val newHorizontal = radius * sin(clampedPolar)
    // Preserve azimuth from the horizontal projection of the original direction.
    // If the eye sat exactly on the polar axis there is no azimuth to preserve;
    // fall back to +Z so the eye still lifts off the singularity.
    val hx: Float
    val hz: Float
    if (horizontal <= 1e-6f) {
        hx = 0f
        hz = 1f
    } else {
        hx = dx / horizontal
        hz = dz / horizontal
    }
    return Position(
        x = target.x + hx * newHorizontal,
        y = target.y + newDy,
        z = target.z + hz * newHorizontal,
    )
}

/**
 * Absolute yaw distance, in degrees, between an orbit eye and [referenceYawDegrees] around
 * [target] — the same convention [HeroOrbitCameraManipulator]'s authored path uses (`x =
 * sin(yaw) * radius`, `z = cos(yaw) * radius`, both relative to [target]). Returns `0` when the
 * eye sits directly above/below [target] (no azimuth to measure) or when any component is
 * non-finite. The counterpart read (not a clamp) to [clampOrbitEyePitch] — see
 * [orbitLabelFadeAlpha] for why #3802 wants a measurement here instead of a bound.
 *
 * @param eye                  orbit eye world position to measure.
 * @param target               orbit target the eye looks at / pivots around.
 * @param referenceYawDegrees  yaw, in degrees, the deviation is measured from (the authored /
 *                             front-on framing — usually `0`).
 */
internal fun orbitYawDeviationDegrees(
    eye: Position,
    target: Position,
    referenceYawDegrees: Float,
): Float {
    val dx = eye.x - target.x
    val dz = eye.z - target.z
    if (!dx.isFinite() || !dz.isFinite()) return 0f

    val horizontal = sqrt(dx * dx + dz * dz)
    if (horizontal <= 1e-6f) return 0f

    val yawDegrees = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
    // Delta from the reference, wrapped into [-180, 180) so a reference near +/-180 does not
    // read a false, near-360-degree deviation.
    var delta = (yawDegrees - referenceYawDegrees) % 360f
    if (delta < -180f) delta += 360f
    if (delta >= 180f) delta -= 360f
    return abs(delta)
}

/** Yaw deviation, in degrees, below which [orbitLabelFadeAlpha] returns full opacity. */
internal const val DEFAULT_LABEL_FADE_START_DEGREES: Float = 25f

/** Yaw deviation, in degrees, at and beyond which [orbitLabelFadeAlpha] returns zero. */
internal const val DEFAULT_LABEL_FADE_END_DEGREES: Float = 45f

/**
 * Opacity for a caption anchored to a subject on a flat orbit wall, as a function of
 * [deviationDegrees] — [orbitYawDeviationDegrees] between the camera and the wall's front-on
 * framing (#3802).
 *
 * A flat wall of captioned subjects (Materials' 3×3 sphere grid, Contact Shadow Preview's box
 * pair) is captioned for a roughly head-on view: drag the camera towards broadside and
 * perspective foreshortening collapses the gap between neighbours' screen-space positions
 * faster than a fixed-width or fixed-position caption accounts for, so adjacent labels overlap
 * and merge into unreadable text (Materials) or merge into one ("NShadow", Contact Shadow
 * Preview). The orbit itself has to stay completely free — these demos are calibrated against
 * Sketchfab/Polycam, where nothing ever stops the drag, and Materials exists specifically to
 * turn a reflection around — so the fix reads the angle instead of bounding it: full opacity
 * for [fullyVisibleDegrees] either side of front-on, easing smoothly to fully transparent by
 * [fullyHiddenDegrees], and back the moment the drag returns.
 *
 * @param deviationDegrees    yaw distance from the front-on framing, in degrees; see
 *                            [orbitYawDeviationDegrees].
 * @param fullyVisibleDegrees deviation up to which the caption is fully opaque.
 * @param fullyHiddenDegrees  deviation at and beyond which the caption is fully transparent.
 */
internal fun orbitLabelFadeAlpha(
    deviationDegrees: Float,
    fullyVisibleDegrees: Float = DEFAULT_LABEL_FADE_START_DEGREES,
    fullyHiddenDegrees: Float = DEFAULT_LABEL_FADE_END_DEGREES,
): Float {
    if (deviationDegrees <= fullyVisibleDegrees) return 1f
    if (deviationDegrees >= fullyHiddenDegrees) return 0f
    val t = (deviationDegrees - fullyVisibleDegrees) / (fullyHiddenDegrees - fullyVisibleDegrees)
    // Smoothstep: eases in/out at both ends instead of fading at a constant, visibly linear rate.
    val eased = t * t * (3f - 2f * t)
    return 1f - eased
}

/**
 * A [io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator] that flies
 * the camera **in** to its resting pose when the model arrives, then behaves exactly
 * like the stock `DefaultCameraManipulator` (#3406).
 *
 * At `progress() == 0` the eye sits [startDistanceScale]× further from [target], swung
 * [startYawDegrees] around world +Y and lifted by [startLiftFraction] of the orbit
 * radius; at `progress() == 1` it is exactly [eye]. The caller animates that progress —
 * one `tween(duration, ease-expressive)` is the whole effect — so the model is not
 * merely displayed, it is arrived at. The model itself never moves, which is what keeps
 * lights and reflections landing on the surfaces the resting frame will show.
 *
 * The first touch hands off to a stock manipulator seeded with the eye the flight had
 * reached, so interrupting the entrance is seamless rather than a snap — the same
 * hand-off [HeroOrbitCameraManipulator] does, for the same reason. There is no resume:
 * once the user has taken the camera it is theirs, and replaying a fly-in under their
 * fingers would be a bug, not a flourish.
 *
 * In [DemoSettings.qaMode] the caller pins progress at `1f`: a screenshot taken
 * mid-flight is a different framing every run.
 */
class EntranceCameraManipulator(
    private val eye: () -> Position,
    private val target: () -> Position,
    private val progress: () -> Float,
    private val fitDistance: () -> Float = { 1f },
    private val distanceOverride: () -> Float? = { null },
    private val onDistanceChange: (Float) -> Unit = {},
    private val startDistanceScale: Float = 1.55f,
    private val startYawDegrees: Float = 24f,
    private val startLiftFraction: Float = 0.22f,
) : io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator {
    private var fallback: io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator? =
        null
    private var viewportW = 1
    private var viewportH = 1

    /**
     * Set by [beginRecenterFlight] — non-null while a recenter flight is interpolating from the
     * pose the camera was actually showing back to [eye]. `null` means "no captured start", which
     * is the cold-open case: the synthetic swing/lift geometry below is the whole point of a first
     * arrival, but replaying it for Recenter ignored wherever the user had orbited to and snapped
     * the flight's start to that same swung-off-axis pose every time (#3622).
     */
    private var flightStartEye: Position? = null

    /**
     * Starts a flight back to [eye] from wherever the camera is on screen right now, instead of
     * the cold-open's synthetic swing. Called by the caller's Recenter action, which then resets
     * `progress` to `0` and animates it back to `1` — capturing here, before that reset, is what
     * lets [currentEye] read the pose the user actually left the camera at rather than the frozen
     * post-drag pose [fallback] would otherwise keep returning (#3622).
     */
    fun beginRecenterFlight() {
        flightStartEye = getTransform().position
        fallback = null
    }

    /** Eye position for the current [progress] — [eye] itself once the flight is over. */
    private fun currentEye(): Position {
        val eye = eye()
        val target = target()
        val p = progress().coerceIn(0f, 1f)
        if (p >= 1f) return eye
        val remaining = 1f - p
        flightStartEye?.let { start ->
            return Position(
                x = eye.x + (start.x - eye.x) * remaining,
                y = eye.y + (start.y - eye.y) * remaining,
                z = eye.z + (start.z - eye.z) * remaining,
            )
        }
        val dx = eye.x - target.x
        val dy = eye.y - target.y
        val dz = eye.z - target.z
        val radius = sqrt(dx * dx + dy * dy + dz * dz)
        // Degenerate framing: nothing to fly along, sit at the resting pose.
        if (!radius.isFinite() || radius <= 1e-6f) return eye
        // Swing the offset around world +Y, push it out along itself, and lift it.
        val yaw = Math.toRadians((startYawDegrees * remaining).toDouble()).toFloat()
        val c = cos(yaw)
        val s = sin(yaw)
        val scale = 1f + (startDistanceScale - 1f) * remaining
        val lift = radius * startLiftFraction * remaining
        return Position(
            x = target.x + (dx * c + dz * s) * scale,
            y = target.y + (dy + lift) * scale,
            z = target.z + (dz * c - dx * s) * scale,
        )
    }

    private fun flightTransform(): io.github.sceneview.math.Transform = aimedAt(currentEye())

    /** `lookAt` from [from] to the live target, with world +Y up. */
    private fun aimedAt(from: Position): io.github.sceneview.math.Transform =
        io.github.sceneview.math.Transform(
            dev.romainguy.kotlin.math.lookAt(
                eye = from,
                target = target(),
                up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
            )
        )

    /**
     * Re-places [from] at [distance] from the live target **along the current view ray** (#3403).
     * Changing the zoom therefore never disturbs the yaw and pitch the user dragged to — which is
     * exactly what rebuilding the manipulator on every slider step used to do.
     */
    private fun atDistance(from: Position, distance: Float): Position {
        val t = target()
        val dx = from.x - t.x
        val dy = from.y - t.y
        val dz = from.z - t.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!length.isFinite() || length <= 1e-5f || !distance.isFinite() || distance <= 0f) return from
        val k = distance / length
        return Position(t.x + dx * k, t.y + dy * k, t.z + dz * k)
    }

    /** Camera-to-target distance on screen right now. */
    private fun effectiveDistance(): Float {
        distanceOverride()?.takeIf { it.isFinite() && it > 0f }?.let { return it }
        val t = target()
        val e = fallback?.getTransform()?.position ?: currentEye()
        val d = sqrt(
            (e.x - t.x) * (e.x - t.x) + (e.y - t.y) * (e.y - t.y) + (e.z - t.z) * (e.z - t.z)
        )
        return if (d.isFinite() && d > 0f) d else fitDistance()
    }

    private fun ensureFallback() {
        if (fallback == null) {
            // Hand off at the exact pose on screen — flight position and zoom override included —
            // so the first drag continues from where the user was looking, with no snap.
            fallback = io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
                eyePosition = getTransform().position,
                targetPosition = target(),
            ).also { it.setViewport(viewportW, viewportH) }
        }
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        fallback?.setViewport(viewportW, viewportH)
    }

    override fun getTransform(): io.github.sceneview.math.Transform {
        val override = distanceOverride()?.takeIf { it.isFinite() && it > 0f }
        val fb = fallback
            ?: return if (override == null) flightTransform()
            else aimedAt(atDistance(currentEye(), override))
        // Same polar clamp as the hero orbit (#2487): the stock manipulator lets a drag
        // carry the eye onto the orbit pole, where `lookAt`'s fixed world-up collapses
        // and the model flips upside-down.
        val eyeNow = clampOrbitEyePitch(fb.getTransform().position, target())
        return aimedAt(if (override == null) eyeNow else atDistance(eyeNow, override))
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        // A new touch owns the camera: drop any double-tap zoom still in flight (#3608).
        zoomAnimationDuration = 0f
        ensureFallback()
        fallback?.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) {
        fallback?.grabUpdate(x, y)
    }

    override fun grabEnd() {
        fallback?.grabEnd()
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        zoomAnimationDuration = 0f
        ensureFallback()
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        // The pinch PUBLISHES a distance instead of translating the delegate, so it and the
        // "Camera distance" slider drive the same number (#3403). The step is a fraction of the
        // current distance, so the gesture feels the same on a 5 cm model and a 150 m one, and the
        // clamps are relative to the model's own fitted distance (#3426).
        val fit = fitDistance().takeIf { it.isFinite() && it > 0f } ?: 1f
        onDistanceChange(
            io.github.sceneview.gesture.zoomedDistanceForPinch(
                distance = effectiveDistance(),
                prevSeparation = prevSeparation,
                currSeparation = currSeparation,
                minDistance = fit * VIEWER_MIN_ZOOM_FACTOR,
                maxDistance = fit * VIEWER_MAX_ZOOM_FACTOR,
            )
        )
    }

    override fun scrollEnd() {
        fallback?.scrollEnd()
    }

    // ── Double-tap zoom (#3608) ──────────────────────────────────────────────────────────────────
    //
    // The SDK's built-in double-tap zoom lives in `DefaultCameraManipulator` and dollies the
    // Filament manipulator directly. This class does not delegate its zoom — a pinch *publishes* a
    // distance so the gesture and the "Camera distance" slider stay one number (see
    // `scrollUpdate`) — so the built-in default no-ops here and the gesture has to be implemented
    // against the same published distance, with the same clamps the pinch uses.
    private var zoomAnimationStart = 0f
    private var zoomAnimationTarget = 0f
    private var zoomAnimationElapsed = 0f
    private var zoomAnimationDuration = 0f

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        ensureFallback()
        val fit = fitDistance().takeIf { it.isFinite() && it > 0f } ?: 1f
        val start = effectiveDistance()
        val target = io.github.sceneview.gesture.zoomedDistanceForDoubleTap(
            distance = start,
            homeDistance = fit,
            zoomIn = zoomIn,
            minDistance = fit * VIEWER_MIN_ZOOM_FACTOR,
            maxDistance = fit * VIEWER_MAX_ZOOM_FACTOR,
        )
        if (target == start) return
        zoomAnimationStart = start
        zoomAnimationTarget = target
        zoomAnimationElapsed = 0f
        zoomAnimationDuration = io.github.sceneview.gesture.CameraGestureDetector
            .DefaultCameraManipulator.DEFAULT_DOUBLE_TAP_ZOOM_DURATION_SECONDS
    }

    override fun update(deltaTime: Float) {
        if (zoomAnimationDuration > 0f) {
            zoomAnimationElapsed += deltaTime
            val progress = zoomAnimationElapsed / zoomAnimationDuration
            onDistanceChange(
                io.github.sceneview.gesture.animatedZoomDistance(
                    start = zoomAnimationStart,
                    target = zoomAnimationTarget,
                    progress = progress,
                )
            )
            if (progress >= 1f) zoomAnimationDuration = 0f
        }
        fallback?.update(deltaTime)
    }
}
