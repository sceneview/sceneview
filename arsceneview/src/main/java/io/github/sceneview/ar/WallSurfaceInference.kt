package io.github.sceneview.ar

import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.atan2
import kotlin.math.sqrt

// Where a wall is found without a plane on it (#4070): a vertical plane's polygon, extended,
// and a wall inferred from where the tracked floor ends. Pure geometry, JVM-tested in
// WallPlacementMathTest; the ARCore glue lives in AutoPlacementScene.kt and WallPlacement.kt.

// ── Polygon geometry (plane-local X/Z) ─────────────────────────────────────────────────────

/** The polygon of a plane as a flat `[x0, z0, x1, z1, …]` array in its centre-pose frame. */
internal fun Plane.polygonArray(): FloatArray {
    val buffer = polygon.duplicate()
    buffer.rewind()
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}

/** [pose]'s position in this plane's centre-pose frame, as plane-local `(x, z)`. */
internal fun Plane.localXZ(pose: Pose): FloatArray {
    val local = centerPose.inverse().transformPoint(pose.translation)
    return floatArrayOf(local[0], local[2])
}

/** How far [pose] lies outside this plane's polygon, metres: `0` inside. */
internal fun Plane.outsidePolygonDistance(pose: Pose): Float {
    val local = localXZ(pose)
    return polygonOutsideDistance(local[0], local[1], polygonArray())
}

/** Even-odd point-in-polygon test for a flat `[x0, z0, x1, z1, …]` polygon. */
internal fun isInsidePolygon(x: Float, z: Float, polygon: FloatArray): Boolean {
    val n = polygon.size / 2
    if (n < 3) return false
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = polygon[i * 2]
        val zi = polygon[i * 2 + 1]
        val xj = polygon[j * 2]
        val zj = polygon[j * 2 + 1]
        if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** The polygon edge nearest a point: its distance and its unit direction, plane-local X/Z. */
internal data class PolygonEdge(val distance: Float, val dx: Float, val dz: Float)

/** The edge of a flat `[x0, z0, …]` polygon nearest `(x, z)`, `null` under three vertices. */
internal fun nearestPolygonEdge(x: Float, z: Float, polygon: FloatArray): PolygonEdge? {
    val n = polygon.size / 2
    if (n < 3) return null
    var best: PolygonEdge? = null
    for (i in 0 until n) {
        val ax = polygon[i * 2]
        val az = polygon[i * 2 + 1]
        val bx = polygon[((i + 1) % n) * 2]
        val bz = polygon[((i + 1) % n) * 2 + 1]
        val ex = bx - ax
        val ez = bz - az
        val lengthSquared = ex * ex + ez * ez
        if (lengthSquared < WALL_NORMAL_EPSILON) continue
        val t = (((x - ax) * ex + (z - az) * ez) / lengthSquared).coerceIn(0f, 1f)
        val px = ax + ex * t - x
        val pz = az + ez * t - z
        val distance = sqrt(px * px + pz * pz)
        if (best == null || distance < best.distance) {
            val length = sqrt(lengthSquared)
            best = PolygonEdge(distance, ex / length, ez / length)
        }
    }
    return best
}

/** How far `(x, z)` lies outside a flat polygon, metres: `0` inside, `+∞` for no polygon. */
internal fun polygonOutsideDistance(x: Float, z: Float, polygon: FloatArray): Float = when {
    polygon.size < 6 -> Float.POSITIVE_INFINITY
    isInsidePolygon(x, z, polygon) -> 0f
    else -> nearestPolygonEdge(x, z, polygon)?.distance ?: Float.POSITIVE_INFINITY
}

// ── Wall inferred from the floor seam (#4070) ──────────────────────────────────────────────

/** How close to the tracked floor's edge the aimed floor point must be to read as a wall base. */
internal const val SEAM_EDGE_TOLERANCE_M = 0.3f

/** Aimed floor points closer than this (horizontally) are the user's feet, not a wall base. */
internal const val SEAM_MIN_HORIZONTAL_M = 0.5f

/** Steeper than this below the horizon, the camera is scanning the floor, not aiming at a wall. */
internal const val SEAM_MAX_DOWN_ANGLE_DEG = 55f

/** Two floor planes within this height of each other are the same floor. */
internal const val SEAM_FLOOR_TOLERANCE_M = 0.15f

/** A depth normal steeper than this (|y|) is a floor or a table, useless for the seam. */
private const val SEAM_DEPTH_NORMAL_MAX_Y = 0.5f

/** A floor edge seen more obliquely than 60° is the floor's side, not a wall base in front. */
private const val SEAM_EDGE_MIN_FACING = 0.5f

/**
 * A wall inferred from the floor↔wall seam: [point] on the seam at floor height, [normal] the
 * horizontal wall normal facing the room, [seam] the full [floorWallSeam].
 */
internal data class SeamWall(val point: Position, val normal: Direction, val seam: FloorWallSeam)

/**
 * Infers a wall from where the tracked floor ends, for walls ARCore never grows a plane on — a
 * plain painted wall has no features, but the floor in front of it and the skirting board
 * usually do (#4070). Works with or without the depth API.
 *
 * The camera ray met the floor at [rayHitOnFloor], [floorEdgeDistance] metres from the edge of
 * the floor's polygon. That edge is where the floor stopped being seen, which, with the camera
 * aimed at a wall, is the wall's base. Refused when the point is not near the edge, too close to
 * the camera, or when the camera looks down steeper than [SEAM_MAX_DOWN_ANGLE_DEG] (scanning the
 * floor, not aiming at a wall).
 *
 * The wall normal comes from, in order: [depthNormal] if it is roughly horizontal; the floor
 * polygon's nearest edge ([edgeDirection], world space) if that edge faces the camera; otherwise
 * the horizontal direction back to the camera.
 *
 * [floorEndsBeyond]: a probe a little past the aimed point, away from the camera, falls outside
 * the floor polygon — the edge is the far one, not the one at the user's feet.
 */
internal fun seamWallCandidate(
    floorY: Float,
    rayHitOnFloor: Position,
    cameraPosition: Position,
    floorEdgeDistance: Float,
    edgeDirection: Direction? = null,
    depthNormal: Direction? = null,
    floorEndsBeyond: Boolean = true,
): SeamWall? {
    // The near edge of the floor (toward the user) is not a wall base: the floor must end
    // *beyond* the aimed point.
    val nearEdge = floorEdgeDistance.isFinite() && floorEdgeDistance <= SEAM_EDGE_TOLERANCE_M
    val dx = cameraPosition.x - rayHitOnFloor.x
    val dz = cameraPosition.z - rayHitOnFloor.z
    val horizontal = sqrt(dx * dx + dz * dz)
    val height = cameraPosition.y - floorY
    // Aimed at the floor from a distance, not straight down at the user's feet.
    val lookingAcross = horizontal.isFinite() && horizontal >= SEAM_MIN_HORIZONTAL_M && height > 0f &&
        Math.toDegrees(atan2(height, horizontal).toDouble()) <= SEAM_MAX_DOWN_ANGLE_DEG
    if (!floorEndsBeyond || !nearEdge || !lookingAcross) return null
    val towardCamera = Direction(dx / horizontal, 0f, dz / horizontal)
    val fromDepth = depthNormal
        ?.takeIf { kotlin.math.abs(it.y) < SEAM_DEPTH_NORMAL_MAX_Y }
        ?.let { horizontalFacing(it, towardCamera) }
    // cross(up, edge) with up = +Y: horizontal, perpendicular to the seam line.
    val fromEdge = edgeDirection
        ?.let { horizontalFacing(Direction(it.z, 0f, -it.x), towardCamera) }
        ?.takeIf { dev.romainguy.kotlin.math.dot(it, towardCamera) >= SEAM_EDGE_MIN_FACING }
    val normal = fromDepth ?: fromEdge ?: towardCamera
    val seam = floorWallSeam(normal, rayHitOnFloor, floorY)
    return SeamWall(seam.point, normal, seam)
}

/** [normal] flattened to the horizontal, unit length, turned toward [towardCamera]; null if vertical. */
private fun horizontalFacing(normal: Direction, towardCamera: Direction): Direction? {
    val length = sqrt(normal.x * normal.x + normal.z * normal.z)
    if (!length.isFinite() || length < WALL_NORMAL_EPSILON) return null
    return roomFacingNormal(Direction(normal.x / length, 0f, normal.z / length), towardCamera)
}
