package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.DepthHitResult
import io.github.sceneview.ar.arcore.hitTestDepth

/**
 * AR real-time **depth-based** hit-test positioned node — Compose-idiomatic mirror of
 * [HitResultNode] for placement against the ARCore depth image rather than against detected
 * planes / points / instant-placement (#1814).
 *
 * Each AR frame the node re-runs the [hitTest] lambda at the configured selection rule and moves
 * to the world-space surface point under that pixel. Unlike [HitResultNode], a depth hit test
 * resolves a point on *any* real-world geometry the depth camera can see (sofa, slope,
 * cluttered desk) without waiting for ARCore to grow a plane there, and carries a real surface
 * normal — see [DepthHitResult].
 *
 * Requires the [Session] to be configured with a depth mode — [com.google.ar.core.Config.DepthMode.AUTOMATIC]
 * or [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY]. When depth is unavailable (not
 * supported, not yet computed, camera not tracking, no valid depth at the pixel) the node
 * keeps its last known pose — same fallback contract as [HitResultNode].
 *
 * Threading: updated on the AR frame thread (the rendering thread) — same protocol as
 * [PoseNode.update].
 *
 * @param engine    The Filament [Engine].
 * @param hitTest   Custom depth-hit-test selector — receives the live [Frame] and returns a
 *                  [DepthHitResult] (or `null` to keep the previous pose). Lets callers raycast
 *                  at a custom screen anchor, sample multiple pixels, or skip frames cheaply.
 *                  For the common centre-screen / fixed-pixel case use the secondary
 *                  `(engine, xPx, yPx)` constructor — it builds this lambda for you.
 */
open class DepthHitResultNode(
    engine: Engine,
    val hitTest: DepthHitResultNode.(Frame) -> DepthHitResult?,
) : PoseNode(engine) {

    /**
     * Build a depth-hit `DepthHitResultNode` that raycasts at a fixed screen pixel — convenience
     * overload for the common centre-screen / reticle case. Mirrors the screen-pixel form of
     * the sibling [HitResultNode] (#1844).
     *
     * @param engine The Filament [Engine].
     * @param xPx    Screen X coordinate in pixels for the depth hit test.
     * @param yPx    Screen Y coordinate in pixels for the depth hit test.
     */
    constructor(
        engine: Engine,
        xPx: Float,
        yPx: Float,
    ) : this(
        engine = engine,
        hitTest = { frame -> frame.hitTestDepth(xPx, yPx) },
    )

    /**
     * Whether the node re-runs the depth hit test each frame. Default `true`. Set to `false` to
     * freeze the node at its current pose — useful to "lock" the placement after a tap.
     */
    var update: Boolean = true

    /**
     * The latest [DepthHitResult], or `null` until the first successful frame.
     *
     * Exposed read-only so callers can read the surface [DepthHitResult.normal] (e.g. to align a
     * placed object with the surface orientation) or the [DepthHitResult.distance] from the
     * camera.
     */
    var depthHitResult: DepthHitResult? = null
        protected set

    init {
        // Smooth the per-frame jitter inherent to depth sampling — same default as [HitResultNode].
        isSmoothTransformEnabled = true
    }

    override fun update(session: Session, frame: Frame) {
        if (update && frame.camera.trackingState == TrackingState.TRACKING) {
            hitTest(frame)?.let { result ->
                depthHitResult = result
                // Build a `Pose` whose translation is the depth hit point, orientation is identity
                // (the depth hit carries a normal but not a full surface frame). Callers that need
                // the surface normal can read it via [depthHitResult].
                //
                // **Per-frame `Pose` allocation cost (#1846):** ARCore's [Pose] is a small JNI
                // wrapper around two `float[]`s (translation + rotation) — the alloc shows up as
                // ~60 alloc/sec per node at 60 fps. We investigated reusing a single cached Pose
                // and discovered that ARCore [Pose] is *immutable* by design (no setter, no
                // `setTranslation()`), and [DepthHitResult] is built from the raw depth-sample
                // floats — there is no pre-existing Pose on the result we could reuse. So one
                // [Pose.makeTranslation] per active node per frame is the floor for this surface.
                // 60 Hz × small JNI alloc stays well below the AR-frame budget; documenting the
                // invariant here so future perf passes don't try to "fix" it without first
                // verifying [Pose] is still immutable.
                pose = Pose.makeTranslation(result.position.x, result.position.y, result.position.z)
            }
        }
        super.update(session, frame)
    }
}
