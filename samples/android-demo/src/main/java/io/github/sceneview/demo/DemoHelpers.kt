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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sceneview.math.Position
import java.io.File
import kotlin.math.acos
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
fun LoadingScrim(loading: Boolean, label: String = "Loading…") {
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
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
            )
            Text(
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
 * Box(Modifier.fillMaxSize()) {
 *     ARSceneView(
 *         onSessionUpdated = { _, _ -> cameraReady = true /* … */ },
 *         …
 *     ) { … }
 *     ARCameraInitScrim(initializing = !cameraReady)
 * }
 * ```
 *
 * Place it as the last child of the [Box] that wraps the `ARSceneView` so it draws
 * on top of the still-black viewport but below any other status overlays.
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
 */
@Composable
fun ARCameraInitScrim(
    initializing: Boolean,
    label: String = "Starting camera…",
    timeoutMillis: Long = AR_CAMERA_INIT_SCRIM_TIMEOUT_MS,
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
    )
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
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
            )
            Text(
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
 * The one case that still dismisses completely is the QA camera backdrop (#3308): there a
 * synthetic room photo is deliberately rendered behind the viewport and must be visible.
 */
internal fun arCameraInitScrimVisibility(
    initializing: Boolean,
    timedOut: Boolean,
    qaBackdropEnabled: Boolean,
): ArCameraInitScrimVisibility = when {
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
 * can flip the scrim off exactly when the first Filament frame is presented:
 *
 * ```kotlin
 * val firstFrame = rememberFirstFrameState()
 * DemoScaffold(title = …, onBack = onBack, firstFrameRendered = firstFrame.rendered) {
 *     SceneView(onFrame = firstFrame.onFrame, …) { … }
 * }
 * ```
 *
 * @property rendered Read in the scaffold — `false` until the first frame, then `true`.
 * @property onFrame Pass straight to `SceneView(onFrame = …)`. Cheap after the first call.
 */
class FirstFrameState internal constructor(
    private val renderedState: androidx.compose.runtime.MutableState<Boolean>,
) {
    val rendered: androidx.compose.runtime.State<Boolean> get() = renderedState

    val onFrame: (frameTimeNanos: Long) -> Unit = {
        if (!renderedState.value) renderedState.value = true
    }
}

/**
 * Remembers a [FirstFrameState] for wiring the [DemoScaffold] loading scrim to a
 * SceneView's first presented frame. See [FirstFrameState] for the usage pattern.
 */
@Composable
fun rememberFirstFrameState(): FirstFrameState {
    val rendered = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    return androidx.compose.runtime.remember { FirstFrameState(rendered) }
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
 * On first gesture the manipulator captures the current orbit pose as the new
 * [DefaultCameraManipulator.eyePosition], so there's no snap — the user's first
 * drag continues from exactly where the idle orbit left off.
 *
 * ### Auto-orbit resume after idle (#2225)
 *
 * Once the user releases a grab/scroll the manipulator stays in user-control mode for
 * [resumeAfterMillis] of inactivity, then clears the fallback so the idle auto-orbit
 * resumes from the user's last pose (no snap — the next orbit yaw is read from
 * [yawProvider] when [getTransform] falls through to [orbitTransform]). Set
 * [resumeAfterMillis] to `0L` or negative to disable the resume — the manipulator then
 * stays in user control forever after the first touch (legacy behaviour).
 */
class HeroOrbitCameraManipulator(
    private val yawProvider: () -> Float,
    private val radius: Float,
    private val yHeight: Float,
    private val target: Position,
    private val resumeAfterMillis: Long = 3_000L,
) : io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator {
    private var fallback: io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator? =
        null
    private var viewportW = 1
    private var viewportH = 1

    /**
     * Timestamp (from [System.nanoTime]) when the last user gesture released, or `0L`
     * if the user is still actively dragging / scrolling (or has never touched yet).
     * Used by [update] to know when to clear the [fallback] and resume the auto-orbit.
     */
    private var grabEndTimeNanos: Long = 0L

    fun isPaused(): Boolean = fallback != null

    private fun currentEye(): Position {
        val rad = Math.toRadians(yawProvider().toDouble()).toFloat()
        return Position(
            x = sin(rad) * radius + target.x,
            y = target.y + yHeight,
            z = cos(rad) * radius + target.z,
        )
    }

    private fun orbitTransform(): io.github.sceneview.math.Transform {
        val eye = currentEye()
        val mat = dev.romainguy.kotlin.math.lookAt(
            eye = eye,
            target = target,
            up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
        )
        return io.github.sceneview.math.Transform(mat)
    }

    private fun ensureFallback() {
        if (fallback == null) {
            // Capture the current orbit eye as the manipulator's home so the hand-off is
            // seamless — the first drag begins exactly where we stopped orbiting.
            fallback = io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
                eyePosition = currentEye(),
                targetPosition = target,
            ).also { it.setViewport(viewportW, viewportH) }
        }
        // A new gesture is starting — clear the "idle since" stamp so the resume timer
        // doesn't fire mid-drag.
        grabEndTimeNanos = 0L
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        fallback?.setViewport(viewportW, viewportH)
    }

    override fun getTransform(): io.github.sceneview.math.Transform {
        val fb = fallback ?: return orbitTransform()
        // While the user is dragging (or in the post-drag resume window) the camera
        // pose comes from the stock Filament orbit manipulator, which does NOT clamp
        // its polar angle — a drag straight over the top/bottom of the model carries
        // the eye onto the orbit pole, the lookAt's fixed world-up collapses, and the
        // model snaps fully upside-down (#2487, Pixel 9 review). Re-derive the eye from
        // the manipulator's transform, clamp its pitch just shy of the poles, and
        // re-aim it at the (unchanged) orbit target so the flip can never happen. The
        // idle auto-orbit path (`orbitTransform`) sits at a gentle down-tilt well clear
        // of the poles and needs no clamp.
        val transform = fb.getTransform()
        val eye = transform.position
        val clampedEye = clampOrbitEyePitch(eye, target)
        if (clampedEye == eye) return transform
        val mat = dev.romainguy.kotlin.math.lookAt(
            eye = clampedEye,
            target = target,
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
        // Mark the moment the user released — `update` watches this timestamp and
        // clears the fallback after `resumeAfterMillis`, restoring the auto-orbit.
        grabEndTimeNanos = System.nanoTime()
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
        grabEndTimeNanos = System.nanoTime()
    }

    override fun update(deltaTime: Float) {
        fallback?.update(deltaTime)
        // Clear the fallback after `resumeAfterMillis` of post-gesture inactivity so the
        // auto-orbit resumes (#2225). `resumeAfterMillis <= 0L` disables the resume —
        // legacy behaviour where the fallback is never cleared.
        if (fallback != null && grabEndTimeNanos != 0L && resumeAfterMillis > 0L) {
            val idleNs = System.nanoTime() - grabEndTimeNanos
            if (idleNs > resumeAfterMillis * 1_000_000L) {
                fallback = null
                grabEndTimeNanos = 0L
            }
        }
    }
}

/**
 * Factory for [HeroOrbitCameraManipulator] that wires the idle-orbit yaw to a pausable
 * animator. Returns the manipulator ready to drop into a `SceneView(cameraManipulator = ...)`.
 *
 * In [DemoSettings.qaMode] the yaw is frozen at [staticYaw] so screenshot tests stay stable.
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
): HeroOrbitCameraManipulator {
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(trigger, DemoSettings.qaMode) {
        if (trigger && !DemoSettings.qaMode) {
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
    // Deep-link zoom override (#1571): a non-null DemoSettings.cameraDistance wins over the
    // caller's auto-fit `radius`. Reading the Compose state here (not inside remember{})
    // keeps it a recomposition input; it is also a remember{} key so the manipulator is
    // rebuilt with the new orbit distance if the zoom changes (e.g. a warm-start onNewIntent).
    val effectiveRadius = DemoSettings.cameraDistance ?: radius
    return androidx.compose.runtime.remember(effectiveRadius, yHeight, target, resumeAfterMillis) {
        HeroOrbitCameraManipulator(
            yawProvider = { if (DemoSettings.qaMode) staticYaw else anim.value },
            radius = effectiveRadius,
            yHeight = yHeight,
            target = target,
            resumeAfterMillis = resumeAfterMillis,
        )
    }
}

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
