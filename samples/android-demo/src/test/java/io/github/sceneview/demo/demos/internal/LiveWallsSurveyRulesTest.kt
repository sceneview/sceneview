package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The live walls (3D Scan and Measure) follow the room survey's rules: a vertical plane is a wall
 * only when it rises past standing height and is seen over 0.40 m of its height (or stands over
 * furniture before it), is seen long enough, along one of the room's directions, and is not a piece
 * of furniture before a wall. Each scene counts the walls shown before
 * these rules ([LegacyPlaneWalls], the tracker as it was) and after ([PlaneWallSource]).
 */
class LiveWallsSurveyRulesTest {
    private val floorY = -1.4f

    /** A vertical plane from (x0, z0) to (x1, z1), [bottom] to [top] metres above the floor. */
    private fun plane(
        id: Int,
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        bottom: Float = 0.1f,
        top: Float = 2f,
    ) = LivePlane(
        id,
        LivePlane.Kind.Wall,
        floatArrayOf(
            x0, floorY + bottom, z0, x1, floorY + bottom, z1,
            x1, floorY + top, z1, x0, floorY + top, z0,
        ),
    )

    /** A vertical plane [length] long, centred on ([cx], [cz]), [degrees] off the x axis. */
    private fun turned(id: Int, cx: Float, cz: Float, length: Float, degrees: Float, top: Float = 2f): LivePlane {
        val a = degrees * PI.toFloat() / 180f
        val hx = cos(a) * length / 2f
        val hz = sin(a) * length / 2f
        return plane(id, cx - hx, cz - hz, cx + hx, cz + hz, top = top)
    }

    private fun ceiling(id: Int, height: Float) = LivePlane(
        id,
        LivePlane.Kind.Ceiling,
        floatArrayOf(
            -1f, floorY + height, -1f, 1f, floorY + height, -1f,
            1f, floorY + height, 1f, -1f, floorY + height, 1f,
        ),
    )

    /** A horizontal plane facing up, [height] metres above the floor, over x0..x1 by z0..z1: a seat, a top. */
    private fun upward(id: Int, x0: Float, z0: Float, x1: Float, z1: Float, height: Float): LivePlane {
        val y = floorY + height
        return LivePlane(id, LivePlane.Kind.Floor, floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1))
    }

    // The room: a front wall 4 m long and a left wall 3.6 m long, both seen up to 2.3 m.
    private val front = plane(1, -2f, -2.6f, 2f, -2.6f, top = 2.3f)
    private val left = plane(2, -2f, -2.6f, -2f, 1f, top = 2.3f)

    private fun before(planes: List<LivePlane>): Int = LegacyPlaneWalls().update(planes, floorY).size

    private fun after(planes: List<LivePlane>): Int = PlaneWallSource().update(planes, floorY, 1f).walls.size

    /** [planes] showed [before] walls before the rules, and show [after] with them. */
    private fun assertWalls(planes: List<LivePlane>, before: Int, after: Int) {
        assertEquals("walls before the rules", before, before(planes))
        assertEquals("walls with the rules", after, after(planes))
    }

    @Test
    fun `a sofa's front is not a wall`() {
        // Pixel 9 QA: a vertical plane from 0.3 m to 0.8 m over the floor, 1.5 m long.
        assertWalls(listOf(front, left, plane(10, -1.5f, -1f, 0f, -1f, bottom = 0.3f, top = 0.8f)), before = 3, after = 2)
    }

    @Test
    fun `a kitchen worktop and a table football table are not walls`() {
        val worktop = plane(11, -1f, -2f, 1f, -2f, bottom = 0.05f, top = 0.9f)
        val table = plane(12, 0.5f, -0.5f, 1.7f, -0.5f, bottom = 0.2f, top = 0.9f)
        assertWalls(listOf(front, left, worktop, table), before = 4, after = 2)
    }

    @Test
    fun `a shelf's front is not a wall`() {
        assertWalls(listOf(front, left, plane(13, -1.6f, -1f, -1.6f, 0.2f, bottom = 0.05f, top = 0.9f)), before = 3, after = 2)
    }

    @Test
    fun `a band seen over less than 0,40 m high is not a wall, as in the survey`() {
        // The survey's wall is seen over 0.40 m of its height at least: a shelf's lip, a valance.
        assertWalls(listOf(front, left, plane(14, 0f, -1f, 1.5f, -1f, bottom = 1.5f, top = 1.85f)), before = 3, after = 2)
    }

    @Test
    fun `a panel seen from 0,6 m up, nothing behind it, is a wall, as in the survey`() {
        // Its foot is not looked for, as the survey does not look for it: a room divider, a screen
        // standing in the room with no wall within a metre behind it, is a wall there and here alike.
        assertWalls(listOf(front, left, plane(14, 0f, -1f, 1.5f, -1f, bottom = 0.6f, top = 2f)), before = 3, after = 3)
    }

    @Test
    fun `a wall whose foot unplaned furniture hides is a wall, as in the survey`() {
        // The back wall shows from 0.9 m up only, over a sofa ARCore fitted no plane to.
        val back = plane(26, -2f, 1f, 1f, 1f, bottom = 0.9f, top = 2.4f)
        assertWalls(listOf(front, left, back), before = 3, after = 3)
    }

    @Test
    fun `a plane reaching no higher than 1,25 m is not a wall, as in the survey`() {
        // The survey's wall rises past 1.30 m: a plane short of it is furniture there and here alike.
        assertWalls(listOf(front, left, plane(25, 0f, -1f, 1.5f, -1f, top = 1.25f)), before = 3, after = 2)
    }

    @Test
    fun `a wall seen only above a sofa before it is a wall, as in the survey`() {
        // The back wall shows from 0.9 m up, over a sofa 0.9 m tall standing 0.8 m before it.
        val back = plane(26, -2f, 1f, 1f, 1f, bottom = 0.9f, top = 2.4f)
        val sofa = plane(27, -1.5f, 0.2f, 0.5f, 0.2f, bottom = 0.05f, top = 0.9f)
        assertWalls(listOf(front, left, back, sofa), before = 4, after = 3)
        // ARCore found the sofa's top rather than its front: the same.
        val top = upward(28, -1.5f, 0.2f, 0.5f, 1f, height = 0.9f)
        assertEquals("over the sofa's top", 3, after(listOf(front, left, back, top)))
        // Seen over less than 0.40 m of its height, it is a wall only with the sofa before it.
        val band = plane(26, -2f, 1f, 1f, 1f, bottom = 2f, top = 2.3f)
        assertEquals("a band over the sofa", 3, after(listOf(front, left, band, sofa)))
        assertEquals("the same band with nothing before it", 2, after(listOf(front, left, band)))
    }

    @Test
    fun `a tall wardrobe and its side are not walls`() {
        val wardrobe = plane(15, -1.5f, -2f, -0.5f, -2f, bottom = 0.02f, top = 1.9f)
        val side = plane(16, -0.5f, -2.6f, -0.5f, -2f, bottom = 0.02f, top = 1.9f)
        assertWalls(listOf(front, left, wardrobe, side, ceiling(3, 2.5f)), before = 4, after = 2)
    }

    @Test
    fun `a bookcase against a wall is that wall`() {
        val bookcase = plane(17, -1.7f, -1.5f, -1.7f, -0.3f, bottom = 0f, top = 2f)
        assertWalls(listOf(front, left, bookcase), before = 3, after = 2)
    }

    @Test
    fun `two planes 2 degrees apart on the same side are one wall`() {
        // Pixel 9 QA: two nearly parallel walls on one side, 30 cm apart.
        assertWalls(listOf(front, left, turned(18, 0.7f, -2.3f, 2.4f, 2f)), before = 3, after = 2)
    }

    @Test
    fun `a piece 19 degrees off a wall, along it, is part of that wall`() {
        val host = plane(1, -2f, -2.6f, 0.5f, -2.6f, top = 2.3f)
        val shard = turned(19, 1f, -2.6f, 1.2f, 19f)
        assertWalls(listOf(host, left, shard), before = 3, after = 2)
        val wall = PlaneWallSource().update(listOf(host, left, shard), floorY, 1f).walls
            .first { abs(it.az - it.bz) < 0.1f }
        assertTrue("the wall reaches the shard's far end: ${maxOf(wall.ax, wall.bx)}", maxOf(wall.ax, wall.bx) > 1.4f)
    }

    @Test
    fun `a short piece 19 degrees off the room is not a wall`() {
        assertWalls(listOf(front, left, turned(20, 0f, -0.5f, 0.8f, 19f, top = 1.8f)), before = 3, after = 2)
    }

    @Test
    fun `a wall seen only in part, a jog and a corridor are walls`() {
        val partial = plane(21, 2f, -2.6f, 2f, -1.8f, top = 1.5f)
        assertWalls(listOf(front, left, partial), before = 3, after = 3)
        val jogged = plane(1, -2f, -2.6f, 0f, -2.6f, top = 2.3f)
        val jog = plane(22, 0f, -2.3f, 2f, -2.3f, top = 2.3f)
        assertWalls(listOf(jogged, jog, left), before = 3, after = 3)
        val corridor = plane(23, -2f, -1.5f, 1.5f, -1.5f, top = 2f)
        assertWalls(listOf(front, left, corridor), before = 3, after = 3)
    }

    @Test
    fun `a wall no plane holds up any more goes`() {
        val legacy = LegacyPlaneWalls()
        val source = PlaneWallSource()
        fun counts(planes: List<LivePlane>, time: Float) =
            legacy.update(planes, floorY).size to source.update(planes, floorY, time).walls.size
        val piece = plane(24, -1f, -1f, 0.5f, -1f, bottom = 0.1f, top = 1.5f)
        assertEquals(3 to 3, counts(listOf(front, left, piece), 1f))
        // ARCore refits it to what it was, a sofa's front: no plane holds the wall up.
        val refit = plane(24, -1f, -1f, 0.5f, -1f, bottom = 0.3f, top = 0.8f)
        assertEquals(3 to 2, counts(listOf(front, left, refit), 2f))
        // And when the plane itself goes, so does the wall.
        assertEquals(3 to 3, counts(listOf(front, left, piece), 3f))
        assertEquals(3 to 2, counts(listOf(front, left), 4f))
    }

    @Test
    fun `the walls are drawn to the ceiling seen, else 2,50 m, never to the top of a plane`() {
        val low = listOf(plane(1, -2f, -2.6f, 2f, -2.6f, top = 1.6f), plane(2, -2f, -2.6f, -2f, 1f, top = 1.6f))
        val legacy = LegacyPlaneWalls().apply { update(low, floorY) }
        assertEquals("before: the top of the highest plane", 1.6f, legacy.height(floorY)!!, 0.01f)
        assertEquals(2.5f, PlaneWallSource().update(low, floorY, 1f).height, 0.01f)
        val high = listOf(plane(1, -2f, -2.6f, 2f, -2.6f, top = 2.8f), left)
        assertEquals(2.5f, PlaneWallSource().update(high, floorY, 1f).height, 0.01f)
        val seen = PlaneWallSource().update(low + ceiling(3, 2.6f), floorY, 1f)
        assertEquals(2.6f, seen.height, 0.01f)
        assertTrue(seen.ceilingSeen)
    }

    @Test
    fun `a ceiling lower than the survey looks for is not the room's`() {
        // Under 2 m over the floor the survey looks for no ceiling: a shelf seen from below.
        val shelf = PlaneWallSource().update(listOf(front, left, ceiling(3, 1.9f)), floorY, 1f)
        assertEquals(2.5f, shelf.height, 0.01f)
        assertTrue(!shelf.ceilingSeen)
    }

    @Test
    fun `with no ceiling seen, the walls are drawn to the height measured in the session`() {
        val low = listOf(plane(1, -2f, -2.6f, 2f, -2.6f, top = 1.6f), plane(2, -2f, -2.6f, -2f, 1f, top = 1.6f))
        val source = PlaneWallSource()
        source.measuredHeight = 2.7f
        assertEquals(2.7f, source.update(low, floorY, 1f).height, 0.01f)
        // A ceiling seen wins over one measured.
        assertEquals(2.6f, source.update(low + ceiling(3, 2.6f), floorY, 2f).height, 0.01f)
        // A new capture in the same session keeps what was measured.
        source.reset()
        assertEquals(2.7f, source.update(low, floorY, 3f).height, 0.01f)
        // Cleared, with the same planes: the default.
        source.measuredHeight = null
        assertEquals(2.5f, source.update(low, floorY, 4f).height, 0.01f)
    }

    @Test
    fun `furniture is strictly lower than the wall behind it, as the survey cuts`() {
        // A wall up to 0.4 m world height, furniture up to 0.2 m: exactly the cut apart, no rounding.
        val cut = WallRules.FURNITURE_UNDER_M
        val wall = Segment(0f, 0f, 0f, -2f, 2f, top = 2 * cut)
        fun front(top: Float) = Segment(0f, 0f, 0.5f, -1.5f, 1.5f, top = top)
        fun side(top: Float) = Segment(PI.toFloat() / 2f, 1.5f, 0.25f, -0.25f, 0.25f, top = top)
        fun panel(top: Float) = floatArrayOf(-1.5f, 0f, 0.5f, 1.5f, 0f, 0.5f, 1.5f, top, 0.5f, -1.5f, top, 0.5f)
        val ceilingY = 10f
        // Exactly the cut lower: a wall still, for each of the three rules.
        assertEquals(null, WallRules.furnitureTop(wall, panel(cut)))
        assertTrue(!WallRules.standsBefore(front(cut), wall, ceilingY))
        assertTrue(!WallRules.isSideOf(side(cut), front(cut), wall))
        // A centimetre lower: furniture.
        val lower = cut - 0.01f
        assertEquals(lower, WallRules.furnitureTop(wall, panel(lower))!!, 0f)
        assertTrue(WallRules.standsBefore(front(lower), wall, ceilingY))
        assertTrue(WallRules.isSideOf(side(lower), front(lower), wall))
    }
}
