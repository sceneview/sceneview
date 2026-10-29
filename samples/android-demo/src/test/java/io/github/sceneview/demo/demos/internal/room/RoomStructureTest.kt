package io.github.sceneview.demo.demos.internal.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * The room engine on synthetic scans: depth frames rendered from a walk through a known room and
 * fused as on a phone ([SyntheticScan]). Each case's floor area must come within 1 % of the room's.
 */
class RoomStructureTest {

    private fun check(name: String, room: SyntheticRoom, walls: Int, deduced: Int = 0): RoomStructure {
        val structure = RoomStructure.read(capture(name, room).scan())
        val area = assertNotNull("$name: no area", structure.area).let { structure.area!! }
        val error = (area.value - room.area) / room.area
        println("RoomStructure $name: area ${"%.3f".format(area.value)} m² for ${room.area} (${"%+.2f".format(error * 100)} %), " +
            "${structure.walls.size} walls, ${structure.walls.count { it.deduced }} deduced, " +
            "height ${structure.ceilingHeight?.value}")
        assertTrue("$name: area ${area.value} for ${room.area}", abs(error) < MAX_ERROR)
        assertEquals("$name: walls", walls, structure.walls.size)
        assertEquals("$name: deduced walls", deduced, structure.walls.count { it.deduced })
        val perimeterError = abs(structure.perimeter!!.value - room.perimeter) / room.perimeter
        assertTrue("$name: perimeter ${structure.perimeter!!.value} for ${room.perimeter}", perimeterError < MAX_ERROR)
        val height = assertNotNull("$name: no height", structure.ceilingHeight).let { structure.ceilingHeight!!.value }
        assertEquals("$name: height", room.height, height, HEIGHT_M)
        return structure
    }

    @Test
    fun `a rectangle`() {
        check("rectangle", RECTANGLE, walls = 4)
    }

    @Test
    fun `an L`() {
        check("L", SyntheticRoom(floatArrayOf(0f, 6f, 6f, 3f, 3f, 0f), floatArrayOf(0f, 0f, 3f, 3f, 5f, 5f)), walls = 6)
    }

    @Test
    fun `a T`() {
        check(
            "T",
            SyntheticRoom(floatArrayOf(0f, 7f, 7f, 4.5f, 4.5f, 2.5f, 2.5f, 0f), floatArrayOf(0f, 0f, 3f, 3f, 6f, 6f, 3f, 3f)),
            walls = 8,
        )
    }

    @Test
    fun `a U`() {
        check(
            "U",
            SyntheticRoom(floatArrayOf(0f, 6f, 6f, 4f, 4f, 2f, 2f, 0f), floatArrayOf(0f, 0f, 5f, 5f, 2f, 2f, 5f, 5f)),
            walls = 8,
        )
    }

    @Test
    fun `an oblique wall stays oblique`() {
        val room = SyntheticRoom(floatArrayOf(0f, 5f, 5f, 3.5f, 0f), floatArrayOf(0f, 0f, 2.5f, 4f, 4f))
        val structure = check("oblique", room, walls = 5)
        val diagonal = room.wallLength(2)
        assertTrue(
            "the cut corner is a wall of its own",
            structure.walls.any { abs(it.length.value - diagonal) < LENGTH_M },
        )
    }

    @Test
    fun `a wall drawn twice 3 cm apart is one wall`() {
        check("doubled", SyntheticRoom(RECTANGLE.x, RECTANGLE.z, driftWall = 1, drift = 0.03f), walls = 4)
    }

    @Test
    fun `Gaussian depth noise`() {
        check("noise", SyntheticRoom(RECTANGLE.x, RECTANGLE.z, noise = 0.01f), walls = 4)
    }

    @Test
    fun `a wall never seen is deduced, not invented`() {
        val structure = check("unseen", SyntheticRoom(RECTANGLE.x, RECTANGLE.z, hidden = setOf(2)), walls = 4, deduced = 1)
        assertTrue("the area of a room missing a wall is an estimate", structure.area!!.estimate)
    }

    @Test
    fun `a tall wardrobe against a wall is not a wall`() {
        check(
            "furniture",
            SyntheticRoom(RECTANGLE.x, RECTANGLE.z, boxes = listOf(SyntheticBox(1f, 3f, 3.4f, 4f, 2f))),
            walls = 4,
        )
    }

    @Test
    fun `a door and a window are found with their sizes`() {
        val structure = check("openings", OPENINGS, walls = 4)
        val door = structure.openings.singleOrNull { it.kind == OpeningKind.Door }
        val window = structure.openings.singleOrNull { it.kind == OpeningKind.Window }
        println("RoomStructure openings: " + structure.openings.joinToString { "${it.kind} ${it.width}×${it.height} sill ${it.sill}" })
        assertNotNull("one door in ${structure.openings.map { it.kind }}", door)
        assertNotNull("one window in ${structure.openings.map { it.kind }}", window)
        assertEquals(0.9f, door!!.width, OPENING_M)
        assertEquals(2.05f, door.height, OPENING_M)
        assertEquals(0f, door.sill, OPENING_M)
        assertEquals(1.2f, window!!.width, OPENING_M)
        assertEquals(1.2f, window.height, OPENING_M)
        assertEquals(0.9f, window.sill, OPENING_M)
    }

    @Test
    fun `the structure mesh is closed, the openings cut out`() {
        for ((name, room) in listOf("rectangle" to RECTANGLE, "openings" to OPENINGS)) {
            val mesh = RoomStructure.read(capture(name, room).scan()).mesh()
            assertTrue("$name: a mesh", mesh.triangleCount > 0)
            assertEquals("$name: no edge shared by more than two faces", 2, mesh.maxEdgeUse())
            assertEquals("$name: no open edge off a hole", 0, mesh.leaks())
            assertTrue(mesh.watertight)
        }
    }

    @Test
    fun `one wall seen still draws the plan, with no figure`() {
        // Three walls out of four never seen: the plan is drawn, one wall alone measures nothing.
        val scan = capture("one wall", SyntheticRoom(RECTANGLE.x, RECTANGLE.z, hidden = setOf(1, 2, 3))).scan()
        val structure = RoomStructure.read(scan)
        println("RoomStructure one wall: ${structure.walls.size} walls, ${structure.seenWalls} seen, gaps ${structure.gaps}")
        assertTrue("walls ${structure.walls.size}", structure.walls.isNotEmpty())
        assertTrue("seen walls ${structure.seenWalls}", structure.seenWalls in 1 until RoomStructure.MIN_SEEN_WALLS)
        assertTrue("gaps ${structure.gaps}", RoomGap.MissingWall in structure.gaps)
        assertNull(structure.area)
        assertNull(structure.perimeter)
        assertNull(structure.volume)
        assertNull(structure.length)
        assertNull(structure.width)
        assertEquals("height kept", RECTANGLE.height, assertNotNull(structure.ceilingHeight).let { structure.ceilingHeight!!.value }, HEIGHT_M)
        val live = RoomStructure.live(scan)
        assertTrue("live walls", live.walls.isNotEmpty())
        assertTrue("live outline", live.outline.size >= 6)
        assertNull("no live area", live.area)
    }

    @Test
    fun `a wall 3 degrees off square stays where it is`() {
        tilted("tilt 3", 3f)
    }

    @Test
    fun `a wall 5 degrees off square stays where it is`() {
        tilted("tilt 5", 5f)
    }

    /** The rectangle with its second wall turned by [degrees]: the outline must follow it, not square it. */
    private fun tilted(name: String, degrees: Float) {
        val lean = RECTANGLE.z[2] * tan(Math.toRadians(degrees.toDouble())).toFloat()
        val room = SyntheticRoom(floatArrayOf(0f, 5f, 5f + lean, 0f), RECTANGLE.z)
        val structure = check(name, room, walls = 4)
        val want = atan2(room.z[2] - room.z[1], room.x[2] - room.x[1])
        val turns = structure.walls.map { atan2(it.bz - it.az, it.bx - it.ax) }
        println("RoomStructure $name: walls at ${turns.map { "%.1f°".format(Math.toDegrees(it.toDouble())) }}")
        assertTrue(
            "$name: a wall at ${Math.toDegrees(want.toDouble())}° in ${turns.map { Math.toDegrees(it.toDouble()) }}",
            turns.any { abs(Math.toDegrees(abs(it - want).toDouble())) < TURN_DEG },
        )
    }

    @Test
    fun `stray and broken surfels change nothing`() {
        val clean = capture("rectangle", RECTANGLE).scan()
        for (trail in listOf(clean.trail, FloatArray(0))) {
            val base = RoomScan(clean.positions, clean.normals, trail, clean.planes)
            val dirty = RoomScan(
                clean.positions + floatArrayOf(Float.NaN, 1f, 1f, 2f, 1e7f, 2f, 1f, 1f, 1f),
                clean.normals!! + floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, Float.NaN, 0f, 0f),
                if (trail.isEmpty()) trail else trail + floatArrayOf(Float.NaN, 1f, 1f),
                clean.planes + listOf(
                    RoomPlane(RoomPlaneKind.Wall, floatArrayOf(0f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 2f, 0f)),
                    RoomPlane(RoomPlaneKind.Floor, floatArrayOf(0f, 0f, 0f, 1e7f, 0f, 0f, 0f, 0f, 1e7f)),
                ),
            )
            val want = RoomStructure.read(base)
            val got = RoomStructure.read(dirty)
            val what = if (trail.isEmpty()) "no trail" else "trail"
            assertEquals("$what: area", want.area?.value, got.area?.value)
            assertEquals("$what: walls", want.walls.size, got.walls.size)
            assertEquals("$what: floor", want.floorY, got.floorY)
            val wantLive = RoomStructure.live(base)
            val gotLive = RoomStructure.live(dirty)
            assertEquals("$what: live walls", wantLive.walls.size, gotLive.walls.size)
            assertEquals("$what: live area", wantLive.area!!.value, gotLive.area!!.value, wantLive.area!!.value * MAX_ERROR)
        }
    }

    @Test
    fun `nothing but strays reads as no depth`() {
        val n = RoomLevels.MIN_SURFELS * 3
        val structure = RoomStructure.read(RoomScan(FloatArray(n * 3) { Float.NaN }, FloatArray(n * 3) { 0f }))
        assertEquals(RoomGap.NoDepth, structure.gaps.first())
        assertTrue(structure.walls.isEmpty())
    }

    @Test
    fun `the whole room reads in well under a second`() {
        val scan = capture("openings", OPENINGS).scan()
        repeat(FULL_WARMUP) { RoomStructure.read(scan) }
        val millis = (0 until RUNS).map {
            val start = System.nanoTime()
            RoomStructure.read(scan)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        println("RoomStructure full read: median ${"%.1f".format(millis[RUNS / 2])} ms, max ${"%.1f".format(millis.last())} ms, ${scan.positions.size / 3} points")
        assertTrue("median ${millis[RUNS / 2]} ms", millis[RUNS / 2] < FULL_BUDGET_MS)
    }

    @Test
    fun `the live reading sees the walls of a scan in progress, in milliseconds`() {
        val partial = SyntheticScan.capture(RECTANGLE, share = 0.4f)
        val scan = partial.scan()
        repeat(WARMUP) { RoomStructure.live(scan) }
        val millis = (0 until RUNS).map {
            val start = System.nanoTime()
            RoomStructure.live(scan)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        val live = RoomStructure.live(scan)
        println("RoomStructure live read: best ${"%.2f".format(millis.first())} ms, median ${"%.2f".format(millis[RUNS / 2])} ms, " +
            "max ${"%.2f".format(millis.last())} ms, ${scan.positions.size / 3} points, ${live.walls.size} walls, area ${live.area}")
        assertTrue("best ${millis.first()} ms", millis.first() < LIVE_BUDGET_MS)
        assertTrue("walls seen", live.walls.isNotEmpty())
        assertTrue("an outline", live.outline.size >= 6)
        for (wall in live.walls) {
            assertTrue(wall.height > 1f)
            assertTrue(wall.confidence in 0f..1f)
        }
        liveArea("40 %", live, RECTANGLE)
    }

    @Test
    fun `the live reading does without ARCore's planes`() {
        val scan = SyntheticScan.capture(RECTANGLE, share = 0.4f).scan()
        liveArea("no planes", RoomStructure.live(RoomScan(scan.positions, scan.normals, scan.trail)), RECTANGLE)
    }

    @Test
    fun `the live reading copes with a cropped, shifted floor plane`() {
        val scan = SyntheticScan.capture(RECTANGLE, share = 0.4f).scan()
        val random = java.util.Random(3L)
        val planes = scan.planes.map { plane ->
            if (plane.kind != RoomPlaneKind.Floor) return@map plane
            // ARCore's floor: two thirds of the room, 2 cm off the floor, its corners off by up to 10 cm.
            val p = plane.polygon
            val m = p.size / 3
            val cx = (0 until m).map { p[it * 3] }.average().toFloat()
            val cz = (0 until m).map { p[it * 3 + 2] }.average().toFloat()
            RoomPlane(
                RoomPlaneKind.Floor,
                FloatArray(p.size) { k ->
                    val jitter = (random.nextFloat() - 0.5f) * 0.2f
                    when (k % 3) {
                        0 -> cx + (p[k] - cx) * CROP + jitter
                        1 -> p[k] + 0.02f
                        else -> cz + (p[k] - cz) * CROP + jitter
                    }
                },
            )
        }
        liveArea("cropped floor", RoomStructure.live(RoomScan(scan.positions, scan.normals, scan.trail, planes)), RECTANGLE)
    }

    /** A live area, when given, is within [LIVE_AREA_ERROR] of the room's: an estimate, not a measure. */
    private fun liveArea(name: String, live: LiveRoom, room: SyntheticRoom) {
        val area = live.area?.value
        println("RoomStructure live $name: area $area for ${room.area}, ${live.walls.size} walls")
        assertNotNull("$name: an area", area)
        val error = abs(area!! - room.area) / room.area
        assertTrue("$name: area $area for ${room.area} (${"%.1f".format(error * 100)} %)", error < LIVE_AREA_ERROR)
    }

    @Test
    fun `two live readings at once on the same scan agree`() {
        val scan = capture("rectangle", RECTANGLE).scan()
        val want = RoomStructure.live(scan)
        val start = java.util.concurrent.CountDownLatch(1)
        val results = arrayOfNulls<LiveRoom>(THREADS * ROUNDS)
        val threads = (0 until THREADS).map { t ->
            Thread {
                start.await()
                repeat(ROUNDS) { results[t * ROUNDS + it] = RoomStructure.live(scan) }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        for (got in results) {
            assertNotNull(got)
            assertEquals(want.area, got!!.area)
            assertEquals(want.walls.size, got.walls.size)
            assertTrue(want.outline.contentEquals(got.outline))
        }
    }

    companion object {
        private const val MAX_ERROR = 0.01f
        private const val HEIGHT_M = 0.05f
        private const val LENGTH_M = 0.05f
        private const val OPENING_M = 0.05f
        private const val WARMUP = 100
        private const val RUNS = 21
        private const val FULL_BUDGET_MS = 1000.0
        private const val FULL_WARMUP = 10

        /**
         * The live reading's ceiling: its best run of [RUNS], so a shared runner's stalls do not count, its CPU does.
         * A laptop JVM reads in 2.5 ms, a GitHub-hosted runner in 8.7 ms: this catches a complexity regression, not a
         * phone's frame budget, which wants a device.
         */
        private const val LIVE_BUDGET_MS = 20.0

        /** A live area is read on a 10 cm grid: within this share of the room's (0.0 % measured here). */
        private const val LIVE_AREA_ERROR = 0.02f
        private const val TURN_DEG = 1.0
        private const val CROP = 0.8f
        private const val THREADS = 4
        private const val ROUNDS = 5

        val RECTANGLE = SyntheticRoom(floatArrayOf(0f, 5f, 5f, 0f), floatArrayOf(0f, 0f, 4f, 4f))
        val OPENINGS = SyntheticRoom(
            RECTANGLE.x,
            RECTANGLE.z,
            openings = listOf(
                SyntheticOpening(wall = 0, from = 1f, width = 0.9f, sill = 0f, height = 2.05f),
                SyntheticOpening(wall = 1, from = 1.4f, width = 1.2f, sill = 0.9f, height = 1.2f),
            ),
        )

        private val captures = HashMap<String, SyntheticCapture>()

        /** Each room is rendered and fused once per run. */
        @Synchronized
        fun capture(name: String, room: SyntheticRoom): SyntheticCapture = captures.getOrPut(name) { SyntheticScan.capture(room) }
    }
}
