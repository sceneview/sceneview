package io.github.sceneview.demo.demos.internal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which planes [PlaneFlattening] merges, and what it leaves standing off a wall.
 *
 * The first four build the mesh by hand, flat sheets of 2.5 cm quads with no noise, so a test
 * sees the merge alone: a door set back into a wall that was meshed twice, the same door in pieces
 * beside a wall whose second pass pulls its fitted plane towards it, a door beside a wall meshed
 * in two offset pieces, a panel tilted by a degree far from the origin. The others fuse rooms
 * through the real TSDF: picture frames 3 cm off a wall — 40 × 30 cm, 30 × 20 cm and A4, across
 * the footprint grid — keep their relief; a wall bulging by as much is flattened all the same.
 */
class PlaneFlatteningTest {
    @Test
    fun `a door set back into a wall meshed twice keeps a plane of its own`() {
        val sheets = Sheets()
        val wall = sheets.add(0f, 0f, 5f, 2.6f, hole = Rect(3f, 0f, 3.9f, 2.05f)) { _, _ -> 0f }
        val secondPass = sheets.add(0.5f, 0.4f, 2.9f, 2.3f) { _, _ -> -0.03f }
        val door = sheets.add(3f, 0f, 3.9f, 2.05f) { _, _ -> DOOR_Z }
        val mesh = sheets.mesh()
        val result = PlaneFlattening.flatten(mesh)
        val wallPlane = onePlane(result, sheets.range(wall), "wall")
        assertEquals(
            "the second pass, 3 cm behind, is the same wall: ${describe(result)}",
            setOf(wallPlane),
            planesOf(result, sheets.range(secondPass)),
        )
        val doorPlane = onePlane(result, sheets.range(door), "door")
        assertNotEquals("the door, 3.5 cm back, has a plane of its own: ${describe(result)}", wallPlane, doorPlane)
        val drift = worstDrift(mesh, sheets.range(door)) { _, _ -> DOOR_Z }
        assertTrue("the door moved ${cm(drift)} cm off its place", drift < FLAT_M)
    }

    @Test
    fun `a door in pieces set back into a wall meshed twice stays off it`() {
        // The second passes, 4 cm behind, pull the wall's fitted plane 2 cm towards the door: the
        // door's pieces, each under a region's size, stand 3.5 cm off the wall itself.
        val sheets = Sheets()
        val wall = sheets.add(0f, 0f, 5f, 2.6f, hole = Rect(3f, 0f, 3.9f, DOOR_TOP)) { _, _ -> 0f }
        val passes = listOf(
            sheets.add(0.2f, 0f, 2.9f, 2.6f) { _, _ -> SECOND_PASS_Z },
            sheets.add(4f, 0f, 4.9f, 2.6f) { _, _ -> SECOND_PASS_Z },
        )
        val pieces = (0 until DOOR_PIECES).flatMap { i ->
            (0 until DOOR_PIECES).map { j ->
                val x0 = 3f + i * DOOR_PIECE_W
                val y0 = j * DOOR_PIECE_H
                sheets.add(x0, y0, x0 + DOOR_PIECE_W, y0 + DOOR_PIECE_H) { _, _ -> DOOR_Z }
            }
        }
        val mesh = sheets.mesh()
        val result = PlaneFlattening.flatten(mesh)
        val wallPlane = onePlane(result, sheets.range(wall), "wall")
        for (pass in passes) {
            assertEquals(
                "the second pass, 4 cm behind, is the same wall: ${describe(result)}",
                setOf(wallPlane),
                planesOf(result, sheets.range(pass)),
            )
        }
        val pressed = pieces.filter { wallPlane in planesOf(result, sheets.range(it)) }
        assertTrue(
            "${pressed.size} of the door's ${pieces.size} pieces, 3.5 cm back, pressed onto the wall: ${describe(result)}",
            pressed.isEmpty(),
        )
        val drift = pieces.maxOf { piece -> worstDrift(mesh, sheets.range(piece)) { _, _ -> DOOR_Z } }
        assertTrue("the door moved ${cm(drift)} cm off its place", drift < FLAT_M)
    }

    @Test
    fun `a door beside a wall meshed in two offset pieces keeps a plane of its own`() {
        val sheets = Sheets()
        val left = sheets.add(0f, 0f, 2.8f, 2.6f) { _, _ -> 0f }
        val right = sheets.add(1f, 0f, 4f, 2.6f, hole = Rect(2.8f, 0f, 3.7f, 2.05f)) { _, _ -> -0.025f }
        val door = sheets.add(2.8f, 0f, 3.7f, 2.05f) { _, _ -> DOOR_Z }
        val mesh = sheets.mesh()
        val result = PlaneFlattening.flatten(mesh)
        val wallPlane = onePlane(result, sheets.range(left), "left piece")
        assertEquals(
            "the right piece, 2.5 cm behind and overlapping, is the same wall: ${describe(result)}",
            setOf(wallPlane),
            planesOf(result, sheets.range(right)),
        )
        val doorPlane = onePlane(result, sheets.range(door), "door")
        assertNotEquals("the door, 3.5 cm back, has a plane of its own: ${describe(result)}", wallPlane, doorPlane)
        val drift = worstDrift(mesh, sheets.range(door)) { _, _ -> DOOR_Z }
        assertTrue("the door moved ${cm(drift)} cm off its place", drift < FLAT_M)
    }

    @Test
    fun `a panel tilted by a degree far from the origin keeps a plane of its own`() {
        // The panel's plane passes through the origin like the wall's, 2 m away: equal offsets,
        // while the panel stands 2.5 to 4.5 cm off the wall where it is.
        val sheets = Sheets()
        val wall = sheets.add(-3f, 0f, 0f, 2.6f, hole = Rect(-2.6f, 1f, -1.4f, 1.7f)) { _, _ -> 0f }
        val panel = sheets.add(-2.6f, 1f, -1.4f, 1.7f) { x, _ -> -TILT * x }
        val mesh = sheets.mesh()
        val result = PlaneFlattening.flatten(mesh)
        val wallPlane = onePlane(result, sheets.range(wall), "wall")
        val panelPlane = onePlane(result, sheets.range(panel), "panel")
        assertNotEquals("the panel has a plane of its own: ${describe(result)}", wallPlane, panelPlane)
        val drift = worstDrift(mesh, sheets.range(panel)) { x, _ -> -TILT * x }
        assertTrue("the panel moved ${cm(drift)} cm off its place", drift < FLAT_M)
    }

    @Test
    fun `a frame 3 cm off a wall, across the footprint grid, keeps its relief`() {
        val before = frameRelief(scene, FRAME, RING_M)
        assertTrue("the raw frame stands ${cm(before)} cm off the wall", before >= MIN_RELIEF_M)
        val mesh = prepared(scene)
        val coverage = frameCoverage(mesh)
        PlaneFlattening.flatten(mesh)
        val after = frameRelief(mesh, FRAME, RING_M)
        assertTrue(
            "frame relief ${cm(before)} -> ${cm(after)} cm; $coverage",
            after >= MIN_RELIEF_KEPT * before,
        )
    }

    @Test
    fun `a 30 x 20 cm frame 3 cm off a wall keeps its relief wherever it falls on the footprint grid`() =
        smallFramesKeepTheirRelief(POSTCARD_FRAMES)

    @Test
    fun `an A4 frame 3 cm off a wall keeps its relief wherever it falls on the footprint grid`() =
        smallFramesKeepTheirRelief(A4_FRAMES)

    /** [frames], on [smallFrames]' wall at each of [OFFSETS]: each keeps its relief once flattened. */
    private fun smallFramesKeepTheirRelief(frames: List<Box>) {
        val mesh = prepared(smallFrames)
        val before = frames.map { frameRelief(smallFrames, it, SMALL_RING_M) }
        PlaneFlattening.flatten(mesh)
        val after = frames.map { frameRelief(mesh, it, SMALL_RING_M) }
        val figures = OFFSETS.indices.joinToString { i ->
            "offset ${cm(OFFSETS[i])} cm: ${cm(before[i])} -> ${cm(after[i])} cm"
        }
        val flat = OFFSETS.indices.filter { before[it] < MIN_RELIEF_M }
        assertTrue("raw frames under ${cm(MIN_RELIEF_M)} cm at ${flat.map { cm(OFFSETS[it]) }}: $figures", flat.isEmpty())
        val flattened = OFFSETS.indices.filter { after[it] < MIN_RELIEF_KEPT * before[it] }
        assertTrue("flattened at offsets ${flattened.map { cm(OFFSETS[it]) }} cm: $figures", flattened.isEmpty())
    }

    @Test
    fun `a wall bulging 3 cm is flattened all the same`() {
        val before = bulgeRelief(scene)
        assertTrue("the raw wall bulges ${cm(before)} cm", before >= MIN_BULGE_M)
        val mesh = prepared(scene)
        val result = PlaneFlattening.flatten(mesh)
        val after = bulgeRelief(mesh)
        assertTrue(
            "bulge ${cm(before)} -> ${cm(after)} cm, ${percent(freeShare(mesh, result))} of its core left free",
            abs(after) <= MAX_BULGE_LEFT * before,
        )
    }

    /** Flat sheets of [STEP] quads, each a range of triangles in the one mesh. */
    private class Sheets {
        private val positions = ArrayList<Float>()
        private val indices = ArrayList<Int>()
        private val ranges = ArrayList<IntRange>()

        /** A sheet over [x0]..[x1] × [y0]..[y1] at z = [depth] (x, y), less the quads centred in [hole]; its number. */
        fun add(x0: Float, y0: Float, x1: Float, y1: Float, hole: Rect? = null, depth: (Float, Float) -> Float): Int {
            val columns = ((x1 - x0) / STEP).roundToInt()
            val rows = ((y1 - y0) / STEP).roundToInt()
            val first = positions.size / 3
            for (j in 0..rows) {
                for (i in 0..columns) {
                    val x = x0 + i * STEP
                    val y = y0 + j * STEP
                    positions += x
                    positions += y
                    positions += depth(x, y)
                }
            }
            val from = indices.size / 3
            for (j in 0 until rows) {
                for (i in 0 until columns) {
                    if (hole != null && hole.contains(x0 + (i + 0.5f) * STEP, y0 + (j + 0.5f) * STEP)) continue
                    val a = first + j * (columns + 1) + i
                    val c = a + columns + 1
                    indices += listOf(a, a + 1, c + 1, a, c + 1, c)
                }
            }
            ranges += from until indices.size / 3
            return ranges.size - 1
        }

        fun range(sheet: Int): IntRange = ranges[sheet]

        /** The sheets as one mesh, without the vertices of the holes: the triangles keep their order. */
        fun mesh(): WorkMesh {
            val vertices = positions.size / 3
            val all = WorkMesh(positions.toFloatArray(), IntArray(vertices) { CleanScenes.GREY }, indices.toIntArray())
            return all.keepTriangles(BooleanArray(all.triangleCount) { true })
        }
    }

    private data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun contains(x: Float, y: Float): Boolean = x in x0..x1 && y in y0..y1
    }

    private fun planesOf(result: Flattening, range: IntRange): Set<Int> = range.mapTo(HashSet()) { result.facePlane[it] }

    /** The one plane [range]'s triangles were given. */
    private fun onePlane(result: Flattening, range: IntRange, name: String): Int {
        val planes = planesOf(result, range)
        assertEquals("the $name lies on one plane: $planes; ${describe(result)}", 1, planes.size)
        assertTrue("the $name is flattened: ${describe(result)}", planes.single() >= 0)
        return planes.single()
    }

    /** How far the vertices of [range]'s triangles lie, at worst, from z = [depth] (x, y). */
    private fun worstDrift(mesh: WorkMesh, range: IntRange, depth: (Float, Float) -> Float): Float {
        var worst = 0f
        for (t in range) {
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] * 3
                val p = mesh.positions
                worst = max(worst, abs(p[v + 2] - depth(p[v], p[v + 1])))
            }
        }
        return worst
    }

    private fun describe(result: Flattening): String = "planes " + result.planes.joinToString { plane ->
        String.format(Locale.ROOT, "(%.4f %.4f %.4f) %.4f m", plane.nx, plane.ny, plane.nz, plane.offset)
    }

    /** The raw scene cleaned the way [CleanGeometry.clean] does it up to the flatten, on a copy. */
    private fun prepared(raw: WorkMesh): WorkMesh {
        val copy = WorkMesh(raw.positions.copyOf(), raw.colors.copyOf(), raw.indices.copyOf())
        val pieces = MeshCleanup.removeSmallPieces(copy).first
        val mesh = MeshCleanup.removeDoubledSurfaces(pieces).first
        MeshCleanup.smooth(mesh)
        return mesh
    }

    /**
     * The area-weighted mean of coordinate [axis] over the faces that face along it, within
     * [NEAR_M] of 0 on it, where [where] holds: how far a wall's surface stands there.
     */
    private fun meanDepth(mesh: WorkMesh, axis: Int, where: (Float, Float, Float) -> Boolean): Float {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        var sum = 0.0
        var area = 0.0
        for (t in 0 until faces.count) {
            val depth = faces.centroids[t * 3 + axis]
            if (faces.normals[t * 3 + axis] < FACING || abs(depth) > NEAR_M) continue
            if (!where(faces.centroids[t * 3], faces.centroids[t * 3 + 1], faces.centroids[t * 3 + 2])) continue
            sum += depth.toDouble() * faces.areas[t]
            area += faces.areas[t]
        }
        return if (area == 0.0) Float.NaN else (sum / area).toFloat()
    }

    /** How far [frame]'s face, [INSET_M] in from its edges, stands off the wall in a [ringM] ring around it. */
    private fun frameRelief(mesh: WorkMesh, frame: Box, ringM: Float): Float {
        val face = meanDepth(mesh, Z) { x, y, _ ->
            x in frame.x0 + INSET_M..frame.x1 - INSET_M && y in frame.y0 + INSET_M..frame.y1 - INSET_M
        }
        val around = meanDepth(mesh, Z) { x, y, _ ->
            x in frame.x0 - ringM..frame.x1 + ringM && y in frame.y0 - ringM..frame.y1 + ringM &&
                !(x in frame.x0 - INSET_M..frame.x1 + INSET_M && y in frame.y0 - INSET_M..frame.y1 + INSET_M)
        }
        return face - around
    }

    /**
     * Per footprint cell (10 cm, as the flatten's) of the frame's face, how many wall faces —
     * facing the room, within [WALL_BAND_M] of the wall — have their centroid in it, and the
     * share of those cells that holds at least n of them, for a few n: the coverage the
     * footprint test would see at each minimum.
     */
    private fun frameCoverage(mesh: WorkMesh): String {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        val wall = HashMap<Long, Int>()
        val face = HashSet<Long>()
        for (t in 0 until faces.count) {
            if (faces.normals[t * 3 + Z] < WALL_FACING) continue
            val x = faces.centroids[t * 3]
            val y = faces.centroids[t * 3 + 1]
            val z = faces.centroids[t * 3 + Z]
            val key = cell(x, y)
            if (abs(z) <= WALL_BAND_M) {
                wall[key] = (wall[key] ?: 0) + 1
            } else if (z <= NEAR_M && x in FRAME.x0..FRAME.x1 && y in FRAME.y0..FRAME.y1) {
                face += key
            }
        }
        if (face.isEmpty()) return "no face of the frame found"
        val cells = face.sorted()
        val counts = cells.map { wall[it] ?: 0 }
        val shares = MIN_COUNTS.joinToString(" ") { n -> "$n:${counts.count { it >= n } * PERCENT / counts.size}%" }
        val perCell = cells.joinToString(" ") { "${it shr Int.SIZE_BITS},${it.toInt()}=${wall[it] ?: 0}" }
        return "${cells.size} cells under the frame, wall centroids per cell [$perCell], covered at n centroids [$shares]"
    }

    private fun cell(x: Float, y: Float): Long {
        val u = floor(x / CELL_M).toInt()
        val v = floor(y / CELL_M).toInt()
        return (u.toLong() shl Int.SIZE_BITS) or (v.toLong() and LOW_INT)
    }

    /** How far the bulge's core stands off the wall around it, which it leaves flat. */
    private fun bulgeRelief(mesh: WorkMesh): Float {
        val core = meanDepth(mesh, X) { _, y, z -> bulgeRadius(y, z) <= CORE_RADIUS }
        val around = meanDepth(mesh, X) { _, y, z -> bulgeRadius(y, z) in RING_RADII }
        return core - around
    }

    /** The share of the area of the bulge's core that [result] left off every plane. */
    private fun freeShare(mesh: WorkMesh, result: Flattening): Float {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        var free = 0.0
        var total = 0.0
        for (t in 0 until faces.count) {
            if (faces.normals[t * 3 + X] < FACING || abs(faces.centroids[t * 3 + X]) > NEAR_M) continue
            if (bulgeRadius(faces.centroids[t * 3 + 1], faces.centroids[t * 3 + 2]) > CORE_RADIUS) continue
            total += faces.areas[t]
            if (result.facePlane[t] < 0) free += faces.areas[t]
        }
        return if (total == 0.0) Float.NaN else (free / total).toFloat()
    }

    private fun cm(m: Float): String = String.format(Locale.ROOT, "%.2f", m * CM_PER_M)

    private fun percent(share: Float): String = String.format(Locale.ROOT, "%.0f%%", share * PERCENT)

    private companion object {
        const val STEP = 0.025f
        const val DOOR_Z = -0.035f
        const val DOOR_TOP = 2.025f
        const val SECOND_PASS_Z = -0.04f

        /** The door in 3 × 3 pieces, each 0.2 m², under a region's 0.25 m². */
        const val DOOR_PIECES = 3
        const val DOOR_PIECE_W = 0.3f
        const val DOOR_PIECE_H = 0.675f

        /** tan 1°. */
        const val TILT = 0.0175f

        /** A sheet left on its own plane does not move: well under the scan's millimetres. */
        const val FLAT_M = 0.001f

        const val X = 0
        const val Z = 2
        const val FACING = 0.8f
        const val NEAR_M = 0.06f
        const val INSET_M = 0.05f
        const val RING_M = 0.25f

        /** A small frame's ring of wall: clear of its neighbours, 20 cm apart and more. */
        const val SMALL_RING_M = 0.15f
        const val MIN_RELIEF_M = 0.02f

        /** As much of the relief as the TV and the door of the real-scan room must keep. */
        const val MIN_RELIEF_KEPT = 0.75f

        const val MIN_BULGE_M = 0.01f
        const val MAX_BULGE_LEFT = 0.25f
        const val BULGE_M = 0.03f
        const val CORE_RADIUS = 0.5f
        val RING_RADII = 1.1f..1.5f

        /** cos 18°: the angle a wall's region grows within. */
        const val WALL_FACING = 0.951f
        const val WALL_BAND_M = 0.015f
        const val CELL_M = 0.1f
        const val LOW_INT = 0xFFFFFFFFL
        val MIN_COUNTS = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 20, 24)
        const val PERCENT = 100
        const val CM_PER_M = 100f

        val ROOM = Box(0f, 0f, 0f, 2.6f, 2.5f, 2.2f)

        /** 40 × 30 cm, 3 cm deep, its edges 4.5 to 5.5 cm into the footprint cells. */
        val FRAME = Box(1.055f, 1.155f, -0.05f, 1.455f, 1.455f, 0.03f)

        /** Where the x = 0 wall bulges, out of 1: an ellipse 1 m tall and 1.2 m wide. */
        fun bulgeRadius(y: Float, z: Float): Float {
            val dy = (y - 1.25f) / 0.5f
            val dz = (z - 1.1f) / 0.6f
            return sqrt(dy * dy + dz * dz)
        }

        /** How far the x = 0 wall bulges into the room: [BULGE_M] at the middle, nothing at the rim, no edge. */
        fun bulge(y: Float, z: Float): Float {
            val r = bulgeRadius(y, z)
            return if (r >= 1f) 0f else BULGE_M * 0.5f * (1f + cos(PI.toFloat() * r))
        }

        /** How far each small frame's corner sits from a footprint cell's corner, along x and y. */
        val OFFSETS = floatArrayOf(0f, 0.025f, 0.05f, 0.075f)

        /** 30 × 20 cm frames, 3 cm deep, one per offset, half a metre apart. */
        val POSTCARD_FRAMES = OFFSETS.mapIndexed { k, off ->
            val x0 = 0.3f + 0.5f * k + off
            val y0 = 0.8f + off
            Box(x0, y0, -0.05f, x0 + 0.3f, y0 + 0.2f, 0.03f)
        }

        /** A4 frames, 21 × 29.7 cm and 3 cm deep, one per offset, above the others. */
        val A4_FRAMES = OFFSETS.mapIndexed { k, off ->
            val x0 = 0.3f + 0.5f * k + off
            val y0 = 1.5f + off
            Box(x0, y0, -0.05f, x0 + 0.21f, y0 + 0.297f, 0.03f)
        }

        /** The room with the small frames on its z = 0 wall, as the real TSDF meshes it. */
        val smallFrames: WorkMesh by lazy {
            val bounds = Box(-0.2f, -0.2f, -0.2f, 2.8f, 2.7f, 2.4f)
            val frames = POSTCARD_FRAMES + A4_FRAMES
            CleanScenes.mesh(
                CleanScenes.fuse(bounds, rippleM = 0.004f, noiseM = 0.006f) { x, y, z ->
                    frames.fold(-ROOM.sdf(x, y, z)) { d, frame -> min(d, frame.sdf(x, y, z)) }
                },
            )
        }

        /** A room whose x = 0 wall bulges and whose z = 0 wall holds the frame, as the real TSDF meshes it. */
        val scene: WorkMesh by lazy {
            val bounds = Box(-0.2f, -0.2f, -0.2f, 2.8f, 2.7f, 2.4f)
            CleanScenes.mesh(
                CleanScenes.fuse(bounds, rippleM = 0.004f, noiseM = 0.006f) { x, y, z ->
                    min(min(-ROOM.sdf(x, y, z), x - bulge(y, z)), FRAME.sdf(x, y, z))
                },
            )
        }
    }
}
