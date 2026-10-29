package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * How close Room mode comes to the true room: synthetic rooms traced corner by corner through
 * the reticle's real snapper ([measureSnapAim]), the board and the readout's figures, with the
 * noise of a hand-held phone.
 *
 * - Rooms: a 4 × 3 m rectangle, an L and a room with a 95° corner (and so an 85° one).
 * - Each trace starts at a random corner, either way round, in a random spot and heading.
 * - Every aim lands within 1 cm of the corner, and the tracking drifts 2 mm per metre walked,
 *   in one direction (the worst case for an outline).
 * - With wall planes, every wall is a tracked vertical plane off by 1–2 cm, its ends by up to
 *   2 cm, seen with the drift of the moment the trace reaches it. Without, nothing but the
 *   aim and the placed points snaps — a bare white wall ARCore does not track.
 * - The ceiling is locked at 2.5 m ± 1 cm.
 *
 * The target is a median error of 3 % at most and a maximum of 5 %, on the floor area, the
 * perimeter, the wall (paint) area and the volume, for every case. The square snap takes 6° only
 * when a tracked wall confirms the right angle, 3° otherwise: the 95° room's 95° and 85° corners
 * stay as they are traced, with wall planes or without.
 *
 * The figures of each case are in its name, so they read in the test log.
 */
@RunWith(Parameterized::class)
class MeasurePrecisionTest(private val name: String, private val case: PrecisionCase) {

    @Test
    fun `room figures stay within their error bounds`() {
        assertEquals("$name: traces that did not close where they started", 0, case.unclosed)
        assertTrue("$name: plan and readout disagree by ${case.planMismatch}", case.planMismatch < PLAN_MATCH)
        for ((figure, errors) in case.errors) {
            assertTrue("$name: $figure median ${errors.median} %", errors.median <= case.bounds.median)
            assertTrue("$name: $figure max ${errors.max} %", errors.max <= case.bounds.max)
        }
    }

    /** Median and maximum relative error over every trace of a case, percent. */
    class Errors(values: List<Float>) {
        val median: Float
        val max: Float = values.max()

        init {
            val sorted = values.sorted()
            val half = sorted.size / 2
            median = if (sorted.size % 2 == 0) (sorted[half - 1] + sorted[half]) / 2f else sorted[half]
        }
    }

    /** The bounds a case is held to, percent. */
    data class Bounds(val median: Float, val max: Float)

    class PrecisionCase(
        val label: String,
        val bounds: Bounds,
        val errors: Map<String, Errors>,
        val unclosed: Int,
        val planMismatch: Float,
    ) {
        override fun toString(): String = label
    }

    private class Room(val label: String, val corners: List<Pair<Float, Float>>)

    companion object {
        private const val TRACES = 200
        private const val CEILING_M = 2.5f
        private const val CEILING_NOISE_M = 0.01f
        private const val AIM_NOISE_M = 0.01f
        private const val DRIFT_PER_METER = 0.002f
        private const val PLANE_OFFSET_MIN_M = 0.01f
        private const val PLANE_OFFSET_MAX_M = 0.02f
        private const val PLANE_END_NOISE_M = 0.02f
        private const val PLANE_BOTTOM_M = 0.1f
        private const val PLANE_TOP_M = 2f
        private const val CAMERA_DISTANCE_M = 1.6f
        private const val PLAN_MATCH = 1e-3f
        private const val TWO_PI = (2 * Math.PI).toFloat()

        private val TARGET = Bounds(median = 3f, max = 5f)

        private val ROOMS = listOf(
            Room("4 x 3 m rectangle", listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f)),
            Room("5 x 4 m L", listOf(0f to 0f, 5f to 0f, 5f to 2f, 3f to 2f, 3f to 4f, 0f to 4f)),
            Room(
                "room with a 95 degree corner",
                listOf(0f to 0f, 4f to 0f, 4f to 3f, -3f * tan(Math.toRadians(5.0)).toFloat() to 3f),
            ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = ROOMS.flatMapIndexed { r, room ->
            listOf(true, false).mapIndexed { p, walls ->
                val case = precisionCase(room, walls, Random(SEED + r * 2L + p))
                arrayOf(case.label, case)
            }
        }

        private const val SEED = 4765L

        private fun precisionCase(room: Room, walls: Boolean, random: Random): PrecisionCase {
            val area = ArrayList<Float>()
            val perimeter = ArrayList<Float>()
            val wallArea = ArrayList<Float>()
            val volume = ArrayList<Float>()
            var unclosed = 0
            var planMismatch = 0f
            repeat(TRACES) {
                val trace = trace(room.corners, walls, random)
                if (trace == null) {
                    unclosed++
                    return@repeat
                }
                area += trace[0]
                perimeter += trace[1]
                wallArea += trace[2]
                volume += trace[3]
                planMismatch = maxOf(planMismatch, trace[4])
            }
            val errors = linkedMapOf(
                "floor area" to Errors(area),
                "perimeter" to Errors(perimeter),
                "wall area" to Errors(wallArea),
                "volume" to Errors(volume),
            )
            val figures = errors.entries.joinToString(", ") { (figure, e) ->
                String.format(Locale.US, "%s %.2f %% median %.2f %% max", figure, e.median, e.max)
            }
            val label = buildString {
                append(room.label)
                append(if (walls) " with wall planes: " else " without wall planes: ")
                append(figures)
            }
            return PrecisionCase(label, TARGET, errors, unclosed, planMismatch)
        }

        /**
         * One trace of [corners] with [walls] planes or none: the errors in percent of the floor
         * area, perimeter, wall area and volume, then how far the plan's area and perimeter are
         * from the readout's (relative). `null` when the outline did not close on its first point.
         */
        @Suppress("LongMethod") // one trace, start to finish, reads best in one place
        private fun trace(corners: List<Pair<Float, Float>>, walls: Boolean, random: Random): FloatArray? {
            val n = corners.size
            val yaw = random.nextFloat() * TWO_PI
            val ox = random.nextFloat() * 4f - 2f
            val oz = random.nextFloat() * 4f - 2f
            val c = cos(yaw)
            val s = sin(yaw)
            val world = corners.map { (x, z) -> Position(x * c - z * s + ox, 0f, x * s + z * c + oz) }
            val trueArea = measureFloorArea(world)
            val truePerimeter = world.indices.sumOf { measureDistanceMeters(world[it], world[(it + 1) % n]).toDouble() }
                .toFloat()

            val start = random.nextInt(n)
            val reverse = random.nextBoolean()
            val order = (0 until n).map { Math.floorMod(if (reverse) start - it else start + it, n) }
            val walked = FloatArray(n + 1)
            for (k in 1..n) walked[k] = walked[k - 1] + measureDistanceMeters(world[order[k - 1]], world[order[k % n]])
            val driftAngle = random.nextFloat() * TWO_PI
            fun drift(k: Int) = DRIFT_PER_METER * walked[k] * cos(driftAngle) to DRIFT_PER_METER * walked[k] * sin(driftAngle)

            val planes = if (walls) {
                (0 until n).map { k -> wallPlane(world[order[k]], world[order[(k + 1) % n]], drift(k), random) }
            } else {
                emptyList()
            }
            val tolerance = measureSnapTolerance(CAMERA_DISTANCE_M)
            var board = MeasureBoard<Position>(mode = MeasureMode.Room)
            for (k in 0..n) {
                val target = world[order[k % n]]
                val (dx, dz) = drift(k)
                val r = AIM_NOISE_M * sqrt(random.nextFloat())
                val theta = random.nextFloat() * TWO_PI
                val aim = Position(target.x + dx + r * cos(theta), 0f, target.z + dz + r * sin(theta))
                val outline = board.active?.points.orEmpty()
                val level = outline.firstOrNull()?.y ?: aim.y
                val snap = measureSnapAim(MeasureMode.Room, planes, level, board.allPoints.distinct(), outline, aim, tolerance)
                val closes = board.active != null && measureClosesOutline(outline, snap.position, tolerance)
                if (closes != (k == n)) return null
                board = board.tap(snap.position, closes)
            }
            board = board.lockHeight(CEILING_M + (random.nextFloat() * 2f - 1f) * CEILING_NOISE_M)
            val figure = board.figures.single()
            val outline = measureFigureOutline(figure.mode, figure.points)
            val numbers = measureFigureNumbers(figure, outline)
            val plan = measureRoomSurvey(outline, figure.height) ?: return null
            fun error(measured: Float, truth: Float) = abs(measured / truth - 1f) * 100f
            val floor = numbers.outlineArea ?: return null
            return floatArrayOf(
                error(floor, trueArea),
                error(numbers.length, truePerimeter),
                error(numbers.wallArea ?: return null, truePerimeter * CEILING_M),
                error(numbers.volume ?: return null, trueArea * CEILING_M),
                maxOf(abs(plan.area!!.value / floor - 1f), abs(plan.perimeter!!.value / numbers.length - 1f)),
            )
        }

        /** The tracked plane of the wall [a]–[b]: off the wall and short or long at its ends, drifted. */
        private fun wallPlane(a: Position, b: Position, drift: Pair<Float, Float>, random: Random): MeasurePlane {
            val length = measureDistanceMeters(a, b)
            val ux = (b.x - a.x) / length
            val uz = (b.z - a.z) / length
            val sign = if (random.nextBoolean()) 1f else -1f
            val offset = sign * (PLANE_OFFSET_MIN_M + random.nextFloat() * (PLANE_OFFSET_MAX_M - PLANE_OFFSET_MIN_M))
            val endA = (random.nextFloat() * 2f - 1f) * PLANE_END_NOISE_M
            val endB = (random.nextFloat() * 2f - 1f) * PLANE_END_NOISE_M
            val (dx, dz) = drift
            val ax = a.x - ux * endA - uz * offset + dx
            val az = a.z - uz * endA + ux * offset + dz
            val bx = b.x + ux * endB - uz * offset + dx
            val bz = b.z + uz * endB + ux * offset + dz
            return MeasurePlane(
                MeasurePlaneKind.Vertical,
                listOf(
                    Position(ax, PLANE_BOTTOM_M, az),
                    Position(bx, PLANE_BOTTOM_M, bz),
                    Position(bx, PLANE_TOP_M, bz),
                    Position(ax, PLANE_TOP_M, az),
                ),
            )
        }
    }
}
