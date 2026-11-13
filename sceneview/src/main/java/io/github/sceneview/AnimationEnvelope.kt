package io.github.sceneview

import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.times
import io.github.sceneview.node.CameraNode

/** Points used to frame a subject. Explicit bounds keep [FitMode.Corners] by default. */
enum class FitMode {
    /** All eight box corners: use for products and other box-shaped geometry. */
    Corners,
    /** Side mid-lines and top/bottom face centres: a tighter heuristic for organic subjects. */
    Silhouette
}

/**
 * A clip's separate pose bounds, including its rest pose, in model-root space unless [transformed].
 * Pass this as `animationEnvelope` to [fitCameraToBounds], [CameraNode.fitToBounds] or
 * [CameraNode.frameToBounds], together with [bounds]. Every pose's probes are fitted; the union
 * box is only used to choose the target depth. Unrelated extrema never become a fictitious pose.
 *
 * This is a joint/flesh approximation, not a vertex silhouette. The fit contains every sampled
 * probe, not necessarily every vertex or an extremum between samples. Increase the sample count
 * for fast clips; use [FitMode.Corners] for geometry that occupies its box corners.
 *
 * @property poses Bounds of each sampled pose, all expressed in the same space.
 */
class AnimationEnvelope private constructor(
    val poses: List<Aabb>,
    internal val corners: List<Position>,
    internal val silhouette: List<Position>
) {
    /** Creates an envelope from separate pose boxes, without merging their probes. */
    constructor(poses: List<Aabb>) : this(
        poses.toList(), poses.flatMap { it.framingProbes(FitMode.Corners) },
        poses.flatMap { it.framingProbes(FitMode.Silhouette) }
    )

    /** Union for centring and size queries; fitting uses the separate poses instead. */
    val bounds: Aabb = poses.union()

    /**
     * Moves the envelope into the camera's space. Transforms the probes themselves, preserving
     * their shape under rotation and non-uniform scale rather than re-boxing each silhouette.
     */
    fun transformed(transform: Transform): AnimationEnvelope = AnimationEnvelope(
        corners.chunked(8).map { boundsOfPoints(it.map { p -> transform * p }) },
        corners.map { transform * it }, silhouette.map { transform * it }
    )
}

internal fun Aabb.framingProbes(mode: FitMode): List<Position> {
    if (isEmpty) return emptyList()
    val lo = min
    val hi = max
    return when (mode) {
        FitMode.Corners -> List(8) { i ->
            Position(if (i and 1 == 0) lo.x else hi.x,
                if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z)
        }
        // The horizontal mid-line of each side face (its two endpoints), plus face centres.
        FitMode.Silhouette -> listOf(
            Position(lo.x, center.y, lo.z), Position(hi.x, center.y, lo.z),
            Position(lo.x, center.y, hi.z), Position(hi.x, center.y, hi.z),
            Position(center.x, lo.y, center.z), Position(center.x, hi.y, center.z)
        )
    }
}

internal fun boundsOfPoints(points: List<Position>): Aabb {
    if (points.isEmpty()) return Aabb()
    return Aabb.fromMinMax(
        Position(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
        Position(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
    )
}

/** Per-axis flesh outside the rest joints, carried unchanged to each sampled joint extent. */
internal fun fleshBounds(rest: Aabb, restJoints: Aabb, posedJoints: Aabb): Aabb {
    val lower = Position(maxOf(0f, restJoints.min.x - rest.min.x),
        maxOf(0f, restJoints.min.y - rest.min.y), maxOf(0f, restJoints.min.z - rest.min.z))
    val upper = Position(maxOf(0f, rest.max.x - restJoints.max.x),
        maxOf(0f, rest.max.y - restJoints.max.y), maxOf(0f, rest.max.z - restJoints.max.z))
    return Aabb.fromMinMax(posedJoints.min - lower, posedJoints.max + upper)
}
