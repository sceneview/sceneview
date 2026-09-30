package io.github.sceneview.ar

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import com.google.ar.core.*
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.material.setColor
import kotlin.math.roundToInt
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/** Supported automatic-placement alignment. Surface accepts tables, never ceilings. */
enum class PlacementSurface { SURFACE, WALL }

/** A real plane-associated placement. The anchor and plane retain their ARCore identity. */
class AutoPlacementResult internal constructor(
    anchor: Anchor,
    plane: Plane,
    pose: Pose,
) {
    var anchor: Anchor = anchor
        internal set
    var plane: Plane = plane
        internal set
    var pose: Pose = pose
        internal set
}

/** A validated, oriented surface candidate. Creating its anchor can still fail. */
class AutoPlacementCandidate internal constructor(val plane: Plane, val pose: Pose) {
    fun createAnchor(): AutoPlacementResult? = runCatching {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null || !plane.isPoseInPolygon(pose)) {
            return@runCatching null
        }
        val anchor = plane.createAnchor(pose)
        if (anchor.trackingState != TrackingState.TRACKING) {
            anchor.detach()
            null
        } else AutoPlacementResult(anchor, plane, pose)
    }.getOrNull()
}

/**
 * Shared center-first policy for automatic placement and app adapters. Fallback centers
 * are ordered by screen-center proximity, and must lie inside the detected polygon.
 */
fun findAutoPlacementSurface(
    frame: Frame,
    planes: Collection<Plane>,
    width: Int,
    height: Int,
    surface: PlacementSurface = PlacementSurface.SURFACE,
): AutoPlacementCandidate? {
    if (width <= 0 || height <= 0 || frame.camera.trackingState != TrackingState.TRACKING) return null
    fun supported(plane: Plane) = plane.trackingState == TrackingState.TRACKING &&
        plane.subsumedBy == null && plane.type == when (surface) {
            PlacementSurface.SURFACE -> Plane.Type.HORIZONTAL_UPWARD_FACING
            PlacementSurface.WALL -> Plane.Type.VERTICAL
        }
    val hit = runCatching { frame.hitTest(width / 2f, height / 2f) }.getOrDefault(emptyList())
        .firstOrNull { hit ->
            val plane = hit.trackable as? Plane
            plane != null && plane.subsumedBy == null && UsableSurfacePolicy.accept(
                surface, plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                plane.type == Plane.Type.VERTICAL, plane.trackingState == TrackingState.TRACKING,
                plane.isPoseInPolygon(hit.hitPose), hit.distance,
            )
        }
    if (hit != null) {
        val hitPlane = hit.trackable as Plane
        return AutoPlacementCandidate(hitPlane, orientedPlacementPose(hit.hitPose, hitPlane, frame.camera.pose))
    }
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    val viewProjection = ViewportProjection.multiply(projection, view)
    val camera = frame.camera.pose
    val candidates = planes.filter { supported(it) && it.isPoseInPolygon(it.centerPose) }.map { plane ->
        val pose = plane.centerPose
        FallbackCandidate(
            AutoPlacementCandidate(plane, orientedPlacementPose(pose, plane, camera)),
            ViewportProjection.distance(pose.tx(), pose.ty(), pose.tz(), camera.tx(), camera.ty(), camera.tz()),
            ViewportProjection.project(viewProjection, pose.tx(), pose.ty(), pose.tz()),
        )
    }
    return UsableSurfacePolicy.rankFallback(candidates).firstOrNull()
}

/**
 * The plane finding [AutoPlacementScene] asks ARCore for. Each flow looks only for the planes
 * it can place on: a wall flow that is not asked for vertical planes can never find a wall
 * (#4070), and a surface flow has no use for walls. Pure, so `AutoPlacementPlaneFindingTest`
 * pins the contract on the JVM.
 */
internal fun autoPlacementPlaneFindingMode(surface: PlacementSurface): Config.PlaneFindingMode =
    when (surface) {
        PlacementSurface.SURFACE -> Config.PlaneFindingMode.HORIZONTAL
        PlacementSurface.WALL -> Config.PlaneFindingMode.VERTICAL
    }

/** ARCore's +Y is the surface normal; -Z is gravity-up for upright wall content. */
private fun orientedPlacementPose(pose: Pose, plane: Plane, camera: Pose): Pose {
    if (plane.type != Plane.Type.VERTICAL) return pose
    val axis = plane.centerPose.getTransformedAxis(1, 1f)
    val wall = directWallPose(
        Position(pose.tx(), pose.ty(), pose.tz()), Float3(axis[0], axis[1], axis[2]),
        Float3(camera.tx() - pose.tx(), camera.ty() - pose.ty(), camera.tz() - pose.tz()),
    )
    // Keep the existing automatic anchor convention: +Y normal, -Z wall-up.
    val rotation = wall.rotation * Rotation(x = 90f).toQuaternion()
    return Pose(pose.translation, floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w))
}

@Composable
fun rememberAutoPlacementState(): AutoPlacementState = remember { AutoPlacementState() }

/**
 * Places once on the first usable detected plane after [assetReady]. No tap, reticle or
 * plane visualization. Load the asset before setting [assetReady]; keep the old asset
 * until a replacement succeeds, and gate asynchronous results using [AutoPlacementState.ticket].
 *
 * [state] exposes recovery, reset and explicit request actions. Camera/capability events
 * are forwarded without imposing app copy. Haptics are opt-in: call [ARHapticFeedback] with the
 * same [state]. Content is composed on the main thread.
 * [AutoPlacementModel] supplies grounded, surface-constrained manipulation.
 *
 * @param coaching show the animated [ARCoachingOverlay] (phone sweep while scanning, a
 *   "surface found" beat on placement, pause/look-back glyphs when tracking degrades). On by
 *   default. Pass `false` to draw your own from [rememberArGuidanceState], and hide your own
 *   status chrome while [ArGuidanceState.isCoaching] is true either way.
 */
@Composable
fun AutoPlacementScene(
    assetReady: Boolean,
    modifier: Modifier = Modifier,
    state: AutoPlacementState = rememberAutoPlacementState(),
    surface: PlacementSurface = PlacementSurface.SURFACE,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    groundShadows: Boolean = true,
    coaching: Boolean = true,
    playbackDataset: File? = null,
    onARCoreAvailability: ((availability: ARCoreAvailability?) -> Unit)? = null,
    onTrackingFailureChanged: ((TrackingFailureReason?) -> Unit)? = null,
    onSessionFailed: ((Exception) -> Unit)? = null,
    onPlaced: ((AutoPlacementResult) -> Unit)? = null,
    content: @Composable ARSceneScope.(AutoPlacementResult) -> Unit,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var placement by remember(state) { mutableStateOf<AutoPlacementResult?>(null) }
    var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
    val ready by rememberUpdatedState(assetReady)
    val placedCallback by rememberUpdatedState(onPlaced)
    LaunchedEffect(state, assetReady) {
        if (assetReady) state.requestPlacement() else state.withdrawRequest()
    }
    // Keyed on [state] alone, the disposal lambda below would capture the `placement` of the
    // composition that created the effect — normally null. Reading the latest value through
    // [rememberUpdatedState] is what makes `detach()` actually run when the host navigates away
    // after a placement, including when the content composes no AnchorNode of its own.
    val currentPlacement by rememberUpdatedState(placement)
    // The coaching glyph is centred, exactly where ARSceneView draws its "Couldn't start AR"
    // card — and it kept sweeping over that card's copy and its Try again button (#3986).
    // DESIGN.md: the glyph is silent whenever a card explains the state.
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    DisposableEffect(state) {
        onDispose {
            currentPlacement?.anchor?.detach()
            state.dismiss()
        }
    }
    Box(modifier = modifier) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            planeRenderer = false,
            planeFindingMode = autoPlacementPlaneFindingMode(surface),
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            onGestureListener = rememberOnGestureListener(
                // Tap the object to select it (the selection ring appears), tap empty space
                // to deselect — Scene Viewer's selection model.
                onSingleTapConfirmed = { _, node ->
                    if (node == null) state.deselectPlacement() else state.selectPlacement()
                },
                // Double-tap the object: back to 100 %, or back to the user's size from 100 %.
                onDoubleTap = { _, node -> if (node != null) state.toggleBaseScale() },
            ),
            onARCoreAvailability = {
                availability = it
                onARCoreAvailability?.invoke(it)
            },
            onTrackingFailureChanged = onTrackingFailureChanged,
            onSessionFailed = { state.cameraFailed(); onSessionFailed?.invoke(it) },
            onSessionUpdated = { session, frame ->
                if (!state.hasPlacement && placement != null) {
                    placement?.anchor?.detach()
                    placement = null
                }
                val tracked = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                if (tracked != planes) planes = tracked
                val candidate = if (ready && state.wantsSurface) {
                    findAutoPlacementSurface(frame, tracked, viewport.width, viewport.height, surface)
                } else null
                val effect = state.onFrame(
                    FrameInput(SystemClock.uptimeMillis(), frame.camera.trackingState == TrackingState.TRACKING,
                        candidate != null, placement?.anchor?.trackingState?.let { it == TrackingState.TRACKING }),
                    commit = {
                        candidate?.createAnchor()?.let { placement = it; true } ?: false
                    },
                )
                if (effect == FrameEffect.PLACE) placement?.let { placedCallback?.invoke(it) }
            },
        ) {
            placement?.let { result -> key(result) { content(result) } }
            if (groundShadows && (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)) {
                planes.forEach { plane -> key(plane) { ShadowReceiverPlane(plane = plane) } }
            }
        }
        if (coaching) {
            val guidance = rememberArGuidanceState(state, surface)
            ARCoachingOverlay(
                cue = if (availability == null) guidance.cue else ArGuidanceCue.NONE,
                surface = guidance.surface,
            )
        }
    }
}

/**
 * Binary-compatibility shim for the pre-`coaching` descriptor of [AutoPlacementScene]. The
 * Compose compiler puts every parameter in the JVM signature, so adding `coaching` retyped
 * the method (CONTRIBUTING.md — a retyped public symbol is a breaking change). Code compiled
 * against the old signature gets the coaching overlay, the new default.
 */
@Deprecated(
    "Binary-compatibility overload. Use the AutoPlacementScene overload that takes `coaching`.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
fun AutoPlacementScene(
    assetReady: Boolean,
    modifier: Modifier = Modifier,
    state: AutoPlacementState = rememberAutoPlacementState(),
    surface: PlacementSurface = PlacementSurface.SURFACE,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    groundShadows: Boolean = true,
    playbackDataset: File? = null,
    onARCoreAvailability: ((availability: ARCoreAvailability?) -> Unit)? = null,
    onTrackingFailureChanged: ((TrackingFailureReason?) -> Unit)? = null,
    onSessionFailed: ((Exception) -> Unit)? = null,
    onPlaced: ((AutoPlacementResult) -> Unit)? = null,
    content: @Composable ARSceneScope.(AutoPlacementResult) -> Unit,
) = AutoPlacementScene(
    assetReady = assetReady,
    modifier = modifier,
    state = state,
    surface = surface,
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    groundShadows = groundShadows,
    coaching = true,
    playbackDataset = playbackDataset,
    onARCoreAvailability = onARCoreAvailability,
    onTrackingFailureChanged = onTrackingFailureChanged,
    onSessionFailed = onSessionFailed,
    onPlaced = onPlaced,
    content = content,
)

/**
 * Grounded model content for [AutoPlacementScene], with a 0.3 m longest-axis preview by
 * default. Pass null [scaleToUnits] to retain authored units. The model's complete bounding
 * volume is selectable, including nested meshes. [assetRotation] corrects authored axes
 * before the grounded bounds are calculated.
 *
 * Gestures, as Scene Viewer and AR Quick Look:
 * - **Pinch** follows the fingers 1:1 relative to where they landed (spreading them twice as
 *   far doubles the object), limited to 25–400 % of the base, snapping to 100 % within ±4 %
 *   with a short elastic rebound.
 * - **Twist** turns the object about its base.
 * - **Double-tap** ([AutoPlacementState.toggleBaseScale]) animates back to 100 %; at 100 % it
 *   returns to the size last pinched to.
 * - **Selection** draws a thin white ring on the surface around the object's footprint while
 *   [AutoPlacementState.isSelected]; opt out with [AutoPlacementState.showsSelectionRing].
 *   [AutoPlacementNode]'s free-form content draws no ring — it has no bounds to encircle.
 */
@Composable
fun ARSceneScope.AutoPlacementModel(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    modelInstance: ModelInstance,
    scaleToUnits: Float? = 0.3f,
    assetRotation: Rotation = Rotation(0f),
    onInvalidMove: (Boolean) -> Unit = {},
    onScaleChanged: (percent: Int, atBaseSize: Boolean, enteredBaseSize: Boolean) -> Unit = { _, _, _ -> },
) {
    val entrance = rememberPlacementEntrance(placement, state)
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = entrance.value > 0f) {
        val model = remember(modelInstance, scaleToUnits, assetRotation) {
            io.github.sceneview.node.ModelNode(modelInstance, scaleToUnits = scaleToUnits).apply {
                quaternion = if (placement.plane.type == Plane.Type.VERTICAL) {
                    Rotation(x = -90f).toQuaternion() * assetRotation.toQuaternion()
                } else assetRotation.toQuaternion()
                // Transform all eight authored bounds corners after the axis correction.
                val corners = buildList<Position> {
                    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                        val corner = center +
                            Position(x * halfExtent.x, y * halfExtent.y, z * halfExtent.z)
                        add(quaternion * (corner * scale))
                    }
                }
                position = automaticPlacementOffset(corners, placement.plane.type == Plane.Type.VERTICAL)
                isEditable = true
                isPositionEditable = false
                isRotationEditable = false
                isScaleEditable = false
            }
        }
        val footprint = remember(model) {
            // The model's own bounds, already rotated and grounded — the ring hugs the object.
            val grounded = buildList<Position> {
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                    val corner = model.center +
                        Position(x * model.halfExtent.x, y * model.halfExtent.y, z * model.halfExtent.z)
                    add(model.quaternion * (corner * model.scale) + model.position)
                }
            }
            SelectionRing.footprint(grounded)
        }
        // Opaque glTF materials cannot fade, so the model grows into place and shrinks away
        // on tracking loss instead. Scaling about the pivot keeps the contact point fixed.
        Node(scale = Scale(PlacementEntrance.scaleFraction(entrance.value))) {
            NodeLifecycle(model, null)
            SelectionRingNode(state, footprint)
        }
    }
}

/**
 * Scene Viewer's selection affordance: a thin white ring lying on the surface around the
 * placed object's footprint, fading in while [AutoPlacementState.isSelected] and out on
 * deselection. A real node in the anchor's frame — so it sits on the floor in true perspective,
 * is hidden behind the object where the object stands in front of it, and scales with the pinch
 * — rather than a screen-space overlay chasing the object one frame late. Never touchable, never
 * a shadow caster.
 */
@Composable
private fun io.github.sceneview.NodeScope.SelectionRingNode(
    state: AutoPlacementState,
    footprint: SelectionRing.Footprint,
) {
    if (footprint.radius <= 0f) return
    val material = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(
            io.github.sceneview.math.colorOf(1f, 1f, 1f, SelectionRing.ALPHA),
        )
    }
    // Declared before the ring's own lifecycle below: Compose disposes in reverse order, so
    // the renderable is gone before its material instance is.
    DisposableEffect(material) {
        onDispose { materialLoader.destroyMaterialInstance(material) }
    }
    val ring = remember(engine, footprint) {
        val tube = SelectionRing.tubeRadius(footprint.radius)
        io.github.sceneview.node.TorusNode(
            engine = engine,
            majorRadius = footprint.radius,
            minorRadius = tube,
            // Lifted by its own thickness so it lies ON the surface instead of z-fighting the
            // shadow catcher that shares y = 0.
            center = Position(footprint.centerX, tube + SelectionRing.LIFT, footprint.centerZ),
            majorSegments = SelectionRing.MAJOR_SEGMENTS,
            minorSegments = SelectionRing.MINOR_SEGMENTS,
            materialInstance = material,
        ).apply {
            // Hidden until the fade below has run once: no one-frame flash when unselected.
            isVisible = false
            isTouchable = false
            isShadowCaster = false
            isShadowReceiver = false
        }
    }
    val placed = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    val opacity = animateFloatAsState(
        targetValue = if (state.showsSelectionRing && state.isSelected && placed) 1f else 0f,
        animationSpec = tween(SelectionRing.FADE_MS),
        label = "selectionRing",
    )
    // Every animation frame goes straight to the material, without recomposing: a read of
    // `opacity` inside a `SideEffect` is not tracked, so the fade would stop at its first frame
    // and a deselected ring would stay drawn.
    LaunchedEffect(ring, material) {
        snapshotFlow { opacity.value }.collect { value ->
            ring.isVisible = value > 0f
            material.setColor(io.github.sceneview.math.colorOf(1f, 1f, 1f, SelectionRing.ALPHA * value))
            // A material parameter is invisible to the frame gate: ask for the frame that shows
            // the fade, or a scene with no new camera frame (a finished playback, a paused
            // session) keeps drawing the old ring.
            ring.requestRender()
        }
    }
    NodeLifecycle(ring, null)
}

/** Geometry of the selection ring — pure, pinned in `ScaleSnapTest`. */
internal object SelectionRing {
    /** Peak opacity: present on any floor, never a solid white hoop. */
    const val ALPHA = 0.9f

    /** Fade in/out on (de)selection, milliseconds (`motion-fade` family). */
    const val FADE_MS = 180

    /** Clearance between the object's footprint and the ring, as a fraction of the radius. */
    const val MARGIN = 0.12f

    /** Lift above the surface, metres — clears the shadow catcher at y = 0. */
    const val LIFT = 0.001f

    const val MAJOR_SEGMENTS = 96
    const val MINOR_SEGMENTS = 8

    /** Ring centre on the surface plane (anchor X/Z) and radius, metres. */
    data class Footprint(val centerX: Float, val centerZ: Float, val radius: Float)

    /**
     * The circle on the surface plane (anchor X/Z — the floor, or the wall for a wall
     * placement, whose normal is the anchor's Y) that encloses every grounded bounds corner,
     * plus [MARGIN].
     */
    fun footprint(corners: List<Position>): Footprint {
        if (corners.isEmpty()) return Footprint(0f, 0f, 0f)
        val cx = (corners.minOf { it.x } + corners.maxOf { it.x }) / 2f
        val cz = (corners.minOf { it.z } + corners.maxOf { it.z }) / 2f
        val r = corners.maxOf { kotlin.math.hypot(it.x - cx, it.z - cz) }
        return Footprint(cx, cz, if (r.isFinite()) r * (1f + MARGIN) else 0f)
    }

    /** Tube radius: a hairline that stays visible on a mug and slim under a sofa. */
    fun tubeRadius(radius: Float): Float = (radius * 0.012f).coerceIn(0.0018f, 0.006f)
}

/**
 * Surface-constrained procedural content using the same gestures and state as [AutoPlacementModel].
 * Author +Y up and +Z front. Put the bottom at y=0 and, for a wall, the back at z=0.
 * Size content before supplying it (demo preview: 0.3 m longest dimension). Each selectable
 * child must be editable with position/rotation/scale editing disabled so gestures reach the pivot.
 * [AutoPlacementState.moveBy], [AutoPlacementState.rotateBy] and [AutoPlacementState.scaleTo]
 * expose the same validated transforms to accessibility controls. Apply the content callback's
 * opacity to transparent materials: it fades from zero on placement and out on tracking loss
 * over 300 ms. The content also grows into place from 55 % (the same entrance as
 * [AutoPlacementModel]), scaled about the contact pivot so it never leaves the surface.
 */
@Composable
fun ARSceneScope.AutoPlacementNode(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    onInvalidMove: (Boolean) -> Unit = {},
    onScaleChanged: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> },
    content: @Composable io.github.sceneview.NodeScope.(opacity: Float) -> Unit,
) {
    val opacity = remember(placement) { Animatable(0f) }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    LaunchedEffect(visible) { opacity.animateTo(if (visible) 1f else 0f, tween(PlacementEntrance.EXIT_MS)) }
    val entrance = rememberPlacementEntrance(placement, state)
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = opacity.value > 0f || entrance.value > 0f) {
        Node(
            rotation = if (placement.plane.type == Plane.Type.VERTICAL) Rotation(x = -90f) else Rotation(),
            scale = Scale(PlacementEntrance.scaleFraction(entrance.value)),
        ) {
            content(opacity.value)
        }
    }
}

@Composable
private fun ARSceneScope.AutomaticPlacementPivot(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    onInvalidMove: (Boolean) -> Unit,
    onScaleChanged: (Int, Boolean, Boolean) -> Unit,
    visibleDuringFade: Boolean? = null,
    content: @Composable io.github.sceneview.NodeScope.() -> Unit,
) {
    val invalidMove by rememberUpdatedState(onInvalidMove)
    val scaleChanged by rememberUpdatedState(onScaleChanged)
    val root = remember(engine, placement) {
        var wasInvalid = false
        AutomaticAnchorNode(engine, placement, state) { invalid ->
            // One tick when the move turns invalid, not one per rejected drag frame.
            if (invalid && !wasInvalid) state.gestureHapticSink?.invoke(ARHapticEvent.InvalidMove)
            wasInvalid = invalid
            invalidMove(invalid)
        }
    }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    SideEffect {
        root.visibleTrackingStates = if (visibleDuringFade != null) TrackingState.values().toSet()
            else setOf(TrackingState.TRACKING)
        root.isVisible = visibleDuringFade ?: visible
    }
    NodeLifecycle(root) {
        val pivot = remember(engine, placement) { object : io.github.sceneview.node.Node(engine) {
            /**
             * The logical scale; [scale] only differs from it during the 100 % rebound and
             * the animated double-tap resize.
             */
            var logicalScale = 1f
            private var rebound: Boolean? = null // fromAbove, while a rebound is pending or running
            private var reboundStartNanos = 0L

            /** The last size the user pinched to away from 100 % — what a double-tap restores. */
            var rememberedScale: Float? = null

            /** Visual scale the double-tap animation started from, `null` when none runs. */
            private var tweenFrom: Float? = null
            private var tweenStartNanos = 0L

            fun startRebound(fromAbove: Boolean) {
                tweenFrom = null
                rebound = fromAbove
                reboundStartNanos = 0L
            }

            fun cancelRebound() {
                rebound = null
                // Any running double-tap tween lands where it was going, so a pinch that
                // starts mid-animation grabs the object at its logical size.
                if (tweenFrom != null) {
                    tweenFrom = null
                    scale = Scale(logicalScale)
                }
            }

            /** Animates the visual scale to [target] (already written to [logicalScale]). */
            fun animateTo(target: Float) {
                rebound = null
                tweenFrom = scale.x
                tweenStartNanos = 0L
                logicalScale = target
            }

            override val isFrameActive: Boolean
                get() = rebound != null || tweenFrom != null || super.isFrameActive

            override fun onFrame(frameTimeNanos: Long) {
                super.onFrame(frameTimeNanos)
                tweenFrom?.let { from ->
                    if (tweenStartNanos == 0L) tweenStartNanos = frameTimeNanos
                    val elapsedMs = (frameTimeNanos - tweenStartNanos) / 1_000_000f
                    scale = Scale(ScaleSnap.doubleTapProgress(from, logicalScale, elapsedMs))
                    if (elapsedMs >= ScaleSnap.DOUBLE_TAP_MS) tweenFrom = null
                    return
                }
                val fromAbove = rebound ?: return
                if (reboundStartNanos == 0L) reboundStartNanos = frameTimeNanos
                val elapsedMs = (frameTimeNanos - reboundStartNanos) / 1_000_000f
                if (elapsedMs >= ScaleSnap.REBOUND_MS) {
                    rebound = null
                    scale = Scale(logicalScale)
                } else {
                    scale = Scale(logicalScale * ScaleSnap.rebound(elapsedMs, fromAbove))
                }
            }

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                super.onRotateEnd(detector, e)
                state.endAdjustment()
            }
            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
                super.onScaleEnd(detector, e)
                state.endAdjustment()
            }
        }.apply {
            isEditable = true
            isPositionEditable = false
            editableScaleRange = ScaleSnap.MIN..ScaleSnap.MAX
            onRotateBegin = { _, _ -> state.beginAdjustment() }
            // The pinch is measured against the moment two fingers landed — the scale then,
            // times the span ratio since — never accumulated event by event: 1:1 with the
            // fingers (Scene Viewer / Quick Look / the iOS controller), path-independent, and
            // immune to whether the detector's factor is per-event or cumulative.
            var pinchStartScale = 1f
            var pinchStartSpan = 0f
            onScaleBegin = { detector, _ ->
                cancelRebound()
                pinchStartScale = logicalScale
                pinchStartSpan = detector.currentSpan
                state.beginAdjustment()
            }
            onRotate = { _, _, _ -> state.isAdjusting }
            onScale = { detector, _, _ ->
                if (state.isAdjusting) {
                    val previous = logicalScale
                    val rawScale = ScaleSnap.pinchRaw(pinchStartScale, pinchStartSpan, detector.currentSpan)
                        .coerceIn(ScaleSnap.MIN, ScaleSnap.MAX)
                    val step = ScaleSnap.step(previous, rawScale)
                    logicalScale = step.displayed
                    if (step.enteredSnap) {
                        startRebound(fromAbove = previous > 1f)
                        state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                    } else if (!step.snapped) {
                        cancelRebound()
                        scale = Scale(step.displayed)
                    }
                    if (step.enteredLimit) state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                    if (!step.snapped) rememberedScale = step.displayed
                    state.scaleFactor = step.displayed
                    scaleChanged((step.displayed * 100).roundToInt(), step.snapped, step.enteredSnap)
                }
                false
            }
        } }
        DisposableEffect(pivot, state) {
            state.moveAction = { x, y ->
                // Every refused button press is felt, not only the first one (throttled per event).
                root.moveBy(x, y).also { if (!it) state.gestureHapticSink?.invoke(ARHapticEvent.InvalidMove) }
            }
            state.rotateAction = { pivot.quaternion *= Rotation(y = it).toQuaternion() }
            state.scaleAction = {
                val previous = pivot.logicalScale
                pivot.cancelRebound()
                // Keep the double-tap toggle coherent with the read-out's tap-to-100 % and
                // the accessibility slider: the size left behind is the size a double-tap
                // brings back.
                if (it != 1f) pivot.rememberedScale = it else if (previous != 1f) pivot.rememberedScale = previous
                pivot.logicalScale = it
                pivot.scale = Scale(it)
                state.scaleFactor = it
                val crossed = (previous < 1f && it >= 1f) || (previous > 1f && it <= 1f)
                if (crossed) state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                if (ScaleSnap.isAtLimit(it) && !ScaleSnap.isAtLimit(previous)) {
                    state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                }
                scaleChanged((it * 100).roundToInt(), it == 1f, crossed)
            }
            state.scaleToggleAction = {
                val previous = pivot.logicalScale
                val target = ScaleSnap.doubleTapTarget(previous, pivot.rememberedScale)
                if (target == null) {
                    // Nothing to toggle: acknowledge the tap with the 100 % rebound.
                    pivot.startRebound(fromAbove = false)
                } else {
                    if (target == 1f) pivot.rememberedScale = previous
                    pivot.animateTo(target)
                    state.scaleFactor = target
                    if (target == 1f) state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                    scaleChanged((target * 100).roundToInt(), target == 1f, target == 1f)
                }
            }
            onDispose {
                state.moveAction = null
                state.rotateAction = null
                state.scaleAction = null
                state.scaleToggleAction = null
            }
        }
        NodeLifecycle(pivot, content)
    }
}

/**
 * Entrance progress (0 = hidden, 1 = settled) of a placement: runs up over
 * [PlacementEntrance.DURATION_MS] when the object becomes visible (placed, or re-tracked)
 * and back down over [PlacementEntrance.EXIT_MS] when tracking is lost.
 */
@Composable
private fun rememberPlacementEntrance(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
): Animatable<Float, *> {
    val progress = remember(placement) { Animatable(0f) }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    LaunchedEffect(progress, visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                if (visible) PlacementEntrance.DURATION_MS else PlacementEntrance.EXIT_MS,
                easing = LinearEasing,
            ),
        )
    }
    return progress
}

/**
 * The "grows into place" entrance of a freshly placed object (`motion-placement-entrance`
 * in `DESIGN.md`). A model that appears at full size on the frame its anchor lands reads as
 * a glitch; Scene Viewer, IKEA Place and Reality Composer all ease it up from a smaller scale
 * over about a quarter of a second — short enough to feel instant, long enough to register.
 */
internal object PlacementEntrance {

    /** Duration of the scale-in, milliseconds. */
    const val DURATION_MS = 260

    /** Duration of the shrink/fade-out on tracking loss (`motion-fade`). */
    const val EXIT_MS = 300

    /** Scale fraction the object starts at — deliberately not 0, which reads as a flicker. */
    const val START_FRACTION = 0.55f

    /**
     * Eased scale fraction at animation [progress] (`0..1`). Cubic ease-out: fast out of the
     * gate, settling without overshoot — an overshoot on a *physical-scale* object reads as
     * the object being the wrong size, not as bounce.
     */
    fun scaleFraction(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return START_FRACTION + (1f - START_FRACTION) * eased
    }
}

/** Bounds are already rotated into the anchor frame before grounding. */
internal fun automaticPlacementOffset(corners: List<Position>, wall: Boolean): Position = Position(
    -(corners.minOf { it.x } + corners.maxOf { it.x }) / 2f,
    -corners.minOf { it.y },
    if (wall) -corners.maxOf { it.z } else -(corners.minOf { it.z } + corners.maxOf { it.z }) / 2f,
)

private fun visiblePlacementPoint(frame: Frame, pose: Pose): Boolean {
    if (frame.camera.trackingState != TrackingState.TRACKING) return false
    val camera = frame.camera.pose
    val distance = ViewportProjection.distance(pose.tx(), pose.ty(), pose.tz(), camera.tx(), camera.ty(), camera.tz())
    if (distance !in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M) return false
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    return ViewportProjection.project(ViewportProjection.multiply(projection, view),
        pose.tx(), pose.ty(), pose.tz())?.isInsideViewport == true
}

/** Keeps the logical anchor alive while dragging; only a valid finished move replaces it. */
private class AutomaticAnchorNode(
    engine: Engine,
    private val placement: AutoPlacementResult,
    private val state: AutoPlacementState,
    private val invalidMove: (Boolean) -> Unit,
) : AnchorNode(engine, placement.anchor) {
    private var offset: FloatArray? = null
    private var pending: AutoPlacementCandidate? = null
    private var interruptedMove = false
    init { isEditable = true }

    /** Accessibility moves obey the same polygon/range/anchor checks as a drag. */
    fun moveBy(x: Float, y: Float): Boolean {
        val frame = frame ?: return false
        if (!isTracking(frame)) return false
        val tangent = pose.rotateVector(floatArrayOf(x, 0f,
            if (placement.plane.type == Plane.Type.VERTICAL) -y else y))
        val target = translated(pose, tangent)
        if (!placement.plane.isPoseInPolygon(target) || !visiblePlacementPoint(frame, target)) {
            invalidMove(true)
            return false
        }
        pending = AutoPlacementCandidate(placement.plane, target)
        val committed = commitMove()
        if (!committed) pending = null
        invalidMove(!committed)
        return committed
    }

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        val grab = grabOffset(e) ?: return false
        if (!state.beginAdjustment()) return false
        offset = floatArrayOf(grab.x, grab.y, grab.z)
        pending = null
        interruptedMove = false
        updateAnchorPose = false
        return true
    }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        if (!state.isAdjusting) return false
        val frame = frame ?: return false
        val hit = frame.hitTest(e).firstOrNull {
            val plane = it.trackable as? Plane
            plane != null && plane.type == placement.plane.type && plane.subsumedBy == null &&
                plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose) &&
                it.distance in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M
        }
        if (hit == null) { invalidMove(true); return false }
        val delta = offset ?: floatArrayOf(
            pose.tx() - hit.hitPose.tx(),
            pose.ty() - hit.hitPose.ty(),
            pose.tz() - hit.hitPose.tz(),
        ).also { offset = it }
        val plane = hit.trackable as Plane
        // Project grab offset into the new plane to prevent movement out of its surface.
        val normal = plane.centerPose.getTransformedAxis(1, 1f)
        val tangent = wallTangentOffset(
            Position(delta[0], delta[1], delta[2]),
            Float3(normal[0], normal[1], normal[2]),
        )
        val target = orientedPlacementPose(
            translated(hit.hitPose, floatArrayOf(tangent.x, tangent.y, tangent.z)),
            plane, frame.camera.pose,
        )
        if (!plane.isPoseInPolygon(target) || !visiblePlacementPoint(frame, target)) { invalidMove(true); return false }
        pending = AutoPlacementCandidate(plane, target)
        pose = target
        invalidMove(false)
        return true
    }

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
        if (state.isAdjusting) commitMove()
        if (!interruptedMove) {
            updateAnchorPose = true
            pending = null
        }
        offset = null
        invalidMove(false)
        state.endAdjustment()
    }

    /** Where the finger grabbed the node against its own contact plane, null when unusable. */
    private fun grabOffset(e: MotionEvent): Position? {
        val frame = frame ?: return null
        if (!isTracking(frame)) return null
        val ray = collisionSystem?.view?.screenToRay(e.x, e.y) ?: return null
        val axis = pose.getTransformedAxis(1, 1f)
        return wallGrabOffset(
            Position(pose.tx(), pose.ty(), pose.tz()),
            Float3(axis[0], axis[1], axis[2]), ray.origin, ray.direction,
        )
    }

    private fun isTracking(frame: Frame) =
        frame.camera.trackingState == TrackingState.TRACKING &&
            anchor.trackingState == TrackingState.TRACKING

    /** [source] shifted by [delta] in world space, keeping this node's authored rotation. */
    private fun translated(source: Pose, delta: FloatArray) = Pose(
        floatArrayOf(source.tx() + delta[0], source.ty() + delta[1], source.tz() + delta[2]),
        pose.rotationQuaternion,
    )

    private fun commitMove(): Boolean {
        val candidate = pending ?: return false
        val frame = frame ?: return false
        if (anchor.trackingState != TrackingState.TRACKING ||
            !visiblePlacementPoint(frame, candidate.pose)
        ) return false
        val next = candidate.createAnchor() ?: return false
        anchor = next.anchor
        placement.anchor = next.anchor
        placement.plane = next.plane
        placement.pose = next.pose
        pending = null
        return true
    }

    override fun update(session: Session, frame: Frame) {
        val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
            anchor.trackingState == TrackingState.TRACKING
        if (!tracking) {
            if (!updateAnchorPose) {
                // Freeze the last valid drag pose without replacing the logical anchor.
                interruptedMove = pending != null
                offset = null
                state.endAdjustment()
                if (!interruptedMove) updateAnchorPose = true
            }
            // A still-tracking anchor must not move the content while the camera is limited.
            // Keep the frozen pose available for the opacity fade, including anchor loss.
            val followAnchor = updateAnchorPose
            updateAnchorPose = false
            super.update(session, frame)
            updateAnchorPose = followAnchor
            return
        }
        if (interruptedMove && commitMove()) {
            interruptedMove = false
            updateAnchorPose = true
        }
        super.update(session, frame)
    }
}
