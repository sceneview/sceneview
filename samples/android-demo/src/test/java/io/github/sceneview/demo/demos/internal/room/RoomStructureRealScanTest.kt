package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import io.github.sceneview.demo.demos.internal.RoomSurveyor
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.demos.internal.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The room engine on synthetic scans shaped like the phone scans that defeated it: walls seen only
 * up to 0.9 m, floor seen only ahead of the camera, a doorway the floor runs on through, one wall
 * seen twice, two walls of four, an L and a T seen on three quarters of their length, corners
 * hidden behind furniture, a headboard against a wall, a bedroom whose clutter outweighs its walls,
 * one wall seen along a stretch of it, a garage's storage fronts, walls the depth missed and ARCore
 * found as planes, a side open onto the next room, a wall returned only in shards out of line, a far
 * wall never seen, two walls not followed to their far corners, cupboard fronts out of line before
 * a wall never seen. When a figure is given it is
 * within [MAX_ERROR] of the room's, or the case's own bound; where the walls seen do not close the
 * room, none is, and the plan is still drawn — but two adjacent walls alone give an area marked as
 * an estimate, the floor seen standing for the other two.
 */
class RoomStructureRealScanTest {

    /**
     * A case: the room, the camera's walk through it and the [share] of that walk's frames scanned,
     * whether its walls close it — or, [estimated], give an area only as an estimate — and how near
     * its area has to be.
     */
    private class Case(
        val name: String,
        val room: SyntheticRoom,
        val figures: Boolean,
        val walk: List<Vec3>? = null,
        val maxError: Float = MAX_ERROR,
        val estimated: Boolean = false,
        val share: Float = 1f,
    )

    private fun capture(case: Case): SyntheticCapture = synchronized(captures) {
        captures.getOrPut(case.name) {
            if (case.walk == null) {
                SyntheticScan.capture(case.room, share = case.share)
            } else {
                SyntheticScan.capture(case.room, share = case.share, walk = case.walk)
            }
        }
    }

    /** One line a failure prints: what was read against the room. */
    private fun summary(case: Case, structure: RoomStructure): String {
        val area = structure.area?.value
        val error = area?.let { "%+.1f %%".format((it - case.room.area) / case.room.area * 100) } ?: "no area"
        return "${case.name}: area ${area?.let { "%.2f".format(it) }} for ${"%.2f".format(case.room.area)} ($error), " +
            "${structure.walls.size} walls, ${structure.seenWalls} seen, ${structure.walls.count { it.deduced }} deduced, " +
            "perimeter ${structure.perimeter?.value?.let { "%.2f".format(it) }} for ${"%.2f".format(case.room.perimeter)}, " +
            "height ${structure.ceilingHeight?.value?.let { "%.2f".format(it) }}, gaps ${structure.gaps}"
    }

    /** What is wrong with [case]'s reading, or `null`. */
    private fun problem(case: Case, structure: RoomStructure): String? {
        val area = structure.area
        val reading = structure.reading
        return when {
            structure.walls.isEmpty() -> "no plan"
            !case.figures && area != null -> "a figure where the walls seen do not close the room"
            !case.figures && (structure.perimeter != null || structure.length != null || structure.width != null) -> "a length"
            !case.figures && RoomGap.MissingWall !in structure.gaps -> "no missing wall"
            case.figures && area == null -> "no area"
            case.estimated && !area!!.estimate -> "an area not marked as an estimate"
            // Walls that do not close the room, or close it on fewer than [RoomStructure.MIN_SEEN_WALLS]
            // distinct walls, give a length only measured between two seen walls.
            case.estimated &&
                (reading?.closed != true || (reading?.distinctWalls ?: 0) < RoomStructure.MIN_SEEN_WALLS) &&
                (structure.length?.estimate == true || structure.width?.estimate == true) -> "an estimated length"
            case.figures && abs(area!!.value - case.room.area) / case.room.area >= case.maxError -> "area off"
            else -> null
        }
    }

    private fun check(case: Case) {
        val structure = RoomStructure.read(capture(case).scan())
        val problem = problem(case, structure)
        assertNull("${summary(case, structure)}: $problem", problem)
    }

    @Test
    fun `walls seen only up to 0,9 m are walls, a sofa standing free is not`() {
        check(LOW_WALLS)
    }

    @Test
    fun `floor seen only ahead of the camera still holds the room`() {
        val scan = capture(FLOOR_AHEAD).scan()
        val share = trailOverFloor(scan)
        assertTrue("the walk stands over seen floor ${"%.0f".format(share * 100)} % of the time", share < MAX_TRAIL_OVER_FLOOR)
        check(FLOOR_AHEAD)
    }

    @Test
    fun `floor seen a metre on through a doorway stays out of the room`() {
        check(DOOR)
    }

    @Test
    fun `one wall seen twice 3 cm apart gives a plan and no figure`() {
        check(TWICE)
    }

    @Test
    fun `two adjacent walls of four give an area marked as an estimate and no estimated length`() {
        check(TWO_LOW)
        check(TWO)
    }

    @Test
    fun `two adjacent walls trace the room's own sides, not the floor's edge`() {
        for (case in listOf(TWO_LOW, TWO, L_CORNER)) {
            val structure = RoomStructure.read(capture(case).scan())
            val reading = structure.reading
            val summary = "${summary(case, structure)}, traced ${reading?.traced}, missed ${reading?.miss}"
            assertTrue(summary, reading?.traced == true)
            assertEquals(summary, case.room.x.size, structure.walls.size)
            assertEquals(summary, 2, structure.walls.count { it.deduced })
        }
    }

    @Test
    fun `two facing walls of four give the width between them and no area`() {
        val structure = RoomStructure.read(capture(FACING).scan())
        val summary = summary(FACING, structure)
        assertTrue("$summary: no plan", structure.walls.isNotEmpty())
        assertNull(summary, structure.area)
        assertNull(summary, structure.perimeter)
        assertNull(summary, structure.volume)
        assertTrue(summary, RoomGap.MissingWall in structure.gaps)
        val between = listOfNotNull(structure.length, structure.width)
        assertTrue("$summary: ${between.size} lengths", between.size == 1 && !between[0].estimate)
        assertTrue("$summary: width ${between[0].value}", abs(between[0].value - FACING_APART_M) / FACING_APART_M < MAX_ERROR)
    }

    @Test
    fun `an L seen on three quarters of its walls is read between them`() {
        check(L_PART)
    }

    @Test
    fun `a T seen on three quarters of its walls is read between them`() {
        check(T_PART)
    }

    @Test
    fun `corners hidden behind furniture against walls seen only low still close the room`() {
        check(CORNERS)
    }

    @Test
    fun `a headboard against a wall seen only low does not pull the wall in`() {
        check(HEADBOARD)
    }

    @Test
    fun `walls each under 3 % of the vertical surfels, among clutter turned every way, are walls`() {
        val scan = capture(CLUTTERED).scan()
        val shares = intArrayOf(0, 90, 180, 270).map { facingShare(scan, it) }
        val summary = shares.joinToString { "%.2f %%".format(it * 100) }
        println("RoomStructure cluttered bedroom, walls' shares $summary")
        assertTrue("walls' shares $summary", shares.all { it < CLUTTERED_WALL_SHARE })
        check(CLUTTERED)
    }

    @Test
    fun `one wall seen along 1,5 m of a 5 m side draws no plan`() {
        val structure = RoomStructure.read(capture(STRETCH).scan())
        val summary = summary(STRETCH, structure)
        assertTrue("$summary: a plan", structure.walls.isEmpty())
        assertEquals(summary, RoomGap.NoWalls, structure.gaps.first())
        assertNull(summary, structure.area)
        assertEquals(summary, 2.5f, structure.ceilingHeight!!.value, 0.05f)
    }

    @Test
    fun `storage fronts 2 m high before a garage's walls are furniture`() {
        check(GARAGE)
    }

    @Test
    fun `walls the depth missed and ARCore found as planes close the room`() {
        check(PLANES)
    }

    @Test
    fun `two passes on one wall 15 cm apart are one wall`() {
        val structure = RoomStructure.read(capture(APART).scan())
        val summary = summary(APART, structure)
        assertTrue("$summary: no plan", structure.walls.isNotEmpty())
        assertNull(summary, structure.area)
        assertNull(summary, structure.perimeter)
        assertNull(summary, structure.volume)
        assertTrue(summary, RoomGap.MissingWall in structure.gaps)
    }

    @Test
    fun `a side open onto the next room closes on the opening`() {
        for (case in listOf(OPEN, OPEN_WIDE)) {
            check(case)
            val structure = RoomStructure.read(capture(case).scan())
            val summary = summary(case, structure)
            val beyond = structure.walls.count { max(it.az, it.bz) > RECTANGLE_Z.max() + PAST_OPENING_M }
            assertTrue("$summary: $beyond walls drawn into the next room", beyond == 0)
            assertTrue("$summary: ${structure.walls.size} walls", structure.walls.size == RECTANGLE_X.size)
        }
    }

    @Test
    fun `a wall returned only in shards out of line is one wall`() {
        check(SHARDS)
        val structure = RoomStructure.read(capture(SHARDS).scan())
        assertTrue("${summary(SHARDS, structure)}: ${structure.seenWalls} walls seen", structure.seenWalls == 2)
    }

    @Test
    fun `a scan that stopped short of the fourth wall gives a plan and no figure`() {
        val structure = RoomStructure.read(capture(STOPPED).scan())
        val summary = summary(STOPPED, structure)
        println("RoomStructure real-scan-like $summary, ${timings(STOPPED)}")
        assertTrue("$summary: no plan", structure.walls.isNotEmpty())
        assertNull(summary, structure.area)
        assertNull(summary, structure.perimeter)
        assertNull(summary, structure.volume)
        assertNull(summary, structure.width)
        assertTrue(summary, RoomGap.MissingWall in structure.gaps)
    }

    @Test
    fun `a wardrobe against a wall seen only low does not stand for the wall`() {
        val structure = RoomStructure.read(capture(WARDROBE).scan())
        val summary = summary(WARDROBE, structure)
        println("RoomStructure real-scan-like $summary, ${timings(WARDROBE)}")
        val area = structure.area?.value
        assertTrue("$summary: area off", area == null || abs(area - WARDROBE.room.area) / WARDROBE.room.area < MAX_ERROR)
        val front = structure.walls.filter { abs(it.az - WARDROBE_DEPTH_M) < FRONT_NEAR_M && abs(it.bz - WARDROBE_DEPTH_M) < FRONT_NEAR_M }
        assertTrue("$summary: the wardrobe's front is a wall", front.isEmpty())
    }

    @Test
    fun `a far wall never seen, the floor read 1,5 to 2 m out, is one side deduced from the floor's edge`() {
        for (case in listOf(FAR_SHORT, FAR)) {
            val structure = RoomStructure.read(capture(case).scan())
            val summary = summary(case, structure)
            assertEquals(summary, RECTANGLE_X.size, structure.walls.size)
            assertEquals(summary, 1, structure.walls.count { it.deduced })
            val area = structure.area
            assertTrue("$summary: no area", area != null)
            assertTrue("$summary: an area not marked as an estimate", area!!.estimate)
            assertTrue("$summary: area off", abs(area.value - case.room.area) / case.room.area < MAX_ERROR)
        }
    }

    @Ignore("A 9 cm spur along the inner wall: https://github.com/sceneview/sceneview/issues/4784")
    @Test
    fun `an L's two walls never seen at the end of its stem are deduced as two sides`() {
        val structure = RoomStructure.read(capture(L_STEM).scan())
        val summary = summary(L_STEM, structure)
        assertTrue("$summary: ${structure.walls.size} sides", structure.walls.size <= L_STEM.room.x.size)
        assertTrue(summary, structure.walls.count { it.deduced } <= 2)
    }

    @Test
    fun `an L's corner never seen gives its area as an estimate and its corners where they stand`() {
        check(L_CORNER)
        val structure = RoomStructure.read(capture(L_CORNER).scan())
        val summary = summary(L_CORNER, structure)
        val room = L_CORNER.room
        // Its west and north walls never seen, both its spans stand on the floor's edge: estimates.
        assertTrue("$summary: a length not marked as an estimate", structure.length?.estimate == true)
        assertTrue("$summary: a width not marked as an estimate", structure.width?.estimate == true)
        for (i in room.x.indices) {
            val off = structure.walls.minOf { hypot(it.ax - room.x[i], it.az - room.z[i]) }
            assertTrue("$summary: corner $i ${"%.2f".format(off)} m off", off < CORNER_NEAR_M)
        }
    }

    @Test
    fun `two walls not followed to their far corners give no area and say to walk on to the corner`() {
        val structure = RoomStructure.read(capture(QUARTER).scan())
        val summary = summary(QUARTER, structure)
        assertTrue("$summary: no plan", structure.walls.isNotEmpty())
        assertNull(summary, structure.area)
        assertEquals(summary, "WallEnd", structure.gaps.firstOrNull()?.name)
        val survey = RoomSurveyor.of(structure)
        assertNull(summary, survey.area)
        assertEquals(summary, "Walk along the wall to the corner.", SurveyReport.areaHint(survey, WORDING))
    }

    @Test
    fun `walls seen partway give no area, or one within 10 %`() {
        for (case in listOf(QUARTER_REACH, HALF_WALK)) {
            val structure = RoomStructure.read(capture(case).scan())
            val area = structure.area?.value
            assertTrue(summary(case, structure), area == null || abs(area - case.room.area) / case.room.area < PARTWAY_ERROR)
        }
    }

    @Test
    fun `the live area of two adjacent walls is marked as an estimate`() {
        val live = RoomStructure.live(capture(TWO_LOW).scan())
        assertTrue("live area ${live.area}", live.area?.estimate == true)
    }

    @Test
    fun `cupboard fronts far apart before a wall never seen do not stand for it`() {
        val structure = RoomStructure.read(capture(CUPBOARDS).scan())
        val summary = summary(CUPBOARDS, structure)
        val north = RECTANGLE_Z.max()
        val fronts = structure.walls.filter {
            abs(it.az - it.bz) < FRONT_NEAR_M && max(it.az, it.bz) < north - FRONT_NEAR_M && min(it.az, it.bz) > north - 1f
        }
        assertTrue("$summary: ${fronts.size} walls along the cupboards' fronts", fronts.isEmpty())
        // The floor seen stands for the wall unseen: the room's full 20 m², marked as an estimate.
        check(CUPBOARDS)
    }

    /** A case's full reading's median and live reading's best run. */
    private fun timings(case: Case): String {
        val scan = capture(case).scan()
        repeat(WARMUP) { RoomStructure.read(scan) }
        val full = (0 until RUNS).map { timed { RoomStructure.read(scan) } }.sorted()
        repeat(WARMUP) { RoomStructure.live(scan) }
        val live = (0 until RUNS).map { timed { RoomStructure.live(scan) } }.sorted()
        return "full ${"%.1f".format(full[RUNS / 2])} ms, live ${"%.2f".format(live.first())} ms"
    }

    @Test
    fun `every case at once, with its timings`() {
        val lines = ArrayList<String>()
        var failed = false
        for (case in CASES) {
            val scan = capture(case).scan()
            repeat(WARMUP) { RoomStructure.read(scan) }
            val full = (0 until RUNS).map { timed { RoomStructure.read(scan) } }.sorted()
            repeat(WARMUP) { RoomStructure.live(scan) }
            val live = (0 until RUNS).map { timed { RoomStructure.live(scan) } }.sorted()
            val structure = RoomStructure.read(scan)
            val problem = problem(case, structure)
            val slow = full[RUNS / 2] >= FULL_BUDGET_MS || live.first() >= LIVE_BUDGET_MS
            if (problem != null || (BUDGETED && slow)) failed = true
            lines += "${summary(case, structure)}, full ${"%.1f".format(full[RUNS / 2])} ms, " +
                "live ${"%.2f".format(live.first())} ms, ${scan.positions.size / 3} points" + (problem?.let { " — $it" } ?: "")
        }
        lines.forEach { println("RoomStructure real-scan-like $it") }
        assertTrue(lines.joinToString(" | "), !failed)
    }

    /**
     * The share of [scan]'s vertical surfels, from [RoomLevels.WALL_FROM_M] up, that face within
     * [FACING_DEG] of [degrees] — about +Y, from +X towards +Z: a wall's peak as the engine smooths it.
     */
    private fun facingShare(scan: RoomScan, degrees: Int): Float {
        val normals = scan.normals ?: return 0f
        var vertical = 0
        var facing = 0
        for (i in 0 until scan.positions.size / 3) {
            val nx = normals[i * 3]
            val nz = normals[i * 3 + 2]
            if (abs(normals[i * 3 + 1]) > RoomLevels.VERTICAL || hypot(nx, nz) < HORIZONTAL) continue
            if (scan.positions[i * 3 + 1] < RoomLevels.WALL_FROM_M) continue
            vertical++
            val a = Math.toDegrees(atan2(nz, nx).toDouble()).toFloat()
            val off = abs(((a - degrees) % FULL_TURN + FULL_TURN + HALF_TURN) % FULL_TURN - HALF_TURN)
            if (off <= FACING_DEG) facing++
        }
        return if (vertical > 0) facing.toFloat() / vertical else 0f
    }

    /** The share of the camera's path that stands over floor the scan saw. */
    private fun trailOverFloor(scan: RoomScan): Float {
        val normals = scan.normals ?: return 0f
        val floor = HashSet<Long>()
        for (i in 0 until scan.positions.size / 3) {
            if (normals[i * 3 + 1] < UP || abs(scan.positions[i * 3 + 1]) > FLOOR_BAND_M) continue
            floor += cellOf(scan.positions[i * 3], scan.positions[i * 3 + 2])
        }
        val steps = scan.trail.size / 3
        if (steps == 0) return 0f
        val over = (0 until steps).count { floor.contains(cellOf(scan.trail[it * 3], scan.trail[it * 3 + 2])) }
        return over.toFloat() / steps
    }

    private fun cellOf(x: Float, z: Float): Long =
        (floor(x / TRAIL_CELL_M).toLong() shl CELL_BITS) xor (floor(z / TRAIL_CELL_M).toLong() and CELL_MASK)

    private inline fun timed(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    private companion object {
        const val MAX_ERROR = 0.03f
        const val MAX_TRAIL_OVER_FLOOR = 0.2f
        const val UP = 0.8f
        const val FLOOR_BAND_M = 0.05f
        const val TRAIL_CELL_M = 0.1f
        const val CELL_BITS = 32
        const val CELL_MASK = 0xffffffffL
        const val WARMUP = 3
        const val RUNS = 7

        /** The budgets of [RoomStructureTest]: a full reading's median, a live reading's best run. */
        const val FULL_BUDGET_MS = 1000.0
        const val LIVE_BUDGET_MS = 20.0

        /**
         * Whether [FULL_BUDGET_MS] and [LIVE_BUDGET_MS] hold every case at once: on a developer's
         * machine. A CI runner shares its cores with other jobs, so there the timings are printed only.
         */
        val BUDGETED = System.getenv("CI") == null

        /** A wall's peak of azimuths, as the engine smooths it: within this many degrees. */
        const val FACING_DEG = 3.5f
        const val HORIZONTAL = 0.5f
        const val FULL_TURN = 360f
        const val HALF_TURN = 180f

        const val CLUTTER_SEED = 11L

        /** A direction holding this share of the vertical surfels is a family of walls on its own. */
        const val CLUTTERED_WALL_SHARE = 0.03f

        /** How high a phone held towards the floor sees the walls. */
        const val LOW_M = 0.9f

        val RECTANGLE_X = floatArrayOf(0f, 5f, 5f, 0f)
        val RECTANGLE_Z = floatArrayOf(0f, 0f, 4f, 4f)

        val LOW_WALLS = Case(
            "low walls",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, seenUpTo = LOW_M, boxes = listOf(SyntheticBox(1.5f, 3.5f, 2.6f, 3.4f, 0.85f))),
            figures = true,
        )

        /** The camera never leaves the room's middle: the floor under it is never seen, the east wall never looked at. */
        val FLOOR_AHEAD = Case(
            "floor ahead",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(1)),
            figures = true,
            walk = listOf(Vec3(2.3f, 1.4f, 1.8f), Vec3(2.7f, 1.4f, 1.8f), Vec3(2.7f, 1.4f, 2.2f), Vec3(2.3f, 1.4f, 2.2f)),
        )

        val DOOR = Case(
            "door",
            SyntheticRoom(
                RECTANGLE_X,
                RECTANGLE_Z,
                openings = listOf(SyntheticOpening(wall = 0, from = 1f, width = 0.9f, sill = 0f, height = 2.05f)),
                seenUpTo = LOW_M,
                nextDoor = listOf(SyntheticFloor(-1f, 6f, -1f, 0f)),
            ),
            figures = true,
        )

        val TWICE = Case(
            "one wall twice",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(1, 2, 3), driftWall = 0, drift = 0.03f, seenUpTo = LOW_M),
            figures = false,
        )

        val TWO_LOW = Case(
            "two walls",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2, 3), seenUpTo = LOW_M),
            figures = true,
            estimated = true,
        )

        val TWO = Case("two full walls", SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2, 3)), figures = true, estimated = true)

        /**
         * The L's north and west walls never seen: the corner they make is deduced from the floor,
         * the area given as an estimate.
         */
        val L_CORNER = Case(
            "L corner unseen",
            SyntheticRoom(floatArrayOf(0f, 6f, 6f, 3f, 3f, 0f), floatArrayOf(0f, 0f, 3f, 3f, 5f, 5f), hidden = setOf(4, 5), seenUpTo = LOW_M),
            figures = true,
            estimated = true,
        )

        /** The L's stem's east and north walls never seen: the corner they make is deduced from the floor. */
        val L_STEM = Case(
            "L stem unseen",
            SyntheticRoom(floatArrayOf(0f, 6f, 6f, 3f, 3f, 0f), floatArrayOf(0f, 0f, 3f, 3f, 5f, 5f), hidden = setOf(3, 4), seenUpTo = LOW_M),
            figures = true,
            estimated = true,
        )

        /** A corner the reading draws stands this near the room's own. */
        const val CORNER_NEAR_M = 0.1f

        /** The rectangle's north wall never seen by a camera kept to the south half, the floor read [floorReach] out. */
        fun far(name: String, floorReach: Float): Case {
            val room = SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2), floorReach = floorReach)
            return Case(name, room, figures = true, walk = SyntheticScan.stops(room).filter { it.z <= SOUTH_HALF_M }, estimated = true)
        }

        const val SOUTH_HALF_M = 2f
        val FAR_SHORT = far("far wall, floor read 1.5 m out", 1.5f)
        val FAR = far("far wall, floor read 2 m out", 2f)

        /**
         * The rectangle seen on its south and east walls only, from a camera kept to their corner's
         * quarter, the floor read 1.5 m out: neither wall followed to its far end, the floor seen
         * covering a little over half the room.
         */
        val QUARTER_ROOM = SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2, 3), seenUpTo = LOW_M, floorReach = 1.5f)
        val QUARTER = Case(
            "quarter walk",
            QUARTER_ROOM,
            figures = false,
            walk = SyntheticScan.stops(QUARTER_ROOM).filter { it.x >= RECTANGLE_X.max() / 2 && it.z <= SOUTH_HALF_M },
        )

        /** [QUARTER], the depth read only 1.5 m out. */
        val QUARTER_REACH_ROOM = SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2, 3), seenUpTo = LOW_M, reach = 1.5f)
        val QUARTER_REACH = Case(
            "quarter walk, depth read 1.5 m out",
            QUARTER_REACH_ROOM,
            figures = false,
            walk = SyntheticScan.stops(QUARTER_REACH_ROOM).filter { it.x >= RECTANGLE_X.max() / 2 && it.z <= SOUTH_HALF_M },
        )

        /** An 8 × 6 m room seen on two adjacent walls, the floor read 2.5 m out, the scan stopped halfway through its walk. */
        val HALF_WALK = Case(
            "8 × 6 m, half the walk",
            SyntheticRoom(floatArrayOf(0f, 8f, 8f, 0f), floatArrayOf(0f, 0f, 6f, 6f), hidden = setOf(2, 3), floorReach = 2.5f),
            figures = false,
            share = 0.5f,
        )

        /** No area is given where the walls were seen only partway, or one this near. */
        const val PARTWAY_ERROR = 0.1f

        /**
         * The rectangle's north wall never seen, two cupboards 2 m high standing against it, their
         * fronts 20 cm out of line and 3.2 m apart: no wall stands along them.
         */
        val CUPBOARDS = Case(
            "cupboards before a wall unseen",
            SyntheticRoom(
                RECTANGLE_X,
                RECTANGLE_Z,
                hidden = setOf(2),
                boxes = listOf(SyntheticBox(0.3f, 0.9f, 3.5f, 4f, 2f), SyntheticBox(4.1f, 4.7f, 3.3f, 4f, 2f)),
            ),
            figures = true,
            estimated = true,
        )

        /** The south and north walls alone, 4 m apart. */
        val FACING = Case("two facing walls", SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(1, 3)), figures = false)
        const val FACING_APART_M = 4f

        /** The L's 5 m west wall never seen: 17 m of its 22. */
        val L_PART = Case(
            "L three quarters",
            SyntheticRoom(floatArrayOf(0f, 6f, 6f, 3f, 3f, 0f), floatArrayOf(0f, 0f, 3f, 3f, 5f, 5f), hidden = setOf(5), seenUpTo = LOW_M),
            figures = true,
        )

        /** The T's west wall and its stem's end never seen: 21 m of its 26. */
        val T_PART = Case(
            "T three quarters",
            SyntheticRoom(
                floatArrayOf(0f, 7f, 7f, 4.5f, 4.5f, 2.5f, 2.5f, 0f),
                floatArrayOf(0f, 0f, 3f, 3f, 6f, 6f, 3f, 3f),
                hidden = setOf(4, 7),
                seenUpTo = LOW_M,
            ),
            figures = true,
        )

        /**
         * Walls seen only low by a camera that kept to the south half, the floor given up on 1.5 m
         * out: a wardrobe in the north-west corner and a dresser against the east wall hide the
         * corner and the walls behind them.
         */
        val CORNERS_ROOM = SyntheticRoom(
            RECTANGLE_X,
            RECTANGLE_Z,
            seenUpTo = LOW_M,
            floorReach = 1.5f,
            boxes = listOf(SyntheticBox(0f, 2.4f, 3.4f, 4f, 2.1f), SyntheticBox(4.4f, 5f, 1f, 3f, 1.9f)),
        )
        val CORNERS = Case(
            "furniture in a corner",
            CORNERS_ROOM,
            figures = true,
            walk = SyntheticScan.stops(CORNERS_ROOM).filter { it.z <= 2f },
        )

        /** A 4 × 3.5 m bedroom seen only up to 1 m: a double bed, its headboard 25 cm deep against the north wall. */
        val HEADBOARD = Case(
            "headboard",
            SyntheticRoom(
                floatArrayOf(0f, 4f, 4f, 0f),
                floatArrayOf(0f, 0f, 3.5f, 3.5f),
                seenUpTo = 1f,
                boxes = listOf(SyntheticBox(1.2f, 2.8f, 3.25f, 3.5f, 1.2f), SyntheticBox(1.3f, 2.7f, 1.25f, 3.25f, 0.5f)),
            ),
            figures = true,
            maxError = 0.01f,
        )

        /**
         * Round clutter [radius] round, [top] high, about [step] apart over [x0]..[x1] × [z0]..[z1]:
         * each thing moved off the grid by up to a quarter step, so that no row of them lines up.
         */
        fun posts(x0: Float, x1: Float, z0: Float, z1: Float, step: Float, radius: Float, top: Float): List<SyntheticPost> {
            val random = Random(CLUTTER_SEED)
            val posts = ArrayList<SyntheticPost>()
            var x = x0
            while (x <= x1 + 1e-3f) {
                var z = z0
                while (z <= z1 + 1e-3f) {
                    val dx = (random.nextFloat() - 0.5f) * step / 2
                    val dz = (random.nextFloat() - 0.5f) * step / 2
                    posts += SyntheticPost(x + dx, z + dz, radius, top)
                    z += step
                }
                x += step
            }
            return posts
        }

        /**
         * A 4 × 3.5 m bedroom seen only up to 1 m, its normals spread as a phone's are (12°), round
         * clutter 1.2 m high standing all over its middle: each wall makes under 3 % of the
         * vertical surfels, as the walls of a cluttered bedroom scanned with a phone did.
         */
        val CLUTTERED = Case(
            "cluttered bedroom",
            SyntheticRoom(
                floatArrayOf(0f, 4f, 4f, 0f),
                floatArrayOf(0f, 0f, 3.5f, 3.5f),
                seenUpTo = 1f,
                normalSpread = 12f,
                clutter = posts(0.6f, 3.4f, 0.6f, 2.9f, 0.27f, 0.12f, 1.2f),
            ),
            figures = true,
        )

        /**
         * The rectangle with one wall of four seen, and that one only along 1.5 m of its 5 m: a stub
         * on the floor's edge, under half the side it stands on — no plan, as `RoomSurveyTest` holds
         * on a clean cloud, the height still given.
         */
        val STRETCH = Case(
            "one wall seen on 1.5 m",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(1, 2, 3), seenAlong = mapOf(0 to 1.75f..3.25f)),
            figures = false,
        )

        /**
         * A 6 × 5.5 m garage seen floor to ceiling, storage units 0.5 m deep standing against two of
         * its walls, 2 m and 1.8 m high: their fronts face the camera, the walls are seen above them.
         */
        val GARAGE = Case(
            "garage",
            SyntheticRoom(
                floatArrayOf(0f, 6f, 6f, 0f),
                floatArrayOf(0f, 0f, 5.5f, 5.5f),
                height = 2.6f,
                boxes = listOf(SyntheticBox(0.4f, 5.4f, 0f, 0.5f, 2f), SyntheticBox(5.5f, 6f, 1f, 4.5f, 1.8f)),
            ),
            figures = true,
        )

        /**
         * The rectangle with two facing walls the depth never returned on, ARCore's planes found on
         * them: from the depth alone, two facing walls and the width between them.
         */
        val PLANES = Case(
            "two walls found only as planes",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, planeOnly = setOf(1, 3)),
            figures = true,
        )

        /** The rectangle's north side open from wall to wall onto the next room, its floor seen 1.6 m on. */
        val OPEN = Case(
            "open onto the next room",
            SyntheticRoom(
                RECTANGLE_X,
                RECTANGLE_Z,
                openings = listOf(SyntheticOpening(wall = 2, from = 0f, width = 5f, sill = 0f, height = 2.5f)),
                hidden = setOf(2),
                nextDoor = listOf(SyntheticFloor(0f, 5f, 4f, 5.6f)),
            ),
            figures = true,
        )

        /** [OPEN], the next room 9 m wide: its floor seen on either side past the walls' ends. */
        val OPEN_WIDE = Case(
            "open onto a wider room",
            SyntheticRoom(
                RECTANGLE_X,
                RECTANGLE_Z,
                openings = listOf(SyntheticOpening(wall = 2, from = 0f, width = 5f, sill = 0f, height = 2.5f)),
                hidden = setOf(2),
                nextDoor = listOf(SyntheticFloor(-2f, 7f, 4f, 5.6f)),
            ),
            figures = true,
        )

        /**
         * A 2 × 3.5 m room seen on two adjacent walls, the south one returned only in four shards
         * 0.15 to 0.45 m long, 1.25 m of its 2 m, the last 20 cm from the east wall: turned 19°
         * from each other, so in two families, and in three layers up to 6 cm in or out of line —
         * as a wall of a real scan was, no piece of it seen along 0.6 m. The layers are such that
         * no two shards of one family stand in line: two would make one line along 0.6 m.
         */
        val SHARDS = Case(
            "a wall in shards",
            SyntheticRoom(
                floatArrayOf(0f, 2f, 2f, 0f),
                floatArrayOf(0f, 0f, 3.5f, 3.5f),
                hidden = setOf(2, 3),
                shards = SyntheticShards(
                    wall = 0,
                    pieces = listOf(0.1f..0.55f, 0.7f..1f, 1.15f..1.3f, 1.45f..1.8f),
                    degrees = 19f,
                    layers = floatArrayOf(0.06f, -0.06f, 0f),
                ),
            ),
            figures = true,
            estimated = true,
        )

        /** No wall of [OPEN] is drawn further into the next room than this. */
        const val PAST_OPENING_M = 0.3f

        val CASES = listOf(
            LOW_WALLS, FLOOR_AHEAD, DOOR, TWICE, TWO_LOW, TWO, L_PART, T_PART, CORNERS, HEADBOARD, CLUTTERED, GARAGE,
            PLANES, OPEN, OPEN_WIDE, SHARDS,
        )

        /** Two facing walls of four, one drawn in two passes 15 cm apart, each seeing half of it. */
        val APART = Case(
            "one wall in two passes",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(1, 3), driftWall = 0, drift = 0.15f, driftHalves = true),
            figures = false,
        )

        /**
         * The scan stopped 2 m out from a camera that kept to the south of the room: the side
         * walls seen halfway, the floor seen a little further, the north wall never reached.
         */
        val STOPPED = Case(
            "stopped short",
            SyntheticRoom(RECTANGLE_X, RECTANGLE_Z, hidden = setOf(2), reach = 2f),
            figures = false,
            walk = listOf(Vec3(1.5f, 1.4f, 0.8f), Vec3(2.5f, 1.4f, 0.8f), Vec3(3.5f, 1.4f, 0.8f)),
        )

        /** How deep the wardrobe of [WARDROBE] stands out from its wall. */
        const val WARDROBE_DEPTH_M = 0.6f
        const val FRONT_NEAR_M = 0.15f

        /** A 3.5 × 3 m bedroom seen only up to 1 m, a 2 m wardrobe in a corner against its south wall. */
        val WARDROBE = Case(
            "wardrobe",
            SyntheticRoom(
                floatArrayOf(0f, 3.5f, 3.5f, 0f),
                floatArrayOf(0f, 0f, 3f, 3f),
                seenUpTo = 1f,
                boxes = listOf(SyntheticBox(0f, 2f, 0f, WARDROBE_DEPTH_M, 2f)),
            ),
            figures = true,
        )

        val captures = HashMap<String, SyntheticCapture>()
    }
}
