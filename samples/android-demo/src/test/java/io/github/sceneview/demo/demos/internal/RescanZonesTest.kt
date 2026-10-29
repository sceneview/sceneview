package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The parts of a scan worth scanning again (#4736): each kind of zone on plain inputs, then the
 * whole chain — the room survey's walls, the ground's holes, the cloud's coverage — on the
 * ray-cast room, whole and with a wall panel, a piece of floor and a corner's density taken out.
 */
class RescanZonesTest {

    // ── The finder, on plain inputs ────────────────────────────────────────────────────────────

    /** A 4 m × 3 m room's four walls, chained, every one seen. */
    private fun walls(
        inferred: Int = -1,
        unseen: Map<Int, List<ClosedFloatingPointRange<Float>>> = emptyMap(),
    ): List<RescanWall> {
        val corners = listOf(-2f to -1.5f, 2f to -1.5f, 2f to 1.5f, -2f to 1.5f)
        return corners.indices.map { i ->
            val (ax, az) = corners[i]
            val (bx, bz) = corners[(i + 1) % corners.size]
            RescanWall(ax, az, bx, bz, inferred = i == inferred, unseen = unseen[i].orEmpty())
        }
    }

    /** A coverage of the same room, 25 cm cells, every one at [base] but those [paint] sets. */
    private fun coverage(base: Float = 100f, paint: (x: Float, z: Float) -> Float? = { _, _ -> null }): RescanCoverage {
        val cell = 0.25f
        val columns = 18
        val rows = 14
        val originX = -2.25f
        val originZ = -1.75f
        val density = FloatArray(columns * rows) { at ->
            val x = originX + (at % columns + 0.5f) * cell
            val z = originZ + (at / columns + 0.5f) * cell
            if (abs(x) > 2f || abs(z) > 1.5f) 0f else paint(x, z) ?: base
        }
        return RescanCoverage(cell, originX, originZ, columns, rows, density)
    }

    private fun square(x0: Float, z0: Float, x1: Float, z1: Float) = RescanHole(floatArrayOf(x0, z0, x1, z0, x1, z1, x0, z1))

    @Test
    fun `a complete room has nothing to scan again`() {
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(), coverage = coverage()))
        assertEquals(zones.joinToString(), 0, zones.size)
    }

    @Test
    fun `an inferred wall is one zone end to end`() {
        val zones = RescanZones.find(RescanInput(floorY = 0f, height = 2.5f, walls = walls(inferred = 1), coverage = coverage()))
        assertEquals(zones.joinToString(), 1, zones.size)
        val zone = zones.single()
        assertEquals(RescanKind.MissingWall, zone.kind)
        assertEquals(3f, zone.size, 1e-4f)
        // The east wall, x = 2, from z -1.5 to 1.5, floor to ceiling.
        assertEquals(2f - RescanZones.WALL_HALF_M, zone.box[0], 1e-4f)
        assertEquals(0f, zone.box[1], 1e-4f)
        assertEquals(-1.5f - RescanZones.WALL_HALF_M, zone.box[2], 1e-4f)
        assertEquals(2.5f, zone.box[4], 1e-4f)
        assertEquals(1.5f + RescanZones.WALL_HALF_M, zone.box[5], 1e-4f)
    }

    @Test
    fun `an unseen stretch of a seen wall is a zone, a short one is not`() {
        // The south wall runs from (-2, -1.5) to (2, -1.5): 1 m to 2 m along it is x -1..0.
        val unseen = mapOf(0 to listOf(1f..2f, 3f..3.2f))
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(unseen = unseen), coverage = coverage()))
        assertEquals(zones.joinToString(), 1, zones.size)
        val zone = zones.single()
        assertEquals(RescanKind.MissingWall, zone.kind)
        assertEquals(1f, zone.size, 1e-4f)
        assertEquals(-1f, zone.centre[0] - 0.5f, 1e-4f)
        assertEquals(-1.5f, zone.centre[1], 1e-4f)
    }

    @Test
    fun `floor never seen with nothing on it is a hole`() {
        val hole = square(-0.5f, -0.5f, 0.25f, 0.25f)
        val cover = coverage { x, z -> if (hole.contains(x, z)) 0f else null }
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(), holes = listOf(hole), coverage = cover))
        assertEquals(zones.joinToString(), 1, zones.size)
        val zone = zones.single()
        assertEquals(RescanKind.FloorHole, zone.kind)
        assertEquals(hole.area, zone.size, 1e-4f)
        assertEquals(-0.5f, zone.box[0], 1e-4f)
        assertEquals(0.25f, zone.box[5], 1e-4f)
    }

    @Test
    fun `a hole under furniture is not a zone`() {
        // The floor under a cabinet: never seen, but the cabinet over it was.
        val hole = square(1f, 0.5f, 1.75f, 1.25f)
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(), holes = listOf(hole), coverage = coverage()))
        assertEquals(zones.joinToString(), 0, zones.size)
    }

    @Test
    fun `the floor at the back of a tall cabinet is not a zone either`() {
        // Its front seen, its top above every camera: the cells at the back read empty.
        val hole = square(1.5f, 0.5f, 2f, 1.5f)
        val cover = coverage { x, z -> if (x > 1.75f && z in 0.5f..1.5f) 0f else null }
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(), holes = listOf(hole), coverage = cover))
        assertEquals(zones.joinToString(), 0, zones.size)
    }

    @Test
    fun `a sparsely seen patch is low density, a single sparse cell is not`() {
        val cover = coverage { x, z ->
            when {
                x in -1.75f..-1f && z in 0.5f..1.25f -> 10f
                x in 1f..1.25f && z in -1f..-0.75f -> 10f
                else -> null
            }
        }
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(), coverage = cover))
        assertEquals(zones.joinToString(), 1, zones.size)
        val zone = zones.single()
        assertEquals(RescanKind.LowDensity, zone.kind)
        assertEquals(9 * 0.25f * 0.25f, zone.size, 1e-4f)
        assertTrue(zone.reason, zone.reason.contains("10 %"))
    }

    @Test
    fun `without coverage a hole stands alone, a small one does not`() {
        val holes = listOf(square(0f, 0f, 0.5f, 0.5f), square(1f, 1f, 1.2f, 1.2f))
        val zones = RescanZones.find(RescanInput(floorY = 0f, holes = holes))
        assertEquals(zones.joinToString(), 1, zones.size)
        assertEquals(RescanKind.FloorHole, zones.single().kind)
        assertEquals(0.25f, zones.single().size, 1e-4f)
    }

    @Test
    fun `floor left unseen at the foot of a missing wall is the wall's`() {
        // The east wall inferred, and the strip of floor along it never seen either.
        val cover = coverage { x, _ -> if (x > 1.75f) 0f else null }
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = walls(inferred = 1), coverage = cover))
        assertEquals(zones.joinToString(), listOf(RescanKind.MissingWall), zones.map { it.kind })
    }

    @Test
    fun `walls that do not close read the covered area's own outline`() {
        val open = walls().dropLast(1)
        val cover = coverage { x, z -> if (x in -0.5f..0.25f && z in -0.5f..0.25f) 10f else null }
        val zones = RescanZones.find(RescanInput(floorY = 0f, walls = open, coverage = cover))
        assertEquals(zones.joinToString(), listOf(RescanKind.LowDensity), zones.map { it.kind })
    }

    // ── The whole chain, on the ray-cast room ──────────────────────────────────────────────────

    @Test
    fun `the survey's walls land on the room's in the scan frame`() {
        val walls = RescanSources.walls(survey(room), room.positions, room.normals!!, room.count)
        assertEquals(4, walls.size)
        assertTrue(walls.joinToString { it.describe() }, walls.none { it.inferred })
        for (wall in walls) assertOnRoomWall(wall, turn = 0f, dx = 0f, dz = 0f)
    }

    @Test
    fun `the survey's walls land on a turned and shifted room's`() {
        // The same scan, read from a session that started elsewhere: 30° about Y, 1.2 m and -0.7 m.
        val turn = Math.toRadians(30.0).toFloat()
        val moved = moved(room, turn, 1.2f, -0.7f)
        val walls = RescanSources.walls(survey(moved), moved.positions, moved.normals!!, moved.count)
        assertEquals(4, walls.size)
        for (wall in walls) assertOnRoomWall(wall, turn, 1.2f, -0.7f)
    }

    @Test
    fun `the whole room has nothing to scan again, as fused and as a recording stores it`() {
        // A recording keeps the cloud to the millimetre (SVPC): the walls move off the grid lines
        // the room's own walls sit on, and what the room hides must still read as seen.
        for (cloud in listOf(room, SvpcCodec.decode(SvpcCodec.encode(room))!!)) {
            val zones = zonesOf(cloud)
            assertEquals(zones.joinToString("\n"), 0, zones.size)
        }
    }

    @Test
    fun `a wall panel, a piece of floor and a sparse corner taken out give three zones of their kinds`() {
        var kept = 0
        val damaged = room.filter { x, y, z ->
            when {
                // A 1 m panel of the far wall (z = 1.8), floor to ceiling.
                z > 1.7f && x in -0.2f..0.8f -> false
                // 70 cm × 70 cm of open floor, nothing over it.
                y < 0.1f && x in -0.6f..0.1f && z in -0.8f..-0.1f -> false
                // The near-right corner, one point in ten kept, at every height.
                x in 1.0f..2.1f && z in -1.9f..-0.8f -> kept++ % SPARSE == 0
                else -> true
            }
        }
        val zones = zonesOf(damaged)
        val report = zones.joinToString("\n")
        assertEquals(report, 3, zones.size)
        val wall = zones.single { it.kind == RescanKind.MissingWall }
        val hole = zones.single { it.kind == RescanKind.FloorHole }
        val sparse = zones.single { it.kind == RescanKind.LowDensity }
        assertCentre(report, wall, 0.3f, 1.8f)
        assertEquals(report, 1f, wall.size, WALL_TOLERANCE_M)
        assertCentre(report, hole, -0.25f, -0.45f)
        assertEquals(report, 0.49f, hole.size, HOLE_TOLERANCE_M2)
        assertCentre(report, sparse, 1.5f, -1.3f, SPARSE_TOLERANCE_M)
        assertTrue(report, sparse.size >= RescanZones.MIN_LOW_M2)
    }

    private fun zonesOf(cloud: DenseCloud): List<RescanZone> {
        val ground = ScanGround.of(cloud.positions, cloud.normals, cloud.count)
        return RescanSources.zones(cloud, ground)
    }

    private fun survey(cloud: DenseCloud) = RoomSurveyor.survey(RoomSurveyInput(cloud.positions, cloud.normals))

    private fun assertCentre(report: String, zone: RescanZone, x: Float, z: Float, tolerance: Float = CENTRE_TOLERANCE_M) {
        val c = zone.centre
        assertTrue("${zone.kind} at ${c[0]}, ${c[1]}, not near $x, $z\n$report", abs(c[0] - x) <= tolerance && abs(c[1] - z) <= tolerance)
    }

    /** [wall] on one of the room's four walls, the room turned by [turn] about Y then shifted by [dx], [dz]. */
    private fun assertOnRoomWall(wall: RescanWall, turn: Float, dx: Float, dz: Float) {
        // Back into the room's own frame.
        fun back(x: Float, z: Float): Pair<Float, Float> {
            val px = x - dx
            val pz = z - dz
            val c = cos(turn)
            val s = sin(turn)
            return (px * c + pz * s) to (-px * s + pz * c)
        }
        val (ax, az) = back(wall.ax, wall.az)
        val (bx, bz) = back(wall.bx, wall.bz)
        val min = RerunSyntheticRoom.MIN
        val max = RerunSyntheticRoom.MAX
        val onX = listOf(min.x, max.x).any { abs(ax - it) <= WALL_FIT_M && abs(bx - it) <= WALL_FIT_M }
        val onZ = listOf(min.z, max.z).any { abs(az - it) <= WALL_FIT_M && abs(bz - it) <= WALL_FIT_M }
        assertTrue("${wall.describe()} -> ($ax, $az)..($bx, $bz) is on no wall of the room", onX || onZ)
    }

    private fun RescanWall.describe() = "($ax, $az)..($bx, $bz)${if (inferred) " inferred" else ""} $unseen"

    companion object {
        private const val SPARSE = 10
        private const val CENTRE_TOLERANCE_M = 0.2f
        private const val SPARSE_TOLERANCE_M = 0.35f
        private const val WALL_TOLERANCE_M = 0.25f
        private const val HOLE_TOLERANCE_M2 = 0.15f
        private const val WALL_FIT_M = 0.05f

        /**
         * Where the walk stands: the ray-cast room's three stops, which leave floor unseen behind
         * the table and between the sofa's end and the left wall (real holes, a quarter of a
         * square metre each), and two more that look into them, so the room is whole.
         */
        val STOPS = RerunSyntheticRoom.STOPS + Vec3(0.3f, 1.4f, -1.3f) + Vec3(-1.5f, 1.4f, -0.5f)

        /** The ray-cast room, fused from [STOPS] as a recording fuses it. */
        val room: DenseCloud by lazy {
            val fusion = DenseFusion()
            for (pose in RerunSyntheticRoom.poses(stops = STOPS)) {
                fusion.add(DepthBackProjection.project(RerunSyntheticRoom.scene.render(pose)))
            }
            fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        }

        /** [this] with only the points [keep] keeps. */
        fun DenseCloud.filter(keep: (x: Float, y: Float, z: Float) -> Boolean): DenseCloud {
            val kept = (0 until count).filter { keep(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2]) }
            return DenseCloud(
                positions = FloatArray(kept.size * 3) { positions[kept[it / 3] * 3 + it % 3] },
                colors = IntArray(kept.size) { colors[kept[it]] },
                normals = normals?.let { n -> FloatArray(kept.size * 3) { n[kept[it / 3] * 3 + it % 3] } },
                confidences = confidences?.let { c -> ByteArray(kept.size) { c[kept[it]] } },
                levels = levels?.let { l -> ByteArray(kept.size) { l[kept[it]] } },
            )
        }

        /** [cloud] turned by [turn] about Y, then shifted by [dx], [dz]: a rigid move of a scan. */
        fun moved(cloud: DenseCloud, turn: Float, dx: Float, dz: Float): DenseCloud {
            val c = cos(turn)
            val s = sin(turn)
            val p = FloatArray(cloud.positions.size)
            val n = cloud.normals?.let { FloatArray(it.size) }
            for (i in 0 until cloud.count) {
                val x = cloud.positions[i * 3]
                val z = cloud.positions[i * 3 + 2]
                p[i * 3] = x * c - z * s + dx
                p[i * 3 + 1] = cloud.positions[i * 3 + 1]
                p[i * 3 + 2] = x * s + z * c + dz
                if (n != null) {
                    val nx = cloud.normals!![i * 3]
                    val nz = cloud.normals[i * 3 + 2]
                    n[i * 3] = nx * c - nz * s
                    n[i * 3 + 1] = cloud.normals[i * 3 + 1]
                    n[i * 3 + 2] = nx * s + nz * c
                }
            }
            return DenseCloud(p, cloud.colors, n, cloud.confidences, cloud.levels)
        }
    }
}
