package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.animation.slerp
import io.github.sceneview.ar.arcore.quaternion

/**
 * Depth Lab `OrientedReticle`-style orientation damping (#2241 Sprint-1, PR 4).
 *
 * Keeps the last applied rotation and, on every new sample, slerps from it toward the
 * target by a fixed per-frame [smoothing] fraction — an exponential approach that kills
 * the frame-to-frame jitter of ARCore's refined surface normals without adding
 * perceptible lag (Depth Lab ships 0.75).
 *
 * The first sample after construction or [reset] is applied verbatim (no easing from an
 * arbitrary identity), so the reticle never visibly "rolls in" when it first acquires a
 * surface.
 */
internal class ReticleOrientationSmoother(smoothing: Float) {

    /** Per-frame slerp fraction in `0..1`. `1` = no smoothing (raw hit orientation). */
    var smoothing: Float = smoothing.coerceIn(0.0f, 1.0f)
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
        }

    private var current: Quaternion? = null

    /** Damps [target] against the previous output and returns the new orientation. */
    fun smooth(target: Quaternion): Quaternion {
        val next = current?.let { slerp(it, target, smoothing) } ?: target
        current = next
        return next
    }

    /** Forgets the damping state — the next [smooth] call applies its target verbatim. */
    fun reset() {
        current = null
    }
}

/**
 * AR placement cursor with surface-normal smoothing — the Sprint-1 (#2241) layer on top
 * of [ReticleNode], a port of ARCore Depth Lab's `OrientedReticle`.
 *
 * Two upgrades over the plain [ReticleNode]:
 *
 * - **Orientation smoothing.** ARCore refines a surface normal frame by frame, which
 *   makes a raw reticle disc visibly jitter on textured or slanted surfaces. The node
 *   slerps each frame's rotation toward the hit orientation by [orientationSmoothing]
 *   (Depth Lab's `Quaternion.Slerp(current, target, 0.75f)`); the *damping layer* leaves
 *   the position verbatim (the node's inherited smooth-transform easing still applies to
 *   the final motion, as on [ReticleNode]). `1.0f` disables the damping; `0.0f` freezes
 *   the orientation at the first acquired surface — use a small positive value if you
 *   want heavy damping that still converges.
 * - **Depth-capable acceptance.** Pass `depthPoint = true` to also accept depth-based
 *   hits — the cursor then lands on arbitrary geometry (sofa, slope, cluttered desk)
 *   without waiting for a detected plane. Requires the session depth mode ≠ `DISABLED`;
 *   the default is **off** so a no-depth device behaves exactly like [ReticleNode]
 *   (#1891 plane-only contract).
 *
 * A `null` hit (ray misses every accepted trackable) resets the damping state, so the
 * next surface is re-acquired verbatim at the damping layer (the base smooth-transform
 * easing still animates the node's rendered motion).
 *
 * `snapToPlane = false` is **free placement**: feature-point hits become acceptable and
 * plane hits must still fall inside the polygon (`planePoseInPolygon` stays on) — the
 * same contract as the demos\' `PlacementHitPolicy`. Route project-specific acceptance
 * (e.g. a max-distance cap) through [predicate].
 *
 * @param engine                Filament [Engine] that owns this node.
 * @param xPx                   View X coordinate in pixels for the per-frame hit test.
 * @param yPx                   View Y coordinate in pixels for the per-frame hit test.
 * @param snapToPlane           `true` = plane-only (#1891 default); `false` = free
 *                              placement (adds feature-point hits, planes stay
 *                              in-polygon).
 * @param depthPoint            Also accept depth hits (needs depth mode enabled).
 * @param predicate             Custom acceptance filter for each candidate hit. When set
 *                              it REPLACES the built-in trackable-type / in-polygon /
 *                              tracking-state checks (only the camera-distance floor still
 *                              applies) — re-check any built-in condition you still need
 *                              inside it. Construction-time only.
 * @param orientationSmoothing  Per-frame slerp fraction in `0..1` toward the hit
 *                              orientation. Default [DEFAULT_ORIENTATION_SMOOTHING]
 *                              (= 0.75, the Depth Lab value); `1.0f` = raw orientation.
 * @param onHitResultChanged    Invoked whenever the resolved [HitResult] changes
 *                              (including transitions to / from `null`) — drives
 *                              AIMING / READY host state.
 *
 * @see io.github.sceneview.ar.ARSceneScope.PlacementReticle
 * @see ReticleNode
 */
open class PlacementReticleNode(
    engine: Engine,
    xPx: Float,
    yPx: Float,
    snapToPlane: Boolean = true,
    depthPoint: Boolean = false,
    orientationSmoothing: Float = DEFAULT_ORIENTATION_SMOOTHING,
    predicate: ((HitResult) -> Boolean)? = null,
    onHitResultChanged: ((HitResult?) -> Unit)? = null
) : ReticleNode(
    engine = engine,
    xPx = xPx,
    yPx = yPx,
    // Planes are always candidates (in-polygon enforced by the base default);
    // free placement (snapToPlane = false) additionally accepts feature points —
    // the PlacementHitPolicy contract, not "no planes".
    planeTypes = Plane.Type.values().toSet(),
    point = !snapToPlane,
    depthPoint = depthPoint,
    predicate = predicate,
    onHitResultChanged = onHitResultChanged
) {

    private val smoother = ReticleOrientationSmoother(orientationSmoothing)

    /**
     * Per-frame slerp fraction in `0..1` toward the hit orientation. Adjustable live;
     * values outside `0..1` are coerced. `1.0f` applies the raw hit orientation.
     */
    var orientationSmoothing: Float
        get() = smoother.smoothing
        set(value) {
            smoother.smoothing = value
        }

    /**
     * Resets the damping when the ray misses (so the next surface is acquired verbatim
     * at the damping layer), then lets the base setter apply pose + change callback.
     */
    override var hitResult: HitResult?
        get() = super.hitResult
        set(value) {
            if (value == null) smoother.reset()
            super.hitResult = value
        }

    /**
     * Damps only the rotation component; the translation is the verbatim hit position.
     * Runs once per accepted hit via the [HitResultNode.resolveHitPose] hook, so the node
     * pose is written a single time per frame, already smoothed.
     *
     * Note the node's inherited `isSmoothTransformEnabled` easing still applies on top
     * (same as [ReticleNode]) — this layer removes the *surface-normal jitter* the base
     * easing can't (it eases toward whatever target it is given; a jittering target stays
     * jittery). Whether to disable the base easing for reticles is a PR 5 device-QA call.
     */
    override fun resolveHitPose(hitPose: Pose): Pose {
        val smoothed = smoother.smooth(hitPose.quaternion)
        return Pose(
            floatArrayOf(hitPose.tx(), hitPose.ty(), hitPose.tz()),
            floatArrayOf(smoothed.x, smoothed.y, smoothed.z, smoothed.w)
        )
    }

    companion object {
        /**
         * Default [orientationSmoothing] — 0.75, matching ARCore Depth Lab's
         * `OrientedReticle` slerp factor.
         */
        const val DEFAULT_ORIENTATION_SMOOTHING = 0.75f
    }
}
