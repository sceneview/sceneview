package io.github.sceneview.ar

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/**
 * One-line tap-to-place AR scene — the Sceneform `ArFragment` parity bundle
 * ([#1765](https://github.com/sceneview/sceneview/issues/1765)).
 *
 * Every AR placement demo re-implements the same 30-50 lines of
 * plane-find-then-tap-to-place boilerplate: a center-screen reticle, an ARCore hit-test in the tap
 * gesture, anchor creation, an instant-placement fallback when no plane has converged yet.
 * `PlacementScene` bundles all of it behind a single composable so an app — or an AI generating
 * AR code from `llms.txt` — gets correct, complete tap-to-place behaviour in one call:
 *
 * ```kotlin
 * PlacementScene { anchor ->
 *     AnchorNode(anchor = anchor) {
 *         ModelNode(
 *             modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb"),
 *             scaleToUnits = 0.3f
 *         )
 *     }
 * }
 * ```
 *
 * What it wires for you:
 *  - An [ARSceneView] with [planeRenderer] enabled so the user sees detected surfaces.
 *  - A built-in **reticle** — a thin unlit disc that snaps to the center-screen hit-test result
 *    each frame, so the user can see *where* the next tap will land. Themed via [reticleColor]
 *    (defaults to the DESIGN.md primary cyan). Hide it with `showReticle = false`.
 *  - **Tap-to-place**: each tap on a tracked surface resolves an ARCore [HitResult], creates an
 *    [Anchor], and invokes [onPlaced] inside the [ARSceneScope] so the caller declares whatever
 *    content should ride that anchor. Taps that fall on existing scene nodes are ignored, so a
 *    placed model can still be gesture-edited without spawning a duplicate on top.
 *  - An **instant-placement fallback** ([instantPlacement] = `true` by default): models can be
 *    dropped before ARCore has converged a plane, then snap to the real surface once tracking
 *    catches up — exactly Sceneform's behaviour.
 *
 * The composable keeps the list of created anchors itself; everything the caller declares inside
 * [onPlaced] is composed once per anchor. Call [PlacementController.clear] (via the [controller]
 * the [content] receives) to detach every anchor and wipe the placed content.
 *
 * **Threading:** like every SceneView composable, all Filament JNI work happens on the main
 * thread. [onPlaced] runs inside the Compose [ARSceneScope]; do not call it from a background
 * coroutine.
 *
 * @param modifier              [Modifier] applied to the AR viewport.
 * @param engine                Shared Filament [Engine]. Defaults to [rememberEngine].
 * @param modelLoader           [ModelLoader] for glTF/GLB models. Defaults to [rememberModelLoader].
 * @param materialLoader        [MaterialLoader] for material instances. Defaults to
 *                              [rememberMaterialLoader] — also builds the reticle material.
 * @param planeRenderer         Render the translucent plane-detection overlay. Default `true`.
 * @param planeFindingMode      Which plane orientations ARCore tracks. Default
 *                              [Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL].
 * @param instantPlacement      Enable the instant-placement fallback so taps land before a plane
 *                              has converged. Default `true` (Sceneform `ArFragment` parity).
 * @param showReticle           Show the built-in center-screen placement reticle. Default `true`.
 * @param reticleColor          Reticle disc color. Defaults to the DESIGN.md primary cyan,
 *                              semi-transparent.
 * @param playbackDataset       Optional recorded ARCore MP4 dataset to replay instead of the live
 *                              camera feed. Forwarded verbatim to [ARSceneView]. Default `null`
 *                              (live camera).
 * @param sessionConfiguration  Escape-hatch ARCore [Config] callback, forwarded verbatim to
 *                              [ARSceneView]. Runs after the typed params above.
 * @param onPlaced              Invoked inside the [ARSceneScope] once per created [Anchor]. Declare
 *                              the content (typically an `AnchorNode { ModelNode(...) }`) to ride
 *                              that anchor. Composed once per anchor; recomposes on placement.
 * @param content               Optional extra [ARSceneScope] content — receives a
 *                              [PlacementController] so the caller can [PlacementController.clear]
 *                              all placements or read the live anchor list.
 */
@Composable
fun PlacementScene(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    planeRenderer: Boolean = true,
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    instantPlacement: Boolean = true,
    showReticle: Boolean = true,
    reticleColor: Color = DEFAULT_RETICLE_COLOR,
    playbackDataset: File? = null,
    sessionConfiguration: ((session: com.google.ar.core.Session, Config) -> Unit)? = null,
    onPlaced: @Composable ARSceneScope.(anchor: Anchor) -> Unit,
    content: (@Composable ARSceneScope.(controller: PlacementController) -> Unit)? = null,
) {
    val controller = remember { PlacementController() }

    // Latest ARCore frame, captured from onSessionUpdated so the tap gesture (which runs on the
    // UI thread, off the AR frame callback) can still hit-test against a live frame.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Viewport pixels — needed to hit-test the screen centre for the reticle. Captured via
    // onSizeChanged; until measured the reticle stays hidden so it never races a (0,0) hit.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    ARSceneView(
        modifier = modifier.onSizeChanged { viewportSize = it },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        playbackDataset = playbackDataset,
        planeRenderer = planeRenderer,
        planeFindingMode = planeFindingMode,
        instantPlacementMode = if (instantPlacement) {
            Config.InstantPlacementMode.LOCAL_Y_UP
        } else {
            Config.InstantPlacementMode.DISABLED
        },
        sessionConfiguration = sessionConfiguration,
        onSessionUpdated = { _, frame -> latestFrame = frame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event: MotionEvent, node ->
                // A tap on an existing scene node is a gesture on that node (drag / scale /
                // rotate) — don't spawn a fresh anchor on top of it.
                if (node != null) return@rememberOnGestureListener
                val frame = latestFrame ?: return@rememberOnGestureListener
                placementHit(frame, frame.hitTest(event), instantPlacement)
                    ?.let { hit -> controller.add(hit.createAnchor()) }
            }
        ),
    ) {
        // Built-in reticle — a thin unlit disc that snaps to the centre-screen hit-test each
        // frame so the user previews where the next tap lands. Purely visual: the tap handler
        // above runs its own hit-test at the tap coordinates, so placement is not centre-only.
        if (showReticle && viewportSize != IntSize.Zero) {
            val reticleMaterial = remember(materialLoader, reticleColor) {
                materialLoader.createUnlitColorInstance(reticleColor)
            }
            val centreX = viewportSize.width / 2f
            val centreY = viewportSize.height / 2f
            HitResultNode(
                hitTest = { frame ->
                    placementHit(frame, frame.hitTest(centreX, centreY), instantPlacement)
                },
            ) {
                // 7 cm radius, 5 mm tall disc — sits flush on the detected surface. The
                // HitResultNode pose orients +Y along the surface normal, so the disc lays flat.
                CylinderNode(
                    radius = RETICLE_RADIUS,
                    height = RETICLE_HEIGHT,
                    sideCount = RETICLE_SIDES,
                    materialInstance = reticleMaterial,
                )
            }
        }

        // One caller-declared content block per created anchor. The `key` gives each anchor its
        // own remember slot so models load independently (Filament instances are single-parent).
        controller.anchors.forEach { anchor ->
            key(anchor) {
                onPlaced(anchor)
            }
        }

        content?.invoke(this, controller)
    }
}

/**
 * Holds the [Anchor]s created by tap-to-place inside a [PlacementScene].
 *
 * A fresh instance is `remember`ed per `PlacementScene`. The [content] block of `PlacementScene`
 * receives it so the caller can wipe every placement ([clear]) or inspect the live list
 * ([anchors]) — e.g. to drive a "3 models placed" counter.
 */
class PlacementController internal constructor() {
    private val _anchors = mutableStateListOf<Anchor>()

    /** The anchors created so far, in placement order. Compose-observable. */
    val anchors: List<Anchor> get() = _anchors

    /** Number of placed anchors. Compose-observable convenience accessor. */
    val count: Int get() = _anchors.size

    internal fun add(anchor: Anchor) {
        _anchors += anchor
    }

    /**
     * Detaches every created [Anchor] so ARCore stops tracking them, then drops them from the
     * list — recomposition removes the matching content from the scene. Safe to call from the
     * main thread (e.g. a "Clear All" button `onClick`).
     */
    fun clear() {
        _anchors.forEach { runCatching { it.detach() } }
        _anchors.clear()
    }
}

/** Default reticle color — DESIGN.md primary cyan, semi-transparent. */
val DEFAULT_RETICLE_COLOR: Color = Color(0x99_44_E7_FF)

/** Reticle disc radius, meters. */
internal const val RETICLE_RADIUS = 0.07f

/** Reticle disc height (thickness), meters. */
internal const val RETICLE_HEIGHT = 0.005f

/** Reticle disc tessellation — side count of the cylinder. */
internal const val RETICLE_SIDES = 48

/**
 * Maximum camera-to-hit distance accepted for a placement, meters. Hits further than this are
 * almost certainly noise (a half-converged plane far behind a wall) and are rejected.
 */
internal const val MAX_PLACEMENT_DISTANCE = 5.0f

/**
 * Pure-logic placement hit selector — picks the first acceptable [HitResult] for tap-to-place.
 *
 * Extracted as a top-level function (not a `PlacementScene` local) so it is exercised by
 * pure-JVM unit tests without spinning up Compose or an ARCore session.
 *
 * Rules, applied in order to each candidate:
 *  1. The camera must be `TRACKING` — placements off a lost session are dropped.
 *  2. The trackable must be `TRACKING`.
 *  3. The hit must be within [MAX_PLACEMENT_DISTANCE] of the camera.
 *  4. A [Plane] hit must fall inside the plane polygon (no anchoring off a half-grown plane edge).
 *  5. An [com.google.ar.core.InstantPlacementPoint] hit is accepted **only** when
 *     [instantPlacement] is enabled — this is the Sceneform `ArFragment` "place before a plane
 *     exists" fallback.
 *  6. Any other trackable type ([com.google.ar.core.Point],
 *     [com.google.ar.core.DepthPoint]) is rejected — `PlacementScene` is a plane-or-instant
 *     placement bundle by design; depth-surface placement is the job of `DepthHitResultNode`.
 *
 * @param frame             The live ARCore [Frame] the hit-test came from.
 * @param hits              The hit-test results to choose from (already sorted near-to-far by
 *                          ARCore).
 * @param instantPlacement  Whether [com.google.ar.core.InstantPlacementPoint] hits are eligible.
 * @return the first acceptable [HitResult], or `null` if none qualifies (keep the previous pose).
 */
fun placementHit(
    frame: Frame,
    hits: List<HitResult>,
    instantPlacement: Boolean,
): HitResult? {
    if (frame.camera.trackingState != TrackingState.TRACKING) return null
    return hits.firstOrNull { hit -> isPlacementHit(hit, instantPlacement) }
}

/**
 * Per-[HitResult] acceptance predicate for [placementHit] — see that function's KDoc for the
 * rule list. Split out so the trackable-type branching is unit-testable in isolation.
 *
 * Reads the JNI-bound [HitResult] / [com.google.ar.core.Trackable] once, then delegates to the
 * pure-JVM [isPlacementHit] overload (which the unit tests exercise without an ARCore session,
 * mirroring the `streetscapeGeometryPasses` split landed by #1772).
 */
internal fun isPlacementHit(hit: HitResult, instantPlacement: Boolean): Boolean {
    val trackable = hit.trackable
    val kind = when (trackable) {
        is Plane -> if (trackable.isPoseInPolygon(hit.hitPose)) {
            PlacementTrackableKind.PLANE_IN_POLYGON
        } else {
            PlacementTrackableKind.PLANE_OUT_OF_POLYGON
        }
        is com.google.ar.core.InstantPlacementPoint -> PlacementTrackableKind.INSTANT_PLACEMENT
        else -> PlacementTrackableKind.OTHER
    }
    return isPlacementHit(
        trackableKind = kind,
        trackableTracking = trackable.trackingState == TrackingState.TRACKING,
        distance = hit.distance,
        instantPlacement = instantPlacement,
    )
}

/**
 * The trackable categories [PlacementScene] cares about when filtering a tap hit. ARCore's
 * `Trackable` hierarchy is JNI-only and unmockable; collapsing it to this plain enum lets the
 * acceptance rule be unit-tested without a live ARCore session.
 */
enum class PlacementTrackableKind {
    /** A [Plane] hit whose pose lies inside the plane polygon — accepted. */
    PLANE_IN_POLYGON,

    /** A [Plane] hit that fell outside the polygon (half-grown plane edge) — rejected. */
    PLANE_OUT_OF_POLYGON,

    /** An [com.google.ar.core.InstantPlacementPoint] hit — accepted only when instant placement is on. */
    INSTANT_PLACEMENT,

    /** Any other trackable ([com.google.ar.core.Point], [com.google.ar.core.DepthPoint]) — rejected. */
    OTHER,
}

/**
 * Pure-JVM acceptance rule for a single placement hit — the testable core of [isPlacementHit].
 *
 * Mirrors the `streetscapeGeometryPasses` enum-overload pattern from #1772: the production
 * `HitResult` overload above maps the JNI types into [PlacementTrackableKind] + primitives, then
 * calls this function. Unit tests call this overload directly with plain values.
 *
 * Rules:
 *  - The trackable must be tracking.
 *  - The hit must be within [MAX_PLACEMENT_DISTANCE] of the camera.
 *  - [PlacementTrackableKind.PLANE_IN_POLYGON] is always accepted.
 *  - [PlacementTrackableKind.INSTANT_PLACEMENT] is accepted only when [instantPlacement] is on.
 *  - [PlacementTrackableKind.PLANE_OUT_OF_POLYGON] and [PlacementTrackableKind.OTHER] are rejected.
 *
 * @param trackableKind     The mapped trackable category.
 * @param trackableTracking Whether the trackable's state is `TRACKING`.
 * @param distance          Camera-to-hit distance, meters.
 * @param instantPlacement  Whether [PlacementTrackableKind.INSTANT_PLACEMENT] hits are eligible.
 */
internal fun isPlacementHit(
    trackableKind: PlacementTrackableKind,
    trackableTracking: Boolean,
    distance: Float,
    instantPlacement: Boolean,
): Boolean {
    if (!trackableTracking) return false
    if (distance > MAX_PLACEMENT_DISTANCE) return false
    return when (trackableKind) {
        PlacementTrackableKind.PLANE_IN_POLYGON -> true
        PlacementTrackableKind.INSTANT_PLACEMENT -> instantPlacement
        PlacementTrackableKind.PLANE_OUT_OF_POLYGON,
        PlacementTrackableKind.OTHER -> false
    }
}
