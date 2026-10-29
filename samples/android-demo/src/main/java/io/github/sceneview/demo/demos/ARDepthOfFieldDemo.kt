package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.postprocessing.ARDepthOfFieldOptions
import io.github.sceneview.ar.postprocessing.arDepthOfField
import io.github.sceneview.ar.postprocessing.depthFocusDistance
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModePill
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.CatalogueProductPill
import io.github.sceneview.demo.common.CatalogueProducts
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.rememberCatalogueProduct
import io.github.sceneview.demo.demos.internal.PortraitFocus
import io.github.sceneview.demo.demos.internal.PortraitFocusStatus
import io.github.sceneview.demo.demos.internal.PortraitFocusTarget
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * Portrait focus (`ar-depth-of-field`, #4649) — a phone camera's portrait mode in AR: the
 * sneaker stays sharp while the room behind it blurs with its real distance, and an
 * Original | Portrait switch compares the two.
 *
 * The blur is Filament's native depth-of-field post-pass fed by ARCore environment depth:
 * `arsceneview/`'s depth-occlusion camera material writes the real-world depth into Filament's
 * z-buffer (`camera_stream_depth.mat`), and Filament's DoF samples that same z-buffer, so the
 * camera background blurs by its distance from the focus plane with no extra render pass (#1716).
 *
 * Focus works like the camera app:
 *  - **Subject** (default): the focus distance follows the sneaker every tracked frame — the
 *    distance from the lens to the sneaker, not ARCore's depth under it, which is the floor
 *    behind a virtual object. Walk around it and it stays sharp.
 *  - **Point**: tap the room and the focus moves to the real depth of that pixel
 *    ([Frame.depthFocusDistance]); tap the sneaker to come back.
 *
 * The rules live in [PortraitFocus], tested on the JVM.
 */
@Composable
fun ARDepthOfFieldDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    var arSessionUnavailable by remember { mutableStateOf(false) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo with
    // `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // A real product at its real size to focus on: the Nike Air Jordan 1 from the catalogue
    // (#4560), seen three-quarter — the sneaker product shot portrait mode is made for.
    val sneaker = rememberCatalogueProduct(modelLoader, CatalogueProducts.AIR_JORDAN_1)

    var portrait by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<PortraitFocusTarget>(PortraitFocusTarget.Subject) }
    var subjectDistance by remember { mutableStateOf<Float?>(null) }
    // Filament's stock cinematic strength (cocScale identity). 2x crushed out-of-focus regions
    // into black bands (#2480); the slider still goes to 6x for a stronger blur.
    var blurStrength by remember { mutableFloatStateOf(1.0f) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }
    // Until the first tracked frame, the distance the sneaker is placed at.
    val appliedFocus = PortraitFocus.focusDistance(target, subjectDistance) ?: DEFAULT_FOCUS_METRES

    // Latest Frame for tap-to-focus. The depth API requires the Frame from the same update tick.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    // Where the focus ring is drawn, and a counter that restarts its fade on every tap.
    var ringAt by remember { mutableStateOf<Offset?>(null) }
    var ringTap by remember { mutableIntStateOf(0) }

    val view = rememberARView(engine)
    val cameraNode = rememberARCameraNode(engine)
    arDepthOfField(
        view = view,
        camera = cameraNode,
        options = ARDepthOfFieldOptions(
            focusDepth = appliedFocus,
            blurStrength = blurStrength,
            enabled = portrait && depthSupported != false,
        ),
    )

    val status = PortraitFocus.status(portrait, depthSupported, target, subjectDistance)
    val modes = remember {
        listOf(
            DemoMode("original", R.string.portrait_mode_original),
            DemoMode("portrait", R.string.portrait_mode_portrait),
        )
    }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = !arSessionUnavailable,
        title = stringResource(R.string.demo_ar_depth_of_field_title),
        onBack = onBack,
        controls = {
            Text(stringResource(R.string.portrait_how_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.portrait_how_body), style = MaterialTheme.typography.bodyMedium)
            if (depthSupported == false) {
                Text(
                    stringResource(R.string.portrait_status_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Same breathing room as the other demo sheets between the explainer and the controls.
            Spacer(Modifier.height(SceneViewTokens.Space.md))
            LabeledSlider(
                label = stringResource(R.string.portrait_blur),
                value = blurStrength,
                onValueChange = { blurStrength = it },
                valueRange = 0f..MAX_BLUR,
                valueText = "%.1f×".format(Locale.US, blurStrength),
                enabled = portrait && depthSupported != false,
            )
            LabeledSlider(
                label = stringResource(R.string.portrait_focus_distance),
                value = appliedFocus,
                onValueChange = { target = PortraitFocusTarget.Point(it) },
                valueRange = PortraitFocus.MIN_FOCUS_METRES..PortraitFocus.MAX_FOCUS_METRES,
                valueText = stringResource(R.string.portrait_distance_metres, appliedFocus),
                enabled = portrait && depthSupported != false,
            )
            OutlinedButton(
                onClick = { target = PortraitFocusTarget.Subject },
                enabled = target != PortraitFocusTarget.Subject,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.portrait_refocus)) }
        },
        topOverlay = {
            if (!arSessionUnavailable) PortraitStatusCard(status, appliedFocus)
        },
        bottomOverlay = {
            if (!arSessionUnavailable) {
                // Until the sneaker is in, its pill names it and narrates the download.
                CatalogueProductPill(sneaker)
                // Before / after, where a camera app puts its modes: just above the shutter.
                DemoModePill(
                    DemoModeSwitch(
                        modes = modes,
                        selected = if (portrait) 1 else 0,
                        onSelect = { portrait = it == 1 },
                    ),
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                lifecycle = rememberHostLifecycle(),
                onSessionFailure = { arSessionFailed = true },
                onARCoreAvailability = { arSessionUnavailable = it != null },
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                view = view,
                cameraNode = cameraNode,
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    depthSupported = supported
                    config.depthMode = if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                },
                // Depth occlusion installs the gl_FragDepth-writing camera material, which is
                // what puts real-world depth in Filament's z-buffer for the blur to read.
                cameraStream = rememberARCameraStream(
                    materialLoader = materialLoader,
                    creator = {
                        createARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }
                    },
                ),
                onSessionUpdated = { _, frame: Frame ->
                    latestFrame = frame
                    if (frame.camera.trackingState == TrackingState.TRACKING) {
                        val measured = PortraitFocus.distance(frame.camera.pose.translation, SNEAKER_POSITION)
                        val next = PortraitFocus.follow(subjectDistance, measured)
                        if (next != subjectDistance) subjectDistance = next
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, node ->
                        val frame = latestFrame ?: return@rememberOnGestureListener
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            return@rememberOnGestureListener
                        }
                        if (node != null) {
                            // The sneaker is the only node: back to following it.
                            target = PortraitFocusTarget.Subject
                        } else {
                            frame.depthFocusDistance(event.x, event.y)?.let { d ->
                                target = PortraitFocusTarget.Point(d)
                            } ?: return@rememberOnGestureListener
                        }
                        ringAt = Offset(event.x, event.y)
                        ringTap++
                    },
                ),
            ) {
                // Placement-free, 1 m ahead of the session's first pose and a little below eye
                // level: a stable subject against the real room. Placement has its own demos.
                sneaker.instance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = sneaker.realSizeMetres,
                        position = Position(SNEAKER_POSITION[0], SNEAKER_POSITION[1], SNEAKER_POSITION[2]),
                        rotation = Rotation(y = SNEAKER_YAW_DEGREES),
                    )
                }
            }
            ringAt?.let { FocusRing(it, ringTap, onFinished = { ringAt = null }) }
        }
    }
}

/** The status over the camera: what is sharp right now. */
@Composable
private fun PortraitStatusCard(status: PortraitFocusStatus, focus: Float) {
    val distance = stringResource(R.string.portrait_distance_metres, focus)
    val (text, tone, icon) = when (status) {
        PortraitFocusStatus.Original ->
            Triple(
                stringResource(R.string.portrait_status_original),
                DemoStatusTone.Progress,
                Icons.Filled.VisibilityOff,
            )
        PortraitFocusStatus.Unsupported ->
            Triple(stringResource(R.string.portrait_status_unsupported), DemoStatusTone.Blocked, null)
        PortraitFocusStatus.Waiting ->
            Triple(stringResource(R.string.portrait_status_waiting), DemoStatusTone.Guidance, Icons.Filled.Portrait)
        PortraitFocusStatus.Subject ->
            Triple(
                stringResource(R.string.portrait_status_subject, distance),
                DemoStatusTone.Progress,
                Icons.Filled.CenterFocusStrong,
            )
        PortraitFocusStatus.Point ->
            Triple(
                stringResource(R.string.portrait_status_point, distance),
                DemoStatusTone.Progress,
                Icons.Filled.FilterCenterFocus,
            )
    }
    DemoStatusCard(text, tone = tone, icon = icon)
}

/**
 * The camera app's focus ring at the tapped point: a `accent-guidance` amber circle (the
 * colour of the iOS focus square) that fades out, restarted by every tap ([tap]).
 */
@Composable
internal fun FocusRing(at: Offset, tap: Int, onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(tap) {
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(FOCUS_RING_FADE_MS, delayMillis = FOCUS_RING_HOLD_MS))
        finish()
    }
    val size = SceneViewTokens.Space.x3l
    val half = with(LocalDensity.current) { size.toPx() / 2f }
    val description = stringResource(R.string.portrait_focus_point)
    Canvas(
        modifier = Modifier
            .offset { IntOffset((at.x - half).toInt(), (at.y - half).toInt()) }
            .size(size)
            .graphicsLayer { this.alpha = alpha.value }
            .semantics { contentDescription = description },
    ) {
        val stroke = SceneViewTokens.Layout.selectedOutlineWidth.toPx()
        drawCircle(
            color = SceneViewTokens.ArOverlay.accentGuidance,
            radius = this.size.minDimension / 2f - stroke,
            style = Stroke(width = stroke),
        )
    }
}

/** Focus before the sneaker's distance is known: the distance it is placed at. */
private const val DEFAULT_FOCUS_METRES = 1.0f

/** Strongest blur the slider offers (circle-of-confusion scale). */
private const val MAX_BLUR = 6f

/** How long the focus ring stays fully visible, then how long it fades, in ms. */
internal const val FOCUS_RING_HOLD_MS = 600
internal const val FOCUS_RING_FADE_MS = 400

/**
 * The sneaker's world position (x, y, z): 1 m ahead of the session's first pose (the world
 * origin, looking down -Z) and 0.3 m below it — a hand-held phone over a table-top.
 */
private val SNEAKER_POSITION = floatArrayOf(0f, -0.3f, -1.0f)

/** The catalogue's three-quarter turn (the Model Viewer's `CATALOGUE_FRONT_YAW`). */
private const val SNEAKER_YAW_DEGREES = -30f
