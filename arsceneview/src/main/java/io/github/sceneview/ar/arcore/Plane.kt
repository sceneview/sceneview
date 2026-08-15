package io.github.sceneview.ar.arcore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer

/**
 * The 2D vertices of a polygon approximating the detected plane, in the form
 * `[x1, z1, x2, z2, ...]` in plane-local coordinates (X-Z plane, Y = 0).
 *
 * Wraps [Plane.getPolygon] — exposed as a Kotlin property so plane-snapping helpers,
 * plane-merge detection, and custom plane visualizers don't have to redeclare the boilerplate
 * every time.
 *
 * The returned buffer is owned and recycled by ARCore: **do not retain it across frames**.
 * Either consume it immediately or copy the floats into your own buffer.
 *
 * The polygon is empty (`limit() == 0`) until the plane has been tracked for long enough
 * to establish its extents.
 *
 * @see com.google.ar.core.Plane.getPolygon
 */
val Plane.polygon: FloatBuffer get() = getPolygon()

/**
 * The plane that this plane has been subsumed by, or `null` if this plane has not been
 * subsumed.
 *
 * When two trackable planes are detected to be coplanar and overlapping, ARCore merges them
 * — the smaller one is removed from the active tracked-planes list and its
 * [Plane.getSubsumedBy] points at the surviving plane.
 *
 * Apps anchored to a now-subsumed plane should re-anchor onto [subsumedBy] to keep their
 * anchor in sync with the merged geometry. The trackable state of a subsumed plane goes
 * to [com.google.ar.core.TrackingState.STOPPED].
 *
 * @see com.google.ar.core.Plane.getSubsumedBy
 */
val Plane.subsumedBy: Plane? get() = getSubsumedBy()

/**
 * The three plane-lifecycle deltas between two consecutive frames (#1774) — the SceneView
 * equivalent of AR Foundation's `ARPlanesChangedEventArgs` (`added` / `updated` / `removed`).
 *
 * @property added   Planes that appear in the new tracked set but not the previous one.
 * @property updated Planes present in both sets — still tracked, geometry possibly refined.
 * @property removed Planes that were tracked in the previous set but no longer are (typically
 *                   subsumed by a larger coplanar plane, see [subsumedBy]).
 */
data class PlanesDiff(
    val added: List<Plane>,
    val updated: List<Plane>,
    val removed: List<Plane>
)

/**
 * Pure-logic diff between a [previous] and [current] set of detected planes (#1774).
 *
 * Split out from [rememberDetectedPlanes] so the add / update / remove classification — the part
 * apps actually depend on for `ARPlaneManager.planesChanged` parity — is covered by pure-JVM
 * unit tests without an ARCore [Session] handle. Set membership is the correct comparison, but not
 * for the reason one would assume: ARCore returns a **fresh** `Plane` wrapper on every frame, so
 * reference identity would always be false. `TrackableBase` overrides `equals`/`hashCode` on its
 * native handle, and `Plane` inherits both unchanged — that value equality is what makes a `Set`
 * of planes stable across frames.
 *
 * Delegates to the generic [diffTrackedSet] so the set-membership logic — the only part with any
 * branching — is exercised by pure-JVM tests against plain objects (ARCore's `Plane` is JNI-only
 * and cannot be constructed under unit tests).
 *
 * @return a [PlanesDiff] whose three lists partition `previous ∪ current`. The lists are empty
 *         (never `null`) when there is no change in that category.
 */
fun diffPlanes(previous: Set<Plane>, current: Set<Plane>): PlanesDiff {
    val (added, updated, removed) = diffTrackedSet(previous, current)
    return PlanesDiff(added = added, updated = updated, removed = removed)
}

/**
 * Generic added / updated / removed partition of `previous ∪ current` for any reference type
 * (#1774). The testable core of [diffPlanes] — see that function for the plane-specific semantics.
 *
 * @return a [Triple] of `(added, updated, removed)` lists; each list is empty (never `null`)
 *         when nothing falls in that category.
 */
internal fun <T> diffTrackedSet(
    previous: Set<T>,
    current: Set<T>
): Triple<List<T>, List<T>, List<T>> = Triple(
    current.filter { it !in previous },
    current.filter { it in previous },
    previous.filter { it !in current }
)

/**
 * Apply the per-frame trackable deltas to an incremental [tracked] cache (#2270) — the testable
 * core of the incremental path in [rememberDetectedPlanes].
 *
 * For each item in [updated] (ARCore's `frame.getUpdatedTrackables(...)` delta, i.e. only the
 * trackables whose state changed this frame): keep it in [tracked] when [isActive] returns `true`
 * (still TRACKING), otherwise drop it (STOPPED/PAUSED — e.g. a subsumed or lost plane). ARCore
 * surfaces every state transition through that delta, so applying just the delta keeps [tracked]
 * in sync with the full live set without re-scanning `getAllTrackables` every frame.
 *
 * Mutates [tracked] in place and returns it for chaining. `internal` so the membership bookkeeping
 * is unit-testable against plain objects (ARCore's `Plane` is JNI-only and can't be constructed
 * under unit tests).
 */
internal fun <T> applyTrackedUpdates(
    tracked: MutableSet<T>,
    updated: Iterable<T>,
    isActive: (T) -> Boolean
): MutableSet<T> {
    for (item in updated) {
        if (isActive(item)) tracked += item else tracked -= item
    }
    return tracked
}

/**
 * Classic ray-casting point-in-polygon test (#2203 PR #2) — returns true when query point
 * `(x, z)` lies inside the simple [polygon] expressed as `[x0, z0, x1, z1, ...]` in
 * plane-local X-Z coordinates.
 *
 * Used by [io.github.sceneview.ar.arcore.buildPlaneDepthMeshGeometry] to clip depth-driven
 * vertices to the visible portion of an ARCore [Plane.getPolygon] — without it, the depth
 * mesh would extend across the entire camera frustum and ignore the plane's own shape.
 *
 * `O(n)` in `polygon.size / 2`. Treats boundary points as inside (the canonical half-open
 * interval `(z0 > z) != (z1 > z)` guard handles the colinear-with-an-edge case without
 * double-counting). Returns false for empty or degenerate polygons (< 3 vertices). Self-
 * intersecting polygons are out of scope — ARCore guarantees simple polygons so the test
 * is well-defined for the actual input.
 *
 * Polygon array layout matches [polygon] / [Plane.getPolygon]: `[x0, z0, x1, z1, ...]`.
 *
 * `internal` so the geometry math stays unit-testable without an ARCore [com.google.ar.core.Plane].
 */
internal fun pointInPolygon2D(x: Float, z: Float, polygon: FloatArray): Boolean {
    val pointCount = polygon.size / 2
    if (pointCount < 3) return false
    var inside = false
    var j = pointCount - 1
    for (i in 0 until pointCount) {
        val xi = polygon[i * 2]
        val zi = polygon[i * 2 + 1]
        val xj = polygon[j * 2]
        val zj = polygon[j * 2 + 1]
        // Half-open interval trick: an edge is counted iff exactly one of its endpoints is
        // strictly above the horizontal ray through (x, z). Prevents double-counting when
        // the ray passes through a shared vertex, and handles horizontal edges gracefully
        // (they trivially fail the condition).
        if ((zi > z) != (zj > z)) {
            val xIntersection = (xj - xi) * (z - zi) / (zj - zi) + xi
            if (x < xIntersection) inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Compose `State<List<Plane>>` that tracks the live set of ARCore-detected planes and fires
 * lifecycle callbacks as planes appear, refine, and disappear (#1774).
 *
 * This is the SceneView equivalent of AR Foundation's `ARPlaneManager`: rather than writing a
 * `frame.getUpdatedTrackables(Plane::class.java)` loop and diffing it by hand, apps observe the
 * returned `State` (or wire the [onAdded] / [onUpdated] / [onRemoved] callbacks) and pair each
 * plane with a [io.github.sceneview.ar.ARSceneScope.PlaneNode] composable:
 *
 * ```kotlin
 * ARSceneView(onSessionCreated = { arSession = it }) {
 *     val planes by rememberDetectedPlanes(
 *         session = arSession,
 *         onAdded = { added -> /* e.g. play a chime, count detected surfaces */ }
 *     )
 *     planes.forEach { plane ->
 *         PlaneNode(plane = plane) {
 *             ModelNode(modelInstance = rememberModelInstance(modelLoader, "marker.glb"))
 *         }
 *     }
 * }
 * ```
 *
 * The set is re-evaluated every Compose frame via [withFrameNanos], so it inherits Compose's natural
 * cadence and pauses with the composition — no separate timer to manage. Only planes in
 * [TrackingState.TRACKING] are reported, so a subsumed plane naturally surfaces through
 * [onRemoved]. Returns an empty list while [session] is `null` (AR lifecycle paused) or before
 * the first plane is detected.
 *
 * **Performance (#2270):** when the passed [session] is SceneView's [ARSession], the live set is
 * maintained **incrementally** from the per-frame delta `frame.getUpdatedPlanes()` (ARCore returns
 * only the planes whose state changed this frame) plus a mutable cache, instead of recomputing the
 * full `session.getAllTrackables(Plane).filter { … }.toSet()` on every Compose frame. The
 * add/update/remove classification is identical — only how the current snapshot is derived changes.
 * If [session] is a plain ARCore [Session] (no per-frame handle available) or no frame has been
 * produced yet, it transparently falls back to the original full-rescan path.
 *
 * @param session  The ARCore [Session], typically captured from `ARSceneView`'s `onSessionCreated`.
 * @param onAdded   Invoked with the planes newly detected since the previous frame.
 * @param onUpdated Invoked with the still-tracked planes whose geometry ARCore may have refined.
 * @param onRemoved Invoked with the planes no longer tracked (subsumed or lost).
 */
@Composable
fun rememberDetectedPlanes(
    session: Session?,
    onAdded: ((List<Plane>) -> Unit)? = null,
    onUpdated: ((List<Plane>) -> Unit)? = null,
    onRemoved: ((List<Plane>) -> Unit)? = null
): State<List<Plane>> {
    return produceState<List<Plane>>(initialValue = emptyList(), key1 = session) {
        if (session == null) {
            value = emptyList()
            return@produceState
        }
        var previous: Set<Plane> = emptySet()
        // #2270: the incremental cache of currently-TRACKING planes, mutated frame-to-frame from
        // `frame.getUpdatedPlanes()` so we never recompute the whole set unless we must fall back.
        val tracked = LinkedHashSet<Plane>()
        var trackedSeeded = false
        var lastFrameTimestamp = 0L
        while (true) {
            withFrameNanos {
                // All ARCore reads here run on the Compose frame callback (main thread),
                // satisfying the ARCore main-thread contract.
                val arSession = session as? ARSession
                val frame = arSession?.frame
                val current: Set<Plane> = if (frame != null) {
                    // First time we get a frame, seed the cache from the full trackable list so a
                    // fallback→incremental hand-off (or planes detected before the first frame was
                    // observed here) doesn't spuriously report every plane as removed.
                    if (!trackedSeeded) {
                        trackedSeeded = true
                        session.getAllTrackables(Plane::class.java)
                            .filterTo(tracked) { it.trackingState == TrackingState.TRACKING }
                    }
                    // Incremental path: apply only this frame's plane deltas to the cache.
                    // ARCore surfaces every state transition (including → STOPPED for subsumed
                    // or lost planes) through getUpdatedPlanes, so the cache stays in sync.
                    // Re-applying on the same frame timestamp is a no-op for an idempotent set.
                    if (frame.timestamp != lastFrameTimestamp) {
                        lastFrameTimestamp = frame.timestamp
                        applyTrackedUpdates(tracked, frame.getUpdatedPlanes()) {
                            it.trackingState == TrackingState.TRACKING
                        }
                    }
                    tracked
                } else {
                    // Fallback: full rescan (plain Session, or no frame produced yet).
                    session.getAllTrackables(Plane::class.java)
                        .filterTo(LinkedHashSet()) { it.trackingState == TrackingState.TRACKING }
                }
                if (current != previous) {
                    val diff = diffPlanes(previous, current)
                    if (diff.added.isNotEmpty()) onAdded?.invoke(diff.added)
                    if (diff.updated.isNotEmpty()) onUpdated?.invoke(diff.updated)
                    if (diff.removed.isNotEmpty()) onRemoved?.invoke(diff.removed)
                    // Snapshot a copy — `tracked` is mutated in place on later frames.
                    previous = current.toSet()
                    value = current.toList()
                }
            }
        }
    }
}
