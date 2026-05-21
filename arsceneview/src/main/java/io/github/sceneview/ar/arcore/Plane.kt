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
 * unit tests without an ARCore [Session] handle. `Plane` identity is by reference: ARCore returns
 * the same `Plane` instance for a given trackable across frames, so set membership is the correct
 * comparison.
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
 * The set is re-read every Compose frame via [withFrameNanos], so it inherits Compose's natural
 * cadence and pauses with the composition — no separate timer to manage. Only planes in
 * [TrackingState.TRACKING] are reported, so a subsumed plane naturally surfaces through
 * [onRemoved]. Returns an empty list while [session] is `null` (AR lifecycle paused) or before
 * the first plane is detected.
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
        while (true) {
            withFrameNanos {
                // getAllTrackables runs on the caller thread — here the Compose frame
                // callback on the main thread, satisfying the ARCore main-thread contract.
                val current = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING }
                    .toSet()
                if (current != previous) {
                    val diff = diffPlanes(previous, current)
                    if (diff.added.isNotEmpty()) onAdded?.invoke(diff.added)
                    if (diff.updated.isNotEmpty()) onUpdated?.invoke(diff.updated)
                    if (diff.removed.isNotEmpty()) onRemoved?.invoke(diff.removed)
                    previous = current
                    value = current.toList()
                }
            }
        }
    }
}
