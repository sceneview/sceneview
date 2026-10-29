package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The real-size pools offered by [io.github.sceneview.demo.demos.ARPoolDemo]: the water
 * surface in metres, length × width. The coping adds [PoolGeometry.COPING_WIDTH] around it.
 */
internal enum class PoolPreset(val length: Float, val width: Float) {
    COMPACT(6f, 3f),
    FAMILY(8f, 4f),
    LARGE(10f, 5f),
    ;

    val spec: PoolSpec get() = PoolSpec(length = length, width = width)
}

/**
 * One in-ground pool, in the pool's own frame — the frame the placement anchor carries:
 *
 * - the lawn is the plane `y = 0` and the pool is dug below it;
 * - the origin is the middle of the coping's outer edge on the viewer's side, so the
 *   placement reticle sits on the lawn in front of the pool rather than in the water;
 * - the pool runs along **-Z**, away from the viewer. The near wall ([zNear]) is the deep end;
 *   the far wall ([zFar]) is the shallow end, with the Roman steps in its left corner — the
 *   wall the viewer faces, so the steps are in view as soon as the pool lands.
 *
 * @param length       Water length along Z, metres.
 * @param width        Water width along X, metres.
 * @param shallowDepth Floor depth below the lawn at the far wall, metres.
 * @param deepDepth    Floor depth below the lawn at the near wall, metres.
 */
internal data class PoolSpec(
    val length: Float,
    val width: Float,
    val shallowDepth: Float = DEFAULT_SHALLOW_DEPTH,
    val deepDepth: Float = DEFAULT_DEEP_DEPTH,
) {
    init {
        require(length > 0f && width > 0f) { "A pool needs a positive size, got $length × $width m" }
        require(shallowDepth > PoolGeometry.STEP_TOP + PoolGeometry.WATER_DROP) {
            "The shallow end ($shallowDepth m) must leave room for the steps"
        }
        require(deepDepth >= shallowDepth) { "The deep end ($deepDepth m) is shallower than $shallowDepth m" }
    }

    val halfWidth: Float get() = width / 2f

    /** z of the near wall — the deep end, just behind the coping the viewer stands at. */
    val zNear: Float get() = -PoolGeometry.COPING_WIDTH

    /** z of the far wall — the shallow end, with the steps. */
    val zFar: Float get() = zNear - length

    /** z of the middle of the water, the pivot the coping's stone joints are laid from. */
    val zCenter: Float get() = zNear - length / 2f

    /**
     * Depth of the floor below the lawn at [z]: [deepDepth] along the near wall, [shallowDepth]
     * along the far one, with a smooth slope across the middle half of the pool — flat where
     * people stand at either end.
     */
    fun floorDepthAt(z: Float): Float =
        deepDepth + (shallowDepth - deepDepth) * smoothstep(RAMP_START, RAMP_END, along(z))

    /** Upward normal of the floor at [z]: tilted towards the deep end across the slope. */
    fun floorNormalAt(z: Float): Direction {
        val x = ((along(z) - RAMP_START) / (RAMP_END - RAMP_START)).coerceIn(0f, 1f)
        val dDepthDt = (shallowDepth - deepDepth) * 6f * x * (1f - x) / (RAMP_END - RAMP_START)
        // floor y = -depth(t), t = (zNear - z) / length, so dy/dz = depth'(t) / length.
        val dyDz = dDepthDt / length
        return normalize(Direction(x = 0f, y = 1f, z = -dyDz))
    }

    /** 0 at the near wall, 1 at the far wall. */
    private fun along(z: Float) = ((zNear - z) / length).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_SHALLOW_DEPTH = 1.2f
        const val DEFAULT_DEEP_DEPTH = 1.5f

        /** The floor slopes between these fractions of the length; flat outside. */
        const val RAMP_START = 0.25f
        const val RAMP_END = 0.75f
    }
}

/** A generated mesh, ready for `Geometry.Builder(TRIANGLES).vertices(…).indices(…)`. */
internal class PoolMesh(val vertices: List<Geometry.Vertex>, val indices: List<Int>)

/**
 * The procedural meshes of the in-ground pool demo. Four meshes, one material each, plus the
 * [screen] triangle the night veil is drawn with:
 *
 * - [liner] — walls, sloped floor and Roman steps, normals facing **into** the pool. Its vertex
 *   colour carries the object-space normal (`rgb = n × 0.5 + 0.5`, rotation-proof for the
 *   caustic projection) and, in alpha, `1` on the contrasting nose band of each step.
 * - [water] — the surface, [WATER_DROP] below the lawn.
 * - [coping] — the stone border, with a rounded bull-nose overhanging the water and mitred
 *   corners. It is the only part above the lawn.
 * - [hull] — an open-topped box [HULL_GAP] outside the liner, drawn with the SDK's occlusion
 *   material: it writes depth but no colour, so wherever the camera looks *through the lawn*
 *   the hull is nearer than the liner and the real lawn stays on screen. Only the opening
 *   inside the coping shows the dug pool.
 *
 * Pure arithmetic and pure Kotlin: safe to call from any thread. It is the *upload* —
 * `Geometry.Builder.build(engine)` — that must stay on the main thread.
 */
internal object PoolGeometry {

    /** Width of the stone coping around the water, metres. */
    const val COPING_WIDTH = 0.30f

    /** Height of the coping above the lawn, metres. */
    const val COPING_HEIGHT = 0.05f

    /** How far the bull-nose overhangs the water, metres. */
    const val COPING_OVERHANG = 0.025f

    /** Water level below the lawn, metres. */
    const val WATER_DROP = 0.15f

    /** Gap between the liner and the occlusion hull, metres — hidden under the coping. */
    const val HULL_GAP = 0.05f

    /** Depth of the top step below the lawn, metres. */
    const val STEP_TOP = 0.40f

    /** Width of the contrasting band along each step's nose, metres. */
    const val STEP_NOSE = 0.05f

    /** Number of treads of the Roman steps, the floor being the next one down. */
    const val STEP_COUNT = 3

    /** Outer radius of each tread as a fraction of [stairRadius]. */
    private val STEP_RADII = floatArrayOf(0.41f, 0.70f, 1.0f)

    /** Floor and side-wall rows along the length. */
    const val FLOOR_ROWS = 24

    /** Floor columns across the width — matched by the end walls so no seam has a T-junction. */
    const val FLOOR_COLUMNS = 2

    /** Segments of every quarter-circle of the steps. */
    const val ARC_SEGMENTS = 24

    private const val COPING_UNDERSIDE = -0.03f
    private const val COPING_BURIED = -0.01f
    private const val NOSE_RADIUS = 0.02f
    private const val NOSE_SEGMENTS = 4
    private const val STAIR_RADIUS_MAX = 1.6f
    private const val STAIR_RADIUS_OF_WIDTH = 0.45f
    private const val SCREEN_DEPTH = 0.5f

    private val UP = Direction(y = 1f)
    private val DOWN = Direction(y = -1f)
    private val PLUS_X = Direction(x = 1f)
    private val MINUS_X = Direction(x = -1f)
    private val PLUS_Z = Direction(z = 1f)
    private val MINUS_Z = Direction(z = -1f)

    /**
     * Radius of the lowest tread of the Roman steps, measured from the far-left corner: as big
     * as a real one, never wider than ~half the pool, and always inside the flat shallow end.
     */
    fun stairRadius(spec: PoolSpec): Float = min(
        min(STAIR_RADIUS_MAX, STAIR_RADIUS_OF_WIDTH * spec.width),
        (1f - PoolSpec.RAMP_END) * spec.length,
    )

    /** Walls, floor and steps. See [PoolGeometry] for the vertex-colour layout. */
    fun liner(spec: PoolSpec): PoolMesh {
        val mesh = MeshBuilder()
        val rows = FloatArray(FLOOR_ROWS + 1) { j -> spec.zNear + (spec.zFar - spec.zNear) * j / FLOOR_ROWS }
        floor(mesh, spec, rows)
        for (side in floatArrayOf(-1f, 1f)) sideWall(mesh, spec, rows, side)
        endWall(mesh, spec, z = spec.zNear, depth = spec.deepDepth, inward = MINUS_Z)
        endWall(mesh, spec, z = spec.zFar, depth = spec.shallowDepth, inward = PLUS_Z)
        steps(mesh, spec)
        return mesh.build()
    }

    /** The water surface, one quad facing up. */
    fun water(spec: PoolSpec): PoolMesh {
        val mesh = MeshBuilder()
        val y = -WATER_DROP
        val a = spec.halfWidth
        val corners = listOf(
            Position(-a, y, spec.zNear),
            Position(a, y, spec.zNear),
            Position(a, y, spec.zFar),
            Position(-a, y, spec.zFar),
        ).map { mesh.vertex(it, normal = UP) }
        mesh.quad(corners[0], corners[1], corners[2], corners[3], facing = UP)
        return mesh.build()
    }

    /**
     * The open-topped box around the liner that hides the pool from every ray that enters
     * the ground outside the coping. Positions only: the occlusion material reads nothing else.
     */
    fun hull(spec: PoolSpec): PoolMesh {
        val mesh = MeshBuilder()
        val x0 = -spec.halfWidth - HULL_GAP
        val x1 = spec.halfWidth + HULL_GAP
        val z0 = spec.zFar - HULL_GAP
        val z1 = spec.zNear + HULL_GAP
        val y0 = -(spec.deepDepth + HULL_GAP)
        fun face(a: Position, b: Position, c: Position, d: Position, outward: Direction) {
            mesh.quad(mesh.vertex(a), mesh.vertex(b), mesh.vertex(c), mesh.vertex(d), facing = outward)
        }
        // Corners: left/right, near/far, top (the lawn)/bottom.
        val lnt = Position(x0, 0f, z1)
        val rnt = Position(x1, 0f, z1)
        val lft = Position(x0, 0f, z0)
        val rft = Position(x1, 0f, z0)
        val lnb = Position(x0, y0, z1)
        val rnb = Position(x1, y0, z1)
        val lfb = Position(x0, y0, z0)
        val rfb = Position(x1, y0, z0)
        face(lnt, rnt, rnb, lnb, PLUS_Z)
        face(rft, lft, lfb, rfb, MINUS_Z)
        face(lft, lnt, lnb, lfb, MINUS_X)
        face(rnt, rft, rfb, rnb, PLUS_X)
        face(lnb, rnb, rfb, lfb, DOWN)
        return mesh.build()
    }

    /**
     * One triangle over the whole screen, in device coordinates — x and y in [-1, 1] across the
     * screen — for the night veil, drawn with a `vertexDomain: device` material. Its corners
     * overshoot to (-1, -3) and (3, 1), the way the AR camera image is drawn, so the screen is
     * covered without a diagonal seam. Mid-depth, where no clip plane can catch it.
     */
    fun screen(): PoolMesh {
        val mesh = MeshBuilder()
        val corners = listOf(
            Position(-1f, 1f, SCREEN_DEPTH),
            Position(-1f, -3f, SCREEN_DEPTH),
            Position(3f, 1f, SCREEN_DEPTH),
        ).map { mesh.vertex(it) }
        mesh.triangle(corners[0], corners[1], corners[2], facing = PLUS_Z)
        return mesh.build()
    }

    /**
     * The stone coping: a bull-nose profile swept along the four sides, mitred at the corners.
     * Each profile segment gets its own vertices so the flat faces shade flat.
     */
    fun coping(spec: PoolSpec): PoolMesh {
        val mesh = MeshBuilder()
        val a = spec.halfWidth
        val zN = spec.zNear
        val zF = spec.zFar
        val profile = copingProfile()
        val nearLeft = Position(-a, 0f, zN)
        val nearRight = Position(a, 0f, zN)
        val farRight = Position(a, 0f, zF)
        val farLeft = Position(-a, 0f, zF)
        val sides = listOf(
            CopingSide(nearLeft, nearRight, outward = PLUS_Z, outwardBeforeA = MINUS_X, outwardAfterB = PLUS_X),
            CopingSide(nearRight, farRight, outward = PLUS_X, outwardBeforeA = PLUS_Z, outwardAfterB = MINUS_Z),
            CopingSide(farRight, farLeft, outward = MINUS_Z, outwardBeforeA = PLUS_X, outwardAfterB = MINUS_X),
            CopingSide(farLeft, nearLeft, outward = MINUS_X, outwardBeforeA = MINUS_Z, outwardAfterB = PLUS_Z),
        )
        for (side in sides) {
            for (segment in profile) {
                val normal = normalize(side.outward * segment.normalOut + UP * segment.normalUp)
                val a0 = mesh.vertex(side.atA(segment.start), normal)
                val b0 = mesh.vertex(side.atB(segment.start), normal)
                val b1 = mesh.vertex(side.atB(segment.end), normal)
                val a1 = mesh.vertex(side.atA(segment.end), normal)
                mesh.quad(a0, b0, b1, a1, facing = normal)
            }
        }
        return mesh.build()
    }

    private fun floor(mesh: MeshBuilder, spec: PoolSpec, rows: FloatArray) {
        val grid = Array(rows.size) { j ->
            val z = rows[j]
            val y = -spec.floorDepthAt(z)
            val normal = spec.floorNormalAt(z)
            IntArray(FLOOR_COLUMNS + 1) { i ->
                val x = -spec.halfWidth + spec.width * i / FLOOR_COLUMNS
                mesh.linerVertex(Position(x, y, z), normal)
            }
        }
        for (j in 0 until FLOOR_ROWS) {
            for (i in 0 until FLOOR_COLUMNS) {
                mesh.quad(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i], facing = UP)
            }
        }
    }

    private fun sideWall(mesh: MeshBuilder, spec: PoolSpec, rows: FloatArray, side: Float) {
        val x = side * spec.halfWidth
        val inward = Direction(-side, 0f, 0f)
        val top = IntArray(rows.size) { j -> mesh.linerVertex(Position(x, 0f, rows[j]), inward) }
        val bottom = IntArray(rows.size) { j ->
            mesh.linerVertex(Position(x, -spec.floorDepthAt(rows[j]), rows[j]), inward)
        }
        for (j in 0 until FLOOR_ROWS) mesh.quad(top[j], top[j + 1], bottom[j + 1], bottom[j], facing = inward)
    }

    private fun endWall(mesh: MeshBuilder, spec: PoolSpec, z: Float, depth: Float, inward: Direction) {
        val xs = FloatArray(FLOOR_COLUMNS + 1) { i -> -spec.halfWidth + spec.width * i / FLOOR_COLUMNS }
        val top = IntArray(xs.size) { i -> mesh.linerVertex(Position(xs[i], 0f, z), inward) }
        val bottom = IntArray(xs.size) { i -> mesh.linerVertex(Position(xs[i], -depth, z), inward) }
        for (i in 0 until FLOOR_COLUMNS) mesh.quad(top[i], top[i + 1], bottom[i + 1], bottom[i], facing = inward)
    }

    /**
     * Roman steps: concentric quarter-circle treads around the far-left corner, each with a
     * contrasting nose band, dropping by equal risers from [STEP_TOP] to the floor.
     */
    private fun steps(mesh: MeshBuilder, spec: PoolSpec) {
        val corner = Position(-spec.halfWidth, 0f, spec.zFar)
        val radius = stairRadius(spec)
        val rise = (spec.shallowDepth - STEP_TOP) / STEP_COUNT
        for (i in 0 until STEP_COUNT) {
            val y = -(STEP_TOP + i * rise)
            val inner = if (i == 0) 0f else STEP_RADII[i - 1] * radius
            val outer = STEP_RADII[i] * radius
            tread(mesh, corner, y, inner, outer - STEP_NOSE, nose = false)
            tread(mesh, corner, y, outer - STEP_NOSE, outer, nose = true)
            riser(mesh, corner, outer, top = y, bottom = y - rise)
        }
    }

    private fun tread(mesh: MeshBuilder, corner: Position, y: Float, inner: Float, outer: Float, nose: Boolean) {
        val outerRing = IntArray(ARC_SEGMENTS + 1) { k ->
            mesh.linerVertex(stepPoint(corner, outer, y, k), UP, nose)
        }
        if (inner == 0f) {
            val center = mesh.linerVertex(Position(corner.x, y, corner.z), UP, nose)
            for (k in 0 until ARC_SEGMENTS) mesh.triangle(center, outerRing[k], outerRing[k + 1], facing = UP)
            return
        }
        val innerRing = IntArray(ARC_SEGMENTS + 1) { k ->
            mesh.linerVertex(stepPoint(corner, inner, y, k), UP, nose)
        }
        for (k in 0 until ARC_SEGMENTS) {
            mesh.quad(innerRing[k], innerRing[k + 1], outerRing[k + 1], outerRing[k], facing = UP)
        }
    }

    private fun riser(mesh: MeshBuilder, corner: Position, radius: Float, top: Float, bottom: Float) {
        val tops = IntArray(ARC_SEGMENTS + 1) { k ->
            mesh.linerVertex(stepPoint(corner, radius, top, k), stepDirection(k))
        }
        val bottoms = IntArray(ARC_SEGMENTS + 1) { k ->
            mesh.linerVertex(stepPoint(corner, radius, bottom, k), stepDirection(k))
        }
        for (k in 0 until ARC_SEGMENTS) {
            val facing = stepDirection(k) + stepDirection(k + 1)
            mesh.quad(tops[k], tops[k + 1], bottoms[k + 1], bottoms[k], facing = facing)
        }
    }

    /** Direction `k` of the quarter circle, from along the far wall (+X) to along the left wall (+Z). */
    private fun stepDirection(k: Int): Direction {
        val theta = (PI / 2.0 * k / ARC_SEGMENTS).toFloat()
        return Direction(cos(theta), 0f, sin(theta))
    }

    private fun stepPoint(corner: Position, radius: Float, y: Float, k: Int): Position {
        val direction = stepDirection(k)
        return Position(corner.x + radius * direction.x, y, corner.z + radius * direction.z)
    }

    /**
     * The coping's cross-section, as (offset outward from the liner plane, height) pairs:
     * underside of the overhang, nose face, rounded nose, top, and the outer face that sinks
     * just below the lawn so no gap shows between stone and grass.
     */
    private fun copingProfile(): List<ProfileSegment> {
        val under = COPING_UNDERSIDE
        val lip = -COPING_OVERHANG
        val top = COPING_HEIGHT
        val outer = COPING_WIDTH
        val noseOut = lip + NOSE_RADIUS
        val noseUp = top - NOSE_RADIUS
        val segments = mutableListOf(
            ProfileSegment(0f, under, lip, under, normalOut = 0f, normalUp = -1f),
            ProfileSegment(lip, under, lip, noseUp, normalOut = -1f, normalUp = 0f),
        )
        for (s in 0 until NOSE_SEGMENTS) {
            val phi0 = PI - PI / 2.0 * s / NOSE_SEGMENTS
            val phi1 = PI - PI / 2.0 * (s + 1) / NOSE_SEGMENTS
            val mid = (phi0 + phi1) / 2.0
            segments += ProfileSegment(
                outStart = noseOut + NOSE_RADIUS * cos(phi0).toFloat(),
                upStart = noseUp + NOSE_RADIUS * sin(phi0).toFloat(),
                outEnd = noseOut + NOSE_RADIUS * cos(phi1).toFloat(),
                upEnd = noseUp + NOSE_RADIUS * sin(phi1).toFloat(),
                normalOut = cos(mid).toFloat(),
                normalUp = sin(mid).toFloat(),
            )
        }
        segments += ProfileSegment(noseOut, top, outer, top, normalOut = 0f, normalUp = 1f)
        segments += ProfileSegment(outer, top, outer, COPING_BURIED, normalOut = 1f, normalUp = 0f)
        return segments
    }

    private class ProfileSegment(
        outStart: Float,
        upStart: Float,
        outEnd: Float,
        upEnd: Float,
        val normalOut: Float,
        val normalUp: Float,
    ) {
        val start = ProfilePoint(outStart, upStart)
        val end = ProfilePoint(outEnd, upEnd)
    }

    private class ProfilePoint(val out: Float, val up: Float)

    /** One straight run of coping; offsets at its ends follow the 45° mitre. */
    private class CopingSide(
        val a: Position,
        val b: Position,
        val outward: Direction,
        val outwardBeforeA: Direction,
        val outwardAfterB: Direction,
    ) {
        fun atA(p: ProfilePoint) = a + (outward + outwardBeforeA) * p.out + UP * p.up
        fun atB(p: ProfilePoint) = b + (outward + outwardAfterB) * p.out + UP * p.up
    }

    /** Liner vertex: the object normal rides in the colour so the shader sees it unrotated. */
    private fun MeshBuilder.linerVertex(position: Position, normal: Direction, nose: Boolean = false): Int =
        vertex(
            position,
            normal = normal,
            color = Color(
                normal.x * 0.5f + 0.5f,
                normal.y * 0.5f + 0.5f,
                normal.z * 0.5f + 0.5f,
                if (nose) 1f else 0f,
            ),
        )

    private class MeshBuilder {
        private val vertices = ArrayList<Geometry.Vertex>()
        private val indices = ArrayList<Int>()

        fun vertex(position: Position, normal: Direction? = null, color: Color? = null): Int {
            vertices += Geometry.Vertex(position = position, normal = normal, color = color)
            return vertices.lastIndex
        }

        /** Adds the triangle wound counter-clockwise as seen from [facing] — Filament's front face. */
        fun triangle(a: Int, b: Int, c: Int, facing: Direction) {
            val pa = vertices[a].position
            val winding = cross(vertices[b].position - pa, vertices[c].position - pa)
            val counterClockwise = dot(winding, facing) >= 0f
            indices += a
            indices += if (counterClockwise) b else c
            indices += if (counterClockwise) c else b
        }

        fun quad(a: Int, b: Int, c: Int, d: Int, facing: Direction) {
            triangle(a, b, c, facing)
            triangle(a, c, d, facing)
        }

        fun build() = PoolMesh(vertices.toList(), indices.toList())
    }
}

/** GLSL's `smoothstep`, for the floor slope. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
