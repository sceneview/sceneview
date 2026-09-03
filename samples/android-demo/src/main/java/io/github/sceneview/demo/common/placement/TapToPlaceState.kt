package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.math.Rotation

/**
 * What one accepted tap places. Produced by the host's `onPlaceModel` lambda at
 * tap time (never captured at composition — the
 * [#2476](https://github.com/sceneview/sceneview/issues/2476) stale-closure class
 * is impossible by construction).
 */
@Immutable
data class PlacementSpec(
    /**
     * `file://…` URI for a streamed asset OR `assets/`-relative path for a bundled
     * GLB. Loaded via `rememberModelInstance(modelLoader, fileLocation = …)` — the
     * named-param overload that scheme-detects both forms
     * ([#1422](https://github.com/sceneview/sceneview/issues/1422) /
     * [#2302](https://github.com/sceneview/sceneview/issues/2302) overload trap).
     */
    val assetLocation: String,
    /** User-facing name — drives "Tap a surface to place {name}". */
    val displayName: String,
    /**
     * The object's real-world size in metres, passed to `ModelNode(scaleToUnits = …)`.
     * This is what 100 % means on the pinch readout — see
     * [PlacementModel.realWorldSizeMeters] (#3326).
     */
    val realWorldSizeMeters: Float = 0.3f,
    /**
     * Optional per-asset rotation override. `null` ⇒ the session applies
     * [io.github.sceneview.demo.demos.internal.DemoMath.placementRotationFor]
     * ([#1477](https://github.com/sceneview/sceneview/issues/1477) — the helmet's
     * −90° X correction; identity for everything else).
     */
    val rotationOverride: Rotation? = null,
)

/**
 * A committed placement (internal render-list entry; moved out of
 * `ARPlacementDemo`'s private `PlacedModel`).
 */
internal data class PlacedModel(
    val id: Int,
    val anchor: Anchor,
    val spec: PlacementSpec,
    /**
     * The ARCore [Trackable] the anchor was created against, or `null` for a placement made
     * before #3405 taught the session to care.
     *
     * Only read to answer one question — is this an `InstantPlacementPoint`, and has it
     * refined from `SCREENSPACE_WITH_APPROXIMATE_DISTANCE` to `FULL_TRACKING` yet — which is
     * the whole substance of the folded `ar-instant-placement` demo. See
     * [instantTrackingLabel].
     */
    val trackable: Trackable? = null,
)

/**
 * Which edit gesture is live on a placed model (typed; was a raw `String` in the
 * original `ARPlacementDemo`).
 */
enum class PlacementGesture { MOVING, ROTATING, SCALING }

/**
 * The camera/plane/reticle axis of the UX state machine. `placedCount` and
 * `activeGesture` are orthogonal observables layered on top — see [TapToPlaceState].
 */
enum class TapToPlaceUxState {
    /** No camera frame delivered yet — viewport is jet black (#2484). */
    INITIALIZING,

    /** Camera not TRACKING, or a forced failure override is set (#1881). */
    TRACKING_LOST,

    /** Camera TRACKING but no Plane has reached TRACKING yet (#2234 gate). */
    SCANNING,

    /** ≥1 plane tracked, but the centre-screen hit test is empty (#1882 prompt). */
    AIMING,

    /** Centre reticle is locked on a real surface — a tap will place. */
    READY,
}

/**
 * Hoisted state holder — created by the host via [rememberTapToPlaceState], written
 * by the session, read by the host (status pills, reset buttons, "Next tap places").
 */
@Stable
class TapToPlaceState internal constructor() {
    /** True once the first `onSessionUpdated` frame arrived (drives the init scrim). */
    var cameraReady: Boolean by mutableStateOf(false)
        internal set

    /**
     * Non-null once ARCore has ruled the session out on this device (#3341).
     *
     * [cameraReady] can never flip in that case, so everything keyed off "still starting" —
     * the init scrim above all — has to read this too or it waits forever. The SDK draws the
     * explanation card itself; this is what tells the demo's own chrome to stop claiming AR
     * is on its way.
     */
    var arCoreAvailability: ARCoreAvailability? by mutableStateOf(null)
        internal set

    var isTracking: Boolean by mutableStateOf(false)
        internal set

    var trackingFailureReason: TrackingFailureReason? by mutableStateOf(null)
        internal set

    /** #2234 — at least one ARCore Plane is in `TrackingState.TRACKING`. */
    var anyPlaneTracked: Boolean by mutableStateOf(false)
        internal set

    /** Latest accepted centre-screen hit, `null` when nothing is under the reticle. */
    var reticleHit: HitResult? by mutableStateOf(null)
        internal set

    var activeGesture: PlacementGesture? by mutableStateOf(null)
        internal set

    /**
     * Percentage of real-world size the model under the live pinch currently sits at, or
     * `null` when no pinch is in flight (#3326).
     *
     * Scene Viewer's readout: the number is only on screen while the user is resizing, and
     * `100` is the value the gesture snaps to — see [PlacementScale].
     */
    var scalePercent: Int? by mutableStateOf(null)
        internal set

    /**
     * `true` while the live pinch sits exactly at real-world size, so the readout can
     * emphasise the detent it just snapped into.
     */
    var isRealWorldSize: Boolean by mutableStateOf(false)
        internal set

    /**
     * Timestamp (`SystemClock.uptimeMillis`) of the most recent placement, or `0` when
     * nothing has been placed. Opens the one-shot "drag / twist / pinch" hint window —
     * see [PLACEMENT_GESTURE_HINT_MS].
     */
    var lastPlacedAtMillis: Long by mutableStateOf(0L)
        internal set

    /**
     * Whether the **most recent** placement is still an approximation, or `null` when it is
     * anchored to a real plane and there is nothing to report (#3405).
     *
     * Deliberately the latest placement rather than a count across all of them: the badge
     * answers "is the thing I just dropped real yet", which is a question about one object,
     * and an aggregate ("2 of 5 approximating") reads as telemetry rather than as feedback.
     */
    var instantTracking: InstantTrackingLabel? by mutableStateOf(null)
        internal set

    internal val placedModels = mutableStateListOf<PlacedModel>()
    internal var nextId: Int = 0
    val placedCount: Int get() = placedModels.size

    /** Derived — pure function of the fields above; see [deriveUxState]. */
    val uxState: TapToPlaceUxState
        get() = deriveUxState(
            cameraReady = cameraReady,
            isTracking = isTracking,
            forcedFailure = ForcedTrackingFailure.override != null,
            anyPlaneTracked = anyPlaneTracked,
            hasReticleTarget = reticleHit != null,
        )

    /**
     * Detach every ARCore anchor and clear the placed list. The shared semantics of
     * the demo's "Clear All" and the AR-View exit path. `Anchor.detach()` is
     * idempotent and `AnchorNode.destroy()` also detaches, so calling this before
     * disposal is belt-and-braces, not load-bearing.
     */
    fun clearAll() {
        placedModels.forEach { runCatching { it.anchor.detach() } }
        placedModels.clear()
        // Reset the interaction read-outs too: a cleared scene has no live pinch and no
        // "you just placed something" window, and leaving either latched would keep a
        // stale "100 %" or a stale gesture hint on an empty room (#3326).
        activeGesture = null
        scalePercent = null
        isRealWorldSize = false
        lastPlacedAtMillis = 0L
        instantTracking = null
    }
}

@Composable
fun rememberTapToPlaceState(): TapToPlaceState = remember { TapToPlaceState() }

/**
 * Pure derivation of the camera/plane/reticle UX state — the headless-testable core.
 *
 * Priority order is significant and pinned by unit test: [TapToPlaceUxState.INITIALIZING]
 * wins over everything (the scrim covers all pills); a forced/real failure beats plane
 * state; plane state beats reticle state.
 */
internal fun deriveUxState(
    cameraReady: Boolean,
    isTracking: Boolean,
    forcedFailure: Boolean,
    anyPlaneTracked: Boolean,
    hasReticleTarget: Boolean,
): TapToPlaceUxState = when {
    !cameraReady -> TapToPlaceUxState.INITIALIZING
    !isTracking || forcedFailure -> TapToPlaceUxState.TRACKING_LOST
    !anyPlaneTracked -> TapToPlaceUxState.SCANNING
    !hasReticleTarget -> TapToPlaceUxState.AIMING
    else -> TapToPlaceUxState.READY
}

/**
 * Whether to render the plane-detection grid — and, crucially, the V1 plane renderer's OWN
 * shadow receiver that ships with it (`plane_renderer_shadow.filamat`, a `shadowMultiplier`
 * quad on every tracked plane).
 *
 * The grid guides surface discovery, then recedes once the first model is placed — mirroring
 * [io.github.sceneview.ar.PlacementScene]'s `fadePlaneOnFirstPlacement` and Google's AR design
 * guidance (stop decorating the floor after it has served its discovery purpose). Hiding it
 * after placement is what breaks the #2657 double-receiver stack: it removes the V1 renderer's
 * built-in shadow receiver so it can never coexist with the [shouldCatchGroundShadows]
 * `ShadowReceiverPlane` on the same plane.
 */
internal fun shouldRenderPlaneGrid(placedCount: Int): Boolean = placedCount == 0

/**
 * Whether to attach a contact-shadow catcher (`ShadowReceiverPlane`) per tracked plane.
 *
 * Only once a model is placed is there anything casting a shadow — and by then the plane grid
 * (and its V1 shadow receiver, see [shouldRenderPlaneGrid]) has receded, so the catcher is the
 * SINGLE shadow receiver on the plane. This is exactly the inverse of [shouldRenderPlaneGrid]:
 * the two are mutually exclusive by construction, so a plane is never covered by two coplanar
 * `shadowMultiplier` receivers at once (the #2657 z-fight + double-darkening, 0.4 × 0.4 ≈ 0.16).
 */
internal fun shouldCatchGroundShadows(placedCount: Int): Boolean = placedCount > 0

/**
 * Single source of truth for "does this hit accept a placement / reticle lock".
 *
 * Both the `onSingleTapConfirmed` handler and the `HitResultNode(hitTest = …)` lambda
 * call [accept] with values unpacked from the ARCore `HitResult`. Mirrors the original
 * hand-duplicated filter in `ARPlacementDemo` exactly:
 *  - trackable must be TRACKING
 *  - distance ≤ [MAX_HIT_DISTANCE_M]
 *  - `snapToPlane` ON  ⇒ plane && poseInPolygon
 *  - `snapToPlane` OFF ⇒ plane hits still polygon-gated; non-plane hits accepted (#1883)
 */
internal object PlacementHitPolicy {
    /** Maximum ARCore hit distance, in metres, that accepts a placement / reticle lock. */
    const val MAX_HIT_DISTANCE_M = 5.0f

    fun accept(
        isPlane: Boolean,
        isPoseInPolygon: Boolean,
        isTrackableTracking: Boolean,
        distanceMeters: Float,
        snapToPlane: Boolean,
    ): Boolean {
        if (!isTrackableTracking) return false
        if (distanceMeters > MAX_HIT_DISTANCE_M) return false
        return if (snapToPlane) {
            isPlane && isPoseInPolygon
        } else {
            // Free placement: any tracked surface, but Plane hits must still fall
            // inside the polygon so we don't anchor off the edge of a half-converged
            // plane.
            if (isPlane) isPoseInPolygon else true
        }
    }
}
