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
import com.google.ar.core.TrackingFailureReason
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
    /** Passed to `ModelNode(scaleToUnits = …)`. Both surfaces use 0.3f today. */
    val scaleToUnits: Float = 0.3f,
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
