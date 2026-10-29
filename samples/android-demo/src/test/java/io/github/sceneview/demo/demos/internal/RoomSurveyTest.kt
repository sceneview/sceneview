package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pins the room survey on synthetic rooms (#4540): walls read where the surfels stand, never on
 * the extent of what was seen; a floor that is the densest layer and not the lowest one; no
 * figure for what the scan did not see; the same cloud read twice, the same figures.
 */
class RoomSurveyTest {

    /**
     * A cloud of surfels built wall by wall, in a room turned [yaw] and moved off the origin.
     * [noise] moves every surfel by up to that much on each axis, from a fixed sequence.
     */
    private class Room(
        private val yaw: Float = 0f,
        private val noise: Float = 0f,
        private val ox: Float = 0.37f,
        private val oz: Float = -1.23f,
    ) {
        private val positions = ArrayList<Float>()
        private val normals = ArrayList<Float>()
        private val trail = ArrayList<Float>()
        private var seed = 12_345L

        /** The next of a fixed sequence in -[noise]..[noise]: the same room is the same cloud, every run. */
        private fun jitter(): Float {
            if (noise == 0f) return 0f
            seed = (seed * 1_103_515_245L + 12_345L) and 0x7fffffffL
            return (seed / 0x7fffffffL.toFloat() * 2 - 1) * noise
        }

        private fun add(a0: Float, y0: Float, b0: Float, na: Float, ny: Float, nb: Float) {
            val c = cos(yaw)
            val s = sin(yaw)
            val a = a0 + jitter()
            val y = y0 + jitter()
            val b = b0 + jitter()
            positions += ox + a * c - b * s
            positions += y
            positions += oz + a * s + b * c
            normals += na * c - nb * s
            normals += ny
            normals += na * s + nb * c
        }

        /** A horizontal layer over [a0]..[a1] × [b0]..[b1] at [y], [thickness] thick, facing up or down. */
        fun slab(
            a0: Float,
            a1: Float,
            b0: Float,
            b1: Float,
            y: Float,
            thickness: Float = 0f,
            up: Boolean = true,
        ) = apply {
            var i = 0
            var a = a0 + STEP / 2
            while (a < a1) {
                var j = 0
                var b = b0 + STEP / 2
                while (b < b1) {
                    // A fixed pattern spreads the layer over its thickness: no random source.
                    val spread = if (thickness == 0f) 0f else ((i * 7 + j * 13) % 11 - 5) / 10f * thickness
                    add(a, y + spread, b, 0f, if (up) 1f else -1f, 0f)
                    b += STEP
                    j++
                }
                a += STEP
                i++
            }
        }

        /** A wall standing on `a = at`, from [b0] to [b1], facing [facing] along a, floor to [top]. */
        fun wallAcross(
            at: Float,
            b0: Float,
            b1: Float,
            facing: Float,
            top: Float = HEIGHT,
            bottom: Float = 0f,
        ) = apply {
            var b = b0 + STEP / 2
            while (b < b1) {
                var y = bottom + STEP / 2
                while (y < top) {
                    add(at, y, b, facing, 0f, 0f)
                    y += RISE
                }
                b += STEP
            }
        }

        /** A wall standing on `b = at`, from [a0] to [a1], facing [facing] along b. */
        fun wallAlong(at: Float, a0: Float, a1: Float, facing: Float, top: Float = HEIGHT, bottom: Float = 0f) = apply {
            var a = a0 + STEP / 2
            while (a < a1) {
                var y = bottom + STEP / 2
                while (y < top) {
                    add(a, y, at, 0f, 0f, facing)
                    y += RISE
                }
                a += STEP
            }
        }

        /** A wall standing from ([a0], [b0]) to ([a1], [b1]), facing the left of that walk: square to no axis. */
        fun wallBetween(a0: Float, b0: Float, a1: Float, b1: Float) = apply {
            val length = hypot(a1 - a0, b1 - b0)
            val na = (b1 - b0) / length
            val nb = -(a1 - a0) / length
            var t = STEP / 2
            while (t < length) {
                var y = STEP / 2
                while (y < HEIGHT) {
                    add(a0 + (a1 - a0) * t / length, y, b0 + (b1 - b0) * t / length, na, 0f, nb)
                    y += RISE
                }
                t += STEP
            }
        }

        /** The camera walking a loop around ([a], [b]). */
        fun camera(a: Float, b: Float, radius: Float = 0.7f) = apply {
            val c = cos(yaw)
            val s = sin(yaw)
            for (k in 0 until 40) {
                val t = k / 40f * 2 * Math.PI.toFloat()
                val pa = a + radius * cos(t)
                val pb = b + radius * sin(t)
                trail += ox + pa * c - pb * s
                trail += 1.4f
                trail += oz + pa * s + pb * c
            }
        }

        /** The four walls, floor and ceiling of a [length] × [width] room, each optional. */
        @Suppress("LongParameterList")
        fun box(
            length: Float = 5f,
            width: Float = 4f,
            floorThickness: Float = 0f,
            ceiling: Boolean = true,
            west: Boolean = true,
            east: Boolean = true,
            camera: Boolean = true,
        ) = apply {
            slab(0f, length, 0f, width, 0f, floorThickness)
            if (ceiling) slab(0f, length, 0f, width, HEIGHT, up = false)
            if (west) wallAcross(0f, 0f, width, 1f)
            if (east) wallAcross(length, 0f, width, -1f)
            wallAlong(0f, 0f, length, 1f)
            wallAlong(width, 0f, length, -1f)
            if (camera) camera(length / 2, width / 2)
        }

        /** The cloud; [reversed] hands the same surfels over last to first. */
        fun input(reversed: Boolean = false): RoomSurveyInput {
            val path = trail.toFloatArray()
            if (!reversed) return RoomSurveyInput(positions.toFloatArray(), normals.toFloatArray(), path)
            val n = positions.size / 3
            fun turned(from: List<Float>) = FloatArray(n * 3) { from[(n - 1 - it / 3) * 3 + it % 3] }
            return RoomSurveyInput(turned(positions), turned(normals), trail.toFloatArray())
        }

        fun survey() = RoomSurveyor.survey(input())
    }

    private fun assertNear(expected: Float, actual: Float?, tolerance: Float = 0.02f) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, tolerance)
    }

    @Test
    fun `a rectangular room reads its four walls, area, perimeter and height`() {
        val survey = Room().box().survey()

        assertEquals(listOf("A", "B", "C", "D"), survey.walls.map { it.tag })
        assertEquals(listOf(5f, 4f, 5f, 4f), survey.walls.map { Math.round(it.length.value * 10) / 10f })
        assertTrue(survey.walls.all { it.seen && !it.length.estimate })
        assertNear(5f, survey.length?.value)
        assertNear(4f, survey.width?.value)
        assertNear(20f, survey.area?.value, AREA)
        assertNear(18f, survey.perimeter?.value, 0.2f)
        assertNear(2.5f, survey.ceilingHeight?.value)
        assertTrue(survey.gaps.isEmpty())
        assertFalse(survey.hasEstimates)
    }

    @Test
    fun `a camera trail whose grid exceeds the memory budget is not measured`() {
        val room = Room().box().input()
        val trail = floatArrayOf(
            room.trail[0], room.trail[1], room.trail[2],
            room.trail[0] + 40f, room.trail[1], room.trail[2] + 40f,
        )

        val survey = RoomSurveyor.survey(RoomSurveyInput(room.positions, room.normals, trail))

        assertFalse(survey.hasPlan)
        assertNull(survey.area)
        assertEquals(SurveyGap.NoWalls, survey.gaps.first())
    }

    @Test
    fun `the outline starts top-left and walks clockwise on the page`() {
        val walls = Room().box().survey().walls

        assertEquals(0f, walls[0].ax, 0.06f)
        assertEquals(0f, walls[0].ay, 0.06f)
        assertEquals(5f, walls[0].bx, 0.06f)
        assertTrue(walls[0].horizontal)
        assertEquals(4f, walls[1].by, 0.06f)
        assertFalse(walls[1].horizontal)
        // Each wall starts where the one before it ends.
        for (i in walls.indices) {
            val next = walls[(i + 1) % walls.size]
            assertEquals(walls[i].bx, next.ax, 1e-4f)
            assertEquals(walls[i].by, next.ay, 1e-4f)
        }
    }

    @Test
    fun `a room turned on the scan's axes reads the same figures`() {
        val survey = Room(yaw = 0.52f).box().survey()

        assertNear(5f, survey.length?.value)
        assertNear(4f, survey.width?.value)
        assertNear(20f, survey.area?.value, AREA)
        assertEquals(4, survey.walls.size)
    }

    @Test
    fun `a thick floor and a wall drawn twice are one floor and one wall, with their spread`() {
        // The floor's layer is 20 cm thick; the west wall is there twice, 22 cm apart.
        val survey = Room().box(floorThickness = 0.2f).wallAcross(-0.22f, 0f, 4f, 1f).survey()

        assertEquals(0f, survey.floorY, 0.03f)
        assertEquals(0.2f, survey.floorThickness, 0.06f)
        assertEquals(4, survey.walls.size)
        // The doubled wall stands between its two layers, and says so.
        val length = survey.length!!
        assertEquals(5.11f, length.value, 0.05f)
        assertTrue("± ${length.plusMinus}", length.plusMinus in 0.1f..0.2f)
        // The clean pair of walls is read tight.
        assertTrue(survey.width!!.plusMinus < 0.06f)
        assertNear(2.5f, survey.ceilingHeight?.value)
        assertTrue(survey.ceilingHeight!!.plusMinus >= 0.08f)
    }

    @Test
    fun `the survey stays on the walls when planes and stray surfaces overflow them`() {
        // A terrace seen through a doorway in the east wall, and a far façade beyond it.
        val room = Room()
            .slab(0f, 5f, 0f, 4f, 0f)
            .slab(0f, 5f, 0f, 4f, HEIGHT, up = false)
            .wallAcross(0f, 0f, 4f, 1f)
            .wallAcross(5f, 0f, 1.5f, -1f)
            .wallAcross(5f, 2.4f, 4f, -1f)
            .wallAlong(0f, 0f, 5f, 1f)
            .wallAlong(4f, 0f, 5f, -1f)
            .slab(5f, 8.5f, 0.5f, 3.5f, 0f)
            .wallAcross(8.5f, 0f, 4f, -1f, top = 1.1f)
            .camera(2.5f, 2f)
        val survey = room.survey()

        assertNear(20f, survey.area?.value, AREA)
        assertNear(5f, survey.length?.value)
        assertNear(4f, survey.width?.value)

        // The same room as ARCore's planes give it: the floor plane runs out onto the terrace.
        val floor = DebugPlane(
            1,
            DebugPlaneKind.Floor,
            floatArrayOf(0.37f, 0f, -1.23f, 8.87f, 0f, -1.23f, 8.87f, 0f, 2.77f, 0.37f, 0f, 2.77f),
        )
        val rectangle = RoomMeasure.of(listOf(floor), 0f)!!
        assertEquals(1.7f, rectangle.area / survey.area!!.value, 0.05f)
    }

    @Test
    fun `a patch 40 cm under the floor is not the floor`() {
        val survey = Room().box(floorThickness = 0.2f).slab(1f, 2.6f, 1f, 2.6f, -0.4f).survey()

        assertEquals(0f, survey.floorY, 0.03f)
        assertNear(2.5f, survey.ceilingHeight?.value)
    }

    @Test
    fun `three walls give a plan and an estimated area`() {
        val survey = Room().box(east = false).survey()

        // The missing wall is deduced where the floor was seen to end: the figures it bounds are estimates.
        assertNear(20f, survey.area?.value, 3 * AREA)
        assertTrue(survey.area!!.estimate)
        assertTrue(survey.perimeter!!.estimate)
        assertTrue(survey.length!!.estimate)
        // The pair that was seen is measured.
        assertNear(4f, survey.width?.value)
        assertTrue(SurveyGap.MissingWall in survey.gaps)
        assertTrue(survey.hasPlan)
        val unseen = survey.walls.filter { !it.seen }
        assertEquals(1, unseen.size)
        assertTrue(unseen[0].length.estimate)
        assertEquals(0f, unseen[0].seenShare, 0f)
        // The walls that run up to the missing one end where the floor was seen to end: estimates.
        assertEquals(2, survey.walls.count { it.seen && it.length.estimate })
        assertTrue(survey.hasEstimates)
    }

    @Test
    fun `two walls give no area either`() {
        val survey = Room().box(east = false, west = false).survey()

        assertNull(survey.area)
        assertNull(survey.length)
        assertNear(4f, survey.width?.value)
        assertTrue(SurveyGap.MissingWall in survey.gaps)
    }

    @Test
    fun `two adjacent walls, in feet and inches, give an area and wall lengths marked as estimates`() {
        // The west and south walls of a 5 × 4 m room, seen up to the corner they make; the floor seen to its far corner.
        val survey = Room()
            .slab(0f, 5f, 0f, 4f, 0f)
            .slab(0f, 5f, 0f, 4f, HEIGHT, up = false)
            .wallAcross(0f, 0f, 4f, 1f)
            .wallAlong(0f, 0f, 5f, 1f)
            .camera(2.5f, 2f)
            .survey()
        val wording = imperialWording()
        val rows = SurveyReport.rows(survey, wording).map { it.value }
        val walls = SurveyReport.wallRows(survey, wording).map { it.value }
        val plan = SurveyReport.planTexts(survey, wording)
        val metric = Regex("""\d m\b|m²|m³""")

        assertTrue(survey.area!!.estimate)
        assertNear(20f, survey.area?.value, 20f * 0.03f)
        assertTrue(rows[0], rows[0].startsWith(SurveyFormat.ESTIMATE) && rows[0].endsWith("ft²"))
        assertTrue(plan.area.toString(), plan.area!!.startsWith(SurveyFormat.ESTIMATE) && plan.area!!.endsWith("ft²"))
        assertEquals(survey.walls.size, walls.size)
        // The walls bound an estimated area, so each length is given, as an estimate, in feet and inches:
        // as a tape reads it in the wall table, as a dimension line writes it on the plan.
        val marks = Regex("\\d+' \\d+\"")
        assertTrue(survey.walls.toString(), survey.walls.all { it.sized && it.length.estimate })
        assertTrue(walls.toString(), walls.all { it.startsWith(SurveyFormat.ESTIMATE) && " ft " in it })
        assertTrue(
            plan.walls.toString(),
            plan.walls.all { it.startsWith(SurveyFormat.ESTIMATE) && marks.matches(it.removePrefix(SurveyFormat.ESTIMATE)) },
        )
        assertTrue((rows + walls + plan.walls).toString(), (rows + walls + plan.walls).none { metric.containsMatchIn(it) })
    }

    @Test
    fun `a plan its walls do not close gives no wall a guessed length`() {
        val survey = Room().box(east = false, west = false).survey()
        val labels = SurveyReport.planTexts(survey, RoomPlanLayoutTest.WORDING).walls
        val rows = SurveyReport.wallRows(survey, RoomPlanLayoutTest.WORDING).map { it.value }

        assertNull(survey.area)
        assertEquals(survey.walls.size, rows.size)
        assertTrue(labels.toString(), labels.none { it.contains(SurveyFormat.ESTIMATE) })
        assertTrue(rows.toString(), rows.none { it.contains(SurveyFormat.ESTIMATE) })
    }

    @Test
    fun `no ceiling seen, no height`() {
        val survey = Room().box(ceiling = false).survey()

        assertNull(survey.ceilingHeight)
        assertEquals(listOf(SurveyGap.Ceiling), survey.gaps)
        assertNear(20f, survey.area?.value, AREA)
    }

    @Test
    fun `a corner of ceiling is not a height`() {
        // 0.8 x 0.8 m of ceiling: under the area a height is read from.
        val room = Room().box(ceiling = false).slab(0f, 0.8f, 0f, 0.8f, HEIGHT, up = false)

        assertNull(room.survey().ceilingHeight)
    }

    @Test
    fun `a wall seen on less than half its length is dashed and its length an estimate`() {
        // 1.8 m of the 4 m east wall.
        val survey = Room().box(east = false).wallAcross(5f, 0f, 1.8f, -1f).survey()

        val east = survey.walls.single { !it.horizontal && it.ax > 2.5f }
        assertEquals(0.45f, east.seenShare, 0.05f)
        assertFalse(east.seen)
        assertTrue(east.length.estimate)
        assertTrue(survey.area!!.estimate)
        assertTrue(RoomSurveyor.MIN_SEEN_SHARE > east.seenShare)
        // The two walls that end on it end on an estimate: their lengths are estimates too.
        val ending = survey.walls.filter { it.horizontal }
        assertEquals(2, ending.size)
        assertTrue(ending.all { it.seen && it.length.estimate })
        // The wall across the room ends on two walls that were seen: measured.
        assertFalse(survey.walls.single { !it.horizontal && it.ax < 2.5f }.length.estimate)
        // An estimate carries no spread: it was not measured, so it has no scatter to give.
        assertTrue(survey.walls.filter { it.length.estimate }.all { it.length.plusMinus == 0f })
    }

    @Test
    fun `a wall seen on most of its length is measured, and its gap is known`() {
        // The east wall but for 1 m of it.
        val survey = Room().box(east = false).wallAcross(5f, 0f, 2f, -1f).wallAcross(5f, 3f, 4f, -1f).survey()

        val east = survey.walls.single { !it.horizontal && it.ax > 2.5f }
        assertEquals(0.75f, east.seenShare, 0.05f)
        assertTrue(east.seen)
        assertEquals(1, east.unseen.size)
        assertEquals(2f, east.unseen[0].start, 0.1f)
        assertEquals(3f, east.unseen[0].endInclusive, 0.1f)
        assertNear(20f, survey.area?.value, AREA)
    }

    @Test
    fun `furniture is not a wall`() {
        // A sideboard 0.9 m high along the room's middle.
        val survey = Room().box()
            .wallAcross(2f, 1f, 3f, 1f, top = 0.9f)
            .wallAcross(1.6f, 1f, 3f, -1f, top = 0.9f)
            .survey()

        assertEquals(4, survey.walls.size)
        assertNear(20f, survey.area?.value, AREA)
    }

    @Test
    fun `an L-shaped room reads its six walls`() {
        // 5 x 4 m with a 2 x 1.5 m corner taken out of it.
        val room = Room()
            .slab(0f, 5f, 0f, 2.5f, 0f)
            .slab(0f, 3f, 2.5f, 4f, 0f)
            .wallAlong(0f, 0f, 5f, 1f)
            .wallAcross(5f, 0f, 2.5f, -1f)
            .wallAlong(2.5f, 3f, 5f, -1f)
            .wallAcross(3f, 2.5f, 4f, -1f)
            .wallAlong(4f, 0f, 3f, -1f)
            .wallAcross(0f, 0f, 4f, 1f)
            .camera(2f, 1.6f)
        val survey = room.survey()

        assertEquals(6, survey.walls.size)
        assertNear(17f, survey.area?.value, AREA)
        assertNear(18f, survey.perimeter?.value, 0.3f)
        assertEquals(listOf(5f, 2.5f, 2f, 1.5f, 3f, 4f), survey.walls.map { Math.round(it.length.value * 10) / 10f })
    }

    @Test
    fun `the same room built twice gives the same figures, whatever the order of its surfels`() {
        fun room() = Room(yaw = 0.3f, noise = 0.03f).box(floorThickness = 0.2f).wallAcross(-0.22f, 0f, 4f, 1f)
        val first = room().survey()
        val second = room().survey()

        assertEquals(first.area, second.area)
        assertEquals(first.perimeter, second.perimeter)
        assertEquals(first.ceilingHeight, second.ceilingHeight)
        assertEquals(first.floorY, second.floorY, 0f)
        assertEquals(first.yaw, second.yaw, 0f)
        assertEquals(first.walls.map { it.length }, second.walls.map { it.length })
        assertEquals(first.walls.map { it.seenShare }, second.walls.map { it.seenShare })

        // Handed over last to first, the sums run the other way: the figures hold to a tenth of a millimetre.
        val turned = RoomSurveyor.survey(room().input(reversed = true))
        assertEquals(first.walls.size, turned.walls.size)
        assertEquals(first.area!!.value, turned.area!!.value, 1e-3f)
        assertEquals(first.ceilingHeight!!.value, turned.ceilingHeight!!.value, 1e-4f)
        for (i in first.walls.indices) {
            assertEquals(first.walls[i].length.value, turned.walls[i].length.value, 1e-4f)
            assertEquals(first.walls[i].length.plusMinus, turned.walls[i].length.plusMinus, 1e-4f)
            assertEquals(first.walls[i].seen, turned.walls[i].seen)
        }
    }

    @Test
    fun `a noisy cloud reads the same room, with the noise in its spread`() {
        val survey = Room(noise = 0.03f).box().survey()

        assertEquals(4, survey.walls.size)
        assertTrue(survey.walls.all { it.seen && !it.length.estimate })
        assertNear(5f, survey.length?.value, 0.03f)
        assertNear(4f, survey.width?.value, 0.03f)
        assertNear(20f, survey.area?.value, 0.25f)
        assertNear(2.5f, survey.ceilingHeight?.value, 0.03f)
        assertTrue(survey.gaps.isEmpty())
        val spread = survey.length!!.plusMinus
        assertTrue("± $spread", spread >= 2 * RoomSurveyor.MIN_SPREAD_M && spread <= 0.1f)
        assertFalse(survey.ceilingHeight!!.estimate)
    }

    @Test
    fun `a tall wardrobe along a wall is furniture when the wall is seen over it`() {
        // 3 m of the 4 m east wall stand behind a wardrobe 2 m high and 0.6 m deep: the scan saw
        // its front, its two ends, and the wall above it.
        val survey = Room()
            .slab(0f, 4.4f, 0f, 4f, 0f)
            .slab(4.4f, 5f, 0f, 0.5f, 0f)
            .slab(4.4f, 5f, 3.5f, 4f, 0f)
            .slab(0f, 5f, 0f, 4f, HEIGHT, up = false)
            .wallAcross(0f, 0f, 4f, 1f)
            .wallAcross(5f, 0f, 0.5f, -1f)
            .wallAcross(5f, 3.5f, 4f, -1f)
            .wallAcross(5f, 0.5f, 3.5f, -1f, bottom = 2f)
            .wallAlong(0f, 0f, 5f, 1f)
            .wallAlong(4f, 0f, 5f, -1f)
            .wallAcross(4.4f, 0.5f, 3.5f, -1f, top = 2f)
            .wallAlong(0.5f, 4.4f, 5f, -1f, top = 2f)
            .wallAlong(3.5f, 4.4f, 5f, 1f, top = 2f)
            .camera(2.5f, 2f)
            .survey()

        assertEquals(4, survey.walls.size)
        assertNear(5f, survey.length?.value)
        assertNear(20f, survey.area?.value, AREA)
        val east = survey.walls.single { !it.horizontal && it.ax > 2.5f }
        assertTrue(east.seen)
        assertEquals(1f, east.seenShare, 0.05f)
        assertFalse(survey.hasEstimates)
    }

    @Test
    fun `a bed seen more than the floor around it is not the floor, and the height is an estimate`() {
        // The floor shows everywhere but under the bed; the bed's top, 0.5 m up, was scanned so
        // closely that it holds more surfels than the whole floor.
        val room = Room().box()
        repeat(9) { room.slab(1f, 3f, 2f, 3.5f, 0.5f + it * 0.002f) }
        val survey = room.survey()

        assertEquals(0f, survey.floorY, 0.03f)
        val height = survey.ceilingHeight!!
        assertNear(2.5f, height.value, 0.03f)
        // Two layers could be the floor: the height is given from the lower one, marked.
        assertTrue(height.estimate)
        assertEquals(0f, height.plusMinus, 0f)
        assertTrue(survey.hasEstimates)
        assertNear(20f, survey.area?.value, AREA)
    }

    @Test
    fun `a ceiling on two levels gives the height of the larger one, as an estimate`() {
        val survey = Room().box(ceiling = false)
            .slab(0f, 3f, 0f, 4f, HEIGHT, up = false)
            .slab(3f, 5f, 0f, 4f, HEIGHT + 0.4f, up = false)
            .survey()

        val height = survey.ceilingHeight!!
        assertNear(2.5f, height.value)
        assertTrue(height.estimate)
        assertEquals(0f, height.plusMinus, 0f)
        assertTrue(survey.hasEstimates)
        // One level over the whole room is a measured height.
        assertFalse(Room().box().survey().ceilingHeight!!.estimate)
    }

    @Test
    fun `a T-shaped room reads its eight walls`() {
        // A 6 x 2 m bar with a 2 x 3 m stem under its middle.
        val survey = Room()
            .slab(0f, 6f, 0f, 2f, 0f)
            .slab(2f, 4f, 2f, 5f, 0f)
            .slab(0f, 6f, 0f, 2f, HEIGHT, up = false)
            .slab(2f, 4f, 2f, 5f, HEIGHT, up = false)
            .wallAlong(0f, 0f, 6f, 1f)
            .wallAcross(6f, 0f, 2f, -1f)
            .wallAlong(2f, 4f, 6f, -1f)
            .wallAcross(4f, 2f, 5f, -1f)
            .wallAlong(5f, 2f, 4f, -1f)
            .wallAcross(2f, 2f, 5f, 1f)
            .wallAlong(2f, 0f, 2f, -1f)
            .wallAcross(0f, 0f, 2f, 1f)
            .camera(3f, 1.5f)
            .survey()

        assertEquals(8, survey.walls.size)
        assertEquals(
            listOf(2f, 2f, 2f, 2f, 2f, 3f, 3f, 6f),
            survey.walls.map { Math.round(it.length.value * 100) / 100f }.sorted(),
        )
        assertTrue(survey.walls.all { it.seen && !it.length.estimate })
        assertNear(18f, survey.area?.value, AREA)
        assertNear(22f, survey.perimeter?.value, 0.05f)
        assertNear(6f, survey.length?.value)
        assertNear(5f, survey.width?.value)
        assertTrue(survey.gaps.isEmpty())
    }

    @Test
    fun `a wall square to no other is drawn as it stands, and the area it cuts is measured`() {
        // 5 x 4 m with a corner cut by a wall from (5, 2.5) to (3.5, 4).
        val room = Room()
            .slab(0f, 3.5f, 0f, 4f, 0f)
            .slab(3.5f, 5f, 0f, 2.5f, 0f)
            .slab(0f, 5f, 0f, 4f, HEIGHT, up = false)
            .wallAcross(0f, 0f, 4f, 1f)
            .wallAcross(5f, 0f, 2.5f, -1f)
            .wallAlong(0f, 0f, 5f, 1f)
            .wallAlong(4f, 0f, 3.5f, -1f)
            .wallBetween(3.5f, 4f, 5f, 2.5f)
            .camera(2f, 2f)
        // The floor of the cut corner, up to the wall that cuts it.
        var a = 3.5f
        while (a < 5f) {
            room.slab(a, a + STEP * 2, 2.5f, 7.5f - a, 0f)
            a += STEP * 2
        }
        val survey = room.survey()

        // 20 m² less the cut corner's 1.125 m², the oblique wall 2.12 m long.
        assertFalse(SurveyGap.ObliqueWall in survey.gaps)
        assertTrue(survey.gaps.isEmpty())
        assertTrue(survey.hasPlan)
        assertEquals(5, survey.walls.size)
        assertNear(18.875f, survey.area?.value, AREA)
        assertFalse(survey.area!!.estimate)
        assertNear(17.12f, survey.perimeter?.value, 0.05f)
        assertTrue(survey.walls.any { abs(it.length.value - 2.121f) < 0.05f && it.seen })
    }

    @Test
    fun `a scan with no camera path is read around what it saw`() {
        val survey = Room().box(camera = false).survey()

        assertEquals(4, survey.walls.size)
        assertNear(20f, survey.area?.value, AREA)
        assertNear(2.5f, survey.ceilingHeight?.value)
    }

    @Test
    fun `a room with more than 24 walls keeps every one`() {
        // 8 x 3 m with six alcoves, 0.75 m wide and 0.8 m deep, along one side.
        val room = Room()
            .slab(0f, 8f, 0f, 3f, 0f)
            .slab(0f, 8f, 0f, 3f, HEIGHT, up = false)
            .wallAlong(0f, 0f, 8f, 1f)
            .wallAcross(0f, 0f, 3f, 1f)
            .wallAcross(8f, 0f, 3f, -1f)
        var from = 0f
        for (i in 0 until 6) {
            val a = 0.5f + i * 1.25f
            room.wallAlong(3f, from, a, -1f)
                .slab(a, a + 0.75f, 3f, 3.8f, 0f)
                .slab(a, a + 0.75f, 3f, 3.8f, HEIGHT, up = false)
                .wallAcross(a, 3f, 3.8f, 1f)
                .wallAcross(a + 0.75f, 3f, 3.8f, -1f)
                .wallAlong(3.8f, a, a + 0.75f, -1f)
            from = a + 0.75f
        }
        val survey = room.wallAlong(3f, from, 8f, -1f).camera(4f, 1.5f).survey()

        assertEquals(28, survey.walls.size)
        assertEquals("AB", survey.walls.last().tag)
        assertEquals(28, survey.walls.map { it.tag }.toSet().size)
        assertNear(27.6f, survey.area?.value, AREA)
        assertNear(31.6f, survey.perimeter?.value, 0.1f)
        // Every one has its row, and none is lost when the rows are cut into a PDF's columns and pages.
        val rows = SurveyReport.wallRows(survey, RoomPlanLayoutTest.WORDING)
        assertEquals(28, rows.size)
        val pages = SurveyReport.pages(rows, first = 24, following = 160)
        assertEquals(listOf(24, 4), pages.map { it.size })
        assertEquals(rows.map { it.label }, pages.flatten().map { it.label })
    }

    @Test
    fun `a scan with no depth or no wall says so and gives nothing`() {
        val noDepth = RoomSurveyor.survey(RoomSurveyInput(FloatArray(0), null))
        assertEquals(SurveyGap.NoDepth, noDepth.gaps.first())
        assertFalse(noDepth.hasPlan)

        val floorOnly = Room().slab(0f, 5f, 0f, 4f, 0f).camera(2.5f, 2f).survey()
        assertEquals(SurveyGap.NoWalls, floorOnly.gaps.first())
        assertNull(floorOnly.area)
        assertFalse(floorOnly.hasPlan)
    }

    @Test
    fun `an outline no wall was seen along is the floor's extent, and is not drawn`() {
        // The floor, and a short piece of one wall: under half of that side.
        val survey = Room().slab(0f, 5f, 0f, 4f, 0f).wallAlong(0f, 0f, 1.5f, 1f).camera(2.5f, 2f).survey()

        assertFalse(survey.hasPlan)
        assertEquals(SurveyGap.NoWalls, survey.gaps.first())
        assertNull(survey.area)
        assertNull(survey.length)
        assertNull(survey.width)
    }

    @Test
    fun `walls are named A to Z, then AA`() {
        assertEquals("A", RoomSurveyor.tagOf(0))
        assertEquals("Z", RoomSurveyor.tagOf(25))
        assertEquals("AA", RoomSurveyor.tagOf(26))
    }

    @Test
    fun `a survey whose thread is interrupted stops instead of running to its end`() {
        // A measure nobody waits for any more is interrupted: it has to give its thread back.
        val room = Room().box().input()
        Thread.currentThread().interrupt()
        val ended = try {
            runCatching { RoomSurveyor.survey(room) }.exceptionOrNull()
        } finally {
            Thread.interrupted()
        }
        assertTrue("the survey ran to its end: $ended", ended is InterruptedException)
    }

    private companion object {
        const val STEP = 0.025f
        const val RISE = 0.04f
        const val HEIGHT = 2.5f

        /** A noise-free cloud gives its area to the nearest cell along each wall. */
        const val AREA = 0.1f
    }
}
